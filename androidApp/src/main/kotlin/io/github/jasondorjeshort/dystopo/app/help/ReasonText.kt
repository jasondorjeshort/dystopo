package io.github.jasondorjeshort.dystopo.app.help

import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutFault
import io.github.jasondorjeshort.dystopo.readout.ReadoutReason
import io.github.jasondorjeshort.dystopo.readout.ReadoutText
import io.github.jasondorjeshort.dystopo.readout.ReadoutTuning
import io.github.jasondorjeshort.dystopo.readout.ValueState
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat

/**
 * T363: the readout's reasons in words, beside the help registry (pure; `ReasonTextTest`). The owner's answer of
 * 2026-11-23: a value that is not a plain figure gets one line under it in the glance view saying what the state means
 * and what would end it ([sentence]), the bar's description gains the state's few words ([words]), and the glance view
 * lists what went wrong in the last minute ([fault], [pinEnd]) and what the bar leaves out for room ([leftOut]). The
 * sentences are short enough for one line of the glance view at font 1.0 ([MAX_SENTENCE]); ADR 0031: the owner adjusts
 * them. No other product's name. The help registry's texts are paragraphs of 60 to 330 characters; these are one line,
 * so they are their own table with their own test (the audit of 2026-11-23, Mechanical checks).
 */
object ReasonText {
    /** The longest sentence (characters): one line of the glance view on a phone upright at font 1.0. */
    const val MAX_SENTENCE = 56

    /** How long the glance view lists a fault after it ended (the owner: "in the last 60 seconds"). */
    const val RECENT_MS = 60_000L

    private val fill = minOf(ReadoutTuning.CLIMB_MIN_FILL_S.toInt(), 30)

    /**
     * The sentences by (entry, reason). A figure's state (dimmed for the fix stream) and a dash's differ only where the
     * words do ([sentence] picks the figure's for [ValueState.SHOWN] and [ValueState.ZERO]).
     */
    val TABLE: Map<Pair<ReadoutEntry, ReadoutReason>, String> = buildMap {
        for (e in ReadoutEntry.entries) {
            put(e to ReadoutReason.NO_FIX, "No GPS fix: shown once fixes come.")
            put(e to ReadoutReason.RESUMING, "GPS just came back: clears with the next fix.")
            put(e to ReadoutReason.ACTIVITY, "Activity changed: clears with the next GPS fix.")
        }
        put(ReadoutEntry.ELEVATION to ReadoutReason.NO_FIX, "No GPS fix: the last height, until a fix comes.")
        put(ReadoutEntry.ELEVATION to ReadoutReason.HELD, "Phone clock went back: last height, kept up to 1 min.")
        put(ReadoutEntry.ELEVATION to ReadoutReason.NO_HEIGHT_YET, "No height yet: comes with a GPS fix or the map.")
        put(ReadoutEntry.CLIMB to ReadoutReason.HELD, "No GPS fix: the rate at the last fix, until one comes.")
        put(ReadoutEntry.CLIMB to ReadoutReason.NO_HEIGHT_YET, "No height yet, so no climb: comes with the height.")
        put(ReadoutEntry.CLIMB to ReadoutReason.FILLING, "Still measuring: a rate shows after $fill s of fixes.")
        put(ReadoutEntry.CLIMB to ReadoutReason.UNCERTAIN, "Too unsure to tell from level: waits for steadier data.")
        put(ReadoutEntry.CLIMB to ReadoutReason.CONTRADICTION, "Barometer and GPS disagree: back once they agree.")
        put(ReadoutEntry.SPEED to ReadoutReason.NO_SPEED_YET, "No speed yet: shown after a few fixes on the move.")
        put(ReadoutEntry.SPEED to ReadoutReason.STILL, "Standing still: shown again once you move.")
        put(ReadoutEntry.COURSE to ReadoutReason.STILL, "Standing still: the way you last moved.")
        put(ReadoutEntry.COURSE to ReadoutReason.NO_COURSE_YET, "Not moved yet: shown once you move.")
        put(ReadoutEntry.PRESSURE to ReadoutReason.NO_SAMPLES, "No barometer reading yet: shown with the first.")
        put(ReadoutEntry.PRESSURE to ReadoutReason.SILENT, "Barometer silent: shown again when it reads.")
        put(ReadoutEntry.ACCURACY to ReadoutReason.NOT_GIVEN, "This fix gave no accuracy: shown when one does.")
        put(ReadoutEntry.FIX_AGE to ReadoutReason.NO_FIX, "No GPS fix yet: counts from the first fix.")
        // T384: "no fix" while the GPS searches for one (ReadoutReasons.searching); the glance view's GPS area says how it goes.
        for (k in io.github.jasondorjeshort.dystopo.readout.ReadoutReasons.WHILE_SEARCHING) put(k, "GPS is searching: shown once it has a fix.")
        put(ReadoutEntry.FIX_AGE to ReadoutReason.SEARCHING, "GPS is searching: counts from the first fix.")
    }

    /** A dimmed figure in a gap: the value is the last fix's. */
    const val NO_FIX_FIGURE = "No GPS fix: the value at the last fix, until one comes."

    /** T384: a dimmed figure while the GPS searches: the value is the last fix's. */
    const val SEARCHING_FIGURE = "GPS is searching: the value at the last fix till then."

    /** T384: the elevation's dimmed figure while the GPS searches. */
    const val SEARCHING_HEIGHT = "GPS is searching: the last height until it has a fix."

    /** The sentence under [entry]'s value in [state] for [reason], or null where the table has none. */
    fun sentence(entry: ReadoutEntry, state: ValueState?, reason: ReadoutReason?): String? {
        reason ?: return null
        val figure = state == ValueState.SHOWN || state == ValueState.ZERO
        if (reason == ReadoutReason.SEARCHING && figure && entry != ReadoutEntry.FIX_AGE) return if (entry == ReadoutEntry.ELEVATION) SEARCHING_HEIGHT else SEARCHING_FIGURE
        if (reason == ReadoutReason.NO_FIX && entry != ReadoutEntry.ELEVATION) return if (figure) NO_FIX_FIGURE else TABLE[entry to reason]
        if (reason == ReadoutReason.NO_FIX && !figure) return TABLE[ReadoutEntry.SPEED to reason] // the elevation's before any readout
        return TABLE[entry to reason]
    }

    /** The few words a screen reader says for [reason] after the bar's figures ("elevation: no GPS fix"). */
    fun words(reason: ReadoutReason): String = when (reason) {
        ReadoutReason.OFF -> "off"
        ReadoutReason.NO_FIX -> "no GPS fix"
        ReadoutReason.RESUMING -> "GPS just came back"
        ReadoutReason.ACTIVITY -> "activity changed"
        ReadoutReason.HELD -> "last known value"
        ReadoutReason.NO_HEIGHT_YET -> "no height yet"
        ReadoutReason.NO_SPEED_YET -> "no speed yet"
        ReadoutReason.NO_COURSE_YET -> "not moved yet"
        ReadoutReason.STILL -> "standing still"
        ReadoutReason.NEAR_ZERO -> "level"
        ReadoutReason.CONTRADICTION -> "barometer and GPS disagree"
        ReadoutReason.FILLING -> "still measuring"
        ReadoutReason.UNCERTAIN -> "too unsure"
        ReadoutReason.NO_SAMPLES -> "no reading yet"
        ReadoutReason.SILENT -> "barometer silent"
        ReadoutReason.NOT_GIVEN -> "not given"
        ReadoutReason.SEARCHING -> "GPS searching" // T384
    }

    /** "8 s", "3 min", "2 h": a time span as the lines say it. */
    fun span(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) + 500) / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h"
        }
    }

    /** When [f] ended (a gap at its end), on the elapsed clock. */
    fun endedAt(f: ReadoutFault): Long = f.atElapsedMs + (f.lengthMs ?: 0L)

    /**
     * The glance view's lines for what went wrong in the last minute at [nowElapsedMs], newest first: each fault of
     * [record] ended within [RECENT_MS] (a gap still going is left out: the values' own lines say it), the late fixes
     * as one line with their count; distances in [units].
     */
    fun recent(record: List<ReadoutFault>, nowElapsedMs: Long, units: ContourUnits): List<String> {
        val within = record.filter { !it.ongoing && nowElapsedMs - endedAt(it) in 0..RECENT_MS }
        val late = within.filter { it.kind == ReadoutFault.LATE_FIX }
        val out = ArrayList<Pair<Long, String>>()
        for (f in within) if (f.kind != ReadoutFault.LATE_FIX) fault(f, nowElapsedMs, units)?.let { out += endedAt(f) to it }
        late.maxByOrNull { it.atElapsedMs }?.let { last ->
            val ago = span(nowElapsedMs - last.atElapsedMs)
            out += last.atElapsedMs to if (late.size == 1) "1 late GPS fix skipped, $ago ago" else "${late.size} late GPS fixes skipped, last $ago ago"
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /** One fault as a line ("GPS lost for 9 s, 40 s ago"), or null for a kind or cause with no words. */
    fun fault(f: ReadoutFault, nowElapsedMs: Long, units: ContourUnits): String? {
        val ago = span(nowElapsedMs - endedAt(f))
        return when (f.kind) {
            ReadoutFault.GAP -> "GPS lost for ${span(f.lengthMs ?: 0L)}, $ago ago"
            ReadoutFault.BAROMETER_SILENT -> "Barometer silent for ${span(f.lengthMs ?: 0L)}, $ago ago"
            ReadoutFault.LATE_FIX -> "1 late GPS fix skipped, $ago ago"
            ReadoutFault.RESTART -> when (f.cause) {
                ReadoutFault.CAUSE_JUMP -> "Position jumped" + (f.detail?.let { " " + UnitFormat.distance(it, units) } ?: "") + ", $ago ago"
                ReadoutFault.CAUSE_CLOCK -> "Phone clock went back" + (f.detail?.let { " " + ReadoutText.age(it).let { a -> "${a.text} ${a.unit}" } } ?: "") + ", $ago ago"
                ReadoutFault.CAUSE_ACTIVITY -> "Activity changed, $ago ago"
                else -> null
            }
            else -> null
        }
    }

    /** The fault kinds and restart causes [fault] gives words to (`ReasonTextTest` checks each). */
    val FAULT_KINDS = listOf(ReadoutFault.GAP, ReadoutFault.RESTART, ReadoutFault.BAROMETER_SILENT, ReadoutFault.LATE_FIX)
    val RESTART_CAUSES = listOf(ReadoutFault.CAUSE_JUMP, ReadoutFault.CAUSE_CLOCK, ReadoutFault.CAUSE_ACTIVITY)

    /**
     * The known height's mark went ([code] the readout's `pinEnd`: `sigma`, `barometer`, `disagreement`; `replaced` is a
     * new one and says nothing) [agoMs] ago: "Set height no longer used, 12 s ago: barometer not in use".
     */
    fun pinEnd(code: String?, agoMs: Long): String? {
        val why = when (code) {
            "sigma" -> "too long since it was set"
            "barometer" -> "barometer not in use"
            "disagreement" -> "barometer and GPS disagreed"
            "replaced" -> return null
            else -> null
        }
        return "Set height no longer used, ${span(agoMs)} ago" + (why?.let { ": $it" } ?: "")
    }

    /** The pin's end codes [pinEnd] gives words to. */
    val PIN_ENDS = listOf("sigma", "barometer", "disagreement")

    /**
     * The line for what the bar leaves out for room ([dropped]: entry codes and `courseDegrees`, as `GET /readout` ›
     * `layout` › `droppedItems`), or for the whole bar hidden for room ([hiddenForRoom]); null when it shows everything.
     */
    fun leftOut(dropped: List<String>, hiddenForRoom: Boolean): String? {
        if (hiddenForRoom) return "The bar is hidden for room: every value is here."
        if (dropped.isEmpty()) return null
        val names = dropped.map { d -> if (d == "courseDegrees") "the course's degrees" else ReadoutEntry.entries.firstOrNull { it.code == d }?.let { caption(it) } ?: d }
        return "Left off the bar for room: " + names.joinToString(", ") + "."
    }

    private fun caption(e: ReadoutEntry): String = when (e) {
        ReadoutEntry.FIX_AGE -> "fix age"
        else -> e.code
    }

    /** The heading over the glance view's last-minute lines. */
    const val RECENT_HEADING = "In the last minute"
}
