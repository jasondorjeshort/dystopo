package io.github.jasondorjeshort.dystopo.app.options

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A minimal RGBA PNG writer for shade tiles: the "sub" filter on every row and zlib at its fastest level.
 * `Bitmap.compress` spent 40 to 90 ms per 256 px shade tile on the emulator (most of the tile's time);
 * this takes a few milliseconds for somewhat larger files, which only travel over loopback and sit in the
 * disk cache. [argb] is non-premultiplied, row-major.
 */
object FastPng {
    private val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    fun encode(argb: IntArray, width: Int, height: Int, level: Int = 1): ByteArray {
        require(argb.size == width * height) { "expected ${width * height} pixels" }
        val stride = width * 4 + 1
        val raw = ByteArray(stride * height)
        for (y in 0 until height) {
            val o = y * stride
            raw[o] = 1 // sub: each byte minus the same channel of the pixel to its left
            var pr = 0
            var pg = 0
            var pb = 0
            var pa = 0
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val a = p ushr 24
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val i = o + 1 + x * 4
                raw[i] = (r - pr).toByte()
                raw[i + 1] = (g - pg).toByte()
                raw[i + 2] = (b - pb).toByte()
                raw[i + 3] = (a - pa).toByte()
                pr = r; pg = g; pb = b; pa = a
            }
        }
        val deflater = Deflater(level)
        val zipped = ByteArrayOutputStream(raw.size / 3)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val buf = ByteArray(32 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                zipped.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        val out = ByteArrayOutputStream(zipped.size() + 64)
        out.write(signature)
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width)
        putInt(ihdr, 4, height)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 6 // RGBA
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", zipped.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4).also { putInt(it, 0, data.size) }
        out.write(len)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        out.write(ByteArray(4).also { putInt(it, 0, crc.value.toInt()) })
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }
}
