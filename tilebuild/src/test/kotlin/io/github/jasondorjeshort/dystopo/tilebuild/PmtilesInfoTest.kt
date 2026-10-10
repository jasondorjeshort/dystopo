package io.github.jasondorjeshort.dystopo.tilebuild

import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PmtilesInfoTest {
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { bos ->
        GZIPOutputStream(bos).use { it.write(text.toByteArray()) }
    }.toByteArray()

    /** A synthetic v3 archive: header, then metadata right after it, nothing else. */
    private fun syntheticArchive(metadata: ByteArray, internalCompression: Int): ByteArray {
        val header = ByteBuffer.allocate(PmtilesInfo.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PMTiles".toByteArray(Charsets.US_ASCII))
        header.put(7, 3)
        header.putLong(8, 127L + metadata.size) // root dir offset
        header.putLong(16, 11)                 // root dir length
        header.putLong(24, 127)                // metadata offset
        header.putLong(32, metadata.size.toLong())
        header.putLong(40, 0); header.putLong(48, 0)
        header.putLong(56, 138); header.putLong(64, 4242)
        header.putLong(72, 1234)               // addressed tiles
        header.putLong(80, 1200)               // tile entries
        header.putLong(88, 1100)               // tile contents
        header.put(96, 1)                      // clustered
        header.put(97, internalCompression.toByte())
        header.put(98, 2)                      // tile compression gzip
        header.put(99, 1)                      // mvt
        header.put(100, 0); header.put(101, 14)
        header.putInt(102, (-121.86e7).toInt()); header.putInt(106, (46.78e7).toInt())
        header.putInt(110, (-121.66e7).toInt()); header.putInt(114, (46.93e7).toInt())
        header.put(118, 10)
        header.putInt(119, (-121.76e7).toInt()); header.putInt(123, (46.855e7).toInt())
        return header.array() + metadata
    }

    @Test
    fun parsesHeaderFields() {
        val info = PmtilesInfo.parseHeader(syntheticArchive(ByteArray(0), 2))
        assertEquals(3, info.version)
        assertEquals(1234, info.addressedTiles)
        assertEquals(1200, info.tileEntries)
        assertEquals(1100, info.tileContents)
        assertTrue(info.clustered)
        assertEquals("gzip", info.internalCompression)
        assertEquals("gzip", info.tileCompression)
        assertEquals("mvt", info.tileType)
        assertEquals(0, info.minZoom)
        assertEquals(14, info.maxZoom)
        assertEquals(listOf(-121.86, 46.78, -121.66, 46.93), info.bounds.map { Math.round(it * 1e4) / 1e4 })
        assertEquals(10, info.centerZoom)
        assertEquals(4242, info.tileDataBytes)
    }

    @Test
    fun readsGzippedMetadataAndLayerNames() {
        val metadata = """{"name":"test","attribution":"${Attribution.TEXT}",
            "vector_layers":[{"id":"water","fields":{}},{"id":"transportation","fields":{}}]}"""
        val file = File.createTempFile("pmtiles-info", ".pmtiles").apply { deleteOnExit() }
        file.writeBytes(syntheticArchive(gzip(metadata), 2))
        val info = PmtilesInfo.read(file)
        assertEquals(Attribution.TEXT, info.attribution)
        assertEquals(listOf("water", "transportation"), info.vectorLayers)
        val meta = assertNotNull(info.metadata)
        assertEquals("test", meta["name"]?.jsonPrimitive?.content)
        assertTrue("vector_layers" !in meta.keys, "long layer list is replaced by names")
    }

    @Test
    fun readsMbtilesStyleJsonString() {
        val metadata = """{"json":"{\"vector_layers\":[{\"id\":\"place\"}]}"}"""
        val file = File.createTempFile("pmtiles-info", ".pmtiles").apply { deleteOnExit() }
        file.writeBytes(syntheticArchive(metadata.toByteArray(), 1))
        val info = PmtilesInfo.read(file)
        assertEquals(listOf("place"), info.vectorLayers)
    }

    @Test
    fun rejectsOtherFiles() {
        assertFailsWith<IllegalArgumentException> { PmtilesInfo.parseHeader(ByteArray(127)) }
        assertFailsWith<IllegalArgumentException> { PmtilesInfo.parseHeader(ByteArray(10)) }
    }
}
