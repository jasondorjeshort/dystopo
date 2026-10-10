package io.github.jasondorjeshort.dystopo.nav

import kotlin.math.max

/**
 * Snoozing the off-route alert (T65). Navigation keeps detecting and listing events; the snooze only
 * decides whether an event makes a sound or a buzz. Two kinds: for a while ([snooze], minutes of wall
 * time) or for this excursion ([snoozeExcursion]: until the walker is back on the line, so the next
 * time they leave it the alert sounds again). [allow] answers per event and counts what it held back.
 * Pure: the caller passes the clock.
 */
class AlertSnooze {
    /** Alerts held until this wall time (epoch ms), or null. */
    var untilMs: Long? = null
        private set

    /** Alerts held until the next [NavEvent.BACK_ON_ROUTE]. */
    var untilBackOnRoute = false
        private set

    /** Alerts held back so far. */
    var suppressed = 0
        private set

    fun snooze(nowMs: Long, minutes: Double) {
        require(minutes > 0 && minutes <= MAX_MINUTES) { "snooze minutes must be in (0, ${MAX_MINUTES.toInt()}]" }
        untilMs = max(untilMs ?: 0L, nowMs + (minutes * 60_000).toLong())
    }

    fun snoozeExcursion() {
        untilBackOnRoute = true
    }

    fun clear() {
        untilMs = null
        untilBackOnRoute = false
    }

    fun active(nowMs: Long): Boolean = untilBackOnRoute || (untilMs?.let { nowMs < it } ?: false)

    /**
     * Whether the alert for an event of [type] ([NavEvent] types) sounds now. A back-on-route event ends an
     * excursion snooze (and is itself quiet, since its off-route alert was).
     */
    fun allow(type: String, nowMs: Long): Boolean {
        val timed = untilMs?.let { nowMs < it } ?: false
        if (!timed && untilMs != null) untilMs = null
        val excursion = untilBackOnRoute
        if (type == NavEvent.BACK_ON_ROUTE) untilBackOnRoute = false
        val quiet = timed || excursion
        if (quiet) suppressed++
        return !quiet
    }

    fun json(nowMs: Long): Map<String, Any?> = linkedMapOf(
        "active" to active(nowMs),
        "untilMs" to untilMs?.takeIf { nowMs < it },
        "remainingS" to untilMs?.let { ((it - nowMs) / 1000).takeIf { r -> r > 0 } },
        "untilBackOnRoute" to untilBackOnRoute,
        "suppressed" to suppressed,
    )

    companion object {
        const val MAX_MINUTES = 240.0
    }
}

/** Where a fix came from, as far as staleness goes (T65). */
enum class FixOrigin(val label: String, val realTime: Boolean) {
    /** The device GPS while navigating: stamped by the receiver, seconds old at most when it is current. */
    GPS("gps", true),

    /** The last known location when navigation starts: can be hours old and far away. */
    LAST_KNOWN("last", true),

    /** `POST /location` and GPX replays: test input with its own (possibly old) times; never stale. */
    INJECTED("injected", false);

    companion object {
        fun of(label: String): FixOrigin = entries.firstOrNull { it.label == label } ?: INJECTED
    }
}

/** Stale-fix thresholds (T65). */
data class FixFreshnessOptions(
    /** A real-time fix older than this when it arrives is stale: not used for progress, off-route or alerts. */
    val maxAgeS: Double = 30.0,
    /** The last known location starts navigation only when it is also within this of the line. */
    val maxStartDistanceM: Double = 2000.0,
    /** With no usable fix for this long, the position counts as lost: the bar says how old it is and off-route alerts wait. */
    val lostAfterS: Double = 30.0,
) {
    init {
        require(maxAgeS > 0 && maxStartDistanceM > 0 && lostAfterS > 0) { "bad freshness options" }
    }
}

/**
 * Stale fixes (T65): what navigation does with a fix that may not say where the walker is now. A
 * real-time fix (GPS, the last known location) older than [FixFreshnessOptions.maxAgeS] when it arrives
 * is dropped; the last known location begins navigation only when it is also within
 * [FixFreshnessOptions.maxStartDistanceM] of the line (an old far-away fix once put the walker kilometres
 * off route before the first real one). Injected fixes and replays carry their own times and are always
 * used. [ageS] and [lost] tell the bar how old the newest used fix is, by the wall clock it arrived at.
 */
class FixFreshness(val options: FixFreshnessOptions = FixFreshnessOptions()) {
    enum class Verdict { USE, TOO_OLD, TOO_FAR }

    var tooOld = 0
        private set
    var tooFar = 0
        private set
    private var lastUsedAtMs: Long? = null

    /**
     * The verdict for a fix stamped [fixTimeMs] arriving at wall time [nowMs] from [origin]; [distanceToLineM]
     * is its distance from the line (consulted for [FixOrigin.LAST_KNOWN] only).
     */
    fun check(origin: FixOrigin, fixTimeMs: Long, nowMs: Long, distanceToLineM: Double?): Verdict {
        val v = when {
            origin.realTime && (nowMs - fixTimeMs) / 1000.0 > options.maxAgeS -> Verdict.TOO_OLD
            origin == FixOrigin.LAST_KNOWN && distanceToLineM != null && distanceToLineM > options.maxStartDistanceM -> Verdict.TOO_FAR
            else -> Verdict.USE
        }
        when (v) {
            Verdict.TOO_OLD -> tooOld++
            Verdict.TOO_FAR -> tooFar++
            Verdict.USE -> lastUsedAtMs = nowMs
        }
        return v
    }

    /** Seconds since the last used fix arrived (wall clock), or null before one. */
    fun ageS(nowMs: Long): Double? = lastUsedAtMs?.let { max(0.0, (nowMs - it) / 1000.0) }

    /** True when the last used fix (or, before one, the start at [startedAtMs]) is more than [FixFreshnessOptions.lostAfterS] ago. */
    fun lost(nowMs: Long, startedAtMs: Long): Boolean = (ageS(nowMs) ?: ((nowMs - startedAtMs) / 1000.0)) > options.lostAfterS

    fun json(nowMs: Long, startedAtMs: Long): Map<String, Any?> = linkedMapOf(
        "ageS" to ageS(nowMs)?.let(::r1),
        "lost" to lost(nowMs, startedAtMs),
        "droppedTooOld" to tooOld,
        "droppedTooFar" to tooFar,
        "maxAgeS" to options.maxAgeS,
        "maxStartDistanceM" to options.maxStartDistanceM,
        "lostAfterS" to options.lostAfterS,
    )
}
