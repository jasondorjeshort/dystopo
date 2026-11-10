package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.readout.Readout
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutLabel
import io.github.jasondorjeshort.dystopo.readout.ReadoutOptions
import io.github.jasondorjeshort.dystopo.readout.ReadoutText
import io.github.jasondorjeshort.dystopo.readout.ReadoutValue
import io.github.jasondorjeshort.dystopo.readout.ValueState
import io.github.jasondorjeshort.dystopo.render.ContourUnits

/**
 * T161: one entry of the bar as drawn: a small [caption] over the [value] and its [unit] (and the ± when asked for).
 * T177: every shown entry is always drawn, [state] `value`, `zero` or `dash`; [other] is the other unit system's text
 * in both-units mode ("5414 ft" under "1650 m"); [template], [captionTemplate] and [otherTemplate] are the widest
 * texts the entry can show, drawn invisibly under the real ones so the entry's width never changes and nothing after
 * it moves. [trend] is the place for an arrow beside the elevation (T178: the barometer's trend, [trendLabel] its words,
 * "falling slowly"); [trendSlot] widens the elevation's template by the widest arrow whenever an arrow can appear (the
 * barometer switch on and a barometer present), so the climb does not move when it comes and goes.
 *
 * T257: the arrow sits beside the pressure instead while the pressure entry is drawn ([trendSlot] on that item); the
 * elevation keeps the known height's mark slot ([pinSlot], T197's, which rode on [trendSlot] before). [compact] is the
 * course's point without its degrees ("SSE" for "SSE 163°") and [compactTemplate] its widest form: the bar draws it
 * when its room is short, before it leaves out any entry.
 */
data class StripItem(
    val entry: ReadoutEntry,
    val caption: String,
    val value: String,
    val unit: String,
    val plusMinus: String? = null,
    val dimmed: Boolean = false,
    val state: String = STATE_VALUE,
    val other: String? = null,
    val template: ReadoutLabel = ReadoutLabel(value, unit, plusMinus),
    val captionTemplate: String = caption,
    val otherTemplate: String? = null,
    val trend: String? = null,
    val trendLabel: String? = null,
    val trendSlot: Boolean = false,
    val compact: String? = null,
    val compactTemplate: String? = null,
    val pinSlot: Boolean = false,
) {
    /** "4 km/h", "↑320 m/h ± 40", "1650 m". */
    val full: String get() = (if (unit.isEmpty()) value else "$value $unit") + (plusMinus?.let { " $it" } ?: "")

    /** T257: the item as the bar draws it when short of room: the course's point alone, every other item [full]. */
    val short: String get() = compact ?: full

    fun json(): Map<String, Any?> = linkedMapOf(
        "entry" to entry.code, "caption" to caption, "state" to state, "value" to value, "unit" to unit, "plusMinus" to plusMinus,
        "full" to full, "other" to other, "dimmed" to dimmed, "trend" to trend, "trendLabel" to trendLabel, "trendSlot" to trendSlot,
        "compact" to compact, // T257
    )

    companion object {
        const val STATE_VALUE = "value"
        const val STATE_ZERO = "zero"
        const val STATE_DASH = "dash"

        /** T250: "?": the climb's data is insufficient (its window filling, too uncertain to tell, no fix, no height yet). */
        const val STATE_UNKNOWN = "unknown"
    }
}

/**
 * T161: the bar's words (pure, `StripTextTest`): T160's [ReadoutText] for the numbers and units, a caption over each
 * ("elevation", "climb", "speed" or "pace", "course"), the climb's sign as an arrow.
 *
 * T177: every entry shown in the options is drawn, in [ORDER] (elevation and climb first, side by side; the owner,
 * 2026-10-30), with a dash where the value is not known (before a fix, no height yet, the climb window filling, the
 * climb near zero while standing) and "0" for a climb near zero while moving, so the bar never comes and goes or
 * changes width. A gap of the fixes no longer adds an item: the first entry's caption reads "no fix" with how long,
 * and the values dim.
 *
 * T250, the climb's cell says what the estimate knows: a figure, "0" for a near zero the estimate could tell (moving or
 * standing), "?" while the data is insufficient (the window filling, too uncertain, no fix, no height yet, before the
 * first readout), a dash only while the barometer and the GNSS contradict and the barometer is out for it.
 *
 * T257: the course reads "SSE 163°" ([ReadoutText.course]; magnetic "M" when chosen and known), then come the pressure
 * (hPa or inHg, with the trend's arrow) and the extended readout's GNSS accuracy and fix age, in that order, so the bar
 * leaves out the fix age, the accuracy and the pressure before the course, the speed, the climb and the elevation; the
 * course's degrees go before any entry ([StripItem.compact]). The pressure is drawn only on a phone with a barometer
 * (or while a replay feeds one), a dash until a sample comes.
 */
object StripText {
    val ORDER = listOf(
        ReadoutEntry.ELEVATION, ReadoutEntry.CLIMB, ReadoutEntry.SPEED, ReadoutEntry.COURSE,
        ReadoutEntry.PRESSURE, ReadoutEntry.ACCURACY, ReadoutEntry.FIX_AGE, // T257
    )

    /** T257: what `GET /readout` › `layout` › `droppedItems` names first when the bar draws the course's point alone. */
    const val COURSE_DEGREES = "courseDegrees"

    const val UP = "↑"
    const val DOWN = "↓"

    /** The widest gap caption ([gap] reads up to "59 min", then hours). */
    const val GAP_TEMPLATE = "no fix 59 min"

    fun caption(e: ReadoutEntry, o: ReadoutOptions): String = when (e) {
        ReadoutEntry.SPEED -> if (o.speed.pace) "pace" else "speed"
        ReadoutEntry.COURSE -> "course"
        ReadoutEntry.ELEVATION -> "elevation"
        ReadoutEntry.CLIMB -> "climb"
        ReadoutEntry.PRESSURE -> "pressure" // T257
        ReadoutEntry.ACCURACY -> "accuracy"
        ReadoutEntry.FIX_AGE -> "fix age"
    }

    /** The entries the bar draws for [o], in [ORDER]; T257: the pressure only when [pressureDrawn] (the phone has a barometer). */
    fun entries(o: ReadoutOptions, pressureDrawn: Boolean = false): List<ReadoutEntry> =
        ORDER.filter { o[it].shown && (it != ReadoutEntry.PRESSURE || pressureDrawn) }

    /**
     * The items the bar draws: one per entry shown in [o], from [r] (null before the estimator ran: every entry a dash).
     * [both]: the scale bar's both-units mode, each item with the other system's text under it. T178: [trend] and
     * [trendLabel] the barometer's trend arrow and words beside the elevation, [trendSlot] whether one can appear (T257:
     * beside the pressure while it is drawn). T257: [declinationDeg] the WMM's at the fix for a magnetic course,
     * [pressureDrawn] whether the pressure entry is drawn (a barometer present).
     */
    fun items(
        r: Readout?, o: ReadoutOptions, units: ContourUnits, both: Boolean = false, trend: String? = null, trendLabel: String? = null,
        trendSlot: Boolean = false, declinationDeg: Double? = null, pressureDrawn: Boolean = false,
    ): List<StripItem> {
        val out = ArrayList<StripItem>()
        val gap = gap(r?.gapS)
        val otherUnits = if (units == ContourUnits.FEET) ContourUnits.METERS else ContourUnits.FEET
        val shown = entries(o, pressureDrawn)
        // T257: the trend's arrow goes with the pressure while it is drawn, else with the elevation as before.
        val arrowOn = if (ReadoutEntry.PRESSURE in shown) ReadoutEntry.PRESSURE else ReadoutEntry.ELEVATION
        for (e in shown) {
            val v = r?.get(e)
            // T250: the climb with no value yet is "?" (too little data), the other entries a dash as before.
            val missing = if (e == ReadoutEntry.CLIMB) ValueState.UNKNOWN else ValueState.DASH
            val drawn = if (v == null || v.state == ValueState.HIDDEN) ReadoutValue(e, missing) else v
            val state = when (drawn.state) {
                ValueState.SHOWN -> StripItem.STATE_VALUE
                ValueState.ZERO -> StripItem.STATE_ZERO
                ValueState.UNKNOWN -> StripItem.STATE_UNKNOWN
                else -> StripItem.STATE_DASH
            }
            val label = ReadoutText.of(drawn, o, units, declinationDeg) ?: continue
            val other = if (both) ReadoutText.other(drawn, o, units) else null
            val first = out.isEmpty()
            out += StripItem(
                entry = e,
                caption = if (first && gap != null) "no fix $gap" else caption(e, o),
                value = arrow(e, state, label.text),
                unit = label.unit,
                plusMinus = label.plusMinus,
                dimmed = v?.dimmed ?: false,
                state = state,
                other = other?.let { joined(arrow(e, state, it.text), it.unit) },
                template = template(e, o, units),
                captionTemplate = if (first) longer(caption(e, o), GAP_TEMPLATE) else caption(e, o),
                otherTemplate = if (other != null) template(e, o, otherUnits).let { joined(it.text, it.unit) } else null,
                trend = if (e == arrowOn && trendSlot) trend else null, // T178
                trendLabel = if (e == arrowOn && trendSlot && trend != null) trendLabel else null,
                trendSlot = e == arrowOn && trendSlot,
                compact = label.compact, // T257
                compactTemplate = if (e == ReadoutEntry.COURSE && o.course.degrees) courseTemplate(o, degrees = false) else null,
                pinSlot = e == ReadoutEntry.ELEVATION && trendSlot, // T197's mark, on a phone with a barometer
            )
        }
        return out
    }

    private fun joined(text: String, unit: String) = if (unit.isEmpty()) text else "$text $unit"

    /** The climb's sign as an arrow on a value; a zero and a dash have none. */
    private fun arrow(e: ReadoutEntry, state: String, text: String): String =
        if (e != ReadoutEntry.CLIMB || state != StripItem.STATE_VALUE || text == ReadoutText.DASH) text
        else if (text.startsWith("-")) DOWN + text.removePrefix("-") else UP + text

    private fun longer(a: String, b: String) = if (b.length > a.length) b else a

    /**
     * The widest text [e] shows in [units] (T177): elevation up to five digits in feet (the highest summits) and four in
     * metres, the climb an arrow and four digits (T257: or three and "k"), speed "88.5" (three digits by road, rail and
     * air), pace "88:80", course "WNW 388°" (T257; "W" the widest letter, the mark after it when magnetic is chosen),
     * the pressure "1088" hPa or "88.88" inHg, the accuracy four digits, the fix age "88 min", and the ± when asked
     * for. Digits are equally wide in the bar's font, so 8s stand for any.
     */
    fun template(e: ReadoutEntry, o: ReadoutOptions, units: ContourUnits): ReadoutLabel {
        val unit = ReadoutText.of(ReadoutValue(e, ValueState.DASH), o, units)?.unit.orEmpty()
        val value = when (e) {
            ReadoutEntry.ELEVATION -> if (units == ContourUnits.FEET) "28888" else "8888"
            ReadoutEntry.CLIMB -> "${UP}8880"
            ReadoutEntry.SPEED -> when {
                o.speed.pace -> "88:80"
                o.activity == Activity.ROAD || o.activity == Activity.RAIL || o.activity == Activity.AIR -> "888"
                else -> "88.5"
            }
            ReadoutEntry.COURSE -> courseTemplate(o, o.course.degrees)
            ReadoutEntry.PRESSURE -> if (units == ContourUnits.FEET) "88.88" else "1088"
            ReadoutEntry.ACCURACY -> "8888" // a network fix can be off by a kilometre or more
            ReadoutEntry.FIX_AGE -> "88"
        }
        val pm = if (o[e].plusMinus && ReadoutSettings.offersSmoothing(e) && !(e == ReadoutEntry.SPEED && o.speed.pace) && !(e == ReadoutEntry.COURSE && !o.course.degrees)) "± 888" else null
        return ReadoutLabel(value, if (e == ReadoutEntry.COURSE) "" else if (e == ReadoutEntry.FIX_AGE) "min" else unit, pm)
    }

    /** T257: the course's widest text: "WNW 388°", the point alone without [degrees], " M" after it when magnetic is chosen. */
    fun courseTemplate(o: ReadoutOptions, degrees: Boolean): String =
        "WNW" + (if (degrees) " 388°" else "") + (if (o.course.magnetic) " " + ReadoutText.MAGNETIC_MARK else "")

    /**
     * T210 (T204's follow-up): the bar's description for a screen reader, the entries in [drawn] only ("elevation 1650 m ·
     * climb ↑320 m/h"), with the trend's words and the known height's [mark] label as `GET /readout` `line` has them.
     * Where the bar leaves entries out for room it no longer reads them; a tap (a double tap with a screen reader) opens
     * the glance view, which shows and reads every entry. Null with nothing drawn. T257: [short] while the bar draws
     * the course's point alone ([StripItem.short]).
     */
    fun line(drawn: List<StripItem>, mark: String? = null, short: Boolean = false): String? = drawn.takeIf { it.isNotEmpty() }?.let { l ->
        l.joinToString(" · ") { "${it.caption} ${if (short) it.short else it.full}" + (it.trendLabel?.let { t -> ", pressure $t" } ?: "") } +
            (if (mark != null && l.any { it.entry == ReadoutEntry.ELEVATION }) " · $mark" else "")
    }

    /** How long the fixes have been missing, or null when they are not: "8 s", "3 min", "2 h". */
    fun gap(gapS: Double?): String? {
        val s = gapS ?: return null
        return when {
            s < 60 -> "${s.toLong()} s"
            s < 3600 -> "${(s / 60).toLong()} min"
            else -> "${(s / 3600).toLong()} h"
        }
    }
}
