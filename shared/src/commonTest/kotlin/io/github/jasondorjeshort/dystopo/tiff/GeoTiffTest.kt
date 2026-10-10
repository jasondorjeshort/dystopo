package io.github.jasondorjeshort.dystopo.tiff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoTiffTest {
    /** Little-endian TIFF writer for test fixtures: one band, float32, tiled, uncompressed. */
    private class TiffBuilder {
        private val buf = ArrayList<Byte>()
        val size get() = buf.size
        fun u16(v: Int) { buf += (v and 0xFF).toByte(); buf += ((v shr 8) and 0xFF).toByte() }
        fun u32(v: Long) { for (i in 0 until 4) buf += ((v shr (8 * i)) and 0xFF).toByte() }
        fun f64(v: Double) { val b = v.toRawBits(); for (i in 0 until 8) buf += ((b shr (8 * i)) and 0xFF).toByte() }
        fun bytes(b: ByteArray) { b.forEach { buf += it } }
        fun set32(pos: Int, v: Long) { for (i in 0 until 4) buf[pos + i] = ((v shr (8 * i)) and 0xFF).toByte() }
        fun toByteArray() = buf.toByteArray()
    }

    private fun floatBytesLE(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, f ->
            val b = f.toRawBits()
            for (k in 0 until 4) out[i * 4 + k] = ((b shr (8 * k)) and 0xFF).toByte()
        }
        return out
    }

    /**
     * 3x2 image in 2x2 tiles (so 2 tiles across, 1 down, right tile padded), or with [strips] in two
     * one-row strips.
     */
    private fun buildTiff(pixelIsPoint: Boolean, noData: String?, strips: Boolean = false): ByteArray {
        val image = arrayOf(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f))
        val tile0 = if (strips) image[0] else floatArrayOf(image[0][0], image[0][1], image[1][0], image[1][1])
        val tile1 = if (strips) image[1] else floatArrayOf(image[0][2], 0f, image[1][2], 0f)

        val t = TiffBuilder()
        t.bytes("II".encodeToByteArray()); t.u16(42); t.u32(8)
        // Out-of-line data first, IFD after; tags need these offsets.
        val tags = ArrayList<Triple<Int, Int, Any>>() // tag, type, value (Long inline, or offset placeholder)
        val ifdEntries = (if (strips) 14 else 15) + (if (noData != null) 1 else 0)
        val ifdStart = 8
        val dataStart = ifdStart + 2 + ifdEntries * 12 + 4
        var cursor = dataStart
        val data = TiffBuilder()
        fun place(bytes: ByteArray): Long { val at = cursor; data.bytes(bytes); cursor += bytes.size; return at.toLong() }
        val t0 = place(floatBytesLE(tile0))
        val t1 = place(floatBytesLE(tile1))
        val offsets = TiffBuilder().apply { u32(t0); u32(t1) }.toByteArray()
        val counts = TiffBuilder().apply { u32(tile0.size * 4L); u32(tile1.size * 4L) }.toByteArray()
        val offsetsAt = place(offsets)
        val countsAt = place(counts)
        val scaleAt = place(TiffBuilder().apply { f64(0.5); f64(0.25); f64(0.0) }.toByteArray())
        val tieAt = place(TiffBuilder().apply { f64(0.0); f64(0.0); f64(0.0); f64(-122.0); f64(47.0); f64(0.0) }.toByteArray())
        val keys = TiffBuilder().apply { listOf(1, 1, 0, 1, 1025, 0, 1, if (pixelIsPoint) 2 else 1).forEach { u16(it) } }.toByteArray()
        val keysAt = place(keys)
        val noDataAt = noData?.let { place((it + "\u0000").encodeToByteArray()) }

        tags += Triple(256, 3, 3L); tags += Triple(257, 3, 2L); tags += Triple(258, 3, 32L)
        tags += Triple(259, 3, 1L); tags += Triple(277, 3, 1L); tags += Triple(317, 3, 1L)
        if (strips) {
            tags += Triple(273, 4, listOf(2L, offsetsAt)); tags += Triple(278, 3, 1L); tags += Triple(279, 4, listOf(2L, countsAt))
        } else {
            tags += Triple(322, 3, 2L); tags += Triple(323, 3, 2L)
            tags += Triple(324, 4, listOf(2L, offsetsAt)); tags += Triple(325, 4, listOf(2L, countsAt))
        }
        tags += Triple(339, 3, 3L)
        tags += Triple(33550, 12, listOf(3L, scaleAt)); tags += Triple(33922, 12, listOf(6L, tieAt))
        tags += Triple(34735, 3, listOf(8L, keysAt))
        tags += Triple(284, 3, 1L)
        if (noDataAt != null) tags += Triple(42113, 2, listOf((noData.length + 1).toLong(), noDataAt))
        tags.sortBy { it.first }

        t.u16(tags.size)
        for ((tag, type, value) in tags) {
            t.u16(tag); t.u16(type)
            if (value is Long) { t.u32(1); if (type == 3) { t.u16(value.toInt()); t.u16(0) } else t.u32(value) }
            else { val (count, at) = (value as List<*>).map { it as Long }; t.u32(count); t.u32(at) }
        }
        t.u32(0)
        check(t.size == dataStart)
        t.bytes(data.toByteArray())
        return t.toByteArray()
    }

    private fun source(bytes: ByteArray) = ByteRangeSource { offset, length ->
        bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
    }

    @Test
    fun readsTilesAndGeoreference() {
        val tiff = GeoTiff.open(source(buildTiff(pixelIsPoint = false, noData = "-9999")))
        assertEquals(3, tiff.width)
        assertEquals(2, tiff.height)
        assertEquals(2, tiff.tilesAcross)
        assertEquals(1, tiff.tilesDown)
        assertEquals(listOf(1f, 2f, 4f, 5f), tiff.readTile(0, 0).toList())
        assertEquals(3f, tiff.readTile(1, 0)[0])
        assertEquals(6f, tiff.readTile(1, 0)[2])
        assertEquals(-9999f, tiff.noData)
        // PixelIsArea: the tiepoint is the outer corner, so pixel (0,0)'s center is half a pixel in.
        assertEquals(-121.75, tiff.xOf(0.0), 1e-12)
        assertEquals(46.875, tiff.yOf(0.0), 1e-12)
        assertEquals(0.0, tiff.colOf(-121.75), 1e-12)
        assertEquals(1.0, tiff.rowOf(46.625), 1e-12)
    }

    @Test
    fun readsStripsAsFullWidthTiles() {
        val tiff = GeoTiff.open(source(buildTiff(pixelIsPoint = false, noData = null, strips = true)))
        assertEquals(3, tiff.tileWidth)
        assertEquals(1, tiff.tileHeight)
        assertEquals(1, tiff.tilesAcross)
        assertEquals(2, tiff.tilesDown)
        assertEquals(listOf(1f, 2f, 3f), tiff.readTile(0, 0).toList())
        assertEquals(listOf(4f, 5f, 6f), tiff.readTile(0, 1).toList())
    }

    @Test
    fun pixelIsPointPutsCentersOnTheTiepoint() {
        val tiff = GeoTiff.open(source(buildTiff(pixelIsPoint = true, noData = null)))
        assertTrue(tiff.pixelIsPoint)
        assertNull(tiff.noData)
        assertEquals(-122.0, tiff.xOf(0.0), 1e-12)
        assertEquals(47.0, tiff.yOf(0.0), 1e-12)
    }

    @Test
    fun floatPredictorRoundTrips() {
        val values = floatArrayOf(2341.5f, 2342.25f, -1.0f, 4361.277f, 0f, 1e-3f)
        val width = 3
        val height = 2
        // Encode: per row, split floats into big-endian byte planes, then byte-wise delta.
        val bytes = ByteArray(values.size * 4)
        for (y in 0 until height) {
            val base = y * width * 4
            for (x in 0 until width) {
                val bits = values[y * width + x].toRawBits()
                for (k in 0 until 4) bytes[base + k * width + x] = ((bits shr (24 - 8 * k)) and 0xFF).toByte()
            }
            for (i in width * 4 - 1 downTo 1) bytes[base + i] = (bytes[base + i] - bytes[base + i - 1]).toByte()
        }
        assertEquals(values.toList(), GeoTiff.undoFloatPredictor(bytes, width, height).toList())
    }

    @Test
    fun horizontalPredictorUndoesIntegerDifferences() {
        val values = floatArrayOf(2341.5f, 2342.25f, 2340f, -999999f, 1f, 2f)
        val width = 3
        val bytes = ByteArray(values.size * 4)
        for (y in 0 until 2) {
            var prev = 0
            for (x in 0 until width) {
                val bits = values[y * width + x].toRawBits()
                val diff = bits - prev
                prev = bits
                for (k in 0 until 4) bytes[(y * width + x) * 4 + k] = ((diff shr (8 * k)) and 0xFF).toByte()
            }
        }
        assertEquals(values.toList(), GeoTiff.undoHorizontalPredictor32(bytes, width, 2, littleEndian = true).toList())
    }

    @Test
    fun lzwDecodesKnownStream() {
        // "ABABABA" hand-encoded: CLEAR, 'A', 'B', 258(AB), 260(ABA), EOI with 9-bit codes.
        val codes = listOf(256, 65, 66, 258, 260, 257)
        val bits = codes.joinToString("") { it.toString(2).padStart(9, '0') }.padEnd(56, '0')
        val bytes = ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        assertEquals("ABABABA", Lzw.decode(bytes, 7).decodeToString())
    }
}
