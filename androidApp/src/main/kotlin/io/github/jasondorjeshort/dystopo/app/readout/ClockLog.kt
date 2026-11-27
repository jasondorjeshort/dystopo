package io.github.jasondorjeshort.dystopo.app.readout

import android.location.Location
import android.location.LocationManager
import android.util.Log
import io.github.jasondorjeshort.dystopo.readout.GpsClockCheck
import io.github.jasondorjeshort.dystopo.readout.ReadoutClock
import kotlin.math.abs

/**
 * A log of every device fix's own time against the phone's, and of every clock-back restart with the input that caused
 * it ("fix" or "pressure:sensor"). Logcat tag [TAG] (warnings, rate-limited); the last [MAX] entries and the counters are
 * in `GET /readout` `clockLog`.
 *
 * "chipVsPhoneMs" is the fix's own time minus the time the phone's clock implies for the moment the fix was taken (its
 * elapsed-realtime stamp carried back from now): near zero when the two clocks agree.
 *
 * T385: the GPS clock check ([GpsClockCheck], on GPS fixes only: a network fix's time is the phone's own) flags a fix
 * further than [OFF_MS] off; the fix is still used, and the readout orders device inputs on elapsed realtime
 * ([ReadoutClock]), so a wrong time no longer restarts it. A fault (two flagged fixes in a row, ended by two good ones)
 * is logged once as it begins (`chipClockOff`, with its kind), rate-limited while it lasts, and once as it ends
 * (`chipClockBack`); [fault] and [ended] are what the glance view words. A fix whose elapsed stamp is missing, zero or in
 * the future is counted (`fixesElapsedMissing`) and taken at its receipt.
 */
object ClockLog {
    const val TAG = "DystopoClock"
    const val MAX = 50
    /** A fix whose time is further than this from the phone's clock is flagged. */
    const val OFF_MS = GpsClockCheck.OFF_MS
    private const val LOG_EVERY_MS = 5_000L

    private val ring = ArrayDeque<Map<String, Any?>>()
    private val counts = linkedMapOf(
        "fixes" to 0L, "fixesOff" to 0L, "fixesImplausible" to 0L, "fixesElapsedMissing" to 0L, "fixesBackwards" to 0L,
        "faults" to 0L, "readoutClockBacks" to 0L,
    )
    private val check = GpsClockCheck()
    private var lastFixTime = Long.MIN_VALUE
    private var lastLoggedAt = 0L
    private var lastDeltaMs: Long? = null
    private var maxAbsDeltaMs = 0L

    private fun bump(k: String) { counts[k] = counts.getValue(k) + 1 }

    /**
     * One device fix as the phone gave it (main thread), received at [nowElapsedMs] with the phone's clock at [nowWallMs]:
     * its verdict for a GPS fix, null for another provider's.
     */
    @Synchronized fun onFix(loc: Location, nowElapsedMs: Long, nowWallMs: Long): GpsClockCheck.Flag? {
        bump("fixes")
        val stamp = loc.elapsedRealtimeNanos / 1_000_000
        if (ReadoutClock.fixElapsed(stamp, nowElapsedMs) == null) bump("fixesElapsedMissing")
        val phone = GpsClockCheck.phoneTime(stamp, nowElapsedMs, nowWallMs)
        val delta = loc.time - phone
        lastDeltaMs = delta
        maxAbsDeltaMs = maxOf(maxAbsDeltaMs, abs(delta))
        val backwards = lastFixTime != Long.MIN_VALUE && loc.time < lastFixTime
        if (backwards) bump("fixesBackwards")
        val previous = lastFixTime.takeIf { it != Long.MIN_VALUE }
        lastFixTime = loc.time
        if ((loc.provider ?: LocationManager.GPS_PROVIDER) != LocationManager.GPS_PROVIDER) return null
        val before = check.fault
        val flag = check.onFix(loc.time, phone, nowElapsedMs)
        if (flag.off) bump("fixesOff")
        if (flag.implausible) bump("fixesImplausible")
        val now = check.fault
        if (before == null && now != null) bump("faults")
        val event = when {
            before == null && now != null -> "chipClockOff"
            before != null && now == null -> "chipClockBack"
            backwards -> "fixBackwards"
            now != null && nowElapsedMs - lastLoggedAt >= LOG_EVERY_MS -> "chipClockStillOff"
            else -> null
        }
        if (event != null) {
            lastLoggedAt = nowElapsedMs
            add(linkedMapOf(
                "event" to event, "kind" to (now ?: before)?.kind?.code, "atElapsedMs" to nowElapsedMs, "provider" to loc.provider,
                "chipTimeMs" to loc.time, "phoneWallMs" to nowWallMs, "chipVsPhoneMs" to delta, "fixAgeMs" to nowWallMs - phone,
                "implausible" to flag.implausible, "previousFixMs" to previous,
                "accuracyM" to if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            ))
        }
        return flag
    }

    /** The fault going on now (the glance view's line), or null. */
    @Synchronized fun fault(): GpsClockCheck.Fault? = check.fault

    /** The faults that ended, oldest first (the glance view's last minute: one note each). */
    @Synchronized fun ended(): List<GpsClockCheck.Fault> = check.ended.toList()

    /** A clock-back restart in the readout's estimator (worker thread): which input, and by how much; [offsetMs] the readout's clock minus the phone's. */
    @Synchronized fun onClockBack(source: String, tMs: Long, latestMs: Long, elapsedMs: Long, offsetMs: Long) {
        bump("readoutClockBacks")
        add(linkedMapOf("event" to "readoutClockBack", "atElapsedMs" to elapsedMs, "source" to source, "inputTimeMs" to tMs,
            "latestTimeMs" to latestMs, "backS" to (latestMs - tMs) / 1000.0, "fixClockOffsetMs" to offsetMs))
    }

    private fun add(e: Map<String, Any?>) {
        Log.w(TAG, e.entries.joinToString(" ") { "${it.key}=${it.value}" })
        ring.addLast(e)
        while (ring.size > MAX) ring.removeFirst()
    }

    @Synchronized fun json(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): Map<String, Any?> = linkedMapOf(
        "counts" to LinkedHashMap(counts), "chipClockOff" to (check.fault != null),
        "fault" to check.fault?.let { faultJson(it, nowElapsedMs) },
        "ended" to check.ended.map { faultJson(it, nowElapsedMs) },
        "lastChipVsPhoneMs" to lastDeltaMs, "maxAbsChipVsPhoneMs" to maxAbsDeltaMs,
        "offThresholdMs" to OFF_MS, "run" to GpsClockCheck.RUN, "lastedS" to GpsClockCheck.LASTED_MS / 1000,
        "recent" to ring.toList(),
    )

    private fun faultJson(f: GpsClockCheck.Fault, nowElapsedMs: Long): Map<String, Any?> = linkedMapOf(
        "kind" to f.kind.code, "sinceS" to (nowElapsedMs - f.sinceElapsedMs) / 1000, "lastChipVsPhoneMs" to f.lastDeltaMs,
        "lasted" to f.lasted(nowElapsedMs), "endedS" to f.endElapsedMs?.let { (nowElapsedMs - it) / 1000 },
    )

    @Synchronized fun reset() {
        ring.clear(); counts.keys.forEach { counts[it] = 0L }; check.reset()
        lastFixTime = Long.MIN_VALUE; lastDeltaMs = null; maxAbsDeltaMs = 0L
    }
}
