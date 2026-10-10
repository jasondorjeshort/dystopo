package io.github.jasondorjeshort.dystopo.app.sync

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * T57: the clock comparison with a sync peer that the user sees. The T51 skew guard only stops a clock far
 * ahead (over a day) from dragging the others along; edits still order by their timestamps, so a phone whose
 * clock is minutes or hours off can lose a newer edit to an older one. At every sync the phone reads the
 * peer's time (the HTTP `Date` header of the server's replies; the `now` field of a nearby phone's
 * `/share/hello`), and warns when the two clocks differ by more than [WARN_MS] beyond the measurement's
 * uncertainty (half the round trip, plus the `Date` header's one-second resolution).
 */
data class ClockSample(val peerMs: Long, val sentMs: Long, val receivedMs: Long, val resolutionMs: Long = 0) {
    val rttMs: Long get() = (receivedMs - sentMs).coerceAtLeast(0)
}

data class ClockCheck(
    /** Peer clock minus this phone's clock, ms (positive: this phone is behind). */
    val offsetMs: Long,
    val uncertaintyMs: Long,
    val peer: String,
    val at: Long,
    val samples: Int,
) {
    /** True when the clocks differ by more than [WARN_MS] even at the edge of the uncertainty. */
    val warn: Boolean get() = abs(offsetMs) - uncertaintyMs > WARN_MS

    /** One line for the sync sheet and the result message; null when the clocks agree. */
    fun message(): String? {
        if (!warn) return null
        val dir = if (offsetMs > 0) "behind" else "ahead of"
        return "This phone's clock is ${describe(abs(offsetMs))} $dir $peer's. Edits are ordered by time, so set the date and time automatically."
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "peer" to peer, "at" to at, "offsetMs" to offsetMs, "uncertaintyMs" to uncertaintyMs, "samples" to samples,
        "warn" to warn, "message" to message(),
    )

    companion object {
        /** Differences under this are left alone (minutes are what a hand-set clock is off by). */
        const val WARN_MS = 2 * 60_000L

        /** The best estimate from [samples]: the one with the shortest round trip. Null for none. */
        fun of(samples: List<ClockSample>, peer: String, at: Long): ClockCheck? {
            val s = samples.minByOrNull { it.rttMs } ?: return null
            // The peer read its clock somewhere in the round trip, and a Date header drops the milliseconds.
            val peerMid = s.peerMs + s.resolutionMs / 2
            val localMid = (s.sentMs + s.receivedMs) / 2
            return ClockCheck(peerMid - localMid, s.rttMs / 2 + s.resolutionMs / 2, peer, at, samples.size)
        }

        /** An HTTP `Date` header (RFC 1123), as epoch ms; null when it does not parse. */
        fun httpDate(value: String?): Long? = value?.let {
            runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        }

        /** "3 h 12 min", "5 min", "2 days 4 h". */
        fun describe(ms: Long): String {
            val min = ms / 60_000
            val h = min / 60
            val d = h / 24
            return when {
                d >= 1 -> "$d ${if (d == 1L) "day" else "days"}" + if (h % 24 > 0) " ${h % 24} h" else ""
                h >= 1 -> "$h h" + if (min % 60 > 0) " ${min % 60} min" else ""
                else -> "$min min"
            }
        }
    }
}

/** Collects clock samples from a sync's HTTP replies (thread-safe; one per request). */
class ClockSamples {
    private val list = ArrayList<ClockSample>()

    @Synchronized
    fun add(s: ClockSample) {
        list += s
    }

    @Synchronized
    fun all(): List<ClockSample> = list.toList()
}
