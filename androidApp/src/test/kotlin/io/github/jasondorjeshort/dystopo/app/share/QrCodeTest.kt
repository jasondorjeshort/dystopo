package io.github.jasondorjeshort.dystopo.app.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrCodeTest {
    /**
     * `qr/reference.txt` holds matrices made by python-qrcode 8.2 (an independent BSD-licensed encoder:
     * `QRCode(error_correction=M, mask_pattern=m, border=0)` with `QRData(text, MODE_8BIT_BYTE)`), one block per case:
     * `text|mask|version|size` then the rows. Every module must match.
     */
    @Test
    fun matchesReferenceEncoder() {
        val lines = javaClass.classLoader!!.getResource("qr/reference.txt")!!.readText().lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        var i = 0
        var cases = 0
        while (i < lines.size) {
            val (text, mask, version, size) = lines[i].split('|')
            val expected = lines.subList(i + 1, i + 1 + size.toInt())
            val qr = QrCode.encode(text, mask.toInt())
            assertEquals(version.toInt(), qr.version, text)
            assertEquals(size.toInt(), qr.size, text)
            assertEquals(expected, qr.rows(), "matrix for $text mask $mask")
            i += 1 + size.toInt()
            cases++
        }
        assertTrue(cases >= 6, "reference cases read: $cases")
    }

    @Test
    fun picksSmallestVersionAndAMask() {
        assertEquals(1, QrCode.encode("123456").version)
        assertEquals(4, QrCode.encode("x".repeat(QrCode.MAX_BYTES)).version)
        val a = QrCode.encode("dystopo://share?addr=192.168.1.23:8841&code=123456")
        val b = QrCode.encode("dystopo://share?addr=192.168.1.23:8841&code=123456")
        assertTrue(a.mask in 0..7)
        assertEquals(a.rows(), b.rows())
        // The automatic choice is one of the forced ones.
        assertEquals(QrCode.encode("dystopo://share?addr=192.168.1.23:8841&code=123456", a.mask).rows(), a.rows())
    }

    @Test
    fun refusesTooLong() {
        assertFailsWith<IllegalArgumentException> { QrCode.encode("x".repeat(QrCode.MAX_BYTES + 1)) }
    }
}
