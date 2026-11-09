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

/** T249: the tile index kept on disk ([TileIndexSnapshot]) and [TileCache]'s use of it; the two walkers. */
class TileIndexSnapshotTest {
    private val root: File = Files.createTempDirectory("tileindex").toFile()
    private val dir = File(root, "online/tiles")
    private val snap = File(root, "online/tiles.index")
    private var now = 1_000_000_000L

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun cache(max: Long = 100_000_000) = TileCache(dir, max, snapshot = true, clock = { now }).also { it.awaitReady() }

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
    fun changesNotWrittenBeforeAKillMeanAWalk() {
        val a = seeded()
        a.saveSnapshot("test")
        a.put("s_1/dem@v1/12/650/1499.png", ByteArray(9)) // after the write; then the process dies
        assertTrue(File(root, "online/tiles.index.dirty").exists(), "the first change after a write makes the marker")
        val b = cache()
        assertFalse(b.fromSnapshot)
        assertEquals("the last run changed the index after its last write", b.snapshotLoad?.get("why"))
        assertTrue(b.contains("s_1/dem@v1/12/650/1499.png"))
        assertEquals(keys(a), keys(b))
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
    fun aWriteInterruptedByAKillIsDiscarded() {
        val a = seeded()
        a.saveSnapshot("test")
        File(root, "online/tiles.index.tmp").writeBytes(snap.readBytes().copyOf(100)) // half a write
        val b = cache()
        assertFalse(b.fromSnapshot)
        assertEquals("a write of it was interrupted", b.snapshotLoad?.get("why"))
        assertFalse(File(root, "online/tiles.index.tmp").exists())
        assertEquals(keys(a), keys(b))
        assertTrue(waitFor { b.lastSave?.get("reason") == "walk" })
        assertTrue(cache().fromSnapshot, "the walk's own snapshot is read next time")
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
        assertTrue((b.snapshotLoad?.get("why") as String).startsWith("disagrees with the directory: the directory 's_1/dem@v1/12/651' holds 6 files"), "${b.snapshotLoad}")
        assertTrue(b.contains("s_1/dem@v1/12/651/1410.png"))

        assertTrue(waitFor { b.lastSave?.get("reason") == "walk" })
        File(dir, "other_9/dem/3/1/2.png").apply { parentFile.mkdirs() }.writeBytes(ByteArray(4)) // a new directory
        val c = cache()
        assertFalse(c.fromSnapshot)
        assertTrue((c.snapshotLoad?.get("why") as String).contains("the directory '' holds 2 files, the snapshot 1"), "${c.snapshotLoad}")

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
        assertTrue(text.startsWith("DYSTOPO-TILE-INDEX\nformat: 1\nwritten: 123\nroot: tiles\nentries: ${n + 2}\n"), text)
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

        val other = String(bytes, Charsets.ISO_8859_1).replaceFirst("format: 1", "format: 2").toByteArray(Charsets.ISO_8859_1)
        assertEquals("format 2, this build reads 1", assertFailsWith<TileIndexSnapshot.Corrupt> { TileIndexSnapshot.read(ByteArrayInputStream(other)) }.message)
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
