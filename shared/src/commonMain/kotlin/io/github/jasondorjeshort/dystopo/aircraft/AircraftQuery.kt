package io.github.jasondorjeshort.dystopo.aircraft

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * T311: what the phone asks the live feed: a centre rounded to [STEP_DEG] (0.1°, the owner's choice of 2026-11-20: the
 * centre of the map being viewed, never the phone's position, and never finer than about 11 km) and a radius in whole
 * nautical miles that reaches every corner of the view from the rounded centre, at most [AircraftRules.MAX_RADIUS_NM].
 */
data class AircraftQuery(val lat: Double, val lon: Double, val radiusNm: Int) {
    /** adsb.lol's point-and-radius path (`/v2/lat/{lat}/lon/{lon}/dist/{nm}`; the live poll confirmed it, T311's report). */
    val path: String get() = "/v2/lat/${fmt(lat)}/lon/${fmt(lon)}/dist/$radiusNm"

    fun json(): Map<String, Any?> = linkedMapOf("lat" to lat, "lon" to lon, "radiusNm" to radiusNm, "path" to path)

    companion object {
        const val STEP_DEG = 0.1
        const val MIN_RADIUS_NM = 10
        const val NM_M = 1852.0

        /** A coordinate rounded to [STEP_DEG]. */
        fun round(v: Double): Double = (v * 10).roundToLong() / 10.0

        /** The query for a view: [centerLat]/[centerLon] the view's centre, the box west, south, east, north (degrees). */
        fun forView(centerLat: Double, centerLon: Double, west: Double, south: Double, east: Double, north: Double): AircraftQuery {
            val lat = round(centerLat).coerceIn(-89.9, 89.9)
            val lon = round(((centerLon + 540.0) % 360.0) - 180.0)
            val corners = listOf(north to west, north to east, south to west, south to east)
            val far = corners.maxOf { (la, lo) -> AircraftGeo.distanceM(lat, lon, la.coerceIn(-90.0, 90.0), lo) }
            val nm = ceil(far / NM_M).toInt().coerceIn(MIN_RADIUS_NM, AircraftRules.MAX_RADIUS_NM)
            return AircraftQuery(lat, lon, nm)
        }

        /** One decimal, '.' separator, no "-0.0". */
        fun fmt(v: Double): String {
            val tenths = (v * 10).roundToLong()
            val neg = tenths < 0
            val a = kotlin.math.abs(tenths)
            return (if (neg) "-" else "") + "${a / 10}.${a % 10}"
        }
    }
}
