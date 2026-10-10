package io.github.jasondorjeshort.dystopo.png

import io.github.jasondorjeshort.dystopo.tiff.Decompressor

/**
 * DEFLATE (RFC 1951) and zlib (RFC 1950) decompression in pure Kotlin, for a known output size.
 *
 * Why it exists although every platform has zlib: on Android, `java.util.zip.Inflater` measured 3 to 8
 * times slower than BitmapFactory's whole PNG decode on the emulator, and much slower again while other
 * threads allocate (its JNI critical sections wait for the garbage collector), which made the shared PNG
 * decoder lose to BitmapFactory on the device (T42). This one runs as plain compiled code with a 10-bit
 * Huffman lookup table, a 64-bit bit buffer and array copies for non-overlapping matches. It is a
 * [Decompressor] like any other, injected where it wins (Android); the JVM keeps java.util.zip.
 *
 * The zlib Adler-32 trailer is verified, so a corrupt stream that still decodes to the right size (a
 * flipped literal bit, say) fails instead of producing silently wrong bytes; preset dictionaries are rejected.
 */
object Inflate {
    /** zlib stream ([input]) to exactly [expectedSize] bytes. */
    val zlib: Decompressor = { input, expectedSize -> zlib(input, expectedSize) }

    fun zlib(input: ByteArray, expectedSize: Int): ByteArray {
        require(input.size >= 2) { "zlib: stream too short" }
        val cmf = input[0].toInt() and 0xFF
        val flg = input[1].toInt() and 0xFF
        require(cmf and 0x0F == 8 && (cmf shl 8 or flg) % 31 == 0) { "zlib: bad header" }
        require(flg and 0x20 == 0) { "zlib: preset dictionary not supported" }
        val out = ByteArray(expectedSize)
        val state = State(input, 2, out)
        val n = state.run()
        require(n == expectedSize) { "inflate: got $n bytes, expected $expectedSize" }
        val end = state.endOfStream()
        require(end + 4 <= input.size) { "zlib: missing Adler-32 checksum" }
        var stored = 0
        for (i in 0 until 4) stored = (stored shl 8) or (input[end + i].toInt() and 0xFF)
        val actual = adler32(out)
        require(stored == actual) { "zlib: Adler-32 mismatch (stored ${stored.toUInt().toString(16)}, computed ${actual.toUInt().toString(16)})" }
        return out
    }

    /** Raw DEFLATE data starting at [offset] of [input] to exactly [expectedSize] bytes. */
    fun raw(input: ByteArray, offset: Int, expectedSize: Int): ByteArray {
        val out = ByteArray(expectedSize)
        val n = State(input, offset, out).run()
        require(n == expectedSize) { "inflate: got $n bytes, expected $expectedSize" }
        return out
    }

    /** Adler-32 (RFC 1950) of [data]; sums are reduced every 5552 bytes, the most that cannot overflow. */
    fun adler32(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var a = 1L
        var b = 0L
        var i = from
        while (i < to) {
            val end = minOf(to, i + 5552)
            while (i < end) {
                a += data[i++].toLong() and 0xFF
                b += a
            }
            a %= 65521
            b %= 65521
        }
        return ((b shl 16) or a).toInt()
    }

    private const val PRIMARY = 10
    private const val PRIMARY_MASK = (1 shl PRIMARY) - 1

    private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
        1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /**
     * A canonical Huffman code: [table] maps the next [PRIMARY] bits (LSB first) to `symbol shl 4 | length`
     * for codes up to [PRIMARY] bits (0 = a longer code); [count] and [symbol] decode longer codes bit by bit.
     */
    private class Huffman(lengths: IntArray, n: Int) {
        val table = IntArray(1 shl PRIMARY)
        val count = IntArray(16)
        val symbol = IntArray(n)

        init {
            for (i in 0 until n) count[lengths[i]]++
            count[0] = 0
            val offs = IntArray(16)
            for (len in 1 until 16) offs[len] = offs[len - 1] + count[len - 1]
            for (i in 0 until n) if (lengths[i] != 0) symbol[offs[lengths[i]]++] = i
            // Canonical codes, assigned in order of (length, symbol), stored bit-reversed for LSB-first reading.
            var code = 0
            val next = IntArray(16)
            for (len in 1 until 16) {
                next[len] = code
                code = (code + count[len]) shl 1
            }
            for (i in 0 until n) {
                val len = lengths[i]
                if (len == 0 || len > PRIMARY) continue
                val c = next[len]++
                var rev = 0
                for (b in 0 until len) rev = rev or (((c shr b) and 1) shl (len - 1 - b))
                val entry = (i shl 4) or len
                var j = rev
                while (j < table.size) {
                    table[j] = entry
                    j += 1 shl len
                }
            }
        }
    }

    private val FIXED_LIT: Huffman by lazy {
        val l = IntArray(288) { i -> if (i < 144) 8 else if (i < 256) 9 else if (i < 280) 7 else 8 }
        Huffman(l, 288)
    }
    private val FIXED_DIST: Huffman by lazy { Huffman(IntArray(30) { 5 }, 30) }

    private class State(val input: ByteArray, var pos: Int, val out: ByteArray) {
        var bits = 0L
        var nbits = 0
        var o = 0

        /** Fills the bit buffer to at least 56 bits (zeros past the end, caught by [checkOverrun]). */
        fun refill() {
            while (nbits <= 56) {
                val b = if (pos < input.size) input[pos].toLong() and 0xFF else 0L
                pos++
                bits = bits or (b shl nbits)
                nbits += 8
            }
        }

        fun checkOverrun() {
            // Bytes read past the end are only fine while still sitting unused in the buffer.
            require(pos - nbits / 8 <= input.size) { "inflate: unexpected end of data" }
        }

        fun take(n: Int): Int {
            if (nbits < n) refill()
            val v = (bits and ((1L shl n) - 1)).toInt()
            bits = bits ushr n
            nbits -= n
            return v
        }

        fun decode(h: Huffman): Int {
            if (nbits < 15) refill()
            val e = h.table[(bits and PRIMARY_MASK.toLong()).toInt()]
            if (e != 0) {
                val len = e and 15
                bits = bits ushr len
                nbits -= len
                return e ushr 4
            }
            // Longer than PRIMARY bits: canonical decoding one bit at a time.
            var code = 0
            var first = 0
            var index = 0
            for (len in 1 until 16) {
                code = code or (bits and 1L).toInt()
                bits = bits ushr 1
                nbits--
                val c = h.count[len]
                if (code - first < c) return h.symbol[index + code - first]
                index += c
                first = (first + c) shl 1
                code = code shl 1
            }
            throw IllegalArgumentException("inflate: bad Huffman code")
        }

        fun run(): Int {
            do {
                val last = take(1)
                when (take(2)) {
                    0 -> stored()
                    1 -> codes(FIXED_LIT, FIXED_DIST)
                    2 -> dynamic()
                    else -> throw IllegalArgumentException("inflate: bad block type")
                }
                checkOverrun()
            } while (last == 0)
            return o
        }

        /** Input offset just past the last block, once [run] has returned (the zlib trailer starts here). */
        fun endOfStream(): Int = pos - (nbits ushr 3)

        fun stored() {
            // Drop to a byte boundary, then hand the whole bytes still buffered back to the input.
            val drop = nbits and 7
            bits = bits ushr drop
            nbits -= drop
            pos -= nbits / 8
            bits = 0
            nbits = 0
            require(pos + 4 <= input.size) { "inflate: unexpected end of data" }
            val len = (input[pos].toInt() and 0xFF) or ((input[pos + 1].toInt() and 0xFF) shl 8)
            val nlen = (input[pos + 2].toInt() and 0xFF) or ((input[pos + 3].toInt() and 0xFF) shl 8)
            require(len == nlen.inv() and 0xFFFF) { "inflate: bad stored block length" }
            pos += 4
            require(pos + len <= input.size) { "inflate: unexpected end of data" }
            require(o + len <= out.size) { "inflate: output larger than expected" }
            input.copyInto(out, o, pos, pos + len)
            pos += len
            o += len
        }

        fun dynamic() {
            val nlen = take(5) + 257
            val ndist = take(5) + 1
            val ncode = take(4) + 4
            require(nlen <= 286 && ndist <= 30) { "inflate: bad code counts" }
            val cl = IntArray(19)
            for (i in 0 until ncode) cl[CL_ORDER[i]] = take(3)
            val clh = Huffman(cl, 19)
            val lengths = IntArray(nlen + ndist)
            var i = 0
            while (i < nlen + ndist) {
                val sym = decode(clh)
                if (sym < 16) {
                    lengths[i++] = sym
                    continue
                }
                var rep: Int
                var v = 0
                when (sym) {
                    16 -> {
                        require(i > 0) { "inflate: repeat with no previous length" }
                        v = lengths[i - 1]
                        rep = 3 + take(2)
                    }
                    17 -> rep = 3 + take(3)
                    else -> rep = 11 + take(7)
                }
                require(i + rep <= nlen + ndist) { "inflate: too many lengths" }
                while (rep-- > 0) lengths[i++] = v
            }
            require(lengths[256] != 0) { "inflate: no end-of-block code" }
            val lit = Huffman(lengths, nlen)
            val dist = Huffman(lengths.copyOfRange(nlen, nlen + ndist), ndist)
            codes(lit, dist)
        }

        fun codes(lit: Huffman, dist: Huffman) {
            val out = out
            val size = out.size
            val litTable = lit.table
            while (true) {
                // 48 bits cover the longest length/distance pair; a refill then lasts several literals.
                if (nbits < 48) refill()
                val e = litTable[(bits and PRIMARY_MASK.toLong()).toInt()]
                val sym: Int
                if (e != 0) {
                    val len = e and 15
                    bits = bits ushr len
                    nbits -= len
                    sym = e ushr 4
                } else {
                    sym = decode(lit)
                }
                if (sym < 256) {
                    if (o >= size) throw IllegalArgumentException("inflate: output larger than expected")
                    out[o++] = sym.toByte()
                    continue
                }
                if (sym == 256) return
                val li = sym - 257
                require(li < 29) { "inflate: bad length code" }
                val len = LEN_BASE[li] + take(LEN_EXTRA[li])
                val di = decode(dist)
                require(di < 30) { "inflate: bad distance code" }
                val d = DIST_BASE[di] + take(DIST_EXTRA[di])
                require(d <= o) { "inflate: distance too far back" }
                require(o + len <= size) { "inflate: output larger than expected" }
                var from = o - d
                if (d >= len) {
                    out.copyInto(out, o, from, from + len)
                    o += len
                } else {
                    val end = o + len
                    while (o < end) out[o++] = out[from++]
                }
            }
        }
    }
}
