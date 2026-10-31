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
) {
    /** "4 km/h", "↑320 m/h ± 40", "1650 m". */
    val full: String get() = (if (unit.isEmpty()) value else "$value $unit") + (plusMinus?.let { " $it" } ?: "")

    fun json(): Map<String, Any?> = linkedMapOf(
        "entry" to entry.code, "caption" to caption, "state" to state, "value" to value, "unit" to unit, "plusMinus" to plusMinus,
        "full" to full, "other" to other, "dimmed" to dimmed, "trend" to trend, "trendLabel" to trendLabel, "trendSlot" to trendSlot,
    )

    companion object {
        const val STATE_VALUE = "value"
        const val STATE_ZERO = "zero"
        const val STATE_DASH = "dash"
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
 */
object StripText {
    val ORDER = listOf(ReadoutEntry.ELEVATION, ReadoutEntry.CLIMB, ReadoutEntry.SPEED, ReadoutEntry.COURSE)

    const val UP = "↑"
    const val DOWN = "↓"

    /** The widest gap caption ([gap] reads up to "59 min", then hours). */
    const val GAP_TEMPLATE = "no fix 59 min"

    fun caption(e: ReadoutEntry, o: ReadoutOptions): String = when (e) {
        ReadoutEntry.SPEED -> if (o.speed.pace) "pace" else "speed"
        ReadoutEntry.COURSE -> "course"
        ReadoutEntry.ELEVATION -> "elevation"
        ReadoutEntry.CLIMB -> "climb"
    }

    /** The entries the bar draws for [o], in [ORDER]. */
    fun entries(o: ReadoutOptions): List<ReadoutEntry> = ORDER.filter { o[it].shown }

    /**
     * The items the bar draws: one per entry shown in [o], from [r] (null before the estimator ran: every entry a dash).
     * [both]: the scale bar's both-units mode, each item with the other system's text under it. T178: [trend] and
     * [trendLabel] the barometer's trend arrow and words beside the elevation, [trendSlot] whether one can appear.
     */
    fun items(r: Readout?, o: ReadoutOptions, units: ContourUnits, both: Boolean = false, trend: String? = null, trendLabel: String? = null, trendSlot: Boolean = false): List<StripItem> {
        val out = ArrayList<StripItem>()
        val gap = gap(r?.gapS)
        val otherUnits = if (units == ContourUnits.FEET) ContourUnits.METERS else ContourUnits.FEET
        for (e in entries(o)) {
            val v = r?.get(e)
            val state = when (v?.state) {
                ValueState.SHOWN -> StripItem.STATE_VALUE
                ValueState.ZERO -> StripItem.STATE_ZERO
                else -> StripItem.STATE_DASH
            }
            val drawn = if (v == null || v.state == ValueState.HIDDEN) ReadoutValue(e, ValueState.DASH) else v
            val label = ReadoutText.of(drawn, o, units) ?: continue
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
                trend = if (e == ReadoutEntry.ELEVATION && trendSlot) trend else null, // T178
                trendLabel = if (e == ReadoutEntry.ELEVATION && trendSlot && trend != null) trendLabel else null,
                trendSlot = e == ReadoutEntry.ELEVATION && trendSlot,
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
     * metres, the climb an arrow and four digits, speed "88.5" (three digits by road, rail and air), pace "88:80",
     * course "388°", and the ± when asked for. Digits are equally wide in the bar's font, so 8s stand for any.
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
            ReadoutEntry.COURSE -> "388°"
        }
        val pm = if (o[e].plusMinus && !(e == ReadoutEntry.SPEED && o.speed.pace)) "± 888" else null
        return ReadoutLabel(value, if (e == ReadoutEntry.COURSE) "" else unit, pm)
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
