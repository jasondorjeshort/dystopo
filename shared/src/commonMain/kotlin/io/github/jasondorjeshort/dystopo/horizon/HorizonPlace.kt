package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * T317: a point the Horizon view is drawn for in place of the phone's fix ("Sky from here" on the long-press card, or
 * `POST /horizon/ui?lat=&lon=`): [name] the named feature the press was on (a peak, a saddle, a pass), else null. Held in
 * memory only: the app reopens at the fix.
 */
data class HorizonPoint(val lat: Double, val lon: Double, val name: String? = null)

/** T321: what a view opened by the switch stands on ([HorizonPlace.switchFrom]); [key] as `GET /horizon` reports it. */
enum class SwitchFrom(val key: String) { LONG_PRESS("longPress"), SEARCH("search"), FIX("fix") }

/**
 * T326: the views of the switch, left to right ("Map | Horizon | Panorama"): [key] as the debug routes name them,
 * [label] the segment's text.
 */
enum class SkyView(val key: String, val label: String) {
    MAP("map", "Map"), HORIZON("horizon", "Horizon"), PANORAMA("panorama", "Panorama");

    companion object {
        fun of(key: String?): SkyView? = entries.firstOrNull { it.key == key }
    }
}

/** T326: what a tap on a segment does ([HorizonPlace.switchAction]). */
enum class SwitchAction { NONE, CLOSE, OPEN_AT_SELECTION, KEEP_PLACE }

/** T326: the sky views as the switch sees them: which [view] shows, the [point] it stands on (null: the fix) and the [picked] peak's key. */
data class SkyState(val view: SkyView, val point: HorizonPoint?, val picked: String?)

/**
 * T317: the place line under the horizon: [text] as shown, [moved] true when the view is not where the phone is (tinted
 * apart, with "Back to here"), [kind] `here`, `named`, `offset` or `unknown` (no fix to measure from), [distanceM] and
 * [bearingDeg] from the fix to the point (null at the fix or without one).
 */
data class PlaceLine(val text: String, val moved: Boolean, val kind: String, val distanceM: Double?, val bearingDeg: Double?)

/**
 * T317: where the Horizon view stands, said so it is never read as the phone's own sky (the owner, 2026-11-20). Pure.
 *
 * - **The point source** ([source], [layers]): the view's point is the chosen [HorizonPoint] while there is one, else the
 *   fix; at a chosen point the Satellites and Flights layers do not draw (both are about the phone's own sky: the GNSS it
 *   hears, the poll centred on its fix) and their buttons are disabled, the synced settings untouched.
 * - **The place line** ([line]): "Your location" when the point is the fix or within [HERE_M] of it; "At <name>, <distance>
 *   <compass> of you" on a named feature ("At <name>" with no fix); "<distance> <compass> of you" otherwise (16-point
 *   compass, the distance in the user's units, ADR 0022); without a fix, the point's coordinates.
 */
object HorizonPlace {
    /** A point this close to the fix (metres) is the fix: the line says "Your location" (the idea's 50 m). */
    const val HERE_M = 50.0

    const val YOUR_LOCATION = "Your location"
    const val BACK = "Back to here"

    /** The 16 compass points, from north clockwise. */
    val POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    fun compass16(deg: Double): String = POINTS[(((deg % 360 + 360) % 360) / 22.5 + 0.5).toInt() % 16]

    /** The point the view describes: [point] while chosen, else the fix ([fixLat], [fixLon]; null without one). */
    fun source(point: HorizonPoint?, fixLat: Double?, fixLon: Double?): Pair<Double, Double>? =
        point?.let { it.lat to it.lon } ?: if (fixLat != null && fixLon != null) fixLat to fixLon else null

    /** The layers that draw: at a chosen point ([moved]) Satellites and Flights off, the rest as set. */
    fun layers(effective: HorizonLayers, moved: Boolean): HorizonLayers = if (moved) effective.copy(satellites = false, flights = false) else effective

    /** The layers whose buttons are disabled at a chosen point ([moved]). */
    fun disabled(moved: Boolean): Set<HorizonLayer> = if (moved) setOf(HorizonLayer.SATELLITES, HorizonLayer.FLIGHTS) else emptySet()

    /** The line for [point] (null: the fix) seen from the fix ([fixLat], [fixLon]; null without one) in [units]. */
    fun line(point: HorizonPoint?, fixLat: Double?, fixLon: Double?, units: ContourUnits): PlaceLine {
        if (point == null) return PlaceLine(YOUR_LOCATION, false, "here", null, null)
        if (fixLat == null || fixLon == null) {
            val text = point.name?.let { "At $it" } ?: "${coord(point.lat)}, ${coord(point.lon)}"
            return PlaceLine(text, true, if (point.name != null) "named" else "unknown", null, null)
        }
        val d = TrackStats.distanceM(fixLat, fixLon, point.lat, point.lon)
        if (d <= HERE_M) return PlaceLine(YOUR_LOCATION, false, "here", d, null)
        val b = TrackStats.bearingDeg(fixLat, fixLon, point.lat, point.lon)
        val offset = "${UnitFormat.distance(d, units)} ${compass16(b)} of you"
        return if (point.name != null) PlaceLine("At ${point.name}, $offset", true, "named", d, b)
        else PlaceLine(offset, true, "offset", d, b)
    }

    // ---- T321: two more ways in ----

    /** T321: "View from here" on a picked peak: the view stands on the peak, named ("At <peak>, ..."). */
    fun pointOf(peak: io.github.jasondorjeshort.dystopo.dem.Peak): HorizonPoint = HorizonPoint(peak.lat, peak.lon, peak.name)

    /**
     * T321 (the owner, 2026-11-21: "If we select a different location on the map and switch to horizon view we want the
     * horizon from THAT location, not ours"): where a view the switch opens stands. The map's selection is an open
     * long-press card ([longPressOpen]; on a named peak it is that peak, as "Sky from here" finds it), else a search result's
     * marker ([searchMarker]); with neither, the fix. The card wins: it is the newer, deliberate choice, and the marker
     * stays on the map until the search is cleared. One rule for every view the switch opens (the Panorama view later).
     */
    fun switchFrom(longPressOpen: Boolean, searchMarker: HorizonPoint?): SwitchFrom = when {
        longPressOpen -> SwitchFrom.LONG_PRESS
        searchMarker != null -> SwitchFrom.SEARCH
        else -> SwitchFrom.FIX
    }

    // ---- T326: the switch for three views ----

    /**
     * T326: what a tap on the switch's segment [to] does with [from] showing (wave 45's choices (a) and (b)): nothing on
     * the segment already shown; "Map" closes either sky view (the place and a pick let go); from the map a sky view opens
     * at the map's selection ([switchFrom], `ViewSwitch.openAtSelection`); between the two sky views the view changes and
     * keeps the place it stands on and a picked peak (the long-press card is not read again).
     */
    fun switchAction(from: SkyView, to: SkyView): SwitchAction = when {
        from == to -> SwitchAction.NONE
        to == SkyView.MAP -> SwitchAction.CLOSE
        from == SkyView.MAP -> SwitchAction.OPEN_AT_SELECTION
        else -> SwitchAction.KEEP_PLACE
    }

    /**
     * T326: the sky views' state after a tap on [to], from [state]: [selection] is where the map's selection stands (the
     * point [switchFrom] picks, null for the fix), read only when a sky view opens from the map. Pure, as the app applies it.
     */
    fun switched(state: SkyState, to: SkyView, selection: HorizonPoint?): SkyState = when (switchAction(state.view, to)) {
        SwitchAction.NONE -> state
        SwitchAction.CLOSE -> SkyState(SkyView.MAP, null, null)
        SwitchAction.OPEN_AT_SELECTION -> SkyState(to, selection, null)
        SwitchAction.KEEP_PLACE -> state.copy(view = to)
    }

    /**
     * T326: the Panorama's bearing at an opening (wave 45's choice (c)): toward a picked peak when one is kept
     * ([pickedBearingDeg]); else, when the view stands at the fix ([atFix]), the compass [headingDeg] (its first reading,
     * taken once); else north.
     */
    fun openingBearing(pickedBearingDeg: Double?, atFix: Boolean, headingDeg: Double?): Double = when {
        pickedBearingDeg != null -> wrap(pickedBearingDeg)
        atFix && headingDeg != null -> wrap(headingDeg)
        else -> 0.0
    }

    /** T326: the bearing a picked peak's key holds ("<name>@<bearing in tenths>", the sky views' key), or null. */
    fun bearingOfKey(key: String?): Double? = key?.substringAfterLast('@', "")?.toLongOrNull()?.let { wrap(it / 10.0) }

    private fun wrap(deg: Double): Double = ((deg % 360) + 360) % 360

    /** A coordinate to 4 decimals ("46.8530", "-121.7600"). */
    private fun coord(v: Double): String {
        val n = (abs(v) * 10_000).roundToLong()
        return "${if (v < 0) "-" else ""}${n / 10_000}.${(n % 10_000).toString().padStart(4, '0')}"
    }
}
