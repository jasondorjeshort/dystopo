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
    private fun buildTiff(pixelIsPoint: Boolean, noData: String?, strips: Boolean = false, intScale: Double? = null): ByteArray {
        val image = arrayOf(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f))
        val tile0 = if (strips) image[0] else floatArrayOf(image[0][0], image[0][1], image[1][0], image[1][1])
        val tile1 = if (strips) image[1] else floatArrayOf(image[0][2], 0f, image[1][2], 0f)

        val t = TiffBuilder()
        t.bytes("II".encodeToByteArray()); t.u16(42); t.u32(8)
        // Out-of-line data first, IFD after; tags need these offsets.
        val tags = ArrayList<Triple<Int, Int, Any>>() // tag, type, value (Long inline, or offset placeholder)
        val ifdEntries = (if (strips) 14 else 15) + (if (noData != null) 1 else 0) + (if (intScale != null) 1 else 0)
        val ifdStart = 8
        val dataStart = ifdStart + 2 + ifdEntries * 12 + 4
        var cursor = dataStart
        val data = TiffBuilder()
        fun place(bytes: ByteArray): Long { val at = cursor; data.bytes(bytes); cursor += bytes.size; return at.toLong() }
        // Integer samples are stored as round(value / scale), as GDAL writes PROJ's Canadian geoid grids.
        fun samples(tile: FloatArray): ByteArray =
            if (intScale == null) floatBytesLE(tile)
            else TiffBuilder().apply { tile.forEach { u32(kotlin.math.round(it / intScale).toLong() and 0xFFFFFFFFL) } }.toByteArray()
        val t0 = place(samples(tile0))
        val t1 = place(samples(tile1))
        val offsets = TiffBuilder().apply { u32(t0); u32(t1) }.toByteArray()
        val counts = TiffBuilder().apply { u32(tile0.size * 4L); u32(tile1.size * 4L) }.toByteArray()
        val offsetsAt = place(offsets)
        val countsAt = place(counts)
        val scaleAt = place(TiffBuilder().apply { f64(0.5); f64(0.25); f64(0.0) }.toByteArray())
        val tieAt = place(TiffBuilder().apply { f64(0.0); f64(0.0); f64(0.0); f64(-122.0); f64(47.0); f64(0.0) }.toByteArray())
        val keys = TiffBuilder().apply { listOf(1, 1, 0, 1, 1025, 0, 1, if (pixelIsPoint) 2 else 1).forEach { u16(it) } }.toByteArray()
        val keysAt = place(keys)
        val noDataAt = noData?.let { place((it + "\u0000").encodeToByteArray()) }
        val metadata = intScale?.let {
            "<GDALMetadata>\n  <Item name=\"OFFSET\" sample=\"0\" role=\"offset\">0</Item>\n" +
                "  <Item name=\"SCALE\" sample=\"0\" role=\"scale\">$it</Item>\n</GDALMetadata>\n\u0000"
        }
        val metadataAt = metadata?.let { place(it.encodeToByteArray()) }

        tags += Triple(256, 3, 3L); tags += Triple(257, 3, 2L); tags += Triple(258, 3, 32L)
        tags += Triple(259, 3, 1L); tags += Triple(277, 3, 1L); tags += Triple(317, 3, 1L)
        if (strips) {
            tags += Triple(273, 4, listOf(2L, offsetsAt)); tags += Triple(278, 3, 1L); tags += Triple(279, 4, listOf(2L, countsAt))
        } else {
            tags += Triple(322, 3, 2L); tags += Triple(323, 3, 2L)
            tags += Triple(324, 4, listOf(2L, offsetsAt)); tags += Triple(325, 4, listOf(2L, countsAt))
        }
        tags += Triple(339, 3, if (intScale != null) 2L else 3L)
        tags += Triple(33550, 12, listOf(3L, scaleAt)); tags += Triple(33922, 12, listOf(6L, tieAt))
        tags += Triple(34735, 3, listOf(8L, keysAt))
        tags += Triple(284, 3, 1L)
        if (noDataAt != null) tags += Triple(42113, 2, listOf((noData.length + 1).toLong(), noDataAt))
        if (metadataAt != null) tags += Triple(42112, 2, listOf(metadata!!.length.toLong(), metadataAt))
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
    fun readsScaledIntegerSamples() {
        // T69: PROJ's CGG2013a grid is int32 with SCALE 0.001 in GDAL's metadata.
        val tiff = GeoTiff.open(source(buildTiff(pixelIsPoint = false, noData = null, intScale = 0.001)))
        val tile = tiff.readTile(0, 0)
        for ((got, want) in tile.zip(listOf(1f, 2f, 4f, 5f))) assertEquals(want, got, 1e-6f)
        assertEquals(6f, tiff.readTile(1, 0)[2], 1e-6f)
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

    /**
     * BigTIFF (magic 43): a 4x2 image in 2x2 tiles with one 2x1 overview IFD, 8-byte offsets (LONG8
     * tile offsets), 20-byte entries, the georeference out of line and the GeoKeys inline-free.
     */
    private fun buildBigTiff(): ByteArray {
        val t = TiffBuilder()
        fun u64(b: TiffBuilder, v: Long) { b.u32(v and 0xFFFFFFFFL); b.u32(v ushr 32) }
        // Layout: header (16) | data blobs | IFD 0 | IFD 1.
        val blobs = TiffBuilder()
        var cursor = 16L
        fun place(bytes: ByteArray): Long { val at = cursor; blobs.bytes(bytes); cursor += bytes.size; return at }
        val main = listOf(floatArrayOf(1f, 2f, 5f, 6f), floatArrayOf(3f, 4f, 7f, 8f))
        val mainAt = main.map { place(floatBytesLE(it)) }
        val ovAt = place(floatBytesLE(floatArrayOf(3.5f, 5.5f, 0f, 0f)))
        val mainOffsets = place(TiffBuilder().apply { mainAt.forEach { u64(this, it) } }.toByteArray())
        val mainCounts = place(TiffBuilder().apply { repeat(2) { u64(this, 16) } }.toByteArray())
        val scaleAt = place(TiffBuilder().apply { f64(10.0); f64(10.0); f64(0.0) }.toByteArray())
        val tieAt = place(TiffBuilder().apply { f64(0.0); f64(0.0); f64(0.0); f64(4_500_000.0); f64(2_700_000.0); f64(0.0) }.toByteArray())
        val keysAt = place(TiffBuilder().apply { listOf(1, 1, 0, 2, 1025, 0, 1, 1, 3072, 0, 1, 3035).forEach { u16(it) } }.toByteArray())

        class E(val tag: Int, val type: Int, val count: Long, val value: Long)
        fun ifd(entries: List<E>, at: Long, next: Long): ByteArray = TiffBuilder().apply {
            u64(this, entries.size.toLong())
            for (e in entries.sortedBy { it.tag }) {
                u16(e.tag); u16(e.type); u64(this, e.count)
                if (e.type == 3 && e.count == 1L) { u16(e.value.toInt()); u16(0); u32(0) } else u64(this, e.value)
            }
            u64(this, next)
        }.toByteArray().also { check(at >= 0) }
        fun common(w: Long, h: Long) = listOf(
            E(256, 3, 1, w), E(257, 3, 1, h), E(258, 3, 1, 32), E(259, 3, 1, 1), E(277, 3, 1, 1),
            E(322, 3, 1, 2), E(323, 3, 1, 2), E(339, 3, 1, 3),
        )
        val ifd0Entries = common(4, 2) + listOf(
            E(324, 16, 2, mainOffsets), E(325, 16, 2, mainCounts),
            E(33550, 12, 3, scaleAt), E(33922, 12, 6, tieAt), E(34735, 3, 12, keysAt),
        )
        // Overview: one tile; its single offset and count fit inline (LONG8, 8 bytes).
        val ifd1Entries = common(2, 1) + listOf(E(254, 4, 1, 1), E(324, 16, 1, ovAt), E(325, 16, 1, 16))
        val ifd0At = cursor
        val ifd0Size = 8 + ifd0Entries.size * 20 + 8
        val ifd1At = ifd0At + ifd0Size
        t.bytes("II".encodeToByteArray()); t.u16(43); t.u16(8); t.u16(0); u64(t, ifd0At)
        t.bytes(blobs.toByteArray())
        t.bytes(ifd(ifd0Entries, ifd0At, ifd1At))
        t.bytes(ifd(ifd1Entries, ifd1At, 0))
        return t.toByteArray()
    }

    @Test
    fun readsBigTiffWithOverviews() {
        val tiff = GeoTiff.open(source(buildBigTiff()))
        assertEquals(4, tiff.width)
        assertEquals(2, tiff.height)
        assertEquals(3035, tiff.epsg)
        assertEquals(listOf(1f, 2f, 5f, 6f), tiff.readTile(0, 0).toList())
        assertEquals(listOf(3f, 4f, 7f, 8f), tiff.readTile(1, 0).toList())
        assertEquals(4_500_005.0, tiff.xOf(0.0), 1e-9)
        assertEquals(1, tiff.overviews.size)
        val ov = tiff.overviews.single()
        assertEquals(2, ov.width)
        assertEquals(20.0, ov.pixelScaleX, 1e-12)
        assertEquals(listOf(3.5f, 5.5f), ov.readTile(0, 0).toList().take(2))
    }
}
