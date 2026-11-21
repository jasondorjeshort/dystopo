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
    const val PEAKS_WORKING = "Finding the peaks in view..."
    const val PEAKS_NO_MAP = "No offline map with peak names here, so no peaks."
    const val PEAKS_NO_TERRAIN = "No terrain horizon here, so no peaks on it."
    /** The reason code for no peak in view or just out of sight; shown as [peaksNone] in the user's units. */
    const val PEAKS_NONE = "none in view"

    /** The plot's reach (`Horizon.DEFAULT_RANGE_M`, 30 km) in the user's units: "30 km" or "18.6 mi". */
    fun range(units: ContourUnits): String = UnitFormat.distance(io.github.jasondorjeshort.dystopo.dem.Horizon.DEFAULT_RANGE_M, units)

    fun peaksNone(units: ContourUnits) = "No named peak in view or just out of sight within ${range(units)}."

    /** T309: "12 peaks in view or just out of sight within 30 km, closest first." */
    fun peaksCount(n: Int, units: ContourUnits) =
        "$n ${if (n == 1) "peak" else "peaks"} in view or just out of sight within ${range(units)}, closest first."

    /** T309: a peak hidden by less than [io.github.jasondorjeshort.dystopo.horizon.PeakRim.HIDDEN_M]: its row and its pick say so. */
    const val JUST_OUT_OF_SIGHT = "just out of sight"

    /** One peak: "Little Tahoma, 3,410 m, 7.2 km, ESE 112°". */
    fun peakLine(name: String, eleM: Double, distanceM: Double, bearingDeg: Double, units: ContourUnits, outOfSight: Boolean = false): String =
        "$name, ${UnitFormat.elevation(eleM, units)}, ${UnitFormat.distance(distanceM, units)}, ${GnssText.compassPoint(bearingDeg)} ${Math.round(bearingDeg) % 360}°" +
            if (outOfSight) ", $JUST_OUT_OF_SIGHT" else ""

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

    // T312: the Flights layer (user-visible text says flights; code says aircraft).
    const val FLIGHTS = io.github.jasondorjeshort.dystopo.aircraft.AircraftText.LAYER
    const val SKY_RANGE = "Sky range"
    const val SKY_RANGE_LINE = "which flights the Horizon view shows: overhead, near or all"
    const val BEHIND_TERRAIN = "behind the terrain"
    const val FLIGHTS_NO_POSITION = "No position yet, so no flights."

    /** The "Near" sky range in the user's units: "31.1 mi" or "50 km". */
    fun near(units: ContourUnits) = UnitFormat.distance(io.github.jasondorjeshort.dystopo.horizon.HorizonFlights.NEAR_M, units)

    private val overheadDeg get() = Math.round(io.github.jasondorjeshort.dystopo.horizon.HorizonFlights.OVERHEAD_DEG)

    fun skyRangeHelp(units: ContourUnits) =
        "Which flights the Horizon view shows while Flight tracking is on. Overhead: only those more than ${overheadDeg}° up. " +
            "Near: those within ${near(units)} of you. All: every flight the feed reports around you."

    /** "3 flights in the air around you, closest first."; with Overhead or Near the range in place of "in the air around you". */
    fun flightsCount(n: Int, range: io.github.jasondorjeshort.dystopo.horizon.SkyRange, units: ContourUnits): String {
        val what = "$n ${if (n == 1) "flight" else "flights"}"
        return when (range) {
            io.github.jasondorjeshort.dystopo.horizon.SkyRange.OVERHEAD -> "$what overhead (above ${overheadDeg}°), closest first."
            io.github.jasondorjeshort.dystopo.horizon.SkyRange.NEAR -> "$what within ${near(units)}, closest first."
            io.github.jasondorjeshort.dystopo.horizon.SkyRange.ALL -> "$what in the air around you, closest first."
        }
    }

    fun flightsNone(range: io.github.jasondorjeshort.dystopo.horizon.SkyRange, units: ContourUnits): String = when (range) {
        io.github.jasondorjeshort.dystopo.horizon.SkyRange.OVERHEAD -> "No flight overhead now (above ${overheadDeg}°)."
        io.github.jasondorjeshort.dystopo.horizon.SkyRange.NEAR -> "No flight within ${near(units)} now."
        io.github.jasondorjeshort.dystopo.horizon.SkyRange.ALL -> "No flight in the air around you now."
    }

    /** A row's second line: "12.4 mi · NE 45° · 11° up · 35,000 ft", then "behind the terrain" or the stale age. */
    fun flightLine(distanceM: Double, bearingDeg: Double, elevationDeg: Double, altitude: String?, hidden: Boolean, staleAgeS: Double?, units: ContourUnits): String {
        val up = if (elevationDeg >= 0) "${angle(elevationDeg)}° up" else "${angle(-elevationDeg)}° below level"
        return listOfNotNull(
            UnitFormat.distance(distanceM, units), "${GnssText.compassPoint(bearingDeg)} ${Math.round(bearingDeg) % 360}°", up, altitude,
            if (hidden) BEHIND_TERRAIN else null, staleAgeS?.let { "${Math.round(it)} s ago" },
        ).joinToString(" · ")
    }

    /** The kind a row names, from the emitter category ("Helicopter"); null when the feed says nothing. */
    fun kind(k: io.github.jasondorjeshort.dystopo.aircraft.AircraftKind): String? = when (k) {
        io.github.jasondorjeshort.dystopo.aircraft.AircraftKind.LIGHT -> "Light aircraft"
        io.github.jasondorjeshort.dystopo.aircraft.AircraftKind.AIRLINER -> "Airliner"
        io.github.jasondorjeshort.dystopo.aircraft.AircraftKind.HEAVY -> "Heavy jet"
        io.github.jasondorjeshort.dystopo.aircraft.AircraftKind.ROTORCRAFT -> "Helicopter"
        io.github.jasondorjeshort.dystopo.aircraft.AircraftKind.GLIDER -> "Glider"
        io.github.jasondorjeshort.dystopo.aircraft.AircraftKind.BALLOON -> "Balloon"
        io.github.jasondorjeshort.dystopo.aircraft.AircraftKind.UAV -> "Drone"
        io.github.jasondorjeshort.dystopo.aircraft.AircraftKind.GENERIC -> null
    }

    private fun angle(d: Double) = if (d < 10) UnitFormat.fixed(d, 1) else Math.round(d).toString()

    // T317: the sky from another place (the place line's own words are shared `horizon/HorizonPlace`).
    /** The long-press card's action, beside "What can I see from here". */
    const val SKY_FROM_HERE = "Sky from here"
    const val BACK = io.github.jasondorjeshort.dystopo.horizon.HorizonPlace.BACK
    /** Under the toggles at a chosen point, where Satellites and Flights are disabled. */
    const val MOVED_LAYERS = "Satellites and flights show only at your location."
    /** The place line's label for screen readers, before its text ("Horizon from: At Mount Rainier, ..."). */
    const val PLACE_LABEL = "Horizon from"

    // The plot's description for screen readers.
    const val PLOT_LABEL = "Horizon plot"
    fun plotLabel(parts: List<String>) = (listOf(PLOT_LABEL) + parts).joinToString("; ")

}
