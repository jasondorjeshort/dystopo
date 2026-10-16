package io.github.jasondorjeshort.dystopo.app.online

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * A size-capped disk cache of fetched tiles, evicting the least recently used first.
 *
 * Entries are plain files under [dir] at their key's path (`<server>/<route>/<z>/<x>/<y>`), so a
 * restart finds them again: the index is rebuilt from the directory, oldest modification time first,
 * and a hit touches the file's mtime (at most once a minute per entry) so the order survives a restart.
 * Writes go to a temporary file renamed into place, so a reader never sees half a tile. Thread-safe.
 *
 * T91: [maxBytes] caps the entries [keep] does not hold (the pinned tiles of offline areas have their own budget,
 * `pinnedCacheMb`, checked when an area downloads); [pinnedBytes] is what the kept entries take. Each entry
 * remembers whether it was kept when last seen; [recountPinned] refreshes that after the pins change.
 *
 * T112: a cache also reports to the one cache budget over the phone's caches ([onWrite],
 * `io.github.jasondorjeshort.dystopo.app.cachebudget.CacheBudget`), which evicts across caches with [evictOldest]; [maxBytes]
 * stays this cache's own ceiling.
 */
class TileCache(val dir: File, maxBytes: Long, private val clock: () -> Long = System::currentTimeMillis) {
    /** The cap; [resize] changes it (a setting, T41). */
    @Volatile var maxBytes: Long = maxBytes
        private set

    /** T75 hook: entries it says to keep (tiles of pinned areas of interest, `areas/Areas.kt`) are skipped by eviction. */
    @Volatile var keep: (String) -> Boolean = { io.github.jasondorjeshort.dystopo.app.areas.Areas.isPinned(it) }

    /** Sets the cap and evicts the least recently used entries over it at once. */
    fun resize(bytes: Long) {
        maxBytes = bytes
        recountPinned()
    }

    private class Entry(val file: File, val bytes: Long, @Volatile var touchedAt: Long, var pinned: Boolean = false)

    // Access order: iteration starts at the least recently used entry.
    private val index = LinkedHashMap<String, Entry>(256, 0.75f, true)
    private var total = 0L
    private var pinnedTotal = 0L

    /** Removes [e]'s bytes from the totals (under the lock). */
    private fun forget(e: Entry) {
        total -= e.bytes
        if (e.pinned) pinnedTotal -= e.bytes
    }

    /** T91: re-asks [keep] for every entry (after the pins changed), then trims the unpinned ones to the cap. */
    fun recountPinned() {
        synchronized(this) {
            var p = 0L
            for ((k, e) in index) {
                e.pinned = keep(k)
                if (e.pinned) p += e.bytes
            }
            pinnedTotal = p
        }
        trim()
    }

    /** Bytes of the entries [keep] holds (as of the last put, trim or [recountPinned]). */
    val pinnedBytes: Long @Synchronized get() = pinnedTotal

    val hits = AtomicLong()
    val misses = AtomicLong()
    val writes = AtomicLong()
    val evictions = AtomicLong()

    init {
        dir.mkdirs()
        // Stale temporary files of a write interrupted by a kill.
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach { it.delete() }
        val found = dir.walkTopDown().filter { it.isFile }.map { it to it.lastModified() }.sortedBy { it.second }.toList()
        synchronized(this) {
            for ((f, mtime) in found) {
                val len = f.length()
                index[f.relativeTo(dir).invariantSeparatorsPath] = Entry(f, len, mtime)
                total += len
            }
        }
        recountPinned()
    }

    val size: Int @Synchronized get() = index.size

    /** T95: entries [keep] holds (as of the last put, trim or [recountPinned]). */
    val pinnedSize: Int @Synchronized get() = index.values.count { it.pinned }
    val bytes: Long @Synchronized get() = total

    private fun checkKey(key: String) =
        require(key.isNotEmpty() && !key.startsWith("/") && key.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "bad cache key '$key'" }

    /** The cached bytes for [key], or null. A hit makes the entry the most recently used. */
    fun get(key: String): ByteArray? {
        checkKey(key)
        val e = synchronized(this) { index[key] }
        if (e == null) {
            misses.incrementAndGet()
            return null
        }
        val bytes = try {
            e.file.readBytes()
        } catch (_: Exception) {
            // Deleted behind our back (the system trims app caches under storage pressure).
            synchronized(this) { if (index[key] === e) { index.remove(key); forget(e) } }
            misses.incrementAndGet()
            return null
        }
        hits.incrementAndGet()
        val now = clock()
        if (now - e.touchedAt > TOUCH_INTERVAL_MS) {
            e.touchedAt = now
            e.file.setLastModified(now)
        }
        return bytes
    }

    fun contains(key: String): Boolean = synchronized(this) { index.containsKey(key) }

    /**
     * Entries and bytes per first key segment (`<prefix>/...`; T74: the first [depth] segments), for reports. T95:
     * [pinned] true or false counts only the entries [keep] holds or only the others (as of the last put, trim or
     * [recountPinned], like [pinnedBytes]).
     */
    fun byPrefix(depth: Int = 1, pinned: Boolean? = null): Map<String, Pair<Int, Long>> = synchronized(this) {
        val out = LinkedHashMap<String, Pair<Int, Long>>()
        for ((k, e) in index) {
            if (pinned != null && e.pinned != pinned) continue
            val p = k.split('/').take(depth).joinToString("/")
            val (n, b) = out[p] ?: (0 to 0L)
            out[p] = (n + 1) to (b + e.bytes)
        }
        out
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
        synchronized(this) {
            index.remove(key)?.let { forget(it) }
            index[key] = Entry(file, bytes.size.toLong(), now, pinned)
            total += bytes.size
            if (pinned) pinnedTotal += bytes.size
        }
        writes.incrementAndGet()
        trim()
        onWrite?.invoke() // T112: outside the lock; the budget only reads totals here
    }

    /** T112: called after every [put], outside the cache's lock (the cache budget checks the total over all caches). */
    @Volatile var onWrite: (() -> Unit)? = null

    /** T112: entries [evictOldest] removed for the cache budget (also counted in [evictions]). */
    val budgetEvictions = AtomicLong()

    /**
     * T112: deletes the least recently used entries until [bytes] are freed, skipping those [keep] holds (pinned areas),
     * those [eligible] rejects (the budget's derived-first pass) and those [spare] keeps (tiles in view or ahead on the
     * route), and stopping at an entry last used after [notAfter] (the budget merges two caches' oldest-first order);
     * returns the bytes freed (less when nothing else may go). The cache budget's eviction; [maxBytes] is untouched.
     */
    fun evictOldest(bytes: Long, eligible: (String) -> Boolean = { true }, spare: (String) -> Boolean = { false }, notAfter: Long = Long.MAX_VALUE): Long {
        if (bytes <= 0) return 0
        val victims = ArrayList<File>()
        var freed = 0L
        synchronized(this) {
            val it = index.entries.iterator()
            while (freed < bytes && it.hasNext()) {
                val n = it.next()
                val e = n.value
                if (e.pinned || !eligible(n.key) || spare(n.key)) continue
                if (e.touchedAt > notAfter) break
                if (keep(n.key)) { // pinned since the last count
                    e.pinned = true
                    pinnedTotal += e.bytes
                    continue
                }
                it.remove()
                forget(e)
                freed += e.bytes
                victims += e.file
            }
        }
        victims.forEach { it.delete() }
        evictions.addAndGet(victims.size.toLong())
        budgetEvictions.addAndGet(victims.size.toLong())
        return freed
    }

    /**
     * T112: when the least recently used entry [evictOldest] would take with the same [eligible] and [spare] was last used
     * (ms, to the minute: a hit touches an entry at most once a [TOUCH_INTERVAL_MS]), or null when none may go. The budget
     * compares two caches with it to evict oldest-used first across both.
     */
    fun oldestTouch(eligible: (String) -> Boolean = { true }, spare: (String) -> Boolean = { false }): Long? = synchronized(this) {
        for ((k, e) in index) if (!e.pinned && eligible(k) && !spare(k) && !keep(k)) return e.touchedAt
        null
    }

    /** T112: bytes of the entries [matches] accepts, only the unpinned ones by default ([pinned] null: all). */
    fun bytesWhere(pinned: Boolean? = false, matches: (String) -> Boolean): Long = synchronized(this) {
        var n = 0L
        for ((k, e) in index) if ((pinned == null || e.pinned == pinned) && matches(k)) n += e.bytes
        n
    }

    /** Deletes every entry whose key starts with [prefix] (all with ""); returns how many went. */
    fun clear(prefix: String = ""): Int {
        val gone = synchronized(this) {
            val keys = index.keys.filter { it.startsWith(prefix) }
            keys.mapNotNull { k -> index.remove(k)?.also { forget(it) } }
        }
        gone.forEach { it.file.delete() }
        return gone.size
    }

    /**
     * T106 (a T88 follow-up): deletes the other variants of tile [key] that sit next to it under a name starting with
     * `<name without extension>.<infix>` (contour tiles made with out-of-view halo tiles missing:
     * `<y>.halo-<z-x-y...>.pbf`), all but [kept] (the variant just written: the plain key or one of them). They stayed
     * until the cache trim reached them. Lists only the tile's own directory (the y files of one x column). Returns how
     * many went.
     */
    fun clearVariants(key: String, kept: String, infix: String = HALO_INFIX): Int {
        checkKey(key)
        val name = key.substringAfterLast('/')
        val parent = key.substringBeforeLast('/', "")
        val prefix = name.substringBeforeLast('.') + infix
        val names = File(dir, parent).list()?.filter { it.startsWith(prefix) && !it.endsWith(".tmp") }.orEmpty()
        val keys = names.map { if (parent.isEmpty()) it else "$parent/$it" }.filter { it != kept }
        if (keys.isEmpty()) return 0
        val gone = synchronized(this) { keys.mapNotNull { k -> index.remove(k)?.also { forget(it) } } }
        gone.forEach { it.file.delete() }
        return gone.size
    }

    /**
     * T95: deletes every entry [keep] does not hold (Settings › Storage, the Online tiles row's Clear: the tiles of pinned
     * offline areas are a store of their own and stay); returns how many went.
     */
    fun clearUnkept(): Int = clearWhere { !keep(it) }

    /** T74: deletes every entry whose key [matches] (a source's tiles of an old content version); returns how many went. */
    fun clearWhere(matches: (String) -> Boolean): Int {
        val gone = synchronized(this) {
            val keys = index.keys.filter(matches)
            keys.mapNotNull { k -> index.remove(k)?.also { forget(it) } }
        }
        gone.forEach { it.file.delete() }
        return gone.size
    }

    private fun trim() {
        val victims = ArrayList<File>()
        synchronized(this) {
            val it = index.entries.iterator()
            // T91: only the unpinned entries count against the cap.
            while (total - pinnedTotal > maxBytes && it.hasNext()) {
                val n = it.next()
                val e = n.value
                val kept = keep(n.key) // T75: pinned areas stay
                if (kept != e.pinned) {
                    e.pinned = kept
                    pinnedTotal += if (kept) e.bytes else -e.bytes
                }
                if (kept) continue
                it.remove()
                forget(e)
                victims += e.file
            }
        }
        victims.forEach { it.delete() }
        evictions.addAndGet(victims.size.toLong())
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "dir" to dir.path, "entries" to size, "bytes" to bytes, "maxBytes" to maxBytes, "pinnedBytes" to pinnedBytes,
        "hits" to hits.get(), "misses" to misses.get(), "writes" to writes.get(), "evictions" to evictions.get(),
        "budgetEvictions" to budgetEvictions.get(), // T112
    )

    companion object {
        const val TOUCH_INTERVAL_MS = 60_000L

        /** T106: what follows a contour tile's name in the key of a variant made with halo tiles missing ([OnlineTiles.contourKey]). */
        const val HALO_INFIX = ".halo-"
    }
}
