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

/**
 * One recorded position. T158: what the receiver said about it, null when the source gave nothing (a GPX file
 * from elsewhere, a fix without it): [accuracyM] the horizontal accuracy radius (metres, 68 %), [speedMps] the
 * receiver's own (Doppler) ground speed, [courseDeg] its course over ground (degrees clockwise from true north),
 * [verticalAccuracyM] the height's accuracy; and [flags] ([INACCURATE], [NETWORK]). A point over the profile's
 * accuracy bound is kept and flagged, never dropped: smoothing and stats weight or skip it (T171), the raw point
 * stays.
 */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long? = null,
    val extensions: String? = null,
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
    val courseDeg: Double? = null,
    val verticalAccuracyM: Double? = null,
    val flags: Int = 0,
) {
    /** Worse than the recording profile's accuracy bound ([ActivityProfile.accuracyBoundM]) when it was recorded. */
    val inaccurate: Boolean get() = flags and INACCURATE != 0

    /** True when any T158 field is set (the sync codec writes such points in the newer append format). */
    val hasFixFields: Boolean get() = accuracyM != null || speedMps != null || courseDeg != null || verticalAccuracyM != null || flags != 0

    companion object {
        /** Bit of [flags]: the fix was worse than the profile's accuracy bound. */
        const val INACCURATE = 1

        /** Bit of [flags]: a network or passive fix taken while the GPS was silent (the profile's fallback, T159). */
        const val NETWORK = 2

        /** The bits this build knows; a point with others is refused by the sync codec (a newer format). */
        const val KNOWN_FLAGS = INACCURATE or NETWORK
    }
}

/**
 * Why a track has a hole (T158), stored with the track as a [TrackGap]. [code] is stored and synced: never change
 * or reuse one.
 */
enum class GapReason(val code: String) {
    /** No fix arrived for longer than the profile's gap rule. */
    NO_FIX("no-fix"),
    /** Fixes arrived but were rejected (teleports beyond the profile's speed bound). */
    REJECTED("rejected"),
    /** The user paused recording. */
    PAUSED("paused"),
    /** Auto-pause: the fix speed stayed under the still threshold. */
    AUTO_PAUSED("auto-paused"),
    /** The process died while recording (found at the next start, [io.github.jasondorjeshort.dystopo.store.TrackRecorder.recoverInterrupted]). */
    STOPPED_BY_SYSTEM("stopped-by-system"),
    /** The app restarted the recording itself (the service restarted, T159). */
    RESTARTED("restarted");

    companion object {
        fun of(code: String?): GapReason? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A hole in a recorded track (T158): from [start], the time of the last point kept before it, to [end], the time
 * of the first point after it (or when recording stopped), with the [reason]. The point after a gap starts a new
 * segment. Gaps are data on the track, synced and exported, so a dropout is explainable afterwards.
 */
data class TrackGap(val start: Long, val end: Long, val reason: GapReason) {
    val durationMs: Long get() = end - start
}

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
    /** T158: how it was travelled (null: not said, or a newer build's activity). */
    val activity: Activity? = null,
    /** T158: the recording settings it was recorded with (null for an import without them). */
    val profile: ActivityProfile? = null,
    /** T158: its holes, oldest first. */
    val gaps: List<TrackGap> = emptyList(),
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
