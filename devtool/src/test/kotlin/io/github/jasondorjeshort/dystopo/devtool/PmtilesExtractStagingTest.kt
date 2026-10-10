package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktError
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.MemoryTileStaging
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesExtract
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileStaging
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PmtilesExtractStagingTest {
    private val dir: File = Files.createTempDirectory("extract-staging-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private class Bytes(val data: ByteArray) : ByteRangeSource {
        override fun read(offset: Long, length: Int): ByteArray =
            data.copyOfRange(offset.toInt(), minOf(data.size.toLong(), offset + length).toInt())
    }

    private class CountingStaging(private val inner: TileStaging) : TileStaging {
        var appends = 0
        var appendedBytes = 0L
        override fun append(bytes: ByteArray): Long { appends++; appendedBytes += bytes.size; return inner.append(bytes) }
        override fun read(position: Long, length: Int): ByteArray = inner.read(position, length)
    }

    private val box = BoundingBox(5.0, 45.0, 7.5, 46.6)

    /**
     * z5..11 over [box]. A third of the tiles share one "ocean" content (so the archive holds one
     * blob referenced by many entries, some as run lengths); the rest are distinct.
     */
    private fun archive(): ByteArray {
        val writer = PmtilesWriter(TileType.PNG)
        val ocean = ByteArray(300) { 7 }
        for (z in 5..11) for (t in WebMercator.tilesCovering(box, z)) {
            val content = if ((t.x + t.y) % 3 == 0) ocean else ByteArray(200 + (t.x * 31 + t.y) % 97) { i -> (i * 13 + t.x * 7 + t.y * 3 + z).toByte() }
            writer.addTile(t.z, t.x, t.y, content)
        }
        val out = ByteArrayOutputStream()
        writer.finish(ByteSink { out.write(it) })
        return out.toByteArray()
    }

    private fun extract(input: ByteArray, sub: BoundingBox, staging: TileStaging): Pair<ByteArray, io.github.jasondorjeshort.dystopo.pmtiles.ExtractResult> {
        val out = ByteArrayOutputStream()
        val result = PmtilesExtract.extract(Bytes(input), sub, ByteSink { out.write(it) }, staging = staging)
        return out.toByteArray() to result
    }

    @Test
    fun aTempFileStagingGivesTheSameArchiveAndStagesEachBlobOnce() {
        val input = archive()
        val sub = BoundingBox(5.6, 45.4, 6.9, 46.2)
        val (fromMemory, memResult) = extract(input, sub, MemoryTileStaging())

        val stagingFile = File(dir, "x.pmtiles.staging")
        val fileStaging = FileTileStaging(stagingFile)
        val counting = CountingStaging(fileStaging)
        val (fromFile, fileResult) = try {
            extract(input, sub, counting).also {
                // Everything kept went through the temp file.
                assertEquals(counting.appendedBytes, stagingFile.length())
            }
        } finally {
            fileStaging.close()
        }
        assertFalse(stagingFile.exists(), "staging file removed on close")

        assertContentEquals(fromMemory, fromFile, "staging must not change the output")
        assertEquals(memResult.tilesKept, fileResult.tilesKept)
        assertTrue(fileResult.tilesKept > 100, "kept ${fileResult.tilesKept}")
        val header = PmtilesReader.open(Bytes(fromFile)).header
        // One staged copy per distinct content, and each distinct blob read from the input once,
        // although many kept tiles point at the shared ocean blob.
        assertEquals(header.tileContents.toInt(), counting.appends)
        assertEquals(counting.appends, fileResult.tileReads)
        assertTrue(fileResult.tilesKept > fileResult.tileReads + 20, "dedup exercised: ${fileResult.tilesKept} tiles, ${fileResult.tileReads} reads")
    }

    @Test
    fun archiveErrorsBecomeOneLine() {
        val e = assertFailsWith<CliktError> {
            archiveErrorsAsOneLine("info", "x.pmtiles") { throw java.util.zip.ZipException("invalid bit length repeat\nmore") }
        }
        assertEquals("pmtiles info failed on x.pmtiles: ZipException: invalid bit length repeat", e.message)
        assertEquals(1, e.statusCode)
    }
}
