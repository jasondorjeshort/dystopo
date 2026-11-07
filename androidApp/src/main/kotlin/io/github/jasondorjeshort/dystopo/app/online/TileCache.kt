package io.github.jasondorjeshort.dystopo.app.online

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
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
 */
class TileCache(
    val dir: File,
    maxBytes: Long,
    /** T242: whether the calling thread is the UI thread (the JVM's tests pass their own; there is no main looper there). */
    private val onMain: () -> Boolean = ::isUiThread,
    /** T242: called by the walk after each file it indexed, with the count so far (a test holds the walk with it). */
    private val walkHook: ((Long) -> Unit)? = null,
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

    private fun fileOf(key: String) = File(dir, key)

    /** T151: told under the lock of every key added (true) or removed (false); `areas/Areas.kt` keeps the keys as rows. */
    @Volatile var onIndex: ((String, Boolean) -> Unit)? = null

    val hits = AtomicLong()
    val misses = AtomicLong()
    val writes = AtomicLong()
    val evictions = AtomicLong()

    init {
        dir.mkdirs()
        if (dir.list().isNullOrEmpty()) {
            markReady() // nothing to walk (a new install, or after Android's Clear cache)
        } else {
            Thread(::walk, "tile-index-${dir.name}").apply { isDaemon = true }.start()
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
        total += bytes
        entries++
        if (pinned) {
            pinnedTotal += bytes
            pinnedCount++
        }
        onIndex?.invoke(key, true)
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
    }

    // --- the walk (T242) ---

    private class Walked(val key: String, val bytes: Long, val mtime: Long)

    private fun walk() {
        val startedAt = System.currentTimeMillis()
        val batch = ArrayList<Walked>(WALK_BATCH)
        try {
            val prefixes = ArrayDeque<String>()
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
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
                    val name = f.fileName.toString()
                    val mtime = attrs.lastModifiedTime().toMillis()
                    if (name.endsWith(".tmp")) {
                        // A write interrupted by a kill; one under way now (a put while indexing) is younger.
                        if (mtime < startedAt - STALE_TMP_MS) runCatching { Files.deleteIfExists(f) }
                        return FileVisitResult.CONTINUE
                    }
                    batch += Walked(prefixes.last() + name, attrs.size(), mtime)
                    walked++
                    if (batch.size >= WALK_BATCH) fileWalked(batch)
                    walkHook?.invoke(walked)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(f: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
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

    private fun markReady() {
        val actions: List<Pair<String, () -> Unit>>
        synchronized(lock) {
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

    // --- per key: these work while indexing ---

    private fun checkKey(key: String) =
        require(validKey(key)) { "bad cache key '$key'" }

    private fun validKey(key: String) = key.isNotEmpty() && !key.startsWith("/") && key.split('/').none { it == ".." || it == "." || it.isEmpty() }

    /**
     * The cached bytes for [key], or null. A hit makes the entry the most recently used. T242: while indexing a key the
     * walk has not reached yet is read from its file, and filed.
     */
    fun get(key: String): ByteArray? {
        checkKey(key)
        val known = synchronized(lock) { index.find(key) != TileIndex.NONE }
        if (!known && ready) {
            misses.incrementAndGet()
            return null
        }
        val file = fileOf(key)
        val data = try {
            file.readBytes()
        } catch (_: Exception) {
            // Deleted behind our back (the system trims app caches under storage pressure).
            if (known) synchronized(lock) {
                val s = index.find(key)
                if (s != TileIndex.NONE && !file.exists()) removeEntry(key, s)
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
                if (index.minute(s) < minute) touchFile = true
                index.touch(s, minute)
            } else if (removed?.contains(key) != true) {
                addEntry(key, data.size, minute, pinned)
                touchFile = true
            }
        }
        if (touchFile) file.setLastModified(now)
        return data
    }

    /** Whether [key] is cached. T242: while indexing, a key the walk has not reached is looked for on disk (a stat). */
    fun contains(key: String): Boolean {
        if (!validKey(key)) return false
        synchronized(lock) {
            if (index.find(key) != TileIndex.NONE) return true
            if (ready || removed?.contains(key) == true) return false
        }
        return fileOf(key).isFile
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
        val deleted = fileOf(key).delete()
        return held || deleted
    }

    /** Stores [bytes] under [key] (replacing an earlier value) and evicts the oldest entries over [maxBytes]. */
    fun put(key: String, bytes: ByteArray) {
        checkKey(key)
        val file = File(dir, key)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return
            }
        }
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
                setPinned(s, pinned)
                index.touch(s, minute)
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
        val names = File(dir, parent).list()?.filter { it.startsWith(prefix) && !it.endsWith(".tmp") }.orEmpty()
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
        gone.forEach { fileOf(it).delete() }
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
        victims.forEach { fileOf(it).delete() }
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
        gone.forEach { fileOf(it).delete() }
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
            gone.forEach { fileOf(it).delete() }
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
            victims.forEach { fileOf(it).delete() }
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
    )

    fun json(): Map<String, Any?> = linkedMapOf(
        "dir" to dir.path, "state" to state.name.lowercase(), "partial" to partial, // T242
        "entries" to size, "bytes" to bytes, "maxBytes" to maxBytes, "pinnedBytes" to pinnedBytes, "pinnedEntries" to pinnedSize,
        "hits" to hits.get(), "misses" to misses.get(), "writes" to writes.get(), "evictions" to evictions.get(),
        "budgetEvictions" to budgetEvictions.get(), // T112
        "index" to indexJson(), // T242
    )

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

        /** T242: the trim's hysteresis: past the cap it trims this much below it (2 %). */
        fun slackOf(maxBytes: Long): Long = maxBytes / 50

        fun minuteOf(ms: Long): Int = (ms / MINUTE_MS).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

        private fun prefixOf(key: String, depth: Int): String = key.split('/').take(depth).joinToString("/")
    }
}

/** T242: whether this is the UI thread (false on the JVM, where `android.os.Looper` is a stub). */
private fun isUiThread(): Boolean = try {
    android.os.Looper.getMainLooper()?.thread === Thread.currentThread()
} catch (_: Throwable) {
    false
}
