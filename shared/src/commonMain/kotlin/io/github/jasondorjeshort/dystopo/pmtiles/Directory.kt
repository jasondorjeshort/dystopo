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

    fun decode(bytes: ByteArray): List<Entry> {
        val r = ByteReader(bytes)
        val count = r.varint()
        require(count in 0..Int.MAX_VALUE.toLong()) { "bad directory entry count $count" }
        val n = count.toInt()
        val ids = LongArray(n)
        var lastId = 0L
        for (i in 0 until n) {
            lastId += r.varint()
            ids[i] = lastId
        }
        val runs = IntArray(n) { r.varint().toInt() }
        val lengths = IntArray(n) { r.varint().toInt() }
        val out = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val v = r.varint()
            val offset = if (v == 0L) {
                require(i > 0) { "first directory entry cannot be contiguous" }
                out[i - 1].offset + out[i - 1].length
            } else v - 1
            out += Entry(ids[i], offset, lengths[i], runs[i])
        }
        return out
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
