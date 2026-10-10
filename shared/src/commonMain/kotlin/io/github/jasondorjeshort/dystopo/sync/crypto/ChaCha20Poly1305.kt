package io.github.jasondorjeshort.dystopo.sync.crypto

/**
 * ChaCha20-Poly1305 AEAD as specified in RFC 8439, in plain Kotlin (T72; no dependency, every platform).
 * Used to encrypt sync bundles end to end ([io.github.jasondorjeshort.dystopo.sync.EncryptedBundle]).
 *
 * - [chacha20Block] is the block function (RFC 8439 2.3), [chacha20Xor] the stream cipher (2.4).
 * - [Poly1305] is the one-time authenticator (2.5), 26-bit limbs as in poly1305-donna, constant time.
 * - [seal] / [open] are the AEAD construction (2.8): the Poly1305 key is the first 32 bytes of block 0,
 *   the plaintext is encrypted from block 1, the tag covers `aad | pad16 | ciphertext | pad16 | len(aad) | len(ct)`.
 *
 * A key and nonce pair must never encrypt two different messages; callers derive a fresh key and nonce
 * per message from a random salt ([Hkdf]), so that holds by construction.
 * Checked against the RFC's test vectors (commonTest) and against the JDK's own `ChaCha20-Poly1305` cipher
 * on random inputs (jvmTest).
 */
object ChaCha20Poly1305 {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    /** The tag did not verify: wrong key, wrong associated data, or a changed ciphertext. */
    class AuthenticationException : IllegalArgumentException("authentication failed (wrong key or corrupted data)")

    /** Encrypts [plaintext]; returns ciphertext followed by the 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        checkKeyNonce(key, nonce)
        val out = ByteArray(plaintext.size + TAG_BYTES)
        chacha20Xor(key, nonce, 1, plaintext, 0, plaintext.size, out, 0)
        val tag = tag(key, nonce, aad, out, plaintext.size)
        tag.copyInto(out, plaintext.size)
        return out
    }

    /** Verifies and decrypts `ciphertext | tag`; throws [AuthenticationException] when the tag does not match (nothing is decrypted then). */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        checkKeyNonce(key, nonce)
        if (sealed.size < TAG_BYTES) throw AuthenticationException()
        val n = sealed.size - TAG_BYTES
        val expected = tag(key, nonce, aad, sealed, n)
        var diff = 0
        for (i in 0 until TAG_BYTES) diff = diff or (expected[i].toInt() xor sealed[n + i].toInt())
        if (diff != 0) throw AuthenticationException()
        val out = ByteArray(n)
        chacha20Xor(key, nonce, 1, sealed, 0, n, out, 0)
        return out
    }

    private fun checkKeyNonce(key: ByteArray, nonce: ByteArray) {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
    }

    private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray, ctLen: Int): ByteArray {
        val polyKey = chacha20Block(key, 0, nonce).copyOf(32)
        val p = Poly1305(polyKey)
        p.update(aad, 0, aad.size)
        p.update(ZEROS, 0, (16 - aad.size % 16) % 16)
        p.update(ct, 0, ctLen)
        p.update(ZEROS, 0, (16 - ctLen % 16) % 16)
        val lens = ByteArray(16)
        putLe64(lens, 0, aad.size.toLong())
        putLe64(lens, 8, ctLen.toLong())
        p.update(lens, 0, 16)
        return p.finish()
    }

    private val ZEROS = ByteArray(16)

    // ---- ChaCha20 (RFC 8439 2.1 to 2.4) ----

    /** One 64-byte keystream block for [counter] (RFC 8439 2.3). */
    fun chacha20Block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val out = ByteArray(64)
        block(initState(key, nonce), counter, IntArray(16), out)
        return out
    }

    /**
     * XORs [length] bytes of [input] from [inOff] with the keystream starting at block [counter] (RFC 8439 2.4)
     * into [output] from [outOff]. Encryption and decryption are the same operation.
     */
    fun chacha20Xor(key: ByteArray, nonce: ByteArray, counter: Int, input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int) {
        checkKeyNonce(key, nonce)
        val blocks = (length + 63L) / 64
        require(counter.toLong().and(0xffffffffL) + blocks <= 0x1_0000_0000L) { "message too long for one nonce" }
        val state = initState(key, nonce)
        val x = IntArray(16)
        val ks = ByteArray(64)
        var done = 0
        var c = counter
        while (done < length) {
            block(state, c, x, ks)
            val n = minOf(64, length - done)
            for (i in 0 until n) output[outOff + done + i] = (input[inOff + done + i].toInt() xor ks[i].toInt()).toByte()
            done += n
            c++
        }
    }

    /** Constants, key and nonce; word 12 (the counter) is set per block. */
    private fun initState(key: ByteArray, nonce: ByteArray): IntArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, 4 * i)
        for (i in 0 until 3) s[13 + i] = le32(nonce, 4 * i)
        return s
    }

    private fun block(state: IntArray, counter: Int, x: IntArray, out: ByteArray) {
        state[12] = counter
        state.copyInto(x)
        repeat(10) {
            quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14)
        }
        for (i in 0 until 16) putLe32(out, 4 * i, x[i] + state[i])
    }

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    internal fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    internal fun putLe32(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte(); b[at + 2] = (v ushr 16).toByte(); b[at + 3] = (v ushr 24).toByte()
    }

    private fun putLe64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (8 * i)).toByte()
    }
}

/**
 * Poly1305 one-time authenticator (RFC 8439 2.5): a 32-byte one-time key (`r | s`), any number of
 * [update]s, then [finish] for the 16-byte tag. Arithmetic modulo 2^130 - 5 in five 26-bit limbs held in
 * Longs (products stay below 2^58); the final reduction selects with masks, not branches.
 */
class Poly1305(key: ByteArray) {
    private val r0: Long
    private val r1: Long
    private val r2: Long
    private val r3: Long
    private val r4: Long
    private val s1: Long
    private val s2: Long
    private val s3: Long
    private val s4: Long
    private val pad = LongArray(4)
    private var h0 = 0L
    private var h1 = 0L
    private var h2 = 0L
    private var h3 = 0L
    private var h4 = 0L
    private val buf = ByteArray(16)
    private var bufLen = 0
    private var finished = false

    init {
        require(key.size == 32) { "Poly1305 key must be 32 bytes" }
        // r is clamped (RFC 8439 2.5): the limb masks below clear the bits the RFC clears.
        r0 = u32(key, 0) and 0x3ffffff
        r1 = (u32(key, 3) ushr 2) and 0x3ffff03
        r2 = (u32(key, 6) ushr 4) and 0x3ffc0ff
        r3 = (u32(key, 9) ushr 6) and 0x3f03fff
        r4 = (u32(key, 12) ushr 8) and 0x00fffff
        s1 = r1 * 5; s2 = r2 * 5; s3 = r3 * 5; s4 = r4 * 5
        for (i in 0 until 4) pad[i] = u32(key, 16 + 4 * i)
    }

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Poly1305 {
        check(!finished) { "tag already taken" }
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        var i = offset
        val end = offset + length
        if (bufLen > 0) {
            val n = minOf(16 - bufLen, end - i)
            data.copyInto(buf, bufLen, i, i + n)
            bufLen += n
            i += n
            if (bufLen == 16) { block(buf, 0, HIBIT); bufLen = 0 }
        }
        while (end - i >= 16) { block(data, i, HIBIT); i += 16 }
        if (i < end) { data.copyInto(buf, 0, i, end); bufLen = end - i }
        return this
    }

    fun finish(): ByteArray {
        check(!finished) { "tag already taken" }
        finished = true
        if (bufLen > 0) {
            // A final partial block: the message bytes, then 0x01, then zeros, without the 2^128 bit.
            buf[bufLen] = 1
            for (k in bufLen + 1 until 16) buf[k] = 0
            block(buf, 0, 0L)
        }
        var c: Long
        c = h1 ushr 26; h1 = h1 and M26; h2 += c
        c = h2 ushr 26; h2 = h2 and M26; h3 += c
        c = h3 ushr 26; h3 = h3 and M26; h4 += c
        c = h4 ushr 26; h4 = h4 and M26; h0 += c * 5
        c = h0 ushr 26; h0 = h0 and M26; h1 += c
        // g = h + 5 - 2^130; use g when it does not go negative (h >= p).
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and M26
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and M26
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and M26
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and M26
        val g4 = h4 + c - (1L shl 26)
        val useH = g4 shr 63 // all ones when g4 < 0 (keep h), zero otherwise
        val useG = useH.inv()
        h0 = (h0 and useH) or (g0 and useG)
        h1 = (h1 and useH) or (g1 and useG)
        h2 = (h2 and useH) or (g2 and useG)
        h3 = (h3 and useH) or (g3 and useG)
        h4 = (h4 and useH) or (g4 and useG)
        // h mod 2^128, plus s.
        val w0 = (h0 or (h1 shl 26)) and M32
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and M32
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and M32
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and M32
        val out = ByteArray(16)
        var f = w0 + pad[0]; ChaCha20Poly1305.putLe32(out, 0, f.toInt())
        f = w1 + pad[1] + (f ushr 32); ChaCha20Poly1305.putLe32(out, 4, f.toInt())
        f = w2 + pad[2] + (f ushr 32); ChaCha20Poly1305.putLe32(out, 8, f.toInt())
        f = w3 + pad[3] + (f ushr 32); ChaCha20Poly1305.putLe32(out, 12, f.toInt())
        return out
    }

    private fun block(m: ByteArray, at: Int, hibit: Long) {
        h0 += u32(m, at) and M26
        h1 += (u32(m, at + 3) ushr 2) and M26
        h2 += (u32(m, at + 6) ushr 4) and M26
        h3 += (u32(m, at + 9) ushr 6) and M26
        h4 += (u32(m, at + 12) ushr 8) or hibit
        val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
        var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
        var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
        var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
        var c = d0 ushr 26; h0 = d0 and M26
        d1 += c; c = d1 ushr 26; h1 = d1 and M26
        d2 += c; c = d2 ushr 26; h2 = d2 and M26
        d3 += c; c = d3 ushr 26; h3 = d3 and M26
        d4 += c; c = d4 ushr 26; h4 = d4 and M26
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and M26
        h1 += c
    }

    companion object {
        private const val M26 = 0x3ffffffL
        private const val M32 = 0xffffffffL
        private const val HIBIT = 1L shl 24

        private fun u32(b: ByteArray, at: Int): Long = ChaCha20Poly1305.le32(b, at).toLong() and M32

        /** The tag of [message] under the one-time [key]. */
        fun mac(key: ByteArray, message: ByteArray): ByteArray = Poly1305(key).update(message).finish()
    }
}
