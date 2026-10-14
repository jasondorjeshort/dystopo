package io.github.jasondorjeshort.dystopo.app.update

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.util.Locale

/**
 * T83: what the app reads from the GitHub releases API, without Android: the release list, the newest release
 * that carries a universal APK, its `version.json` (written by `release.yml` from the next alpha on: tag,
 * versionCode, sha256 of the universal APK) and its `SHA256SUMS.txt`, and whether that release is newer than
 * the running build. [Updater] does the networking and the Android parts; this file is what the unit tests cover.
 *
 * Newer means: the release's `version.json` versionCode is above ours; a release without one (v0.1.2-alpha and
 * v0.1.3-alpha) is compared by the version in its tag (`v0.1.4-alpha` -> 0.1.4) against our version name, and is
 * newer only when its numbers are strictly higher. Either way the downloaded APK's own versionCode must be higher
 * than ours before it is offered ([Updater.verify]), so a wrong tag cannot offer an older build.
 */
object UpdateFeed {
    /** The public API of the repository's releases (no token: the repository is public). Pre-releases included during alpha. */
    const val DEFAULT_API = "https://api.github.com/repos/jasondorjeshort/dystopo/releases?per_page=20"

    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(val tag: String, val draft: Boolean, val prerelease: Boolean, val assets: List<Asset>) {
        fun asset(name: String): Asset? = assets.firstOrNull { it.name == name }

        /** The alpha's one APK, `dystopo-<tag>-universal.apk` (any `dystopo-*-universal.apk` name is accepted). */
        val universalApk: Asset? get() = asset("dystopo-$tag-universal.apk") ?: assets.firstOrNull { UNIVERSAL.matches(it.name) }
        val sums: Asset? get() = asset(SUMS)
        val versionJson: Asset? get() = asset(VERSION_JSON)
    }

    /** `version.json` of a release. */
    data class VersionInfo(val tag: String?, val versionCode: Long, val sha256: String?)

    const val SUMS = "SHA256SUMS.txt"
    const val VERSION_JSON = "version.json"
    const val DAY_MS = 24L * 3600 * 1000
    const val RETRY_MS = 3600L * 1000

    /**
     * T99: when the automatic check last finished ([lastCheckAt]) and last started ([lastAttemptAt]), and the
     * versionCode of the build that recorded them ([byCode]; null for a record from before T99, v0.1.5-alpha's
     * among them). A check is due once a day, a failed one again after an hour, but only this build's times count:
     * a build installed over another one (the in-app update, a sideload, `adb install -r`) keeps the old build's
     * preferences, and it checks once at its own first start instead of inheriting the old build's day.
     */
    data class CheckRecord(val lastCheckAt: Long = 0, val lastAttemptAt: Long = 0, val byCode: Long? = null) {
        fun due(now: Long, ownCode: Long): Boolean =
            byCode != ownCode || (now - lastCheckAt >= DAY_MS && now - lastAttemptAt >= RETRY_MS)

        /** A check started at [now] by build [ownCode]; another build's times are dropped. */
        fun attempted(now: Long, ownCode: Long) = own(ownCode).copy(lastAttemptAt = now)

        /** A check finished at [now] by build [ownCode] (any answer: up to date, an offer, a refusal). */
        fun checked(now: Long, ownCode: Long) = own(ownCode).copy(lastCheckAt = now)

        private fun own(ownCode: Long) = if (byCode == ownCode) this else CheckRecord(byCode = ownCode)
    }

    private val UNIVERSAL = Regex("^dystopo-.+-universal\\.apk$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** The releases API answer (a JSON array of releases); entries without a tag are skipped. */
    fun parseReleases(json: String): List<Release> {
        val list = MiniJson.parse(json) as? List<*> ?: throw IllegalArgumentException("the releases API did not answer with a list")
        return list.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val tag = (m["tag_name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val assets = (m["assets"] as? List<*>).orEmpty().mapNotNull { a ->
                val am = a as? Map<*, *> ?: return@mapNotNull null
                val name = am["name"] as? String ?: return@mapNotNull null
                val url = am["browser_download_url"] as? String ?: return@mapNotNull null
                Asset(name, url, (am["size"] as? Number)?.toLong() ?: -1L)
            }
            Release(tag, m["draft"] == true, m["prerelease"] == true, assets)
        }
    }

    /**
     * The release to consider: not a draft, pre-releases included (alpha), with a universal APK; of those the
     * highest tag version (the API lists newest first, which breaks ties). A missing `SHA256SUMS.txt` is refused later.
     */
    fun pick(releases: List<Release>, includePrereleases: Boolean = true): Release? {
        var best: Release? = null
        for (r in releases) {
            if (r.draft || (r.prerelease && !includePrereleases) || r.universalApk == null) continue
            val b = best
            if (b == null || compareNumbers(versionNumbers(r.tag) ?: emptyList(), versionNumbers(b.tag) ?: emptyList()) > 0) best = r
        }
        return best
    }

    /** `version.json`: `{"tag": "v0.1.4-alpha", "versionCode": 240, "sha256": "<64 hex>"}`. */
    fun parseVersionJson(json: String): VersionInfo {
        val m = MiniJson.parseObject(json)
        val code = (m["versionCode"] as? Number)?.toLong() ?: (m["versionCode"] as? String)?.toLongOrNull()
            ?: throw IllegalArgumentException("version.json has no versionCode")
        val sha = (m["sha256"] as? String)?.lowercase(Locale.ROOT)?.trim()
        require(sha == null || HEX64.matches(sha)) { "version.json sha256 is not 64 hex digits" }
        return VersionInfo(m["tag"] as? String, code, sha)
    }

    /** `sha256sum` output: `<64 hex>  <name>` (or `<hex> *<name>`) per line -> name to lower-case hex. */
    fun parseSums(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val hex = line.substringBefore(' ').lowercase(Locale.ROOT)
            val name = line.substringAfter(' ', "").trim().removePrefix("*")
            if (HEX64.matches(hex) && name.isNotEmpty()) out[name] = hex
        }
        return out
    }

    /** The leading numbers of a tag or version name: `v0.1.3-alpha` -> [0, 1, 3]; `0.1.0-dev` -> [0, 1, 0]; null without a dotted number (`1a2b3c4`, a bare commit). */
    fun versionNumbers(tagOrName: String): List<Int>? {
        val m = Regex("^[vV]?(\\d+(?:\\.\\d+)+)").find(tagOrName.trim()) ?: return null
        return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /** Compares number lists as versions (missing parts are 0): 0.1.3 < 0.1.10 < 0.2. */
    fun compareNumbers(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Whether [release] (with its `version.json` when it has one) is newer than the running build. */
    data class Verdict(val newer: Boolean, val by: String, val releaseCode: Long?, val reason: String)

    fun verdict(release: Release, info: VersionInfo?, ownCode: Long, ownName: String): Verdict {
        if (info != null) {
            val newer = info.versionCode > ownCode
            return Verdict(newer, "versionCode", info.versionCode,
                "${release.tag} has versionCode ${info.versionCode}, this build ${ownCode}")
        }
        val theirs = versionNumbers(release.tag) ?: return Verdict(false, "tag", null, "${release.tag}: no version in the tag")
        val ours = versionNumbers(ownName) ?: return Verdict(true, "tag", null, "this build's version '$ownName' has no numbers")
        val newer = compareNumbers(theirs, ours) > 0
        return Verdict(newer, "tag", null, "${release.tag} (no ${VERSION_JSON}) against this build's $ownName")
    }

    /** What the package manager reads from a downloaded APK ([Signing.archive]): package, version and signer fingerprints. */
    data class ApkFacts(val packageName: String, val versionCode: Long, val versionName: String?, val signers: List<String>)

    /**
     * Why a downloaded APK may not be offered, or null: unreadable, another package, not newer than [ownCode], no
     * readable certificate, or signers (SHA-256 fingerprints of the full certificates) not exactly [ownSigners].
     */
    fun apkProblem(apk: ApkFacts?, ownPackage: String, ownCode: Long, ownSigners: List<String>): String? {
        apk ?: return "the download is not an APK this phone can read"
        if (apk.packageName != ownPackage) return "it is another app (${apk.packageName})"
        if (apk.versionCode <= ownCode) return "it is not newer (versionCode ${apk.versionCode}, this build $ownCode)"
        if (apk.signers.isEmpty()) return "its signing certificate could not be read"
        if (ownSigners.isEmpty()) return "this app's own signing certificate could not be read"
        if (apk.signers.sorted() != ownSigners.sorted()) return "it is signed with a different certificate (${apk.signers.joinToString { short(it) }}, this app ${ownSigners.joinToString { short(it) }})"
        return null
    }

    /** First 16 hex digits, as the docs quote the release key (`47313810a4313ca3`). */
    fun short(fingerprint: String?): String = fingerprint?.take(16) ?: "none"

    /** Hosts the default feed may talk to: the API, release pages and the asset CDN GitHub redirects downloads to. */
    val GITHUB_HOSTS = setOf("github.com", "api.github.com", "objects.githubusercontent.com")

    fun isGithubHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        return h in GITHUB_HOSTS || (h.endsWith(".githubusercontent.com") && h.length > ".githubusercontent.com".length)
    }

    /** With the default feed: why [url] may not be fetched (not https, or not a GitHub host), or null. */
    fun githubUrlProblem(url: String): String? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return "not a URL: $url"
        val host = uri.host ?: return "no host in $url"
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return "$url is not https"
        if (uri.userInfo != null) return "$url carries credentials"
        if (!isGithubHost(host)) return "$host is not a GitHub host (only github.com, api.github.com and *.githubusercontent.com)"
        return null
    }

    /** The version name a tag gives the build (`release.yml`: the tag without its v). */
    fun versionName(tag: String): String = tag.removePrefix("v")
}
