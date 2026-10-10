package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.sync.crypto.ChaCha20Poly1305
import io.github.jasondorjeshort.dystopo.sync.crypto.ShareCode

/**
 * End-to-end encrypted framing for sync data (T72): what the server relay stores and what the LAN link
 * carries. A frame is
 *
 *     "DSEAL" | version (1) | purpose (1 byte) | salt (16 bytes) | ChaCha20-Poly1305 ciphertext | tag (16 bytes)
 *
 * The key and nonce come from the share code and the frame's random salt ([ShareCode.key], HKDF-SHA256,
 * a fresh salt per frame). The associated data is the 23-byte header plus an optional [context] the
 * caller binds in (a LAN reply binds the salt of the request it answers, so a reply cannot be replayed
 * against another request). Whoever lacks the code sees only the purpose, the salt and the length.
 *
 * Purposes: [Purpose.RELAY] holds a gzipped [Bundle] (`Sync.pack`); [Purpose.LAN_REQUEST] and
 * [Purpose.LAN_RESPONSE] hold one HTTP request or reply of the LAN link
 * ([io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink]). A frame opened under the wrong purpose
 * fails like a wrong code.
 *
 * Every failure is a [SealException]: [SealException.authFailed] for a wrong code or changed bytes
 * (indistinguishable by design), false for a frame that is not one (truncated, wrong magic or version).
 */
object EncryptedBundle {
    const val MAGIC = "DSEAL"
    const val VERSION = 1
    const val SALT_BYTES = 16
    const val HEADER_BYTES = 5 + 1 + 1 + SALT_BYTES

    /** Bytes a frame adds to its plaintext. */
    const val OVERHEAD = HEADER_BYTES + ChaCha20Poly1305.TAG_BYTES

    enum class Purpose(val id: Int, val label: String) {
        RELAY(1, "relay bundle"),
        LAN_REQUEST(2, "lan request"),
        LAN_RESPONSE(3, "lan response"),
    }

    class SealException(message: String, val authFailed: Boolean) : IllegalArgumentException(message)

    /** True when [bytes] start like a frame (magic and version); says nothing about the code. */
    fun isSealed(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_BYTES && MAGIC.indices.all { bytes[it].toInt() == MAGIC[it].code } && bytes[5].toInt() == VERSION

    /** The frame's purpose, or null when it is not a frame or names an unknown purpose. */
    fun purpose(frame: ByteArray): Purpose? = if (isSealed(frame)) Purpose.entries.firstOrNull { it.id == frame[6].toInt() } else null

    /** The frame's salt (its per-message nonce). */
    fun salt(frame: ByteArray): ByteArray {
        if (!isSealed(frame)) throw SealException("not an encrypted frame", authFailed = false)
        return frame.copyOfRange(7, HEADER_BYTES)
    }

    /** Encrypts [plaintext] under [code] with a fresh salt from [random] (a cryptographic source). */
    fun seal(code: String, purpose: Purpose, plaintext: ByteArray, random: (Int) -> ByteArray, context: ByteArray = ByteArray(0)): ByteArray {
        val salt = random(SALT_BYTES)
        require(salt.size == SALT_BYTES) { "random source returned ${salt.size} bytes" }
        val header = ByteArray(HEADER_BYTES)
        MAGIC.forEachIndexed { i, c -> header[i] = c.code.toByte() }
        header[5] = VERSION.toByte()
        header[6] = purpose.id.toByte()
        salt.copyInto(header, 7)
        val (key, nonce) = ShareCode.key(code, salt, purpose.label)
        return header + ChaCha20Poly1305.seal(key, nonce, plaintext, header + context)
    }

    /** Verifies and decrypts [frame]; throws [SealException]. */
    fun open(code: String, purpose: Purpose, frame: ByteArray, context: ByteArray = ByteArray(0)): ByteArray {
        if (!isSealed(frame)) throw SealException("not an encrypted frame (no $MAGIC v$VERSION header)", authFailed = false)
        if (frame.size < OVERHEAD) throw SealException("encrypted frame truncated (${frame.size} bytes)", authFailed = false)
        if (frame[6].toInt() != purpose.id) throw SealException("wrong code or data", authFailed = true)
        val header = frame.copyOf(HEADER_BYTES)
        val (key, nonce) = ShareCode.key(code, header.copyOfRange(7, HEADER_BYTES), purpose.label)
        return try {
            ChaCha20Poly1305.open(key, nonce, frame.copyOfRange(HEADER_BYTES, frame.size), header + context)
        } catch (e: ChaCha20Poly1305.AuthenticationException) {
            throw SealException("wrong code or data", authFailed = true)
        }
    }

    /** A bundle for the relay: [Sync.pack] (gzip) encrypted under [code]. */
    fun sealBundle(bundle: Bundle, code: String, random: (Int) -> ByteArray, gzip: (ByteArray) -> ByteArray): ByteArray =
        seal(code, Purpose.RELAY, Sync.pack(bundle, gzip), random)

    /** The bundle in a relay frame; [SealException] for a wrong code, [SyncFormatException] when the decrypted bytes are not a bundle. */
    fun openBundle(frame: ByteArray, code: String, gunzip: (ByteArray) -> ByteArray): Bundle =
        Sync.unpack(open(code, Purpose.RELAY, frame), gunzip)
}
