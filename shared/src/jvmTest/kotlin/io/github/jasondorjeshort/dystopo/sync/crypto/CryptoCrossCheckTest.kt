package io.github.jasondorjeshort.dystopo.sync.crypto

import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Our pure-Kotlin primitives against independent implementations on random inputs: the JDK's
 * `ChaCha20-Poly1305` cipher and `HmacSHA256`, and Poly1305 written directly from its definition with
 * BigInteger. Lengths cover the block boundaries (0, 15, 16, 17, 63, 64, 65, ...).
 */
class CryptoCrossCheckTest {
    private val lengths = listOf(0, 1, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 1000, 4097)

    @Test
    fun aeadMatchesTheJdk() {
        val r = Random(72)
        for (len in lengths) repeat(4) {
            val key = r.nextBytes(32)
            val nonce = r.nextBytes(12)
            val aad = r.nextBytes(r.nextInt(0, 40))
            val pt = r.nextBytes(len)
            val c = Cipher.getInstance("ChaCha20-Poly1305")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            c.updateAAD(aad)
            val jdk = c.doFinal(pt)
            val ours = ChaCha20Poly1305.seal(key, nonce, pt, aad)
            assertContentEquals(jdk, ours, "length $len")
            assertContentEquals(pt, ChaCha20Poly1305.open(key, nonce, jdk, aad))
        }
    }

    private fun polyReference(key: ByteArray, msg: ByteArray): ByteArray {
        fun le(b: ByteArray) = BigInteger(1, b.reversedArray())
        val clamp = key.copyOf(16)
        for (i in listOf(3, 7, 11, 15)) clamp[i] = (clamp[i].toInt() and 15).toByte()
        for (i in listOf(4, 8, 12)) clamp[i] = (clamp[i].toInt() and 252).toByte()
        val r = le(clamp)
        val s = le(key.copyOfRange(16, 32))
        val p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
        var acc = BigInteger.ZERO
        var i = 0
        while (i < msg.size) {
            val chunk = msg.copyOfRange(i, minOf(i + 16, msg.size)) + byteArrayOf(1)
            acc = acc.add(le(chunk)).multiply(r).mod(p)
            i += 16
        }
        val tag = acc.add(s).mod(BigInteger.ONE.shiftLeft(128))
        val be = tag.toByteArray().reversedArray()
        return ByteArray(16) { if (it < be.size) be[it] else 0 }
    }

    @Test
    fun poly1305MatchesItsDefinition() {
        val r = Random(1305)
        for (len in lengths) repeat(8) {
            val key = r.nextBytes(32)
            val msg = r.nextBytes(len)
            assertContentEquals(polyReference(key, msg), Poly1305.mac(key, msg), "length $len")
            // Fed in pieces.
            val p = Poly1305(key)
            var at = 0
            while (at < msg.size) { val n = minOf(r.nextInt(1, 40), msg.size - at); p.update(msg, at, n); at += n }
            assertContentEquals(polyReference(key, msg), p.finish())
        }
        // Extreme values: all-ones keys and messages (the largest limbs and carries).
        for (len in lengths) {
            val key = ByteArray(32) { -1 }
            val msg = ByteArray(len) { -1 }
            assertContentEquals(polyReference(key, msg), Poly1305.mac(key, msg), "ones, length $len")
        }
    }

    @Test
    fun hmacMatchesTheJdk() {
        val r = Random(4231)
        for (keyLen in listOf(0, 1, 32, 64, 65, 200)) for (len in lengths) {
            val key = r.nextBytes(keyLen)
            val msg = r.nextBytes(len)
            val jdk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(if (keyLen == 0) ByteArray(1) else key, "HmacSHA256")) }.doFinal(msg)
            if (keyLen > 0) assertContentEquals(jdk, HmacSha256.mac(key, msg), "key $keyLen, length $len")
            else assertContentEquals(jdk, HmacSha256.mac(ByteArray(1), msg))
        }
    }
}
