package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TileRow
import io.github.jasondorjeshort.dystopo.areas.TileSet
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.PackCoverage
import io.github.jasondorjeshort.dystopo.packs.PackPrecedence
import io.github.jasondorjeshort.dystopo.packs.PackRoleZooms
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T151: interrupted area downloads. The cursor (the rows after it, the tiles through it, its place in the record file),
 * the repair at start, the check's decision, the backoff and the stop, the coalescing of triggers, and the row-wise
 * coverage pieces (the cache's keys as rows, a pack's span on a row, the union of spans) against per-tile references.
 */
class ResumeTest {
    /** A 5 km disc at Paradise at full detail, the task's test area. */
    private val disc = AreaOfInterest(AreaShape.Corridor(listOf(AreaPoint(46.7859, -121.7356)), 5000.0), 8, 15, "Paradise")
    private val items = AreaManifest.tileItems(disc, 8..15, 8..14)

    private fun record(id: String, state: String, createdAt: Long = 1L, cursor: DownloadCursor? = null, cancelled: Boolean = false, auto: AutoResume? = null, pack: String? = null) =
        Areas.Record(id = id, name = id, area = disc, createdAt = createdAt, state = state, source = "sources", cursor = cursor, cancelled = cancelled, autoResume = auto, pack = pack)

    @Test
    fun theCursorWalksWhatIsLeftAndCountsWhatIsBehind() {
        val walk = AreaManifest.walk(items).toList()
        val total = walk.sumOf { it.count.toLong() }
        assertTrue(walk.size > 50, "the disc has rows to resume over (${walk.size})")
        for (k in listOf(0, 1, walk.size / 3, walk.size - 2, walk.size - 1)) {
            val c = DownloadCursor(walk[k], k + 1)
            assertEquals(walk.drop(k + 1), c.after(AreaManifest.walk(items)).toList(), "after row $k")
            assertEquals(total, c.tilesThrough(AreaManifest.walk(items)) + c.after(AreaManifest.walk(items)).sumOf { it.count.toLong() })
        }
        val none = DownloadCursor(null)
        assertEquals(walk, none.after(AreaManifest.walk(items)).toList())
        assertEquals(0L, none.tilesThrough(AreaManifest.walk(items)))
    }

    @Test
    fun theRecordFileKeepsTheCursorTheCancelAndTheResumeState() {
        val failed = mapOf("dem" to mapOf(12 to TileSet(12).apply { addRow(1430, 650, 652); add(660, 1431) }))
        val c = DownloadCursor(TileRow(12, "dem", 1431, 655, 670), 40, failed, savedAt = 5L)
        val auto = AutoResume(failures = 2, attempts = 3, nextAt = 99L, runFrom = c.key, trigger = "foreground")
        val rec = record(disc.id, "partial", cursor = c, cancelled = true, auto = auto)
        val again = AreaFiles.decode(AreaFiles(listOf(rec)).mainText(), null).records.single()
        assertEquals(c.key, again.cursor?.key)
        assertEquals(40, again.cursor?.wholeRows)
        assertEquals(4, again.cursor?.failedTiles)
        assertEquals(listOf(listOf(1430, 650, 652), listOf(1431, 660, 660)), again.cursor?.failed?.get("dem")?.get(12)?.rows())
        assertTrue(again.cancelled)
        assertEquals(auto, again.autoResume)
        // A finished record is written as before (a version-1 reader's file gains no keys).
        val plain = AreaFiles(listOf(record(disc.id, "done"))).mainText()
        assertFalse("cursor" in plain || "cancelled" in plain || "autoResume" in plain, plain)
        assertNull(AreaFiles.decode(plain, null).records.single().cursor)
    }

    @Test
    fun theFailedTilesKeptAreThoseThroughTheCursor() {
        val failed = mapOf(
            "dem" to mapOf(10 to TileSet(10).apply { add(5, 1); add(5, 9) }, 12 to TileSet(12).apply { add(1, 1) }),
            "osm" to mapOf(10 to TileSet(10).apply { add(5, 1) }),
        )
        val kept = failedThrough(failed, TileRow(10, "dem", 5, 0, 9))
        assertEquals(listOf(listOf(1, 5, 5)), kept["dem"]?.get(10)?.rows())
        assertNull(kept["dem"]?.get(12))
        assertNull(kept["osm"])
        assertTrue(failedThrough(failed, null).isEmpty())
        val all = failedThrough(failed, TileRow(20, "osm", 0, 0, 0))
        assertEquals(4, all.values.sumOf { z -> z.values.sumOf { it.size } })
        val merged = mergeFailed(kept, mapOf("dem" to mapOf(10 to TileSet(10).apply { add(6, 1) }), "osm" to mapOf(11 to TileSet(11).apply { add(0, 0) })))
        assertEquals(listOf(listOf(1, 5, 6)), merged["dem"]?.get(10)?.rows())
        assertEquals(1, merged["osm"]?.get(11)?.size)
    }

    @Test
    fun theRepairAtStartTurnsAnInterruptedDownloadPartial() {
        val c = DownloadCursor(TileRow(9, "dem", 180, 80, 82), 7)
        val moved = record("a1", "downloading", cursor = c, auto = AutoResume(runFrom = ""))
        val r = assertNotNull(repairedAtStart(moved, live = null))
        assertEquals("partial", r.state)
        assertEquals(RESUME_INTERRUPTED, r.error)
        assertEquals(0, r.autoResume?.failures)
        assertNull(r.autoResume?.runFrom)
        assertEquals(c.key, r.cursor?.key, "the cursor stays for the resume")
        assertEquals("cancelled", repairedAtStart(record("a2", "downloading", cancelled = true), null)?.state)
        assertNull(repairedAtStart(record("a3", "downloading"), live = "a3"), "a live job is not repaired")
        assertNull(repairedAtStart(record("a4", "partial"), null))
        assertNull(repairedAtStart(record("a5", "downloading", pack = "p"), null), "a pack's area is the region download's")
        // A run that died where it started (a crash at the same row) counts; the fifth in a row stops the resumes.
        var stuck = record("a6", "downloading", cursor = c, auto = AutoResume(runFrom = c.key))
        repeat(ResumeCheck.MAX_FAILURES) { i ->
            stuck = repairedAtStart(stuck, null)!!
            assertEquals(i + 1, stuck.autoResume?.failures)
            stuck = stuck.copy(state = "downloading", autoResume = stuck.autoResume?.copy(runFrom = c.key))
        }
        val last = repairedAtStart(stuck.copy(autoResume = stuck.autoResume?.copy(failures = ResumeCheck.MAX_FAILURES - 1)), null)!!
        assertEquals(AutoResume.STOP_FAILURES, last.autoResume?.stopped)
        assertEquals(RESUME_STOPPED, last.error)
    }

    @Test
    fun theCheckStartsTheOldestReadyAreaAndLeavesTheRest() {
        val now = 1_000_000L
        val records = listOf(
            record("done", "done"),
            record("cancelled", "partial", createdAt = 1, cancelled = true),
            record("stopped", "partial", createdAt = 2, auto = AutoResume(stopped = AutoResume.STOP_FAILURES)),
            record("refused", "partial", createdAt = 3, auto = AutoResume(stopped = AutoResume.STOP_REFUSED, budgetMb = 2048)),
            record("later", "partial", createdAt = 4, auto = AutoResume(nextAt = now + 60_000)),
            record("ready", "partial", createdAt = 6),
            record("older", "partial", createdAt = 5, auto = AutoResume(nextAt = now - 1)),
            record("pack", "partial", pack = "p"),
        )
        val d = decide(records, live = null, now = now, budgetMb = 2048, userPresent = false)
        assertEquals("older", d.start)
        assertEquals(now + 60_000, d.nextAt)
        assertNull(d.waitingFor)
        assertEquals<Map<Any?, Any?>>(
            mapOf("cancelled" to "cancelled", "stopped" to "stopped", "refused" to "refused", "later" to "backoff", "ready" to "ready", "older" to "ready"),
            d.unfinished.associate { it["id"] to it["why"] },
        )
        // The user is there (start, foreground): the backoff's wait is skipped, never the cancel or the stop; a new budget lets the refused one go.
        val present = decide(records, null, now, budgetMb = 4096, userPresent = true)
        assertEquals("ready", present.unfinished.first { it["id"] == "later" }["why"])
        assertEquals("ready", present.unfinished.first { it["id"] == "refused" }["why"])
        assertEquals("refused", present.start, "the oldest ready one")
        // One download at a time: with a job running nothing starts, and the check says what it waits for.
        val busy = decide(records, live = "ready", now = now, budgetMb = 2048, userPresent = true)
        assertNull(busy.start)
        assertEquals("ready", busy.waitingFor)
        assertEquals("running", busy.unfinished.first { it["id"] == "ready" }["why"])
        // Nothing unfinished: nothing to say.
        assertTrue(decide(listOf(record("x", "done")), null, now, 2048, false).unfinished.isEmpty())
    }

    @Test
    fun failuresBackOffAndStopButAStallIsNotCounted() {
        val now = 10_000L
        var a: AutoResume? = null
        val waits = ArrayList<Long>()
        repeat(ResumeCheck.MAX_FAILURES) {
            a = afterRun(a, "partial", progressed = false, stalled = false, now = now, budgetMb = 2048)
            waits += a!!.nextAt - now
        }
        assertEquals(ResumeCheck.BACKOFF_MS, waits)
        assertEquals(AutoResume.STOP_FAILURES, a!!.stopped)
        // The network stopping: backed off, never stopped.
        var s: AutoResume? = null
        repeat(20) { s = afterRun(s, "partial", progressed = false, stalled = true, now = now, budgetMb = 2048) }
        assertNull(s!!.stopped)
        assertEquals(0, s!!.failures)
        assertEquals(ResumeCheck.BACKOFF_MS.last(), s!!.nextAt - now)
        // Progress starts over; done clears; a refusal stops until the budget changes; a cancel or a failure keeps the state.
        val p = afterRun(AutoResume(failures = 4, attempts = 4), "partial", progressed = true, stalled = false, now = now, budgetMb = 2048)!!
        assertEquals(0, p.failures)
        assertEquals(ResumeCheck.BACKOFF_MS.first(), p.nextAt - now)
        assertNull(afterRun(p, "done", true, false, now, 2048))
        assertEquals(AutoResume(stopped = AutoResume.STOP_REFUSED, budgetMb = 2048), afterRun(null, "refused", false, false, now, 2048))
        assertEquals(AutoResume(failures = 1), afterRun(AutoResume(failures = 1, runFrom = "x"), "cancelled", false, false, now, 2048))
    }

    @Test
    fun triggersWhileACheckIsQueuedMakeOneCheck() {
        val queue = ArrayList<Runnable>()
        val runs = ArrayList<List<String>>()
        val c = Coalescer({ queue += it }) { runs += it }
        assertTrue(c.request("start"))
        assertFalse(c.request("foreground"))
        assertFalse(c.request("download over"))
        assertTrue(c.queued)
        assertEquals(1, queue.size)
        queue.removeAt(0).run()
        assertEquals(listOf(listOf("start", "foreground", "download over")), runs)
        assertFalse(c.queued)
        // A trigger during a run queues the next one.
        val c2 = Coalescer({ queue += it }) { t -> runs += t; if (t == listOf("a")) assertTrue(cRef!!.request("b")) }
        cRef = c2
        c2.request("a")
        queue.removeAt(0).run()
        assertEquals(1, queue.size)
        queue.removeAt(0).run()
        assertEquals(listOf("b"), runs.last())
    }

    private var cRef: Coalescer? = null

    @Test
    fun aVersionNotKnownNowIsNoChange() {
        val rec = mapOf("srv" to mapOf("dem" to "a", "osm" to "b"))
        assertFalse(versionsMoved(rec, mapOf("srv" to mapOf("dem" to "a", "osm" to "b"))))
        assertFalse(versionsMoved(rec, mapOf("srv" to mapOf("dem" to "", "osm" to ""))))
        assertFalse(versionsMoved(rec, mapOf("other" to mapOf("dem" to "z"))))
        assertTrue(versionsMoved(rec, mapOf("srv" to mapOf("dem" to "c", "osm" to "b"))))
    }

    @Test
    fun tileKeysAreTakenApartFromTheEnd() {
        val k = assertNotNull(parseTileKey("10.0.2.2_8765/dem@2026-10/12/655/1430.png"))
        assertEquals(listOf("10.0.2.2_8765/dem@2026-10", "12", "655", "1430", "png"), listOf(k.prefix, k.z.toString(), k.x.toString(), k.y.toString(), k.ext))
        assertNull(parseTileKey("src/contours/12/655/1430.halo-12-654-1430.pbf"))
        assertNull(parseTileKey("12/655/1430.png"), "no prefix")
        assertNull(parseTileKey("src/dem/12/x/1430.png"))
        assertNull(parseTileKey("src/dem/12/655/1430"))
    }

    @Test
    fun theCachedRowsAgreeWithASetOfTiles() {
        val rnd = Random(151)
        val rows = CachedRows()
        val ref = HashSet<Triple<Int, Int, Int>>()
        repeat(4000) {
            val z = 10 + rnd.nextInt(3)
            val x = rnd.nextInt(40)
            val y = rnd.nextInt(5)
            val key = "s/dem@v/$z/$x/$y.png"
            if (rnd.nextInt(3) == 0) { rows.remove(key); ref.remove(Triple(z, x, y)) } else { rows.add(key); ref.add(Triple(z, x, y)) }
        }
        rows.add("s/dem@v/10/1/1.halo-1.png") // not a tile key: ignored
        assertEquals(ref.size.toLong(), rows.size)
        val out = SpanList()
        for (z in 10..12) for (y in 0 until 5) for ((x0, x1) in listOf(0 to 39, 5 to 17, 20 to 20)) {
            out.clear()
            rows.spans("s/dem@v", "png", z, y, x0, x1, out)
            assertEquals((x0..x1).count { Triple(z, it, y) in ref }.toLong(), out.unionLength(), "z$z y$y $x0..$x1")
            out.clear()
            rows.spans("s/dem@v", "pbf", z, y, x0, x1, out)
            assertEquals(0L, out.unionLength(), "another extension")
        }
        assertTrue(rows.spanCount <= ref.size)
    }

    @Test
    fun theUnionOfSpansCountsEachTileOnce() {
        val rnd = Random(7)
        val s = SpanList()
        repeat(300) {
            s.clear()
            val ref = HashSet<Int>()
            repeat(rnd.nextInt(1, 12)) {
                val lo = rnd.nextInt(0, 60)
                val hi = lo + rnd.nextInt(0, 10)
                s.add(lo, hi)
                for (x in lo..hi) ref += x
            }
            assertEquals(ref.size.toLong(), s.unionLength())
        }
    }

    @Test
    fun aPackSpanOnARowIsWhatThePerTileTestSays() {
        val rnd = Random(45)
        repeat(400) {
            val w = -125.0 + rnd.nextDouble() * 10
            val s = 44.0 + rnd.nextDouble() * 5
            val c = PackCoverage("p", rnd.nextInt(6, 11), rnd.nextInt(11, 15), PackBounds(w, s, w + rnd.nextDouble() * 2, s + rnd.nextDouble() * 1.5))
            val z = rnd.nextInt(6, 16)
            val n = 1 shl z
            val y = ((io.github.jasondorjeshort.dystopo.geo.WebMercator.latToY(s + 0.7, z)).toInt() + rnd.nextInt(-3, 4)).coerceIn(0, n - 1)
            val mid = io.github.jasondorjeshort.dystopo.geo.WebMercator.lonToX(w + 1, z).toInt()
            val x0 = (mid - rnd.nextInt(0, 2 shl (z / 2))).coerceIn(0, n - 1)
            val x1 = (x0 + rnd.nextInt(0, 4 shl (z / 2))).coerceIn(x0, n - 1)
            for (role in listOf(PackRoleZooms.DEM, PackRoleZooms.VECTOR)) {
                val ref = (x0..x1).filter { PackPrecedence.overlaps(c, z, it, y, role) }
                val got = packSpan(c, role, z, y, x0, x1)
                assertEquals(ref.takeIf { it.isNotEmpty() }?.let { it.first()..it.last() }, got, "$c z$z y$y $x0..$x1 $role")
                assertEquals(ref.size, got?.count() ?: 0, "one interval")
            }
        }
    }

    @Test
    fun theCardSaysWhenAnInterruptedDownloadContinues() {
        val now = 1_000_000L
        assertEquals("Continues by itself", resumeLine(record("a", "partial"), now))
        assertEquals("Continues by itself in about 2 min", resumeLine(record("a", "partial", auto = AutoResume(nextAt = now + 90_000)), now))
        assertNull(resumeLine(record("a", "partial", cancelled = true), now))
        assertNull(resumeLine(record("a", "partial", auto = AutoResume(stopped = AutoResume.STOP_FAILURES)), now))
        assertNull(resumeLine(record("a", "downloading"), now))
        assertNull(resumeLine(record("a", "done"), now))
    }
}
