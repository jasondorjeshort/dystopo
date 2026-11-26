package io.github.jasondorjeshort.dystopo.app.readout

import android.location.Location
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/**
 * Why the readout's clock goes back: a log of every fix's own time against the phone's, and of every clock-back restart
 * with the input that caused it ("fix" or "pressure:sensor"). A GPS chip that has lost its time stamps fixes off the
 * phone's clock; the barometer's samples are stamped on the phone's clock plus the last fix's offset, so one bad fix
 * moves every sample after it. Logcat tag [TAG] (warnings, rate-limited); the last [MAX] entries and the counters are in
 * `GET /readout` `clockLog`.
 *
 * "chipVsPhoneMs" is the fix's own time minus the time the phone's clock implies for the moment the fix was taken (its
 * elapsed-realtime stamp carried back from now): near zero when the chip's clock is right.
 */
object ClockLog {
    const val TAG = "DystopoClock"
    const val MAX = 50
    /** A fix whose time is further than this from the phone's clock counts as off. */
    const val OFF_MS = 5_000L
    private const val LOG_EVERY_MS = 5_000L

    private val ring = ArrayDeque<Map<String, Any?>>()
    private val counts = linkedMapOf("fixes" to 0L, "fixesOff" to 0L, "fixesBackwards" to 0L, "readoutClockBacks" to 0L)
    private var lastFixTime = Long.MIN_VALUE
    private var off = false
    private var lastLoggedAt = 0L
    private var lastDeltaMs: Long? = null
    private var maxAbsDeltaMs = 0L

    /** One fix as the phone gave it (main thread). */
    @Synchronized fun onFix(loc: Location) {
        counts["fixes"] = counts.getValue("fixes") + 1
        val wall = System.currentTimeMillis()
        val ageMs = SystemClock.elapsedRealtime() - loc.elapsedRealtimeNanos / 1_000_000
        val delta = loc.time - (wall - ageMs)
        lastDeltaMs = delta
        maxAbsDeltaMs = maxOf(maxAbsDeltaMs, abs(delta))
        val backwards = lastFixTime != Long.MIN_VALUE && loc.time < lastFixTime
        if (backwards) counts["fixesBackwards"] = counts.getValue("fixesBackwards") + 1
        val nowOff = abs(delta) > OFF_MS
        if (nowOff) counts["fixesOff"] = counts.getValue("fixesOff") + 1
        val now = SystemClock.elapsedRealtime()
        if (nowOff != off || backwards || (nowOff && now - lastLoggedAt >= LOG_EVERY_MS)) {
            lastLoggedAt = now
            val e = linkedMapOf<String, Any?>(
                "event" to if (nowOff != off) (if (nowOff) "chipClockOff" else "chipClockBack") else if (backwards) "fixBackwards" else "chipClockStillOff",
                "atElapsedMs" to now, "provider" to loc.provider, "chipTimeMs" to loc.time, "phoneWallMs" to wall,
                "chipVsPhoneMs" to delta, "fixAgeMs" to ageMs, "previousFixMs" to lastFixTime.takeIf { it != Long.MIN_VALUE },
                "accuracyM" to if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            )
            add(e)
        }
        off = nowOff
        lastFixTime = loc.time
    }

    /** A clock-back restart in the readout's estimator (worker thread): which input, and by how much. */
    @Synchronized fun onClockBack(source: String, tMs: Long, latestMs: Long, elapsedMs: Long, offsetMs: Long) {
        counts["readoutClockBacks"] = counts.getValue("readoutClockBacks") + 1
        add(linkedMapOf("event" to "readoutClockBack", "atElapsedMs" to elapsedMs, "source" to source, "inputTimeMs" to tMs,
            "latestTimeMs" to latestMs, "backS" to (latestMs - tMs) / 1000.0, "fixClockOffsetMs" to offsetMs))
    }

    private fun add(e: Map<String, Any?>) {
        Log.w(TAG, e.entries.joinToString(" ") { "${it.key}=${it.value}" })
        ring.addLast(e)
        while (ring.size > MAX) ring.removeFirst()
    }

    @Synchronized fun json(): Map<String, Any?> = linkedMapOf(
        "counts" to LinkedHashMap(counts), "chipClockOff" to off, "lastChipVsPhoneMs" to lastDeltaMs, "maxAbsChipVsPhoneMs" to maxAbsDeltaMs,
        "offThresholdMs" to OFF_MS, "recent" to ring.toList(),
    )

    @Synchronized fun reset() { ring.clear(); counts.keys.forEach { counts[it] = 0L }; lastFixTime = Long.MIN_VALUE; off = false; lastDeltaMs = null; maxAbsDeltaMs = 0L }
}
