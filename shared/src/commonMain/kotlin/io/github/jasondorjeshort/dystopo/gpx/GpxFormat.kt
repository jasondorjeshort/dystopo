package io.github.jasondorjeshort.dystopo.gpx

/** Text forms of GPX values: ISO-8601 times and plain decimal numbers. Hand-written so `shared` stays pure Kotlin. */
object GpxFormat {
    /**
     * Parses an XML Schema `dateTime` (GPX `time`) to epoch milliseconds, UTC:
     * `2025-08-15T16:04:05Z`, with optional fractional seconds (`.5`, `.123456`; truncated to
     * milliseconds) and an offset (`Z`, `+02:00`, `-0700`). A time without an offset is taken as
     * UTC, which is what GPX 1.1 requires anyway. Returns null when [text] is not such a time.
     */
    fun parseTime(text: String): Long? {
        val t = text.trim()
        var i = 0
        fun digits(n: Int): Int? {
            if (i + n > t.length) return null
            var v = 0
            for (k in 0 until n) {
                val c = t[i + k]
                if (c !in '0'..'9') return null
                v = v * 10 + (c - '0')
            }
            i += n
            return v
        }
        fun expect(c: Char): Boolean = (i < t.length && t[i] == c).also { if (it) i++ }

        val negativeYear = expect('-')
        val year = digits(4)?.let { if (negativeYear) -it else it } ?: return null
        if (!expect('-')) return null
        val month = digits(2) ?: return null
        if (!expect('-')) return null
        val day = digits(2) ?: return null
        if (!expect('T') && !expect(' ')) return null
        val hour = digits(2) ?: return null
        if (!expect(':')) return null
        val minute = digits(2) ?: return null
        if (!expect(':')) return null
        val second = digits(2) ?: return null
        var millis = 0
        if (expect('.') || expect(',')) {
            var scale = 100
            val start = i
            while (i < t.length && t[i] in '0'..'9') {
                millis += (t[i] - '0') * scale
                scale /= 10
                i++
            }
            if (i == start) return null
        }
        var offsetMinutes = 0
        if (i < t.length) {
            when (t[i]) {
                'Z', 'z' -> i++
                '+', '-' -> {
                    val sign = if (t[i] == '-') -1 else 1
                    i++
                    val oh = digits(2) ?: return null
                    expect(':')
                    val om = if (i < t.length) digits(2) ?: return null else 0
                    offsetMinutes = sign * (oh * 60 + om)
                }
                else -> return null
            }
        }
        if (i != t.length) return null
        if (month !in 1..12 || day !in 1..daysInMonth(year, month) || hour > 24 || minute > 59 || second > 60) return null
        if (hour == 24 && (minute != 0 || second != 0 || millis != 0)) return null
        val days = daysFromCivil(year, month, day)
        // A leap second (:60) folds into the next second, as epoch time has no slot for it.
        val secs = days * 86_400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
        return secs * 1000L + millis
    }

    /** Formats epoch milliseconds as `2025-08-15T16:04:05Z`, or with `.123` when there are milliseconds. */
    fun formatTime(epochMillis: Long): String {
        val days = epochMillis.floorDiv(86_400_000L)
        val msOfDay = epochMillis - days * 86_400_000L
        val (y, m, d) = civilFromDays(days)
        val sb = StringBuilder()
        if (y < 0) sb.append('-')
        sb.append(pad(kotlin.math.abs(y), 4)).append('-').append(pad(m, 2)).append('-').append(pad(d, 2)).append('T')
        val secOfDay = (msOfDay / 1000).toInt()
        sb.append(pad(secOfDay / 3600, 2)).append(':').append(pad(secOfDay / 60 % 60, 2)).append(':').append(pad(secOfDay % 60, 2))
        val ms = (msOfDay % 1000).toInt()
        if (ms != 0) sb.append('.').append(pad(ms, 3))
        return sb.append('Z').toString()
    }

    private fun pad(v: Int, n: Int) = v.toString().padStart(n, '0')

    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun daysInMonth(y: Int, m: Int) = when (m) {
        2 -> if (isLeap(y)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (era-based; exact for any year). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12 // March = 0
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** Inverse of [daysFromCivil]: (year, month, day). */
    fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + (if (m <= 2) 1 else 0)).toInt()
        return Triple(y, m, d)
    }

    /**
     * A double as an `xsd:decimal`: the shortest text that reads back to the same value, never in
     * exponent notation (`1.0E-5` becomes `0.00001`) and without a trailing `.0`. Negative zero is written `0`.
     */
    fun formatDecimal(v: Double): String {
        require(v.isFinite()) { "not a finite number: $v" }
        val raw = v.toString()
        val e = raw.indexOfFirst { it == 'E' || it == 'e' }
        val plain = if (e < 0) raw else expandExponent(raw.substring(0, e), raw.substring(e + 1).toInt())
        return if (plain.endsWith(".0")) plain.dropLast(2).let { if (it == "-0") "0" else it } else plain
    }

    private fun expandExponent(mantissa: String, exp: Int): String {
        val negative = mantissa.startsWith('-')
        val m = mantissa.removePrefix("-")
        val dot = m.indexOf('.').let { if (it < 0) m.length else it }
        val digits = m.replace(".", "")
        val point = dot + exp // position of the decimal point within digits
        val body = when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        val trimmed = (if ('.' in body) body.trimEnd('0').trimEnd('.') else body).trimStart('0').let {
            if (it.isEmpty() || it.startsWith('.')) "0$it" else it
        }
        return if (negative && trimmed != "0") "-$trimmed" else trimmed
    }
}
