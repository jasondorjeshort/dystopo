package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat

/**
 * T159: the one explicit state of a recording, shown by the track controls and the recording's notification and
 * reported by `GET /tracks` (`state`). Worked out by [RecordingWatch.stateOf] from the recorder and the watch.
 */
enum class RecState(val code: String) {
    IDLE("idle"),
    RECORDING("recording"),
    /** Recording, but no fix from the GPS for [RecordingWatch.WAITING_AFTER_MS] or more. */
    WAITING_FOR_GPS("waiting-for-gps"),
    PAUSED("paused"),
    AUTO_PAUSED("auto-paused"),
    /** The system stopped the recording: the process died (the track resumed, waiting for its first fix) or the service could not run. */
    STOPPED_BY_SYSTEM("stopped-by-system"),
}

/**
 * T159: the times the tracking thread keeps while a track records (elapsed-realtime milliseconds, so a clock change
 * does not fire anything), and the rules read from them: when the state is "waiting for GPS", when the network
 * fallback is wanted, when the watchdog re-registers the GPS listener. Pure and not thread-safe: [Tracking] calls it on
 * its one thread; unit-tested in `RecordingWatchTest`.
 *
 * A primary fix is one from the GPS (or an injected one standing in for it) that reached the recorder; a fallback fix
 * is a network or passive one taken while the GPS is silent ([fallback]). The watchdog keys on the primary fixes: a
 * phone whose GPS stalled but whose network location still answers is still a stalled GPS.
 */
class RecordingWatch(val waitingAfterMs: Long = WAITING_AFTER_MS) {
    var lastPrimaryAt = 0L
        private set
    var lastPrimaryAccuracyM: Double? = null
        private set
    var lastFallbackAt = 0L
        private set
    var lastFallbackAccuracyM: Double? = null
        private set
    var lastWatchdogAt = 0L
        private set
    /** Watchdog re-registrations since the recording started (or resumed after a process death). */
    var watchdogRestarts = 0L
        private set
    /** Fallback fixes taken since the recording started. */
    var fallbackFixes = 0L
        private set

    /** A recording starts (or resumes after a process death) at [now]: silence is counted from here. */
    fun begin(now: Long) {
        lastPrimaryAt = now
        lastWatchdogAt = now
        lastFallbackAt = 0L
        lastPrimaryAccuracyM = null
        lastFallbackAccuracyM = null
        watchdogRestarts = 0
        fallbackFixes = 0
    }

    /** The recording was paused and resumes at [now]: the silence while paused does not count. */
    fun resumed(now: Long) {
        lastPrimaryAt = maxOf(lastPrimaryAt, now)
        lastWatchdogAt = maxOf(lastWatchdogAt, now)
    }

    fun primary(now: Long, accuracyM: Double?) {
        lastPrimaryAt = now
        accuracyM?.takeIf { it > 0 && it.isFinite() }?.let { lastPrimaryAccuracyM = it }
    }

    fun fallback(now: Long, accuracyM: Double?) {
        lastFallbackAt = now
        lastFallbackAccuracyM = accuracyM?.takeIf { it > 0 && it.isFinite() }
        fallbackFixes++
    }

    /** How long the GPS has been silent. */
    fun silentMs(now: Long): Long = (now - lastPrimaryAt).coerceAtLeast(0)

    fun waiting(now: Long): Boolean = silentMs(now) >= waitingAfterMs

    /** True when a fallback fix arrived within the waiting window (the notification says it is being used). */
    fun fallbackRecent(now: Long): Boolean = lastFallbackAt != 0L && now - lastFallbackAt < waitingAfterMs

    /** True when the watchdog should re-register now: [timeoutMs] since the last primary fix and since its own last firing. */
    fun watchdogDue(now: Long, timeoutMs: Long): Boolean = now - maxOf(lastPrimaryAt, lastWatchdogAt) >= timeoutMs

    /**
     * The watchdog fired at [now]; returns true when no fix of any source arrived for [timeoutMs], so the hole is a gap
     * on the track ([GapReason.RESTARTED]); false when fallback fixes kept the track going.
     */
    fun watchdogFired(now: Long, timeoutMs: Long): Boolean {
        watchdogRestarts++
        lastWatchdogAt = now
        return now - maxOf(lastPrimaryAt, lastFallbackAt) >= timeoutMs
    }

    companion object {
        /**
         * No GPS fix for this long is "waiting for GPS", and the network fallback starts. The GPS delivers a fix a
         * second while it has one (the profile's interval, no distance filter), so 15 s of silence is a lost fix, not
         * a quiet moment; shorter would flicker under trees.
         */
        const val WAITING_AFTER_MS = 15_000L

        fun stateOf(recording: Boolean, paused: Boolean, autoPaused: Boolean, pendingGap: GapReason?, serviceDown: Boolean, waiting: Boolean): RecState = when {
            !recording -> RecState.IDLE
            serviceDown || pendingGap == GapReason.STOPPED_BY_SYSTEM -> RecState.STOPPED_BY_SYSTEM
            paused -> RecState.PAUSED
            waiting -> RecState.WAITING_FOR_GPS
            autoPaused -> RecState.AUTO_PAUSED
            else -> RecState.RECORDING
        }
    }
}

/**
 * T159: what the recording's notification and the controls' state line say, as data (ADR 0031: the strings are listed
 * in the task report). [stoppedAt] and [startedAt] are clock times already formatted for the phone's locale.
 */
data class RecordingStatus(
    val state: RecState,
    val name: String?,
    val distanceM: Double,
    val units: ContourUnits,
    val silentS: Long = 0,
    val lastAccuracyM: Double? = null,
    /** The accuracy of a recent network fix while the GPS is silent, or null when none is being used. */
    val fallbackAccuracyM: Double? = null,
    val startedAt: String? = null,
    val stoppedAt: String? = null,
    /** The recording service is not running (the system refused or stopped it): the notification asks for a tap. */
    val serviceDown: Boolean = false,
) {
    private val track: String get() = name?.takeIf { it.isNotBlank() } ?: "track"
    private val distance: String get() = UnitFormat.distance(distanceM, units)

    val title: String
        get() = when (state) {
            RecState.IDLE -> "Not recording"
            RecState.RECORDING -> "Recording $track"
            RecState.WAITING_FOR_GPS -> "Waiting for GPS: $track"
            RecState.PAUSED -> "Paused: $track"
            RecState.AUTO_PAUSED -> "Auto-paused: $track"
            RecState.STOPPED_BY_SYSTEM -> if (serviceDown) "Recording stopped by the system" else "Resumed: $track"
        }

    val text: String
        get() = when (state) {
            RecState.IDLE -> ""
            RecState.RECORDING -> "$distance" + (startedAt?.let { " since $it" } ?: "")
            RecState.WAITING_FOR_GPS -> waitingLine
            RecState.PAUSED -> "$distance so far. Resume to go on recording."
            RecState.AUTO_PAUSED -> "Not moving; recording goes on when you move. $distance so far."
            RecState.STOPPED_BY_SYSTEM ->
                if (serviceDown) "$track stopped" + (stoppedAt?.let { " at $it" } ?: "") + ". Tap to go on recording it."
                else "The system stopped recording" + (stoppedAt?.let { " at $it" } ?: "") + "; it goes on with the next fix."
        }

    /** The line under the live stats on the track controls; null while recording normally. */
    val controlsLine: String?
        get() = when (state) {
            RecState.IDLE, RecState.RECORDING -> null
            RecState.WAITING_FOR_GPS -> "Waiting for GPS: " + waitingLine.replaceFirstChar { it.lowercase() }
            RecState.PAUSED -> "Paused"
            RecState.AUTO_PAUSED -> "Auto-paused: not moving"
            RecState.STOPPED_BY_SYSTEM -> title + (stoppedAt?.let { " at $it" } ?: "")
        }

    private val waitingLine: String
        get() = "No fix for ${silence(silentS)}" +
            (lastAccuracyM?.let { ", last accuracy " + UnitFormat.elevation(it, units) } ?: "") +
            (fallbackAccuracyM?.let { "; using network location, " + UnitFormat.elevation(it, units) } ?: "")

    companion object {
        /** "40 s" in steps of 10 s under a minute (the notification is not redrawn every second), then "3 min". */
        fun silence(s: Long): String = if (s < 60) "${(s / 10 * 10).coerceAtLeast(10)} s" else "${s / 60} min"
    }
}
