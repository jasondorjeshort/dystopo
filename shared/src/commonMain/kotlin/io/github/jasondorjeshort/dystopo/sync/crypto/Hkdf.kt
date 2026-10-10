package io.github.jasondorjeshort.dystopo.sync.crypto

import io.github.jasondorjeshort.dystopo.packs.Sha256

/** HMAC-SHA256 (RFC 2104 over FIPS 180-4 SHA-256, the shared [Sha256]); test vectors from RFC 4231. */
object HmacSha256 {
    const val BLOCK = 64
    const val LENGTH = 32

    fun mac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val k = if (key.size > BLOCK) Sha256().update(key).digest() else key
        val ipad = ByteArray(BLOCK) { i -> ((if (i < k.size) k[i].toInt() else 0) xor 0x36).toByte() }
        val opad = ByteArray(BLOCK) { i -> ((if (i < k.size) k[i].toInt() else 0) xor 0x5c).toByte() }
        val inner = Sha256().update(ipad)
        for (p in parts) inner.update(p)
        return Sha256().update(opad).update(inner.digest()).digest()
    }
}

/** HKDF with HMAC-SHA256 (RFC 5869): extract a pseudorandom key from input keying material and a salt, expand it under a context label. */
object Hkdf {
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        HmacSha256.mac(if (salt.isEmpty()) ByteArray(HmacSha256.LENGTH) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..255 * HmacSha256.LENGTH) { "HKDF output too long" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var done = 0
        var i = 1
        while (done < length) {
            t = HmacSha256.mac(prk, t, info, byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - done)
            t.copyInto(out, done, 0, n)
            done += n
            i++
        }
        return out
    }

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray = expand(extract(salt, ikm), info, length)
}
