package io.github.jasondorjeshort.dystopo.aircraft

import kotlin.math.roundToLong

/** T311: what the layer says about its feed (the finding: never an empty sky without a word). */
enum class FeedState(val json: String, val message: String?) {
    WAITING("waiting", "Getting flight data"),
    LIVE("live", null),
    OFFLINE("offline", "No flight data: offline"),
    STOPPED("stopped", "Flight data stopped"),
    LIMITED("limited", "Daily flight data limit reached"),
}

/** T311: how airports read (the "Airport names" setting): codes on labels and both on the card, codes, or cities. */
enum class AirportNaming(val key: String, val label: String) {
    BOTH("both", "Codes and cities"),
    CODES("codes", "Codes"),
    CITIES("cities", "Cities");

    companion object {
        val DEFAULT = BOTH
        fun of(s: String): AirportNaming? = entries.firstOrNull { it.key == s.trim().lowercase() }
    }
}

/**
 * T311: where the phone is, for distances and angles: [heightM] above EGM2008 (the fix's mean-sea-level altitude), and
 * [undulationM] the geoid's height above the WGS84 ellipsoid there, measured by the fix (its ellipsoid height minus
 * `mslAltitudeMeters`), or null when the fix gives no such pair.
 */
data class Observer(val lat: Double, val lon: Double, val heightM: Double, val undulationM: Double?)

/** One kept position: [atMs] when the aircraft was there (the poll's time minus `seen_pos`). */
data class TrackPoint(val lat: Double, val lon: Double, val altitudeM: Double?, val atMs: Long)

/** T311: one aircraft as the layer draws it now. */
data class AircraftNow(
    val report: AircraftReport,
    /** Moved up along its track by its speed for [movedS] (at most [AircraftRules.moveCapS] of the refresh). */
    val lat: Double,
    val lon: Double,
    val movedS: Double,
    val altitudeM: Double?,
    val altitudeKind: AltitudeKind,
    val ageS: Double,
    val stale: Boolean,
    val route: RoutePlausibility.Match?,
    val distanceM: Double?,
    val bearingDeg: Double?,
    val elevationDeg: Double?,
    val label: String,
    val trail: List<TrackPoint>,
    /** T316: the ground's height (EGM2008 metres) where the aircraft last reported, from the elevation data; null where unknown. */
    val groundM: Double? = null,
) {
    /** T316: the height above that ground ([AboveGround.of]), or null. */
    val aboveGround: AboveGround? get() = AboveGround.of(altitudeM, altitudeKind, groundM)

    val hex: String get() = report.hex
    val title: String get() = report.callSign ?: report.registration ?: report.hex.uppercase()
}

/** T311: an altitude in metres above EGM2008 and which height it was made from. */
data class AltitudeNow(val meters: Double?, val kind: AltitudeKind)

/**
 * T311: the layer's state between polls, pure (times are passed in): the aircraft last reported with what the layer has
 * seen of each since it started ([history], memory only), the feed's state, and the drawn view: positions moved up to now,
 * stale ones marked, old ones dropped, routes checked against where each is, distances and angles from the phone.
 */
class AircraftTracker {
    private class Seen(var report: AircraftReport, var posAtMs: Long, var altitude: AltitudeNow, val history: ArrayDeque<TrackPoint>) {
        /** T316: the reported position [groundM] was sampled at (null: not yet), and the sample (null: no data there). */
        var groundAt: Pair<Double, Double>? = null
        var groundM: Double? = null
        val groundNow: Double? get() = if (groundAt == (report.lat to report.lon)) groundM else null
    }

    /** T316: one reported position whose ground is not sampled yet ([groundNeeded], [setGround]). */
    data class GroundAsk(val hex: String, val lat: Double, val lon: Double)

    private val seen = LinkedHashMap<String, Seen>()
    var state: FeedState = FeedState.WAITING
        private set
    var lastOkMs: Long = 0
        private set
    var lastFetchMs: Long = 0
        private set
    var polls: Long = 0
        private set
    var failures: Int = 0
        private set
    var lastError: String? = null
        private set
    var lastPoll: AircraftPoll? = null
        private set
    var lastUndulationM: Double? = null
        private set

    /** Forgets everything (the layer turned off: nothing of the feed is kept). */
    fun clear() {
        seen.clear()
        state = FeedState.WAITING
        lastOkMs = 0
        lastFetchMs = 0
        failures = 0
        lastError = null
        lastPoll = null
    }

    /**
     * Takes a poll's outcome at [nowMs]. [undulationM] (the phone's, null without a fix that gives it) converts every
     * geometric altitude of the poll; [refreshS] the interval, for when a run of failures reads as a stopped feed, how
     * many positions each aircraft keeps ([AircraftRules.historyMax]) and when an unseen one is dropped
     * ([AircraftRules.dropS]; T316).
     */
    fun onFetch(fetch: AircraftFetch, nowMs: Long, undulationM: Double?, refreshS: Int = AircraftRules.REFRESH_DEFAULT_S) {
        lastFetchMs = nowMs
        when (fetch) {
            is AircraftFetch.Ok -> {
                polls++
                failures = 0
                lastError = null
                lastOkMs = nowMs
                lastPoll = fetch.poll
                lastUndulationM = undulationM
                state = FeedState.LIVE
                for (r in fetch.poll.aircraft) {
                    val at = fetch.poll.atMs - (r.seenPosS * 1000).roundToLong()
                    val alt = altitude(r, undulationM)
                    val s = seen[r.hex]
                    if (s == null) {
                        seen[r.hex] = Seen(r, at, alt, ArrayDeque<TrackPoint>().also { it.addLast(TrackPoint(r.lat, r.lon, alt.meters, at)) })
                    } else {
                        if (at >= s.posAtMs) {
                            s.report = r
                            s.altitude = alt
                            if (at > s.posAtMs && (s.history.lastOrNull()?.let { it.lat != r.lat || it.lon != r.lon } != false)) {
                                s.history.addLast(TrackPoint(r.lat, r.lon, alt.meters, at))
                                val keep = AircraftRules.historyMax(refreshS)
                                while (s.history.size > keep) s.history.removeFirst()
                            }
                            s.posAtMs = at
                        }
                    }
                }
            }
            is AircraftFetch.Offline -> {
                lastError = fetch.reason
                state = FeedState.OFFLINE
            }
            is AircraftFetch.Limited -> {
                lastError = "HTTP 429"
                state = FeedState.LIMITED
            }
            is AircraftFetch.Failed -> {
                failures++
                lastError = fetch.reason
                val since = if (lastOkMs == 0L) Double.MAX_VALUE else (nowMs - lastOkMs) / 1000.0
                val stopAfter = maxOf(AircraftRules.STOPPED_MIN_S, AircraftRules.STOPPED_INTERVALS * refreshS.toDouble())
                if (failures >= AircraftRules.STOPPED_INTERVALS || since >= stopAfter) state = FeedState.STOPPED
            }
        }
        prune(nowMs, refreshS)
    }

    /** A feed that has not answered since [nowMs] minus the stop time (a poll that hangs) reads as stopped. */
    fun checkStalled(nowMs: Long, refreshS: Int) {
        if (state != FeedState.LIVE || lastOkMs == 0L) return
        val stopAfter = maxOf(AircraftRules.STOPPED_MIN_S, AircraftRules.STOPPED_INTERVALS * refreshS.toDouble())
        if ((nowMs - lastOkMs) / 1000.0 >= stopAfter) state = FeedState.STOPPED
    }

    private fun prune(nowMs: Long, refreshS: Int) {
        val drop = AircraftRules.dropS(refreshS)
        val it = seen.values.iterator()
        while (it.hasNext()) if ((nowMs - it.next().posAtMs) / 1000.0 > drop) it.remove()
    }

    val count: Int get() = seen.size

    /**
     * T316: the reported positions whose ground is not sampled yet, one per aircraft (each aircraft's ground is sampled
     * once per reported position, not each second: the cache). The caller samples them off this object's lock and
     * hands each back with [setGround].
     */
    fun groundNeeded(): List<GroundAsk> =
        seen.values.filter { it.groundAt != (it.report.lat to it.report.lon) }.map { GroundAsk(it.report.hex, it.report.lat, it.report.lon) }

    /** T316: the ground's height [groundM] (EGM2008 metres, null where the elevation data has none) at [hex]'s reported ([lat], [lon]). */
    fun setGround(hex: String, lat: Double, lon: Double, groundM: Double?) {
        val s = seen[hex] ?: return
        s.groundAt = lat to lon
        s.groundM = groundM?.takeUnless { it.isNaN() }
    }

    /**
     * The aircraft as drawn at [nowMs]: moved up, aged, with routes and angles; dropped ones left out. T316: [refreshS]
     * the interval, for the stale, drop, move-cap and trail times ([AircraftRules.staleS], [AircraftRules.dropS],
     * [AircraftRules.moveCapS], [AircraftRules.trailS]; the default keeps the 20 s behaviour).
     */
    fun view(
        nowMs: Long,
        observer: Observer?,
        routes: RouteTable?,
        naming: AirportNaming = AirportNaming.DEFAULT,
        refreshS: Int = AircraftRules.REFRESH_DEFAULT_S,
    ): List<AircraftNow> {
        prune(nowMs, refreshS)
        return seen.values.map { s -> now(s, nowMs, observer, routes, naming, refreshS) }
    }

    /** All positions the layer kept for [hex] since it first saw it, oldest first (the tapped aircraft's solid track). */
    fun history(hex: String): List<TrackPoint> = seen[hex]?.history?.toList().orEmpty()

    private fun now(s: Seen, nowMs: Long, observer: Observer?, routes: RouteTable?, naming: AirportNaming, refreshS: Int): AircraftNow {
        val r = s.report
        val age = ((nowMs - s.posAtMs) / 1000.0).coerceAtLeast(0.0)
        val cap = AircraftRules.moveCapS(refreshS)
        val move = moveUp(r, age, cap)
        val lat = move.first
        val lon = move.second
        val moved = if (lat == r.lat && lon == r.lon) 0.0 else minOf(age, cap)
        val match = r.callSign?.let { cs -> routes?.lookup(cs) }?.let { RoutePlausibility.check(it, lat, lon, r.trackDeg, r.groundSpeedKt, r.onGround, r.verticalRateFpm) }
        var dist: Double? = null
        var bearing: Double? = null
        var elev: Double? = null
        if (observer != null) {
            val d = AircraftGeo.distanceM(observer.lat, observer.lon, lat, lon)
            dist = d
            bearing = AircraftGeo.bearingDeg(observer.lat, observer.lon, lat, lon)
            val h = s.altitude.meters
            if (h != null) elev = AircraftGeo.elevationAngleDeg(d, observer.heightM, h)
        }
        val stale = age > AircraftRules.staleS(refreshS)
        val trailFrom = nowMs - (AircraftRules.trailS(refreshS) * 1000).toLong()
        val trail = s.history.filter { it.atMs >= trailFrom } + TrackPoint(lat, lon, s.altitude.meters, nowMs)
        return AircraftNow(
            r, lat, lon, moved, s.altitude.meters, s.altitude.kind, age, stale, match, dist, bearing, elev,
            AircraftText.label(r, match, naming, if (stale) age else null), trail, s.groundNow,
        )
    }

    companion object {
        /** The aircraft's position moved along its track by its ground speed for its age, at most [capS] (T316: the refresh's [AircraftRules.moveCapS]). */
        fun moveUp(r: AircraftReport, ageS: Double, capS: Double = AircraftRules.MOVE_CAP_S): Pair<Double, Double> {
            val track = r.trackDeg ?: return r.lat to r.lon
            val gs = r.groundSpeedKt ?: return r.lat to r.lon
            if (r.onGround || gs <= 0.0 || ageS <= 0.0) return r.lat to r.lon
            val t = minOf(ageS, capS)
            return AircraftGeo.move(r.lat, r.lon, track, gs * AircraftRules.KNOT_TO_MPS * t)
        }

        /**
         * The altitude above EGM2008: the geometric one (WGS84 ellipsoid) minus the phone's [undulationM]; with no
         * undulation or no geometric altitude, the pressure altitude, marked [AltitudeKind.BAROMETRIC]; on the ground,
         * the geometric one when there is one, else none.
         */
        fun altitude(r: AircraftReport, undulationM: Double?): AltitudeNow {
            val geom = r.altGeomFt
            if (geom != null && undulationM != null) return AltitudeNow(geom * AircraftRules.FEET_TO_M - undulationM, if (r.onGround) AltitudeKind.GROUND else AltitudeKind.GEOMETRIC)
            if (r.onGround) return AltitudeNow(null, AltitudeKind.GROUND)
            r.altBaroFt?.let { return AltitudeNow(it * AircraftRules.FEET_TO_M, AltitudeKind.BAROMETRIC) }
            return AltitudeNow(null, AltitudeKind.NONE)
        }
    }
}
