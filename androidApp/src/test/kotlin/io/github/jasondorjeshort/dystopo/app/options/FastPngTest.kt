package io.github.jasondorjeshort.dystopo.app.options

import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals

class FastPngTest {
    private fun int(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    /** A small PNG reader for what [FastPng] writes (RGBA 8-bit, sub filter), checking chunk CRCs. */
    private fun decode(png: ByteArray): Triple<Int, Int, IntArray> {
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code, 13, 10, 26, 10), png.take(8).map { it.toInt() and 0xFF })
        var o = 8
        var w = 0
        var h = 0
        var idat = ByteArray(0)
        while (o < png.size) {
            val len = int(png, o)
            val type = String(png, o + 4, 4, Charsets.US_ASCII)
            val data = png.copyOfRange(o + 8, o + 8 + len)
            val crc = CRC32().apply { update(png, o + 4, 4 + len) }
            assertEquals(crc.value.toInt(), int(png, o + 8 + len), "crc of $type")
            when (type) {
                "IHDR" -> {
                    w = int(data, 0)
                    h = int(data, 4)
                    assertEquals(8, data[8].toInt())
                    assertEquals(6, data[9].toInt())
                }
                "IDAT" -> idat += data
            }
            o += 12 + len
        }
        val raw = ByteArray((w * 4 + 1) * h)
        Inflater().apply { setInput(idat); assertEquals(raw.size, inflate(raw)); end() }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val r = y * (w * 4 + 1)
            assertEquals(1, raw[r].toInt(), "sub filter")
            val c = IntArray(4)
            for (x in 0 until w) {
                for (k in 0 until 4) c[k] = (c[k] + raw[r + 1 + x * 4 + k]) and 0xFF
                out[y * w + x] = (c[3] shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
            }
        }
        return Triple(w, h, out)
    }

    @Test
    fun decodesBackToTheSamePixels() {
        val w = 37
        val h = 23
        // Non-premultiplied ARGB with every channel varying, fully transparent and opaque pixels included.
        val argb = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val a = when {
                (x + y) % 7 == 0 -> 0
                (x + y) % 5 == 0 -> 255
                else -> (x * 7 + y * 3) and 0xFF
            }
            (a shl 24) or (((x * 13) and 0xFF) shl 16) or (((y * 29) and 0xFF) shl 8) or ((x * y) and 0xFF)
        }
        val (dw, dh, pixels) = decode(FastPng.encode(argb, w, h))
        assertEquals(w, dw)
        assertEquals(h, dh)
        for (i in argb.indices) assertEquals(argb[i], pixels[i], "pixel $i")
    }
}
