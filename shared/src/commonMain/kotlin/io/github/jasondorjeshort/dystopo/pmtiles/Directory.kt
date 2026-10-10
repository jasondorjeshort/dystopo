package io.github.jasondorjeshort.dystopo.pmtiles

/**
 * One directory entry. With [runLength] >= 1 it addresses tiles [tileId] .. [tileId] + runLength - 1,
 * all sharing the same contents at [offset] (relative to the tile data section). With runLength 0
 * it points at a leaf directory at [offset] (relative to the leaf directories section) that holds
 * the entries for tile IDs from [tileId] up to the next entry's tile ID.
 */
data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int) {
    val isLeaf: Boolean get() = runLength == 0
}

/**
 * Directory serialization. Entries are sorted by tile ID and written column by column as varints:
 * count, tile-ID deltas, run lengths, lengths, then offsets (0 when an entry follows the previous
 * one contiguously, otherwise offset + 1).
 */
object Directory {
    fun encode(entries: List<Entry>): ByteArray {
        val w = ByteWriter(entries.size * 6 + 8)
        w.varint(entries.size.toLong())
        var lastId = 0L
        for (e in entries) {
            require(e.tileId >= lastId) { "directory entries must be sorted by tile id" }
            w.varint(e.tileId - lastId)
            lastId = e.tileId
        }
        for (e in entries) w.varint(e.runLength.toLong())
        for (e in entries) w.varint(e.length.toLong())
        for (i in entries.indices) {
            val e = entries[i]
            val contiguous = i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length
            w.varint(if (contiguous) 0L else e.offset + 1)
        }
        return w.toByteArray()
    }

    /**
     * Decodes a serialized directory. Corrupt input fails with [IllegalArgumentException] (or
     * [IllegalStateException] for truncated varints) before anything large is allocated: the entry
     * count is checked against the bytes present, and lengths, run lengths, tile IDs and offsets
     * must fit their types.
     */
    fun decode(bytes: ByteArray): List<Entry> {
        val r = ByteReader(bytes)
        val count = r.varint()
        // Each entry takes at least one byte in each of the four columns.
        require(count >= 0 && count <= r.remaining / 4) {
            "bad directory entry count $count for ${r.remaining} remaining bytes"
        }
        val n = count.toInt()
        val ids = LongArray(n)
        var lastId = 0L
        for (i in 0 until n) {
            val delta = r.varint()
            require(delta >= 0 && lastId <= Long.MAX_VALUE - delta) { "tile id overflow at directory entry $i" }
            lastId += delta
            ids[i] = lastId
        }
        val runs = IntArray(n) { intField(r.varint(), "run length", it) }
        val lengths = IntArray(n) { intField(r.varint(), "length", it) }
        val out = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val v = r.varint()
            val offset = if (v == 0L) {
                require(i > 0) { "first directory entry cannot be contiguous" }
                val prev = out[i - 1]
                require(prev.offset <= Long.MAX_VALUE - prev.length) { "offset overflow at directory entry $i" }
                prev.offset + prev.length
            } else {
                require(v > 0) { "offset overflow at directory entry $i" }
                v - 1
            }
            out += Entry(ids[i], offset, lengths[i], runs[i])
        }
        return out
    }

    private fun intField(v: Long, what: String, i: Int): Int {
        require(v in 0..Int.MAX_VALUE.toLong()) { "directory entry $i: $what $v exceeds Int.MAX_VALUE" }
        return v.toInt()
    }

    /**
     * The entry addressing [tileId] in a sorted directory: a tile entry whose run covers it, or the
     * leaf entry whose range contains it. Null if no entry can hold it.
     */
    fun find(entries: List<Entry>, tileId: Long): Entry? {
        var lo = 0
        var hi = entries.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].tileId <= tileId) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (found < 0) return null
        val e = entries[found]
        return if (e.isLeaf || tileId < e.tileId + e.runLength) e else null
    }
}
