package io.github.jasondorjeshort.dystopo.pmtiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncodingTest {
    @Test
    fun varintsRoundTripAndMatchKnownEncodings() {
        val values = listOf(0L, 1L, 127L, 128L, 300L, 16383L, 16384L, Int.MAX_VALUE.toLong(), 1L shl 35, Long.MAX_VALUE, -1L)
        val w = ByteWriter()
        values.forEach { w.varint(it) }
        val r = ByteReader(w.toByteArray())
        values.forEach { assertEquals(it, r.varint()) }
        assertEquals(0, r.remaining)

        assertEquals(listOf<Byte>(0x7F), ByteWriter().apply { varint(127) }.toByteArray().toList())
        assertEquals(listOf<Byte>(-0x80, 0x01), ByteWriter().apply { varint(128) }.toByteArray().toList())
        assertEquals(listOf<Byte>(-0x54, 0x02), ByteWriter().apply { varint(300) }.toByteArray().toList())
        assertEquals(10, ByteWriter().apply { varint(-1) }.size)
    }

    @Test
    fun headerRoundTripsThrough127Bytes() {
        val h = PmtilesHeader(
            rootDirectoryOffset = 127, rootDirectoryLength = 1000, metadataOffset = 1127, metadataLength = 50,
            leafDirectoriesOffset = 1177, leafDirectoriesLength = 0, tileDataOffset = 1177, tileDataLength = 123456789012L,
            addressedTiles = 5461, tileEntries = 5000, tileContents = 4000, clustered = true,
            internalCompression = Compression.GZIP, tileCompression = Compression.NONE, tileType = TileType.PNG,
            minZoom = 0, maxZoom = 12, minLon = -121.9, minLat = 46.7, maxLon = -121.5, maxLat = 47.0,
            centerZoom = 10, centerLon = -121.7, centerLat = 46.85,
        )
        val bytes = h.encode()
        assertEquals(PmtilesHeader.SIZE, bytes.size)
        assertEquals("PMTiles", bytes.copyOfRange(0, 7).decodeToString())
        assertEquals(3, bytes[7].toInt())
        val back = PmtilesHeader.decode(bytes)
        assertEquals(h.copy(minLon = 0.0, minLat = 0.0, maxLon = 0.0, maxLat = 0.0, centerLon = 0.0, centerLat = 0.0),
            back.copy(minLon = 0.0, minLat = 0.0, maxLon = 0.0, maxLat = 0.0, centerLon = 0.0, centerLat = 0.0))
        assertEquals(h.minLon, back.minLon, 1e-7)
        assertEquals(h.maxLat, back.maxLat, 1e-7)
        assertEquals(h.centerLon, back.centerLon, 1e-7)
        assertEquals(h.centerLat, back.centerLat, 1e-7)
    }

    @Test
    fun headerRejectsOtherFiles() {
        assertFailsWith<IllegalArgumentException> { PmtilesHeader.decode(ByteArray(127)) }
        assertFailsWith<IllegalArgumentException> { PmtilesHeader.decode(ByteArray(10)) }
        val v2 = PmtilesHeader.decode(ByteArray(127).also { "PMTiles".encodeToByteArray().copyInto(it); it[7] = 3 }).encode()
        v2[7] = 2
        assertFailsWith<IllegalArgumentException> { PmtilesHeader.decode(v2) }
    }

    @Test
    fun directoryRoundTripsWithRunsAndContiguousOffsets() {
        val entries = listOf(
            Entry(0, 0, 100, 1),
            Entry(1, 100, 200, 3), // run of 3 identical tiles, contiguous with the previous blob
            Entry(4, 100, 200, 1), // shares the blob (offset repeats, not contiguous)
            Entry(5, 300, 50, 1),
            Entry(21, 0, 999, 0), // leaf entry
            Entry(1L shl 40, 1L shl 33, 7, 1),
        )
        val bytes = Directory.encode(entries)
        assertEquals(entries, Directory.decode(bytes))
        // Column layout: count, tile-ID deltas, run lengths, lengths, offsets (0 = contiguous, else offset + 1).
        val small = Directory.encode(entries.take(2))
        assertEquals(listOf(2, 0, 1, 1, 3, 100, 0xC8, 0x01, 1, 0), small.map { it.toInt() and 0xFF })
    }

    @Test
    fun directoryFindHonoursRunsAndLeafRanges() {
        val entries = listOf(Entry(0, 0, 10, 1), Entry(5, 10, 10, 3), Entry(21, 0, 50, 0), Entry(85, 20, 10, 1))
        assertEquals(entries[0], Directory.find(entries, 0))
        assertNull(Directory.find(entries, 1))
        assertEquals(entries[1], Directory.find(entries, 5))
        assertEquals(entries[1], Directory.find(entries, 7))
        assertNull(Directory.find(entries, 8))
        assertEquals(entries[2], Directory.find(entries, 21))
        assertEquals(entries[2], Directory.find(entries, 84)) // leaf covers up to the next entry
        assertEquals(entries[3], Directory.find(entries, 85))
        assertNull(Directory.find(entries, 86))
        assertNull(Directory.find(emptyList(), 3))
    }

    @Test
    fun directoryRejectsUnsortedEntries() {
        assertFailsWith<IllegalArgumentException> { Directory.encode(listOf(Entry(5, 0, 1, 1), Entry(4, 1, 1, 1))) }
    }

    @Test
    fun miniJsonParsesAndEncodes() {
        val text = """{"name":"Test \"tiles\"","version":3,"scale":1.5,"neg":-2e3,"ok":true,"none":null,
            |"layers":[{"id":"water","fields":{"kind":"String"}},{"id":"roads"}],"empty":{},"list":[],"uni":"é\n"}""".trimMargin()
        val v = MiniJson.parseObject(text)
        assertEquals("Test \"tiles\"", v["name"])
        assertEquals(3L, v["version"])
        assertEquals(1.5, v["scale"])
        assertEquals(-2000.0, v["neg"])
        assertEquals(true, v["ok"])
        assertNull(v["none"])
        assertTrue(v.containsKey("none"))
        assertEquals(listOf("water", "roads"), (v["layers"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals(emptyMap<String, Any?>(), v["empty"])
        assertEquals(emptyList<Any?>(), v["list"])
        assertEquals("é\n", v["uni"])

        val encoded = MiniJson.encode(v)
        assertEquals(v, MiniJson.parseObject(encoded))
        assertEquals("""{"a":[1,2.5,"x",null,true],"b":{"c":"q\"\\\n"}}""",
            MiniJson.encode(mapOf("a" to listOf(1, 2.5, "x", null, true), "b" to mapOf("c" to "q\"\\\n"))))
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("{\"a\":}") }
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("[1,2] x") }
    }
}
