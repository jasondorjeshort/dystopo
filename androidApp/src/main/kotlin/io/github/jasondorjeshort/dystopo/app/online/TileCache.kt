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
 */
class TileCache(val dir: File, maxBytes: Long, private val clock: () -> Long = System::currentTimeMillis) {
    /** The cap; [resize] changes it (a setting, T41). */
    @Volatile var maxBytes: Long = maxBytes
        private set

    /** Sets the cap and evicts the least recently used entries over it at once. */
    fun resize(bytes: Long) {
        maxBytes = bytes
        trim()
    }

    private class Entry(val file: File, val bytes: Long, @Volatile var touchedAt: Long)

    // Access order: iteration starts at the least recently used entry.
    private val index = LinkedHashMap<String, Entry>(256, 0.75f, true)
    private var total = 0L

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
        trim()
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
            synchronized(this) { if (index[key] === e) { index.remove(key); total -= e.bytes } }
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

    /** Entries and bytes per first key segment (`<prefix>/...`), for reports. */
    fun byPrefix(): Map<String, Pair<Int, Long>> = synchronized(this) {
        val out = LinkedHashMap<String, Pair<Int, Long>>()
        for ((k, e) in index) {
            val p = k.substringBefore('/')
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
        synchronized(this) {
            index.remove(key)?.let { total -= it.bytes }
            index[key] = Entry(file, bytes.size.toLong(), now)
            total += bytes.size
        }
        writes.incrementAndGet()
        trim()
    }

    /** Deletes every entry whose key starts with [prefix] (all with ""); returns how many went. */
    fun clear(prefix: String = ""): Int {
        val gone = synchronized(this) {
            val keys = index.keys.filter { it.startsWith(prefix) }
            keys.mapNotNull { k -> index.remove(k)?.also { total -= it.bytes } }
        }
        gone.forEach { it.file.delete() }
        return gone.size
    }

    private fun trim() {
        val victims = ArrayList<File>()
        synchronized(this) {
            val it = index.entries.iterator()
            while (total > maxBytes && it.hasNext()) {
                val e = it.next().value
                it.remove()
                total -= e.bytes
                victims += e.file
            }
        }
        victims.forEach { it.delete() }
        evictions.addAndGet(victims.size.toLong())
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "dir" to dir.path, "entries" to size, "bytes" to bytes, "maxBytes" to maxBytes,
        "hits" to hits.get(), "misses" to misses.get(), "writes" to writes.get(), "evictions" to evictions.get(),
    )

    companion object {
        const val TOUCH_INTERVAL_MS = 60_000L
    }
}
