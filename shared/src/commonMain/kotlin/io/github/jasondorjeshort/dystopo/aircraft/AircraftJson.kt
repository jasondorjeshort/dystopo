package io.github.jasondorjeshort.dystopo.aircraft

import kotlin.math.round

/** T311: one drawn aircraft as `GET /aircraft` and `devtool aircraft` print it (positions to five decimals). */
object AircraftJson {
    private fun r(v: Double, d: Int): Double {
        var s = 1.0
        repeat(d) { s *= 10 }
        return round(v * s) / s
    }

    fun now(a: AircraftNow): Map<String, Any?> = linkedMapOf(
        "hex" to a.hex, "callSign" to a.report.callSign, "registration" to a.report.registration, "type" to a.report.type,
        "lat" to r(a.lat, 5), "lon" to r(a.lon, 5), "reportedLat" to r(a.report.lat, 5), "reportedLon" to r(a.report.lon, 5),
        "movedS" to r(a.movedS, 1), "altitudeM" to a.altitudeM?.let { r(it, 0) }, "altitudeKind" to a.altitudeKind.json,
        "altGeomFt" to a.report.altGeomFt, "altBaroFt" to a.report.altBaroFt, "onGround" to a.report.onGround,
        "trackDeg" to a.report.trackDeg, "groundSpeedKt" to a.report.groundSpeedKt, "verticalRateFpm" to a.report.verticalRateFpm,
        "ageS" to r(a.ageS, 1), "stale" to a.stale,
        "distanceKm" to a.distanceM?.let { r(it / 1000, 2) }, "bearingDeg" to a.bearingDeg?.let { r(it, 1) },
        "elevationDeg" to a.elevationDeg?.let { r(it, 2) },
        "route" to a.route?.json(), "label" to a.label, "trailPoints" to a.trail.size,
        "groundM" to a.groundM?.let { r(it, 0) }, "aboveGroundM" to a.aboveGround?.let { r(it.meters, 0) }, // T316
        "aboveGroundApproximate" to a.aboveGround?.approximate,
        "kind" to a.report.kind.json, "category" to a.report.category, "squawk" to a.report.squawk, "emergency" to a.report.emergency?.json,
    )
}
