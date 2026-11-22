package io.github.jasondorjeshort.dystopo.app.storage

import java.io.File

/**
 * T328: walks and recursive deletes over the app's cache trees that tolerate an entry another thread deleted.
 *
 * The stdlib's `File.walk*()` (and `deleteRecursively()`, which is `walkBottomUp()`) checks `isDirectory` on an entry and
 * then asserts it again when it enters it (`FileTreeWalk.DirectoryState`: "rootDir must be verified to be directory
 * beforehand"). A directory deleted between the two checks throws an `AssertionError` where assertions are on (a debuggable
 * build: D8 forces them on, so `kotlin._Assertions.ENABLED` is true; R8 removes the assertion from a release build), and on
 * a plain thread that kills the app: the launch crash of three closing sweeps after the emulator guard's reboot, whose
 * stack was not kept (`docs/ideas/2026-11-19-launch-crash-rootdir-after-the-guards-reboot.md`). Every walk and recursive
 * delete of the app goes through here. A directory is listed with `listFiles`, and one that has gone (null) is skipped; a
 * file that has gone is skipped too.
 */
object FileTrees {
    /** Calls [visit] with every regular file under [root] (none when [root] is not a directory), depth-first, parents first. */
    fun forEachFile(root: File, visit: (File) -> Unit) {
        val dirs = ArrayDeque<File>()
        dirs.addLast(root)
        while (dirs.isNotEmpty()) {
            val dir = dirs.removeLast()
            val children = dir.listFiles() ?: continue // gone, not a directory, or unreadable: nothing under it
            for (f in children) {
                if (f.isDirectory) dirs.addLast(f) else if (f.isFile) visit(f)
            }
        }
    }

    /** Every regular file under [root], as [forEachFile] finds them. */
    fun files(root: File): List<File> = ArrayList<File>().also { out -> forEachFile(root) { out += it } }

    /**
     * Deletes everything under [root], bottom-up, and [root] itself unless [keepRoot]; an entry already gone is skipped.
     * [onFile] hears each regular file this call deleted. Returns how many regular files it deleted. A directory that
     * still holds something (a write landed in it meanwhile) stays.
     */
    fun deleteTree(root: File, keepRoot: Boolean = false, onFile: (File) -> Unit = {}): Int {
        var n = 0
        val order = ArrayList<File>() // directories, parents first; deleted in reverse so children go first
        val dirs = ArrayDeque<File>()
        dirs.addLast(root)
        while (dirs.isNotEmpty()) {
            val dir = dirs.removeLast()
            val children = dir.listFiles() ?: continue
            order += dir
            for (f in children) {
                if (f.isDirectory) dirs.addLast(f)
                else if (f.delete()) { n++; onFile(f) }
            }
        }
        for (i in order.indices.reversed()) {
            val d = order[i]
            if (d != root || !keepRoot) d.delete()
        }
        if (!keepRoot && root.isFile && root.delete()) { n++; onFile(root) } // a root that is a file
        return n
    }
}
