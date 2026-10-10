package io.github.jasondorjeshort.dystopo.pmtiles

import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Corrupt or hostile input must fail with a clear exception, never a huge allocation or a stack overflow. */
class CorruptArchiveTest {
    /** A small valid archive: 40 distinct 1000-byte tiles, so it is larger than the 16 KiB head read. */
    private fun archive(): ByteArray {
        val writer = PmtilesWriter(TileType.PNG)
        for (x in 0 until 8) for (y in 0 until 5) writer.addTile(3, x, y, ByteArray(1000) { (x * 7 + y * 13 + it).toByte() })
        val chunks = ArrayList<ByteArray>()
        writer.finish({ chunks += it }, """{"name":"corrupt-test"}""")
        val out = ByteArray(chunks.sumOf { it.size })
        var p = 0
        for (c in chunks) { c.copyInto(out, p); p += c.size }
        return out
    }

    private class Memory(val bytes: ByteArray) : ByteRangeSource {
        override fun read(offset: Long, length: Int): ByteArray =
            bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
    }

    /** The tile stored last, so it lies beyond the 16 KiB head and must be read from the source. */
    private fun lastTile(bytes: ByteArray): TileId =
        TileIds.toZxy(PmtilesReader.open(Memory(bytes)).tileEntries().maxBy { it.offset }.tileId)

    private fun PmtilesReader.tile(t: TileId) = tile(t.z, t.x, t.y)

    private fun ByteArray.putU64(at: Int, v: Long) {
        for (i in 0 until 8) this[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun directory(vararg varints: Long): ByteArray = ByteWriter().apply { varints.forEach { varint(it) } }.toByteArray()

    @Test
    fun validArchiveOpens() {
        val bytes = archive()
        assertTrue(bytes.size > PmtilesReader.HEAD_BYTES)
        val reader = PmtilesReader.open(Memory(bytes))
        assertEquals(1000, reader.tile(3, 7, 4)!!.size)
        assertEquals("corrupt-test", reader.metadata()["name"])
    }

    @Test
    fun directoryCountIsCheckedAgainstTheBytesPresent() {
        // Claims a billion entries in a 5-byte buffer: must fail before allocating 8 GB of tile IDs.
        assertFailsWith<IllegalArgumentException> { Directory.decode(directory(1_000_000_000L)) }
        assertFailsWith<IllegalArgumentException> { Directory.decode(directory(Long.MAX_VALUE)) }
        assertFailsWith<IllegalArgumentException> { Directory.decode(directory(-1L)) }
        // Two entries need at least 8 more bytes; 7 is not enough.
        assertFailsWith<IllegalArgumentException> { Directory.decode(directory(2, 0, 1, 1, 1, 10, 10, 1)) }
        assertEquals(2, Directory.decode(directory(2, 0, 1, 1, 1, 10, 10, 1, 0)).size)
    }

    @Test
    fun directoryRejectsFieldsBeyondIntRange() {
        val big = Int.MAX_VALUE.toLong() + 1
        // count, tile-id delta, run length, length, offset+1
        assertEquals(listOf(Entry(0, 0, 10, 1)), Directory.decode(directory(1, 0, 1, 10, 1)))
        assertFailsWith<IllegalArgumentException> { Directory.decode(directory(1, 0, big, 10, 1)) }
        assertFailsWith<IllegalArgumentException> { Directory.decode(directory(1, 0, 1, big, 1)) }
        assertFailsWith<IllegalArgumentException> { Directory.decode(directory(1, 0, -1, 10, 1)) }
        // Offset varint that decodes negative (top bit set).
        assertFailsWith<IllegalArgumentException> { Directory.decode(directory(1, 0, 1, 10, -1)) }
        // Tile IDs that overflow when the deltas are summed.
        assertFailsWith<IllegalArgumentException> { Directory.decode(directory(2, Long.MAX_VALUE, 1, 1, 1, 1, 1, 1, 0)) }
        // Contiguous offset that overflows.
        assertFailsWith<IllegalArgumentException> {
            Directory.decode(directory(2, 0, 1, 1, 1, Int.MAX_VALUE.toLong(), 1, Long.MAX_VALUE, 0))
        }
    }

    @Test
    fun truncatedDirectoryFails() {
        val full = Directory.encode(listOf(Entry(0, 0, 100, 1), Entry(1, 100, 100, 1), Entry(2, 200, 100, 1)))
        assertFails { Directory.decode(full.copyOf(full.size - 1)) }
        // Ends inside a varint (continuation bit set on the last byte).
        assertFailsWith<IllegalStateException> { Directory.decode(full.copyOf(full.size - 1) + byteArrayOf(0x80.toByte())) }
    }

    @Test
    fun headerLengthsBeyondIntRangeAreRejected() {
        val metadataTooLong = archive().also { it.putU64(32, Int.MAX_VALUE.toLong() + 1) }
        val reader = PmtilesReader.open(Memory(metadataTooLong))
        assertFailsWith<IllegalArgumentException> { reader.metadataJson() }

        val rootTooLong = archive().also { it.putU64(16, 1L shl 40) }
        assertFailsWith<IllegalArgumentException> { PmtilesReader.open(Memory(rootTooLong)) }

        // Unsigned 64-bit values past Long.MAX_VALUE.
        val negative = archive().also { it.putU64(56, -5L) }
        assertFailsWith<IllegalArgumentException> { PmtilesHeader.decode(negative) }
        val negativeCount = archive().also { it.putU64(72, Long.MIN_VALUE) }
        assertFailsWith<IllegalArgumentException> { PmtilesHeader.decode(negativeCount) }
    }

    @Test
    fun tileOffsetOverflowIsRejected() {
        // Tile data offset near Long.MAX_VALUE: offset + entry offset overflows.
        val bytes = archive().also { it.putU64(56, Long.MAX_VALUE - 10) }
        val reader = PmtilesReader.open(Memory(bytes))
        assertFailsWith<IllegalArgumentException> { reader.tile(3, 7, 4) }
    }

    @Test
    fun sourceThatIgnoresRangeRequestsIsDetectedOnOpen() {
        val bytes = archive()
        // Like an HTTP server answering 200 with the whole file instead of 206.
        val fullBody = ByteRangeSource { _, _ -> bytes }
        val e = assertFailsWith<IllegalStateException> { PmtilesReader.open(fullBody) }
        assertTrue("range" in e.message!!, e.message)
    }

    @Test
    fun sourceThatIgnoresRangeLaterIsDetectedOnRead() {
        val bytes = archive()
        val good = Memory(bytes)
        val fullBodyAfterHead = ByteRangeSource { offset, length -> if (offset == 0L) good.read(offset, length) else bytes }
        val reader = PmtilesReader.open(fullBodyAfterHead)
        // A tile beyond the head must be read from the source, which hands back the whole file.
        val e = assertFailsWith<IllegalStateException> { reader.tile(lastTile(bytes)) }
        assertTrue("range" in e.message!!, e.message)
    }

    @Test
    fun truncatedArchiveFailsOnShortRead() {
        val bytes = archive()
        val reader = PmtilesReader.open(Memory(bytes.copyOf(bytes.size - 10)))
        assertFailsWith<IllegalStateException> { reader.tile(lastTile(bytes)) }
        assertContentEquals(PmtilesReader.open(Memory(bytes)).tile(3, 0, 0), reader.tile(3, 0, 0))
    }

    @Test
    fun miniJsonCapsNestingDepth() {
        val ok = "[".repeat(MiniJson.MAX_DEPTH) + "]".repeat(MiniJson.MAX_DEPTH)
        MiniJson.parse(ok)
        val tooDeep = "[".repeat(MiniJson.MAX_DEPTH + 1) + "]".repeat(MiniJson.MAX_DEPTH + 1)
        assertFailsWith<IllegalArgumentException> { MiniJson.parse(tooDeep) }
        // Without the cap this overflows the stack.
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("[".repeat(200_000)) }
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("{\"a\":".repeat(200_000)) }
        // Depth is per path, not cumulative: many siblings are fine.
        MiniJson.parse("[" + List(1000) { "[{}]" }.joinToString(",") + "]")
    }

    @Test
    fun miniJsonRejectsBadUnicodeEscape() {
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("\"\\uZZZZ\"") }
    }
}
