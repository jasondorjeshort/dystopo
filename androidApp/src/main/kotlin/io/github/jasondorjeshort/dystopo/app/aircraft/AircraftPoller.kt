package io.github.jasondorjeshort.dystopo.app.aircraft

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.aircraft.AdsbLolSource
import io.github.jasondorjeshort.dystopo.aircraft.AircraftFetch
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttp
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttpReply
import io.github.jasondorjeshort.dystopo.aircraft.AircraftNoConnection
import io.github.jasondorjeshort.dystopo.aircraft.AircraftNow
import io.github.jasondorjeshort.dystopo.aircraft.AircraftQuery
import io.github.jasondorjeshort.dystopo.aircraft.AircraftQuota
import io.github.jasondorjeshort.dystopo.aircraft.AircraftSource
import io.github.jasondorjeshort.dystopo.aircraft.AircraftText
import io.github.jasondorjeshort.dystopo.aircraft.AircraftTracker
import io.github.jasondorjeshort.dystopo.aircraft.FeedState
import io.github.jasondorjeshort.dystopo.aircraft.Observer
import io.github.jasondorjeshort.dystopo.aircraft.ReplaySource
import io.github.jasondorjeshort.dystopo.aircraft.RouteTable
import io.github.jasondorjeshort.dystopo.aircraft.TrackPoint
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.online.ConnectFailed
import io.github.jasondorjeshort.dystopo.app.online.HttpGet
import io.github.jasondorjeshort.dystopo.app.online.HttpReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** T311: what the poller last made: the aircraft drawn now, the feed's state and its status line, when, and who asked. */
data class AircraftView(
    val aircraft: List<AircraftNow>,
    val state: FeedState,
    val message: String?,
    val atMs: Long,
    val owners: Set<String>,
) {
    companion object {
        val EMPTY = AircraftView(emptyList(), FeedState.WAITING, null, 0L, emptySet())
    }
}

/**
 * T311: the one poll loop of the Flights data, driven by its callers. A caller that shows aircraft asks with
 * [request] (an owner name and the centre it wants, called again whenever the centre moves) and stops with [release]:
 * the map ([AircraftLayer], owner [MAP]) asks for its view's centre and a radius reaching the view's corners; another
 * view may ask for the phone's own position. The latest request decides the query; the centre is always rounded to
 * 0.1° ([AircraftQuery.round]). It polls only while Flight tracking is on, someone asks, and the app is in the
 * foreground; one request per refresh interval whoever asks, so the day's count ([AircraftQuota]) is spent once.
 *
 * Once a second it makes [view] ([AircraftTracker]: positions moved up, stale marked, old dropped, routes checked,
 * distances and angles from the phone) and tells its listeners. Feed data stays in memory (ODbL); turning Flight
 * tracking off forgets it.
 */
object AircraftPoller {
    const val MAP = "map"
    private const val PREFS = "aircraft"
    private const val KEY_QUOTA = "quota"
    const val ROUTE_ASSET = "aircraft/routes.gzip"

    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dystopo-aircraft").apply { isDaemon = true } }
    private val poller = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-aircraft-poll").apply { isDaemon = true } }
    @Volatile private var started = false

    private val tracker = AircraftTracker()
    private val lock = Any()

    private class Ask(val query: AircraftQuery, val at: Long)

    private val asks = LinkedHashMap<String, Ask>()

    private val flow = MutableStateFlow(AircraftView.EMPTY)

    /** The latest view (once a second while anything is drawn or asked for). */
    val view: StateFlow<AircraftView> get() = flow

    /** Called on the tick thread before each tick (the map refreshes its request here) and after it with the new view. */
    private val beforeTick = CopyOnWriteArrayList<() -> Unit>()
    private val listeners = CopyOnWriteArrayList<(AircraftView) -> Unit>()

    fun onBeforeTick(f: () -> Unit) { beforeTick += f; start() }
    fun addListener(f: (AircraftView) -> Unit) { listeners += f; start() }

    /** The source polled: null for the live feed; a replay set by `POST /aircraft/replay`. */
    @Volatile var replay: ReplaySource? = null
        private set

    /** Debug: the undulation used instead of the fix's (`POST /aircraft/replay?undulationM=`), for an emulator fix that has none. */
    @Volatile var undulationOverride: Double? = null

    /** Debug: polls fail as with no connection (`POST /aircraft?offline=1`). */
    @Volatile var forceOffline = false

    @Volatile private var routes: RouteTable? = null
    @Volatile var routesMs: Long? = null
        private set
    @Volatile var routesError: String? = null
        private set
    @Volatile private var routesLoading = false

    @Volatile private var quota: AircraftQuota? = null
    @Volatile private var polling = false
    @Volatile private var lastPollAt = 0L
    @Volatile var lastQuery: AircraftQuery? = null
        private set
    @Volatile var lastOwner: String? = null
        private set
    @Volatile var lastResponseMs: Long? = null
        private set
    @Volatile var lastUrl: String? = null
        private set
    @Volatile var lastCount: Int? = null
        private set
    @Volatile var requests = 0L
        private set
    @Volatile var skippedLimited = 0L
        private set
    @Volatile var ticks = 0L
        private set
    @Volatile var pausedBecause: String? = null
        private set

    /** Flight tracking (the setting). */
    val on: Boolean get() = AppSettings.AIRCRAFT.value

    val state: FeedState get() = synchronized(lock) { tracker.state }

    /** The status line, or null: offline, stopped, the day's limit, or waiting for the first answer. */
    val message: String? get() = if (!on) null else state.message

    /** The credit a card carries: the live feed's, or the replay's own words. */
    val credit: String get() = if (replay != null) AircraftText.REPLAY_CREDIT else AircraftText.CREDIT

    /**
     * [owner] wants aircraft around ([lat], [lon]) within [radiusNm] (at most 250): kept until [release], replaced by the
     * owner's next call. The centre is rounded to 0.1° before it is used. Any thread.
     */
    fun request(owner: String, lat: Double, lon: Double, radiusNm: Int) {
        val q = AircraftQuery(AircraftQuery.round(lat).coerceIn(-89.9, 89.9), AircraftQuery.round(((lon + 540.0) % 360.0) - 180.0), radiusNm.coerceIn(AircraftQuery.MIN_RADIUS_NM, io.github.jasondorjeshort.dystopo.aircraft.AircraftRules.MAX_RADIUS_NM))
        val first = synchronized(asks) {
            val had = asks[owner]
            asks[owner] = Ask(q, if (had != null && had.query == q) had.at else System.currentTimeMillis())
            had == null
        }
        start()
        if (first) { lastPollAt = 0L; ticker.execute { runCatching { tick() } } }
    }

    /** [owner] no longer shows aircraft. Any thread. */
    fun release(owner: String) {
        synchronized(asks) { asks.remove(owner) }
    }

    fun owners(): Set<String> = synchronized(asks) { asks.keys.toSet() }

    /** All positions kept for [hex] since it was first seen (the tapped aircraft's solid track). */
    fun history(hex: String): List<TrackPoint> = synchronized(lock) { tracker.history(hex) }

    /** T314: the feed's answered polls so far (the map's faint route lines are rebuilt when it moves, not every tick). */
    val polls: Long get() = synchronized(lock) { tracker.polls }

    /** A setting changed (`SettingsRoutes.apply`): on polls at once, off forgets everything. */
    fun settingsChanged() {
        if (!on) forget() else lastPollAt = 0L
        start()
        ticker.execute { runCatching { tick() } }
    }

    private fun forget() {
        synchronized(lock) { tracker.clear() }
        publish(AircraftView.EMPTY.copy(atMs = System.currentTimeMillis(), owners = owners()))
    }

    private fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            ticker.scheduleWithFixedDelay({ runCatching { tick() } }, 1, 1, TimeUnit.SECONDS)
        }
    }

    /** Why it does not poll now, or null when it may: off, nobody asks, or the app in the background. */
    private fun pauseReason(): String? {
        if (!on) return "off"
        if (owners().isEmpty()) return "nobodyAsks"
        DystopoApp.instance.map.backgroundReason()?.let { return it }
        return null
    }

    private fun tick() {
        ticks++
        for (f in beforeTick) runCatching { f() }
        val now = System.currentTimeMillis()
        if (!on) {
            if (flow.value.aircraft.isNotEmpty() || flow.value.state != FeedState.WAITING) forget()
            return
        }
        ensureRoutes()
        pausedBecause = pauseReason()
        val refreshS = AppSettings.AIRCRAFT_REFRESH_S.value
        if (pausedBecause == null && !polling && now - lastPollAt >= refreshS * 1000L) {
            val ask = synchronized(asks) { asks.entries.maxByOrNull { it.value.at } }
            if (ask != null) {
                polling = true
                lastPollAt = now
                poller.execute { try { poll(ask.key, ask.value.query) } finally { polling = false } }
            }
        }
        val list = synchronized(lock) {
            tracker.checkStalled(now, refreshS)
            tracker.view(now, observer(), routes, AppSettings.AIRCRAFT_AIRPORTS.value, refreshS) // T316: the times scale with it
        }
        publish(AircraftView(list, state, message, now, owners()))
    }

    private fun publish(v: AircraftView) {
        flow.value = v
        for (l in listeners) runCatching { l(v) }
    }

    /** One poll on the poll thread. */
    private fun poll(owner: String, q: AircraftQuery) {
        val now = System.currentTimeMillis()
        lastQuery = q
        lastOwner = owner
        val r = replay
        val fetch: AircraftFetch = when {
            forceOffline -> AircraftFetch.Offline("debug: offline")
            r != null -> r.poll(q, now)
            else -> live(q, now)
        }
        val und = undulation()
        synchronized(lock) { tracker.onFetch(fetch, System.currentTimeMillis(), und, AppSettings.AIRCRAFT_REFRESH_S.value) }
        if (fetch is AircraftFetch.Ok) {
            lastResponseMs = fetch.ms
            lastUrl = fetch.url
            lastCount = fetch.poll.aircraft.size
        }
    }

    /** The live feed, counting the day's requests; none while a 429's pause holds or the phone has no network. */
    private fun live(q: AircraftQuery, now: Long): AircraftFetch {
        val qt = quota()
        if (qt.isLimited(now)) { skippedLimited++; return AircraftFetch.Limited(null) }
        if (!networkUp()) return AircraftFetch.Offline("no network")
        qt.spend(now)
        requests++
        saveQuota(qt)
        val f = source.poll(q, now)
        if (f is AircraftFetch.Limited) { qt.limited(System.currentTimeMillis(), f.retryAfterS); saveQuota(qt) }
        return f
    }

    private val source: AircraftSource by lazy { AdsbLolSource(http, BuildConfig.VERSION_NAME, { System.currentTimeMillis() }) }

    /** The feed's GET: [FeedGet] (an `online/OnlineFetcher.kt` [HttpGet]) with the generic User-Agent and Retry-After read. */
    private val http = AircraftHttp { url, headers ->
        try {
            val r = FeedGet(headers).get(url)
            AircraftHttpReply(r.status, r.body, r.headers["retry-after"])
        } catch (e: ConnectFailed) {
            throw AircraftNoConnection(e.message ?: "no connection")
        }
    }

    private fun networkUp(): Boolean {
        val cm = DystopoApp.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return cm.activeNetwork != null
    }

    // --- the phone's position, its undulation ---

    /** Where the phone is (the map's last fix), its height above EGM2008 and the undulation the fix measures. */
    fun observer(): Observer? {
        val l = DystopoApp.instance.map.lastLocation ?: return null
        return Observer(l.latitude, l.longitude, msl(l) ?: 0.0, undulation(l))
    }

    private fun msl(l: Location): Double? = when {
        Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude() -> l.mslAltitudeMeters
        l.hasAltitude() -> l.altitude
        else -> null
    }

    /** The fix's ellipsoid height minus its mean-sea-level altitude (API 34; LocationSource.kt), or the debug override. */
    private fun undulation(l: Location? = DystopoApp.instance.map.lastLocation): Double? {
        undulationOverride?.let { return it }
        l ?: return null
        if (Build.VERSION.SDK_INT >= 34 && l.hasAltitude() && l.hasMslAltitude()) return l.altitude - l.mslAltitudeMeters
        return null
    }

    // --- the route table and the day's count ---

    private fun ensureRoutes() {
        if (routes != null || routesLoading || routesError != null) return
        routesLoading = true
        poller.execute {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val bytes = DystopoApp.instance.assets.open(ROUTE_ASSET).use { GZIPInputStream(it, 65536).readBytes() }
                routes = RouteTable.parse(bytes)
                routesMs = SystemClock.elapsedRealtime() - t0
            } catch (e: Exception) {
                routesError = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                routesLoading = false
            }
        }
    }

    fun routesJson(): Map<String, Any?>? = routes?.json()?.plus(mapOf("loadMs" to routesMs)) ?: routesError?.let { mapOf("error" to it) }

    @Synchronized
    private fun quota(): AircraftQuota = quota ?: AircraftQuota.decode(prefs().getString(KEY_QUOTA, null)).also { quota = it }

    private fun saveQuota(q: AircraftQuota) = prefs().edit().putString(KEY_QUOTA, q.encode()).apply()

    private fun prefs() = DystopoApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * "about 480 requests left today (UTC), about 3 h at this rate", the line under the refresh setting (ADR 0023: a
     * visible limit; T316: the hours at the current interval, [AircraftText.quotaLine]).
     */
    fun quotaLine(now: Long = System.currentTimeMillis()): String {
        val q = quota()
        return AircraftText.quotaLine(q.left(now), q.isLimited(now), AppSettings.AIRCRAFT_REFRESH_S.value, now)
    }

    /** T316: how many hours the day's requests left last at the current interval (0 while a 429's pause holds). */
    fun hoursLeft(now: Long = System.currentTimeMillis()): Double {
        val q = quota()
        if (q.isLimited(now)) return 0.0
        return io.github.jasondorjeshort.dystopo.aircraft.AircraftRules.hoursLeft(q.left(now), AppSettings.AIRCRAFT_REFRESH_S.value)
    }

    fun quotaJson(now: Long = System.currentTimeMillis()): Map<String, Any?> = quota().json(now)

    /** Debug: sets the day's count (`POST /aircraft?spent=`). */
    fun setSpent(n: Int) {
        val q = quota()
        q.spend(System.currentTimeMillis())
        q.spent = n
        saveQuota(q)
    }

    /** Debug: a 429 as the feed would answer it (`POST /aircraft?limit=<s>`), or 0 to lift it. */
    fun limit(seconds: Long) {
        val q = quota()
        if (seconds <= 0) q.limitedUntilMs = 0 else q.limited(System.currentTimeMillis(), seconds)
        saveQuota(q)
        if (seconds > 0) synchronized(lock) { tracker.onFetch(AircraftFetch.Limited(seconds), System.currentTimeMillis(), null, AppSettings.AIRCRAFT_REFRESH_S.value) }
    }

    /** Plays [text] (the replay file) from now; null goes back to the live feed. Forgets what was drawn. */
    fun setReplay(text: String?, name: String = "replay") {
        replay = text?.let { ReplaySource(ReplaySource.Replay.parse(it), System.currentTimeMillis(), name) }
        forget()
        lastPollAt = 0L
        start()
        ticker.execute { runCatching { tick() } }
    }

    fun json(now: Long = System.currentTimeMillis()): Map<String, Any?> = linkedMapOf(
        "on" to on, "state" to state.json, "message" to message, "pausedBecause" to pausedBecause, "owners" to owners().sorted(),
        "source" to (replay?.name ?: "adsb.lol"), "credit" to credit,
        "refreshS" to AppSettings.AIRCRAFT_REFRESH_S.value, "airportNames" to AppSettings.AIRCRAFT_AIRPORTS.value.key,
        // T316: the times at this interval and how long the day's requests last at it
        "staleS" to io.github.jasondorjeshort.dystopo.aircraft.AircraftRules.staleS(AppSettings.AIRCRAFT_REFRESH_S.value),
        "dropS" to io.github.jasondorjeshort.dystopo.aircraft.AircraftRules.dropS(AppSettings.AIRCRAFT_REFRESH_S.value),
        "moveCapS" to io.github.jasondorjeshort.dystopo.aircraft.AircraftRules.moveCapS(AppSettings.AIRCRAFT_REFRESH_S.value),
        "hoursLeft" to Math.round(hoursLeft(now) * 100) / 100.0,
        "query" to lastQuery?.json(), "queryOwner" to lastOwner, "lastUrl" to lastUrl, "lastResponseMs" to lastResponseMs, "lastCount" to lastCount,
        "requests" to requests, "skippedLimited" to skippedLimited, "quota" to quotaJson(now), "quotaLine" to quotaLine(now),
        "observer" to observer()?.let { o -> linkedMapOf("heightM" to Math.round(o.heightM), "undulationM" to o.undulationM, "undulationOverride" to undulationOverride) },
        "routes" to routesJson(), "ticks" to ticks, "forceOffline" to forceOffline,
    )
}

/**
 * T311: the feed's GET, an [HttpGet] as `online/OnlineFetcher.kt` defines it: [io.github.jasondorjeshort.dystopo.app.online.UrlConnectionGet]'s
 * short timeouts, plus the request [headers] (the generic User-Agent) and the Retry-After header read back (lower-case,
 * in [HttpReply.headers]).
 */
internal class FeedGet(private val headers: Map<String, String>, private val connectTimeoutMs: Int = 5_000, private val readTimeoutMs: Int = 15_000) : HttpGet {
    override fun get(url: String): HttpReply {
        val conn = java.net.URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        try {
            try {
                conn.connect()
            } catch (e: IOException) {
                throw ConnectFailed("${e.javaClass.simpleName}: ${e.message}", e)
            }
            val status = conn.responseCode
            val body = if (status in 200..299) conn.inputStream.use { it.readBytes() } else ByteArray(0)
            val h = conn.getHeaderField("Retry-After")?.let { mapOf("retry-after" to it) } ?: emptyMap()
            return HttpReply(status, body, h)
        } finally {
            conn.disconnect()
        }
    }
}
