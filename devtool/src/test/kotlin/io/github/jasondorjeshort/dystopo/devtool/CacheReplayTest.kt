package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.devtool.CacheReplay.Event
import io.github.jasondorjeshort.dystopo.devtool.CacheReplay.Kind
import io.github.jasondorjeshort.dystopo.devtool.CacheReplay.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T112: `devtool cache-replay` ([CacheReplay]): the log's paths as the phone's kinds, and the rules it compares. */
class CacheReplayTest {
    private val mb = CacheReplay.MB

    @Test
    fun parsesTheServersRequestLogIntoThePhonesKinds() {
        val log = listOf(
            """{"ts":"2026-10-16T05:27:03.100Z","level":"info","event":"started","port":8712}""",
            """{"ts":"2026-10-16T05:27:04.000Z","level":"info","event":"request","method":"GET","path":"/dem/12/655/1431.png","status":200,"ms":3.1,"bytes":160000,"remote":"127.0.0.1","source":"pack"}""",
            """{"ts":"2026-10-16T05:27:04.200Z","level":"info","event":"request","method":"GET","path":"/contours/12/655/1431.pbf","status":200,"ms":40,"bytes":13000,"remote":"127.0.0.1"}""",
            """{"ts":"2026-10-16T05:27:04.300Z","level":"info","event":"request","method":"GET","path":"/osm/12/655/1431.pbf","status":204,"ms":1,"bytes":-1,"remote":"127.0.0.1"}""",
            """{"ts":"2026-10-16T05:27:04.400Z","level":"info","event":"request","method":"GET","path":"/style.json","status":200,"ms":1,"bytes":9000,"remote":"127.0.0.1"}""",
            """{"ts":"2026-10-16T05:27:04.500Z","level":"info","event":"request","method":"GET","path":"/shade/13/1310/2862.png","status":200,"ms":12,"bytes":30000,"remote":"127.0.0.1"}""",
            "",
        )
        val p = CacheReplay.parse(log.asSequence())
        assertEquals(6, p.lines)
        assertEquals(5, p.requests)
        assertEquals(listOf(Kind.ONLINE, Kind.DERIVED, Kind.DERIVED), p.events.map { it.kind })
        assertEquals(mapOf("status not 200" to 1, "not a tile kind the phone caches" to 1), p.skipped)
        assertEquals(Kind.ONLINE, CacheReplay.kindOf("/glyphs/Noto Sans Regular/0-255.pbf"))
        assertNull(CacheReplay.kindOf("/out/rainier-dem.pmtiles"), "packs are outside the budget")
        // A page load starts a new session (an app restart for --forget-on-restart).
        val two = CacheReplay.parse((log.take(2) + listOf(log[4].replace("/style.json", "/")) + log.drop(5)).asSequence())
        assertEquals(listOf(0, 1), two.events.map { it.session })
    }

    private fun ev(t: Long, kind: Kind, key: String, bytes: Long) = Event(t * 60_000, kind, key, bytes)

    @Test
    fun derivedGoesFirstAndNoKindBelowItsFloor() {
        // 100 MB (a run frees 4 MB below it); floors: online 25 MB, derived 15 MB. Tiles of 1 MB.
        val events = ArrayList<Event>()
        var t = 0L
        for (i in 0 until 30) events += ev(t++, Kind.DERIVED, "c$i", mb)
        for (i in 0 until 71) events += ev(t++, Kind.ONLINE, "d$i", mb) // 101 MB: over
        events += ev(t++, Kind.DERIVED, "c0", mb) // went first (the oldest derived tile)
        events += ev(t++, Kind.DERIVED, "c10", mb) // still there
        for (i in 71 until 91) events += ev(t++, Kind.ONLINE, "d$i", mb)
        val r = CacheReplay.run(events, 100 * mb, Rule.LRU, protectMs = 0)
        val derived = r.perKind.getValue(Kind.DERIVED)
        assertEquals(1L, derived.hits)
        assertEquals(16 * mb, derived.bytesEvicted, "derived went first, down to its floor")
        assertEquals(15 * mb, r.endBytes.getValue(Kind.DERIVED))
        assertEquals(9 * mb, r.perKind.getValue(Kind.ONLINE).bytesEvicted, "then the oldest downloaded tiles")
        assertEquals(0, r.stuckRuns)
        // One LRU over everything, the reference, has no derived-first and no floors.
        val g = CacheReplay.run(events, 100 * mb, Rule.LRU_GLOBAL, protectMs = 0)
        assertEquals(events.size.toLong(), g.all.requests)
        assertTrue(g.endBytes.getValue(Kind.DERIVED) < 15 * mb, "below the floor without one")
    }

    @Test
    fun theViewIsSparedByTime() {
        val events = listOf(ev(0, Kind.ONLINE, "a", 3 * mb), ev(10, Kind.ONLINE, "b", 3 * mb), ev(10, Kind.ONLINE, "c", 3 * mb), ev(10, Kind.ONLINE, "d", 3 * mb))
        val r = CacheReplay.run(events, 8 * mb, Rule.LRU, protectMs = 5 * 60_000)
        // Over at c: a (ten minutes old) goes. Over again at d: b, c and d were just used, so they stay though the cache stays over.
        assertEquals(1L, r.perKind.getValue(Kind.ONLINE).evictions)
        assertEquals(1, r.stuckRuns)
    }

    @Test
    fun theFrequencyScoreKeepsSmallPopularTilesOverALargeOneUsedOnce() {
        val events = ArrayList<Event>()
        var t = 0L
        repeat(3) { for (i in 0 until 4) events += ev(t++, Kind.ONLINE, "small$i", mb / 2) } // 2 MB, used three times
        events += ev(t++, Kind.ONLINE, "big", 7 * mb) // used once, most recently
        events += ev(t++, Kind.ONLINE, "new", mb)
        for (i in 0 until 4) events += ev(t++, Kind.ONLINE, "small$i", mb / 2)
        val lru = CacheReplay.run(events, 9 * mb, Rule.LRU, protectMs = 0)
        val gdsf = CacheReplay.run(events, 9 * mb, Rule.GDSF, protectMs = 0)
        assertTrue(gdsf.all.hits > lru.all.hits, "gdsf ${gdsf.all.hits} vs lru ${lru.all.hits}")
        assertTrue(gdsf.all.byteHitRate <= gdsf.all.hitRate + 0.5)
    }

    @Test
    fun repeatsShiftTimeAndFloorsParse() {
        val one = listOf(ev(0, Kind.ONLINE, "a", 1), ev(1, Kind.ONLINE, "b", 1))
        val two = CacheReplay.repeat(one, 2)
        assertEquals(4, two.size)
        assertTrue(two[2].atMs > two[1].atMs + 3_000_000)
        assertEquals(0.3, CacheReplay.parseFloors("online=0.3").getValue(Kind.ONLINE))
        assertEquals(0.0, CacheReplay.parseFloors("online=0.3").getValue(Kind.DERIVED))
        assertEquals(mapOf("requests" to 2, "tiles" to 2, "bytes" to 2L, "maxHitRate" to 0.0), CacheReplay.workingSet(one)["online"])
    }
}
