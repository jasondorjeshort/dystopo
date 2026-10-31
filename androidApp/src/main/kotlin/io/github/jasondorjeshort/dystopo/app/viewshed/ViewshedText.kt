package io.github.jasondorjeshort.dystopo.app.viewshed

import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * T182: the viewshed's user-visible strings in one place (ADR 0031: listed in the task report), so the sheet, the
 * settings row, the help and `GET /viewshed` say the same thing. No other product's name appears (owner, 2026-10-13).
 */
object ViewshedText {
    const val TITLE = "What can I see from here"
    const val ACTION = "What can I see from here"
    const val CLOSE = "Close"
    /** Map options: the row's hint, its chip and the range row's label. */
    const val ROW_HINT = "green where the ground is in sight, with the peaks in view"
    const val FROM_POSITION = "From where I am"
    const val RANGE_ROW = "View range"
    const val SORT = "Sort:"
    const val NO_PEAKS = "No named peaks in view."
    const val NO_PEAKS_LAYER = "No named peaks: the offline maps here have no peak names."
    const val NO_DEM = "No elevation data here: download an offline area around this point first."
    const val NO_FIX = "Waiting for your position."
    const val ON_SKYLINE = "on the skyline"
    const val LEGEND = "Green: ground you can see from here at eye height. Clear: hidden ground."
    const val DATA_STOPPED = "Hatched: no elevation data there, so nothing is known past it."

    /** A range choice: "30 km", or in feet units the miles it is ("19 mi"). */
    fun range(km: Int, units: ContourUnits): String =
        if (units == ContourUnits.FEET) "${(km * 1000 / UnitFormat.METERS_PER_MILE).roundToInt()} mi" else "$km km"

    /** The sheet's status line while the sweep runs. */
    fun looking(km: Int, units: ContourUnits): String = "Looking ${range(km, units)} around…"

    /** The sheet's status line once done: the range and the time it took. */
    fun done(km: Int, units: ContourUnits, ms: Double, peaks: Int): String {
        val secs = String.format(Locale.US, "%.1f s", ms / 1000)
        val p = when (peaks) { 0 -> "no peaks in view"; 1 -> "1 peak in view"; else -> "$peaks peaks in view" }
        return "${range(km, units)} around, $p ($secs)"
    }

    /** A true bearing with its compass letters: "NW 315°". */
    fun bearing(deg: Double, compass: String): String = "$compass ${deg.roundToInt() % 360}°"

    /** One row's second line: "4392 m · 7.6 km · NNW 345° · on the skyline". */
    fun peakLine(p: PeakView, units: ContourUnits): String =
        listOfNotNull(
            UnitFormat.elevation(p.eleM, units), UnitFormat.distance(p.distanceM, units), bearing(p.bearingDeg, p.compass),
            ON_SKYLINE.takeIf { p.skyline },
        ).joinToString(" · ")
}
