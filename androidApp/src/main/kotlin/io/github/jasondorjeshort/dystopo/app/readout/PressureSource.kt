package io.github.jasondorjeshort.dystopo.app.readout

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.MapViewport
import io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.readout.MetarAltimeter
import io.github.jasondorjeshort.dystopo.readout.STATION_SOURCE_ID
import io.github.jasondorjeshort.dystopo.readout.StationPressure
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import io.github.jasondorjeshort.dystopo.store.LocationFix
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * T173: the weather stations' sea-level pressure (their altimeter settings) for the live readout's barometer, from the
 * registry's source [STATION_SOURCE_ID] (`sources.json`: NOAA's Aviation Weather Center data API, public domain; the
 * owner's rule: public-domain station data only). [LiveReadout] calls [tick] at each refresh; while the readout wants
 * the barometer (the strip shown in the foreground, the barometer switch on, an activity that fuses it, climb or
 * elevation shown) and the phone has one, it fetches the reports of the stations in a box around the map view and
 * hands the nearest station's setting to the estimator as a prior (T160's `onStationPressure`, whose uncertainty
 * grows with the station's distance and the report's age). Otherwise nothing is fetched.
 *
 * **What leaves the phone** (ADR 0031's new data flow): one HTTPS GET, `<url>?bbox=<south>,<west>,<north>,<east>&format=json`,
 * the box in whole degrees around the view's centre ([boxAround]): never the fix, no identifier, no activity, no
 * cookie; the user agent is the bare word [USER_AGENT] (the data API asks for one). Over any network (ADR 0023: no
 * Wi-Fi gate), about 18 kB (4 kB compressed) for some 36 stations; counted on `GET /online` › `stationPressure`.
 *
 * **Cadence and range** (the research of 2026-10-30, T173's report: aviation sets an altimeter from a station within
 * 100 nautical miles, reports are hourly, the data API asks for scoped, infrequent requests): a fetch when the view's box
 * changes (at most once a [BOX_GAP_MS]) and every [REFRESH_MS] while in use, [RETRY_MS] after a failure; a station
 * counts within [MAX_DISTANCE_M] of the fix (the view's centre before the first fix) and a report up to [MAX_AGE_MS]
 * old. The nearest such station is used; another replaces it only when nearer by [SWITCH_MARGIN_M] or when it falls
 * out of range, so each prior is applied once.
 *
 * The altimeter value is parsed from the raw report ([MetarAltimeter.parse] on `rawOb`), not the JSON's rounded
 * `altim`; the station's position comes from the JSON. Test hook: [testUrl] (`POST /readout/stations?url=`) points
 * the fetch at a mock endpoint.
 */
object PressureSource {
    const val TAG = "DystopoStations"
    const val DEFAULT_URL = "https://aviationweather.gov/api/data/metar"
    const val USER_AGENT = "Dystopo"
    const val MAX_DISTANCE_M = 100_000.0
    const val MAX_AGE_MS = 3 * 3_600_000L
    const val REFRESH_MS = 30 * 60_000L
    const val RETRY_MS = 5 * 60_000L
    const val BOX_GAP_MS = 60_000L
    const val SWITCH_MARGIN_M = 10_000.0
    const val TIMEOUT_MS = 15_000

    /** The box of whole degrees fetched (`bbox` is south, west, north, east). */
    data class Box(val west: Int, val south: Int, val east: Int, val north: Int) {
        val query: String get() = "bbox=$south,$west,$north,$east"
    }

    /** One station's report from a response: [hPa] parsed from the raw report, [timeMs] its observation (wall clock). */
    data class Station(val id: String, val lat: Double, val lon: Double, val hPa: Double, val timeMs: Long, val group: String)

    /** A response read: the stations with a valid altimeter group, how many reports it carried, how many were not usable. */
    data class Parsed(val stations: List<Station>, val reports: Int, val unusable: Int)

    /**
     * The box around [lat], [lon]: the whole-degree cell holding the point, widened by enough whole degrees on each
     * side to hold [MAX_DISTANCE_M] (one degree of latitude; of longitude one or more by the latitude, at most 3).
     */
    fun boxAround(lat: Double, lon: Double): Box {
        val la = floor(lat).toInt().coerceIn(-90, 89)
        val lo = floor(lon).toInt().coerceIn(-180, 179)
        val km = MAX_DISTANCE_M / 1000
        val latPad = ceil(km / 111.0).toInt()
        val c = cos(Math.toRadians(minOf(85.0, kotlin.math.abs(lat) + 1)))
        val lonPad = ceil(km / (111.0 * c)).toInt().coerceIn(1, 3)
        return Box((lo - lonPad).coerceAtLeast(-180), (la - latPad).coerceAtLeast(-90), (lo + 1 + lonPad).coerceAtMost(180), (la + 1 + latPad).coerceAtMost(90))
    }

    /** The request for [box]: the source's address, the box and the format, nothing else. */
    fun requestUrl(base: String, box: Box): String = "${base.trimEnd('?')}?${box.query}&format=json"

    /** Reads a data API response (a JSON array of reports): `rawOb` parsed for the altimeter, `lat`, `lon` and `obsTime` from the JSON. */
    fun parse(text: String, nowMs: Long): Parsed {
        val list = MiniJson.parse(text) as? List<*> ?: throw IllegalArgumentException("not a JSON array")
        val out = ArrayList<Station>()
        var unusable = 0
        for (o in list) {
            val m = o as? Map<*, *>
            val raw = m?.get("rawOb") as? String
            val lat = (m?.get("lat") as? Number)?.toDouble()
            val lon = (m?.get("lon") as? Number)?.toDouble()
            val p = raw?.let { MetarAltimeter.parse(it) }
            if (p == null || lat == null || lon == null) { unusable++; continue }
            val t = p.timeMs(nowMs) ?: (m["obsTime"] as? Number)?.toLong()?.times(1000)
            if (t == null) { unusable++; continue }
            out += Station(p.station, lat, lon, p.hPa, t, p.group)
        }
        // A station may report more than once (a special report after the routine one): the latest of each.
        val latest = out.groupBy { it.id }.values.map { l -> l.maxBy { it.timeMs } }
        return Parsed(latest, list.size, unusable)
    }

    /** The usable stations by distance from [lat], [lon]: within [MAX_DISTANCE_M], not older than [MAX_AGE_MS] (nor 10 min ahead). */
    fun inRange(stations: List<Station>, lat: Double, lon: Double, nowMs: Long): List<Pair<Station, Double>> =
        stations.asSequence()
            .filter { nowMs - it.timeMs <= MAX_AGE_MS && it.timeMs - nowMs <= 600_000L }
            .map { it to TrackStats.distanceM(lat, lon, it.lat, it.lon) }
            .filter { it.second <= MAX_DISTANCE_M }
            .sortedBy { it.second }
            .toList()

    /** The station to use: [current] unless gone from [ranked] or beaten by [SWITCH_MARGIN_M]; else the nearest. */
    fun choose(ranked: List<Pair<Station, Double>>, current: Station?): Pair<Station, Double>? {
        val best = ranked.firstOrNull() ?: return null
        val kept = current?.let { c -> ranked.firstOrNull { it.first.id == c.id } } ?: return best
        return if (best.second < kept.second - SWITCH_MARGIN_M) best else kept
    }

    // ---- state: written on the readout's thread ([tick]) and the fetch thread ----

    /** Test hook (`POST /readout/stations?url=`): the endpoint to fetch instead of [DEFAULT_URL]; null for the registry's. */
    @Volatile var testUrl: String? = null

    private val fetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-stations").apply { isDaemon = true } }

    @Volatile private var active = false
    @Volatile private var inFlight = false
    @Volatile private var lastBox: Box? = null
    @Volatile private var lastAttemptAt = 0L // elapsed realtime
    @Volatile private var lastOk = false
    @Volatile private var stations: List<Station> = emptyList()
    @Volatile private var lastResponse: Map<String, Any?>? = null
    @Volatile private var lastError: String? = null
    @Volatile private var lastRequest: String? = null

    // The prior handed to the estimator: the station, its distance when chosen, and the clock offset it was made on.
    @Volatile private var chosen: Station? = null
    @Volatile private var prior: StationPressure? = null
    private var priorOffsetMs = 0L
    @Volatile private var lastDistanceM: Double? = null

    /** A station's pressure has reached the estimator since the app started ([LiveReadout] sets it): the About screen credits the source. */
    @Volatile var credited = false
        internal set

    private val counts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun bump(k: String, by: Long = 1) { counts.merge(k, by, Long::plus) }

    /**
     * One refresh of the readout (its own thread): [wanted] when the barometer serves a shown entry and the phone has
     * one; [fix] the last fix fed; [offsetMs] the estimator's clock minus the wall clock. Starts a fetch when due and
     * returns the prior to feed, or null. The returned object is the same until the station or the report changes,
     * so the estimator (which applies a report once) is not fed it twice.
     */
    fun tick(wanted: Boolean, fix: LocationFix?, offsetMs: Long): StationPressure? {
        if (!wanted) {
            if (active) bump("stops")
            active = false
            return null
        }
        if (!active) bump("starts")
        active = true
        val view = MapViewport.current
        val nowWall = System.currentTimeMillis()
        if (view != null) maybeFetch(boxAround(view.centerLat, view.centerLon))
        val ref = fix?.let { it.lat to it.lon } ?: view?.let { it.centerLat to it.centerLon } ?: return null
        val pick = choose(inRange(stations, ref.first, ref.second, nowWall), chosen)
        if (pick == null) {
            chosen = null
            lastDistanceM = null
            return null
        }
        val (s, d) = pick
        lastDistanceM = d
        val p = prior
        if (p != null && chosen == s && kotlin.math.abs(offsetMs - priorOffsetMs) < 60_000L) return p
        chosen = s
        priorOffsetMs = offsetMs
        bump("priorsMade")
        return StationPressure(s.id, s.hPa, s.lat, s.lon, s.timeMs + offsetMs).also { prior = it }
    }

    private fun maybeFetch(box: Box) {
        if (inFlight) return
        val since = SystemClock.elapsedRealtime() - lastAttemptAt
        val due = lastAttemptAt == 0L ||
            (box != lastBox && since >= BOX_GAP_MS) ||
            since >= (if (lastOk) REFRESH_MS else RETRY_MS)
        if (!due) return
        inFlight = true
        lastAttemptAt = SystemClock.elapsedRealtime()
        lastBox = box
        val url = requestUrl(testUrl ?: DEFAULT_URL, box)
        fetcher.execute { fetch(url) }
    }

    private fun fetch(url: String) {
        val online = runCatching { OnlineService.get() }.getOrNull()
        lastRequest = url
        try {
            val c = CleartextPolicy.open(url)
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.useCaches = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            online?.stationFetches?.incrementAndGet()
            bump("fetches")
            val code = c.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            val bytes = c.inputStream.use { it.readBytes() }
            online?.stationBytes?.addAndGet(bytes.size.toLong())
            val r = parse(bytes.decodeToString(), System.currentTimeMillis())
            stations = r.stations
            lastOk = true
            lastError = null
            lastResponse = linkedMapOf("reports" to r.reports, "stations" to r.stations.size, "unusable" to r.unusable, "bytes" to bytes.size, "atMs" to System.currentTimeMillis())
            Log.i(TAG, "stations: ${r.reports} reports, ${r.stations.size} usable")
        } catch (e: Exception) {
            online?.stationFetchFailures?.incrementAndGet()
            bump("failures")
            lastOk = false
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "station fetch failed: $lastError")
        } finally {
            inFlight = false
        }
        LiveReadout.refresh()
    }

    /** `POST /readout/stations`: sets or clears [testUrl] (empty for the registry's) and makes the next refresh fetch. */
    fun configure(url: String?): Map<String, Any?> {
        if (url != null) testUrl = url.trim().ifEmpty { null }
        lastAttemptAt = 0L
        stations = emptyList()
        chosen = null
        prior = null
        lastDistanceM = null
        LiveReadout.refresh()
        return linkedMapOf("url" to (testUrl ?: DEFAULT_URL), "testUrl" to testUrl)
    }

    /** `GET /readout` › `seaLevelPressure`: the prior's value, distance and age, the source and the fetching. */
    fun json(applied: Long?): Map<String, Any?> {
        val s = chosen
        val now = System.currentTimeMillis()
        val src = SourceRegistry.builtIn[STATION_SOURCE_ID]
        return linkedMapOf(
            "state" to when {
                !active -> "off"
                s != null -> "in use"
                inFlight || lastAttemptAt == 0L -> "fetching"
                lastError != null && stations.isEmpty() -> "unavailable"
                else -> "no station in range"
            },
            "hPa" to s?.let { Math.round(it.hPa * 100) / 100.0 },
            "station" to s?.id,
            "group" to s?.group,
            "distanceM" to lastDistanceM?.let { Math.round(it).toDouble() },
            "ageS" to s?.let { (now - it.timeMs) / 1000 },
            "source" to STATION_SOURCE_ID,
            "credit" to src?.attribution,
            "applied" to applied,
            "limits" to linkedMapOf("maxDistanceM" to MAX_DISTANCE_M, "maxAgeS" to MAX_AGE_MS / 1000, "refreshS" to REFRESH_MS / 1000, "retryS" to RETRY_MS / 1000),
            "request" to lastRequest,
            "box" to lastBox?.let { listOf(it.west, it.south, it.east, it.north) },
            "lastResponse" to lastResponse,
            "lastError" to lastError,
            "stationsKnown" to stations.size,
            "nextFetchInS" to if (lastAttemptAt == 0L) 0 else ((if (lastOk) REFRESH_MS else RETRY_MS) - (SystemClock.elapsedRealtime() - lastAttemptAt)).coerceAtLeast(0) / 1000,
            "testUrl" to testUrl,
            "counts" to counts.toSortedMap(),
        )
    }
}
