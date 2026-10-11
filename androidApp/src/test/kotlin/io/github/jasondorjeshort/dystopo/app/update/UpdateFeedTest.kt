package io.github.jasondorjeshort.dystopo.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T83: reading the releases API, `version.json` and `SHA256SUMS.txt`, and the "is it newer" decision. */
class UpdateFeedTest {
    private val hexA = "a".repeat(64)
    private val hexB = "0123456789abcdef".repeat(4)

    private fun release(tag: String, draft: Boolean = false, pre: Boolean = true, assets: List<String>) =
        """{"tag_name": "$tag", "draft": $draft, "prerelease": $pre, "assets": [""" +
            assets.joinToString(",") { """{"name": "$it", "size": 1234, "browser_download_url": "https://github.com/jasondorjeshort/dystopo/releases/download/$tag/$it"}""" } +
            "]}"

    // The shape of the real answer: newest first; v0.1.2-alpha and v0.1.3-alpha shipped five APKs and no version.json.
    private val api = "[" + listOf(
        release("v0.1.5-alpha", draft = true, assets = listOf("dystopo-v0.1.5-alpha-universal.apk", "SHA256SUMS.txt")),
        release("v0.1.4-alpha", assets = listOf("dystopo-v0.1.4-alpha-universal.apk", "SHA256SUMS.txt", "version.json", "NOTICES.md")),
        release("v0.1.3-alpha", assets = listOf("dystopo-v0.1.3-alpha-arm64-v8a.apk", "dystopo-v0.1.3-alpha-universal.apk", "SHA256SUMS.txt")),
        release("v0.1.2-alpha", assets = listOf("dystopo-v0.1.2-alpha-x86_64.apk", "dystopo-v0.1.2-alpha-universal.apk", "SHA256SUMS.txt")),
    ).joinToString(",") + "]"

    @Test
    fun parsesTheReleasesAnswer() {
        val rs = UpdateFeed.parseReleases(api)
        assertEquals(listOf("v0.1.5-alpha", "v0.1.4-alpha", "v0.1.3-alpha", "v0.1.2-alpha"), rs.map { it.tag })
        assertTrue(rs[0].draft)
        assertEquals("dystopo-v0.1.3-alpha-universal.apk", rs[2].universalApk?.name)
        assertEquals("https://github.com/jasondorjeshort/dystopo/releases/download/v0.1.4-alpha/version.json", rs[1].versionJson?.url)
        assertNull(rs[2].versionJson)
        assertNotNull(rs[2].sums)
        assertFailsWith<IllegalArgumentException> { UpdateFeed.parseReleases("""{"message": "API rate limit exceeded"}""") }
    }

    @Test
    fun picksTheNewestPublishedReleaseWithAUniversalApk() {
        val rs = UpdateFeed.parseReleases(api)
        assertEquals("v0.1.4-alpha", UpdateFeed.pick(rs)?.tag, "drafts are skipped")
        assertEquals("v0.1.3-alpha", UpdateFeed.pick(rs.drop(2))?.tag)
        // Out of order in the list: the highest tag wins.
        assertEquals("v0.1.4-alpha", UpdateFeed.pick(listOf(rs[3], rs[1], rs[2]))?.tag)
        // No universal APK: nothing to offer.
        val perAbi = UpdateFeed.parseReleases("[" + release("v0.2.0-beta", pre = false, assets = listOf("dystopo-v0.2.0-beta-arm64-v8a.apk")) + "]")
        assertNull(UpdateFeed.pick(perAbi))
        assertNull(UpdateFeed.pick(rs.drop(1), includePrereleases = false))
        assertNull(UpdateFeed.pick(emptyList()))
    }

    @Test
    fun versionJsonAndSums() {
        val v = UpdateFeed.parseVersionJson("""{"tag": "v0.1.4-alpha", "versionCode": 240, "sha256": "${hexB.uppercase()}"}""")
        assertEquals(UpdateFeed.VersionInfo("v0.1.4-alpha", 240, hexB), v)
        assertEquals(241L, UpdateFeed.parseVersionJson("""{"versionCode": "241"}""").versionCode)
        assertFailsWith<IllegalArgumentException> { UpdateFeed.parseVersionJson("""{"tag": "v1"}""") }
        assertFailsWith<IllegalArgumentException> { UpdateFeed.parseVersionJson("""{"versionCode": 3, "sha256": "abc"}""") }

        val sums = UpdateFeed.parseSums("$hexA  dystopo-v0.1.4-alpha-universal.apk\n$hexB *NOTICES.md\r\n\nnot a line\nzz  bad.apk\n")
        assertEquals(mapOf("dystopo-v0.1.4-alpha-universal.apk" to hexA, "NOTICES.md" to hexB), sums)
    }

    @Test
    fun versionNumbersCompareAsVersions() {
        assertEquals(listOf(0, 1, 3), UpdateFeed.versionNumbers("v0.1.3-alpha"))
        assertEquals(listOf(0, 1, 0), UpdateFeed.versionNumbers("0.1.0-dev"))
        // A sweep build: git describe plus -sweep.
        assertEquals(listOf(0, 1, 3), UpdateFeed.versionNumbers("0.1.3-alpha-12-g1a2b3c4-sweep"))
        assertNull(UpdateFeed.versionNumbers("1a2b3c4"))
        assertTrue(UpdateFeed.compareNumbers(listOf(0, 1, 10), listOf(0, 1, 3)) > 0)
        assertTrue(UpdateFeed.compareNumbers(listOf(0, 2), listOf(0, 1, 9)) > 0)
        assertEquals(0, UpdateFeed.compareNumbers(listOf(1), listOf(1, 0, 0)))
        assertEquals("0.1.4-alpha", UpdateFeed.versionName("v0.1.4-alpha"))
    }

    @Test
    fun newerByVersionCodeWhenTheReleaseSaysSo() {
        val r = UpdateFeed.parseReleases(api)[1]
        val info = UpdateFeed.VersionInfo("v0.1.4-alpha", 240, hexA)
        assertTrue(UpdateFeed.verdict(r, info, ownCode = 220, ownName = "0.1.3-alpha").newer)
        // Equal or lower code: quiet, whatever the names say.
        assertFalse(UpdateFeed.verdict(r, info, ownCode = 240, ownName = "0.1.3-alpha").newer)
        assertFalse(UpdateFeed.verdict(r, info, ownCode = 250, ownName = "0.1.0-dev").newer)
        assertEquals("versionCode", UpdateFeed.verdict(r, info, 1, "x").by)
    }

    @Test
    fun theDefaultFeedTalksOnlyToGithub() {
        for (ok in listOf(
            "https://api.github.com/repos/jasondorjeshort/dystopo/releases?per_page=20",
            "https://github.com/jasondorjeshort/dystopo/releases/download/v0.1.3-alpha/SHA256SUMS.txt",
            "https://objects.githubusercontent.com/github-production-release-asset/1/2?X-Amz-Signature=x",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/2",
            "https://GitHub.com./x",
        )) assertNull(UpdateFeed.githubUrlProblem(ok), ok)
        for (bad in listOf(
            "http://github.com/x", // not https
            "https://github.com.evil.example/x",
            "https://evilgithub.com/x",
            "https://githubusercontent.com.evil.example/x",
            "https://.githubusercontent.com/x",
            "https://githubusercontent.com/x",
            "https://user:pw@github.com/x",
            "https://10.0.2.2:9171/repos/x",
            "file:///sdcard/x.apk",
            "not a url at all",
        )) assertNotNull(UpdateFeed.githubUrlProblem(bad), bad)
        assertTrue(UpdateFeed.githubUrlProblem("https://example.com/a.apk")!!.contains("not a GitHub host"))
    }

    private val ours = listOf("47313810a4313ca3" + "0".repeat(48))
    private fun apk(pkg: String = "io.github.jasondorjeshort.dystopo", code: Long = 230, signers: List<String> = ours) =
        UpdateFeed.ApkFacts(pkg, code, "0.1.4", signers)

    @Test
    fun verifyAcceptsOnlyOurPackageNewerAndSameKey() {
        val own = "io.github.jasondorjeshort.dystopo"
        assertNull(UpdateFeed.apkProblem(apk(), own, 229, ours))
        assertTrue(UpdateFeed.apkProblem(null, own, 229, ours)!!.contains("not an APK"))
        assertTrue(UpdateFeed.apkProblem(apk(pkg = "com.example.other"), own, 229, ours)!!.contains("another app"))
        assertTrue(UpdateFeed.apkProblem(apk(code = 229), own, 229, ours)!!.contains("not newer"), "same versionCode")
        assertTrue(UpdateFeed.apkProblem(apk(code = 203), own, 229, ours)!!.contains("not newer"), "a downgrade")
        assertTrue(UpdateFeed.apkProblem(apk(signers = emptyList()), own, 229, ours)!!.contains("could not be read"))
        assertNotNull(UpdateFeed.apkProblem(apk(), own, 229, emptyList()), "our own certificate unreadable")
        val debug = listOf("a5f26ea7b4453ea2" + "1".repeat(48))
        val wrong = UpdateFeed.apkProblem(apk(signers = debug), own, 229, ours)!!
        assertTrue("different certificate (a5f26ea7b4453ea2, this app 47313810a4313ca3)" in wrong, wrong)
        // Exactly the same set: an extra signer, or one of two, is refused; order does not matter.
        assertNotNull(UpdateFeed.apkProblem(apk(signers = ours + debug), own, 229, ours))
        assertNotNull(UpdateFeed.apkProblem(apk(signers = ours), own, 229, ours + debug))
        assertNull(UpdateFeed.apkProblem(apk(signers = debug + ours), own, 229, ours + debug))
        // Same 16-digit prefix but a different certificate: compared on the full fingerprint.
        assertNotNull(UpdateFeed.apkProblem(apk(signers = listOf("47313810a4313ca3" + "f".repeat(48))), own, 229, ours))
    }

    @Test
    fun olderReleasesAreComparedByTag() {
        val v013 = UpdateFeed.parseReleases(api)[2]
        // The published 0.1.2 build sees 0.1.3 as newer.
        assertTrue(UpdateFeed.verdict(v013, null, ownCode = 203, ownName = "0.1.2-alpha").newer)
        // The same version, or a sweep build described from it: quiet.
        assertFalse(UpdateFeed.verdict(v013, null, ownCode = 220, ownName = "0.1.3-alpha").newer)
        assertFalse(UpdateFeed.verdict(v013, null, ownCode = 231, ownName = "0.1.3-alpha-11-gabcdef0-sweep").newer)
        // A newer build than the release: quiet.
        assertFalse(UpdateFeed.verdict(v013, null, ownCode = 240, ownName = "0.1.4-alpha").newer)
        assertEquals("tag", UpdateFeed.verdict(v013, null, 1, "0.1.0-dev").by)
    }
}
