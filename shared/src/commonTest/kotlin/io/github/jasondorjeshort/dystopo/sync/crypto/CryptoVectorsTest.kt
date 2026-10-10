package io.github.jasondorjeshort.dystopo.sync.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The published test vectors: RFC 8439 (ChaCha20, Poly1305, AEAD), RFC 4231 (HMAC-SHA256), RFC 5869 (HKDF). */
class CryptoVectorsTest {
    private fun hex(s: String): ByteArray {
        val t = s.filter { it.isLetterOrDigit() }
        return ByteArray(t.length / 2) { t.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun toHex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val key0to31 = ByteArray(32) { it.toByte() }
    private val key80to9f = ByteArray(32) { (0x80 + it).toByte() }
    private val sunscreen = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."

    @Test
    fun chacha20BlockFunction() { // RFC 8439 2.3.2
        val out = ChaCha20Poly1305.chacha20Block(key0to31, 1, hex("000000090000004a00000000"))
        assertEquals(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
            toHex(out),
        )
    }

    @Test
    fun chacha20Encryption() { // RFC 8439 2.4.2
        val pt = sunscreen.encodeToByteArray()
        val out = ByteArray(pt.size)
        ChaCha20Poly1305.chacha20Xor(key0to31, hex("000000000000004a00000000"), 1, pt, 0, pt.size, out, 0)
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d",
            toHex(out),
        )
        val back = ByteArray(pt.size)
        ChaCha20Poly1305.chacha20Xor(key0to31, hex("000000000000004a00000000"), 1, out, 0, out.size, back, 0)
        assertContentEquals(pt, back)
    }

    @Test
    fun poly1305() { // RFC 8439 2.5.2
        val tag = Poly1305.mac(hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b"), "Cryptographic Forum Research Group".encodeToByteArray())
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", toHex(tag))
    }

    @Test
    fun poly1305EdgeCases() { // RFC 8439 A.3, the cases that exercise the carries and the final reduction
        // #1: all-zero key and message.
        assertEquals("00000000000000000000000000000000", toHex(Poly1305.mac(ByteArray(32), ByteArray(64))))
        // #5: r = 2, s = 0, m = 2^128 - 1: h reaches 2^130 - 5 + 3.
        assertEquals("03000000000000000000000000000000", toHex(Poly1305.mac(hex("02") + ByteArray(31), ByteArray(16) { -1 })))
        // #6: r = 2, s = 2^128 - 1, m = 2.
        assertEquals("03000000000000000000000000000000", toHex(Poly1305.mac(hex("02") + ByteArray(15) + ByteArray(16) { -1 }, hex("02") + ByteArray(15))))
        // #7: r = 1, s = 0.
        assertEquals(
            "05000000000000000000000000000000",
            toHex(Poly1305.mac(hex("01") + ByteArray(31), ByteArray(16) { -1 } + hex("f0") + ByteArray(15) { -1 } + hex("11") + ByteArray(15))),
        )
        // #8: r = 1, s = 0.
        assertEquals(
            "00000000000000000000000000000000",
            toHex(Poly1305.mac(hex("01") + ByteArray(31), ByteArray(16) { -1 } + hex("fb") + ByteArray(15) { -2 } + ByteArray(16) { 1 })),
        )
        // #9: r = 2, s = 0, m = 2^128 - 3 (little-endian fd ff ... ff).
        assertEquals("faffffffffffffffffffffffffffffff", toHex(Poly1305.mac(hex("02") + ByteArray(31), hex("fd") + ByteArray(15) { -1 })))
        // #10 and #11: r = 2^64 + 1... (01 00 .. 04 00 ..), s = 0.
        val k10 = hex("0100000000000000040000000000000000000000000000000000000000000000")
        val m10 = hex(
            "e33594d7505e43b900000000000000003394d7505e4379cd0100000000000000" +
                "0000000000000000000000000000000001000000000000000000000000000000",
        )
        assertEquals("14000000000000005500000000000000", toHex(Poly1305.mac(k10, m10)))
        assertEquals("13000000000000000000000000000000", toHex(Poly1305.mac(k10, m10.copyOf(48))))
    }

    @Test
    fun poly1305KeyGeneration() { // RFC 8439 2.6.2
        val out = ChaCha20Poly1305.chacha20Block(key80to9f, 0, hex("000000000001020304050607")).copyOf(32)
        assertEquals("8ad5a08b905f81cc815040274ab29471a833b637e3fd0da508dbb8e2fdd1a646", toHex(out))
    }

    @Test
    fun aeadSealAndOpen() { // RFC 8439 2.8.2
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val sealed = ChaCha20Poly1305.seal(key80to9f, nonce, sunscreen.encodeToByteArray(), aad)
        assertEquals(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691",
            toHex(sealed),
        )
        assertEquals(sunscreen, ChaCha20Poly1305.open(key80to9f, nonce, sealed, aad).decodeToString())
        // Any change to the ciphertext, the tag, the associated data, the key or the nonce fails.
        for (i in sealed.indices step 7) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<ChaCha20Poly1305.AuthenticationException> { ChaCha20Poly1305.open(key80to9f, nonce, bad, aad) }
        }
        assertFailsWith<ChaCha20Poly1305.AuthenticationException> { ChaCha20Poly1305.open(key80to9f, nonce, sealed, aad.copyOf(11)) }
        assertFailsWith<ChaCha20Poly1305.AuthenticationException> { ChaCha20Poly1305.open(key0to31, nonce, sealed, aad) }
        assertFailsWith<ChaCha20Poly1305.AuthenticationException> { ChaCha20Poly1305.open(key80to9f, hex("070000004041424344454648"), sealed, aad) }
        assertFailsWith<ChaCha20Poly1305.AuthenticationException> { ChaCha20Poly1305.open(key80to9f, nonce, sealed.copyOf(15), aad) }
    }

    @Test
    fun aeadDecryptionVector() { // RFC 8439 A.5
        val key = hex("1c9240a5eb55d38af333888604f6b5f0473917c1402b80099dca5cbc207075c0")
        val nonce = hex("000000000102030405060708")
        val aad = hex("f33388860000000000004e91")
        val ct = hex(
            "64a0861575861af460f062c79be643bd5e805cfd345cf389f108670ac76c8cb2" +
                "4c6cfc18755d43eea09ee94e382d26b0bdb7b73c321b0100d4f03b7f355894cf" +
                "332f830e710b97ce98c8a84abd0b948114ad176e008d33bd60f982b1ff37c855" +
                "9797a06ef4f0ef61c186324e2b3506383606907b6a7c02b0f9f6157b53c867e4" +
                "b9166c767b804d46a59b5216cde7a4e99040c5a40433225ee282a1b0a06c523e" +
                "af4534d7f83fa1155b0047718cbc546a0d072b04b3564eea1b422273f548271a" +
                "0bb2316053fa76991955ebd63159434ecebb4e466dae5a1073a6727627097a10" +
                "49e617d91d361094fa68f0ff77987130305beaba2eda04df997b714d6c6f2c29" +
                "a6ad5cb4022b02709b",
        )
        val tag = hex("eead9d67890cbb22392336fea1851f38")
        val pt = ChaCha20Poly1305.open(key, nonce, ct + tag, aad).decodeToString()
        assertEquals(
            "Internet-Drafts are draft documents valid for a maximum of six months and may be updated, replaced, or obsoleted " +
                "by other documents at any time. It is inappropriate to use Internet-Drafts as reference material or to cite them " +
                "other than as /“work in progress./”",
            pt,
        )
    }

    @Test
    fun hmacSha256() { // RFC 4231
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            toHex(HmacSha256.mac(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            toHex(HmacSha256.mac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())),
        )
        assertEquals(
            "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe",
            toHex(HmacSha256.mac(ByteArray(20) { 0xaa.toByte() }, ByteArray(50) { 0xdd.toByte() })),
        )
        assertEquals(
            "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b",
            toHex(HmacSha256.mac(ByteArray(25) { (it + 1).toByte() }, ByteArray(50) { 0xcd.toByte() })),
        )
        // Case 6: a key longer than the block is hashed first.
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            toHex(HmacSha256.mac(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray())),
        )
        // Parts are concatenated.
        assertContentEquals(HmacSha256.mac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()),
            HmacSha256.mac("Jefe".encodeToByteArray(), "what do ya ".encodeToByteArray(), "want for nothing?".encodeToByteArray()))
    }

    @Test
    fun hkdf() { // RFC 5869 A.1 to A.3
        val prk1 = Hkdf.extract(hex("000102030405060708090a0b0c"), ByteArray(22) { 0x0b })
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", toHex(prk1))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            toHex(Hkdf.expand(prk1, hex("f0f1f2f3f4f5f6f7f8f9"), 42)),
        )
        val okm2 = Hkdf.derive(ByteArray(80) { (0x60 + it).toByte() }, ByteArray(80) { it.toByte() }, ByteArray(80) { (0xb0 + it).toByte() }, 82)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            toHex(okm2),
        )
        val okm3 = Hkdf.derive(ByteArray(0), ByteArray(22) { 0x0b }, ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", toHex(okm3))
    }
}
