package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity

/** One value of the live readout (T160). [code] is the stable key T161's settings use: never change or reuse one. */
enum class ReadoutEntry(val code: String) {
    /** Horizontal speed over ground (or pace, by [EntryOptions.pace]). */
    SPEED("speed"),

    /** Vertical speed: m/h or ft/h on the ground, ft/min or m/min in the air. */
    CLIMB("climb"),

    /** Course over ground in degrees true, held while standing still. */
    COURSE("course"),

    /** Elevation above mean sea level (EGM2008). */
    ELEVATION("elevation");

    companion object {
        fun of(code: String?): ReadoutEntry? = entries.firstOrNull { it.code == code }
    }
}

/**
 * How much an entry is smoothed (T160): the window or lag each level means depends on the activity
 * ([ReadoutTuning]). The marine convention of a damping setting (the readout idea's research, 2026-10-25),
 * offered on every entry.
 */
enum class Damping(val code: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun of(code: String?): Damping? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The options of one readout entry (T160; T161 shows them as Settings rows, ADR 0044).
 *
 * - [shown]: the entry is drawn at all.
 * - [pace]: [ReadoutEntry.SPEED] only: time per kilometre or mile instead of a speed.
 * - [damping]: the smoothing window ([ReadoutTuning]).
 * - [plusMinus]: the 1-sigma uncertainty is drawn after the value ("± 3 m").
 * - [knots]: [ReadoutEntry.SPEED] only, honoured for [Activity.BOAT] and [Activity.AIR] only (the owner's answer of
 *   2026-10-25: knots an option for boat and air, never a default).
 */
data class EntryOptions(
    val shown: Boolean,
    val pace: Boolean = false,
    val damping: Damping = Damping.MEDIUM,
    val plusMinus: Boolean = false,
    val knots: Boolean = false,
) {
    /** `shown=1;pace=0;damping=medium;pm=0;knots=0`: one settings value. */
    fun encode(): String =
        "shown=${b(shown)};pace=${b(pace)};damping=${damping.code};pm=${b(plusMinus)};knots=${b(knots)}"

    companion object {
        private fun b(v: Boolean) = if (v) 1 else 0

        /** Reads [encode]'s form; a key missing or not understood takes [default]'s value (a newer build may add keys). */
        fun decode(text: String?, default: EntryOptions): EntryOptions {
            if (text.isNullOrBlank()) return default
            val m = text.split(';').mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it).trim() to kv.substring(it + 1).trim() } }.toMap()
            fun flag(k: String, def: Boolean) = when (m[k]) { "1" -> true; "0" -> false; else -> def }
            return EntryOptions(
                shown = flag("shown", default.shown),
                pace = flag("pace", default.pace),
                damping = Damping.of(m["damping"]) ?: default.damping,
                plusMinus = flag("pm", default.plusMinus),
                knots = flag("knots", default.knots),
            )
        }
    }
}

/**
 * What the live readout shows for one [activity] (T160), entry by entry, and whether the barometer is used. The
 * defaults ([defaults]) follow the readout idea's "Sketch the research supports" (2026-10-25):
 *
 * - foot: speed (not pace: the owner's point 2 asks for mph or km/h; pace stays an option), climb, course, elevation;
 * - bike: speed, course, elevation; climb an option ("climb rate optional");
 * - road and rail: speed and elevation (a road app shows a speed badge and no vertical speed);
 * - boat: speed and course (the marine pair, speed and course over ground);
 * - air: ground speed, climb (ft/min), track and GPS altitude, the aviation instrument set.
 *
 * The ± is off by default everywhere (no surveyed watch or app shows an accuracy beside the value); damping is
 * medium; knots off ([EntryOptions.knots]). [barometer] is T161's switch, default on (the owner's point 7); the
 * barometer is still never used in road, rail and air ([io.github.jasondorjeshort.dystopo.model.ActivityProfile.elevation]).
 */
data class ReadoutOptions(
    val activity: Activity,
    val speed: EntryOptions,
    val climb: EntryOptions,
    val course: EntryOptions,
    val elevation: EntryOptions,
    val barometer: Boolean = true,
    /**
     * T177: the climb rate's window in seconds, one of [CLIMB_WINDOWS_S] (default [DEFAULT_CLIMB_WINDOW_S]): on the
     * ground the climb shown is the mean vertical speed over the last this many seconds ([ReadoutEstimator]); the air
     * keeps its few seconds' lag (a vertical speed indicator, not a hiker's average).
     */
    val climbWindowS: Int = DEFAULT_CLIMB_WINDOW_S,
) {
    operator fun get(entry: ReadoutEntry): EntryOptions = when (entry) {
        ReadoutEntry.SPEED -> speed
        ReadoutEntry.CLIMB -> climb
        ReadoutEntry.COURSE -> course
        ReadoutEntry.ELEVATION -> elevation
    }

    /** These options with [entry]'s replaced. */
    fun with(entry: ReadoutEntry, options: EntryOptions): ReadoutOptions = when (entry) {
        ReadoutEntry.SPEED -> copy(speed = options)
        ReadoutEntry.CLIMB -> copy(climb = options)
        ReadoutEntry.COURSE -> copy(course = options)
        ReadoutEntry.ELEVATION -> copy(elevation = options)
    }

    /** Speed reads in knots: asked for, and the activity is boat or air. */
    val knots: Boolean get() = speed.knots && knotsAllowed(activity)

    companion object {
        /**
         * T177: the climb windows offered (ADR 0044; the survey of 2026-10-30: Polar's 30 s field is the only published
         * window, Garmin and Suunto users call the instant value jumpy and ask for 1 to 5 minutes, no app offers the
         * choice), 1 minute the default.
         */
        val CLIMB_WINDOWS_S = listOf(30, 60, 300, 600)
        const val DEFAULT_CLIMB_WINDOW_S = 60

        /** "30 s", "1 min", "5 min": a window as the row and `GET /readout` say it. */
        fun windowLabel(seconds: Int): String = if (seconds < 60) "$seconds s" else "${seconds / 60} min"

        /** Knots is offered only for these (the owner's answer of 2026-10-25). */
        fun knotsAllowed(activity: Activity): Boolean = activity == Activity.BOAT || activity == Activity.AIR

        /** The default options of [activity] (see the class KDoc for the source of each). */
        fun defaults(activity: Activity): ReadoutOptions {
            val on = EntryOptions(shown = true)
            val off = EntryOptions(shown = false)
            return when (activity) {
                Activity.FOOT -> ReadoutOptions(activity, speed = on, climb = on, course = on, elevation = on)
                Activity.BIKE -> ReadoutOptions(activity, speed = on, climb = off, course = on, elevation = on)
                Activity.ROAD, Activity.RAIL -> ReadoutOptions(activity, speed = on, climb = off, course = off, elevation = on)
                Activity.BOAT -> ReadoutOptions(activity, speed = on, climb = off, course = on, elevation = off)
                Activity.AIR -> ReadoutOptions(activity, speed = on, climb = on, course = on, elevation = on)
            }
        }

        /**
         * T177: the phone's readout bar defaults: on foot elevation and climb only, side by side (the owner, 2026-10-30:
         * the bar beside the scale for the mountain hike; the survey: elevation and climb as the default fields, speed or
         * pace and course per-entry options), every other activity as [defaults]. [defaults] stays what the estimator
         * and `devtool readout` compute and report.
         */
        fun phoneDefaults(activity: Activity): ReadoutOptions {
            val d = defaults(activity)
            return if (activity != Activity.FOOT) d else d.copy(speed = d.speed.copy(shown = false), course = d.course.copy(shown = false))
        }
    }
}
