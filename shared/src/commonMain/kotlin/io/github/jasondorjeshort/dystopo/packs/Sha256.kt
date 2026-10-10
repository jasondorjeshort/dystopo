package io.github.jasondorjeshort.dystopo.packs

/**
 * SHA-256 (FIPS 180-4) in plain Kotlin, streaming: [update] any number of times, then [digest] or [hex].
 * The pack catalog's content hashes are SHA-256 ([PackHash]); platforms with a native digest (the JVM's
 * `MessageDigest`) may use that for big files, the hex output is the same.
 */
class Sha256 {
    private val h = H0.copyOf()
    private val block = ByteArray(64)
    private var blockLen = 0
    private var total = 0L
    private val w = IntArray(64)
    private var finished = false

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Sha256 {
        check(!finished) { "digest already taken" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var i = offset
        val end = offset + length
        total += length
        if (blockLen > 0) {
            val n = minOf(64 - blockLen, end - i)
            bytes.copyInto(block, blockLen, i, i + n)
            blockLen += n
            i += n
            if (blockLen == 64) { compress(block, 0); blockLen = 0 }
        }
        while (end - i >= 64) { compress(bytes, i); i += 64 }
        if (i < end) { bytes.copyInto(block, 0, i, end); blockLen = end - i }
        return this
    }

    fun digest(): ByteArray {
        check(!finished) { "digest already taken" }
        finished = true
        val bits = total * 8
        block[blockLen++] = 0x80.toByte()
        if (blockLen > 56) {
            while (blockLen < 64) block[blockLen++] = 0
            compress(block, 0)
            blockLen = 0
        }
        while (blockLen < 56) block[blockLen++] = 0
        for (k in 0 until 8) block[56 + k] = (bits ushr (56 - 8 * k)).toByte()
        compress(block, 0)
        val out = ByteArray(32)
        for (k in 0 until 8) for (b in 0 until 4) out[4 * k + b] = (h[k] ushr (24 - 8 * b)).toByte()
        return out
    }

    /** The digest as 64 lowercase hex digits. */
    fun hex(): String = toHex(digest())

    private fun compress(src: ByteArray, at: Int) {
        for (t in 0 until 16) {
            val o = at + 4 * t
            w[t] = ((src[o].toInt() and 0xff) shl 24) or ((src[o + 1].toInt() and 0xff) shl 16) or
                ((src[o + 2].toInt() and 0xff) shl 8) or (src[o + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val x = w[t - 15]
            val y = w[t - 2]
            val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
            val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }

    companion object {
        fun hex(bytes: ByteArray): String = Sha256().update(bytes).hex()

        fun toHex(bytes: ByteArray): String {
            val digits = "0123456789abcdef"
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) { sb.append(digits[(b.toInt() shr 4) and 15]); sb.append(digits[b.toInt() and 15]) }
            return sb.toString()
        }

        private val H0 = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )

        private val K = longArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ).map { it.toInt() }.toIntArray()
    }
}
