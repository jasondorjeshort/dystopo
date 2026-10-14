package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.packs.PackCatalog
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T90: peers kept across restarts: what is written, what comes back, and a damaged file. */
class PeerStoreTest {
    private val sha = "a".repeat(64)

    private val catalog = PackCatalog.parse(
        mapOf(
            "version" to 1, "generatedAt" to "2026-10-13T12:00:00Z",
            "publisher" to mapOf("name" to "Dystopo Pixel 6 3f2a", "device" to "3f2a91c0"),
            "packs" to listOf(
                mapOf(
                    "id" to "rainier", "name" to "rainier", "bytes" to 226815990L, "bounds" to listOf(-122.0, 46.65, -121.5, 47.05),
                    "files" to listOf(
                        mapOf("role" to "dem", "file" to "rainier-dem.pmtiles", "url" to "/peer/file/rainier-dem.pmtiles", "bytes" to 222344963L, "sha256" to sha),
                        mapOf("role" to "outdoors", "file" to "rainier-outdoors.pmtiles", "url" to "/peer/file/rainier-outdoors.pmtiles", "bytes" to 4471027L, "sha256" to "b".repeat(64)),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun aPeerComesBackAsItWasSavedAndMarkedRestored() {
        val p = PeerSources.Peer("10.0.2.16:8841", "7K3MQW2D9XHPT4CA").apply {
            name = "Dystopo Pixel 6 3f2a"
            device = "3f2a91c0"
            nsdName = "Dystopo Pixel 6 3f2a"
            addedAt = 1_791_900_000_000
            helloAt = 1_791_900_000_100
            lastSeenAt = 1_791_900_050_000
            catalogAt = 1_791_900_000_200
            catalog = this@PeerStoreTest.catalog
            areas = listOf(mapOf("id" to "a1", "name" to "Paradise", "kind" to "box"))
        }
        assertEquals("connected", p.state)
        val dir = Files.createTempDirectory("peers").toFile()
        val file = File(dir, PeerSources.STORE_FILE)
        val refused = mapOf("7K3MQW2D9XHPT4CA" to listOf("a:10.0.2.16:8841", "d:3f2a91c0")) // T96
        PeerStore.write(file, PeerStore.encode(listOf(p), listOf("7K3MQW2D9XHPT4CA"), mapOf("10.0.2.16:8841" to "10.0.2.2:8842"), refused))
        val back = PeerStore.read(file)!!
        assertEquals(refused, back.refused, "which phone refused which code survives a restart")
        assertTrue(PeerCodes().apply { restore(back.codes, back.refused) }.isRefused(back.peers.single().phone, back.peers.single().code))
        val q = back.peers.single()
        assertEquals(p.addr, q.addr)
        assertEquals(p.code, q.code)
        assertEquals(p.name, q.name)
        assertEquals(p.device, q.device)
        assertEquals(p.nsdName, q.nsdName)
        assertEquals(p.addedAt, q.addedAt)
        assertEquals(p.lastSeenAt, q.lastSeenAt)
        assertEquals(catalog, q.catalog, "the kept catalog lists the same packs, hashes and publisher")
        assertEquals("a1", q.areas.single()["id"])
        assertTrue(q.restored)
        assertEquals("saved", q.state, "listed before it answers")
        q.error = "connect timed out"
        assertEquals("unreachable", q.state)
        assertEquals(listOf("7K3MQW2D9XHPT4CA"), back.codes)
        assertEquals(mapOf("10.0.2.16:8841" to "10.0.2.2:8842"), back.via)
        assertTrue(!File(file.parentFile, file.name + ".tmp").exists())
        dir.deleteRecursively()
    }

    @Test
    fun aDamagedFileOrEntryIsSkipped() {
        val dir = Files.createTempDirectory("peers").toFile()
        val file = File(dir, "peers.json")
        assertNull(PeerStore.read(file), "no file: no peers")
        file.writeText("{\"version\": 1, \"peers\": [")
        assertNull(PeerStore.read(file), "a truncated file is ignored")
        // One entry without a valid code and one bad code in the list are dropped; the rest is kept.
        file.writeText(
            """{"version": 1, "codes": ["nope", "7K3M-QW2D-9XHP-T4CA"], "peers": [{"addr": "10.0.2.16:8841", "code": "short"},
              {"addr": "10.0.2.20:8841", "code": "7K3MQW2D9XHPT4CA", "catalog": {"version": 1, "packs": []}}]}""",
        )
        val back = PeerStore.read(file)!!
        assertEquals(listOf("10.0.2.20:8841"), back.peers.map { it.addr })
        assertEquals(listOf("7K3MQW2D9XHPT4CA"), back.codes)
        assertEquals(emptyMap(), back.refused, "a T90 file has no refusals")
        assertEquals(0, back.peers.single().catalog!!.packs.size)
        dir.deleteRecursively()
    }

    @Test
    fun theSeenLineSaysWhereAPeerStands() {
        val now = 1_791_900_000_000
        val p = PeerSources.Peer("10.0.2.16:8841", "7K3MQW2D9XHPT4CA").apply { lastSeenAt = now - 3 * 60_000 }
        assertEquals("Connected, last seen 3 min ago", peerSeenLine(p, now))
        p.restored = true
        assertTrue(peerSeenLine(p, now).startsWith("Saved from before"))
        p.error = "connect timed out"
        p.nextRetryAt = now + 30_000
        assertEquals("Not reachable; trying again in 30 s (last seen 3 min ago)", peerSeenLine(p, now))
        p.error = PeerSources.CODE_CHANGED
        assertEquals("codeChanged", p.state, "a kept code that no longer opens is not retried")
        assertTrue(peerSeenLine(p, now).startsWith("Its code changed"))
        p.restored = false
        assertEquals("codeChanged", p.state, "T96: also for a peer that was connected when its code changed")
        // T96 verifier: a download that failed before the peer's state changed is not shown under it.
        p.stateAt = 1_000
        p.setState(null)
        assertEquals("connected", p.state)
        assertTrue(p.stateAt > 1_000, "a state change is noted")
        val changedAt = p.stateAt
        p.setState(null)
        assertEquals(changedAt, p.stateAt, "no change, no new time")
        val failedBefore = mapOf("state" to "failed", "error" to "wrong code for 10.0.2.23:8841", "createdAt" to changedAt - 5_000)
        assertNull(shownJob(failedBefore, p))
        val failedAfter = failedBefore + ("createdAt" to changedAt + 1)
        assertEquals(failedAfter, shownJob(failedAfter, p))
        val running = failedBefore + ("state" to "running")
        assertEquals(running, shownJob(running, p))
        assertEquals("just now", ago(1_000))
        assertEquals("2 h ago", ago(2 * 3_600_000L))
    }
}
