package io.github.jasondorjeshort.dystopo.render

/**
 * The default contour unit for a place: feet in the United States (where USGS maps, trail signs and
 * guidebooks use feet), metres everywhere else. A user setting overrides it; this is only the default
 * a view starts with.
 *
 * The US outline is coarse on purpose: a polygon of about 50 vertices for the lower 48 that follows
 * the 49th parallel exactly and the Great Lakes, the St. Lawrence, Maine, the Rio Grande and the
 * Mexican border to within some tens of kilometres, with generous margins out to sea; a polygon for Alaska (the 141st
 * meridian and the panhandle), the Aleutians west of the antimeridian, and boxes for Hawaii, Puerto
 * Rico with the Virgin Islands, and Guam. Places close to a border other than the 49th parallel can
 * fall on the wrong side (Windsor counts as US, Brownsville does not); the user setting is the fix.
 */
object UnitRegions {
    /** Lower 48, lon/lat pairs, clockwise from the Strait of Juan de Fuca. */
    private val CONUS = doubleArrayOf(
        -125.5, 48.3, -123.25, 48.25, -123.0, 49.0, -95.15, 49.0, -95.15, 49.38, -94.6, 48.72,
        -89.6, 48.0, -84.4, 46.5, -82.45, 45.3, -82.45, 42.95, -83.1, 42.05, -79.05, 42.75,
        -79.05, 43.45, -76.3, 43.6, -74.75, 44.99, -71.5, 45.0, -70.85, 45.25, -70.25, 45.95,
        -70.0, 46.7, -69.2, 47.45, -67.8, 47.08, -67.8, 45.7, -66.95, 44.8, -66.2, 44.0,
        -69.0, 40.5, -74.0, 37.0, -75.2, 35.0, -79.6, 31.0, -79.85, 25.2, -80.6, 24.25,
        -82.5, 24.25, -97.1, 25.9, -99.5, 27.5, -100.45, 28.7, -100.9, 29.35, -103.0, 28.95,
        -104.4, 29.55, -106.5, 31.75, -108.2, 31.78, -108.2, 31.33, -111.07, 31.33,
        -114.8, 32.49, -117.12, 32.53, -118.5, 32.0, -121.5, 33.0, -125.5, 40.0,
    )

    /** Alaska east of the antimeridian. */
    private val ALASKA = doubleArrayOf(
        -180.0, 50.5, -129.9, 54.65, -130.0, 56.1, -133.4, 58.4, -137.5, 59.0, -139.1, 60.0,
        -141.0, 60.3, -141.0, 72.0, -180.0, 72.0,
    )

    /** west, south, east, north. */
    private val BOXES = listOf(
        doubleArrayOf(172.0, 50.5, 180.0, 53.5),    // western Aleutians
        doubleArrayOf(-161.0, 18.5, -154.5, 22.5),  // Hawaii
        doubleArrayOf(-67.5, 17.5, -64.4, 18.7),    // Puerto Rico, US Virgin Islands
        doubleArrayOf(144.5, 13.1, 145.1, 13.8),    // Guam
    )

    /** True when ([lat], [lon]) is inside the coarse US outline above. */
    fun isUnitedStates(lat: Double, lon: Double): Boolean {
        val x = lon - 360.0 * kotlin.math.floor((lon + 180.0) / 360.0) // wrap to [-180, 180)
        if (BOXES.any { b -> x >= b[0] && x <= b[2] && lat >= b[1] && lat <= b[3] }) return true
        return inside(CONUS, x, lat) || inside(ALASKA, x, lat)
    }

    /** Feet in the US, metres elsewhere. */
    fun contourUnitsAt(lat: Double, lon: Double): ContourUnits =
        if (isUnitedStates(lat, lon)) ContourUnits.FEET else ContourUnits.METERS

    /** Even-odd ray cast against a closed lon/lat ring. */
    private fun inside(ring: DoubleArray, x: Double, y: Double): Boolean {
        var c = false
        val n = ring.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[2 * i]; val yi = ring[2 * i + 1]
            val xj = ring[2 * j]; val yj = ring[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }
}
