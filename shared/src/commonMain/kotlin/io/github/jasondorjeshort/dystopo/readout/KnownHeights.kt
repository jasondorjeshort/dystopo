package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.dem.Peak
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import kotlin.math.abs

/**
 * T197: a named feature with a tagged height near the fix, one the user can say they stand at ("I am at <name>,
 * <height>", [KnownHeights.menuLabel]). [kind] its `mountain_peak` class, [eleM] the tag, [sigmaM] the height's 1-sigma
 * ([KnownHeights.sigmaFor]), [distanceM] from the fix, [demM] the DEM there (null with no DEM).
 */
data class KnownHeight(
    val name: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val eleM: Double,
    val sigmaM: Double,
    val distanceM: Double,
    val demM: Double?,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "name" to name, "kind" to kind, "lat" to r6(lat), "lon" to r6(lon), "eleM" to eleM, "sigmaM" to sigmaM,
        "distanceM" to r1(distanceM), "demM" to demM?.let { r1(it) },
    )
}

/**
 * T197: the known heights a pin can come from, and the words around it (ADR 0031; the survey of 2026-10-31,
 * `out/wave24-research/T197-altimeter-calibration.md`: no watch or app picks the height from a map label, so the
 * menu entry, the mark and its words are this build's own). Pure.
 *
 * - **Candidates** ([near], [read]): the pack's `mountain_peak` features of [Viewshed.KNOWN_HEIGHT_CLASSES] (peaks,
 *   saddles and passes), named and with an `ele`, within [NEAR_FIX_M] of the fix, and a tag no more than
 *   [MAX_TAG_OFF_DEM_M] off the DEM there.
 * - **Sigmas** ([sigmaFor]): [SUMMIT_SIGMA_M] for a summit, [SIGN_SIGMA_M] for a saddle, a pass or a typed height.
 *   No source states one (manuals treat a typed height as exact); map labels are often a few metres off and the top
 *   can lie metres from the label, so both are more cautious than every source.
 */
object KnownHeights {
    /**
     * A feature is offered within this of the fix (m). It covers the fix's own error (5 to 15 m in the open, up to
     * 30 m under trees), a label placed tens of metres off the true top by hand from imagery, and a broad summit or
     * saddle, and stays well under the spacing of named features (rarely closer than a few hundred metres), so the
     * nearest one is the one stood on. The user confirms it by the tap.
     */
    const val NEAR_FIX_M = 100.0

    /**
     * A tag further than this off the DEM at its point (m) is a wrong tag, not offered: the same rule as the
     * viewshed's peaks ([Viewshed.peaks]). It catches a height in feet tagged as metres above about 70 m and a tag on
     * the wrong feature, and passes a sharp summit that a 10 to 30 m DEM cell smooths by tens of metres.
     */
    const val MAX_TAG_OFF_DEM_M = 150.0

    /** A tagged summit's 1-sigma (m). */
    const val SUMMIT_SIGMA_M = 3.0

    /** A saddle's, a pass's or a typed height's 1-sigma (m): a broad feature, or a number read off a sign or a map. */
    const val SIGN_SIGMA_M = 5.0

    /** A height outside this (m) is refused (a typing slip). */
    const val MIN_ELE_M = -500.0
    const val MAX_ELE_M = 9000.0

    /** The zooms read for candidates: z13 carries every named feature (the layer is thinned below it), z12 for packs that stop there. */
    val READ_ZOOMS = listOf(13, 12)

    fun sigmaFor(kind: String?): Double = if (kind != null && kind in Viewshed.PEAK_CLASSES) SUMMIT_SIGMA_M else SIGN_SIGMA_M

    /** The candidates near a fix, nearest first, and the features turned away with why (`noEle`, `offDem`, `class`). */
    class Found(val candidates: List<KnownHeight>, val rejected: List<Pair<Peak, String>>) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "candidates" to candidates.map { it.json() },
            "rejected" to rejected.map { (p, why) -> linkedMapOf("name" to p.name, "kind" to p.kind, "eleM" to p.eleM, "why" to why) },
        )
    }

    /** [peaks] (read around the fix) narrowed to the candidates within [NEAR_FIX_M] of ([lat], [lon]), checked against [dem]. */
    fun near(peaks: List<Peak>, lat: Double, lon: Double, dem: ElevationSampler?): Found {
        val out = ArrayList<KnownHeight>()
        val rejected = ArrayList<Pair<Peak, String>>()
        for (p in peaks) {
            val d = TrackStats.distanceM(lat, lon, p.lat, p.lon)
            if (d > NEAR_FIX_M) continue
            if (p.kind !in Viewshed.KNOWN_HEIGHT_CLASSES) { rejected += p to "class"; continue }
            val ele = p.eleM?.takeIf { it.isFinite() && it in MIN_ELE_M..MAX_ELE_M }
            if (ele == null) { rejected += p to "noEle"; continue }
            val demM = dem?.elevation(p.lat, p.lon)?.takeIf { it.isFinite() }
            if (demM != null && abs(ele - demM) > MAX_TAG_OFF_DEM_M) { rejected += p to "offDem"; continue }
            out += KnownHeight(p.name, p.kind, p.lat, p.lon, ele, sigmaFor(p.kind), d, demM)
        }
        return Found(out.sortedBy { it.distanceM }, rejected)
    }

    /** [near] over the `mountain_peak` features [tiles] (a vector pack's `tile(z, x, y)`) holds at [READ_ZOOMS]. */
    fun read(tiles: (Int, Int, Int) -> ByteArray?, lat: Double, lon: Double, dem: ElevationSampler?): Found {
        val peaks = ArrayList<Peak>()
        for (z in READ_ZOOMS) {
            for (p in Viewshed.readPeaks(tiles, lat, lon, NEAR_FIX_M, zoom = z, classes = Viewshed.KNOWN_HEIGHT_CLASSES)) {
                if (peaks.none { it.name == p.name && TrackStats.distanceM(it.lat, it.lon, p.lat, p.lon) < 200 }) peaks += p
            }
        }
        return near(peaks, lat, lon, dem)
    }

    /**
     * The candidate a long-press at ([lat], [lon]) is on: the nearest to the press within [pressRadiusM] (the larger
     * of [NEAR_FIX_M] and a fingertip at the map's zoom, the caller's), or null.
     */
    fun pick(candidates: List<KnownHeight>, lat: Double, lon: Double, pressRadiusM: Double): KnownHeight? =
        candidates.map { it to TrackStats.distanceM(lat, lon, it.lat, it.lon) }.filter { it.second <= pressRadiusM }.minByOrNull { it.second }?.first

    /**
     * How long (s) the mark holds on the offset's random walk alone, from [offsetSigmaM] right after the pin to
     * [ElevationFilter.PIN_HOLD_FACTOR] times [pinSigmaM] (no other input: a station's prior lengthens it).
     */
    fun markHoldS(offsetSigmaM: Double, pinSigmaM: Double): Double {
        val limit = ElevationFilter.PIN_HOLD_FACTOR * pinSigmaM
        return ((limit * limit - offsetSigmaM * offsetSigmaM) / ElevationFilter.OFFSET_WALK_M2_PER_S).coerceAtLeast(0.0)
    }

    /**
     * How long (s) a pin stays surer than a weather station's prior: the offset's sigma from [offsetSigmaM] grown by
     * the walk, against the prior's sigma ([StationPressure.sigmaHPa]: a report fresh at the pin, [distanceM] away,
     * ageing 0.1 hPa an hour) in metres at [hPa] under [seaLevelHPa]. 0 when the prior is surer at once, null when the
     * pin stays surer past [maxS]. In minute steps.
     */
    fun holdAgainstStationS(offsetSigmaM: Double, distanceM: Double, hPa: Double, seaLevelHPa: Double = Altimeter.STANDARD_HPA, maxS: Long = 48 * 3600): Long? {
        val station = StationPressure("", seaLevelHPa, 0.0, 0.0, 0L)
        val mPerHPa = Altimeter.metresPerHPa(hPa, seaLevelHPa)
        var s = 0L
        while (s <= maxS) {
            val pin2 = offsetSigmaM * offsetSigmaM + ElevationFilter.OFFSET_WALK_M2_PER_S * s
            val st = station.sigmaHPa(distanceM, s * 1000) * mPerHPa
            if (pin2 >= st * st) return s
            s += 60
        }
        return null
    }

    /** The station prior's 1-sigma (m) for a fresh report [distanceM] away at [hPa]. */
    fun stationSigmaM(distanceM: Double, hPa: Double, seaLevelHPa: Double = Altimeter.STANDARD_HPA): Double =
        StationPressure("", seaLevelHPa, 0.0, 0.0, 0L).sigmaHPa(distanceM, 0) * Altimeter.metresPerHPa(hPa, seaLevelHPa)

    // The words (ADR 0031; the survey's recommendation).

    /** The long-press card's entry: "I am at Alta Vista, 5940 ft" (the height in the user's units, so a wrong tag shows). */
    fun menuLabel(k: KnownHeight, units: ContourUnits): String = "I am at ${k.name}, ${UnitFormat.elevation(k.eleM, units)}"

    /** The one line after a pin: "Height set from Alta Vista", or for a typed height "Height set to 5420 ft". */
    fun confirmation(name: String?, eleM: Double, units: ContourUnits): String =
        name?.let { "Height set from $it" } ?: "Height set to ${UnitFormat.elevation(eleM, units)}"

    /** The bar's mark, as its description says it: "Set at Alta Vista", "Set by you" for a typed height. */
    fun markLabel(name: String?): String = name?.let { "Set at $it" } ?: "Set by you"

    /** A tap on the mark: "Set at Alta Vista, 10:42, 5940 ft" ([time] the phone's own clock text). */
    fun markDetail(name: String?, time: String, eleM: Double, units: ContourUnits): String =
        "${markLabel(name)}, $time, ${UnitFormat.elevation(eleM, units)}"

    /** The settings action's name and its field's hint (the survey's wording). */
    const val SET_NOW = "Set my height now"
    const val FIELD_HINT = "from a sign, map or marker"

    /**
     * A typed height in metres, or null: digits with an optional thousands comma, a decimal point and a unit (`ft`,
     * `feet`, `'`, `m`, `metres`, `meters`); with no unit it is in [units]. Out of [MIN_ELE_M]..[MAX_ELE_M] is null.
     */
    fun parseTyped(raw: String?, units: ContourUnits): Double? {
        val t = raw?.trim()?.lowercase()?.replace(",", "")?.replace(" ", "") ?: return null
        if (t.isEmpty()) return null
        val (num, feet) = when {
            t.endsWith("feet") -> t.removeSuffix("feet") to true
            t.endsWith("ft") -> t.removeSuffix("ft") to true
            t.endsWith("'") -> t.removeSuffix("'") to true
            t.endsWith("metres") -> t.removeSuffix("metres") to false
            t.endsWith("meters") -> t.removeSuffix("meters") to false
            t.endsWith("m") -> t.removeSuffix("m") to false
            else -> t to (units == ContourUnits.FEET)
        }
        val v = num.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val m = if (feet) v / UnitFormat.FEET_PER_METER else v
        return m.takeIf { it in MIN_ELE_M..MAX_ELE_M }
    }
}

private fun r1(v: Double) = kotlin.math.round(v * 10) / 10
private fun r6(v: Double) = kotlin.math.round(v * 1e6) / 1e6
