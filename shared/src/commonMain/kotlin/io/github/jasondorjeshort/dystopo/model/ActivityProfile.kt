package io.github.jasondorjeshort.dystopo.model

/**
 * When a pause in the fix stream becomes a gap (T158): fixes resuming more than [timeMs] after the last kept one
 * and, when [minDistanceM] is set, more than that many metres from it. A gap starts a new segment (stats never
 * bridge it) and is written as a [TrackGap]. Time and distance keep a long stop in one place (phones deliver no
 * fixes while still) from splitting the track; time only suits flying, where any minute moves kilometres.
 */
data class GapRule(
    /** Null: never a gap on time. */
    val timeMs: Long?,
    /** Null: time only. */
    val minDistanceM: Double?,
) {
    init {
        require(timeMs == null || timeMs > 0) { "gap time must be positive" }
        require(minDistanceM == null || minDistanceM >= 0) { "gap distance must not be negative" }
    }
}

/**
 * Auto-pause thresholds (T158), used when [ActivityProfile.autoPause] is on: recording pauses once the fix speed
 * stays under [stillMps] for [pauseAfterMs], and resumes once it stays over [resumeMps] for [resumeAfterMs]
 * (two thresholds, so a speed near one of them does not flap). The pause is a [GapReason.AUTO_PAUSED] gap.
 */
data class AutoPauseRule(
    val stillMps: Double,
    val pauseAfterMs: Long,
    val resumeMps: Double,
    val resumeAfterMs: Long,
) {
    init {
        require(stillMps >= 0 && resumeMps >= stillMps) { "resume speed must be at least the still speed" }
        require(pauseAfterMs >= 0 && resumeAfterMs >= 0) { "durations must not be negative" }
    }
}

/** Where a track's heights come from (T158; read by the readout, T160): the GNSS height alone, or fused with the barometer and the DEM. */
enum class ElevationSource(val code: String) {
    GNSS("gnss"),
    FUSED("fused");

    companion object {
        fun of(code: String?): ElevationSource? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The recording settings of one [Activity] (T158), as data: stored with each track (`track.profile`, synced as a
 * track field) and remembered for the next start. The defaults ([of]) come from the research of 2026-10-30 (the
 * survey `out/wave22-research/T158-track-recording.md` and two follow-up surveys on stall thresholds and auto-pause,
 * cited in docs/PLANNING.md › Tracks); the speed bounds are physical limits, not survey results.
 *
 * Fields the recorder ([io.github.jasondorjeshort.dystopo.store.TrackRecorder]) applies: [accuracyBoundM] (fixes
 * worse than it are kept and flagged, never dropped), [gap], [speedBoundMps] (a fix that would need more than this
 * from the last kept one, after both accuracies are allowed for, is a teleport and dropped), [autoPause] with
 * [autoPauseRule]. Fields the phone applies (T159, T172): [intervalMs], [distanceFilterM], [watchdogMs] (re-register
 * the listener after this long without a fix), [networkFallback] (network or passive fixes while the GPS is silent,
 * flagged); the readout (T160): [elevation].
 */
data class ActivityProfile(
    val activity: Activity,
    /** Null: no bound (flying: every fix counts). */
    val accuracyBoundM: Double?,
    val intervalMs: Long,
    val distanceFilterM: Double,
    val gap: GapRule,
    val speedBoundMps: Double,
    val watchdogMs: Long,
    val networkFallback: Boolean,
    val autoPause: Boolean,
    val autoPauseRule: AutoPauseRule,
    val elevation: ElevationSource,
) {
    init {
        require(accuracyBoundM == null || accuracyBoundM > 0) { "accuracy bound must be positive" }
        require(intervalMs > 0 && watchdogMs > 0) { "interval and watchdog must be positive" }
        require(distanceFilterM >= 0 && speedBoundMps > 0) { "distance filter must not be negative, speed bound must be positive" }
    }

    /**
     * The profile as one line of `key=value` pairs separated by `;` (`v=1;activity=foot;acc=50;...`): what the
     * track's `profile` field holds. A reader takes the keys it knows and the activity's defaults for the rest, so a
     * newer build may add keys; `none` is a null bound.
     */
    fun encode(): String = buildString {
        fun kv(k: String, v: Any?) { if (isNotEmpty()) append(';'); append(k).append('=').append(v ?: NONE) }
        kv("v", FORMAT)
        kv("activity", activity.code)
        kv("acc", accuracyBoundM?.let(::num))
        kv("interval", intervalMs)
        kv("dist", num(distanceFilterM))
        kv("gap", gap.timeMs)
        kv("gapm", gap.minDistanceM?.let(::num))
        kv("speed", num(speedBoundMps))
        kv("watchdog", watchdogMs)
        kv("net", if (networkFallback) 1 else 0)
        kv("autopause", if (autoPause) 1 else 0)
        kv("still", num(autoPauseRule.stillMps))
        kv("stillms", autoPauseRule.pauseAfterMs)
        kv("move", num(autoPauseRule.resumeMps))
        kv("movems", autoPauseRule.resumeAfterMs)
        kv("ele", elevation.code)
    }

    companion object {
        private const val FORMAT = 1
        private const val NONE = "none"

        private fun num(d: Double): String = if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

        /** The default profile of [activity]. */
        fun of(activity: Activity): ActivityProfile = when (activity) {
            Activity.FOOT -> base(activity, 50.0, GapRule(5 * MIN, 50.0), 25.0, 60_000, AutoPauseRule(0.3, 10_000, 0.6, 2_000), ElevationSource.FUSED)
            Activity.BIKE -> base(activity, 50.0, GapRule(3 * MIN, 100.0), 40.0, 45_000, AutoPauseRule(0.7, 6_000, 1.5, 2_000), ElevationSource.FUSED)
            Activity.ROAD -> base(activity, 100.0, GapRule(2 * MIN, 200.0), 90.0, 30_000, AutoPauseRule(1.0, 8_000, 2.0, 2_000), ElevationSource.GNSS)
            Activity.RAIL -> base(activity, 100.0, GapRule(2 * MIN, 500.0), 120.0, 90_000, AutoPauseRule(0.5, 10_000, 1.5, 3_000), ElevationSource.GNSS)
            Activity.BOAT -> base(activity, 50.0, GapRule(5 * MIN, 100.0), 40.0, 60_000, AutoPauseRule(0.3, 15_000, 0.8, 3_000), ElevationSource.FUSED)
            // Flying: no accuracy bound, time-only gaps; auto-pause has no meaning in the air (its rule is kept for a taxi).
            Activity.AIR -> base(activity, null, GapRule(5 * MIN, null), 350.0, 120_000, AutoPauseRule(1.0, 30_000, 3.0, 3_000), ElevationSource.GNSS)
        }

        private const val MIN = 60_000L

        private fun base(a: Activity, acc: Double?, gap: GapRule, speed: Double, watchdog: Long, rule: AutoPauseRule, ele: ElevationSource) =
            ActivityProfile(a, acc, intervalMs = 1_000, distanceFilterM = 0.0, gap = gap, speedBoundMps = speed, watchdogMs = watchdog,
                networkFallback = true, autoPause = false, autoPauseRule = rule, elevation = ele)

        /**
         * Reads [encode]'s form: null when [text] is null or names no activity this build knows. Keys missing,
         * unknown or with a value out of range take the activity's default (a newer build's keys are ignored).
         */
        fun decode(text: String?): ActivityProfile? {
            if (text.isNullOrBlank()) return null
            val m = text.split(';').mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it).trim() to kv.substring(it + 1).trim() } }.toMap()
            val activity = Activity.of(m["activity"]) ?: return null
            val d = of(activity)
            fun dbl(k: String): Double? = m[k]?.toDoubleOrNull()?.takeIf { it.isFinite() }
            fun lng(k: String): Long? = m[k]?.toLongOrNull()
            fun flag(k: String, def: Boolean) = when (m[k]) { "1" -> true; "0" -> false; else -> def }
            fun nullableDbl(k: String, def: Double?) = if (m[k] == NONE) null else dbl(k) ?: def
            fun nullableLng(k: String, def: Long?) = if (m[k] == NONE) null else lng(k) ?: def
            return try {
                ActivityProfile(
                    activity,
                    nullableDbl("acc", d.accuracyBoundM)?.takeIf { it > 0 },
                    lng("interval")?.takeIf { it > 0 } ?: d.intervalMs,
                    dbl("dist")?.takeIf { it >= 0 } ?: d.distanceFilterM,
                    GapRule(nullableLng("gap", d.gap.timeMs)?.takeIf { it > 0 }, nullableDbl("gapm", d.gap.minDistanceM)?.takeIf { it >= 0 }),
                    dbl("speed")?.takeIf { it > 0 } ?: d.speedBoundMps,
                    lng("watchdog")?.takeIf { it > 0 } ?: d.watchdogMs,
                    flag("net", d.networkFallback),
                    flag("autopause", d.autoPause),
                    AutoPauseRule(
                        dbl("still") ?: d.autoPauseRule.stillMps, lng("stillms") ?: d.autoPauseRule.pauseAfterMs,
                        dbl("move") ?: d.autoPauseRule.resumeMps, lng("movems") ?: d.autoPauseRule.resumeAfterMs,
                    ),
                    ElevationSource.of(m["ele"]) ?: d.elevation,
                )
            } catch (e: IllegalArgumentException) {
                // Values that are each in range but inconsistent (a resume speed under the still speed): the defaults.
                d
            }
        }
    }
}
