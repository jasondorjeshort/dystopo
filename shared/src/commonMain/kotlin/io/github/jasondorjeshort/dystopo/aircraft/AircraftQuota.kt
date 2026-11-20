package io.github.jasondorjeshort.dystopo.aircraft

/**
 * T311: the live feed's requests the phone spent today (the feed counts per IP and UTC day: 500 without a key), and the
 * pause a 429 asks for. The count is the phone's own, so the remaining figure is "about" (other devices behind the same
 * address spend the same allowance). Stored on the phone as [encode] (a count and a day, no position, no feed data).
 * Pure: every time is epoch ms passed in.
 */
class AircraftQuota(var day: Long = -1, var spent: Int = 0, var limitedUntilMs: Long = 0) {
    /** Counts one request at [nowMs], starting a new count when the UTC day changed. */
    fun spend(nowMs: Long) {
        roll(nowMs)
        spent++
    }

    /** Requests left today by the phone's own count (never below 0). */
    fun left(nowMs: Long, daily: Int = AircraftRules.DAILY_REQUESTS): Int {
        roll(nowMs)
        return (daily - spent).coerceAtLeast(0)
    }

    /** A 429 at [nowMs]: no request until Retry-After ([retryAfterS]) passed, else until the next UTC midnight. */
    fun limited(nowMs: Long, retryAfterS: Long?) {
        roll(nowMs)
        limitedUntilMs = if (retryAfterS != null && retryAfterS > 0) nowMs + retryAfterS * 1000 else (dayOf(nowMs) + 1) * DAY_MS
    }

    /** Whether a 429's pause still holds at [nowMs]. */
    fun isLimited(nowMs: Long): Boolean = nowMs < limitedUntilMs

    private fun roll(nowMs: Long) {
        val d = dayOf(nowMs)
        if (d != day) {
            day = d
            spent = 0
        }
    }

    fun encode(): String = "$day:$spent:$limitedUntilMs"

    fun json(nowMs: Long): Map<String, Any?> = linkedMapOf(
        "utcDay" to dayOf(nowMs), "spentToday" to (if (dayOf(nowMs) == day) spent else 0), "leftToday" to left(nowMs),
        "daily" to AircraftRules.DAILY_REQUESTS, "limited" to isLimited(nowMs),
        "limitedForS" to if (isLimited(nowMs)) (limitedUntilMs - nowMs + 999) / 1000 else 0,
    )

    companion object {
        const val DAY_MS = 86_400_000L

        fun dayOf(ms: Long): Long = ms.floorDiv(DAY_MS)

        fun decode(s: String?): AircraftQuota {
            val f = s?.split(':') ?: return AircraftQuota()
            if (f.size != 3) return AircraftQuota()
            return AircraftQuota(f[0].toLongOrNull() ?: -1, f[1].toIntOrNull() ?: 0, f[2].toLongOrNull() ?: 0)
        }

        /** A Retry-After header's seconds (the delta form; an HTTP date is not parsed and reads as null). */
        fun retryAfter(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    }
}
