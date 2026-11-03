package io.github.jasondorjeshort.dystopo.identity

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * Google's attestation revocation list (`https://android.googleapis.com/attestation/status`), parsed (ADR 0051).
 *
 * The documented keys are lowercase hex serials, but the live file keys most entries by decimal strings (1,769
 * entries on 2026-11-02, 788 of them with hex letters). Every key is normalised to the serial's hex form before
 * matching: a key with a hex letter is hex; a key of digits only is taken both ways (as decimal and as hex), since
 * the file does not say which. That can over-revoke only on a collision between one serial's decimal digits and
 * another's hex digits, which for 64-bit and larger random serials does not happen in practice; it never
 * under-revokes. Both statuses (`REVOKED`, `SUSPENDED`) refuse.
 *
 * [fetchedAt] is when this copy was fetched (the phone's cache keeps it); [ageMillis] is what the policy reads: a
 * stranger's chain needs a list younger than 30 days, a group member any age (shown).
 */
class RevocationList private constructor(
    private val serials: Set<String>,
    /** Entries in the file (before the digit-only keys were doubled). */
    val entries: Int,
    val fetchedAt: Long?,
) {
    fun isRevoked(serialHex: String): Boolean = canonical(serialHex) in serials

    fun ageMillis(now: Long): Long? = fetchedAt?.let { now - it }

    companion object {
        const val MAX_TEXT = 8 * 1024 * 1024
        const val MAX_ENTRIES = 200_000
        const val MAX_KEY = 80

        /** No list at all (never fetched). */
        val NONE = RevocationList(emptySet(), 0, null)

        /** Parses the status file's text; any bad key or shape is an [AttestationFormatException] (keep the old copy). */
        fun parse(json: String, fetchedAt: Long?): RevocationList {
            if (json.length > MAX_TEXT) malformed("revocation list of ${json.length} chars, at most $MAX_TEXT")
            val root = try {
                MiniJson.parseObject(json)
            } catch (e: Exception) {
                malformed("revocation list: ${e.message}")
            }
            val entries = root["entries"] as? Map<*, *> ?: malformed("revocation list: no entries object")
            if (entries.size > MAX_ENTRIES) malformed("revocation list of ${entries.size} entries, at most $MAX_ENTRIES")
            val out = HashSet<String>(entries.size * 2)
            for ((k, v) in entries) {
                val key = (k as String).trim().lowercase()
                if (v !is Map<*, *> || v["status"] !is String) malformed("revocation list: entry $key has no status")
                if (key.isEmpty() || key.length > MAX_KEY) malformed("revocation list: key '$key'")
                when {
                    key.all { it in '0'..'9' } -> {
                        out += canonical(key)
                        out += decimalToHex(key)
                    }
                    key.all { it in '0'..'9' || it in 'a'..'f' } -> out += canonical(key)
                    else -> malformed("revocation list: key '$key' is neither decimal nor hex")
                }
            }
            return RevocationList(out, entries.size, fetchedAt)
        }

        internal fun canonical(hex: String): String = hex.lowercase().trimStart('0').ifEmpty { "0" }

        /** A decimal string of any length as lowercase hex without leading zeros. */
        fun decimalToHex(decimal: String): String {
            val mag = ArrayList<Int>() // base 256, least significant first
            for (c in decimal) {
                require(c in '0'..'9') { "not decimal: $decimal" }
                var carry = c - '0'
                for (i in mag.indices) {
                    val v = mag[i] * 10 + carry
                    mag[i] = v and 0xff
                    carry = v shr 8
                }
                while (carry > 0) {
                    mag += carry and 0xff
                    carry = carry shr 8
                }
            }
            return canonical(ByteArray(mag.size) { mag[mag.size - 1 - it].toByte() }.hex())
        }
    }
}
