package io.github.jasondorjeshort.dystopo.app.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T328: [FileTrees] walks and deletes over a tree another thread changes, where the stdlib's walk asserts. */
class FileTreesTest {
    private val dir: File = Files.createTempDirectory("file-trees").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun file(path: String, bytes: Int = 3) = File(dir, path).apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test
    fun walksEveryFile() {
        file("a/1.pbf"); file("a/b/2.pbf"); file("c/3.pbf"); file("4.txt")
        File(dir, "empty").mkdirs()
        assertEquals(setOf("a/1.pbf", "a/b/2.pbf", "c/3.pbf", "4.txt"), FileTrees.files(dir).map { it.relativeTo(dir).invariantSeparatorsPath }.toSet())
        assertEquals(emptyList(), FileTrees.files(File(dir, "missing")))
        assertEquals(emptyList(), FileTrees.files(File(dir, "4.txt"))) // a file is not a tree
    }

    /** The case behind the launch crash: a directory listed by its parent is deleted before the walk enters it. */
    @Test
    fun aDirectoryDeletedInMidWalkIsSkipped() {
        file("x/1.pbf"); file("y/sub/2.pbf"); file("y/3.pbf"); file("z/4.pbf")
        val seen = ArrayList<String>()
        var deleted: String? = null
        FileTrees.forEachFile(dir) { f ->
            seen += f.relativeTo(dir).invariantSeparatorsPath
            if (deleted == null) {
                // The first file visited is under one of x, y, z (all three listed already): delete another of them whole.
                val first = f.relativeTo(dir).invariantSeparatorsPath.substringBefore('/')
                val other = listOf("y", "z", "x").first { it != first }
                assertTrue(File(dir, other).deleteRecursively())
                deleted = other
            }
        }
        assertTrue(seen.none { it.startsWith("$deleted/") }, "nothing under the deleted directory: $seen")
        assertEquals(4 - (if (deleted == "y") 2 else 1), seen.size, "$seen")
    }

    @Test
    fun deleteTreeTakesEverythingAndCountsFiles() {
        file("a/1.pbf"); file("a/b/2.pbf"); file("c/3.pbf")
        val heard = ArrayList<String>()
        assertEquals(3, FileTrees.deleteTree(dir, keepRoot = true) { heard += it.name })
        assertEquals(setOf("1.pbf", "2.pbf", "3.pbf"), heard.toSet())
        assertTrue(dir.isDirectory)
        assertEquals(0, dir.listFiles()!!.size)
        file("d/4.pbf")
        val sub = File(dir, "d")
        assertEquals(1, FileTrees.deleteTree(sub))
        assertFalse(sub.exists())
        assertEquals(0, FileTrees.deleteTree(File(dir, "missing")))
    }

    /** Two deletes of one tree at once (two collections of one stale directory) both end without an error. */
    @Test
    fun twoDeletesOfOneTreeAtOnce() {
        for (i in 0 until 40) for (j in 0 until 20) file("t/$i/$j/x.pbf", 1)
        val tree = File(dir, "t")
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val counts = IntArray(2)
        val threads = (0..1).map { k -> Thread { try { counts[k] = FileTrees.deleteTree(tree) } catch (t: Throwable) { errors += t } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), "$errors")
        // Each file deleted by at least one of the two (on Windows both deletes of one file can report true: delete-pending).
        assertTrue(counts.sum() in 800..1600, "${counts.toList()}")
        assertFalse(tree.exists())
    }
}
