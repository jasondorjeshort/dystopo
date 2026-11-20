package io.github.jasondorjeshort.dystopo.aircraft

/**
 * T311: one aircraft as a feed reported it (the readsb "v2" JSON that adsb.lol answers; [ReadsbParser]). Heights are the
 * feed's own, in feet: [altGeomFt] above the WGS84 ellipsoid (readsb's `alt_geom`), [altBaroFt] pressure altitude
 * (`alt_baro`). [seenPosS] is how old the position was when the feed answered.
 *
 * Feed data lives in memory only (ODbL, the finding `docs/findings/2026-11-20-aircraft-layer-sources-and-display.md`):
 * nothing in this package writes it to disk, a cache or an export.
 */
data class AircraftReport(
    val hex: String,
    val callSign: String?,
    val registration: String?,
    val type: String?,
    val description: String?,
    val lat: Double,
    val lon: Double,
    val altGeomFt: Double?,
    val altBaroFt: Double?,
    val onGround: Boolean,
    val trackDeg: Double?,
    val groundSpeedKt: Double?,
    val verticalRateFpm: Double?,
    val seenPosS: Double,
)

/** One answer of a feed: what it reported, and when the phone received it ([atMs], epoch ms on the phone's clock). */
data class AircraftPoll(val atMs: Long, val aircraft: List<AircraftReport>, val bytes: Int = 0)

/** Which height an aircraft's altitude is: geometric (converted to EGM2008 by the phone's undulation), pressure, or none. */
enum class AltitudeKind(val json: String) {
    GEOMETRIC("geometric"),
    BAROMETRIC("barometric"),
    GROUND("ground"),
    NONE("none"),
}

/** The layer's fixed rules (the finding's common practice; T311's task text). */
object AircraftRules {
    /** A position older than this is drawn hollow, with its age (seconds). */
    const val STALE_S = 30.0

    /** A position older than this is dropped (seconds; tar1090 drops at 58 s). */
    const val DROP_S = 60.0

    /** Between polls an aircraft is moved along its track by its speed for at most this long (seconds). */
    const val MOVE_CAP_S = 30.0

    /** A feed with no answer for this many refresh intervals (and at least this long) reads "Flight data stopped". */
    const val STOPPED_INTERVALS = 3
    const val STOPPED_MIN_S = 60.0

    /** The trail drawn behind each aircraft (seconds; tar1090's temporary trails are 90 s). */
    const val TRAIL_S = 90.0

    /** At most this many positions kept per aircraft for the tapped one's track (a 20 s poll: 4 hours). */
    const val HISTORY_MAX = 720

    /** Labels from this map zoom (tar1090 shows labels from zoom 8) or while at most [LABEL_MAX_COUNT] are in view (RadarBox: 250). */
    const val LABEL_MIN_ZOOM = 8.0
    const val LABEL_MAX_COUNT = 250

    /** The refresh interval's choices and default (seconds; the finding's recommendation 7). */
    val REFRESH_CHOICES_S = listOf(10, 20, 30, 60)
    const val REFRESH_DEFAULT_S = 20

    /** adsb.lol's daily allowance without a key, per IP (requests a UTC day). */
    const val DAILY_REQUESTS = 500

    /** The query's largest radius, nautical miles (adsb.lol's limit). */
    const val MAX_RADIUS_NM = 250

    const val FEET_TO_M = 0.3048
    const val KNOT_TO_MPS = 0.514444
}
