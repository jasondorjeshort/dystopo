package io.github.jasondorjeshort.dystopo.app.firstrun

/**
 * T80: a glyph range with no glyphs, as a valid PBF (`glyphs { stacks { name, range } }`). MapLibre treats an
 * empty 200 as a failed glyph range ("Failed to load glyph range") and the map then never reports fully loaded;
 * this one parses and simply has no characters. The loopback server answers it for a range neither the device
 * nor the server has (TileService.glyph), and the APK bundles it for every range the fonts lack (bundleMapAssets
 * in androidApp/build.gradle.kts writes the same bytes).
 */
object EmptyGlyphs {
    fun pbf(font: String, range: String): ByteArray {
        val stack = field(0x0A, font.encodeToByteArray()) + field(0x12, range.encodeToByteArray())
        return field(0x0A, stack)
    }

    private fun field(tag: Int, bytes: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + varint(bytes.size) + bytes

    private fun varint(v: Int): ByteArray {
        val out = ArrayList<Byte>()
        var x = v
        while (x >= 0x80) {
            out += ((x and 0x7F) or 0x80).toByte()
            x = x ushr 7
        }
        out += x.toByte()
        return out.toByteArray()
    }

    /**
     * The fontstacks a glyph PBF holds, each with its `name` and `range` (field 1 of `glyphs`, fields 1 and 2 of
     * `fontstack`); throws [IllegalArgumentException] for bytes that are not such a message.
     */
    fun stacks(pbf: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var p = 0
        fun varintAt(): Int {
            var shift = 0
            var v = 0L
            while (true) {
                require(p < pbf.size) { "truncated varint" }
                val b = pbf[p++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return v.toInt()
                shift += 7
                require(shift < 35) { "varint too long" }
            }
        }
        while (p < pbf.size) {
            val key = varintAt()
            require(key == 0x0A) { "unexpected field $key in glyphs" }
            val len = varintAt()
            require(len >= 0 && p + len <= pbf.size) { "fontstack overruns the message" }
            val end = p + len
            var name: String? = null
            var range: String? = null
            while (p < end) {
                val k = varintAt()
                when (k and 7) {
                    2 -> {
                        val l = varintAt()
                        require(p + l <= end) { "field overruns the fontstack" }
                        when (k ushr 3) {
                            1 -> name = pbf.decodeToString(p, p + l)
                            2 -> range = pbf.decodeToString(p, p + l)
                        }
                        p += l
                    }
                    0 -> varintAt()
                    else -> throw IllegalArgumentException("unexpected wire type in fontstack")
                }
            }
            out += (name ?: throw IllegalArgumentException("fontstack without a name")) to (range ?: throw IllegalArgumentException("fontstack without a range"))
        }
        require(out.isNotEmpty()) { "no fontstack" }
        return out
    }
}
