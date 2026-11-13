package io.github.jasondorjeshort.dystopo.app.online

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * A size-capped disk cache of fetched tiles, evicting the least recently used first.
 *
 * Entries are plain files under [dir] at their key's path (`<server>/<route>/<z>/<x>/<y>`), so a
 * restart finds them again: the index is rebuilt from the directory, and a hit touches the file's mtime (at most once a
 * minute per entry) so the order survives a restart. Writes go to a temporary file renamed into place, so a reader never
 * sees half a tile. Thread-safe.
 *
 * T91: [maxBytes] caps the entries [keep] does not hold (the pinned tiles of offline areas have their own budget,
 * `pinnedCacheMb`, checked when an area downloads); [pinnedBytes] is what the kept entries take. Each entry
 * remembers whether it was kept when last seen; [recountPinned] refreshes that after the pins change.
 *
 * T112: a cache also reports to the one cache budget over the phone's caches ([onWrite],
 * `io.github.jasondorjeshort.dystopo.app.cachebudget.CacheBudget`), which evicts across caches with [evictOldest]; [maxBytes]
 * stays this cache's own ceiling.
 *
 * T242: **the index is built off the main thread.** The constructor returns at once in [State.INDEXING] (or [State.READY]
 * over an empty directory) and a thread of its own (`tile-index-<dir>`) walks the directory (one stat per file) into the
 * index in batches of [WALK_BATCH] under short holds of the lock, never holding it for the walk; then the state is
 * [State.READY], the cap is applied ([trim]) and the actions queued with [whenReady] run. Meanwhile: [get] reads a file the
 * walk has not reached and adds its entry; [put] writes and adds its entry at once; the walk skips a key already held or
 * removed since ([remove], [clearVariants]); [contains] answers from a stat of the file; the getters ([bytes], [size],
 * [pinnedBytes], [pinnedSize], [json]) never wait and are partial ([partial]). The operations over the whole index
 * ([recountPinned], [clear], [clearUnkept], [clearWhere], [evictOldest], [oldestTouch], [bytesWhere], [byPrefix],
 * [forEachKey], [awaitReady]) wait for [State.READY] off the main thread, as long as the walk makes progress
 * ([WAIT_STALL_MS] without a file is a [TimeoutException] naming the caller), and on the main thread before then are a
 * programming error that names its caller (T229's rule): read the getters, or call them from a thread. [put] does not trim
 * while indexing (the totals are partial); the cap is applied once the index is whole.
 *
 * T242: the index is compact ([TileIndex]: a struct of arrays, the tile keys packed, eviction by touch-minute buckets), and
 * [trim] has hysteresis: past [maxBytes] it trims to [maxBytes] less [slackOf] (2 %), so a put at the cap does not trim
 * per tile.
 *
 * T249: **the index kept on disk.** With [snapshot] on (the online cache), the index is written to `<dir>.index` beside the
 * directory ([snapshotFile]; [TileIndexSnapshot] is the format) when it changed: on the owner's interval and at the app's
 * stop ([saveSnapshot], called by `OnlineService`), and after a walk. A launch reads it in one pass on the index's thread
 * instead of walking, after a sample of its directories and entries agreed with the disk ([TileIndexSnapshot.validate]);
 * it is discarded, and the directory walked, when it is corrupt or disagrees, when a write of it was interrupted (its
 * `.tmp` is left), and when the last run changed the index after its last write (the `.dirty` marker, made at the first
 * change after a write and deleted by the next). Over an index read from it, a key not in the index is still looked for
 * on disk ([get], [contains]), and an entry whose file is gone is dropped on [get]. The pinned flags it kept are asked
 * again once it is read ([recountPinned]: navigation's pins do not outlive the process). The walk itself lists each
 * directory and stats its files ([TileWalker]; on Android `File.list` plus `Os.lstat`).
 *
 * T267 (ADR 0060 W2): **the store moved out of the cache.** With [move] set (the online cache's), the index's thread first
 * moves the old tree ([TileStoreMove.from], `cacheDir/online/tiles`) with its snapshot and marker into [dir]
 * (`noBackupFilesDir/tiles`): one rename; when that is refused (another file system, a project ID: `EXDEV`), a copy in key
 * order while both trees are served ([moveRoot]: a read tries [dir] and then the old tree, a write goes to [dir], a delete
 * goes from both), each file deleted from the old tree once its copy is in place, resumable from the marker
 * ([TileStoreMove.marker]) after a kill; then an ownership pass sets each directory and file to the app's own group and
 * clears the directories' setgid bit ([GroupFixer]; Android gives `cache` the cache GID and setgid, so a renamed tree
 * would go on counting as cache). [whenReady]'s contract is unchanged: the actions run once the index is whole, after the
 * move has put the tree in place (the copy itself goes on after that, under the index).
 */
class TileCache(
    val dir: File,
    maxBytes: Long,
    /** T242: whether the calling thread is the UI thread (the JVM's tests pass their own; there is no main looper there). */
    private val onMain: () -> Boolean = ::isUiThread,
    /** T242: called by the walk after each file it indexed, with the count so far (a test holds the walk with it). */
    private val walkHook: ((Long) -> Unit)? = null,
    /** T249: keep the index on disk beside [dir] ([snapshotFile]); the online cache's. */
    snapshot: Boolean = false,
    /** T249: how the directory is walked when there is no snapshot to read. */
    private val walker: TileWalker = TileWalker.forPlatform(),
    /** T267: where the store was before; moved into [dir] by the index's thread before the walk (the online cache's). */
    val move: TileStoreMove? = null,
    /** Last, so the callers' trailing lambda is the clock (as before T242). */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The cap; [resize] changes it (a setting, T41). */
    @Volatile var maxBytes: Long = maxBytes
        private set

    /** T75 hook: entries it says to keep (tiles of pinned areas of interest, `areas/Areas.kt`) are skipped by eviction. */
    @Volatile var keep: (String) -> Boolean = { io.github.jasondorjeshort.dystopo.app.areas.Areas.isPinned(it) }

    /** T242: where the index is: being built from the directory, or whole. */
    enum class State { INDEXING, READY }

    /** T242: [State.INDEXING] until the walk has filed every file, then [State.READY]. */
    @Volatile var state: State = State.INDEXING
        private set

    /** T242: the index is whole ([State.READY]). */
    val ready: Boolean get() = state == State.READY

    /** T242: the totals ([bytes], [size], [pinnedBytes], [pinnedSize]) count only what is indexed so far. */
    val partial: Boolean get() = state != State.READY

    /** Sets the cap and evicts the least recently used entries over it (T242: once the index is whole, when it is not yet). */
    fun resize(bytes: Long) {
        maxBytes = bytes
        if (ready) recountPinned()
    }

    private val lock = Any()
    private val index = TileIndex()
    private val readyLatch = CountDownLatch(1)

    /** T242: actions [whenReady] queued, run on the index's thread once it is whole (under [lock]). */
    private val pending = ArrayList<Pair<String, () -> Unit>>()

    /** T242: keys removed while indexing, which the walk must not file again (under [lock]; null once ready). */
    private var removed: HashSet<String>? = HashSet()

    // T242: the totals, written under the lock, read without it.
    @Volatile private var total = 0L
    @Volatile private var pinnedTotal = 0L
    @Volatile private var pinnedCount = 0
    @Volatile private var entries = 0

    /** T242: files the walk has seen so far, and how long it took (ms, once ready); the error that ended it early, if any. */
    @Volatile var walked = 0L
        private set
    @Volatile var walkMs: Long? = null
        private set
    @Volatile var indexError: String? = null
        private set
    private val startedNs = System.nanoTime()

    // --- T249: the snapshot ---

    /** T249: where the index is kept on disk (`<dir>.index` beside [dir], so it moves with it), or null when it is not. */
    val snapshotFile: File? = if (snapshot) File(dir.absoluteFile.parentFile, "${dir.name}.index") else null
    private val snapshotTmp: File? = snapshotFile?.let { File(it.parentFile, it.name + ".tmp") }
    /** T249: made at the first change after a write of the snapshot, deleted by the next: present at launch, the last run's changes were not written. */
    private val dirtyMarker: File? = snapshotFile?.let { File(it.parentFile, it.name + ".dirty") }

    /** T249: this launch's index was read from the snapshot (no walk). */
    @Volatile var fromSnapshot = false
        private set

    /** T249: what this launch did with the snapshot: read it (its figures) or why it was not used. */
    @Volatile var snapshotLoad: Map<String, Any?>? = null
        private set

    /** T249: the last write of the snapshot (its reason, figures, or error). */
    @Volatile var lastSave: Map<String, Any?>? = null
        private set
    val saves = AtomicLong()

    /** T249 (debug): the next write of the snapshot stops this long halfway (`POST /debug/cache/snapshot?holdMs=`), so a kill mid-write can be tried. */
    @Volatile var writeHoldMs = 0L

    // Under [lock]: changes to the index (entries, sizes, pins) since the process started, and as of the last write.
    private var changes = 0L
    private var savedChanges = 0L
    private var markerOn = false
    /** Under [lock]: the index's thread has looked for the last run's marker (until then a change makes none: it would hide it). */
    private var markerChecked = false
    /** T249: a touch changed an entry's minute since the last write (written then, but no reason to walk after a kill). */
    @Volatile private var touched = false
    private val saveLock = Any()

    /** T249: under [lock]: the index changed (an entry, a size, a pin); the first change after a write makes the marker. */
    private fun changed() {
        changes++
        if (!markerOn && markerChecked && dirtyMarker != null) {
            markerOn = true
            runCatching { dirtyMarker!!.parentFile?.mkdirs(); dirtyMarker!!.createNewFile() }
        }
    }

    private fun fileOf(key: String) = File(dir, key)

    // --- T267: two roots while the store moves ---

    /**
     * T267: the old tree while it is still served (the move not yet run, or a copy under way or failed); null once the
     * store is wholly in [dir]. Changed under [moveLock].
     */
    @Volatile var moveRoot: File? = null
        private set

    /** T267: the index's thread has a move (or what is left of one) to do before the index ([TileStoreMove.needed]). */
    private val moving: Boolean = move?.needed(dir) == true

    /** T267: held around a write's rename into [dir], a delete from both trees and the copy's swap of one file. */
    private val moveLock = Any()

    /** T267: [key]'s bytes from [dir], else (while the old tree is served) from there; the file read, or null. */
    private fun readFile(key: String): Pair<ByteArray, File>? {
        val f = fileOf(key)
        try {
            return f.readBytes() to f
        } catch (_: Exception) {
        }
        val old = moveRoot ?: return null
        val o = File(old, key)
        try {
            return o.readBytes() to o
        } catch (_: Exception) {
        }
        // The rename may have run between the two reads.
        return try { f.readBytes() to f } catch (_: Exception) { null }
    }

    /** T267: whether [key]'s file is in [dir] or (while it is served) in the old tree. */
    private fun fileExists(key: String): Boolean = fileOf(key).isFile || moveRoot?.let { File(it, key).isFile } == true

    /** T267: deletes [key]'s file from [dir] and (while it is served) from the old tree; whether one went. */
    private fun deleteFile(key: String): Boolean {
        if (moveRoot == null) return fileOf(key).delete()
        synchronized(moveLock) {
            val a = fileOf(key).delete()
            val b = moveRoot?.let { File(it, key).delete() } == true
            return a || b
        }
    }

    /** T267: renames a written [tmp] onto [file] ([key]'s, in [dir]); while the old tree is served, its stale copy of [key] goes. */
    private fun placeWrite(key: String, tmp: File, file: File): Boolean {
        fun place(): Boolean {
            if (tmp.renameTo(file)) return true
            file.delete()
            if (tmp.renameTo(file)) return true
            tmp.delete()
            return false
        }
        if (moveRoot == null) return place()
        synchronized(moveLock) {
            val ok = place()
            if (ok) moveRoot?.let { File(it, key).delete() }
            return ok
        }
    }

    /**
     * T267: the move's first part, on the index's thread before anything is read: the old tree renamed into [dir] with its
     * snapshot (then [moveRoot] is null and the index is read or walked as before), or, when the rename is refused or [dir]
     * already holds tiles, a copy begun ([moveRoot] stays: both trees are served until [copyAfterIndex] ends).
     */
    private fun moveFirst() {
        val m = move!!
        val t0 = System.nanoTime()
        val marker = m.readMarker(dir)
        m.begin(clock(), marker)
        try {
            val from = m.from
            var copy = marker["state"] == STATE_COPYING || marker["state"] == STATE_FAILED
            if (!copy && from.isDirectory) {
                if (dir.list().isNullOrEmpty()) {
                    m.writeMarker(dir, mapOf("state" to "renaming"))
                    try {
                        dir.parentFile?.mkdirs()
                        if (dir.isDirectory) dir.delete() // an empty directory left by an earlier launch
                        synchronized(moveLock) {
                            m.rename(from, dir)
                            moveRoot = null
                        }
                        m.method = "rename"
                    } catch (t: Throwable) {
                        m.renameError = "${t.javaClass.simpleName}: ${t.message}"
                        copy = true
                    }
                } else {
                    m.renameError = "the new tree already holds files"
                    copy = true
                }
            }
            if (copy && from.isDirectory) {
                dir.mkdirs()
                m.method = "copy"
                m.state = TileStoreMove.State.COPYING
                m.writeMarker(dir, mapOf("state" to STATE_COPYING, "method" to "copy", "renameError" to m.renameError))
                // The snapshot named the old tree's state; the copy walks both trees instead.
                for (s in TileStoreMove.SNAPSHOT_SUFFIXES) File(from.parentFile, from.name + s).delete()
                m.renameMs = (System.nanoTime() - t0) / 1_000_000
                return
            }
            // Renamed, nothing to rename, or a copy whose last file went before its marker was written: the snapshot, its marker and an interrupted write's .tmp follow the tree.
            for (s in TileStoreMove.SNAPSHOT_SUFFIXES) m.carry(File(from.parentFile, from.name + s), File(dir.parentFile, dir.name + s))
            synchronized(moveLock) { moveRoot = null }
            from.parentFile?.delete() // `online/` once empty (the versions file leaves it with OnlineService)
            dir.mkdirs()
            if (m.method == null) m.method = marker["method"] ?: "none"
            m.state = TileStoreMove.State.DONE
            m.renameMs = (System.nanoTime() - t0) / 1_000_000
            m.writeMarker(dir, mapOf("state" to STATE_DONE, "method" to m.method, "renameError" to m.renameError, "group" to (marker["group"].takeIf { it == STATE_DONE } ?: "pending")))
        } catch (t: Throwable) {
            m.error = "${t.javaClass.simpleName}: ${t.message}"
            m.state = TileStoreMove.State.FAILED
            m.renameMs = (System.nanoTime() - t0) / 1_000_000
            runCatching { m.writeMarker(dir, mapOf("state" to STATE_FAILED, "error" to m.error)) }
        }
    }

    /**
     * T267: the copy, after the index of both trees is whole (on the index's thread): the old tree in key order (a
     * directory's files, then its subdirectories, by name), each file written beside its place in [dir] with its time and
     * renamed in under [moveLock] unless [dir] has a newer one, then deleted from the old tree, and the emptied directories
     * with it; the marker names the last directory done every [COPY_MARK_EVERY]. A failure (no space) leaves both trees
     * served and the next launch goes on. Then the old tree goes, the snapshot is written, and the ownership pass runs.
     */
    private fun copyAfterIndex() {
        val m = move!!
        val old = moveRoot ?: return
        val t0 = System.nanoTime()
        try {
            copyDir(m, old, "")
            synchronized(moveLock) { moveRoot = null }
            old.deleteRecursively() // what is left: emptied directories, and stale files whose key [dir] holds newer
            old.parentFile?.delete()
            m.copyMs = (System.nanoTime() - t0) / 1_000_000
            m.state = TileStoreMove.State.DONE
            m.writeMarker(dir, mapOf("state" to STATE_DONE, "method" to "copy", "renameError" to m.renameError, "group" to "pending"))
            runCatching { saveSnapshot("move", force = true) }
            afterMove()
        } catch (t: Throwable) {
            m.copyMs = (System.nanoTime() - t0) / 1_000_000
            m.error = "${t.javaClass.simpleName}: ${t.message}"
            m.state = TileStoreMove.State.FAILED
            runCatching { m.writeMarker(dir, mapOf("state" to STATE_FAILED, "method" to "copy", "cursor" to m.cursor, "error" to m.error)) }
            m.settled = true
        }
    }

    private var dirsSinceMark = 0

    private fun copyDir(m: TileStoreMove, d: File, prefix: String) {
        val names = d.list()?.sorted() ?: return
        val subdirs = ArrayList<String>()
        for (n in names) {
            val f = File(d, n)
            if (f.isDirectory) {
                subdirs += n
                continue
            }
            if (n.endsWith(".tmp")) { // a write a kill interrupted (writes go to the new tree)
                f.delete()
                continue
            }
            copyOne(m, prefix + n, f)
        }
        m.cursor = prefix.trimEnd('/')
        if (++dirsSinceMark >= COPY_MARK_EVERY) {
            dirsSinceMark = 0
            m.writeMarker(dir, mapOf("state" to STATE_COPYING, "method" to "copy", "renameError" to m.renameError, "cursor" to m.cursor))
        }
        for (s in subdirs) copyDir(m, File(d, s), "$prefix$s/")
        d.delete() // emptied (refused when a write is still in it)
    }

    private fun copyOne(m: TileStoreMove, key: String, src: File) {
        val dst = fileOf(key)
        if (dst.exists()) { // a newer write is in the new tree
            synchronized(moveLock) { src.delete() }
            m.skipped++
            return
        }
        val bytes = try { src.readBytes() } catch (_: IOException) { return } // removed meanwhile
        val mtime = src.lastModified()
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, "${dst.name}.move.tmp")
        tmp.writeBytes(bytes)
        if (mtime > 0) tmp.setLastModified(mtime) // the order of eviction survives the copy
        synchronized(moveLock) {
            when {
                !src.exists() || dst.exists() -> { // removed, or written newer, meanwhile
                    tmp.delete()
                    src.delete()
                }
                !m.place(tmp, dst) -> {
                    tmp.delete()
                    throw IOException("could not place $key in the new tree")
                }
                else -> {
                    src.delete()
                    m.copied++
                    m.copiedBytes += bytes.size
                }
            }
        }
        m.copyHook?.invoke(m.copied)
    }

    /** T267: after the move: the ownership pass once ([TileStoreMove.fixGroups]), and the move's figures kept in its marker. */
    private fun afterMove() {
        val m = move!!
        if (m.state != TileStoreMove.State.DONE) {
            m.settled = true
            return
        }
        m.movedFiles = entries.toLong()
        m.movedBytes = total
        val marker = m.readMarker(dir)
        if (marker["group"] == "pending") m.fixGroups(dir)
        else m.groupFromMarker(marker)
        runCatching {
            m.writeMarker(dir, marker + mapOf(
                "state" to STATE_DONE, "group" to STATE_DONE, "groupFixed" to m.groupFixed?.toString(),
                "files" to (marker["files"] ?: m.movedFiles.toString()), "bytes" to (marker["bytes"] ?: m.movedBytes.toString()),
                "renameMs" to (marker["renameMs"] ?: m.renameMs?.toString()), "copyMs" to (marker["copyMs"] ?: m.copyMs?.toString()),
                "groupMs" to (marker["groupMs"] ?: m.groupMs?.toString()), "at" to (marker["at"] ?: m.startedAt?.toString()),
            ))
        }
        m.settled = true
    }

    /** T267: where the store is and how it got there, for `GET /debug/cache` and the Storage screen; null without [move]. */
    fun moveJson(): Map<String, Any?>? {
        val m = move ?: return null
        val location = when {
            moveRoot != null && m.state != TileStoreMove.State.PENDING -> "${m.fromName} and ${m.toName}"
            m.state == TileStoreMove.State.DONE -> m.toName
            else -> m.fromName
        }
        return linkedMapOf("location" to location, "moveState" to m.stateName(), "groupFixed" to m.groupFixed, "move" to m.json(dir))
    }

    /** T151: told under the lock of every key added (true) or removed (false); `areas/Areas.kt` keeps the keys as rows. */
    @Volatile var onIndex: ((String, Boolean) -> Unit)? = null

    val hits = AtomicLong()
    val misses = AtomicLong()
    val writes = AtomicLong()
    val evictions = AtomicLong()

    init {
        if (moving && move!!.from.exists()) moveRoot = move.from
        if (!moving) dir.mkdirs() // T267: a pending move renames the old tree into place, so nothing is made there first
        if (!moving && dir.list().isNullOrEmpty()) {
            markerChecked = true
            markReady() // nothing to walk (a new install, or after Android's Clear cache); a snapshot left is written over at the first change
        } else {
            Thread(::index, "tile-index-${dir.name}").apply { isDaemon = true }.start()
        }
    }

    val size: Int get() = entries

    /** T95: entries [keep] holds (as of the last put, trim or [recountPinned]). */
    val pinnedSize: Int get() = pinnedCount

    /** Bytes of the entries [keep] holds (as of the last put, trim or [recountPinned]). */
    val pinnedBytes: Long get() = pinnedTotal

    val bytes: Long get() = total

    // --- the index, under the lock ---

    private fun addEntry(key: String, bytes: Int, minute: Int, pinned: Boolean) {
        index.add(key, bytes, minute, pinned)
        counted(bytes, pinned)
        onIndex?.invoke(key, true)
        changed()
    }

    private fun counted(bytes: Int, pinned: Boolean) {
        total += bytes
        entries++
        if (pinned) {
            pinnedTotal += bytes
            pinnedCount++
        }
    }

    /** Removes [slot] ([key]) from the index and the totals; T151: and tells [onIndex] that [key] went. */
    private fun removeEntry(key: String, slot: Int) {
        val b = index.size(slot)
        total -= b
        entries--
        if (index.isPinned(slot)) {
            pinnedTotal -= b
            pinnedCount--
        }
        index.remove(slot)
        onIndex?.invoke(key, false)
        changed()
    }

    private fun setPinned(slot: Int, pinned: Boolean) {
        if (index.isPinned(slot) == pinned) return
        val b = index.size(slot)
        if (pinned) {
            pinnedTotal += b
            pinnedCount++
        } else {
            pinnedTotal -= b
            pinnedCount--
        }
        index.setPinned(slot, pinned)
        changed()
    }

    // --- the walk (T242) ---

    private class Walked(val key: String, val bytes: Long, val mtime: Long)

    /** T249: the index's thread: the snapshot read when it may be, else the walk (then the snapshot written). */
    private fun index() {
        if (moving) {
            moveFirst()
            if (moveRoot != null) { // T267: a copy: both trees walked (the new first: a key in both is the new one), then copied
                runCatching { snapshotFile?.delete() }
                if (snapshotFile != null) snapshotLoad = mapOf("used" to false, "why" to "the store is being copied")
                synchronized(lock) { markerChecked = true }
                walk(listOf(dir, moveRoot!!))
                copyAfterIndex()
                return
            }
        }
        indexOrRead()
        if (moving) afterMove()
    }

    /** T249: the snapshot read when it may be, else the walk (then the snapshot written). */
    private fun indexOrRead() {
        val reason = if (snapshotFile != null) {
            try {
                loadSnapshot()
            } catch (t: Throwable) {
                runCatching { snapshotFile?.delete() }
                "${t.javaClass.simpleName}: ${t.message}"
            }
        } else "off"
        if (reason == null) {
            markReady(fromSnapshot = true)
            // Navigation's pins do not outlive the process: the kept flags are asked again (and the totals with them).
            val t0 = System.nanoTime()
            runCatching { recountPinned() }
            snapshotLoad = snapshotLoad.orEmpty() + mapOf("pinRecountMs" to (System.nanoTime() - t0) / 1_000_000)
            return
        }
        if (snapshotFile != null) snapshotLoad = snapshotLoad.orEmpty() + mapOf("used" to false, "why" to reason)
        walk()
        if (snapshotFile != null) runCatching { saveSnapshot("walk") }
    }

    private fun walk(roots: List<File> = listOf(dir)) {
        val startedAt = System.currentTimeMillis()
        val batch = ArrayList<Walked>(WALK_BATCH)
        try {
            for (root in roots) walker.walk(root) { key, bytes, mtime, path ->
                if (key.endsWith(".tmp")) {
                    // A write interrupted by a kill; one under way now (a put while indexing) is younger.
                    if (mtime < startedAt - STALE_TMP_MS) runCatching { File(path).delete() }
                } else {
                    batch += Walked(key, bytes, mtime)
                    walked++
                    if (batch.size >= WALK_BATCH) fileWalked(batch)
                    walkHook?.invoke(walked)
                }
            }
        } catch (t: Throwable) {
            indexError = "${t.javaClass.simpleName}: ${t.message}" // what was filed stays; get and contains still find the rest
        } finally {
            runCatching { fileWalked(batch) }
            markReady()
        }
    }

    /** Files [batch] (outside the lock: [keep]; then under it, the keys not held and not removed meanwhile). */
    private fun fileWalked(batch: MutableList<Walked>) {
        if (batch.isEmpty()) return
        val k = keep
        val pins = BooleanArray(batch.size) { runCatching { k(batch[it].key) }.getOrDefault(false) }
        synchronized(lock) {
            val gone = removed
            for (i in batch.indices) {
                val w = batch[i]
                if (gone != null && w.key in gone) continue
                if (index.find(w.key) != TileIndex.NONE) continue // a get or a put while indexing filed it, fresher
                addEntry(w.key, w.bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), minuteOf(w.mtime), pins[i])
            }
        }
        batch.clear()
    }

    private fun markReady(fromSnapshot: Boolean = false) {
        val actions: List<Pair<String, () -> Unit>>
        synchronized(lock) {
            this.fromSnapshot = fromSnapshot
            state = State.READY
            removed = null
            actions = ArrayList(pending)
            pending.clear()
        }
        walkMs = (System.nanoTime() - startedNs) / 1_000_000
        readyLatch.countDown()
        runCatching { trimIfOver() }
        for ((_, action) in actions) runCatching { action() }
    }

    /**
     * T242: runs [action] once the index is whole: on the index's thread after the walk, or on a thread of its own ([name])
     * when it already is. `Areas.attach`'s rows and the cache budget's first run come here.
     */
    fun whenReady(name: String, action: () -> Unit) {
        synchronized(lock) {
            if (!ready) {
                pending += name to action
                return
            }
        }
        Thread(action, name).apply { isDaemon = true }.start()
    }

    /**
     * T242: waits for the index to be whole: off the main thread as long as the walk makes progress (a [TimeoutException]
     * naming the caller after [WAIT_STALL_MS] without a file); on the main thread before then a failed check naming the
     * caller and [op] (T229's rule: never a silent answer, never a wait there).
     */
    fun awaitReady(op: String = "awaitReady") {
        if (ready) return
        check(!onMain()) {
            "TileCache.$op on the main thread while the index is built, from ${callerOf()}: read bytes, size, pinnedBytes and state (partial while indexing), or call it on a thread"
        }
        var seen = walked
        while (!readyLatch.await(WAIT_STALL_MS, TimeUnit.MILLISECONDS)) {
            val now = walked
            if (now == seen) throw TimeoutException("TileCache.$op: the index made no progress in $WAIT_STALL_MS ms (${callerOf()})")
            seen = now
        }
    }

    // --- T249: the snapshot ---

    /** T249: reads the snapshot into the index; null when it was used, else why not (a snapshot not used is deleted). */
    private fun loadSnapshot(): String? {
        val snap = snapshotFile!!
        val t0 = System.nanoTime()
        val interrupted = snapshotTmp!!.exists()
        if (interrupted) snapshotTmp.delete()
        val dirty: Boolean
        synchronized(lock) {
            dirty = dirtyMarker!!.exists()
            markerChecked = true
            markerOn = dirty
            if (changes > 0 && !markerOn) { // a put while this thread started: its marker now
                markerOn = true
                runCatching { dirtyMarker!!.parentFile?.mkdirs(); dirtyMarker!!.createNewFile() }
            }
        }
        if (!snap.isFile) return if (interrupted) "none (a write of it was interrupted)" else "none"
        val fileBytes = snap.length()
        if (interrupted) return "a write of it was interrupted".also { snap.delete() }
        if (dirty) return "the last run changed the index after its last write".also { snap.delete() }
        val read = try {
            TileIndexSnapshot.read(snap.readBytes()) // one read of the whole file
        } catch (c: TileIndexSnapshot.Corrupt) {
            snap.delete()
            return "corrupt: ${c.message}"
        }
        val tRead = System.nanoTime()
        val mismatch = TileIndexSnapshot.validate(dir, read, kotlin.random.Random(System.nanoTime()))
        val tValid = System.nanoTime()
        if (mismatch != null) return "disagrees with the directory: $mismatch".also { snap.delete() }
        fileSnapshot(read.entries)
        val tFiled = System.nanoTime()
        val written = read.header["written"]?.toLongOrNull()
        snapshotLoad = linkedMapOf(
            "used" to true, "entries" to read.entries.total, "fileBytes" to fileBytes,
            "fileBytesPerEntry" to read.entries.total.takeIf { it > 0 }?.let { fileBytes.toDouble() / it },
            "writtenAt" to written, "ageMs" to written?.let { clock() - it },
            "readMs" to (tRead - t0) / 1_000_000, "validateMs" to (tValid - tRead) / 1_000_000, "fileMs" to (tFiled - tValid) / 1_000_000,
        )
        return null
    }

    /**
     * T249: files [e] (read from the snapshot) in chunks under the lock, skipping keys held or removed meanwhile; not a
     * change. The entries go in by minute ([TileIndex.addLoaded]), so each bucket is found once.
     */
    private fun fileSnapshot(e: TileIndexSnapshot.Entries) {
        val groups = IntArray(e.groups.size)
        synchronized(lock) { for ((g, pe) in e.groups.withIndex()) groups[g] = index.internGroup(pe.first, pe.second) }
        val byMinute = LongArray(e.count) { (e.minutes[it].toLong() shl 32) or it.toLong() }
        byMinute.sort()
        val order = IntArray(e.count) { byMinute[it].toInt() }
        val full = groups.any { it < 0 } // more groups than a key packs: those entries go in one by one, as strings
        val totals = LongArray(4)
        var i = 0
        while (i < e.count) {
            val to = minOf(e.count, i + SCAN_CHUNK)
            synchronized(lock) {
                val gone = removed?.takeIf { it.isNotEmpty() }
                val tell = onIndex
                if (full) {
                    for (o in i until to) {
                        val j = order[o]
                        val key = e.keyOf(j)
                        if (gone?.contains(key) == true || index.find(key) != TileIndex.NONE) continue
                        index.add(key, e.sizes[j], e.minutes[j], e.pinned[j])
                        counted(e.sizes[j], e.pinned[j])
                        tell?.invoke(key, true)
                    }
                } else {
                    totals.fill(0)
                    index.addLoaded(
                        e.keys, e.sizes, e.minutes, e.pinned, order, i, to, groups,
                        skip = gone?.let { g -> { k -> index.keyOfPacked(k) in g } },
                        added = tell?.let { t -> { s -> t(index.keyOf(s), true) } },
                        totals = totals,
                    )
                    total += totals[1]
                    entries += totals[0].toInt()
                    pinnedTotal += totals[3]
                    pinnedCount += totals[2].toInt()
                }
            }
            i = to
            walked = i.toLong()
        }
        synchronized(lock) {
            val gone = removed
            for (en in e.strings) {
                if (gone?.contains(en.key) == true || index.find(en.key) != TileIndex.NONE) continue
                index.add(en.key, en.bytes, en.minute, en.pinned)
                counted(en.bytes, en.pinned)
                onIndex?.invoke(en.key, true)
            }
        }
        walked = e.total.toLong()
    }

    /**
     * T249: writes the index to [snapshotFile] when it changed since the last write (or [force]), atomically (a `.tmp`
     * renamed over it); off the main thread, once the index is whole. Returns what happened: `written`, `clean`, `indexing`,
     * `off` or `error`; [lastSave] has the figures.
     */
    fun saveSnapshot(reason: String, force: Boolean = false): String {
        val snap = snapshotFile ?: return "off"
        check(!onMain()) { "TileCache.saveSnapshot on the main thread, from ${callerOf()}" }
        synchronized(saveLock) {
            if (!ready) return "indexing"
            val gen: Long
            synchronized(lock) {
                gen = changes
                if (!force && gen == savedChanges && !touched) return "clean"
                touched = false
            }
            val t0 = System.nanoTime()
            val e = copyEntries()
            val tCopy = System.nanoTime()
            val tmp = snapshotTmp!!
            var written = 0
            try {
                snap.parentFile?.mkdirs()
                FileOutputStream(tmp).use { fos ->
                    val out = BufferedOutputStream(fos, 1 shl 16)
                    written = TileIndexSnapshot.write(out, e, clock(), dir.name, midway = {
                        val ms = writeHoldMs
                        if (ms > 0) {
                            writeHoldMs = 0
                            out.flush()
                            Thread.sleep(ms)
                        }
                    })
                    out.flush()
                    fos.fd.sync()
                }
                Files.move(tmp.toPath(), snap.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (t: Throwable) {
                touched = true
                runCatching { tmp.delete() }
                lastSave = linkedMapOf("reason" to reason, "error" to "${t.javaClass.simpleName}: ${t.message}", "at" to clock())
                return "error"
            }
            synchronized(lock) {
                if (changes == gen) { // nothing changed while it was written: the marker goes
                    savedChanges = gen
                    if (markerOn) {
                        markerOn = false
                        runCatching { dirtyMarker?.delete() }
                    }
                }
            }
            saves.incrementAndGet()
            val bytes = snap.length()
            lastSave = linkedMapOf(
                "reason" to reason, "at" to clock(), "entries" to written, "fileBytes" to bytes,
                "fileBytesPerEntry" to written.takeIf { it > 0 }?.let { bytes.toDouble() / it },
                "copyMs" to (tCopy - t0) / 1_000_000, "writeMs" to (System.nanoTime() - tCopy) / 1_000_000,
            )
            return "written"
        }
    }

    /** T249: the index's entries copied out, in chunks of [SCAN_CHUNK] slots under the lock (a key moved meanwhile may be listed twice). */
    private fun copyEntries(): TileIndexSnapshot.Entries {
        var cap = entries + 1024
        var keys = LongArray(cap)
        var sizes = IntArray(cap)
        var minutes = IntArray(cap)
        var pinned = BooleanArray(cap)
        var n = 0
        val strings = ArrayList<TileIndexSnapshot.StringEntry>()
        var from = 0
        while (true) {
            val more = synchronized(lock) {
                val to = minOf(index.high, from + SCAN_CHUNK)
                for (s in from until to) {
                    if (!index.isUsed(s)) continue
                    if (index.isStringKey(s)) {
                        strings += TileIndexSnapshot.StringEntry(index.keyOf(s), index.size(s), index.minute(s), index.isPinned(s))
                        continue
                    }
                    if (n == cap) {
                        cap += cap / 2 + 1024
                        keys = keys.copyOf(cap); sizes = sizes.copyOf(cap); minutes = minutes.copyOf(cap); pinned = pinned.copyOf(cap)
                    }
                    keys[n] = index.packedKey(s)
                    sizes[n] = index.size(s)
                    minutes[n] = index.minute(s)
                    pinned[n] = index.isPinned(s)
                    n++
                }
                from = to
                to < index.high
            }
            if (!more) break
        }
        val groups = synchronized(lock) { (0 until index.groups).map { index.groupPrefixOf(it) to index.groupExtOf(it) } }
        return TileIndexSnapshot.Entries(groups, keys, sizes, minutes, pinned, n, strings)
    }

    /** T249: the snapshot's file and what this launch and the last write did with it, for `GET /debug/cache`. */
    fun snapshotJson(): Map<String, Any?>? {
        val f = snapshotFile ?: return null
        val written = lastSave?.get("at") as? Long
        return linkedMapOf(
            "file" to "${f.parentFile?.name}/${f.name}", "present" to f.isFile, "fileBytes" to f.length(),
            "entriesInFile" to (lastSave?.get("entries") ?: snapshotLoad?.get("entries")),
            "ageMs" to (written ?: snapshotLoad?.get("writtenAt") as? Long)?.let { clock() - it },
            "usedThisLaunch" to fromSnapshot, "load" to snapshotLoad, "lastSave" to lastSave, "saves" to saves.get(),
            "dirty" to (synchronized(lock) { changes != savedChanges } || touched), "marker" to dirtyMarker?.isFile,
        )
    }

    // --- per key: these work while indexing ---

    private fun checkKey(key: String) =
        require(validKey(key)) { "bad cache key '$key'" }

    private fun validKey(key: String) = key.isNotEmpty() && !key.startsWith("/") && key.split('/').none { it == ".." || it == "." || it.isEmpty() }

    /**
     * The cached bytes for [key], or null. A hit makes the entry the most recently used. T242: while indexing a key the
     * walk has not reached yet is read from its file, and filed. T249: so
     * is one an index read from the snapshot lacks (written after its last write), and an entry whose file is gone is dropped.
     */
    fun get(key: String): ByteArray? {
        checkKey(key)
        val known = synchronized(lock) { index.find(key) != TileIndex.NONE }
        if (!known && ready && !fromSnapshot) { // T249: over a snapshot's index, a file it does not name is still looked for
            misses.incrementAndGet()
            return null
        }
        val (data, file) = readFile(key) ?: run { // T267: from the new tree, else the old one while it is served
            // Deleted behind our back (the system trims app caches under storage pressure).
            if (known) synchronized(lock) {
                val s = index.find(key)
                if (s != TileIndex.NONE && !fileExists(key)) removeEntry(key, s)
            }
            misses.incrementAndGet()
            return null
        }
        hits.incrementAndGet()
        val now = clock()
        val minute = minuteOf(now)
        val pinned = if (known) false else keep(key)
        var touchFile = false
        synchronized(lock) {
            val s = index.find(key)
            if (s != TileIndex.NONE) {
                if (index.minute(s) < minute) {
                    touchFile = true
                    touched = true
                }
                index.touch(s, minute)
            } else if (removed?.contains(key) != true) {
                addEntry(key, data.size, minute, pinned)
                touchFile = true
            }
        }
        if (touchFile) file.setLastModified(now)
        return data
    }

    /**
     * Whether [key] is cached. T242: while indexing, a key the walk has not reached is looked for on disk (a stat). T249: so
     * is one an index read from the snapshot lacks.
     */
    fun contains(key: String): Boolean {
        if (!validKey(key)) return false
        synchronized(lock) {
            if (index.find(key) != TileIndex.NONE) return true
            if ((ready && !fromSnapshot) || removed?.contains(key) == true) return false
        }
        return fileExists(key) // T267: either tree while the old one is served
    }

    /** T242: deletes the entry [key] (one tile, while indexing too); returns whether there was one. */
    fun remove(key: String): Boolean {
        checkKey(key)
        val held = synchronized(lock) {
            removed?.add(key)
            val s = index.find(key)
            if (s != TileIndex.NONE) {
                removeEntry(key, s)
                true
            } else {
                false
            }
        }
        val deleted = deleteFile(key)
        return held || deleted
    }

    /** Stores [bytes] under [key] (replacing an earlier value) and evicts the oldest entries over [maxBytes]. */
    fun put(key: String, bytes: ByteArray) {
        checkKey(key)
        val file = File(dir, key)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.tmp")
        tmp.writeBytes(bytes)
        if (!placeWrite(key, tmp, file)) return
        val now = clock()
        file.setLastModified(now)
        val pinned = keep(key)
        val minute = minuteOf(now)
        synchronized(lock) {
            removed?.remove(key)
            val s = index.find(key)
            if (s != TileIndex.NONE) {
                val old = index.size(s)
                total += bytes.size - old
                if (index.isPinned(s)) pinnedTotal += bytes.size - old
                index.setSize(s, bytes.size)
                if (old != bytes.size) changed()
                setPinned(s, pinned)
                index.touch(s, minute)
                touched = true
            } else {
                addEntry(key, bytes.size, minute, pinned)
            }
        }
        writes.incrementAndGet()
        trimIfOver()
        onWrite?.invoke() // T112: outside the lock; the budget only reads totals here
    }

    /** T112: called after every [put], outside the cache's lock (the cache budget checks the total over all caches). */
    @Volatile var onWrite: (() -> Unit)? = null

    /** T112: entries [evictOldest] removed for the cache budget (also counted in [evictions]). */
    val budgetEvictions = AtomicLong()

    /**
     * T106 (a T88 follow-up): deletes the other variants of tile [key] that sit next to it under a name starting with
     * `<name without extension>.<infix>` (contour tiles made with out-of-view halo tiles missing:
     * `<y>.halo-<z-x-y...>.pbf`), all but [kept] (the variant just written: the plain key or one of them). They stayed
     * until the cache trim reached them. Lists only the tile's own directory (the y files of one x column). Returns how
     * many went. T242: while indexing, also those the walk has not filed yet.
     */
    fun clearVariants(key: String, kept: String, infix: String = HALO_INFIX): Int {
        checkKey(key)
        val name = key.substringAfterLast('/')
        val parent = key.substringBeforeLast('/', "")
        val prefix = name.substringBeforeLast('.') + infix
        val names = (File(dir, parent).list().orEmpty().asList() + moveRoot?.let { File(it, parent).list() }.orEmpty()) // T267: both trees while the old one is served
            .distinct().filter { it.startsWith(prefix) && !it.endsWith(".tmp") }
        val keys = names.map { if (parent.isEmpty()) it else "$parent/$it" }.filter { it != kept }
        if (keys.isEmpty()) return 0
        val gone = synchronized(lock) {
            keys.filter { k ->
                val s = index.find(k)
                when {
                    s != TileIndex.NONE -> { removeEntry(k, s); removed?.add(k); true }
                    removed != null -> { removed!!.add(k); true }
                    else -> false
                }
            }
        }
        gone.forEach { deleteFile(it) }
        return gone.size
    }

    // --- over the whole index: these wait for it (T242) ---

    /** T151: calls [f] with every key, in chunks under the lock (with [onIndex] set first, nothing is missed). */
    fun forEachKey(f: (String) -> Unit) {
        awaitReady("forEachKey")
        var from = 0
        while (true) {
            val more = synchronized(lock) {
                val to = minOf(index.high, from + SCAN_CHUNK)
                for (s in from until to) if (index.isUsed(s)) f(index.keyOf(s))
                from = to
                to < index.high
            }
            if (!more) return
        }
    }

    /** T91: re-asks [keep] for every entry (after the pins changed), then trims the unpinned ones to the cap. */
    fun recountPinned() {
        awaitReady("recountPinned")
        var from = 0
        while (true) {
            val more = synchronized(lock) {
                val to = minOf(index.high, from + SCAN_CHUNK)
                for (s in from until to) if (index.isUsed(s)) setPinned(s, keep(index.keyOf(s)))
                from = to
                to < index.high
            }
            if (!more) break
        }
        trimIfOver()
    }

    /**
     * Entries and bytes per first key segment (`<prefix>/...`; T74: the first [depth] segments), for reports. T95:
     * [pinned] true or false counts only the entries [keep] holds or only the others (as of the last put, trim or
     * [recountPinned], like [pinnedBytes]).
     */
    fun byPrefix(depth: Int = 1, pinned: Boolean? = null): Map<String, Pair<Int, Long>> {
        awaitReady("byPrefix")
        val out = LinkedHashMap<String, Pair<Int, Long>>()
        fun add(p: String, n: Int, b: Long) {
            val (n0, b0) = out[p] ?: (0 to 0L)
            out[p] = (n0 + n) to (b0 + b)
        }
        synchronized(lock) {
            val groups = index.groups
            // A group whose prefix has [depth] segments answers for all its keys; deeper, each key is split.
            val whole = BooleanArray(groups) { g -> index.groupPrefixOf(g).count { it == '/' } + 1 >= depth }
            val n = IntArray(groups)
            val b = LongArray(groups)
            for (s in 0 until index.high) {
                if (!index.isUsed(s) || (pinned != null && index.isPinned(s) != pinned)) continue
                val g = index.groupOf(s)
                if (g >= 0 && whole[g]) {
                    n[g]++
                    b[g] += index.size(s).toLong()
                } else {
                    add(prefixOf(index.keyOf(s), depth), 1, index.size(s).toLong())
                }
            }
            for (g in 0 until groups) if (n[g] > 0) add(prefixOf(index.groupPrefixOf(g), depth), n[g], b[g])
        }
        return out
    }

    /**
     * T112: deletes the least recently used entries until [bytes] are freed, skipping those [keep] holds (pinned areas),
     * those [eligible] rejects (the budget's derived-first pass) and those [spare] keeps (tiles in view or ahead on the
     * route), and stopping at an entry last used after [notAfter] (the budget merges two caches' oldest-first order);
     * returns the bytes freed (less when nothing else may go). The cache budget's eviction; [maxBytes] is untouched.
     */
    fun evictOldest(bytes: Long, eligible: (String) -> Boolean = { true }, spare: (String) -> Boolean = { false }, notAfter: Long = Long.MAX_VALUE): Long {
        if (bytes <= 0) return 0
        awaitReady("evictOldest")
        val victims = ArrayList<String>()
        var freed = 0L
        synchronized(lock) {
            index.forEachOldest { s ->
                if (freed >= bytes) return@forEachOldest false
                val k = index.keyOf(s)
                if (!eligible(k) || spare(k)) return@forEachOldest true
                if (index.minute(s) * MINUTE_MS > notAfter) return@forEachOldest false
                if (keep(k)) { // pinned since the last count
                    setPinned(s, true)
                    return@forEachOldest true
                }
                freed += index.size(s)
                removeEntry(k, s)
                victims += k
                true
            }
        }
        victims.forEach { deleteFile(it) }
        evictions.addAndGet(victims.size.toLong())
        budgetEvictions.addAndGet(victims.size.toLong())
        return freed
    }

    /**
     * T112: when the least recently used entry [evictOldest] would take with the same [eligible] and [spare] was last used
     * (ms, to the minute: a hit touches an entry at most once a [TOUCH_INTERVAL_MS]), or null when none may go. The budget
     * compares two caches with it to evict oldest-used first across both.
     */
    fun oldestTouch(eligible: (String) -> Boolean = { true }, spare: (String) -> Boolean = { false }): Long? {
        awaitReady("oldestTouch")
        var found: Long? = null
        synchronized(lock) {
            index.forEachOldest { s ->
                val k = index.keyOf(s)
                if (eligible(k) && !spare(k) && !keep(k)) {
                    found = index.minute(s) * MINUTE_MS
                    false
                } else {
                    true
                }
            }
        }
        return found
    }

    /**
     * T112: bytes of the entries [matches] accepts, only the unpinned ones by default ([pinned] null: all). T242: in chunks
     * of [SCAN_CHUNK] slots under the lock (each key is made again for [matches]), so the map's lookups get it between them.
     */
    fun bytesWhere(pinned: Boolean? = false, matches: (String) -> Boolean): Long {
        awaitReady("bytesWhere")
        var n = 0L
        var from = 0
        while (true) {
            val more = synchronized(lock) {
                val to = minOf(index.high, from + SCAN_CHUNK)
                for (s in from until to) {
                    if (!index.isUsed(s) || (pinned != null && index.isPinned(s) != pinned)) continue
                    if (matches(index.keyOf(s))) n += index.size(s)
                }
                from = to
                to < index.high
            }
            if (!more) return n
        }
    }

    /** Deletes every entry whose key starts with [prefix] (all with ""); returns how many went. */
    fun clear(prefix: String = ""): Int {
        awaitReady("clear")
        val gone = ArrayList<String>()
        synchronized(lock) {
            // A group's keys all start with "<group prefix>/": decided once per group when [prefix] ends within that.
            val groups = index.groups
            val byGroup = IntArray(groups) { g ->
                val p = index.groupPrefixOf(g) + "/"
                when {
                    prefix.length > p.length -> 0 // decided per key
                    p.startsWith(prefix) -> 1
                    else -> -1
                }
            }
            for (s in 0 until index.high) {
                if (!index.isUsed(s)) continue
                val g = index.groupOf(s)
                val decided = if (g >= 0) byGroup[g] else 0
                if (decided < 0) continue
                val k = index.keyOf(s)
                if (decided == 0 && !k.startsWith(prefix)) continue
                removeEntry(k, s)
                gone += k
            }
        }
        gone.forEach { deleteFile(it) }
        return gone.size
    }

    /**
     * T95: deletes every entry [keep] does not hold (Settings › Storage, the Online tiles row's Clear: the tiles of pinned
     * offline areas are a store of their own and stay); returns how many went.
     */
    fun clearUnkept(): Int = clearWhere { !keep(it) }

    /**
     * T74: deletes every entry whose key [matches] (a source's tiles of an old content version); returns how many went.
     *
     * T221: in chunks of [chunk] entries, so a country's tiles leave as a counter can follow and the map's lookups get the
     * lock between chunks. The keys are listed under the lock (T242: in chunks too) and [matches] walks the list outside it
     * (it went over a whole index under the lock before); then each chunk is asked again under the lock, so a tile an area
     * pinned meanwhile stays, leaves the index, and its files go outside the lock. A key refused the first time is not asked
     * again (a predicate may count what it refuses). [onChunk], outside the lock, hears the entries deleted so far and how
     * many matched at the start: first (0, total), then after every chunk. A chunk not reached yet is untouched: its tiles
     * still read.
     */
    fun clearWhere(chunk: Int = CLEAR_CHUNK, onChunk: ((deleted: Int, total: Int) -> Unit)? = null, matches: (String) -> Boolean): Int {
        require(chunk > 0) { "chunk $chunk" }
        awaitReady("clearWhere")
        val keys = ArrayList<String>()
        var from = 0
        while (true) {
            val listed = ArrayList<String>(SCAN_CHUNK)
            val more = synchronized(lock) {
                val to = minOf(index.high, from + SCAN_CHUNK)
                for (s in from until to) if (index.isUsed(s)) listed += index.keyOf(s)
                from = to
                to < index.high
            }
            listed.filterTo(keys, matches)
            if (!more) break
        }
        onChunk?.invoke(0, keys.size)
        var deleted = 0
        var at = 0
        while (at < keys.size) {
            val part = keys.subList(at, minOf(at + chunk, keys.size))
            val gone = synchronized(lock) {
                part.filter(matches).filter { k ->
                    val s = index.find(k)
                    if (s != TileIndex.NONE) removeEntry(k, s)
                    s != TileIndex.NONE
                }
            }
            gone.forEach { deleteFile(it) }
            deleted += gone.size
            at += part.size
            onChunk?.invoke(deleted, keys.size)
        }
        return deleted
    }

    private fun trimIfOver() {
        if (ready && total - pinnedTotal > maxBytes) trim()
    }

    /**
     * Evicts the least recently used unpinned entries to [maxBytes] less [slackOf] (T242: the hysteresis, so the next puts
     * at the cap do not trim again per tile), [CLEAR_CHUNK] at a time under the lock, the files deleted outside it.
     */
    private fun trim() {
        val target = maxBytes - slackOf(maxBytes)
        while (true) {
            val victims = ArrayList<String>()
            synchronized(lock) {
                index.forEachOldest { s ->
                    // T91: only the unpinned entries count against the cap.
                    if (total - pinnedTotal <= target || victims.size >= CLEAR_CHUNK) return@forEachOldest false
                    val k = index.keyOf(s)
                    if (keep(k)) { // T75: pinned areas stay
                        setPinned(s, true)
                    } else {
                        removeEntry(k, s)
                        victims += k
                    }
                    true
                }
            }
            victims.forEach { deleteFile(it) }
            evictions.addAndGet(victims.size.toLong())
            if (victims.size < CLEAR_CHUNK || total - pinnedTotal <= target) return
        }
    }

    /**
     * T242: the index's own figures: its state, the walk's count and time, the heap it takes (estimated: [TileIndex.heapBytes]),
     * the keys kept as strings, the groups and the touch-minute buckets. Read without the lock (racy counts, an estimate).
     */
    fun indexJson(): Map<String, Any?> = linkedMapOf(
        "state" to state.name.lowercase(), "walked" to walked, "walkMs" to walkMs, "error" to indexError,
        "heapBytes" to index.heapBytes(), "heapBytesPerEntry" to entries.takeIf { it > 0 }?.let { index.heapBytes() / it },
        "stringKeys" to index.stringKeys, "groups" to index.groups, "buckets" to index.bucketCount,
        "walker" to walker.name, "fromSnapshot" to fromSnapshot, // T249
    )

    fun json(): Map<String, Any?> = linkedMapOf(
        "dir" to dir.path, "state" to state.name.lowercase(), "partial" to partial, // T242
        "entries" to size, "bytes" to bytes, "maxBytes" to maxBytes, "pinnedBytes" to pinnedBytes, "pinnedEntries" to pinnedSize,
        "hits" to hits.get(), "misses" to misses.get(), "writes" to writes.get(), "evictions" to evictions.get(),
        "budgetEvictions" to budgetEvictions.get(), // T112
        "index" to indexJson(), // T242
        "fromSnapshot" to fromSnapshot, "snapshot" to snapshotJson(), // T249
    ) + (moveJson() ?: emptyMap()) // T267: location, moveState, groupFixed, move

    /** The first frame outside this class and its index: the caller a failed check names. */
    private fun callerOf(): String = Throwable().stackTrace.firstOrNull { f ->
        listOf(TileCache::class.java.name, TileIndex::class.java.name).none { f.className == it || f.className.startsWith("$it$") }
    }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" } ?: "unknown"

    companion object {
        const val TOUCH_INTERVAL_MS = 60_000L
        private const val MINUTE_MS = 60_000L

        /** T221: the entries [clearWhere] deletes at a time. */
        const val CLEAR_CHUNK = 256

        /** T106: what follows a contour tile's name in the key of a variant made with halo tiles missing ([OnlineTiles.contourKey]). */
        const val HALO_INFIX = ".halo-"

        /** T242: files the walk files at a time under the lock. */
        const val WALK_BATCH = 1024

        /** T242: entries a pass over the whole index visits per hold of the lock. */
        const val SCAN_CHUNK = 4096

        /** T242: how long a whole-index operation waits for a walk that files nothing more before it gives up. */
        const val WAIT_STALL_MS = 60_000L

        /** T242: a `.tmp` file older than this when the walk starts is a write a kill interrupted. */
        const val STALE_TMP_MS = 60_000L

        /**
         * T242: what the one list and the Storage screen say where a figure would be while the index is built (ADR 0031: the
         * owner adjusts the words after the build).
         */
        const val INDEXING_LINE = "Counting the map tiles"

        /** T267: the move's marker states ([TileStoreMove.marker]). */
        const val STATE_COPYING = "copying"
        const val STATE_FAILED = "failed"
        const val STATE_DONE = "done"

        /** T267: the copy writes its marker (the last directory done) after this many directories. */
        const val COPY_MARK_EVERY = 64

        /** T242: the trim's hysteresis: past the cap it trims this much below it (2 %). */
        fun slackOf(maxBytes: Long): Long = maxBytes / 50

        fun minuteOf(ms: Long): Int = (ms / MINUTE_MS).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

        private fun prefixOf(key: String, depth: Int): String = key.split('/').take(depth).joinToString("/")
    }
}

/**
 * T267 (ADR 0060 W2): the online store's move from [from] (`cacheDir/online/tiles`) into a [TileCache]'s directory
 * (`noBackupFilesDir/tiles`), which the cache's index thread runs ([TileCache.move]); this holds its settings, its state
 * and its figures. The marker ([marker], `tiles.move` beside the new tree) keeps the state across launches as `key=value`
 * lines: `state` (`renaming`, `copying`, `failed`, `done`), `method` (`rename`, `copy`, `none`), `group` (`pending`,
 * `done`), the copy's `cursor` (the last directory done), and the figures once done. [rename] is a seam: a test (or the
 * debug build's forced copy) makes it throw as `EXDEV` would.
 */
class TileStoreMove(
    val from: File,
    /** The ownership pass's system calls (null: no pass, as on the JVM). */
    val groups: GroupFixer? = null,
    /** Renames a file or directory, throwing when it cannot (Android: `rename(2)`; `EXDEV` across file systems or project IDs). */
    val rename: (File, File) -> Unit = ::atomicRename,
    /** What `location` calls the old and the new place. */
    val fromName: String = "cache",
    val toName: String = "files",
    /** Called after each file the copy placed, with the count so far (a test kills or holds the copy with it). */
    val copyHook: ((Long) -> Unit)? = null,
) {
    enum class State { PENDING, COPYING, FAILED, DONE }

    @Volatile var state = State.PENDING
        internal set
    @Volatile var method: String? = null
        internal set
    @Volatile var renameError: String? = null
        internal set
    @Volatile var error: String? = null
        internal set
    @Volatile var startedAt: Long? = null
        private set
    @Volatile var renameMs: Long? = null
        internal set
    @Volatile var copyMs: Long? = null
        internal set
    @Volatile var cursor: String? = null
        internal set
    @Volatile var copied = 0L
        internal set
    @Volatile var copiedBytes = 0L
        internal set
    @Volatile var skipped = 0L
        internal set
    @Volatile var movedFiles: Long? = null
        internal set
    @Volatile var movedBytes: Long? = null
        internal set
    /** The ownership pass: null until it ran (or with no [groups]), then whether every entry has the app's group and no directory setgid. */
    @Volatile var groupFixed: Boolean? = null
        private set
    @Volatile var groupMs: Long? = null
        private set
    @Volatile var group: Map<String, Any?>? = null
        private set
    /** This launch's move has come to rest: done with its ownership pass and figures, or failed (tests wait for it). */
    @Volatile var settled = false
        internal set

    /** The marker as the last launch left it (its figures when this one had nothing to do). */
    @Volatile private var last: Map<String, String> = emptyMap()

    fun marker(to: File) = File(to.absoluteFile.parentFile, "${to.name}.move")

    fun readMarker(to: File): Map<String, String> = runCatching {
        marker(to).readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
    }.getOrDefault(emptyMap())

    /** Writes the marker atomically (a `.tmp` renamed over it); null values are left out. */
    fun writeMarker(to: File, values: Map<String, String?>) {
        val f = marker(to)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(values.filterValues { it != null }.entries.joinToString("") { "${it.key}=${it.value!!.replace('\n', ' ')}\n" })
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /**
     * Whether the cache's index thread has anything to do before the index ([TileCache]'s constructor asks; a few stats):
     * the old tree or its snapshot is still there, a copy or a rename was left unfinished, or the ownership pass has not run.
     * When not, the state is [State.DONE] with the marker's figures.
     */
    fun needed(to: File): Boolean {
        val m = readMarker(to)
        val left = from.exists() || SNAPSHOT_SUFFIXES.any { File(from.absoluteFile.parentFile, from.name + it).exists() }
        val n = left || m["state"].let { it != null && it != TileCache.STATE_DONE } || m["group"] == "pending"
        if (!n) {
            last = m
            state = State.DONE
            method = m["method"] ?: "none"
            renameError = m["renameError"]
            groupFromMarker(m)
            settled = true
        }
        return n
    }

    internal fun begin(at: Long, marker: Map<String, String>) {
        startedAt = at
        last = marker
        cursor = marker["cursor"]
        renameError = marker["renameError"]
        method = marker["method"]
    }

    internal fun groupFromMarker(m: Map<String, String>) {
        groupFixed = m["groupFixed"]?.toBooleanStrictOrNull()
        groupMs = m["groupMs"]?.toLongOrNull()
    }

    /** Puts [tmp] at [dst] (in the new tree), not over a file there. */
    internal fun place(tmp: File, dst: File): Boolean = !dst.exists() && tmp.renameTo(dst)

    /** Carries one small file ([old] to [new]): a rename, else a copy; when [new] is there already, [old] is stale and goes. */
    fun carry(old: File, new: File) {
        if (!old.exists()) return
        if (new.exists()) {
            old.delete()
            return
        }
        new.parentFile?.mkdirs()
        try {
            rename(old, new)
        } catch (_: Throwable) {
            old.copyTo(new, overwrite = false)
            new.setLastModified(old.lastModified())
            old.delete()
        }
    }

    /**
     * The ownership pass over [root]: each directory and file lstat'ed; one whose group is not the app's own is given it,
     * and a directory's setgid bit is cleared (Android makes `cache` setgid with the cache GID, so a renamed tree and every
     * file made in it would go on counting as cache). One pass for directories and files: a launch over a snapshot never
     * stats the files, so "lazily by the walk" would leave most of them. Failures are counted, the first one kept.
     */
    fun fixGroups(root: File) {
        val g = groups ?: return
        val t0 = System.nanoTime()
        val own = g.ownGid
        var dirs = 0L; var files = 0L; var dirsFixed = 0L; var filesFixed = 0L; var setgidCleared = 0L; var failed = 0L
        var firstError: String? = null
        var otherGid: Int? = null
        val stack = ArrayList<String>()
        stack += root.path
        while (stack.isNotEmpty()) {
            val p = stack.removeAt(stack.size - 1)
            val st = g.stat(p) ?: continue
            val isDir = st[2] == 1
            if (isDir) dirs++ else files++
            try {
                if (st[0] != own) {
                    if (otherGid == null) otherGid = st[0]
                    g.chown(p, own)
                    if (isDir) dirsFixed++ else filesFixed++
                }
                if (isDir && st[1] and S_ISGID != 0) {
                    g.chmod(p, st[1] and 0xfff and S_ISGID.inv())
                    setgidCleared++
                }
            } catch (t: Throwable) {
                failed++
                if (firstError == null) firstError = "$p: ${t.javaClass.simpleName}: ${t.message}"
            }
            if (isDir) File(p).list()?.forEach { stack += "$p/$it" }
        }
        groupMs = (System.nanoTime() - t0) / 1_000_000
        groupFixed = failed == 0L
        group = linkedMapOf(
            "ownGid" to own, "otherGidSeen" to otherGid, "dirs" to dirs, "files" to files, "dirsFixed" to dirsFixed,
            "filesFixed" to filesFixed, "setgidCleared" to setgidCleared, "failed" to failed, "firstError" to firstError, "ms" to groupMs,
        )
    }

    fun stateName(): String = when (state) {
        State.PENDING -> "pending"
        State.COPYING -> TileCache.STATE_COPYING
        State.FAILED -> TileCache.STATE_FAILED
        State.DONE -> TileCache.STATE_DONE
    }

    /** `GET /debug/cache`'s `online.move`: how the store moved (this launch's figures, else the marker's from the launch that moved it). */
    fun json(to: File): Map<String, Any?> = linkedMapOf(
        "from" to from.path, "to" to to.path, "method" to method, "renameError" to renameError, "error" to error,
        "files" to (movedFiles ?: last["files"]?.toLongOrNull()), "bytes" to (movedBytes ?: last["bytes"]?.toLongOrNull()),
        "renameMs" to (renameMs ?: last["renameMs"]?.toLongOrNull()), "copyMs" to (copyMs ?: last["copyMs"]?.toLongOrNull()),
        "groupMs" to (groupMs ?: last["groupMs"]?.toLongOrNull()), "at" to (startedAt ?: last["at"]?.toLongOrNull()),
        "thisLaunch" to (startedAt != null),
        "copy" to if (method == "copy") linkedMapOf("copied" to copied, "copiedBytes" to copiedBytes, "skippedNewer" to skipped, "cursor" to cursor) else null,
        "group" to group,
    )

    companion object {
        /** The files named after the tree that move with it: T249's snapshot, its marker and an interrupted write of it. */
        val SNAPSHOT_SUFFIXES = listOf(".index", ".index.dirty", ".index.tmp")

        const val S_ISGID = 0x400 // 02000

        /** `rename(2)` through `Files.move` with ATOMIC_MOVE (an `IOException` names the refusal, `EXDEV` among them). */
        fun atomicRename(a: File, b: File) {
            Files.move(a.toPath(), b.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }
}

/** T267: the system calls of the ownership pass ([TileStoreMove.fixGroups]); [OsGroupFixer] on Android, a fake in tests. */
interface GroupFixer {
    val ownGid: Int

    /** [path]'s group, mode and 1 for a directory (lstat), or null when it cannot be read. */
    fun stat(path: String): IntArray?
    fun chown(path: String, gid: Int)
    fun chmod(path: String, mode: Int)
}

/** T267: Android's `Os` (the app's own gid; `lchown` with the owner unchanged; `chmod`). */
object OsGroupFixer : GroupFixer {
    override val ownGid: Int get() = android.system.Os.getgid()

    override fun stat(path: String): IntArray? = try {
        val st = android.system.Os.lstat(path)
        intArrayOf(st.st_gid, st.st_mode, if (android.system.OsConstants.S_ISDIR(st.st_mode)) 1 else 0)
    } catch (_: android.system.ErrnoException) {
        null
    }

    override fun chown(path: String, gid: Int) = android.system.Os.lchown(path, -1, gid)

    override fun chmod(path: String, mode: Int) = android.system.Os.chmod(path, mode)
}

/** T242: whether this is the UI thread (false on the JVM, where `android.os.Looper` is a stub). */
private fun isUiThread(): Boolean = try {
    android.os.Looper.getMainLooper()?.thread === Thread.currentThread()
} catch (_: Throwable) {
    false
}

/**
 * T249: how [TileCache] walks its directory: every regular file below the root as its key (the path relative to the root,
 * `/` between segments), its bytes and its modification time (ms), with its path for the stale `.tmp` files' deletion.
 * [NioWalker] is `Files.walkFileTree` (T242's); [ListWalker] lists each directory with `File.list` and asks a stat of each
 * name ([ListWalker.osLstat] on Android: `Os.lstat`, one system call and no `Path` objects). `POST /debug/cache/walk` times
 * both over the online cache.
 */
abstract class TileWalker(val name: String) {
    fun interface Visitor {
        fun file(key: String, bytes: Long, mtimeMs: Long, path: String)
    }

    abstract fun walk(root: File, visit: Visitor)

    companion object {
        /** On Android the list walker over `Os.lstat` (T249's measurement), elsewhere (the JVM's tests) `Files.walkFileTree`. */
        fun forPlatform(): TileWalker =
            if (System.getProperty("java.vm.name").orEmpty().contains("Dalvik")) ListWalker(ListWalker::osLstat) else NioWalker
    }
}

/** T242's walk: `Files.walkFileTree` with one `BasicFileAttributes` read per file. */
object NioWalker : TileWalker("walkFileTree") {
    override fun walk(root: File, visit: Visitor) {
        val prefixes = ArrayDeque<String>()
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                prefixes.addLast(if (prefixes.isEmpty()) "" else prefixes.last() + d.fileName.toString() + "/")
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                prefixes.removeLast()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                visit.file(prefixes.last() + f.fileName.toString(), attrs.size(), attrs.lastModifiedTime().toMillis(), f.toString())
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(f: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    }
}

/**
 * T249: `File.list` per directory and [stat] per name (depth first, an explicit stack). [stat] fills `out[0]` with the
 * bytes and `out[1]` with the modification time (ms) and answers [FILE], [DIR], [OTHER] or [NONE] (gone, or unreadable).
 */
class ListWalker(private val stat: (String, LongArray) -> Int) : TileWalker("list+lstat") {
    override fun walk(root: File, visit: Visitor) {
        val paths = ArrayList<String>()
        val prefixes = ArrayList<String>()
        paths += root.path
        prefixes += ""
        val out = LongArray(2)
        while (paths.isNotEmpty()) {
            val p = paths.removeAt(paths.size - 1)
            val prefix = prefixes.removeAt(prefixes.size - 1)
            val names = File(p).list() ?: continue
            for (n in names) {
                val full = "$p/$n"
                when (stat(full, out)) {
                    FILE -> visit.file(prefix + n, out[0], out[1], full)
                    DIR -> {
                        paths += full
                        prefixes += "$prefix$n/"
                    }
                }
            }
        }
    }

    companion object {
        const val NONE = 0
        const val FILE = 1
        const val DIR = 2
        const val OTHER = 3

        /** Android's `lstat(2)` ([android.system.Os]); seconds of modification time (API 26 has no `st_mtim`). */
        fun osLstat(path: String, out: LongArray): Int = try {
            val st = android.system.Os.lstat(path)
            out[0] = st.st_size
            out[1] = st.st_mtime * 1000
            when {
                android.system.OsConstants.S_ISREG(st.st_mode) -> FILE
                android.system.OsConstants.S_ISDIR(st.st_mode) -> DIR
                else -> OTHER
            }
        } catch (_: android.system.ErrnoException) {
            NONE
        }

        /** The JVM's stat (the tests'): `BasicFileAttributes` without following links. */
        fun jvmStat(path: String, out: LongArray): Int = try {
            val a = Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            out[0] = a.size()
            out[1] = a.lastModifiedTime().toMillis()
            when {
                a.isRegularFile -> FILE
                a.isDirectory -> DIR
                else -> OTHER
            }
        } catch (_: IOException) {
            NONE
        }
    }
}
