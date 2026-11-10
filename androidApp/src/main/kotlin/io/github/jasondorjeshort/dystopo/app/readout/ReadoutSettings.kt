package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.readout.BarometerMode
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
 * - T250: the barometer's mode, `readoutBarometer` ([BAROMETER]; [BarometerMode]: Automatic (the default), Always or
 *   Never; T161's on/off switch before it), one for every activity, stored as its code under [BAROMETER_STORED] (the
 *   old key held a boolean, whose type never changes: `AppSettings.load` migrates on to Automatic and off to Never)
 *   and synced as `app.readoutBarometer` in [syncValue]'s form: `on` for Automatic, `off` for Never (what a build
 *   before T250 wrote and reads), `always` for Always (a build before T250 skips it and keeps its own value).
 * - T177: the bar switch, `readoutBar` ([BAR], default on: the owner, 2026-10-30, "always visible beside the scale
 *   with an option"; T161's missing whole-strip switch), synced as `app.readoutBar` `on`/`off`; and the climb window,
 *   `climbWindow` ([CLIMB_WINDOW], seconds, 30, 60 (default), 300 or 600; ADR 0044: users ask for 30 s to 5 min and no
 *   app offers the choice), synced as `app.climbWindow`. One of each for every activity.
 * - T177: an activity's defaults are [ReadoutOptions.phoneDefaults] (on foot elevation and climb only).
 *
 * Pace and knots belong to speed only; knots is offered for boat and air only (the owner's answer of 2026-10-25), and a
 * value that asks for it elsewhere is kept but not honoured ([ReadoutOptions.knots]).
 *
 * T257: three more entries, each `readout.<activity>.<entry>` like the others and synced the same way: `pressure`
 * (shown by default, drawn only on a phone with a barometer), `accuracy` and `fixAge` (off by default; the
 * "Extended readout" row turns both on or off for the remembered activity, [EXTENDED]); and two course options,
 * `deg` (the degrees after the compass point, default on) and `mag` (magnetic north, default off), written only when
 * away from their defaults. ADR 0033: a value stored before T257 reads as before with the new options at their
 * defaults (a course "SSE 163°", degrees true; the pressure shown; the extended entries off), and keys this build does
 * not know are kept in the stored value ([EntryOptions.extra]). ADR 0044: the survey of 2026-11-09 found every app that
 * offers these fields lets the user choose them.
 */
object ReadoutSettings {
    const val BAROMETER = "readoutBarometer"

    /** T250: where the mode is stored (a string code); [BAROMETER] held T161's boolean switch, migrated from on load. */
    const val BAROMETER_STORED = "readoutBarometerMode"

    /**
     * T250: the mode as `POST /settings` and a sync give it: `auto` (also `automatic`, and T161's `1`, `on`, `true`,
     * `yes`), `always`, `never` (also `0`, `off`, `false`, `no`); null for anything else.
     */
    fun parseBarometer(raw: String?): BarometerMode? = when (raw?.trim()?.lowercase()) {
        "auto", "automatic", "1", "on", "true", "yes" -> BarometerMode.AUTO
        "always" -> BarometerMode.ALWAYS
        "never", "0", "off", "false", "no" -> BarometerMode.NEVER
        else -> null
    }

    /** T250: the synced value: `on` (Automatic) and `off` (Never) as builds before T250 read them, `always`. */
    fun syncValue(m: BarometerMode): String = when (m) {
        BarometerMode.AUTO -> "on"
        BarometerMode.NEVER -> "off"
        BarometerMode.ALWAYS -> "always"
    }

    /** T250: the row's choices (ADR 0031: the owner adjusts). */
    fun barometerLabel(m: BarometerMode): String = when (m) {
        BarometerMode.AUTO -> "Automatic"
        BarometerMode.ALWAYS -> "Always"
        BarometerMode.NEVER -> "Never"
    }

    /** T177: the bar switch and the climb window. */
    const val BAR = "readoutBar"
    const val CLIMB_WINDOW = "climbWindow"

    /**
     * T197: the row "Set my height now" (`settings/SettingsLayout`'s `SET_HEIGHT`): an action, not a setting (ADR 0044:
     * calibrating at a known height is one practice everywhere), so nothing is stored or synced. Its field reads a
     * height as `readout/KnownHeights.parseTyped` does, in the user's units unless it names one, sets it with a sign's
     * 5 m and says "Height set to 5420 ft".
     */
    const val SET_HEIGHT = "setHeight"

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

    private val VALUE_KEYS = EntryOptions.KNOWN

    /** T257: the entries the "Extended readout" row turns on and off together, for the remembered activity. */
    val EXTENDED = listOf(ReadoutEntry.ACCURACY, ReadoutEntry.FIX_AGE)

    /** T257: the row's id and its help key; its edits are [EXTENDED]'s `readout.<entry>` (`POST /settings?readout.accuracy=shown=1&readout.fixAge=shown=1`). */
    const val EXTENDED_ROW = "readoutExtended"

    /**
     * Checks a value (whole or partial) and returns [base] with what it sets, or null when a known key has a value
     * out of range or none of the keys is known. Keys this build does not know are dropped (a newer build may add some).
     * Pace and knots are kept for speed only; pace and knots asked together keep pace.
     */
    fun merge(e: ReadoutEntry, raw: String, base: EntryOptions): EntryOptions? {
        val m = raw.split(';').mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it).trim() to kv.substring(it + 1).trim() } }.toMap()
        if (m.keys.none { it in VALUE_KEYS }) return null
        for (k in listOf("shown", "pace", "pm", "knots", "deg", "mag")) m[k]?.let { v -> if (v != "0" && v != "1") return null }
        m["damping"]?.let { if (Damping.of(it) == null) return null }
        val o = EntryOptions.decode(m.filterKeys { it in VALUE_KEYS }.entries.joinToString(";") { "${it.key}=${it.value}" }, base)
        return normalize(e, o)
    }

    /** Pace and knots only on speed; pace wins over knots. T257: degrees and magnetic only on the course. */
    fun normalize(e: ReadoutEntry, o: EntryOptions): EntryOptions {
        val c = if (e != ReadoutEntry.COURSE) o.copy(degrees = true, magnetic = false) else o
        return when {
            e != ReadoutEntry.SPEED -> c.copy(pace = false, knots = false)
            c.pace && c.knots -> c.copy(knots = false)
            else -> c
        }
    }

    /**
     * A stored or synced whole value in [EntryOptions.encode]'s form over [a]'s defaults, or null when it is refused
     * ([merge]). T257: keys this build does not know are kept ([EntryOptions.extra]).
     */
    fun parseValue(a: Activity, e: ReadoutEntry, raw: String): String? {
        val d = ReadoutOptions.phoneDefaults(a)[e]
        val o = merge(e, raw, d) ?: return null
        return o.copy(extra = EntryOptions.decode(raw, d).extra).encode()
    }

    /** [a]'s options: its defaults (T177: [ReadoutOptions.phoneDefaults]) with the [stored] values (by [key]) over them, the barometer's mode and the climb window. */
    fun options(a: Activity, stored: Map<String, String>, barometer: BarometerMode, climbWindowS: Int = ReadoutOptions.DEFAULT_CLIMB_WINDOW_S): ReadoutOptions {
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

    /** T257: smoothing and the ± belong to the estimated entries (T161's four); the pressure, accuracy and age are raw readings. */
    fun offersSmoothing(e: ReadoutEntry) = e in ESTIMATED

    /** T257: the course's degrees and its north (true or magnetic). */
    fun offersNorth(e: ReadoutEntry) = e == ReadoutEntry.COURSE

    private val ESTIMATED = setOf(ReadoutEntry.SPEED, ReadoutEntry.CLIMB, ReadoutEntry.COURSE, ReadoutEntry.ELEVATION)

    /** The entry's name as the rows and the strip's captions say it (ADR 0031: the owner adjusts). */
    fun label(e: ReadoutEntry): String = when (e) {
        ReadoutEntry.SPEED -> "Speed"
        ReadoutEntry.CLIMB -> "Climb"
        ReadoutEntry.COURSE -> "Course"
        ReadoutEntry.ELEVATION -> "Elevation"
        ReadoutEntry.PRESSURE -> "Pressure" // T257
        ReadoutEntry.ACCURACY -> "GPS accuracy"
        ReadoutEntry.FIX_AGE -> "Fix age"
    }

    /** T257: the course row's north choices (ADR 0031: the owner adjusts). */
    const val TRUE_NORTH = "True north"
    const val MAGNETIC_NORTH = "Magnetic"
    const val DEGREES = "Degrees"
}
