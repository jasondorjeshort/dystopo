package io.github.jasondorjeshort.dystopo.app.online

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileCacheTest {
    private val dir: File = Files.createTempDirectory("tilecache").toFile()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun cache(max: Long) = TileCache(dir, max) { now }

    @Test
    fun storesAndReadsBack() {
        val c = cache(1000)
        c.put("s/dem/1/0/0.png", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), c.get("s/dem/1/0/0.png"))
        assertNull(c.get("s/dem/1/0/1.png"))
        assertEquals(1L, c.hits.get())
        assertEquals(1L, c.misses.get())
        assertEquals(3L, c.bytes)
    }

    @Test
    fun oneCapOverSeveralDirectoriesAndResize() {
        // T41: the contour cache keeps one directory per pack under one LRU cap.
        val c = cache(1000)
        c.put("v2-ft-512-aaaa/12/1/1.pbf", ByteArray(300))
        c.put("v2-ft-512-bbbb/12/1/1.pbf", ByteArray(300))
        c.put("v2-m-512-aaaa/12/1/1.pbf", ByteArray(300))
        now += 1000
        c.get("v2-ft-512-aaaa/12/1/1.pbf") // the first pack's tile is the most recently used now
        assertEquals(setOf("v2-ft-512-aaaa", "v2-ft-512-bbbb", "v2-m-512-aaaa"), c.byPrefix().keys)
        c.resize(700)
        assertEquals(700L, c.maxBytes)
        assertTrue(c.contains("v2-ft-512-aaaa/12/1/1.pbf"), "recently used stays")
        assertFalse(c.contains("v2-ft-512-bbbb/12/1/1.pbf"), "least recently used went")
        assertEquals(2, c.clear("v2-"))
        assertEquals(0L, c.bytes)
    }

    @Test
    fun evictsLeastRecentlyUsedFirst() {
        val c = cache(350) // T242: past the cap it trims to 343 (2 % below), so one tile goes here
        c.put("a", ByteArray(100))
        c.put("b", ByteArray(100))
        c.put("c", ByteArray(100))
        c.get("a") // a is now the most recently used
        c.put("d", ByteArray(100)) // over the cap: b goes
        assertTrue(c.contains("a"))
        assertFalse(c.contains("b"))
        assertTrue(c.contains("c"))
        assertTrue(c.contains("d"))
        assertEquals(300L, c.bytes)
        assertEquals(1L, c.evictions.get())
        assertFalse(File(dir, "b").exists(), "evicted file deleted")
    }

    @Test
    fun replacingAKeyKeepsTheTotalRight() {
        val c = cache(1000)
        c.put("k", ByteArray(100))
        c.put("k", ByteArray(40))
        assertEquals(40L, c.bytes)
        assertEquals(1, c.size)
    }

    @Test
    fun indexSurvivesARestartInAccessOrder() {
        val c = cache(10_000)
        c.put("old", ByteArray(100))
        now += 120_000
        c.put("new", ByteArray(100))
        now += 120_000
        c.get("old") // touched: newer than "new" on disk
        // A restart with a smaller cap keeps the most recently used entry (T242: once the index is whole; T379: awaitReady
        // returns after the trim that applies the cap, which ran after it before and lost the race under load).
        val again = cache(150)
        again.awaitReady()
        assertTrue(again.contains("old"))
        assertFalse(again.contains("new"))
        assertEquals(100L, again.bytes)
    }

    @Test
    fun rejectsKeysThatEscapeTheDirectory() {
        val c = cache(1000)
        assertFailsWith<IllegalArgumentException> { c.put("../x", ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { c.get("/abs") }
        assertFailsWith<IllegalArgumentException> { c.get("a//b") }
        assertFalse(c.contains("../x"))
    }

    @Test
    fun clearByPrefix() {
        val c = cache(1000)
        c.put("s1/dem/1", ByteArray(1))
        c.put("s1/osm/1", ByteArray(1))
        c.put("s2/dem/1", ByteArray(1))
        c.put("s1/dem@v/1/2/3.png", ByteArray(1)) // T242: a packed key, decided by its group
        c.put("s10/dem@v/1/2/3.png", ByteArray(1)) // its group starts with "s1" but not with "s1/"
        assertEquals(3, c.clear("s1/"))
        assertEquals(2, c.size)
        assertEquals(1, c.clear("s10/dem@v/1/2/"))
        assertEquals(1, c.size)
    }

    // --- T242: the index built off the main thread ---

    private fun tile(i: Int) = "10.0.2.2_8770/dem@d1/15/${5000 + i}/11000.png"

    /** Writes [n] tiles straight into [dir], the oldest first, a minute apart: what a launch finds. */
    private fun seed(n: Int, bytes: Int = 100) {
        for (i in 0 until n) File(dir, tile(i)).apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes)); setLastModified(now - (n - i) * 60_000L) }
    }

    /** A cache whose walk stops at its first file until [release] counts down; [main] says whether callers are on the UI thread. */
    private fun heldCache(max: Long, release: CountDownLatch, main: () -> Boolean = { false }) =
        TileCache(dir, max, onMain = main, walkHook = { if (it == 1L) release.await(30, TimeUnit.SECONDS) }) { now }

    @Test
    fun gettersAnswerAtOnceAndPartialWhileTheWalkIsHeld() {
        seed(5)
        val release = CountDownLatch(1)
        val c = heldCache(1_000_000, release, main = { true })
        val t0 = System.nanoTime()
        // On the main thread too: they never wait for the walk.
        val figures = listOf(c.bytes, c.size.toLong(), c.pinnedBytes, c.pinnedSize.toLong())
        val json = c.json()
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(ms < 200, "the getters took $ms ms")
        assertEquals(listOf(0L, 0L, 0L, 0L), figures)
        assertEquals(TileCache.State.INDEXING, c.state)
        assertEquals(true, json["partial"])
        assertEquals("indexing", json["state"])
        release.countDown()
        c.whenReady("test") {}
        waitReady(c)
        assertEquals(5, c.size)
        assertEquals(500L, c.bytes)
        assertEquals(false, c.json()["partial"])
    }

    private fun waitReady(c: TileCache) {
        val until = System.currentTimeMillis() + 10_000
        while (!c.ready && System.currentTimeMillis() < until) Thread.sleep(5)
        assertTrue(c.ready)
    }

    @Test
    fun aGetAndAPutWhileIndexingFileTheirEntriesAndTheWalkSkipsThem() {
        seed(4)
        val release = CountDownLatch(1)
        val c = heldCache(1_000_000, release)
        now += 10 * 60_000
        assertEquals(100, c.get(tile(1))?.size, "read from its file while the walk has not reached it")
        assertEquals(1, c.size)
        assertEquals(100L, c.bytes)
        c.put(tile(9), ByteArray(40))
        assertEquals(2, c.size)
        assertEquals(140L, c.bytes)
        c.put(tile(2), ByteArray(70)) // a put over a file the walk has not filed yet
        assertNull(c.get("10.0.2.2_8770/dem@d1/15/1/1.png"), "no file: a miss")
        release.countDown()
        c.awaitReady()
        assertEquals(5, c.size, "the walk skipped the three keys filed meanwhile")
        assertEquals(100L + 100 + 70 + 100 + 40, c.bytes, "counted once each, the put's bytes for tile 2")
        // tile(1) was used now, so it is no longer the oldest: tile(0) and tile(3) go before it.
        assertEquals(200L, c.evictOldest(200))
        assertTrue(c.contains(tile(1)) && !c.contains(tile(0)) && !c.contains(tile(3)))
    }

    @Test
    fun containsLooksOnDiskWhileIndexing() {
        seed(3)
        val release = CountDownLatch(1)
        val c = heldCache(1_000_000, release)
        assertEquals(0, c.size)
        assertTrue(c.contains(tile(2)), "a stat of the file")
        assertFalse(c.contains(tile(7)))
        assertTrue(c.remove(tile(2)))
        assertFalse(c.contains(tile(2)), "removed while indexing: gone, and the walk must not file it again")
        release.countDown()
        c.awaitReady()
        assertEquals(2, c.size)
        assertFalse(c.contains(tile(2)))
        assertFalse(c.contains(tile(0).replace("dem", "osm")), "ready: the index alone answers")
    }

    @Test
    fun aWholeIndexOperationOnTheMainThreadFailsByNameWhileIndexing() {
        seed(2)
        val release = CountDownLatch(1)
        val c = heldCache(1_000_000, release, main = { true })
        val e = assertFailsWith<IllegalStateException> { c.recountPinned() }
        assertTrue(e.message!!.contains("TileCache.recountPinned on the main thread"), e.message)
        assertTrue(e.message!!.contains("TileCacheTest"), "names its caller: ${e.message}")
        assertFailsWith<IllegalStateException> { c.clearWhere { true } }
        assertFailsWith<IllegalStateException> { c.byPrefix() }
        c.resize(10) // a setting: applied once the index is whole, never a wait
        assertEquals(10L, c.maxBytes)
        val done = CountDownLatch(1)
        c.whenReady("test") { done.countDown() } // after the trim that applies the cap
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(0, c.size, "the new cap applied once whole")
        c.recountPinned() // ready: the main thread may, as before
    }

    @Test
    fun offTheMainThreadAWholeIndexOperationWaitsForTheWalk() {
        seed(3)
        val release = CountDownLatch(1)
        val c = heldCache(1_000_000, release)
        val ran = CountDownLatch(1)
        c.whenReady("test") { ran.countDown() }
        Thread { Thread.sleep(150); release.countDown() }.start()
        val t0 = System.nanoTime()
        val byPrefix = c.byPrefix(2)
        assertTrue((System.nanoTime() - t0) / 1e6 >= 100, "it waited")
        assertEquals(mapOf("10.0.2.2_8770/dem@d1" to (3 to 300L)), byPrefix)
        assertTrue(ran.await(5, TimeUnit.SECONDS), "whenReady's action ran")
    }

    @Test
    fun aStaleTmpFileGoesAndAFreshOneStays() {
        seed(1)
        val stale = File(dir, "${tile(0)}.7.tmp").apply { writeBytes(ByteArray(5)); setLastModified(System.currentTimeMillis() - 10 * 60_000) }
        val fresh = File(dir, "${tile(0)}.8.tmp").apply { writeBytes(ByteArray(5)) }
        val c = cache(1000)
        c.awaitReady()
        assertFalse(stale.exists())
        assertTrue(fresh.exists(), "a put under way while indexing")
        assertEquals(1, c.size)
    }

    // --- T242: eviction by touch-minute buckets, the hysteresis ---

    /**
     * One sequence of puts and gets, the clock moving by seconds to minutes, against the order before T242 (a
     * `LinkedHashMap` in access order, trimmed from its head): in the process the same tiles go (the buckets keep the access
     * order within a minute); after a restart the order is by minute, the same minutes in the same order.
     */
    @Test
    fun touchMinuteOrderEqualsTheOldOrderAtMinuteGranularity() {
        val cap = 20_000L
        val c = cache(cap)
        val ref = LinkedHashMap<String, Pair<Int, Long>>(16, 0.75f, true) // key to bytes and last use (ms)
        fun refTrim() {
            var total = ref.values.sumOf { it.first.toLong() }
            if (total <= cap) return
            val it = ref.entries.iterator()
            while (total > cap - TileCache.slackOf(cap) && it.hasNext()) {
                total -= it.next().value.first
                it.remove()
            }
        }
        val rnd = Random(242)
        repeat(3000) {
            now += rnd.nextLong(0, 90_000)
            val k = tile(rnd.nextInt(400))
            if (rnd.nextInt(3) == 0) {
                val n = 100 + rnd.nextInt(400)
                c.put(k, ByteArray(n))
                ref.remove(k)
                ref[k] = n to now
                refTrim()
            } else {
                val got = c.get(k)
                val r = ref[k]
                assertEquals(r?.first, got?.size, "get $k")
                if (r != null) ref[k] = r.first to now
            }
            assertEquals(ref.size, c.size)
        }
        val keys = HashSet<String>().also { s -> c.forEachKey { s += it } }
        assertEquals(ref.keys, keys, "the same tiles stayed")
        // A restart: the walk files each tile in its file time's minute bucket.
        val again = cache(cap)
        again.awaitReady()
        val minutes = ArrayList<Int>()
        while (again.size > 0) {
            minutes += (again.oldestTouch()!! / 60_000).toInt()
            again.evictOldest(1)
        }
        assertEquals(ref.values.map { (it.second / 60_000).toInt() }, minutes, "oldest first, minute by minute")
    }

    @Test
    fun aPutAtTheCapTrimsOncePerSlackNotPerTile() {
        val cap = 100_000L
        val c = cache(cap)
        for (i in 0 until 100) c.put(tile(i), ByteArray(1000)) // at the cap
        assertEquals(0L, c.evictions.get())
        var trims = 0
        for (i in 100 until 199) {
            val before = c.evictions.get()
            c.put(tile(i), ByteArray(1000))
            if (c.evictions.get() > before) {
                trims++
                assertTrue(c.bytes <= cap - TileCache.slackOf(cap), "down to the cap less the slack")
            }
            assertTrue(c.bytes <= cap + 1000)
        }
        assertEquals(33, trims, "one trim every three puts (2000 B of slack, 1000 B tiles), not one per put")
    }

    // --- T279: a file a hard stop left empty is a miss, removed and counted ---

    private fun tornAfterPut(c: TileCache, key: String) {
        c.put(key, byteArrayOf(1, 2, 3))
        File(dir, key).writeBytes(ByteArray(0)) // what a stop leaves when the rename reached the disk and the data did not
    }

    @Test
    fun anEmptyFileUnderAKnownKeyIsAMissRemovedAndCounted() {
        val c = cache(1000)
        c.awaitReady()
        val key = "s/dem/12/654/1431.png"
        tornAfterPut(c, key)
        assertNull(c.get(key), "an empty file is never served")
        assertFalse(File(dir, key).exists(), "the empty file is deleted")
        assertFalse(c.contains(key), "and its index entry with it")
        assertEquals(0, c.size)
        assertEquals(0L, c.bytes)
        assertEquals(1L, c.emptyDropped.get())
        assertEquals(0L, c.emptyDroppedKept.get())
        assertEquals(1L, c.misses.get())
        c.put(key, byteArrayOf(4, 5)) // fetched again
        assertContentEquals(byteArrayOf(4, 5), c.get(key))
    }

    @Test
    fun aKeptKeyLeftEmptyIsAMissTooAndServesAgainAfterAPut() {
        val c = cache(1000)
        c.awaitReady()
        c.keep = { true }
        val key = "s/osm/14/1/1.pbf"
        tornAfterPut(c, key)
        assertEquals(1, c.pinnedSize)
        assertNull(c.get(key))
        assertEquals(0, c.pinnedSize)
        assertEquals(0L, c.pinnedBytes)
        assertEquals(1L, c.emptyDroppedKept.get())
        c.put(key, byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), c.get(key))
        assertEquals(1, c.pinnedSize)
    }

    @Test
    fun anEmptyValueIsWrittenAsOneByteAndReadBackEmpty() {
        val c = cache(1000)
        c.put("s/osm/14/2/2.pbf", ByteArray(0)) // open ocean: content
        assertEquals(1L, File(dir, "s/osm/14/2/2.pbf").length(), "never a file with no bytes")
        assertContentEquals(ByteArray(0), c.get("s/osm/14/2/2.pbf"))
        assertEquals(1L, c.hits.get())
        assertEquals(0L, c.emptyDropped.get())
    }

    @Test
    fun theWalkDropsAnEmptyFile() {
        File(dir, "s/dem/9/1/1.png").apply { parentFile.mkdirs(); writeBytes(ByteArray(0)) }
        File(dir, "s/dem/9/1/2.png").writeBytes(byteArrayOf(1, 2))
        val c = cache(1000)
        c.awaitReady()
        assertEquals(1, c.size)
        assertFalse(File(dir, "s/dem/9/1/1.png").exists())
        assertEquals(1L, c.emptyDropped.get())
        assertNull(c.get("s/dem/9/1/1.png"))
    }

    // --- T379 (ADR 0071): no walk without a screen or a notified job ---

    /** A cache whose walk asks the process's gate ([io.github.jasondorjeshort.dystopo.app.Foreground.walkGate]); [hook] as [heldCache]'s. */
    private fun gatedCache(max: Long = 1_000_000, hook: ((Long) -> Unit)? = null) =
        TileCache(dir, max, walkHook = hook, gate = io.github.jasondorjeshort.dystopo.app.Foreground.walkGate) { now }

    private val fg = io.github.jasondorjeshort.dystopo.app.Foreground

    private fun waitFor(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < until) Thread.sleep(5)
        assertTrue(ok(), what)
    }

    @AfterTest
    fun foregroundBack() {
        fg.reset()
    }

    @Test
    fun theWalkIsHeldWithNoScreenAndNoNotifiedJob() {
        fg.reset()
        seed(5)
        val c = gatedCache()
        waitFor("the walk held") { c.walkHeld }
        Thread.sleep(100)
        assertEquals(TileCache.State.INDEXING, c.state)
        assertEquals(0L, c.walked, "not one file walked")
        assertEquals(0, c.size)
        val gate = c.indexJson()["gate"] as Map<*, *>
        assertEquals(false, gate["open"])
        assertEquals(true, gate["held"])
        assertEquals("no screen and no notified job", gate["why"])
        assertTrue(c.contains(tile(2)), "a key is still answered from its file")
        fg.activityStarted() // the app comes to the screen
        waitReady(c)
        assertEquals(5, c.size)
        assertFalse(c.walkHeld)
        assertEquals(1L, (c.indexJson()["gate"] as Map<*, *>)["holds"])
    }

    @Test
    fun theWalkPausesWhenTheAppLeavesTheScreenAndResumesAtItsReturn() {
        fg.reset()
        fg.activityStarted()
        seed(6)
        // Home after the second file: the walk stops before the third.
        val c = gatedCache(hook = { if (it == 2L) fg.activityStopped() })
        waitFor("held after Home") { c.walkHeld }
        Thread.sleep(100)
        assertEquals(2L, c.walked)
        assertEquals(2, c.size, "what was walked is filed before the pause")
        assertEquals(TileCache.State.INDEXING, c.state)
        fg.activityStarted()
        waitReady(c)
        assertEquals(6, c.size)
        assertEquals(6L, c.walked)
    }

    @Test
    fun theWalkRunsUnderANotifiedJob() {
        fg.reset()
        seed(4)
        val c = gatedCache()
        waitFor("held") { c.walkHeld }
        fg.notifiedWork("download", true) // an area download's notification is up, the app off screen
        waitReady(c)
        assertEquals(4, c.size)
        assertEquals("under download", (c.indexJson()["gate"] as Map<*, *>)["why"])
        fg.notifiedWork("download", false)
        fg.notifiedWork("download", false) // idempotent
        assertFalse(fg.workAllowed)
    }

    @Test
    fun aNotifiedJobKeepsTheWalkGoingAfterHome() {
        fg.reset()
        fg.activityStarted()
        fg.notifiedWork("recording", true)
        seed(5)
        val c = gatedCache(hook = { if (it == 2L) fg.activityStopped() })
        waitReady(c)
        assertEquals(5, c.size)
        assertEquals(0L, (c.indexJson()["gate"] as Map<*, *>)["holds"], "never held")
    }

    @Test
    fun awaitReadyDoesNotTimeOutWhileTheWalkIsHeld() {
        fg.reset()
        seed(3)
        val c = gatedCache()
        c.waitStallMs = 100
        waitFor("held") { c.walkHeld }
        var failure: Throwable? = null
        val done = CountDownLatch(1)
        Thread {
            try {
                c.awaitReady("test")
            } catch (t: Throwable) {
                failure = t
            }
            done.countDown()
        }.start()
        assertFalse(done.await(800, TimeUnit.MILLISECONDS), "still waiting after eight stall periods, no timeout")
        assertNull(failure)
        fg.activityStarted()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNull(failure, "returned once the walk was whole")
        assertTrue(c.ready)
    }

    @Test
    fun aWalkThatStallsWithTheGateOpenStillTimesOut() {
        fg.reset()
        fg.activityStarted()
        seed(3)
        val release = CountDownLatch(1)
        val c = gatedCache(hook = { if (it == 1L) release.await(30, TimeUnit.SECONDS) })
        c.waitStallMs = 100
        val e = assertFailsWith<java.util.concurrent.TimeoutException> { c.awaitReady("test") }
        assertTrue(e.message!!.contains("made no progress"), e.message)
        release.countDown()
        c.waitStallMs = TileCache.WAIT_STALL_MS
        c.awaitReady()
    }

    @Test
    fun whenReadyOrHeldRunsAtTheFirstHoldOrWhenWhole() {
        fg.reset()
        seed(3)
        val c = gatedCache()
        val atHold = CountDownLatch(1)
        c.whenReadyOrHeld("test") { atHold.countDown() }
        assertTrue(atHold.await(10, TimeUnit.SECONDS), "ran at the hold")
        assertFalse(c.ready)
        fg.activityStarted()
        waitReady(c)
        val after = CountDownLatch(1)
        c.whenReadyOrHeld("test") { after.countDown() }
        assertTrue(after.await(10, TimeUnit.SECONDS), "ran at once on its own thread when whole")
        // A walk never held: at the end.
        val dir2 = Files.createTempDirectory("tilecache2").toFile()
        try {
            File(dir2, "a/1").apply { parentFile.mkdirs(); writeBytes(ByteArray(3)) }
            val whole = CountDownLatch(1)
            val c2 = TileCache(dir2, 1000, gate = fg.walkGate) { now }
            c2.whenReadyOrHeld("test") { whole.countDown() }
            assertTrue(whole.await(10, TimeUnit.SECONDS))
            assertTrue(c2.ready)
        } finally {
            dir2.deleteRecursively()
        }
    }
}
