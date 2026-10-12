package io.github.jasondorjeshort.dystopo.server

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * T74: a byte cap with least-recently-used eviction over one server cache directory (`live/`, `openfreemap/`,
 * `derived/` under the tile cache root, see [LiveTiles]). The files stay where their owner writes them; this
 * only keeps the books: [added] after a file is published, [touch] on a hit, and eviction deletes the least
 * recently used entries once the total passes [maxBytes].
 *
 * An entry is a file together with its sidecars: files whose paths differ only in the last extension
 * (`12/655/1431.png` and `12/655/1431.src`) count as one entry and go together. Files for which [exclude]
 * is true (an in-progress `.part`, the OpenFreeMap TileJSON) are never counted or deleted.
 *
 * The index of what is already on disk is built on a background thread at start (a cache of a few GB holds
 * tens of thousands of files), oldest modification time first; until it is done nothing is evicted, and
 * entries added meanwhile stay the most recently used. A hit sets the file's mtime (at most once a minute per
 * entry), so the order survives a restart. Readers must expect a file to vanish between a check and a read
 * (eviction runs concurrently): they treat that as a miss. Thread-safe.
 */
class BoundedCache(
    val name: String,
    val dir: File,
    maxBytes: Long,
    private val exclude: (String) -> Boolean = { it.endsWith(".part") || it.endsWith(".tmp") },
    private val clock: () -> Long = System::currentTimeMillis,
    indexInBackground: Boolean = true,
) {
    @Volatile var maxBytes: Long = maxBytes
        private set

    private class Entry(val files: MutableSet<String>, var bytes: Long, @Volatile var touchedAt: Long)

    // Access order: iteration starts at the least recently used entry.
    private val index = LinkedHashMap<String, Entry>(1024, 0.75f, true)
    private var total = 0L

    @Volatile var indexed = false
        private set
    @Volatile var indexMs = 0L
        private set

    val hits = AtomicLong()
    val adds = AtomicLong()
    val evictions = AtomicLong()
    val evictedBytes = AtomicLong()
    /** Highest [bytes] seen after a trim (what a soak checks against [maxBytes]). */
    val peakBytes = AtomicLong()

    init {
        if (indexInBackground) Thread(::buildIndex, "cache-index-$name").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        else buildIndex()
    }

    val bytes: Long @Synchronized get() = total
    val entries: Int @Synchronized get() = index.size

    /** Sets the cap and evicts at once (when the index is built). */
    fun resize(bytes: Long) {
        maxBytes = bytes
        trim()
    }

    private fun stem(rel: String): String {
        val slash = rel.lastIndexOf('/')
        val dot = rel.lastIndexOf('.')
        return if (dot > slash) rel.substring(0, dot) else rel
    }

    private fun rel(file: File): String = file.relativeTo(dir).invariantSeparatorsPath

    /** The file at [rel] (a path relative to [dir], `/`-separated). */
    fun file(rel: String): File = File(dir, rel)

    /** [file] (under [dir]) was read: it becomes the most recently used. */
    fun touch(file: File) = touch(rel(file))

    fun touch(rel: String) {
        val e = synchronized(this) { index[stem(rel)] } ?: return
        hits.incrementAndGet()
        val now = clock()
        if (now - e.touchedAt > TOUCH_INTERVAL_MS) {
            e.touchedAt = now
            file(rel).setLastModified(now)
        }
    }

    /** [file] (under [dir]) was written: its size counts from now, as the most recently used entry. */
    fun added(file: File) = added(rel(file), file.length())

    fun added(rel: String, length: Long) {
        if (exclude(rel)) return
        val key = stem(rel)
        val victims = synchronized(this) {
            val e = index[key]
            when {
                e == null -> {
                    index[key] = Entry(mutableSetOf(rel), length, clock())
                    total += length
                }
                rel in e.files -> {
                    // Rewritten in place: re-measure the entry (the old size of this file is not known here).
                    total -= e.bytes
                    e.bytes = e.files.sumOf { file(it).length() }
                    total += e.bytes
                }
                else -> {
                    e.files += rel
                    e.bytes += length
                    total += length
                }
            }
            victimsLocked() // in the same lock, so nobody sees the total over the cap
        }
        adds.incrementAndGet()
        delete(victims)
    }

    /** Deletes every entry (and the files the index has not seen yet); returns the bytes freed. */
    fun clear(): Long {
        val victims = synchronized(this) {
            val all = index.values.toList()
            index.clear()
            total = 0
            all
        }
        var freed = 0L
        for (e in victims) for (f in e.files) { val file = file(f); val n = file.length(); if (file.delete()) freed += n }
        dir.walkBottomUp().forEach { f ->
            val r = if (f == dir) "" else rel(f)
            if (f.isFile && !exclude(r)) { val n = f.length(); if (f.delete()) freed += n } else if (f.isDirectory && f != dir) f.delete()
        }
        return freed
    }

    private fun buildIndex() {
        val started = System.nanoTime()
        try {
            if (!dir.isDirectory) return
            val found = HashMap<String, Entry>()
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val r = rel(f)
                if (exclude(r)) return@forEach
                val e = found.getOrPut(stem(r)) { Entry(mutableSetOf(), 0, 0) }
                e.files += r
                e.bytes += f.length()
                e.touchedAt = maxOf(e.touchedAt, f.lastModified())
            }
            synchronized(this) {
                // Entries added while walking stay the most recently used: rebuild with the walked ones first.
                val live = LinkedHashMap(index)
                index.clear()
                total = 0
                for ((k, e) in found.entries.sortedBy { it.value.touchedAt }) {
                    if (k in live) continue
                    index[k] = e
                    total += e.bytes
                }
                for ((k, e) in live) {
                    index[k] = e
                    total += e.bytes
                }
            }
        } finally {
            indexMs = (System.nanoTime() - started) / 1_000_000
            indexed = true
            trim()
        }
    }

    private fun trim() = delete(synchronized(this) { victimsLocked() })

    /** Takes the least recently used entries out of the index until the total fits (nothing before the index is built). */
    private fun victimsLocked(): List<Entry> {
        if (!indexed) return emptyList()
        val victims = ArrayList<Entry>()
        val it = index.entries.iterator()
        while (total > maxBytes && it.hasNext()) {
            val e = it.next().value
            it.remove()
            total -= e.bytes
            victims += e
        }
        peakBytes.accumulateAndGet(total, ::maxOf)
        return victims
    }

    private fun delete(victims: List<Entry>) {
        for (e in victims) {
            for (f in e.files) file(f).delete()
            evictedBytes.addAndGet(e.bytes)
        }
        evictions.addAndGet(victims.size.toLong())
    }

    /** The `/stats` line for this store. */
    fun json(): Map<String, Any?> = synchronized(this) {
        linkedMapOf(
            "dir" to dir.path, "bytes" to total, "maxBytes" to maxBytes, "entries" to index.size,
            "indexed" to indexed, "indexMs" to indexMs, "hits" to hits.get(), "adds" to adds.get(),
            "evictions" to evictions.get(), "evictedBytes" to evictedBytes.get(), "peakBytes" to peakBytes.get(),
        )
    }

    companion object {
        const val TOUCH_INTERVAL_MS = 60_000L
    }
}
