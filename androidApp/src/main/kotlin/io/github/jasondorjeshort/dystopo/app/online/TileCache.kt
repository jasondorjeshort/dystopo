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

    /** Entries and bytes per first key segment (`<prefix>/...`; T74: the first [depth] segments), for reports. */
    fun byPrefix(depth: Int = 1): Map<String, Pair<Int, Long>> = synchronized(this) {
        val out = LinkedHashMap<String, Pair<Int, Long>>()
        for ((k, e) in index) {
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
    )

    companion object {
        const val TOUCH_INTERVAL_MS = 60_000L
    }
}
