package io.github.jasondorjeshort.dystopo.pmtiles

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.dem.TileWindow
import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmtilesExtractTest {
    private class MemoryArchive : ByteSink, ByteRangeSource {
        private val chunks = ArrayList<ByteArray>()
        var reads = 0
        val bytes: ByteArray by lazy {
            val out = ByteArray(chunks.sumOf { it.size })
            var p = 0
            for (c in chunks) { c.copyInto(out, p); p += c.size }
            out
        }

        override fun write(bytes: ByteArray) { chunks += bytes }

        override fun read(offset: Long, length: Int): ByteArray {
            reads++
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            return bytes.copyOfRange(offset.toInt(), end)
        }
    }

    private val tileSize = 16

    /** Smooth synthetic terrain in global pixel coordinates; 0 m ("ocean") west of 5.5 degrees east. */
    private fun demTile(t: TileId): ElevationGrid {
        val w = TileWindow(t, halo = 0, tileSize = tileSize).window
        val values = FloatArray(tileSize * tileSize) { i ->
            val lon = w.lonAt(i % tileSize)
            val lat = w.latAt(i / tileSize)
            if (lon < 5.5) 0f else (1200 + 900 * sin(lon * 7.3) * sin(lat * 5.1) + 0.37 * (i % 7)).toFloat()
        }
        return ElevationGrid(tileSize, tileSize, w.cellSizeMeters, values)
    }

    /** Terrarium pixels as raw RGB bytes: the archive does not care that this is not a PNG. */
    private fun encodeTile(grid: ElevationGrid): ByteArray {
        val argb = TileWindows.encodeTerrarium(grid)
        return ByteArray(argb.size * 3) { i -> (argb[i / 3] shr (16 - 8 * (i % 3))).toByte() }
    }

    private fun decodeTile(bytes: ByteArray, cellSize: Double): ElevationGrid {
        val argb = IntArray(bytes.size / 3) { p ->
            (0xFF shl 24) or ((bytes[3 * p].toInt() and 0xFF) shl 16) or ((bytes[3 * p + 1].toInt() and 0xFF) shl 8) or (bytes[3 * p + 2].toInt() and 0xFF)
        }
        val side = kotlin.math.sqrt(argb.size.toDouble()).toInt()
        return Terrarium.decode(argb, side, side, cellSize)
    }

    private val region = BoundingBox(5.0, 45.0, 7.5, 46.6)

    private class Built(val archive: MemoryArchive, val tiles: Map<TileId, ByteArray>, val grids: Map<TileId, ElevationGrid>)

    private fun buildDemArchive(
        box: BoundingBox = region, zooms: IntRange = 5..10, leaves: Boolean = false,
        internal: Compression = Compression.NONE, compress: Compress? = null, metadata: String = """{"name":"synthetic-dem"}""",
    ): Built {
        val writer = if (leaves) {
            PmtilesWriter(TileType.PNG, internalCompression = internal, compress = compress, maxHeadBytes = 300, initialLeafSize = 8)
        } else PmtilesWriter(TileType.PNG, internalCompression = internal, compress = compress)
        val tiles = LinkedHashMap<TileId, ByteArray>()
        val grids = LinkedHashMap<TileId, ElevationGrid>()
        for (z in zooms) for (t in WebMercator.tilesCovering(box, z)) {
            val g = demTile(t)
            val b = encodeTile(g)
            tiles[t] = b
            grids[t] = g
            writer.addTile(t.z, t.x, t.y, b)
        }
        val archive = MemoryArchive()
        writer.finish(archive, metadata, box.west, box.south, box.east, box.north, centerZoom = 8)
        return Built(archive, tiles, grids)
    }

    @Test
    fun demTilesReadBackToTheSameGrids() {
        val built = buildDemArchive()
        val reader = PmtilesReader.open(built.archive)
        assertEquals(TileType.PNG, reader.header.tileType)
        assertEquals(Compression.NONE, reader.header.tileCompression)
        assertEquals(5, reader.header.minZoom)
        assertEquals(10, reader.header.maxZoom)
        assertEquals(built.tiles.size.toLong(), reader.header.addressedTiles)
        // Ocean tiles are identical, so they are stored once.
        assertTrue(reader.header.tileContents < reader.header.addressedTiles, "duplicate ocean tiles deduplicated")
        var maxErr = 0f
        for ((t, grid) in built.grids) {
            val back = decodeTile(assertNotNull(reader.tile(t.z, t.x, t.y)), grid.cellSizeMeters)
            val direct = decodeTile(built.tiles.getValue(t), grid.cellSizeMeters)
            assertContentEquals(direct.values, back.values, "tile $t decodes to the same grid as the bytes written")
            for (i in grid.values.indices) maxErr = maxOf(maxErr, abs(back.values[i] - grid.values[i]))
        }
        assertTrue(maxErr <= 1f / 256, "Terrarium precision: max error $maxErr")
    }

    @Test
    fun extractKeepsExactlyTheTilesInsideTheBox() {
        val built = buildDemArchive(leaves = true)
        val inReader = PmtilesReader.open(built.archive)
        assertTrue(inReader.rootDirectory.any { it.isLeaf }, "input uses leaf directories")

        val sub = BoundingBox(6.1, 45.6, 6.6, 46.0)
        val out = MemoryArchive()
        val result = PmtilesExtract.extract(built.archive, sub, out, maxZoom = 9)
        val reader = PmtilesReader.open(out)

        val expected = (5..9).flatMap { WebMercator.tilesCovering(sub, it) }.toSet()
        val got = reader.tileEntries().flatMap { e -> (0 until e.runLength).map { TileIds.toZxy(e.tileId + it) } }.toSet()
        assertEquals(expected, got)
        assertEquals(expected.size, result.tilesKept)
        assertEquals(expected.size.toLong(), reader.header.addressedTiles)
        for (t in expected) assertContentEquals(built.tiles.getValue(t), reader.tile(t.z, t.x, t.y), "tile $t copied byte for byte")
        assertNull(reader.tile(10, 0, 0))

        assertEquals(5, reader.header.minZoom)
        assertEquals(9, reader.header.maxZoom)
        assertEquals(8, reader.header.centerZoom)
        assertEquals(TileType.PNG, reader.header.tileType)
        assertEquals(sub.west, reader.header.minLon, 1e-7)
        assertEquals(sub.north, reader.header.maxLat, 1e-7)
        assertEquals("""{"name":"synthetic-dem"}""", reader.metadataJson())
        assertEquals((5..9).associateWith { WebMercator.tilesCovering(sub, it).size }, result.tilesByZoom)
        // z10 leaves (and leaves for distant parts of each zoom) are never fetched.
        assertTrue(result.leafDirectoriesSkipped > 0, "skipped ${result.leafDirectoriesSkipped} leaves")
        assertTrue(result.leafDirectoriesRead < inReader.rootDirectory.count { it.isLeaf })
    }

    @Test
    fun extractSplitsRunsOfIdenticalTiles() {
        // All ocean: one blob, long run-length entries that only partly overlap the box.
        val ocean = BoundingBox(-4.0, 44.0, -1.0, 47.0)
        val built = buildDemArchive(box = ocean, zooms = 6..9)
        val inReader = PmtilesReader.open(built.archive)
        assertEquals(1L, inReader.header.tileContents)

        val sub = BoundingBox(-3.0, 45.0, -2.6, 45.3)
        val out = MemoryArchive()
        val result = PmtilesExtract.extract(built.archive, sub, out, minZoom = 7)
        val reader = PmtilesReader.open(out)
        val expected = (7..9).flatMap { WebMercator.tilesCovering(sub, it) }
        assertEquals(expected.size.toLong(), reader.header.addressedTiles)
        assertEquals(1L, reader.header.tileContents)
        assertEquals(1, result.tileReads, "the shared blob is read once")
        for (t in expected) assertContentEquals(built.tiles.getValue(t), reader.tile(t.z, t.x, t.y))
        assertEquals(7, reader.header.minZoom)
    }

    @Test
    fun extractClampsZoomsAndRejectsEmptyResults() {
        val built = buildDemArchive(zooms = 6..8)
        val out = MemoryArchive()
        val r = PmtilesExtract.extract(built.archive, BoundingBox(6.0, 45.5, 6.5, 46.0), out, minZoom = 0, maxZoom = 20)
        assertEquals(6, r.minZoom)
        assertEquals(8, r.maxZoom)
        assertFailsWith<IllegalArgumentException> {
            PmtilesExtract.extract(built.archive, BoundingBox(-120.0, 40.0, -119.0, 41.0), MemoryArchive())
        }
        assertFailsWith<IllegalArgumentException> {
            PmtilesExtract.extract(built.archive, BoundingBox(6.0, 45.5, 6.5, 46.0), MemoryArchive(), minZoom = 9)
        }
    }

    /**
     * A hand-laid archive that does not deduplicate: z1 tile 0 at offset 0, and tiles 1..3 sharing a
     * second copy of the same bytes at offset 4. Not something our writer produces, but valid PMTiles.
     */
    private fun duplicateContentArchive(): MemoryArchive {
        val blob = byteArrayOf(1, 2, 3, 4)
        val root = Directory.encode(listOf(Entry(1, 0, 4, 1), Entry(2, 4, 4, 3)))
        val metadata = "{}".encodeToByteArray()
        val rootOffset = PmtilesHeader.SIZE.toLong()
        val metaOffset = rootOffset + root.size
        val dataOffset = metaOffset + metadata.size
        val header = PmtilesHeader(
            rootDirectoryOffset = rootOffset, rootDirectoryLength = root.size.toLong(),
            metadataOffset = metaOffset, metadataLength = metadata.size.toLong(),
            leafDirectoriesOffset = dataOffset, leafDirectoriesLength = 0,
            tileDataOffset = dataOffset, tileDataLength = 8,
            addressedTiles = 4, tileEntries = 2, tileContents = 2, clustered = true,
            internalCompression = Compression.NONE, tileCompression = Compression.NONE, tileType = TileType.PNG,
            minZoom = 1, maxZoom = 1, minLon = -180.0, minLat = -85.0, maxLon = 180.0, maxLat = 85.0,
            centerZoom = 1, centerLon = 0.0, centerLat = 0.0,
        )
        return MemoryArchive().apply {
            write(header.encode()); write(root); write(metadata); write(blob); write(blob.copyOf())
        }
    }

    @Test
    fun identicalContentAtTwoOffsetsIsReadOncePerOffset() {
        val input = duplicateContentArchive()
        val out = MemoryArchive()
        val result = PmtilesExtract.extract(input, BoundingBox(-179.0, -80.0, 179.0, 80.0), out)
        assertEquals(4, result.tilesKept)
        // Offset 0 once, offset 4 once; the reuses of offset 4 come from staging, not the source.
        assertEquals(2, result.tileReads)
        assertEquals(8L, result.tileBytesRead)
        val reader = PmtilesReader.open(out)
        assertEquals(1L, reader.header.tileContents, "the writer stores the content once")
        for (y in 0..1) for (x in 0..1) assertContentEquals(byteArrayOf(1, 2, 3, 4), reader.tile(1, x, y))
    }

    @Test
    fun extractReplacesMetadataAndKeepsInternalCompression() {
        val toyCompress: Compress = { input -> ByteArray(input.size + 1) { i -> if (i == 0) 0x5A else (input[i - 1].toInt() xor 0x55).toByte() } }
        val toyDecompress: Decompress = { input -> require(input[0].toInt() == 0x5A); ByteArray(input.size - 1) { (input[it + 1].toInt() xor 0x55).toByte() } }
        val decompressors = mapOf(Compression.GZIP to toyDecompress)
        val built = buildDemArchive(zooms = 6..8, internal = Compression.GZIP, compress = toyCompress)

        val out = MemoryArchive()
        PmtilesExtract.extract(
            built.archive, BoundingBox(6.0, 45.5, 6.5, 46.0), out,
            decompressors = decompressors, compress = toyCompress, metadataJson = """{"name":"cut"}""",
        )
        val reader = PmtilesReader.open(out, decompressors)
        assertEquals(Compression.GZIP, reader.header.internalCompression)
        assertEquals("""{"name":"cut"}""", reader.metadataJson())

        // Without a compressor the extract falls back to uncompressed directories.
        val plain = MemoryArchive()
        PmtilesExtract.extract(built.archive, BoundingBox(6.0, 45.5, 6.5, 46.0), plain, decompressors = decompressors)
        val plainReader = PmtilesReader.open(plain)
        assertEquals(Compression.NONE, plainReader.header.internalCompression)
        assertEquals("""{"name":"synthetic-dem"}""", plainReader.metadataJson())
    }
}
