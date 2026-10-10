package io.github.jasondorjeshort.dystopo.sync.crypto

import io.github.jasondorjeshort.dystopo.packs.Sha256

/**
 * The code two phones share to sync (T72): the LAN link and the server relay both use one. It is 16
 * characters of Crockford base32 (`0-9`, `A-Z` without `I L O U`), 80 random bits, shown in groups of four
 * (`7K3M-QW2D-9XHP-T4CA`). Typing is forgiving: case, spaces and dashes do not matter, and `I`/`L` read
 * as `1`, `O` as `0`.
 *
 * Why 80 bits and not the 6 digits of T54: the code is the only secret. Anyone holding one encrypted
 * message (a LAN eavesdropper, the relay server itself) can try codes offline as fast as they can run
 * HKDF, about a million per second per core; six digits fall in a second, 80 bits take longer than the
 * age of the universe on any hardware. See docs/PLANNING.md, Sync, Threat model.
 *
 * Key derivation ([key]): HKDF-SHA256 (RFC 5869) with
 * - IKM = the canonical code as ASCII (16 bytes),
 * - salt = a fresh random 16-byte salt per message (the per-session nonce, sent in the clear),
 * - info = `dystopo seal v1 <purpose>` (so a key for one purpose never opens another),
 * - 44 bytes of output: the ChaCha20-Poly1305 key (32) then its nonce (12).
 * A fresh salt per message gives a fresh key and nonce, so no key and nonce pair is ever reused.
 *
 * The relay slot ([slot]) is HKDF-SHA256 with a fixed salt (`dystopo relay slot v1`) and info `slot`,
 * 16 bytes as hex: the server files blobs under it without learning the code.
 */
object ShareCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 16
    const val BITS = 80

    /** A new code from 10 random bytes of [random] (a cryptographic source: SecureRandom on the JVM). */
    fun generate(random: (Int) -> ByteArray): String {
        val b = random(BITS / 8)
        require(b.size == BITS / 8) { "random source returned ${b.size} bytes" }
        val sb = StringBuilder(LENGTH)
        var acc = 0L
        var bits = 0
        for (x in b) {
            acc = (acc shl 8) or (x.toLong() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[((acc ushr bits) and 31).toInt()])
            }
        }
        return sb.toString()
    }

    /** The canonical 16-character code of [input], or null when it is not one (wrong length or a character outside the alphabet). */
    fun normalize(input: String?): String? {
        if (input == null) return null
        val sb = StringBuilder(LENGTH)
        for (ch in input) {
            val c = when (val u = ch.uppercaseChar()) {
                ' ', '-', '\t' -> continue
                'I', 'L' -> '1'
                'O' -> '0'
                else -> u
            }
            if (c !in ALPHABET) return null
            sb.append(c)
            if (sb.length > LENGTH) return null
        }
        return sb.toString().takeIf { it.length == LENGTH }
    }

    /** Groups of four for the screen: `7K3M-QW2D-9XHP-T4CA`. */
    fun format(code: String): String = code.chunked(4).joinToString("-")

    private fun canonical(code: String): ByteArray =
        (normalize(code) ?: throw IllegalArgumentException("a share code is $LENGTH characters of 0-9 and A-Z (no I, L, O, U)")).encodeToByteArray()

    /** The key (32 bytes) and nonce (12 bytes) for one message under [code], [salt] and [purpose]. */
    fun key(code: String, salt: ByteArray, purpose: String): Pair<ByteArray, ByteArray> {
        require(salt.size >= 16) { "salt must be at least 16 bytes" }
        val okm = Hkdf.derive(salt, canonical(code), "dystopo seal v1 $purpose".encodeToByteArray(), ChaCha20Poly1305.KEY_BYTES + ChaCha20Poly1305.NONCE_BYTES)
        return okm.copyOf(ChaCha20Poly1305.KEY_BYTES) to okm.copyOfRange(ChaCha20Poly1305.KEY_BYTES, okm.size)
    }

    /** The relay slot of [code]: 32 hex digits. */
    fun slot(code: String): String = Sha256.toHex(Hkdf.derive(SLOT_SALT, canonical(code), "slot".encodeToByteArray(), 16))

    private val SLOT_SALT = "dystopo relay slot v1".encodeToByteArray()
}
