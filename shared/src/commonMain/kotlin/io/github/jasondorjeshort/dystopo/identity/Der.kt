package io.github.jasondorjeshort.dystopo.identity

/**
 * The one exception every reader in this package throws on malformed, truncated or oversized input (ADR 0051:
 * chains and records come from strangers, so the parsers have bounded lengths and one failure type). Callers that
 * judge a chain catch it and refuse with [Reason.MALFORMED]; nothing in this package accepts after it.
 */
class AttestationFormatException(message: String) : Exception(message)

internal fun malformed(message: String): Nothing = throw AttestationFormatException(message)

/**
 * One DER element (ITU-T X.690): its identifier and where its content lies in [buf]. Only definite lengths are
 * read; anything else is an [AttestationFormatException]. Non-minimal lengths and integers and BER booleans are
 * tolerated, as Java's certificate parser tolerates them: Keystore implementations differ, and refusing a phone over
 * an encoding detail would decide nothing about trust (signatures cover the bytes as they are).
 */
class DerElement internal constructor(
    val buf: ByteArray,
    /** 0 universal, 1 application, 2 context-specific, 3 private. */
    val tagClass: Int,
    val constructed: Boolean,
    val tag: Int,
    /** Where the identifier starts. */
    val start: Int,
    val contentStart: Int,
    val end: Int,
    internal val depth: Int,
) {
    val contentLength: Int get() = end - contentStart

    fun content(): ByteArray = buf.copyOfRange(contentStart, end)

    /** The whole element, identifier and length included. */
    fun encoded(): ByteArray = buf.copyOfRange(start, end)

    fun isUniversal(tag: Int, constructed: Boolean = false) = tagClass == UNIVERSAL && this.tag == tag && this.constructed == constructed

    fun isContext(tag: Int) = tagClass == CONTEXT && this.tag == tag

    /** A reader over this constructed element's children. */
    fun reader(): DerReader {
        if (!constructed) malformed("expected a constructed element, found primitive tag $tag")
        if (depth + 1 > DerReader.MAX_DEPTH) malformed("nesting deeper than ${DerReader.MAX_DEPTH}")
        return DerReader(buf, contentStart, end, depth + 1)
    }

    /** Every child of this constructed element, at most [DerReader.MAX_ITEMS]. */
    fun children(): List<DerElement> = reader().readAll()

    fun expectSequence(what: String): DerElement = apply { if (!isUniversal(SEQUENCE, true)) malformed("$what: expected a SEQUENCE, found ${describe()}") }

    /** INTEGER or ENUMERATED as a signed 64-bit value (attestation fields all fit; longer is malformed). */
    fun asLong(what: String): Long {
        if (!(isUniversal(INTEGER) || isUniversal(ENUMERATED))) malformed("$what: expected an INTEGER, found ${describe()}")
        val n = contentLength
        if (n < 1 || n > 8) malformed("$what: integer of $n bytes")
        var v = buf[contentStart].toLong() // sign-extended
        for (i in contentStart + 1 until end) v = (v shl 8) or (buf[i].toLong() and 0xff)
        return v
    }

    fun asInt(what: String): Int {
        val v = asLong(what)
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) malformed("$what: $v out of range")
        return v.toInt()
    }

    /** An INTEGER of any length as its magnitude, leading zero octets dropped (certificate serials). */
    fun asUnsignedBytes(what: String): ByteArray {
        if (!isUniversal(INTEGER)) malformed("$what: expected an INTEGER, found ${describe()}")
        if (contentLength < 1 || contentLength > MAX_INTEGER_BYTES) malformed("$what: integer of $contentLength bytes")
        var s = contentStart
        while (s < end - 1 && buf[s].toInt() == 0) s++
        return buf.copyOfRange(s, end)
    }

    fun asOctets(what: String, max: Int = DerReader.MAX_ELEMENT): ByteArray {
        if (!isUniversal(OCTET_STRING)) malformed("$what: expected an OCTET STRING, found ${describe()}")
        if (contentLength > max) malformed("$what: $contentLength bytes, at most $max")
        return content()
    }

    fun asBoolean(what: String): Boolean {
        if (!isUniversal(BOOLEAN) || contentLength != 1) malformed("$what: expected a BOOLEAN, found ${describe()}")
        // BER's rule (any non-zero octet is true): some Keystore implementations write 0x01, and leniency here
        // decides nothing about trust.
        return buf[contentStart].toInt() != 0
    }

    fun asNull(what: String) {
        if (!isUniversal(NULL) || contentLength != 0) malformed("$what: expected NULL, found ${describe()}")
    }

    /** A BIT STRING with no unused bits, as its octets (keys and signatures). */
    fun asBitStringOctets(what: String): ByteArray {
        if (!isUniversal(BIT_STRING) || contentLength < 1) malformed("$what: expected a BIT STRING, found ${describe()}")
        if (buf[contentStart].toInt() != 0) malformed("$what: BIT STRING with unused bits")
        return buf.copyOfRange(contentStart + 1, end)
    }

    fun asOid(what: String): String {
        if (!isUniversal(OID) || contentLength < 1 || contentLength > 64) malformed("$what: expected an OBJECT IDENTIFIER, found ${describe()}")
        val parts = ArrayList<Long>()
        var v = 0L
        var fresh = true
        for (i in contentStart until end) {
            val b = buf[i].toInt() and 0xff
            if (fresh && b == 0x80) malformed("$what: OID arc not minimal")
            if (v > (Long.MAX_VALUE shr 7)) malformed("$what: OID arc too large")
            v = (v shl 7) or (b and 0x7f).toLong()
            fresh = b and 0x80 == 0
            if (fresh) {
                if (parts.isEmpty()) {
                    val first = if (v < 40) 0L else if (v < 80) 1L else 2L
                    parts += first
                    parts += v - first * 40
                } else {
                    parts += v
                }
                v = 0
            }
        }
        if (!fresh) malformed("$what: OID truncated")
        return parts.joinToString(".")
    }

    /** UTCTime or GeneralizedTime (the `Z` forms DER allows) as epoch milliseconds. */
    fun asTime(what: String): Long {
        val s = ascii(what, 32)
        return when {
            isUniversal(UTC_TIME) -> {
                if (s.length != 13 || !s.endsWith("Z")) malformed("$what: UTCTime '$s'")
                val yy = digits(s, 0, 2, what)
                Der.epochMillis(if (yy >= 50) 1900 + yy else 2000 + yy, digits(s, 2, 2, what), digits(s, 4, 2, what), digits(s, 6, 2, what), digits(s, 8, 2, what), digits(s, 10, 2, what), what)
            }
            isUniversal(GENERALIZED_TIME) -> {
                if (s.length != 15 || !s.endsWith("Z")) malformed("$what: GeneralizedTime '$s'")
                Der.epochMillis(digits(s, 0, 4, what), digits(s, 4, 2, what), digits(s, 6, 2, what), digits(s, 8, 2, what), digits(s, 10, 2, what), digits(s, 12, 2, what), what)
            }
            else -> malformed("$what: expected a time, found ${describe()}")
        }
    }

    /** A string type (UTF8String, PrintableString, IA5String) or an OCTET STRING holding UTF-8. */
    fun asText(what: String, max: Int = 1024): String {
        val ok = tagClass == UNIVERSAL && !constructed && tag in setOf(UTF8_STRING, PRINTABLE_STRING, IA5_STRING, OCTET_STRING)
        if (!ok) malformed("$what: expected a string, found ${describe()}")
        if (contentLength > max) malformed("$what: $contentLength bytes, at most $max")
        return try {
            buf.decodeToString(contentStart, end, throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            malformed("$what: not UTF-8")
        }
    }

    private fun ascii(what: String, max: Int): String {
        if (constructed || contentLength > max) malformed("$what: bad time")
        val sb = StringBuilder()
        for (i in contentStart until end) {
            val c = buf[i].toInt() and 0xff
            if (c < 0x20 || c > 0x7e) malformed("$what: bad time")
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun digits(s: String, at: Int, n: Int, what: String): Int {
        var v = 0
        for (i in at until at + n) {
            val c = s[i]
            if (c !in '0'..'9') malformed("$what: bad time '$s'")
            v = v * 10 + (c - '0')
        }
        return v
    }

    fun describe(): String = "${listOf("universal", "application", "context", "private")[tagClass]} ${if (constructed) "constructed" else "primitive"} tag $tag"

    companion object {
        const val UNIVERSAL = 0
        const val CONTEXT = 2
        const val BOOLEAN = 1
        const val INTEGER = 2
        const val BIT_STRING = 3
        const val OCTET_STRING = 4
        const val NULL = 5
        const val OID = 6
        const val ENUMERATED = 10
        const val UTF8_STRING = 12
        const val SEQUENCE = 16
        const val SET = 17
        const val PRINTABLE_STRING = 19
        const val IA5_STRING = 22
        const val UTC_TIME = 23
        const val GENERALIZED_TIME = 24

        /** Certificate serials are at most 20 octets (RFC 5280); one more for a sign octet, with slack. */
        const val MAX_INTEGER_BYTES = 32
    }
}

/**
 * Reads DER elements from `buf[pos until end]`. Every length is checked against what is left, so a hostile
 * length can never read past the input; nesting and item counts are capped ([MAX_DEPTH], [MAX_ITEMS]).
 */
class DerReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size, private val depth: Int = 0) {
    init {
        if (pos < 0 || end > buf.size || pos > end) malformed("bad bounds")
    }

    val hasMore: Boolean get() = pos < end

    fun next(): DerElement {
        if (pos >= end) malformed("unexpected end of data")
        val start = pos
        val id = buf[pos++].toInt() and 0xff
        val tagClass = id ushr 6
        val constructed = id and 0x20 != 0
        var tag = id and 0x1f
        if (tag == 0x1f) {
            tag = 0
            var n = 0
            while (true) {
                if (pos >= end) malformed("truncated tag")
                val b = buf[pos++].toInt() and 0xff
                if (n == 0 && b == 0x80) malformed("tag not minimal")
                if (++n > 4) malformed("tag too large")
                tag = (tag shl 7) or (b and 0x7f)
                if (b and 0x80 == 0) break
            }
            if (tag < 0x1f) malformed("tag not minimal")
        }
        if (pos >= end) malformed("truncated length")
        val first = buf[pos++].toInt() and 0xff
        val length: Int = when {
            first < 0x80 -> first
            first == 0x80 -> malformed("indefinite length is not DER")
            else -> {
                val n = first and 0x7f
                if (n > 3) malformed("length of $n octets")
                if (end - pos < n) malformed("truncated length")
                var v = 0
                for (i in 0 until n) v = (v shl 8) or (buf[pos++].toInt() and 0xff)
                v
            }
        }
        if (length > MAX_ELEMENT) malformed("element of $length bytes, at most $MAX_ELEMENT")
        if (length > end - pos) malformed("element of $length bytes, only ${end - pos} left")
        val contentStart = pos
        pos += length
        return DerElement(buf, tagClass, constructed, tag, start, contentStart, pos, depth)
    }

    fun nextOrNull(): DerElement? = if (hasMore) next() else null

    fun readAll(): List<DerElement> {
        val out = ArrayList<DerElement>()
        while (hasMore) {
            if (out.size >= MAX_ITEMS) malformed("more than $MAX_ITEMS items")
            out += next()
        }
        return out
    }

    /** Fails unless every byte has been read (no trailing data after a top-level element). */
    fun finish(what: String) {
        if (pos != end) malformed("$what: ${end - pos} trailing bytes")
    }

    companion object {
        /** Deepest nesting the readers follow; a certificate and the key description need under 12. */
        const val MAX_DEPTH = 24

        /** Most children of one constructed element. */
        const val MAX_ITEMS = 256

        /** Largest single element (a whole certificate is one); attestation certificates are about 1 to 2 KiB. */
        const val MAX_ELEMENT = 64 * 1024

        /** Reads exactly one element from [bytes] with nothing after it. */
        fun single(bytes: ByteArray, what: String): DerElement {
            val r = DerReader(bytes)
            val e = r.next()
            r.finish(what)
            return e
        }
    }
}

/** DER encoding helpers, for the records this package signs and for tests that mint certificates. */
object Der {
    fun tlv(identifier: Int, content: ByteArray): ByteArray = header(byteArrayOf(identifier.toByte()), content)

    /** A context-specific constructed [n] EXPLICIT wrapper (high tag numbers included, as the key description uses). */
    fun explicit(n: Int, inner: ByteArray): ByteArray = header(identifier(CONTEXT_CONSTRUCTED, n), inner)

    /** A context-specific primitive [n] IMPLICIT element. */
    fun implicit(n: Int, content: ByteArray): ByteArray = header(identifier(0x80, n), content)

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts.toList()))

    fun sequence(parts: List<ByteArray>): ByteArray = tlv(0x30, concat(parts))

    /** SET OF in DER order (members sorted by their encodings). */
    fun setOf(parts: List<ByteArray>): ByteArray = tlv(0x31, concat(parts.sortedWith(::compareBytes)))

    fun integer(v: Long): ByteArray {
        var n = 8
        while (n > 1) {
            val top = (v shr (8 * (n - 1))).toInt() and 0xff
            val next = (v shr (8 * (n - 2))).toInt() and 0x80
            if ((top == 0 && next == 0) || (top == 0xff && next != 0)) n-- else break
        }
        return tlv(0x02, ByteArray(n) { i -> (v shr (8 * (n - 1 - i))).toByte() })
    }

    fun enumerated(v: Long): ByteArray = integer(v).also { it[0] = 0x0a }

    /** A non-negative INTEGER from its magnitude. */
    fun unsignedInteger(magnitude: ByteArray): ByteArray {
        var s = 0
        while (s < magnitude.size - 1 && magnitude[s].toInt() == 0) s++
        val m = if (magnitude.isEmpty()) byteArrayOf(0) else magnitude.copyOfRange(s, magnitude.size)
        return tlv(0x02, if (m[0].toInt() and 0x80 != 0) byteArrayOf(0) + m else m)
    }

    fun octets(b: ByteArray): ByteArray = tlv(0x04, b)

    fun boolean(v: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (v) -1 else 0))

    fun nullValue(): ByteArray = tlv(0x05, ByteArray(0))

    fun utf8(s: String): ByteArray = tlv(0x0c, s.encodeToByteArray())

    fun bitString(b: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + b)

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2) { "OID needs two arcs" }
        val out = ArrayList<Byte>()
        fun arc(v: Long) {
            val tmp = ArrayList<Byte>()
            var x = v
            tmp += (x and 0x7f).toByte()
            x = x shr 7
            while (x > 0) {
                tmp += ((x and 0x7f) or 0x80).toByte()
                x = x shr 7
            }
            out += tmp.reversed()
        }
        arc(arcs[0] * 40 + arcs[1])
        for (a in arcs.drop(2)) arc(a)
        return tlv(0x06, out.toByteArray())
    }

    /** UTCTime for 1950 to 2049, GeneralizedTime otherwise (RFC 5280 4.1.2.5). */
    fun time(epochMillis: Long): ByteArray {
        val c = civil(epochMillis)
        fun two(v: Int) = v.toString().padStart(2, '0')
        val rest = two(c[1]) + two(c[2]) + two(c[3]) + two(c[4]) + two(c[5]) + "Z"
        return if (c[0] in 1950..2049) tlv(0x17, (two(c[0] % 100) + rest).encodeToByteArray())
        else tlv(0x18, (c[0].toString().padStart(4, '0') + rest).encodeToByteArray())
    }

    fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** Epoch milliseconds of a UTC civil time (days from civil, H. Hinnant's algorithm). */
    fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, what: String = "time"): Long {
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) malformed("$what: out of range")
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era.toLong() * 146097 + doe - 719468
        return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L
    }

    /** [year, month, day, hour, minute, second] in UTC of epoch milliseconds. */
    fun civil(epochMillis: Long): IntArray {
        val secs = floorDiv(epochMillis, 1000L)
        val days = floorDiv(secs, 86_400L)
        val sod = (secs - days * 86_400L).toInt()
        val z = days + 719468
        val era = floorDiv(z, 146097L)
        val doe = (z - era * 146097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = (yoe + era * 400).toInt() + if (m <= 2) 1 else 0
        return intArrayOf(y, m, d, sod / 3600, sod / 60 % 60, sod % 60)
    }

    /** ISO-8601 UTC text of epoch milliseconds, for reports. */
    fun iso(epochMillis: Long): String {
        val c = civil(epochMillis)
        fun two(v: Int) = v.toString().padStart(2, '0')
        return "${c[0].toString().padStart(4, '0')}-${two(c[1])}-${two(c[2])}T${two(c[3])}:${two(c[4])}:${two(c[5])}Z"
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    private const val CONTEXT_CONSTRUCTED = 0xa0

    private fun identifier(base: Int, n: Int): ByteArray {
        if (n < 0x1f) return byteArrayOf((base or n).toByte())
        val tmp = ArrayList<Byte>()
        var x = n
        tmp += (x and 0x7f).toByte()
        x = x shr 7
        while (x > 0) {
            tmp += ((x and 0x7f) or 0x80).toByte()
            x = x shr 7
        }
        return byteArrayOf((base or 0x1f).toByte()) + tmp.reversed().toByteArray()
    }

    private fun header(identifier: ByteArray, content: ByteArray): ByteArray {
        val n = content.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return identifier + len + content
    }
}

/** Lowercase hex of bytes. */
internal fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(digits[v shr 4]).append(digits[v and 15])
    }
    return sb.toString()
}
