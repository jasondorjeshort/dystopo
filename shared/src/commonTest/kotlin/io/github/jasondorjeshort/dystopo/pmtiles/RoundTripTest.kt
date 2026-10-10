package io.github.jasondorjeshort.dystopo.pmtiles

import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundTripTest {
    /** Collects the writer's output and serves it back with read counters, like a file or HTTP would. */
    private class MemoryArchive : ByteSink, ByteRangeSource {
        private val chunks = ArrayList<ByteArray>()
        var reads = 0
        var bytesRead = 0L
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
            bytesRead += end - offset
            return bytes.copyOfRange(offset.toInt(), end)
        }
    }

    /** A stand-in for gzip that is visibly different from its input, so we know it was applied. */
    private val toyCompress: Compress = { input -> ByteArray(input.size + 1) { i -> if (i == 0) 0x5A else (input[i - 1].toInt() xor 0x55).toByte() } }
    private val toyDecompress: Decompress = { input -> require(input[0].toInt() == 0x5A); ByteArray(input.size - 1) { (input[it + 1].toInt() xor 0x55).toByte() } }
    private val decompressors = mapOf(Compression.GZIP to toyDecompress)

    /** Distinct per tile (the address is in the first bytes), random length, filler after. */
    private fun syntheticTile(z: Int, x: Int, y: Int, rnd: Random, maxLen: Int = 64): ByteArray =
        ByteArray(rnd.nextInt(5, maxLen)) { i ->
            when (i) {
                0 -> z.toByte(); 1 -> x.toByte(); 2 -> (x shr 8).toByte(); 3 -> y.toByte(); 4 -> (y shr 8).toByte()
                else -> (z * 31 + x * 17 + y * 7 + i).toByte()
            }
        }

    @Test
    fun thousandsOfTilesRoundTripWithARootOnlyDirectory() {
        val rnd = Random(1)
        // Uncompressed directories need about 4 bytes per entry, so ~2400 entries fit the 16 KiB root.
        val writer = PmtilesWriter(TileType.PNG)
        val expected = HashMap<TileId, ByteArray>()
        val coords = ArrayList<TileId>()
        for (z in 0..5) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) coords += TileId(z, x, y)
        for (x in 0 until 16) for (y in 0 until 64) coords += TileId(6, x, y)
        coords.shuffle(rnd) // insertion order must not matter
        for (c in coords) {
            val bytes = syntheticTile(c.z, c.x, c.y, rnd)
            expected[c] = bytes
            writer.addTile(c.z, c.x, c.y, bytes)
        }
        assertEquals(2389, writer.tileCount)

        val archive = MemoryArchive()
        val metadata = """{"name":"synthetic","attribution":"none","vector_layers":[]}"""
        val result = writer.finish(archive, metadata, minLon = -122.0, minLat = 46.5, maxLon = -121.0, maxLat = 47.0, centerZoom = 5)
        assertEquals(0, result.leafDirectories)
        assertTrue(result.rootDirectoryCompressedBytes + PmtilesHeader.SIZE <= PmtilesReader.HEAD_BYTES)
        assertEquals(archive.bytes.size.toLong(), result.totalBytes)

        val reader = PmtilesReader.open(archive)
        assertEquals(1, archive.reads) // header and root directory come from the first 16 KiB
        val h = reader.header
        assertEquals(2389L, h.addressedTiles)
        assertEquals(2389L, h.tileEntries)
        assertEquals(2389L, h.tileContents)
        assertEquals(0, h.minZoom)
        assertEquals(6, h.maxZoom)
        assertEquals(5, h.centerZoom)
        assertEquals(TileType.PNG, h.tileType)
        assertEquals(Compression.NONE, h.internalCompression)
        assertEquals(Compression.NONE, h.tileCompression)
        assertTrue(h.clustered)
        assertEquals(-122.0, h.minLon, 1e-7)
        assertEquals(-121.5, h.centerLon, 1e-7)
        assertEquals(46.75, h.centerLat, 1e-7)
        assertEquals("synthetic", reader.metadata()["name"])
        assertEquals(metadata, reader.metadataJson())

        for ((c, bytes) in expected) assertContentEquals(bytes, reader.tile(c.z, c.x, c.y), "tile $c")
        assertEquals(0, reader.leafReads)
        assertNull(reader.tile(7, 0, 0))
        assertNull(reader.tile(12, 100, 100))

        // Clustered: offsets never decrease along the tile-ID line; the data section is exactly the blobs.
        val entries = reader.tileEntries().toList()
        assertEquals(h.tileEntries, entries.size.toLong())
        assertTrue(entries.zipWithNext().all { (a, b) -> a.tileId < b.tileId && a.offset <= b.offset })
        assertEquals(h.tileDataLength, entries.sumOf { it.length.toLong() })
    }

    @Test
    fun largeArchiveNeedsLeafDirectoriesAndStillReadsEveryTile() {
        val rnd = Random(2)
        // 30 000 entries take ~5 bytes each (the toy compressor adds a byte), far beyond the 16 KiB root.
        val writer = PmtilesWriter(
            TileType.MVT, tileCompression = Compression.GZIP, internalCompression = Compression.GZIP,
            compress = toyCompress, initialLeafSize = 1024,
        )
        val expected = HashMap<TileId, ByteArray>()
        val z = 9
        val n = 1 shl z
        while (expected.size < 30_000) {
            val c = TileId(z, rnd.nextInt(n), rnd.nextInt(n))
            if (c in expected) continue
            val bytes = syntheticTile(c.z, c.x, c.y, rnd, maxLen = 16)
            expected[c] = bytes
            writer.addTile(c.z, c.x, c.y, bytes)
        }
        writer.addTile(0, 0, 0, byteArrayOf(1))
        expected[TileId(0, 0, 0)] = byteArrayOf(1)

        val archive = MemoryArchive()
        val result = writer.finish(archive)
        assertTrue(result.leafDirectories > 1, "leaves: ${result.leafDirectories}")
        assertTrue(result.rootDirectoryCompressedBytes + PmtilesHeader.SIZE <= PmtilesReader.HEAD_BYTES)
        assertEquals(result.leafDirectories, (expected.size + result.leafSize - 1) / result.leafSize)

        val reader = PmtilesReader.open(archive, decompressors)
        assertEquals(result.leafDirectories, reader.rootDirectory.size)
        assertTrue(reader.rootDirectory.all { it.isLeaf })
        assertEquals(0, reader.header.minZoom)
        assertEquals(9, reader.header.maxZoom)
        assertEquals(Compression.GZIP, reader.header.tileCompression)

        val readsBefore = archive.reads
        for ((c, bytes) in expected) assertContentEquals(bytes, reader.tileStored(c.z, c.x, c.y), "tile $c")
        assertEquals(result.leafDirectories, reader.leafReads) // each leaf decoded once, then cached
        // One read per tile, plus one per leaf that lies beyond the first 16 KiB.
        val reads = archive.reads - readsBefore
        assertTrue(reads in expected.size..expected.size + result.leafDirectories, "reads: $reads")

        // Absent tiles inside a leaf's range are reported absent, not as a neighbour.
        var misses = 0
        while (misses < 100) {
            val c = TileId(z, rnd.nextInt(n), rnd.nextInt(n))
            if (c in expected) continue
            assertNull(reader.findEntry(c.z, c.x, c.y), "tile $c should be absent")
            misses++
        }
        assertEquals(expected.size.toLong(), reader.tileEntries().sumOf { it.runLength.toLong() })
    }

    @Test
    fun identicalTilesAreStoredOnceAndRunLengthEncoded() {
        val writer = PmtilesWriter(TileType.PNG)
        val ocean = ByteArray(300) { 7 }
        val land = ByteArray(300) { 9 }
        // Zoom 3: 64 tiles; the first 40 along the Hilbert curve are "ocean", then 20 "land", then ocean again.
        for (i in 0 until 64) {
            val c = TileIds.toZxy(TileIds.zoomStart(3) + i)
            writer.addTile(c.z, c.x, c.y, if (i < 40 || i >= 60) ocean else land)
        }
        val archive = MemoryArchive()
        val result = writer.finish(archive)
        val h = result.header
        assertEquals(64L, h.addressedTiles)
        assertEquals(2L, h.tileContents)
        assertEquals(3L, h.tileEntries)
        assertEquals(600L, h.tileDataLength)

        val reader = PmtilesReader.open(archive)
        val entries = reader.tileEntries().toList()
        assertEquals(listOf(40, 20, 4), entries.map { it.runLength })
        assertEquals(entries[0].offset, entries[2].offset)
        for (i in 0 until 64) {
            val c = TileIds.toZxy(TileIds.zoomStart(3) + i)
            assertContentEquals(if (i < 40 || i >= 60) ocean else land, reader.tile(c.z, c.x, c.y))
        }
    }

    @Test
    fun writerRejectsDuplicatesAndEmptyTiles() {
        val writer = PmtilesWriter(TileType.PNG)
        writer.addTile(1, 0, 0, byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { writer.addTile(1, 0, 0, byteArrayOf(2)) }
        assertFailsWith<IllegalArgumentException> { writer.addTile(1, 1, 0, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { PmtilesWriter(TileType.PNG, internalCompression = Compression.GZIP) }
        assertFailsWith<IllegalArgumentException> { PmtilesWriter(TileType.PNG).finish(MemoryArchive()) }
    }

    @Test
    fun readerNeedsADecompressorForCompressedDirectories() {
        val writer = PmtilesWriter(TileType.PNG, internalCompression = Compression.GZIP, compress = toyCompress)
        writer.addTile(0, 0, 0, byteArrayOf(1, 2, 3))
        val archive = MemoryArchive()
        writer.finish(archive)
        assertFailsWith<UnsupportedOperationException> { PmtilesReader.open(archive) }
        assertContentEquals(byteArrayOf(1, 2, 3), PmtilesReader.open(archive, decompressors).tile(0, 0, 0))
    }
}
