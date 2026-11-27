package io.github.jasondorjeshort.dystopo.app.online

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T249: the tile index kept on disk ([TileIndexSnapshot]) and [TileCache]'s use of it; the two walkers. T390: the journal and the resumable walk. */
class TileIndexSnapshotTest {
    private val root: File = Files.createTempDirectory("tileindex").toFile()
    private val dir = File(root, "online/tiles")
    private val snap = File(root, "online/tiles.index")
    private var now = 1_000_000_000L

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private var boot = "boot-1"

    /** T390: the list walker (the phone's: sorted, resumable); [boot] is the device's boot the cache sees. */
    private fun cache(max: Long = 100_000_000) =
        TileCache(dir, max, snapshot = true, walker = ListWalker(ListWalker::jvmStat), bootId = { boot }, clock = { now }).also { it.awaitReady() }

    private val journal = File(root, "online/tiles.index.journal")

    /** T390: the process dies: the cache writes nothing more, its threads end. */
    private fun kill(c: TileCache) {
        c.simulateKill()
        c.indexThread?.join(10_000)
    }

    /** A cache with tiles in several groups and directories, kept as strings too (a glyph range, a halo variant, a bare key). */
    private fun seeded(): TileCache {
        val c = cache()
        for (x in 0 until 3) for (y in 0 until 5) {
            c.put("s_1/dem@v1/12/${650 + x}/${1400 + y}.png", ByteArray(100 + x * 10 + y))
            now += 60_000
        }
        for ((k, b) in listOf(
            "s_1/osm@o1/12/650/1400.pbf" to 77, "s_1/osm@o1/12/650/1401.pbf" to 78,
            "s_1/contours-v9-ft-512@v1/12/650/1400.halo-12-649-1400.pbf" to 33, "s_1/glyphs/Noto Sans Regular/0-255.pbf" to 55, "bare" to 5,
        )) {
            c.put(k, ByteArray(b))
            now += 60_000 // a minute each, so the order after a read is the same throughout (within a minute it is the key's)
        }
        return c
    }

    private fun keys(c: TileCache): Map<String, Long> {
        val out = HashMap<String, Long>()
        c.forEachKey { out[it] = File(c.dir, it).length() }
        return out
    }

    private fun evictionOrder(c: TileCache): List<String> {
        val out = ArrayList<String>()
        c.evictOldest(Long.MAX_VALUE, spare = { out += it; true })
        return out
    }

    @Test
    fun aCleanSnapshotIsReadInsteadOfAWalk() {
        val a = seeded()
        assertFalse(a.fromSnapshot, "the first launch walked (here: an empty directory, then puts)")
        assertEquals("written", a.saveSnapshot("test"))
        assertTrue(snap.isFile)
        assertFalse(File(root, "online/tiles.index.dirty").exists(), "a write deletes the marker")
        assertEquals("clean", a.saveSnapshot("test"), "nothing changed since")
        val want = keys(a)
        val order = evictionOrder(a)

        val b = cache()
        assertTrue(b.fromSnapshot, "${b.snapshotLoad}")
        assertEquals(true, b.snapshotLoad?.get("used"))
        assertEquals(0L, b.walked - want.size, "the read counts as the walk's progress")
        assertEquals(want, keys(b))
        assertEquals(a.bytes, b.bytes)
        assertEquals(a.size, b.size)
        assertEquals(order, evictionOrder(b), "least recently used first, as before (minutes kept)")
        assertContentEquals(ByteArray(77), b.get("s_1/osm@o1/12/650/1400.pbf"))
        assertEquals(true, b.json()["fromSnapshot"])
        @Suppress("UNCHECKED_CAST")
        val sj = b.json()["snapshot"] as Map<String, Any?>
        assertEquals(true, sj["usedThisLaunch"])
        assertEquals("online/tiles.index", sj["file"])
    }

    @Test
    fun aWalkWritesTheSnapshotAndTheNextLaunchReadsIt() {
        seeded() // never saved: its marker says so
        val walked = cache()
        assertFalse(walked.fromSnapshot)
        assertEquals("none", walked.snapshotLoad?.get("why"))
        // The walk's thread writes it after the walk (awaitReady returns at ready, just before).
        assertTrue(waitFor { walked.lastSave?.get("reason") == "walk" }, "${walked.lastSave}")
        assertFalse(File(root, "online/tiles.index.dirty").exists())
        val read = cache()
        assertTrue(read.fromSnapshot, "${read.snapshotLoad}")
        assertEquals(keys(walked), keys(read))
    }

    @Test
    fun aKillAfterChangesKeepsTheSnapshotThroughTheJournal() {
        val a0 = seeded()
        assertEquals("written", a0.saveSnapshot("test"))
        kill(a0)
        val a = cache() // over a snapshot's index, a get looks on disk for a key it lacks
        assertTrue(a.fromSnapshot)
        val gen = a.generation
        // After the write, then the process dies: a new tile, one replaced with another size, one removed, one found on disk by a get.
        a.put("s_1/dem@v1/12/650/1499.png", ByteArray(9))
        a.put("s_1/osm@o1/12/650/1400.pbf", ByteArray(70))
        assertTrue(a.remove("s_1/dem@v1/12/651/1402.png"))
        File(dir, "s_1/dem@v1/12/652/1490.png").writeBytes(ByteArray(13)) // a write the index never saw (a copy into the store)
        assertContentEquals(ByteArray(13), a.get("s_1/dem@v1/12/652/1490.png"))
        assertFalse(File(root, "online/tiles.index.dirty").exists(), "no marker any more")
        val want = keys(a)
        val bytes = a.bytes
        kill(a)
        val b = cache()
        assertTrue(b.fromSnapshot, "${b.snapshotLoad} ${b.journalLoad}")
        assertEquals(true, b.snapshotLoad?.get("used"))
        assertEquals(gen, b.generation)
        assertEquals(4, b.journalLoad?.get("records"))
        assertEquals(0L, b.walkedThisLaunch, "nothing walked")
        assertEquals(want, keys(b))
        assertEquals(want, diskKeys(), "the index is the disk")
        assertEquals(bytes, b.bytes)
        assertNull(b.get("s_1/dem@v1/12/651/1402.png"))
        // The next write folds the journal: a new generation, an empty journal, and the start after reads it alone.
        assertEquals("written", b.saveSnapshot("test"))
        assertEquals(gen + 1, b.generation)
        @Suppress("UNCHECKED_CAST")
        val j = (b.snapshotJson()!!["journal"] as Map<String, Any?>)
        assertEquals(0, j["records"])
        kill(b)
        val c = cache()
        assertTrue(c.fromSnapshot)
        assertEquals(0, c.journalLoad?.get("records"))
        assertEquals(want, keys(c))
    }

    @Test
    fun aTornLastRecordIsIgnoredAndTheNextWalkFindsWhatItRecorded() {
        val a = seeded()
        a.saveSnapshot("test")
        a.put("s_1/dem@v1/12/650/1497.png", ByteArray(5))
        a.put("s_1/dem@v1/12/650/1498.png", ByteArray(6))
        kill(a)
        // The second put's record cut short (the process killed mid-write, or a device stop): its file is there.
        journal.writeBytes(journal.readBytes().let { it.copyOf(it.size - 3) })
        val scan = TileJournal.scan(journal.readBytes())
        assertEquals(1, scan.records.count, "the torn record is not read")
        assertTrue(scan.tornBytes > 0)
        val b = cache()
        assertFalse(b.fromSnapshot)
        assertTrue((b.snapshotLoad?.get("why") as String).startsWith("the journal may lack changes: a torn last record"), "${b.snapshotLoad}")
        assertTrue(b.contains("s_1/dem@v1/12/650/1498.png"), "the walk found what the torn record named")
        assertEquals(diskKeys(), keys(b))
        assertTrue(waitFor { b.lastSave?.get("reason") == "walk" })
        kill(b)
        val c = cache()
        assertTrue(c.fromSnapshot, "the walk's snapshot is read next time: ${c.snapshotLoad}")
        assertEquals(diskKeys(), keys(c))
    }

    @Test
    fun aFileWrittenWithNoJournalLineIsReconciledByTheNextWalk() {
        // A device stop (the journal's records of an earlier boot): its last records may be lost while the files they named are there.
        val a = seeded()
        a.saveSnapshot("test")
        a.put("s_1/dem@v1/12/650/1496.png", ByteArray(4))
        kill(a)
        File(dir, "s_1/dem@v1/12/650/1495.png").writeBytes(ByteArray(8)) // its record was lost with the page cache
        boot = "boot-2"
        val b = cache()
        assertFalse(b.fromSnapshot)
        assertTrue((b.snapshotLoad?.get("why") as String).contains("earlier boot"), "${b.snapshotLoad}")
        assertEquals(diskKeys(), keys(b), "the walk reconciled the index with the disk")
        assertTrue(waitFor { b.lastSave?.get("reason") == "walk" })
        kill(b)
        // The same boot, a file with no line in a directory the journal did not touch (a write behind the cache's back):
        // the snapshot's check of the directories sees it, and the walk files it.
        val c = cache()
        assertTrue(c.fromSnapshot)
        File(dir, "s_1/dem@v1/12/651/1499.png").writeBytes(ByteArray(7))
        kill(c)
        val d = cache()
        assertFalse(d.fromSnapshot)
        assertTrue((d.snapshotLoad?.get("why") as String).startsWith("disagrees with the directory"), "${d.snapshotLoad}")
        assertEquals(diskKeys(), keys(d))
        // A journal of an earlier boot with no record costs nothing: its header is renewed.
        assertTrue(waitFor { d.lastSave?.get("reason") == "walk" })
        kill(d)
        boot = "boot-3"
        val e = cache()
        assertTrue(e.fromSnapshot, "${e.snapshotLoad}")
        assertTrue(journal.readText(Charsets.ISO_8859_1).contains("boot: boot-3"))
    }

    @Test
    fun aJournalThatNamesAFileSinceDeleted() {
        val a = seeded()
        a.saveSnapshot("test")
        val k = "s_1/dem@v1/12/650/1494.png"
        a.put(k, ByteArray(21))
        a.put("s_1/dem@v1/12/650/1493.png", ByteArray(22))
        kill(a)
        File(dir, k).delete() // a kill between the record and the rename, or the system's trim
        val b = cache()
        assertTrue(b.fromSnapshot, "${b.snapshotLoad}")
        assertEquals(1, b.journalLoad?.get("tailDropped"), "${b.journalLoad}")
        assertFalse(b.contains(k))
        assertEquals(diskKeys(), keys(b))
        assertEquals(diskKeys().values.sum(), b.bytes)
        // Beyond the checked tail, a get drops it.
        val k2 = "s_1/dem@v1/12/650/1400.png"
        File(dir, k2).delete()
        val before = b.bytes
        assertNull(b.get(k2))
        assertEquals(before - 100, b.bytes)
    }

    @Test
    fun anOldFormatSnapshotIsDroppedByItsVersionAndTheStoreWalkedOnce() {
        val a = seeded()
        a.saveSnapshot("test")
        kill(a)
        // What a build before T390 left: a format 1 snapshot (no generation) and its dirty marker; no journal.
        val text = String(snap.readBytes(), Charsets.ISO_8859_1)
            .replaceFirst("format: 2", "format: 1").replace(Regex("generation: \\d+\n"), "").replaceFirst("complete: true\n", "")
        snap.writeBytes(text.toByteArray(Charsets.ISO_8859_1))
        journal.delete()
        File(root, "online/tiles.index.dirty").writeText("")
        val b = cache()
        assertFalse(b.fromSnapshot)
        assertEquals("corrupt: format 1, this build reads 2", b.snapshotLoad?.get("why"))
        assertFalse(File(root, "online/tiles.index.dirty").exists(), "the old marker goes")
        assertEquals(diskKeys(), keys(b))
        assertTrue(waitFor { b.lastSave?.get("reason") == "walk" })
        assertTrue(String(snap.readBytes(), 0, 60, Charsets.ISO_8859_1).contains("format: 2"))
        kill(b)
        val c = cache()
        assertTrue(c.fromSnapshot, "walked once: ${c.snapshotLoad}")
    }

    @Test
    fun aLongJournalIsFoldedIntoANewSnapshot() {
        val a = seeded()
        a.saveSnapshot("test")
        val gen = a.generation
        a.foldRecords = 10
        for (i in 0 until 25) a.put("s_1/dem@v1/13/1300/${3000 + i}.png", ByteArray(10 + i))
        assertTrue(waitFor { a.lastSave?.get("reason") == "journal" }, "${a.lastSave}")
        assertTrue(waitFor { a.generation > gen })
        @Suppress("UNCHECKED_CAST")
        val j = a.snapshotJson()!!["journal"] as Map<String, Any?>
        assertTrue((j["records"] as Int) < 25, "$j")
        val want = keys(a)
        kill(a)
        val b = cache()
        assertTrue(b.fromSnapshot)
        assertTrue((b.journalLoad?.get("records") as Int) < 25, "${b.journalLoad}")
        assertEquals(want, keys(b))
    }

    @Test
    fun aWriteCutAfterTheJournalTurnedOverLosesNothing() {
        val a = seeded()
        a.saveSnapshot("test")
        val gen = a.generation
        a.put("s_1/dem@v1/12/650/1491.png", ByteArray(3)) // in the journal the write turns over (`.old`)
        a.writeHoldMs = 2_000
        val writer = Thread { a.saveSnapshot("test") }
        writer.start()
        assertTrue(waitFor { File(root, "online/tiles.index.journal.old").isFile && File(root, "online/tiles.index.tmp").isFile })
        a.put("s_1/dem@v1/12/650/1492.png", ByteArray(4)) // in the new journal
        kill(a) // halfway through the write
        writer.join(10_000)
        assertEquals(gen, TileIndexSnapshot.read(snap.readBytes()).generation, "the snapshot before it stays")
        val b = cache()
        assertTrue(b.fromSnapshot, "${b.snapshotLoad} ${b.journalLoad}")
        assertEquals(true, b.snapshotLoad?.get("interruptedWrite"))
        assertEquals(2, b.journalLoad?.get("journals"))
        assertEquals(diskKeys(), keys(b))
        // The two journals are folded into one snapshot at once.
        assertTrue(waitFor { !File(root, "online/tiles.index.journal.old").exists() && b.generation > gen }, "${b.lastSave}")
    }

    @Test
    fun aPutRacingAWriteOfTheSnapshotIsIndexedAfterTheNextLoad() {
        val a0 = seeded()
        a0.saveSnapshot("test")
        kill(a0)
        val a = cache()
        assertTrue(a.fromSnapshot)
        val k = "s_1/dem@v1/12/650/1489.png"
        val renamed = java.util.concurrent.CountDownLatch(1)
        val goOn = java.util.concurrent.CountDownLatch(1)
        a.putHook = { if (it == k) { renamed.countDown(); goOn.await(10, java.util.concurrent.TimeUnit.SECONDS) } }
        val putter = Thread { a.put(k, ByteArray(17)) }
        putter.start()
        assertTrue(renamed.await(10, java.util.concurrent.TimeUnit.SECONDS), "the put's record written and its file renamed")
        // A whole write in the gap before the put's add: the journal turns over, the copy lacks the entry, `.old` (with the record) goes.
        assertEquals("written", a.saveSnapshot("test", force = true))
        assertFalse(File(root, "online/tiles.index.journal.old").exists())
        goOn.countDown()
        putter.join(10_000)
        kill(a)
        val b = cache()
        assertTrue(b.fromSnapshot, "the snapshot is read (no walk to find the file): ${b.snapshotLoad}")
        assertEquals(1, b.journalLoad?.get("records"), "the put journalled again after the turnover: ${b.journalLoad}")
        assertEquals(diskKeys(), keys(b), "the file is in the index, not only on disk")
        assertEquals(diskKeys().values.sum(), b.bytes)
    }

    @Test
    fun aJournalThatCannotBeMadeAtAWriteMeansAWalk() {
        val a0 = seeded()
        a0.saveSnapshot("test")
        kill(a0)
        val a = cache()
        a.turnoverHook = { throw java.io.IOException("No space left on device") }
        a.put("s_1/dem@v1/12/650/1488.png", ByteArray(9))
        assertEquals("error", a.saveSnapshot("test"))
        assertTrue((a.lastSave?.get("error") as String).contains("the journal could not be made"), "${a.lastSave}")
        assertFalse(snap.exists(), "the snapshot is not trusted any more")
        a.put("s_1/dem@v1/12/650/1487.png", ByteArray(8)) // not journalled: nothing could hold it
        kill(a)
        val b = cache()
        assertFalse(b.fromSnapshot)
        assertEquals("none", b.snapshotLoad?.get("why"))
        assertEquals(diskKeys(), keys(b), "the walk found both")
        // A later write that can make its journal trusts the snapshot again.
        assertTrue(waitFor { b.lastSave?.get("reason") == "walk" })
        kill(b)
        assertTrue(cache().fromSnapshot)
    }

    @Test
    fun aWalkKilledMidwayResumesFromItsCheckpoint() {
        for (x in 0 until 6) for (y in 0 until 10) File(dir, "s_1/dem@v1/12/${700 + x}/${1500 + y}.png").apply { parentFile.mkdirs() }.writeBytes(ByteArray(10 + y))
        val gate = TestGate()
        val holder = arrayOfNulls<TileCache>(1)
        val atKill = java.util.concurrent.CountDownLatch(1)
        val a = TileCache(dir, 100_000_000, snapshot = true, walker = ListWalker(ListWalker::jvmStat), gate = gate, bootId = { boot },
            walkHook = { n -> if (n == 45L) { holder[0]!!.simulateKill(); atKill.countDown() } }, clock = { now })
        holder[0] = a
        a.checkpointMin = 10
        gate.set(true)
        assertTrue(atKill.await(10, java.util.concurrent.TimeUnit.SECONDS))
        a.indexThread?.join(10_000)
        assertTrue(snap.isFile, "a checkpoint was written")
        val cp = TileIndexSnapshot.read(snap.readBytes())
        // Checkpoints after 10, 20 and 30 files (each at least half the last); 40 is not half again past 30; the kill at 45.
        assertEquals("s_1/dem@v1/12/702", cp.walkCursor, "the last directory finished at the last checkpoint")
        assertEquals(30, cp.entries.total)
        val b = cache()
        assertTrue(b.fromSnapshot, "${b.snapshotLoad}")
        assertEquals(true, b.snapshotLoad?.get("partial"))
        assertEquals("s_1/dem@v1/12/702", b.resumeJson()["resumedFrom"])
        assertEquals(30, b.resumeJson()["entriesAtResume"])
        assertEquals(30L, b.walkedThisLaunch, "only the three directories after the cursor")
        assertEquals(diskKeys(), keys(b))
        assertTrue(waitFor { b.lastSave?.get("reason") == "walk" })
        kill(b)
        val c = cache()
        assertTrue(c.fromSnapshot)
        assertEquals(false, c.snapshotLoad?.get("partial"))
        assertEquals(60, c.size)
    }

    @Test
    fun homeMidWalkWritesACheckpointAndAKillWhileAwayResumesFromIt() {
        for (x in 0 until 6) for (y in 0 until 10) File(dir, "s_1/dem@v1/12/${700 + x}/${1500 + y}.png").apply { parentFile.mkdirs() }.writeBytes(ByteArray(10 + y))
        val gate = TestGate()
        val a = TileCache(dir, 100_000_000, snapshot = true, walker = ListWalker(ListWalker::jvmStat), gate = gate, bootId = { boot },
            walkHook = { n -> if (n == 35L) gate.set(false) }, clock = { now }) // Home in the fourth directory
        a.checkpointMin = 12 // checkpoints after 20 files; at the hold, 15 since
        gate.set(true)
        assertTrue(waitFor { a.walkHeld })
        assertEquals("checkpoint", a.lastSave?.get("reason"), "${a.lastSave}")
        assertEquals("s_1/dem@v1/12/702", a.lastSave?.get("walkCursor"))
        assertEquals(35, a.lastSave?.get("entries"), "what it had filed, five past the cursor too")
        kill(a) // Android's kill for CPU, after Home
        val b = cache()
        assertEquals(true, b.snapshotLoad?.get("partial"), "${b.snapshotLoad}")
        assertEquals("s_1/dem@v1/12/702", b.resumeJson()["resumedFrom"])
        assertEquals(35, b.resumeJson()["entriesAtResume"])
        assertEquals(30L, b.walkedThisLaunch)
        assertEquals(diskKeys(), keys(b))
    }

    @Test
    fun aPutDuringAWalkAfterItsCheckpointIsInTheJournalAtTheResume() {
        for (x in 0 until 4) for (y in 0 until 10) File(dir, "s_1/dem@v1/12/${700 + x}/${1500 + y}.png").apply { parentFile.mkdirs() }.writeBytes(ByteArray(10 + y))
        val gate = TestGate()
        val holder = arrayOfNulls<TileCache>(1)
        val atPut = java.util.concurrent.CountDownLatch(1)
        val goOn = java.util.concurrent.CountDownLatch(1)
        val a = TileCache(dir, 100_000_000, snapshot = true, walker = ListWalker(ListWalker::jvmStat), gate = gate, bootId = { boot },
            walkHook = { n -> if (n == 25L) { atPut.countDown(); goOn.await(10, java.util.concurrent.TimeUnit.SECONDS); holder[0]!!.simulateKill() } }, clock = { now })
        holder[0] = a
        a.checkpointMin = 10
        gate.set(true)
        assertTrue(atPut.await(10, java.util.concurrent.TimeUnit.SECONDS))
        a.put("s_1/dem@v1/12/700/1598.png", ByteArray(5)) // behind the cursor (701 is the checkpoint's)
        assertTrue(a.remove("s_1/dem@v1/12/701/1500.png"))
        goOn.countDown()
        a.indexThread?.join(10_000)
        val b = cache()
        assertEquals(true, b.snapshotLoad?.get("partial"), "${b.snapshotLoad}")
        assertEquals(2, b.journalLoad?.get("records"), "${b.journalLoad}")
        assertEquals(diskKeys(), keys(b))
    }

    @Test
    fun theJournalIsReplayedOnlyWhenTheGateOpens() {
        val a = seeded()
        a.saveSnapshot("test")
        a.put("s_1/dem@v1/12/650/1490.png", ByteArray(5))
        kill(a)
        val gate = TestGate()
        val b = TileCache(dir, 100_000_000, snapshot = true, walker = ListWalker(ListWalker::jvmStat), gate = gate, bootId = { boot }, clock = { now })
        assertTrue(waitFor { b.walkHeld })
        Thread.sleep(100)
        assertNull(b.journalLoad, "nothing read without a screen or a notified job")
        assertNull(b.snapshotLoad)
        gate.set(true)
        b.awaitReady()
        assertEquals(1, b.journalLoad?.get("records"))
        assertEquals(diskKeys(), keys(b))
    }

    @Test
    fun theJournalsFormatRoundTripsAndStopsAtABadRecord() {
        val head = TileJournal.header(7, "b")
        val r1 = TileJournal.put("a/dem/12/1/2.png", 1234, -5)
        val r2 = TileJournal.remove("a/glyphs/Noto Sans Bold/0-255.pbf")
        val r3 = TileJournal.put("bare", 0, 29_000_000)
        val all = head + r1 + r2 + r3
        val s = TileJournal.scan(all)
        assertEquals(7L, s.base)
        assertEquals("b", s.boot)
        assertEquals(3, s.records.count)
        assertEquals(listOf("a/dem/12/1/2.png", "a/glyphs/Noto Sans Bold/0-255.pbf", "bare"), s.records.keys)
        assertEquals(1234, s.records.sizes[0])
        assertEquals(-5, s.records.minutes[0])
        assertEquals(TileJournal.REMOVE, s.records.ops[1].toInt())
        assertEquals(29_000_000, s.records.minutes[2])
        assertEquals(0, s.tornBytes)
        // A flipped byte in the second record: the first is kept, nothing after it is read.
        val bad = all.copyOf()
        bad[head.size + r1.size + 4] = (bad[head.size + r1.size + 4] + 1).toByte()
        val t = TileJournal.scan(bad)
        assertEquals(1, t.records.count)
        assertEquals(head.size + r1.size, t.goodEnd)
        assertEquals(r2.size + r3.size, t.tornBytes)
        println("T390 journal: ${r1.size} B for a put of a ${"a/dem/12/1/2.png".length}-character key, ${r2.size} B for a remove")
        assertEquals("not a tile journal", assertFailsWith<TileIndexSnapshot.Corrupt> { TileJournal.scan("x\n".toByteArray()) }.message)
    }

    /** The files under the store (keys and sizes), the `.tmp` ones aside. */
    private fun diskKeys(): Map<String, Long> =
        dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.length() }

    /** A gate a test opens and shuts. */
    private class TestGate : WalkGate {
        private val lock = Object()
        @Volatile private var on = false
        override val open: Boolean get() = on
        override fun awaitOpen() = synchronized(lock) { if (!on) lock.wait(1000) }
        override fun why() = if (on) "test open" else "test shut"
        fun set(v: Boolean) = synchronized(lock) {
            on = v
            lock.notifyAll()
        }
    }

    @Test
    fun aTouchAloneIsWrittenButDoesNotCostAWalk() {
        val a = seeded()
        a.saveSnapshot("test")
        now += 10 * 60_000
        assertNotNull(a.get("s_1/dem@v1/12/650/1400.png")) // its minute moves: worth writing, not worth a walk
        assertFalse(File(root, "online/tiles.index.dirty").exists())
        val b = cache()
        assertTrue(b.fromSnapshot, "${b.snapshotLoad}")
        assertEquals("written", a.saveSnapshot("test"))
    }

    @Test
    fun aWriteInterruptedByAKillLeavesTheOneBeforeIt() {
        val a = seeded()
        a.saveSnapshot("test")
        File(root, "online/tiles.index.tmp").writeBytes(snap.readBytes().copyOf(100)) // half a write
        val b = cache()
        assertTrue(b.fromSnapshot, "T390: the snapshot before it, with the journal, still holds everything: ${b.snapshotLoad}")
        assertEquals(true, b.snapshotLoad?.get("interruptedWrite"))
        assertFalse(File(root, "online/tiles.index.tmp").exists())
        assertEquals(keys(a), keys(b))
    }

    @Test
    fun aCorruptSnapshotIsDiscardedAndTheWalkRuns() {
        val a = seeded()
        a.saveSnapshot("test")
        val bytes = snap.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("bytes: ") + 7 // a digit of the header's total: only the checksum tells
        bytes[at] = (if (bytes[at] == '9'.code.toByte()) '8'.code else bytes[at] + 1).toByte()
        snap.writeBytes(bytes)
        val b = cache()
        assertFalse(b.fromSnapshot)
        assertEquals("corrupt: bad checksum", b.snapshotLoad?.get("why"))
        assertEquals(keys(a), keys(b))

        assertTrue(waitFor { b.lastSave?.get("reason") == "walk" })
        snap.writeBytes(snap.readBytes().copyOf(snap.length().toInt() - 30)) // cut short
        val c = cache()
        assertFalse(c.fromSnapshot)
        assertEquals("corrupt: ends early", c.snapshotLoad?.get("why"))
        assertEquals(keys(a), keys(c))
    }

    @Test
    fun aSnapshotThatDisagreesWithTheDirectoryIsDiscarded() {
        val a = seeded()
        a.saveSnapshot("test")
        // A file the snapshot does not know, in a directory it does (the copy of a backup, a write behind its back).
        File(dir, "s_1/dem@v1/12/651/1410.png").writeBytes(ByteArray(3))
        val b = cache()
        assertFalse(b.fromSnapshot)
        assertTrue((b.snapshotLoad?.get("why") as String).startsWith("disagrees with the directory: the directory 's_1/dem@v1/12/651' holds 6 names besides its directories, the snapshot 5 files"), "${b.snapshotLoad}")
        assertTrue(b.contains("s_1/dem@v1/12/651/1410.png"))

        assertTrue(waitFor { b.lastSave?.get("reason") == "walk" })
        File(dir, "other_9/dem/3/1/2.png").apply { parentFile.mkdirs() }.writeBytes(ByteArray(4)) // a new directory
        val c = cache()
        assertFalse(c.fromSnapshot)
        assertTrue((c.snapshotLoad?.get("why") as String).contains("the directory '' holds 2 names besides its directories, the snapshot 1 files"), "${c.snapshotLoad}")

        assertTrue(waitFor { c.lastSave?.get("reason") == "walk" })
        File(dir, "s_1/osm@o1/12/650/1401.pbf").writeBytes(ByteArray(5)) // a size changed
        val d = TileIndexSnapshot.validate(dir, TileIndexSnapshot.read(snap.inputStream().buffered()), Random(1), entrySample = 10_000)
        assertEquals("the entry s_1/osm@o1/12/650/1401.pbf: 5 bytes, the snapshot says 78", d)
    }

    @Test
    fun emptyDirectoriesLeftByEvictionStillAgree() {
        val a = seeded()
        a.clear("s_1/dem@v1/12/652/") // a whole column goes; its directory stays, empty
        assertTrue(File(dir, "s_1/dem@v1/12/652").isDirectory)
        a.saveSnapshot("test")
        assertTrue(cache().fromSnapshot)
    }

    @Test
    fun overASnapshotsIndexAMissingFileIsDroppedAndAnUnknownOneFound() {
        val a = seeded()
        a.saveSnapshot("test")
        val b = cache()
        assertTrue(b.fromSnapshot)
        val gone = "s_1/dem@v1/12/650/1400.png"
        val before = b.bytes
        File(dir, gone).delete() // the system trimmed it
        assertNull(b.get(gone))
        assertFalse(b.contains(gone))
        assertEquals(before - 100, b.bytes, "the entry whose file is missing is dropped on get")
        val late = "s_1/dem@v1/12/650/1405.png" // written after the snapshot's last write by a run that was killed
        File(dir, late).writeBytes(ByteArray(11))
        assertTrue(b.contains(late))
        assertContentEquals(ByteArray(11), b.get(late))
        assertEquals(before - 100 + 11, b.bytes, "and filed")
    }

    @Test
    fun theFormatRoundTripsAndRefusesOthers() {
        val groups = listOf("a/dem" to "png", "a/osm" to "pbf", "a/dem" to "webp")
        val r = Random(7)
        val n = 5000
        val keys = LongArray(n)
        val sizes = IntArray(n)
        val minutes = IntArray(n)
        val pinned = BooleanArray(n)
        val seen = HashSet<Long>()
        var i = 0
        while (i < n) {
            val k = TileIndex.packedOf(r.nextInt(3), 14, 2000 + r.nextInt(40), 5000 + r.nextInt(400))
            if (!seen.add(k)) continue
            keys[i] = k; sizes[i] = r.nextInt(1, 70_000); minutes[i] = 29_000_000 + r.nextInt(-5, 5000); pinned[i] = r.nextBoolean()
            i++
        }
        val strings = mutableListOf(
            TileIndexSnapshot.StringEntry("bare", 3, -2, false), // a minute before 1970 survives (zigzag)
            TileIndexSnapshot.StringEntry("a/glyphs/Noto Sans Bold/256-511.pbf", 9, 5, true),
            TileIndexSnapshot.StringEntry("a/glyphs/Noto Sans Bold/256-511.pbf", 9, 5, true), // listed twice: once
        )
        val e = TileIndexSnapshot.Entries(groups, keys.copyOf(), sizes.copyOf(), minutes.copyOf(), pinned.copyOf(), n, strings)
        val out = ByteArrayOutputStream()
        assertEquals(n + 2, TileIndexSnapshot.write(out, e, 123L, "tiles"))
        val bytes = out.toByteArray()
        val text = bytes.decodeToString(0, 400)
        assertTrue(text.startsWith("DYSTOPO-TILE-INDEX\nformat: 2\nwritten: 123\ngeneration: 1\ncomplete: true\nroot: tiles\nentries: ${n + 2}\n"), text)
        val back = TileIndexSnapshot.read(ByteArrayInputStream(bytes))
        assertEquals(n, back.entries.count)
        val want = (0 until n).associate { keys[it] to Triple(sizes[it], minutes[it], pinned[it]) }
        val got = (0 until back.entries.count).associate { back.entries.keys[it] to Triple(back.entries.sizes[it], back.entries.minutes[it], back.entries.pinned[it]) }
        assertEquals(want, got)
        assertEquals(listOf("a/glyphs/Noto Sans Bold/256-511.pbf", "bare"), back.entries.strings.map { it.key })
        assertEquals(-2, back.entries.strings[1].minute)
        assertEquals(n + 2, back.dirs.values.sum())
        assertEquals("123", back.header["written"])
        println("T249 snapshot: ${bytes.size} bytes for ${n + 2} entries, ${"%.1f".format(bytes.size.toDouble() / (n + 2))} B an entry")

        val other = String(bytes, Charsets.ISO_8859_1).replaceFirst("format: 2", "format: 3").toByteArray(Charsets.ISO_8859_1)
        assertEquals("format 3, this build reads 2", assertFailsWith<TileIndexSnapshot.Corrupt> { TileIndexSnapshot.read(ByteArrayInputStream(other)) }.message)
        // T390: a checkpoint carries its cursor.
        val cp = ByteArrayOutputStream()
        TileIndexSnapshot.write(cp, TileIndexSnapshot.Entries(groups, keys.copyOf(), sizes.copyOf(), minutes.copyOf(), pinned.copyOf(), n, mutableListOf()), 5L, "tiles", generation = 9, walkCursor = "a/dem/14/2010")
        val cpr = TileIndexSnapshot.read(cp.toByteArray())
        assertEquals(9L, cpr.generation)
        assertEquals("a/dem/14/2010", cpr.walkCursor)
        assertNull(back.walkCursor)
        assertEquals("not a tile index", assertFailsWith<TileIndexSnapshot.Corrupt> { TileIndexSnapshot.read(ByteArrayInputStream("hello\n".toByteArray())) }.message)
        assertEquals("bytes after the checksum", assertFailsWith<TileIndexSnapshot.Corrupt> { TileIndexSnapshot.read(ByteArrayInputStream(bytes + 0)) }.message)
    }

    @Test
    fun theTwoWalkersFindTheSameFiles() {
        val r = Random(3)
        for (i in 0 until 400) {
            val f = File(dir, "s_${i % 3}/dem@v${i % 2}/${10 + i % 4}/${i % 17}/${i}.png")
            f.parentFile.mkdirs()
            f.writeBytes(ByteArray(r.nextInt(1, 300)))
            f.setLastModified(1_700_000_000_000L + i * 61_000L)
        }
        File(dir, "s_0/glyphs/Noto Sans Regular").mkdirs()
        File(dir, "s_0/glyphs/Noto Sans Regular/0-255.pbf").writeBytes(ByteArray(12))
        File(dir, "s_1/empty/12/4").mkdirs()
        File(dir, "s_0/dem@v0/10/0/0.png.7.tmp").writeBytes(ByteArray(1))
        File(dir, "top").writeBytes(ByteArray(2))
        fun run(w: TileWalker): Map<String, Pair<Long, Long>> {
            val out = HashMap<String, Pair<Long, Long>>()
            w.walk(dir) { key, bytes, mtime, path ->
                assertTrue(File(path).isFile, path)
                assertTrue(out.put(key, bytes to mtime / 1000) == null, "once: $key")
            }
            return out
        }
        val nio = run(NioWalker)
        val list = run(ListWalker(ListWalker::jvmStat))
        assertEquals(403, nio.size)
        assertEquals(nio, list)
        assertTrue("s_0/dem@v0/10/0/0.png.7.tmp" in list, "the walk itself sorts out the .tmp files")
    }

    @Test
    fun twoHundredThousandEntriesWrittenAndRead() {
        val n = 200_000
        val keys = LongArray(n) { TileIndex.packedOf(it % 2, 9 + (it / 65536), 1000 + (it / 256) % 256, it % 256) }
        val e = TileIndexSnapshot.Entries(listOf("fill/dem@f1" to "png", "fill/osm@f1" to "pbf"), keys, IntArray(n) { 1024 }, IntArray(n) { 29_000_000 + it % 1000 }, BooleanArray(n), n, mutableListOf())
        val t0 = System.nanoTime()
        val out = ByteArrayOutputStream()
        TileIndexSnapshot.write(out, e, 1L, "tiles")
        val t1 = System.nanoTime()
        val back = TileIndexSnapshot.read(ByteArrayInputStream(out.toByteArray()))
        val t2 = System.nanoTime()
        assertEquals(n, back.entries.count)
        val ix = TileIndex()
        val g = intArrayOf(ix.internGroup("fill/dem@f1", "png"), ix.internGroup("fill/osm@f1", "pbf"))
        for (i in 0 until n) ix.addPacked(TileIndex.withGroup(back.entries.keys[i], g[TileIndex.groupOfKey(back.entries.keys[i])]), back.entries.sizes[i], back.entries.minutes[i], false)
        val t3 = System.nanoTime()
        assertEquals(n, ix.count)
        assertTrue(ix.find("fill/osm@f1/9/1000/1.pbf") != TileIndex.NONE)
        println("T249 snapshot of $n: ${out.size()} bytes (${"%.1f".format(out.size().toDouble() / n)} B an entry), write ${(t1 - t0) / 1_000_000} ms, read ${(t2 - t1) / 1_000_000} ms, filed ${(t3 - t2) / 1_000_000} ms")
    }

    private fun waitFor(ms: Long = 10_000, f: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (f()) return true
            Thread.sleep(10)
        }
        return f()
    }
}
