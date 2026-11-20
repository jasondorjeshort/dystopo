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
    /** The transponder code (`squawk`, four octal digits), when sent. */
    val squawk: String? = null,
    /** readsb's `emergency` (`none`, `general`, `lifeguard`, `minfuel`, `nordo`, `unlawful`, `downed`, `reserved`), when sent. */
    val emergencyField: String? = null,
    /** The ADS-B emitter category (`category`: A0 to A7, B0 to B7, C0 to C7), when sent. */
    val category: String? = null,
) {
    /** What kind of aircraft the emitter category says ([AircraftKind.of]). */
    val kind: AircraftKind get() = AircraftKind.of(category)

    /** The emergency the squawk or the emergency field declares, or null ([Emergency.of]). */
    val emergency: Emergency? get() = Emergency.of(squawk, emergencyField)
}

/**
 * T311: what kind of aircraft, from the ADS-B emitter category (DO-260B: A1 light, A2 small, A3 large, A4 high-vortex
 * large, A5 heavy, A6 high performance, A7 rotorcraft; B1 glider, B2 lighter than air, B4 ultralight, B6 unmanned).
 * The map draws a silhouette per kind; [GENERIC] when the category is missing or says nothing.
 */
enum class AircraftKind(val json: String) {
    LIGHT("light"),
    AIRLINER("airliner"),
    HEAVY("heavy"),
    ROTORCRAFT("rotorcraft"),
    GLIDER("glider"),
    BALLOON("balloon"),
    UAV("uav"),
    GENERIC("generic");

    companion object {
        fun of(category: String?): AircraftKind = when (category?.trim()?.uppercase()) {
            "A1", "B4" -> LIGHT
            "A2", "A3", "A6" -> AIRLINER
            "A4", "A5" -> HEAVY
            "A7" -> ROTORCRAFT
            "B1" -> GLIDER
            "B2" -> BALLOON
            "B6" -> UAV
            else -> GENERIC
        }
    }
}

/** T311: an emergency an aircraft declares: squawk 7700 (or a general emergency), 7600 (radio failure), 7500 (hijack). */
enum class Emergency(val json: String, val squawk: String, val line: String) {
    GENERAL("general", "7700", "Emergency"),
    RADIO("nordo", "7600", "Radio failure"),
    HIJACK("unlawful", "7500", "Hijack code");

    companion object {
        fun of(squawk: String?, field: String?): Emergency? = when (squawk?.trim()) {
            "7700" -> GENERAL
            "7600" -> RADIO
            "7500" -> HIJACK
            else -> when (field?.trim()?.lowercase()) {
                "general", "downed", "lifeguard", "minfuel" -> GENERAL
                "nordo" -> RADIO
                "unlawful" -> HIJACK
                else -> null
            }
        }
    }
}

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

    /** Aircraft on the ground are drawn only from this map zoom (an airport's apron reads at about z13). */
    const val GROUND_MIN_ZOOM = 13.0

    /** The default transition altitude (feet, the US and Canada): above it the card adds the flight level. */
    const val TRANSITION_FT = 18_000.0

    /** The query's largest radius, nautical miles (adsb.lol's limit). */
    const val MAX_RADIUS_NM = 250

    const val FEET_TO_M = 0.3048
    const val KNOT_TO_MPS = 0.514444
}
