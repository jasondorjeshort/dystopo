package io.github.jasondorjeshort.dystopo.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtobufTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun varintsRoundTrip() {
        val samples = longArrayOf(0, 1, 127, 128, 300, 16383, 16384, Int.MAX_VALUE.toLong(), 1L shl 35, Long.MAX_VALUE, -1, Long.MIN_VALUE)
        val w = ProtoWriter()
        samples.forEach { w.varint(it) }
        val r = ProtoReader(w.toByteArray())
        for (s in samples) assertEquals(s, r.readVarint())
        assertEquals(false, r.hasMore)
    }

    @Test
    fun knownVarintEncodings() {
        assertEquals(listOf(0x96, 0x01), ProtoWriter().apply { varint(150) }.toByteArray().map { it.toInt() and 0xFF })
        assertEquals(300L, ProtoReader(bytes(0xAC, 0x02)).readVarint())
        // -1 as int64 takes the full ten bytes.
        assertEquals(10, ProtoWriter().apply { varint(-1) }.size)
    }

    @Test
    fun zigzag() {
        assertEquals(0, ProtoReader.zigzagEncode(0))
        assertEquals(1, ProtoReader.zigzagEncode(-1))
        assertEquals(2, ProtoReader.zigzagEncode(1))
        assertEquals(3, ProtoReader.zigzagEncode(-2))
        assertEquals(4294967294L, ProtoReader.zigzagEncode(Int.MAX_VALUE).toLong() and 0xFFFFFFFFL)
        for (n in intArrayOf(0, 1, -1, 25, -17, 4096, -4096, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(n, ProtoReader.zigzagDecode(ProtoReader.zigzagEncode(n)))
        }
        for (n in longArrayOf(0, -1, 1, Long.MAX_VALUE, Long.MIN_VALUE, -123456789012L)) {
            assertEquals(n, ProtoReader.zigzagDecode(ProtoReader.zigzagEncode(n)))
        }
    }

    @Test
    fun fieldsRoundTripAndUnknownFieldsSkip() {
        val inner = ProtoWriter().apply { stringField(1, "héllo"); varintField(2, 42) }
        val w = ProtoWriter().apply {
            varintField(1, 7)
            fixed32Field(2, 0x12345678)
            fixed64Field(3, -2L)
            messageField(4, inner)
            packedUint32Field(5, intArrayOf(1, 300, -1))
            varintField(6, 0)
            varintField(6, 1)
        }
        val r = ProtoReader(w.toByteArray())
        assertEquals(1, ProtoReader.fieldNumber(r.readKey())); assertEquals(7L, r.readVarint())
        assertEquals(2, ProtoReader.fieldNumber(r.readKey())); assertEquals(0x12345678, r.readFixed32())
        assertEquals(3, ProtoReader.fieldNumber(r.readKey())); assertEquals(-2L, r.readFixed64())
        val k4 = r.readKey()
        assertEquals(4, ProtoReader.fieldNumber(k4)); assertEquals(2, ProtoReader.wireType(k4))
        val sub = r.readMessage()
        sub.readKey(); assertEquals("héllo", sub.readString())
        sub.readKey(); assertEquals(42L, sub.readVarint())
        assertEquals(false, sub.hasMore)
        val k5 = r.readKey()
        val vec = IntVec()
        r.readUint32s(ProtoReader.wireType(k5), vec)
        // Expanded (non-packed) repeated values append too.
        r.readUint32s(ProtoReader.wireType(r.readKey()), vec)
        r.readUint32s(ProtoReader.wireType(r.readKey()), vec)
        assertEquals(listOf(1, 300, -1, 0, 1), vec.toIntArray().toList())
        assertEquals(false, r.hasMore)

        // Skipping every wire type lands on the next field.
        val s = ProtoReader(w.toByteArray())
        repeat(7) { s.skip(ProtoReader.wireType(s.readKey())) }
        assertEquals(false, s.hasMore)
    }

    @Test
    fun truncatedInputThrows() {
        assertFailsWith<IllegalArgumentException> { ProtoReader(bytes(0x80, 0x80)).readVarint() }
        assertFailsWith<IllegalArgumentException> { ProtoReader(bytes(0x0A, 0x05, 0x01)).let { it.readKey(); it.readString() } }
        assertFailsWith<IllegalArgumentException> { ProtoReader(bytes(0x0D, 0x01)).let { it.readKey(); it.readFixed32() } }
        assertFailsWith<IllegalArgumentException> { ProtoReader(ByteArray(11) { 0xFF.toByte() }).readVarint() }
    }
}
