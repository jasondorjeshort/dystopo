package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.readout.Readout
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutOptions
import io.github.jasondorjeshort.dystopo.readout.ReadoutText
import io.github.jasondorjeshort.dystopo.render.ContourUnits

/**
 * T161: one entry of the strip as drawn: a small [caption] over the [value] and its [unit] (and the ± when asked for).
 * [entry] is null for the gap's item ("no fix" over how long).
 */
data class StripItem(
    val entry: ReadoutEntry?,
    val caption: String,
    val value: String,
    val unit: String,
    val plusMinus: String? = null,
    val dimmed: Boolean = false,
) {
    /** "4 km/h", "↑320 m/h ± 40", "1650 m". */
    val full: String get() = (if (unit.isEmpty()) value else "$value $unit") + (plusMinus?.let { " $it" } ?: "")

    fun json(): Map<String, Any?> = linkedMapOf(
        "entry" to (entry?.code ?: "gap"), "caption" to caption, "value" to value, "unit" to unit, "plusMinus" to plusMinus,
        "full" to full, "dimmed" to dimmed,
    )
}

/**
 * T161: the strip's words (pure, `StripTextTest`): T160's [ReadoutText] for the numbers and units, a caption over each
 * ("speed" or "pace", "course", "elevation", "climb"), the climb's sign as an arrow, and the gap's item. The order puts
 * climb last, since it comes and goes (hidden near zero) and the others then keep their places.
 */
object StripText {
    val ORDER = listOf(ReadoutEntry.SPEED, ReadoutEntry.COURSE, ReadoutEntry.ELEVATION, ReadoutEntry.CLIMB)

    const val UP = "↑"
    const val DOWN = "↓"

    fun caption(e: ReadoutEntry, o: ReadoutOptions): String = when (e) {
        ReadoutEntry.SPEED -> if (o.speed.pace) "pace" else "speed"
        ReadoutEntry.COURSE -> "course"
        ReadoutEntry.ELEVATION -> "elevation"
        ReadoutEntry.CLIMB -> "climb"
    }

    /** The items the strip draws for [r], in [ORDER]; hidden entries are left out, and with no entry left nothing is. */
    fun items(r: Readout, o: ReadoutOptions, units: ContourUnits): List<StripItem> {
        val out = ArrayList<StripItem>()
        for (e in ORDER) {
            val v = r[e]
            val label = ReadoutText.of(v, o, units) ?: continue
            var text = label.text
            if (e == ReadoutEntry.CLIMB && text != ReadoutText.DASH) text = if (text.startsWith("-")) DOWN + text.removePrefix("-") else UP + text
            out += StripItem(e, caption(e, o), text, label.unit, label.plusMinus, v.dimmed)
        }
        if (out.isEmpty()) return out
        gap(r.gapS)?.let { out.add(0, StripItem(null, "no fix", it, "", dimmed = false)) }
        return out
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
