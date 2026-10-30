package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import kotlin.math.pow

/**
 * Pressure and height (T160): the International Standard Atmosphere's barometric formula below 11 km, as altimeters
 * use it. A height from a pressure is only as good as the reference pressure; the readout's elevation filter
 * ([ElevationFilter]) carries the difference between [pressureAltitudeM] and the true height as an offset state.
 */
object Altimeter {
    /** Standard sea-level pressure (hPa). */
    const val STANDARD_HPA = 1013.25

    /** One inch of mercury in hPa (the US altimeter group reads in hundredths of an inch). */
    const val HPA_PER_INHG = 33.8639

    private const val SCALE_M = 44_330.77
    private const val EXPONENT = 0.190263

    /** The height (m) at which the standard atmosphere has [hPa], given [seaLevelHPa] at sea level. */
    fun heightM(hPa: Double, seaLevelHPa: Double = STANDARD_HPA): Double = SCALE_M * (1 - (hPa / seaLevelHPa).pow(EXPONENT))

    /** The pressure altitude (m): [heightM] against the standard 1013.25 hPa. */
    fun pressureAltitudeM(hPa: Double): Double = heightM(hPa, STANDARD_HPA)

    /** Metres per hPa at [hPa] under [seaLevelHPa] (about 8.3 at sea level, 10 at 2000 m). */
    fun metresPerHPa(hPa: Double, seaLevelHPa: Double = STANDARD_HPA): Double = heightM(hPa - 0.5, seaLevelHPa) - heightM(hPa + 0.5, seaLevelHPa)
}

/**
 * A weather station's altimeter setting (T160): the sea-level pressure that makes an altimeter at the station read
 * the station's elevation, reported in a station's routine weather report. [lat] and [lon] are the station's (the
 * report itself carries no position: T173's fetch adds it from the station list); [timeMs] is when it was observed.
 * The readout uses it as a prior on the barometer offset ([ReadoutEstimator.onStationPressure]) whose uncertainty
 * grows with distance and age ([sigmaHPa]).
 */
data class StationPressure(val station: String, val hPa: Double, val lat: Double, val lon: Double, val timeMs: Long) {
    /**
     * The 1-sigma error (hPa) of this setting at [distanceM] from the station and [nowMs]: 0.5 hPa for the
     * station and the phone's sensor, 1 hPa per 100 km in ordinary weather, and 0.1 hPa per hour of age, so that
     * at about 12 h it is as weak as one good GNSS height (the readout idea's research, 2026-10-25).
     */
    fun sigmaHPa(distanceM: Double, nowMs: Long): Double {
        val km = distanceM / 1000
        val ageH = ((nowMs - timeMs).coerceAtLeast(0)) / 3_600_000.0
        return kotlin.math.sqrt(BASE_HPA * BASE_HPA + (km / 100) * (km / 100) + (DRIFT_HPA_PER_H * ageH) * (DRIFT_HPA_PER_H * ageH))
    }

    companion object {
        const val BASE_HPA = 0.5
        const val DRIFT_HPA_PER_H = 0.1
    }
}

/** The altimeter group of one report ([MetarAltimeter.parse]). [day], [hour], [minute] are the report's UTC time. */
data class ParsedAltimeter(val station: String, val day: Int, val hour: Int, val minute: Int, val hPa: Double, val group: String) {
    /**
     * The observation time in epoch ms, taking the month and year from [referenceMs] (the time the report was
     * fetched): a day of month later than the reference's is in the previous month. Null when the day does not
     * exist in that month.
     */
    fun timeMs(referenceMs: Long): Long? {
        val refDays = referenceMs.floorDiv(86_400_000L)
        var (y, m, d) = GpxFormat.civilFromDays(refDays)
        if (day > d) {
            m -= 1
            if (m == 0) { m = 12; y -= 1 }
        }
        val monthDays = (if (m == 12) GpxFormat.daysFromCivil(y + 1, 1, 1) else GpxFormat.daysFromCivil(y, m + 1, 1)) - GpxFormat.daysFromCivil(y, m, 1)
        if (day > monthDays) return null
        return (GpxFormat.daysFromCivil(y, m, day) * 86_400L + hour * 3600L + minute * 60L) * 1000L
    }
}

/** The registry id (`sources.json`) of the weather station pressure source: what a readout that used it credits. */
const val STATION_SOURCE_ID = "awc-metar"

/**
 * A pure parser of the altimeter group of a METAR or SPECI report (T160; the fetch is T173's): `A2992` (inches of
 * mercury, hundredths) or `Q1013` (hPa). Only the body is read: the remarks (`RMK ...`, where the sea-level
 * pressure `SLP...` sits) are ignored, because the remark's sea-level pressure is reduced with a temperature model
 * and drifts at high stations, while the altimeter setting is what an altimeter at the station is set to. User-facing
 * text calls this "weather station pressure", never by the report's name.
 *
 * The source is registry entry [STATION_SOURCE_ID] (`sources.json`: public domain, US government work, with its
 * credit line). T173's fetch reads the data API's JSON (`rawOb`, `lat`, `lon`, `obsTime`) and parses `rawOb` here:
 * the JSON's own `altim` is rounded to whole hPa.
 */
object MetarAltimeter {
    private val STATION = Regex("^[A-Z][A-Z0-9]{3}$")
    private val TIME = Regex("^(\\d{2})(\\d{2})(\\d{2})Z$")
    private val GROUP = Regex("^([AQ])(\\d{4})$")

    /** The altimeter of [report], or null when it has no station, time or valid altimeter group (`A////`, `Q////`). */
    fun parse(report: String): ParsedAltimeter? {
        val tokens = report.trim().removeSuffix("=").split(Regex("\\s+")).map { it.removeSuffix("=") }
        var i = 0
        if (tokens.getOrNull(i) == "METAR" || tokens.getOrNull(i) == "SPECI") i++
        if (tokens.getOrNull(i) == "COR") i++
        val station = tokens.getOrNull(i)?.takeIf { STATION.matches(it) } ?: return null
        i++
        val t = tokens.getOrNull(i)?.let { TIME.matchEntire(it) } ?: return null
        val (day, hour, minute) = t.destructured.toList().map { it.toInt() }
        if (day !in 1..31 || hour > 23 || minute > 59) return null
        for (k in i + 1 until tokens.size) {
            val tok = tokens[k]
            if (tok == "RMK") break
            val g = GROUP.matchEntire(tok) ?: continue
            val n = g.groupValues[2].toInt()
            val hPa = if (g.groupValues[1] == "A") n / 100.0 * Altimeter.HPA_PER_INHG else n.toDouble()
            // Plausible sea-level settings only (the record low and high are about 870 and 1084 hPa).
            if (hPa !in 850.0..1100.0) return null
            return ParsedAltimeter(station, day, hour, minute, hPa, tok)
        }
        return null
    }
}
