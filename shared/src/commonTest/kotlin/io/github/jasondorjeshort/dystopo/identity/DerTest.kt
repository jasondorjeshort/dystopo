package io.github.jasondorjeshort.dystopo.identity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The DER reader and writer of the identity package (pure Kotlin, offline). */
class DerTest {
    private fun one(b: ByteArray) = DerReader.single(b, "test")

    @Test fun integersRoundTrip() {
        for (v in listOf(0L, 1L, 127L, 128L, 255L, 256L, -1L, -128L, -129L, 20251001L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertEquals(v, one(Der.integer(v)).asLong("v"), "$v")
        }
        assertContentEquals(byteArrayOf(0x02, 0x02, 0x00, 0x80.toByte()), Der.integer(128))
        assertEquals("80", one(Der.unsignedInteger(byteArrayOf(0x80.toByte()))).asUnsignedBytes("s").hex())
        assertFailsWith<AttestationFormatException> { one(Der.unsignedInteger(ByteArray(9) { 1 })).asLong("too long") }
    }

    @Test fun oidsRoundTrip() {
        for (oid in listOf(KeyDescription.OID, Oids.ECDSA_SHA256, Oids.RSA_SHA256, "2.5.4.3", "2.999.1")) assertEquals(oid, one(Der.oid(oid)).asOid("oid"))
    }

    @Test fun timesRoundTripInBothForms() {
        val t = Der.epochMillis(2026, 11, 1, 12, 34, 56)
        assertEquals("2026-11-01T12:34:56Z", Der.iso(t))
        assertEquals(t, one(Der.time(t)).asTime("t"))
        assertEquals(DerElement.UTC_TIME, one(Der.time(t)).tag)
        val far = Der.epochMillis(2126, 10, 10, 6, 16, 23)
        assertEquals(DerElement.GENERALIZED_TIME, one(Der.time(far)).tag)
        assertEquals(far, one(Der.time(far)).asTime("t"))
        assertEquals(0L, Der.epochMillis(1970, 1, 1, 0, 0, 0))
        assertEquals("1969-12-31T23:59:59Z", Der.iso(-1000))
        assertEquals("2024-02-29T00:00:00Z", Der.iso(Der.epochMillis(2024, 2, 29, 0, 0, 0)))
    }

    @Test fun highTagNumbersRoundTrip() {
        val e = one(Der.explicit(704, Der.boolean(true)))
        assertEquals(DerElement.CONTEXT, e.tagClass)
        assertEquals(704, e.tag)
        assertEquals(true, e.children().single().asBoolean("b"))
        assertContentEquals(byteArrayOf(0xbf.toByte(), 0x85.toByte(), 0x40), Der.explicit(704, ByteArray(0)).copyOf(3))
    }

    @Test fun longLengthsAndSets() {
        val big = Der.octets(ByteArray(70_000 / 2))
        assertEquals(35_000, one(big).contentLength)
        val set = one(Der.setOf(listOf(Der.integer(3), Der.integer(1), Der.integer(2))))
        assertEquals(listOf(1L, 2L, 3L), set.children().map { it.asLong("i") })
    }

    @Test fun malformedInputThrowsTheOneException() {
        val bad = listOf(
            byteArrayOf(),
            byteArrayOf(0x30),
            byteArrayOf(0x30, 0x05, 0x02, 0x01),
            byteArrayOf(0x30, 0x80.toByte(), 0x00, 0x00),
            byteArrayOf(0x04, 0x84.toByte(), 0x01, 0x00, 0x00, 0x00),
            byteArrayOf(0x1f, 0x80.toByte(), 0x01, 0x00),
            byteArrayOf(0x02, 0x01, 0x01, 0x00),
        )
        for (b in bad) assertFailsWith<AttestationFormatException>(b.hex()) { one(b) }
        var nested = Der.nullValue()
        repeat(DerReader.MAX_DEPTH + 2) { nested = Der.sequence(nested) }
        assertFailsWith<AttestationFormatException> {
            fun walk(e: DerElement) { if (e.constructed) e.children().forEach { walk(it) } }
            walk(one(nested))
        }
        assertFailsWith<AttestationFormatException> { one(Der.sequence(List(DerReader.MAX_ITEMS + 1) { Der.nullValue() })).children() }
        assertFailsWith<AttestationFormatException> { one(Der.utf8("x")).asOctets("o") }
        assertFailsWith<AttestationFormatException> { one(byteArrayOf(0x0c, 0x02, 0xc3.toByte(), 0x28)).asText("t") }
    }
}
