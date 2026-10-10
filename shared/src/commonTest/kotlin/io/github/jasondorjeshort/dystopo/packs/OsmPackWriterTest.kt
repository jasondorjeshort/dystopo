package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsmPackWriterTest {
    private class Archive : ByteSink {
        private val chunks = ArrayList<ByteArray>()
        override fun write(bytes: ByteArray) { chunks += bytes }
        fun bytes(): ByteArray {
            val out = ByteArray(chunks.sumOf { it.size })
            var p = 0
            for (c in chunks) { c.copyInto(out, p); p += c.size }
            return out
        }
    }

    private fun source(bytes: ByteArray) = ByteRangeSource { offset, length ->
        bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), offset + length).toInt())
    }

    /** Stand-in for gzip in common code: the gzip magic, then the raw bytes (enough for "was it compressed once"). */
    private var compressions = 0
    private val fakeGzip: (ByteArray) -> ByteArray = { raw -> compressions++; byteArrayOf(0x1f, 0x8b.toByte()) + raw }

    private val layers = MiniJson.parseObject(
        """{"tiles":["https://tiles.example/planet/b1/{z}/{x}/{y}.pbf"],"vector_layers":[
           {"id":"water","fields":{"class":"String"},"minzoom":0,"maxzoom":14},
           {"id":"transportation","fields":{"class":"String"},"minzoom":4,"maxzoom":14},
           {"id":"mountain_peak","fields":{"name":"String","ele":"Number"},"minzoom":7,"maxzoom":14}]}""",
    )

    private fun meta() = OsmPackMetadata(
        name = "mont-blanc-outdoors",
        bounds = listOf(6.78, 45.8, 6.95, 45.93),
        minZoom = 8, maxZoom = 9,
        vectorLayers = OsmPackWriter.vectorLayers(layers),
        attribution = "© OpenStreetMap contributors",
        attributionHtml = "<a href=\"https://www.openstreetmap.org/copyright\">&copy; OpenStreetMap contributors</a>",
        provenance = listOf("osm", "openmaptiles", "openfreemap"),
        license = "ODbL-1.0",
        sourceBuild = "b1",
    )

    @Test
    fun writesGzipMvtWithOpenMapTilesMetadata() {
        val w = OsmPackWriter(fakeGzip)
        val raw = byteArrayOf(0x1a, 1, 2, 3)
        val gz = byteArrayOf(0x1f, 0x8b.toByte(), 9, 9)
        assertTrue(w.add(8, 133, 90, raw))
        assertTrue(w.add(9, 266, 180, gz)) // already gzipped: stored as is
        assertTrue(w.add(9, 267, 180, raw)) // same content as z8: stored once
        assertFalse(w.add(9, 266, 181, ByteArray(0))) // ocean: left out
        assertEquals(3, w.tiles)
        assertEquals(1, w.emptyTiles)
        assertEquals(2, compressions)

        val a = Archive()
        val result = w.finish(a, meta())
        val bytes = a.bytes()
        val reader = PmtilesReader.open(source(bytes), mapOf(Compression.GZIP to { b: ByteArray -> b.copyOfRange(2, b.size) }))
        val h = reader.header
        assertEquals(TileType.MVT, h.tileType)
        assertEquals(Compression.GZIP, h.tileCompression)
        assertEquals(8, h.minZoom)
        assertEquals(9, h.maxZoom)
        assertEquals(6.78, h.minLon, 1e-7)
        assertEquals(45.93, h.maxLat, 1e-7)
        assertEquals(3L, h.addressedTiles)
        assertEquals(2L, result.header.tileContents)
        assertContentEquals(raw, reader.tile(8, 133, 90)) // stored gzipped, read back through the decompressor
        assertContentEquals(byteArrayOf(9, 9), reader.tile(9, 266, 180))
        assertNull(reader.tile(9, 266, 181))

        val m = reader.metadata()
        assertEquals("mont-blanc-outdoors", m["name"])
        assertEquals("6.78,45.8,6.95,45.93", m["bounds"])
        assertEquals("pbf", m["format"])
        assertEquals(8L, m["minzoom"])
        assertEquals(OsmPackWriter.PROFILE_OPENMAPTILES, m["dystopo:profile"])
        assertTrue((m["dystopo:outdoors_layers"] as String).startsWith("absent"))
        assertEquals("b1", m["dystopo:source_build"])
        val ids = (m["vector_layers"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertEquals(listOf("water", "transportation", "mountain_peak"), ids)
        // The app's credits read the registry ids from the same key tilebuild writes.
        assertEquals(listOf("osm", "openmaptiles", "openfreemap"), PackAttribution.registryIds(m))

        // And the pack reads as a vector pack with the right box.
        val info = PackArchiveInfo.read("mont-blanc-outdoors.pmtiles", bytes.size.toLong(), reader)
        assertEquals("mvt", info.tileType)
        assertEquals("© OpenStreetMap contributors", info.attribution)
        assertEquals(PackRole.VECTOR, PackNames.parse("mont-blanc-outdoors.pmtiles")?.role)
    }

    @Test
    fun refusesAnEmptyPack() {
        val w = OsmPackWriter(fakeGzip)
        w.add(8, 1, 1, ByteArray(0))
        assertFailsWith<IllegalArgumentException> { w.finish(Archive(), meta()) }
    }

    @Test
    fun vectorLayersMissingIsEmpty() {
        assertEquals(emptyList(), OsmPackWriter.vectorLayers(mapOf("tiles" to listOf("x"))))
    }
}
