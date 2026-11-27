package io.github.jasondorjeshort.dystopo.app.gnss

import io.github.jasondorjeshort.dystopo.gnss.GnssHealth
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat

/**
 * T307: the GPS view's words, now the Horizon view's Satellites layer (T308; the screen's own words are `HorizonText`) (ADR 0031: built in alpha, the owner adjusts them). No other product is named (the owner's
 * rule of 2026-10-13); the systems (GPS, GLONASS, Galileo, BeiDou) are the satellites' own names.
 */
object GnssText {
    /** T308: the screen and its Settings row ("GPS status" until T308). */
    const val TITLE = io.github.jasondorjeshort.dystopo.app.horizon.HorizonText.TITLE
    const val SATELLITES = "Satellites"
    const val MORE = "More actions"
    const val CHECK = "Check my GPS"
    const val CHECK_HINT = "Stand still outdoors for 2 minutes, phone face up, to check the GPS."
    const val STOP = "Stop"
    const val STOPPED = "The check was stopped."
    const val CHECK_AGAIN = "Check again"

    fun checking(left: Int) = "Checking: stand still, $left s left"

    /** "…more than 20 m" or "…more than 65 ft" (the 20 m limit in feet, rounded down to 5 ft). */
    fun moved(units: ContourUnits) =
        "The check stopped: the phone moved more than ${movedLimit(units)}. Stand still and try again."

    fun movedLimit(units: ContourUnits): String =
        if (units == ContourUnits.FEET) "${(GnssHealth.MAX_MOVE_M / 0.3048 / 5).toInt() * 5} ft" else "${Math.round(GnssHealth.MAX_MOVE_M)} m"

    const val NO_PERMISSION = "Location permission is off: allow it in the phone's settings to see the satellites."
    const val NONE_YET = "No satellites heard yet. Indoors the phone may hear none."
    const val NO_FIX = "No fix yet"
    const val DASH = "–"

    // The summary's labels.
    const val L_SATELLITES = "Satellites"
    const val L_SIGNAL = "Four best"
    const val L_SYSTEMS = "Systems"
    const val L_BANDS = "Bands"
    const val L_ACCURACY = "Accuracy"
    const val L_FIX_AGE = "Fix age"
    const val L_HEIGHT = "Height"
    const val L_SPEED = "Speed"
    const val L_COURSE = "Course"
    const val L_FIRST_FIX = "First fix"
    const val L_TIME = "Time"

    /** "4.2 km/h" or "2.6 mph" (ADR 0022). */
    fun speed(mps: Double, units: ContourUnits): String =
        if (units == ContourUnits.FEET) String.format(java.util.Locale.US, "%.1f mph", mps * 2.236936) else String.format(java.util.Locale.US, "%.1f km/h", mps * 3.6)

    fun usedOf(used: Int, inView: Int) = "$used used of $inView in view"
    const val BAND_UNKNOWN = "band not reported"

    /** "1,650 m GPS · 1,648 m map". */
    fun heights(gpsM: Double?, mapM: Double?, units: ContourUnits): String = listOfNotNull(
        gpsM?.let { "${UnitFormat.elevation(it, units)} GPS" }, mapM?.let { "${UnitFormat.elevation(it, units)} map" },
    ).joinToString(" · ").ifEmpty { DASH }

    // The sky plot.
    const val PLOT_LABEL = "Sky plot of the satellites"
    const val NORTH_UP_NO_COMPASS = "No compass reading: north is up."
    const val LEGEND_FILL = "Fill: signal strength, dB-Hz"
    const val LEGEND_USED = "Thick outline: used in the fix"
    const val LEGEND_SECOND_BAND = "Ring: heard on a second band"
    const val LEGEND_TERRAIN = "Shaded: the land's skyline"
    const val LEGEND_SUN = "Sun"

    // The terrain's horizon.
    const val NO_POSITION = "No position yet, so no terrain horizon."
    const val HORIZON_WORKING = "Working out the terrain horizon..."
    const val NO_TERRAIN = "No elevation data here (no offline map, no online source), so no terrain horizon: the flat horizon is used."
    const val NO_TERRAIN_HERE = "No elevation data at this point, so no terrain horizon: the flat horizon is used."
    const val NO_TERRAIN_ERROR = "The terrain horizon could not be worked out"

    /** "Terrain: highest 18° toward NNE, 8.2 km away; from the offline map." */
    fun horizonLine(highestDeg: Double, bearingDeg: Double, distanceM: Double, source: String?, units: ContourUnits): String =
        "Terrain: highest ${Math.round(highestDeg)}° toward ${compassPoint(bearingDeg)}, ${UnitFormat.distance(distanceM, units)} away" +
            (source?.let { "; from the $it" } ?: "") + "."

    private val POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    fun compassPoint(deg: Double): String = POINTS[(((deg % 360 + 360) % 360) / 22.5 + 0.5).toInt() % 16]

    // The list.
    const val H_SATELLITE = "Satellite"
    const val H_SIGNAL = "Signal, dB-Hz"
    const val H_ELEVATION = "Elev."
    const val H_AZIMUTH = "Azim."
    const val H_BAND = "Band"
    const val H_USED = "Used"
    const val USED = "Used"
    const val EPHEMERIS = "ephemeris"

    /** A satellite's place at large text, where the list has no columns: "43° up, 261°". */
    fun place(elevationDeg: Double, azimuthDeg: Double) = "${Math.round(elevationDeg)}° up, ${Math.round(azimuthDeg)}°"
    const val BEHIND = "behind terrain"

    // The menu.
    const val RESET = "Reset assistance data"
    const val RESET_TITLE = "Reset assistance data?"
    const val RESET_BODY = "The phone forgets the satellite orbits and time it downloaded, and fetches them again. The next fix takes longer, up to a few minutes. Some phones ignore this." // T385
    const val RESET_CONFIRM = "Reset"
    const val CANCEL = "Cancel"
    const val RESET_DONE = "Assistance data reset: the next fix may take a few minutes."
    const val RESET_REFUSED = "The phone refused the reset."

    // The verdict.
    fun headline(kind: GnssHealth.Kind): String = when (kind) {
        GnssHealth.Kind.HEALTHY -> "Healthy"
        GnssHealth.Kind.WEAK -> "Weak signal"
        GnssHealth.Kind.FAULT -> "May be a hardware fault"
    }

    fun advice(v: GnssHealth.Verdict): String = when (v.kind) {
        GnssHealth.Kind.HEALTHY -> "Strong signals from the open sky: the GPS looks healthy."
        GnssHealth.Kind.FAULT -> "Many satellites are in view above the terrain, but every signal is weak. If open sky and no case give the same, " +
            "the phone's GPS antenna or chip may be at fault."
        GnssHealth.Kind.WEAK -> when {
            GnssHealth.Note.NONE_HEARD in v.notes -> "No satellites were heard. Go outdoors, away from buildings, and try again."
            GnssHealth.Note.BEHIND_TERRAIN in v.notes && v.behindHorizon > v.aboveHorizon ->
                "Most satellites stood behind the terrain, so weak signals are expected here. Move to open sky, away from buildings, out of the case."
            else -> "Move to open sky, away from buildings, out of the case, with no hand over the top of the phone."
        }
    }

    /** The figures behind the verdict, one line. */
    fun figures(v: GnssHealth.Verdict): String = listOfNotNull(
        v.t4?.let { "four best ${Math.round(it)} dB-Hz" + (v.t4Constellation?.let { c -> " (${c.label})" } ?: "") },
        "${v.usedMedian} used of ${v.inViewMedian} in view",
        v.behindHorizon.takeIf { it > 0 }?.let { "$it behind the terrain" },
        v.strongestAbove?.let { "strongest ${Math.round(it)} dB-Hz" },
        v.ttffMs?.let { "first fix ${seconds(it)}" },
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }

    fun seconds(ms: Long): String = if (ms < 10_000) String.format(java.util.Locale.US, "%.1f s", ms / 1000.0) else "${Math.round(ms / 1000.0)} s"

    // Settings.
    const val ROW_LINE = "the sky around you: the land's skyline, the peaks on it, the sun's path and the satellites the phone hears"
    const val SKY_PLOT_LINE = "the Horizon view turns with the phone, or keeps north at the top"
    const val FOLLOW_PHONE = "Follow the phone"
    const val NORTH_UP = "North up"
}
