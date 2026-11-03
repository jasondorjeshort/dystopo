package io.github.jasondorjeshort.dystopo.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Google's revocation list with decimal and hex keys, normalised to one form (ADR 0051). */
class RevocationListTest {
    private val day = 86_400_000L

    @Test fun decimalStringsConvertToHex() {
        assertEquals(6681152659205225093L.toString(16), RevocationList.decimalToHex("6681152659205225093"))
        assertEquals("11244410301401252959".toULong().toString(16), RevocationList.decimalToHex("11244410301401252959"))
        assertEquals("0", RevocationList.decimalToHex("0"))
        assertEquals("ff", RevocationList.decimalToHex("255"))
        // Longer than 64 bits: 2^80 = 1208925819614629174706176.
        assertEquals("1" + "0".repeat(20), RevocationList.decimalToHex("1208925819614629174706176"))
    }

    @Test fun keysMatchInEitherForm() {
        val list = RevocationList.parse(
            """{"entries":{"6681152659205225093":{"status":"REVOKED","reason":"KEY_COMPROMISE"},"C35747A084470C3135AEEFE2B8D40CD6":{"status":"SUSPENDED"}}}""",
            1000L,
        )
        assertEquals(2, list.entries)
        assertTrue(list.isRevoked(6681152659205225093L.toString(16)))
        assertTrue(list.isRevoked("6681152659205225093")) // the same digits read as hex
        assertTrue(list.isRevoked("c35747a084470c3135aeefe2b8d40cd6"))
        assertTrue(list.isRevoked("00c35747a084470c3135aeefe2b8d40cd6"))
        assertFalse(list.isRevoked("5eed"))
        assertEquals(2 * day, list.ageMillis(1000L + 2 * day))
        assertNull(RevocationList.NONE.ageMillis(0))
    }

    @Test fun badListsThrowTheOneException() {
        for (bad in listOf("", "[]", "{}", """{"entries":{"xyz":{"status":"REVOKED"}}}""", """{"entries":{"12":{}}}""", """{"entries":{"12":{"status":"REVOKED"}""")) {
            assertFailsWith<AttestationFormatException>(bad) { RevocationList.parse(bad, 0) }
        }
    }
}
