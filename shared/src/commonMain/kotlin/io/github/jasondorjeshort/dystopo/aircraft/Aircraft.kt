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
    /** A position older than this is drawn hollow, with its age (seconds): the floor of [staleS]. */
    const val STALE_S = 30.0

    /** A position older than this is dropped (seconds; tar1090 drops at 58 s): the floor of [dropS]. */
    const val DROP_S = 60.0

    /** Between polls an aircraft is moved along its track by its speed for at most this long (seconds): the floor of [moveCapS]. */
    const val MOVE_CAP_S = 30.0

    /** T316: a position is stale after this many refresh intervals, dropped after [DROP_INTERVALS] (the idea's Plan). */
    const val STALE_INTERVALS = 1.5
    const val DROP_INTERVALS = 2.5

    /**
     * T316: the position's age past which it is drawn hollow at a refresh of [refreshS] seconds: 1.5 intervals, at least
     * [STALE_S] (30 s at 10 and 20 s, 45 s at 30 s, 90 s at 60 s, 180 s at 120 s, 270 s at 180 s).
     */
    fun staleS(refreshS: Int = REFRESH_DEFAULT_S): Double = maxOf(STALE_S, STALE_INTERVALS * refreshS)

    /** T316: the age past which an aircraft is dropped: 2.5 intervals, at least [DROP_S] (60 s at 20 s, 450 s at 180 s). */
    fun dropS(refreshS: Int = REFRESH_DEFAULT_S): Double = maxOf(DROP_S, DROP_INTERVALS * refreshS)

    /** T316: how long an aircraft is moved along its track between polls: one interval, at least [MOVE_CAP_S]. */
    fun moveCapS(refreshS: Int = REFRESH_DEFAULT_S): Double = maxOf(MOVE_CAP_S, refreshS.toDouble())

    /** A feed with no answer for this many refresh intervals (and at least this long) reads "Flight data stopped". */
    const val STOPPED_INTERVALS = 3
    const val STOPPED_MIN_S = 60.0

    /** The trail drawn behind each aircraft (seconds; tar1090's temporary trails are 90 s). */
    const val TRAIL_S = 90.0

    /** T316: the trail's span at a refresh of [refreshS]: two intervals, at least [TRAIL_S], so it always reaches the last report. */
    fun trailS(refreshS: Int = REFRESH_DEFAULT_S): Double = maxOf(TRAIL_S, 2.0 * refreshS)

    /** At most this many positions kept per aircraft for the tapped one's track (a 20 s poll: 4 hours): the floor of [historyMax]. */
    const val HISTORY_MAX = 720

    /** T316: the span the tapped aircraft's track keeps at any refresh (seconds: 4 hours). */
    const val HISTORY_S = 4 * 3600

    /** T316: positions kept per aircraft at a refresh of [refreshS]: 4 hours of polls, at least [HISTORY_MAX] (1,440 at 10 s). */
    fun historyMax(refreshS: Int = REFRESH_DEFAULT_S): Int = maxOf(HISTORY_MAX, HISTORY_S / refreshS.coerceAtLeast(1))

    /** Labels from this map zoom (tar1090 shows labels from zoom 8) or while at most [LABEL_MAX_COUNT] are in view (RadarBox: 250). */
    const val LABEL_MIN_ZOOM = 8.0
    const val LABEL_MAX_COUNT = 250

    /** The refresh interval's choices and default (seconds; the finding's recommendation 7; T316: 2 and 3 minutes, the owner's). */
    val REFRESH_CHOICES_S = listOf(10, 20, 30, 60, 120, 180)
    const val REFRESH_DEFAULT_S = 20

    /** adsb.lol's daily allowance without a key, per IP (requests a UTC day). */
    const val DAILY_REQUESTS = 500

    /** T316: how long [requests] last at one every [refreshS] seconds, in hours (500 at 20 s: 2.78). */
    fun hoursLeft(requests: Int, refreshS: Int): Double = requests.coerceAtLeast(0) * refreshS.toDouble() / 3600.0

    /** Aircraft on the ground are drawn only from this map zoom (an airport's apron reads at about z13). */
    const val GROUND_MIN_ZOOM = 13.0

    /** The default transition altitude (feet, the US and Canada): above it the card adds the flight level. */
    const val TRANSITION_FT = 18_000.0

    /** The query's largest radius, nautical miles (adsb.lol's limit). */
    const val MAX_RADIUS_NM = 250

    const val FEET_TO_M = 0.3048
    const val KNOT_TO_MPS = 0.514444
}
