package io.github.jasondorjeshort.dystopo.model

import kotlin.random.Random

/**
 * Random (version 4) UUIDs as lowercase strings, `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx`.
 *
 * Every user entity (waypoint, track, route...) is identified by one of these, never by a
 * server-assigned id (see docs/PLANNING.md, offline-first data). The random source is injectable so
 * tests and imports can be made deterministic; the default is [Random.Default]. A collision needs
 * on the order of 2^61 ids, far beyond what a user's data or a sync group will hold.
 */
class UuidGenerator(private val random: Random = Random.Default) {
    fun next(): String = Uuid.format(random.nextBytes(16).also {
        it[6] = ((it[6].toInt() and 0x0f) or 0x40).toByte() // version 4
        it[8] = ((it[8].toInt() and 0x3f) or 0x80).toByte() // RFC 4122 variant
    })
}

object Uuid {
    private const val HEX = "0123456789abcdef"

    /** A new random UUID from [Random.Default]. */
    fun random(): String = UuidGenerator().next()

    /** Formats 16 bytes in the canonical 8-4-4-4-12 layout. */
    fun format(bytes: ByteArray): String {
        require(bytes.size == 16) { "a UUID is 16 bytes, got ${bytes.size}" }
        val sb = StringBuilder(36)
        for ((i, b) in bytes.withIndex()) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            val v = b.toInt() and 0xff
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** True for a canonical UUID string (any version; hex digits in either case). */
    fun isValid(s: String): Boolean {
        if (s.length != 36) return false
        for ((i, c) in s.withIndex()) {
            val dash = i == 8 || i == 13 || i == 18 || i == 23
            if (dash) { if (c != '-') return false } else if (c !in '0'..'9' && c.lowercaseChar() !in 'a'..'f') return false
        }
        return true
    }

    /** The version nibble of a valid UUID (4 for [UuidGenerator] ids). */
    fun version(s: String): Int = s[14].digitToInt(16)
}
