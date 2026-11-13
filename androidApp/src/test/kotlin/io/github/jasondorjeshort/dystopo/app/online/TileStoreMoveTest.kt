package io.github.jasondorjeshort.dystopo.app.online

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T267 (ADR 0060 W2): the online store's move from `cache/online/tiles` to `no_backup/tiles` ([TileStoreMove], run by
 * [TileCache]'s index thread): the rename with its snapshot, the copy and swap when the rename is refused (`EXDEV`), the
 * copy resumed after it stopped, the trees served meanwhile, and the ownership pass.
 */
class TileStoreMoveTest {
    private val root: File = Files.createTempDirectory("tilemove").toFile()
    private val oldDir = File(root, "cache/online/tiles")
    private val newDir = File(root, "no_backup/tiles")
    private var now = 1_000_000_000L

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    /** The store as a build before T267 left it: [n] tiles over a few routes and columns, and its snapshot. */
    private fun oldStore(n: Int = 300): Map<String, ByteArray> {
        val c = TileCache(oldDir, 100_000_000, snapshot = true, clock = { now }).also { it.awaitReady() }
        val want = LinkedHashMap<String, ByteArray>()
        for (i in 0 until n) {
            val route = if (i % 5 == 4) "osm@o1" else "dem@v1"
            val ext = if (i % 5 == 4) "pbf" else "png"
            val k = "s_1/$route/12/${650 + i / 50}/${1400 + i % 50}.$ext"
            val b = ByteArray(40 + i % 17) { (i + it).toByte() }
            c.put(k, b)
            want[k] = b
            now += 60_000
        }
        assertEquals("written", c.saveSnapshot("test"))
        return want
    }

    private fun moved(move: TileStoreMove) = TileCache(newDir, 100_000_000, snapshot = true, move = move, clock = { now })

    private fun filesUnder(d: File): Map<String, ByteArray> =
        if (!d.isDirectory) emptyMap() else d.walkTopDown().filter { it.isFile }.associate { it.relativeTo(d).invariantSeparatorsPath to it.readBytes() }

    private fun assertSame(want: Map<String, ByteArray>, got: Map<String, ByteArray>) {
        assertEquals(want.keys, got.keys)
        for ((k, v) in want) assertContentEquals(v, got[k], k)
    }

    @Test
    fun theTreeIsRenamedWithItsSnapshotWhichStillValidates() {
        val want = oldStore()
        val times = want.keys.associateWith { File(oldDir, it).lastModified() }
        val m = TileStoreMove(oldDir)
        val c = moved(m).also { it.awaitReady() }
        waitFor { m.settled }
        assertEquals(TileStoreMove.State.DONE, m.state)
        assertEquals("rename", m.method)
        assertEquals(times, want.keys.associateWith { File(newDir, it).lastModified() }, "the rename keeps the times (the order of eviction)")
        assertFalse(oldDir.exists(), "the old tree is gone")
        assertFalse(File(root, "cache/online").exists(), "and online/ with it, once empty")
        assertSame(want, filesUnder(newDir))
        assertTrue(File(root, "no_backup/tiles.index").isFile, "the snapshot moved beside the tree")
        assertTrue(c.fromSnapshot, "the moved snapshot validates against the new tree (its header holds the name only): ${c.snapshotLoad}")
        assertEquals(want.size, c.size)
        for ((k, v) in want) assertContentEquals(v, c.get(k), k)
        val j = c.moveJson()!!
        assertEquals("files", j["location"])
        assertEquals("done", j["moveState"])
        assertNull(j["groupFixed"], "no ownership pass without a GroupFixer (the JVM)")
        @Suppress("UNCHECKED_CAST") val mj = j["move"] as Map<String, Any?>
        assertEquals(want.size.toLong(), mj["files"])
        assertEquals(want.values.sumOf { it.size }.toLong(), mj["bytes"])
        assertEquals("done", m.readMarker(newDir)["state"])

        // The next launch has nothing to do and reads the figures from the marker.
        val m2 = TileStoreMove(oldDir)
        val c2 = moved(m2).also { it.awaitReady() }
        assertTrue(m2.settled, "nothing to do")
        assertNull(m2.startedAt)
        assertEquals("done", c2.moveJson()!!["moveState"])
        @Suppress("UNCHECKED_CAST") val mj2 = c2.moveJson()!!["move"] as Map<String, Any?>
        assertEquals(want.size.toLong(), mj2["files"], "kept in the marker")
        assertEquals(false, mj2["thisLaunch"])
        assertTrue(c2.fromSnapshot)
    }

    @Test
    fun aNewInstallHasNothingToMove() {
        val m = TileStoreMove(oldDir)
        val c = moved(m)
        assertTrue(c.ready, "an empty store is ready at once, no thread")
        assertEquals(TileStoreMove.State.DONE, m.state)
        assertEquals("none", m.method)
        assertEquals("files", c.moveJson()!!["location"])
        c.put("s_1/dem@v1/1/0/0.png", ByteArray(3))
        assertTrue(File(newDir, "s_1/dem@v1/1/0/0.png").isFile)
    }

    @Test
    fun aRefusedRenameCopiesWhileBothTreesServe() {
        val want = oldStore(400)
        val times = want.keys.associateWith { File(oldDir, it).lastModified() }
        val keys = want.keys.toList()
        val reached = CountDownLatch(1)
        val go = CountDownLatch(1)
        val m = TileStoreMove(oldDir, rename = { _, _ -> throw IOException("EXDEV (Invalid cross-device link)") }, copyHook = { n ->
            if (n == 100L) {
                reached.countDown()
                go.await(30, TimeUnit.SECONDS)
            }
        })
        val c = moved(m)
        assertTrue(reached.await(30, TimeUnit.SECONDS), "the copy reached its 100th file")
        // Halfway: the index is whole over both trees, the old tree still serves what is not copied yet.
        assertTrue(c.ready)
        assertFalse(c.fromSnapshot, "a copy walks both trees")
        assertEquals(want.size, c.size)
        assertEquals("copying", c.moveJson()!!["moveState"])
        assertEquals("cache and files", c.moveJson()!!["location"])
        assertNotNull(c.moveRoot)
        for (k in keys.drop(50)) assertContentEquals(want[k], c.get(k), "$k read during the copy") // the first 50 left untouched for their times
        val last = keys.last()
        assertTrue(File(oldDir, last).isFile, "not copied yet")
        assertTrue(c.contains(last))
        // A write during the copy goes to the new tree and the old copy goes; a remove goes from both.
        c.put(last, ByteArray(7) { 9 })
        assertFalse(File(oldDir, last).exists())
        assertContentEquals(ByteArray(7) { 9 }, File(newDir, last).readBytes())
        val gone = keys[keys.size - 2]
        assertTrue(c.remove(gone))
        assertFalse(File(oldDir, gone).exists())
        assertFalse(c.contains(gone))
        go.countDown()
        waitFor { m.settled }
        assertEquals(TileStoreMove.State.DONE, m.state)
        assertNull(c.moveRoot)
        assertFalse(oldDir.exists(), "the old tree is deleted at the end")
        val got = filesUnder(newDir)
        val expect = LinkedHashMap(want).apply { remove(gone); put(last, ByteArray(7) { 9 }) }
        assertSame(expect, got)
        for (k in keys.take(50)) assertEquals(times[k], File(newDir, k).lastModified(), "$k keeps its time (not read since)")
        assertEquals("copy", m.method)
        assertTrue(m.renameError!!.contains("EXDEV"))
        assertEquals(want.size.toLong() - 2, m.copied, "every file once; the written and the removed ones not")
        assertTrue(File(root, "no_backup/tiles.index").isFile, "the snapshot is written once the copy is done")
        assertEquals("files", c.moveJson()!!["location"])
        assertEquals(expect.size, c.size)
    }

    @Test
    fun aStoppedCopyResumesAtTheNextLaunch() {
        val want = oldStore(500)
        // The first launch's copy stops after 120 files (as a kill or a full disk would).
        val m1 = TileStoreMove(oldDir, rename = { _, _ -> throw IOException("EXDEV") }, copyHook = { n -> if (n == 120L) throw IOException("No space left on device") })
        val c1 = moved(m1)
        waitFor { m1.settled }
        assertEquals(TileStoreMove.State.FAILED, m1.state)
        assertEquals("failed", c1.moveJson()!!["moveState"])
        assertTrue(m1.error!!.contains("No space"))
        for ((k, v) in want) assertContentEquals(v, c1.get(k), "$k served from either tree after the failure")
        assertEquals("failed", m1.readMarker(newDir)["state"])
        val inNew = filesUnder(newDir).size
        assertEquals(120, inNew, "the files copied stay")

        // The next launch goes on (the rename is not tried again: the marker says copy), and nothing is lost.
        val m2 = TileStoreMove(oldDir)
        val c2 = moved(m2)
        waitFor { m2.settled }
        assertEquals(TileStoreMove.State.DONE, m2.state)
        assertEquals("copy", m2.method)
        assertEquals(want.size.toLong() - 120, m2.copied)
        assertFalse(oldDir.exists())
        assertSame(want, filesUnder(newDir))
        assertEquals(want.size, c2.size)
        assertEquals("done", m2.readMarker(newDir)["state"])
    }

    @Test
    fun aRenameLeftHalfDoneFinishesTheSnapshotsMove() {
        val want = oldStore(50)
        // A kill after the tree's rename and before its snapshot's.
        Files.createDirectories(newDir.parentFile.toPath())
        assertTrue(oldDir.renameTo(newDir))
        val m = TileStoreMove(oldDir)
        val c = moved(m).also { it.awaitReady() }
        assertTrue(File(root, "no_backup/tiles.index").isFile)
        assertFalse(File(root, "cache/online/tiles.index").exists())
        assertTrue(c.fromSnapshot, "${c.snapshotLoad}")
        assertEquals(want.size, c.size)
    }

    @Test
    fun theOwnershipPassGivesEveryEntryTheAppsGroupAndClearsSetgid() {
        oldStore(30)
        val fake = FakeGroups(own = 10_123, cacheGid = 20_123)
        val m = TileStoreMove(oldDir, groups = fake)
        moved(m).also { it.awaitReady() }
        waitFor { m.settled }
        assertEquals(true, m.groupFixed)
        val g = m.group!!
        assertEquals(30L, g["files"])
        assertEquals(30L, g["filesFixed"])
        assertEquals(g["dirs"], g["dirsFixed"])
        assertEquals(g["dirs"], g["setgidCleared"])
        assertEquals(20_123, g["otherGidSeen"])
        assertTrue(fake.gid.values.all { it == 10_123 })
        assertEquals(10_123, fake.gid[File(root, "no_backup/tiles.index").path], "the snapshot carried beside the tree too")
        assertEquals(1L, g["besideFixed"])
        assertTrue(fake.setgid.isEmpty())
        assertEquals("done", m.readMarker(newDir)["group"])

        // Refused for one file: counted, the rest fixed, groupFixed false.
        root.resolve("no_backup").deleteRecursively()
        oldStore(10)
        val refusing = FakeGroups(own = 10_123, cacheGid = 20_123, refuse = { it.endsWith("1400.png") })
        val m2 = TileStoreMove(oldDir, groups = refusing)
        moved(m2).also { it.awaitReady() }
        waitFor { m2.settled }
        assertEquals(false, m2.groupFixed)
        assertEquals(1L, m2.group!!["failed"])
    }

    @Test
    fun aSmallFileIsCarriedOnceAndTheNewOneWins() {
        val old = File(root, "cache/online/versions.json").apply { parentFile.mkdirs(); writeText("old") }
        val new = File(root, "no_backup/versions.json")
        TileStoreMove(oldDir).carry(old, new)
        assertEquals("old", new.readText())
        assertFalse(old.exists())
        old.writeText("stale")
        TileStoreMove(oldDir, rename = { _, _ -> throw IOException("EXDEV") }).carry(old, new)
        assertEquals("old", new.readText(), "a file already there wins")
        assertFalse(old.exists())
        old.writeText("copied")
        new.delete()
        TileStoreMove(oldDir, rename = { _, _ -> throw IOException("EXDEV") }).carry(old, new)
        assertEquals("copied", new.readText(), "a refused rename copies")
        assertFalse(old.exists())
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!what()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(5)
        }
    }

    /** Groups as Android leaves a renamed `cache` tree: every entry the cache GID, every directory setgid. */
    private class FakeGroups(val own: Int, val cacheGid: Int, val refuse: (String) -> Boolean = { false }) : GroupFixer {
        val gid = HashMap<String, Int>()
        val dirs = HashSet<String>()
        val cleared = HashSet<String>()
        val setgid: Set<String> get() = dirs - cleared
        override val ownGid get() = own
        override fun stat(path: String): IntArray? {
            val f = File(path)
            if (!f.exists()) return null
            val dir = f.isDirectory
            if (dir) dirs += path
            val mode = if (dir) 0x1f9 or (if (path in cleared) 0 else TileStoreMove.S_ISGID) else 0x1b0
            return intArrayOf(gid.getOrPut(path) { cacheGid }, mode, if (dir) 1 else 0)
        }
        override fun chown(path: String, gid: Int) {
            if (refuse(path)) throw IOException("EPERM")
            this.gid[path] = gid
        }
        override fun chmod(path: String, mode: Int) {
            if (mode and TileStoreMove.S_ISGID == 0) cleared += path
        }
    }
}
