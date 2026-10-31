package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.readout.Damping
import io.github.jasondorjeshort.dystopo.readout.EntryOptions
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutOptions

/**
 * T161: the live readout's settings (ADR 0044: the readout idea's research of 2026-10-25 found apps split on what each
 * entry shows, so every entry's options are a setting with T160's per-activity defaults, [ReadoutOptions.defaults]).
 * Pure, unit-tested (`ReadoutSettingsTest`).
 *
 * - One value per activity and entry, `readout.<activity>.<entry>` ([key]), [EntryOptions.encode]'s form
 *   (`shown=1;pace=0;damping=medium;pm=0;knots=0`), synced as `app.readout.<activity>.<entry>`. An activity and entry with
 *   no stored value has its defaults.
 * - The rows' own keys, `readout.<entry>` ([editKey]): some of those options (`pace=1`), applied over the remembered
 *   activity's ([io.github.jasondorjeshort.dystopo.app.AppSettings.trackActivity], T172's) current options and stored
 *   whole under its [key], so they sync through it.
 * - The barometer switch, `readoutBarometer` ([BAROMETER], default on: the owner's point 7), one for every activity
 *   (road, rail and air never use the barometer whatever it says), synced as `app.readoutBarometer`.
 * - T177: the bar switch, `readoutBar` ([BAR], default on: the owner, 2026-10-30, "always visible beside the scale
 *   with an option"; T161's missing whole-strip switch), synced as `app.readoutBar` `on`/`off`; and the climb window,
 *   `climbWindow` ([CLIMB_WINDOW], seconds, 30, 60 (default), 300 or 600; ADR 0044: users ask for 30 s to 5 min and no
 *   app offers the choice), synced as `app.climbWindow`. One of each for every activity.
 * - T177: an activity's defaults are [ReadoutOptions.phoneDefaults] (on foot elevation and climb only).
 *
 * Pace and knots belong to speed only; knots is offered for boat and air only (the owner's answer of 2026-10-25), and a
 * value that asks for it elsewhere is kept but not honoured ([ReadoutOptions.knots]).
 */
object ReadoutSettings {
    const val BAROMETER = "readoutBarometer"

    /** T177: the bar switch and the climb window. */
    const val BAR = "readoutBar"
    const val CLIMB_WINDOW = "climbWindow"

    /** T177: a climb window as given (`60`, `60s`, `1min`, `5 min`), or null when it is not one of [ReadoutOptions.CLIMB_WINDOWS_S]. */
    fun parseClimbWindow(raw: String?): Int? {
        val t = raw?.trim()?.lowercase()?.replace(" ", "") ?: return null
        val s = when {
            t.endsWith("min") -> t.removeSuffix("min").toIntOrNull()?.times(60)
            t.endsWith("s") -> t.removeSuffix("s").toIntOrNull()
            else -> t.toIntOrNull()
        }
        return s?.takeIf { it in ReadoutOptions.CLIMB_WINDOWS_S }
    }

    fun key(a: Activity, e: ReadoutEntry) = "readout.${a.code}.${e.code}"

    /** The key a row edits: [e]'s options of the remembered activity. */
    fun editKey(e: ReadoutEntry) = "readout.${e.code}"

    val KEYS: Set<String> = Activity.entries.flatMap { a -> ReadoutEntry.entries.map { key(a, it) } }.toSet()
    val EDIT_KEYS: Set<String> = ReadoutEntry.entries.map(::editKey).toSet()

    /** The activity and entry of a [KEYS] key, or null. */
    fun ofKey(key: String): Pair<Activity, ReadoutEntry>? {
        for (a in Activity.entries) for (e in ReadoutEntry.entries) if (key(a, e) == key) return a to e
        return null
    }

    fun entryOfEditKey(key: String): ReadoutEntry? = ReadoutEntry.entries.firstOrNull { editKey(it) == key }

    private val VALUE_KEYS = setOf("shown", "pace", "damping", "pm", "knots")

    /**
     * Checks a value (whole or partial) and returns [base] with what it sets, or null when a known key has a value
     * out of range or none of the keys is known. Keys this build does not know are dropped (a newer build may add some).
     * Pace and knots are kept for speed only; pace and knots asked together keep pace.
     */
    fun merge(e: ReadoutEntry, raw: String, base: EntryOptions): EntryOptions? {
        val m = raw.split(';').mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it).trim() to kv.substring(it + 1).trim() } }.toMap()
        if (m.keys.none { it in VALUE_KEYS }) return null
        for (k in listOf("shown", "pace", "pm", "knots")) m[k]?.let { v -> if (v != "0" && v != "1") return null }
        m["damping"]?.let { if (Damping.of(it) == null) return null }
        val o = EntryOptions.decode(m.filterKeys { it in VALUE_KEYS }.entries.joinToString(";") { "${it.key}=${it.value}" }, base)
        return normalize(e, o)
    }

    /** Pace and knots only on speed; pace wins over knots. */
    fun normalize(e: ReadoutEntry, o: EntryOptions): EntryOptions = when {
        e != ReadoutEntry.SPEED -> o.copy(pace = false, knots = false)
        o.pace && o.knots -> o.copy(knots = false)
        else -> o
    }

    /** A stored or synced whole value in [EntryOptions.encode]'s form over [a]'s defaults, or null when it is refused ([merge]). */
    fun parseValue(a: Activity, e: ReadoutEntry, raw: String): String? = merge(e, raw, ReadoutOptions.phoneDefaults(a)[e])?.encode()

    /** [a]'s options: its defaults (T177: [ReadoutOptions.phoneDefaults]) with the [stored] values (by [key]) over them, the barometer switch and the climb window. */
    fun options(a: Activity, stored: Map<String, String>, barometer: Boolean, climbWindowS: Int = ReadoutOptions.DEFAULT_CLIMB_WINDOW_S): ReadoutOptions {
        var o = ReadoutOptions.phoneDefaults(a).copy(barometer = barometer, climbWindowS = climbWindowS)
        for (e in ReadoutEntry.entries) {
            val v = stored[key(a, e)] ?: continue
            o = o.with(e, normalize(e, EntryOptions.decode(v, o[e])))
        }
        return o
    }

    /** What a row offers for [e] in [a]: pace for speed, knots for speed in boat and air. */
    fun offersPace(e: ReadoutEntry) = e == ReadoutEntry.SPEED
    fun offersKnots(a: Activity, e: ReadoutEntry) = e == ReadoutEntry.SPEED && ReadoutOptions.knotsAllowed(a)

    /** The entry's name as the rows and the strip's captions say it (ADR 0031: the owner adjusts). */
    fun label(e: ReadoutEntry): String = when (e) {
        ReadoutEntry.SPEED -> "Speed"
        ReadoutEntry.CLIMB -> "Climb"
        ReadoutEntry.COURSE -> "Course"
        ReadoutEntry.ELEVATION -> "Elevation"
    }
}
