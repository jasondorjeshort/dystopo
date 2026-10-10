package io.github.jasondorjeshort.dystopo.search

/**
 * Cuts a vector tile down to the layers a search index reads, before the full MVT decoder runs:
 * land cover, water polygons and the rest are most of a tile's bytes and hold none of its names.
 */
internal object LayerFilter {
    /**
     * The tile's bytes with only the layers named in [keep] (itself a valid MVT); [tile] itself when
     * nothing is dropped. Throws IllegalArgumentException on malformed input.
     */
    fun filter(tile: ByteArray, keep: Set<String>): ByteArray {
        val r = Scanner(tile)
        val kept = ArrayList<IntRange>()
        var dropped = false
        while (r.pos < tile.size) {
            val start = r.pos
            val key = r.varint().toInt()
            if (key ushr 3 == 3 && key and 7 == 2) {
                val n = r.length()
                val body = r.pos
                r.pos += n
                val name = layerName(tile, body, body + n)
                if (name != null && name in keep) kept += start until r.pos else dropped = true
            } else {
                r.skip(key and 7)
                kept += start until r.pos
            }
        }
        if (!dropped) return tile
        val out = ByteArray(kept.sumOf { it.last - it.first + 1 })
        var p = 0
        for (k in kept) {
            tile.copyInto(out, p, k.first, k.last + 1)
            p += k.last - k.first + 1
        }
        return out
    }

    /** Field 1 (name) of the layer message in tile[from, to). */
    private fun layerName(tile: ByteArray, from: Int, to: Int): String? {
        val r = Scanner(tile, from, to)
        while (r.pos < to) {
            val key = r.varint().toInt()
            if (key ushr 3 == 1 && key and 7 == 2) {
                val n = r.length()
                return tile.decodeToString(r.pos, r.pos + n)
            }
            r.skip(key and 7)
        }
        return null
    }

    private class Scanner(val buf: ByteArray, var pos: Int = 0, val end: Int = buf.size) {
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end) { "truncated tile" }
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }

        fun length(): Int {
            val n = varint()
            require(n >= 0 && n <= end - pos) { "bad length $n" }
            return n.toInt()
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> advance(8)
                2 -> advance(length())
                5 -> advance(4)
                else -> throw IllegalArgumentException("bad wire type $wireType")
            }
        }

        private fun advance(n: Int) {
            require(n <= end - pos) { "truncated tile" }
            pos += n
        }
    }
}
