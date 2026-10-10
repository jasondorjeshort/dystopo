package io.github.jasondorjeshort.dystopo.png

import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import kotlin.jvm.Synchronized
import kotlin.time.TimeSource

/** A PNG this decoder does not handle (palette, grey, 16-bit, interlaced...): use a full decoder instead. */
class UnsupportedPngException(message: String) : IllegalArgumentException(message)

/**
 * The pixels of a decoded PNG: [height] rows of [width] pixels with [channels] bytes each (3 = RGB,
 * 4 = RGBA), starting at [offset] in [data], [rowStride] bytes apart (rows keep their filter byte in
 * front, so the inflated buffer is used in place with no copy).
 */
class PngPixels(val width: Int, val height: Int, val channels: Int, val data: ByteArray, val offset: Int, val rowStride: Int) {
    /** All pixels as ARGB, row-major; alpha is 0xFF for RGB images. */
    fun argb(): IntArray {
        val out = IntArray(width * height)
        val d = data
        var k = 0
        for (y in 0 until height) {
            var i = offset + y * rowStride
            for (x in 0 until width) {
                val a = if (channels == 4) d[i + 3].toInt() and 0xFF else 0xFF
                out[k++] = (a shl 24) or ((d[i].toInt() and 0xFF) shl 16) or ((d[i + 1].toInt() and 0xFF) shl 8) or (d[i + 2].toInt() and 0xFF)
                i += channels
            }
        }
        return out
    }
}

/**
 * A small PNG decoder in pure Kotlin for the images this project reads itself, Terrarium DEM tiles:
 * 8-bit RGB or RGBA, not interlaced, every scanline filter (none, sub, up, average, Paeth). The zlib
 * stream of the IDAT chunks is inflated by the injected [inflate] (java.util.zip on the JVM and on
 * Android), called once per image with the exact unfiltered size. Anything else (palette, grey,
 * 16-bit, interlaced) throws [UnsupportedPngException], so a caller can fall back to a platform
 * decoder; malformed data throws [IllegalArgumentException]. Chunk CRCs are not checked (the data
 * comes from our own packs, and a corrupt stream fails zlib's own Adler-32 check).
 *
 * Thread-safe when [inflate] is: it keeps no state between calls.
 */
class PngDecoder(private val inflate: Decompressor) {
    /** IHDR fields. */
    class Header(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int, val interlace: Int)

    /** Elevations decoded by [terrarium], row-major. */
    class TerrariumTile(val width: Int, val height: Int, val elevations: FloatArray)

    /** Decodes [bytes] to unfiltered pixels. */
    fun decode(bytes: ByteArray): PngPixels {
        val header = header(bytes)
        val channels = when (header.colorType) {
            2 -> 3
            6 -> 4
            else -> throw UnsupportedPngException("PNG colour type ${header.colorType} not supported (RGB or RGBA only)")
        }
        if (header.bitDepth != 8) throw UnsupportedPngException("PNG bit depth ${header.bitDepth} not supported (8 only)")
        if (header.interlace != 0) throw UnsupportedPngException("interlaced PNG not supported")
        val w = header.width
        val h = header.height
        val stride = w.toLong() * channels + 1
        require(stride * h <= Int.MAX_VALUE) { "PNG too large: ${w}x$h" }
        val size = (stride * h).toInt()
        val t0 = clock.markNow()
        val raw = inflate(idat(bytes), size)
        require(raw.size >= size) { "PNG image data too short: ${raw.size} of $size bytes" }
        val t1 = clock.markNow()
        unfilter(raw, w * channels, h, channels)
        val t2 = clock.markNow()
        record((t1 - t0).inWholeNanoseconds, (t2 - t1).inWholeNanoseconds, 0L)
        return PngPixels(w, h, channels, raw, 1, w * channels + 1)
    }

    private val clock = TimeSource.Monotonic
    private var images = 0L
    private var inflateNanos = 0L
    private var unfilterNanos = 0L
    private var convertNanos = 0L

    @Synchronized
    private fun record(inflateNs: Long, unfilterNs: Long, convertNs: Long) {
        if (inflateNs > 0 || unfilterNs > 0) images++
        inflateNanos += inflateNs
        unfilterNanos += unfilterNs
        convertNanos += convertNs
    }

    /** Time spent per phase over every image decoded so far: images, then inflate, unfilter, Terrarium conversion in ms. */
    @Synchronized
    fun phases(): Map<String, Any?> = linkedMapOf(
        "images" to images,
        "inflateMs" to inflateNanos / 1_000_000L,
        "unfilterMs" to unfilterNanos / 1_000_000L,
        "convertMs" to convertNanos / 1_000_000L,
    )

    /**
     * Decodes a Terrarium tile straight to elevations in metres (`R * 256 + G + B / 256 - 32768`, the
     * same float arithmetic as `Terrarium.elevation`, so both paths agree bit for bit), with no ARGB
     * array in between.
     */
    fun terrarium(bytes: ByteArray): TerrariumTile {
        val p = decode(bytes)
        val t0 = clock.markNow()
        val out = FloatArray(p.width * p.height)
        val d = p.data
        val c = p.channels
        var k = 0
        for (y in 0 until p.height) {
            var i = p.offset + y * p.rowStride
            for (x in 0 until p.width) {
                val r = d[i].toInt() and 0xFF
                val g = d[i + 1].toInt() and 0xFF
                val b = d[i + 2].toInt() and 0xFF
                // b * 2^-8 is exactly b / 256f (a power of two), and cheaper.
                out[k++] = ((r shl 8) + g + b * INV_256) - 32768f
                i += c
            }
        }
        record(0L, 0L, (clock.markNow() - t0).inWholeNanoseconds)
        return TerrariumTile(p.width, p.height, out)
    }

    companion object {
        private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private const val IHDR = 0x49484452
        private const val IDAT = 0x49444154
        private const val IEND = 0x49454E44
        private const val INV_256 = 1f / 256f

        /** Reads the signature and IHDR. */
        fun header(bytes: ByteArray): Header {
            require(bytes.size >= 33) { "not a PNG (${bytes.size} bytes)" }
            for (i in SIGNATURE.indices) require(bytes[i] == SIGNATURE[i]) { "not a PNG (bad signature)" }
            require(int(bytes, 8) == 13 && int(bytes, 12) == IHDR) { "PNG does not start with IHDR" }
            val w = int(bytes, 16)
            val h = int(bytes, 20)
            require(w > 0 && h > 0) { "bad PNG size ${w}x$h" }
            val compression = bytes[26].toInt()
            val filter = bytes[27].toInt()
            require(compression == 0 && filter == 0) { "bad PNG compression/filter method $compression/$filter" }
            return Header(w, h, bytes[24].toInt() and 0xFF, bytes[25].toInt() and 0xFF, bytes[28].toInt() and 0xFF)
        }

        /** The zlib stream: the data of every IDAT chunk, concatenated. */
        private fun idat(bytes: ByteArray): ByteArray {
            var pos = 8
            val starts = ArrayList<Int>(2)
            val lengths = ArrayList<Int>(2)
            while (pos + 8 <= bytes.size) {
                val len = int(bytes, pos)
                val type = int(bytes, pos + 4)
                require(len >= 0 && pos.toLong() + 12 + len <= bytes.size) { "truncated PNG chunk at byte $pos" }
                if (type == IDAT) {
                    starts += pos + 8
                    lengths += len
                } else if (type == IEND) {
                    break
                }
                pos += 12 + len
            }
            require(starts.isNotEmpty()) { "PNG has no image data" }
            if (starts.size == 1) return bytes.copyOfRange(starts[0], starts[0] + lengths[0])
            val out = ByteArray(lengths.sum())
            var o = 0
            for (i in starts.indices) {
                bytes.copyInto(out, o, starts[i], starts[i] + lengths[i])
                o += lengths[i]
            }
            return out
        }

        /**
         * Undoes the scanline filters in place: [data] holds [height] rows of a filter byte plus [rowBytes]
         * bytes, [bpp] bytes per pixel. The row above is already unfiltered when a row is processed.
         */
        internal fun unfilter(data: ByteArray, rowBytes: Int, height: Int, bpp: Int) {
            val stride = rowBytes + 1
            for (y in 0 until height) {
                val f = data[y * stride].toInt()
                val s = y * stride + 1
                val end = s + rowBytes
                val up = -stride // offset of the byte above
                when (f) {
                    0 -> Unit
                    1 -> for (i in s + bpp until end) data[i] = (data[i] + data[i - bpp]).toByte()
                    2 -> if (y > 0) for (i in s until end) data[i] = (data[i] + data[i + up]).toByte()
                    3 -> if (y > 0) {
                        for (i in s until s + bpp) data[i] = (data[i] + ((data[i + up].toInt() and 0xFF) ushr 1)).toByte()
                        for (i in s + bpp until end) {
                            val a = data[i - bpp].toInt() and 0xFF
                            val b = data[i + up].toInt() and 0xFF
                            data[i] = (data[i] + ((a + b) ushr 1)).toByte()
                        }
                    } else {
                        for (i in s + bpp until end) data[i] = (data[i] + ((data[i - bpp].toInt() and 0xFF) ushr 1)).toByte()
                    }
                    4 -> if (y > 0) {
                        // Paeth with a = c = 0 (first pixel) predicts b: plain "up".
                        for (i in s until s + bpp) data[i] = (data[i] + data[i + up]).toByte()
                        for (i in s + bpp until end) {
                            val a = data[i - bpp].toInt() and 0xFF
                            val b = data[i + up].toInt() and 0xFF
                            val c = data[i + up - bpp].toInt() and 0xFF
                            val pa = if (b > c) b - c else c - b
                            val pb = if (a > c) a - c else c - a
                            val t = a + b - c - c
                            val pc = if (t < 0) -t else t
                            val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                            data[i] = (data[i] + pred).toByte()
                        }
                    } else {
                        // Paeth on the first row (b = c = 0) predicts a: plain "sub".
                        for (i in s + bpp until end) data[i] = (data[i] + data[i - bpp]).toByte()
                    }
                    else -> throw IllegalArgumentException("bad PNG filter type $f in row $y")
                }
            }
        }

        private fun int(b: ByteArray, i: Int): Int =
            ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
                ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
    }
}
