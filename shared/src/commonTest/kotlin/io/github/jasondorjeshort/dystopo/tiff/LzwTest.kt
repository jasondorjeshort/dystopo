package io.github.jasondorjeshort.dystopo.tiff

import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * T110: the LZW decoder rewritten as one loop gives the same bytes as before ([LzwReference]) on streams a TIFF writer
 * makes (libtiff's rules: 9 to 12 bit codes switched one code early, a clear code when the table holds 4094 entries,
 * end of information), including blocks the size of a 3DEP tile that fill and clear the table many times.
 */
class LzwTest {
    /** A TIFF LZW encoder as libtiff's: MSB first, early change, clear at 4094 entries. */
    private fun encode(data: ByteArray): ByteArray {
        val bits = ArrayList<Byte>()
        var acc = 0L
        var count = 0
        fun put(code: Int, width: Int) {
            acc = (acc shl width) or code.toLong()
            count += width
            while (count >= 8) {
                count -= 8
                bits += ((acc ushr count) and 0xFF).toByte()
            }
            acc = acc and ((1L shl count) - 1)
        }
        val dict = HashMap<Int, Int>()
        var width = 9
        var next = 258
        put(256, width)
        var w = -1
        for (b in data) {
            val k = b.toInt() and 0xFF
            if (w < 0) {
                w = k
                continue
            }
            val key = (w shl 8) or k
            val hit = dict[key]
            if (hit != null) {
                w = hit
                continue
            }
            put(w, width)
            dict[key] = next++
            if (next == 4094) {
                put(256, width)
                dict.clear()
                next = 258
                width = 9
            } else if (next > (1 shl width) - 1) {
                width++
            }
            w = k
        }
        if (w >= 0) {
            put(w, width)
            next++
            if (next > (1 shl width) - 1 && width < 12) width++
        }
        put(257, width)
        if (count > 0) bits += ((acc shl (8 - count)) and 0xFF).toByte()
        return bits.toByteArray()
    }

    private fun roundTrip(data: ByteArray) {
        val enc = encode(data)
        assertContentEquals(data, Lzw.decode(enc, data.size))
        assertContentEquals(LzwReference.decode(enc, data.size), Lzw.decode(enc, data.size))
    }

    @Test
    fun smallStreamsDecodeAsBefore() {
        roundTrip("ABABABA".encodeToByteArray())
        roundTrip("TOBEORNOTTOBEORTOBEORNOT#".encodeToByteArray())
        roundTrip(ByteArray(1) { 7 })
        roundTrip(ByteArray(5000) { 0 }) // long runs: the code == next case over and over
    }

    @Test
    fun randomBytesFillAndClearTheTable() {
        val r = Random(110)
        roundTrip(ByteArray(200_000) { r.nextInt(256).toByte() })
        roundTrip(ByteArray(300_000) { r.nextInt(4).toByte() })
    }

    @Test
    fun aDemBlockWithTheFloatingPointPredictorDecodesAsBefore() {
        // A 512 x 512 float tile like a 3DEP block, byte planes delta coded per row as predictor 3 writes them.
        val n = 512
        val bytes = ByteArray(n * n * 4)
        for (y in 0 until n) {
            val row = ByteArray(n * 4)
            for (x in 0 until n) {
                val v = (1500.0 + 800.0 * sin(x / 37.0) * sin(y / 53.0) + (x * 7 + y * 13) % 5 * 0.1).toFloat()
                val b = v.toRawBits()
                row[x] = (b ushr 24).toByte()
                row[n + x] = (b ushr 16).toByte()
                row[2 * n + x] = (b ushr 8).toByte()
                row[3 * n + x] = b.toByte()
            }
            for (i in row.size - 1 downTo 1) row[i] = (row[i] - row[i - 1]).toByte()
            row.copyInto(bytes, y * n * 4)
        }
        roundTrip(bytes)
    }

    @Test
    fun malformedStreamsAreRefused() {
        // No clear code first.
        val codes = listOf(65, 66, 257)
        val bits = codes.joinToString("") { it.toString(2).padStart(9, '0') }.padEnd(32, '0')
        val bytes = ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        assertFailsWith<IllegalArgumentException> { Lzw.decode(bytes, 10) }
        // More output than expected.
        assertFailsWith<IllegalArgumentException> { Lzw.decode(encode(ByteArray(100) { 1 }), 50) }
    }
}
