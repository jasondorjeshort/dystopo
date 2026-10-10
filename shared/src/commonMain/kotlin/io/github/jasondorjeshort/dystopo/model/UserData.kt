package io.github.jasondorjeshort.dystopo.model

/*
 * User data: waypoints, recorded tracks and planned routes. These are the entities the app stores,
 * syncs and exchanges as GPX. Each top-level entity has a UUID [id] (see [UuidGenerator]).
 *
 * Coordinates are WGS84 degrees. Elevations are meters as recorded (GPX elevations are nominally
 * above mean sea level; we don't convert them). Times are epoch milliseconds, UTC.
 *
 * `extensions` fields hold the inner XML of a GPX `<extensions>` element verbatim (trimmed), so
 * data we don't model (Garmin heart rate and cadence, app-specific fields) survives an
 * import/export round trip. [io.github.jasondorjeshort.dystopo.gpx.GpxExtensions] reads the common
 * Garmin fields out of it.
 */

/** A named point of interest. */
data class Waypoint(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long? = null,
    val description: String? = null,
    val comment: String? = null,
    /** GPX `sym`: the symbol name a GPS shows (e.g. "Flag, Blue", "Summit"). */
    val symbol: String? = null,
    val type: String? = null,
    val extensions: String? = null,
)

/** One recorded position. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long? = null,
    val extensions: String? = null,
)

/** An unbroken run of points; a new segment starts after the receiver lost a fix or recording paused. */
data class TrackSegment(
    val points: List<TrackPoint>,
    val extensions: String? = null,
)

/** A recorded path. Distances and stats never bridge the gap between two segments. */
data class Track(
    val id: String,
    val name: String,
    val segments: List<TrackSegment>,
    val description: String? = null,
    val comment: String? = null,
    val source: String? = null,
    val type: String? = null,
    val extensions: String? = null,
) {
    val points: List<TrackPoint> get() = segments.flatMap { it.points }
}

/** A point of a planned [Route]; not an entity of its own. */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long? = null,
    val name: String? = null,
    val description: String? = null,
    val symbol: String? = null,
    val extensions: String? = null,
)

/** A planned path: an ordered list of points to go through. */
data class Route(
    val id: String,
    val name: String,
    val points: List<RoutePoint>,
    val description: String? = null,
    val comment: String? = null,
    val type: String? = null,
    val extensions: String? = null,
)
