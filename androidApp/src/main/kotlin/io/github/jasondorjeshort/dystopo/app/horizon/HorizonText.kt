package io.github.jasondorjeshort.dystopo.app.horizon

import io.github.jasondorjeshort.dystopo.app.gnss.GnssText
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat

/**
 * T308: the Horizon view's words (ADR 0031: built in alpha, the owner adjusts them; no other product named). The
 * satellites layer keeps T307's words ([GnssText]).
 */
object HorizonText {
    const val TITLE = "Horizon view"
    const val LAYERS = "Show"
    const val NO_LAYERS = "Every layer is off: the plot shows the land's skyline only."

    // Peaks.
    const val PEAKS = "Peaks"
    const val PEAKS_WORKING = "Finding the peaks on the skyline..."
    const val PEAKS_NO_MAP = "No offline map with peak names here, so no peaks."
    const val PEAKS_NO_TERRAIN = "No terrain horizon here, so no peaks on it."
    /** The reason code for no peak on the skyline; shown as [peaksNone] in the user's units. */
    const val PEAKS_NONE = "none on the skyline"

    /** The plot's reach (`Horizon.DEFAULT_RANGE_M`, 30 km) in the user's units: "30 km" or "18.6 mi". */
    fun range(units: ContourUnits): String = UnitFormat.distance(io.github.jasondorjeshort.dystopo.dem.Horizon.DEFAULT_RANGE_M, units)

    fun peaksNone(units: ContourUnits) = "No named peak stands on the skyline within ${range(units)}."

    fun peaksCount(skyline: Int, named: Int, units: ContourUnits) =
        if (skyline == named) "$skyline on the skyline within ${range(units)}." else "$skyline on the skyline within ${range(units)}; the highest of each 30° of the rim is named on the plot."

    /** One peak: "Little Tahoma, 3,410 m, 7.2 km, ESE 112°". */
    fun peakLine(name: String, eleM: Double, distanceM: Double, bearingDeg: Double, units: ContourUnits): String =
        "$name, ${UnitFormat.elevation(eleM, units)}, ${UnitFormat.distance(distanceM, units)}, ${GnssText.compassPoint(bearingDeg)} ${Math.round(bearingDeg) % 360}°"

    const val PEAK_CLEAR = "Clear the peak"

    // The sun.
    const val SUN = "Sun"
    const val SUN_NO_POSITION = "No position yet, so no sun path."
    const val TODAY = "Today"

    /** "Now 23° up toward SSW", "Now below the horizon", "Now behind the terrain, 4° up toward SW". */
    fun sunNow(altDeg: Double, azDeg: Double, behind: Boolean): String = when {
        altDeg < -0.5 -> "Now below the horizon."
        behind -> "Now behind the terrain, ${Math.round(altDeg)}° up toward ${GnssText.compassPoint(azDeg)}."
        else -> "Now ${Math.round(altDeg)}° up toward ${GnssText.compassPoint(azDeg)}."
    }

    /** "7 h 28 min of sun here." */
    fun sunMinutes(minutes: Double) = "${hoursMinutes(minutes)} of sun here."
    const val SUN_ALL_DAY = "The sun stays above the terrain all day."
    const val SUN_NEVER = "The sun stays behind the terrain all day."

    /** "Behind the terrain 12:10 to 12:40, toward S." */
    fun gap(fromText: String, toText: String, bearingDeg: Double) = "Behind the terrain $fromText to $toText, toward ${GnssText.compassPoint(bearingDeg)}."

    /** "Clears the terrain at 07:55, goes behind it at 15:40." (the first time it comes out, the last time it goes). */
    fun terrainTimes(riseText: String?, setText: String?) = listOfNotNull(
        riseText?.let { "clears the terrain at $it" }, setText?.let { "goes behind it at $it" },
    ).joinToString(", ").replaceFirstChar { it.uppercase() } + "."

    fun flat(riseText: String?, setText: String?) = "Over a flat horizon: " + listOfNotNull(
        riseText?.let { "sunrise $it" }, setText?.let { "sunset $it" },
    ).ifEmpty { listOf("no sunrise or sunset") }.joinToString(", ") + "."

    const val SUN_FLAT_ONLY = "No terrain horizon here: the times are over a flat horizon."
    const val SUN_LEGEND = "Solid: the sun's path in view; dashed: behind the terrain; dots each hour."

    fun hoursMinutes(minutes: Double): String {
        val m = Math.round(minutes).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    // The plot's description for screen readers.
    const val PLOT_LABEL = "Horizon plot"
    fun plotLabel(parts: List<String>) = (listOf(PLOT_LABEL) + parts).joinToString("; ")

}
