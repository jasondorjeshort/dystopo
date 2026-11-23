package io.github.jasondorjeshort.dystopo.app.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.tracking.GpsLocationSource
import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.HorizonProfile
import io.github.jasondorjeshort.dystopo.gnss.Constellation
import io.github.jasondorjeshort.dystopo.gnss.GnssCases
import io.github.jasondorjeshort.dystopo.gnss.GnssHealth
import io.github.jasondorjeshort.dystopo.gnss.Satellite
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.app.terrain.TerrainRead
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * T307: the phone's GNSS for the GPS view and "Check my GPS". Nothing here runs until something [hold]s it: the view
 * while it is open ([GnssUi]) and the check while it runs ([GnssCheck]). While held it registers a [GnssStatus.Callback]
 * (the satellites: system, number, C/N0, elevation, azimuth, used in the fix, ephemeris, almanac, carrier frequency) and
 * its own GPS `requestLocationUpdates` (the [GpsLocationSource] pattern, on its looper), so the status flows whatever the
 * map's own listener does (an injected fix stops that one); both are removed when the last holder lets go. T333: a
 * recording holds it status-only (`recording`, [hold]'s `statusOnly`): the callback alone, the recorder's own listener
 * being the one that fixes, and [position] unchanged by it.
 *
 * - **Time to first fix** ([ttffMs]) is the app's own: from the first time `MapController` started its device fixes in
 *   this process to the first GPS fix its listener heard ([onDeviceStart], [onDeviceFix]); null (a dash) before that.
 *   The engine's own figure from [GnssStatus.Callback.onFirstFix] is kept beside it ([engineTtffMs], JSON only).
 * - **Injection** (debug, `POST /gnss?case=`): a synthetic status from [GnssCases] replaces the real one in [satellites]
 *   until [clear]; the emulator's GNSS reports one fixed set, so the weak and faulty cases are testable only this way.
 * - **The terrain's horizon** ([horizonAt]): `Horizon.sweep`'s [HorizonProfile] (1 degree bearings from true north, eye
 *   height [Horizon.EYE_HEIGHT_M]) at the phone's position, over T340's merged read ([TerrainRead]: the open maps cell by
 *   cell, the deepest first, then the online source; the Panorama reads the same), worked out on its own thread and
 *   kept until the position moves [HORIZON_MOVE_M]; [HorizonState.reason] says why there is none. T321: up to
 *   [HORIZON_KEEP] are kept (the fix's beside the Horizon view's chosen points), so going back to the fix sweeps nothing.
 *   T340: one made before a map opened or closed, or lacking cells (or none) before the network came back, is swept again.
 */
object Gnss {
    /** The horizon is worked out again once the phone is this far from where it was, metres. */
    const val HORIZON_MOVE_M = 100.0

    private val app get() = DystopoApp.instance
    private val holders = LinkedHashSet<String>()
    private var callback: GnssStatus.Callback? = null
    private var listener: LocationListener? = null

    @Volatile var real: List<Satellite> = emptyList()
        private set
    @Volatile var realAt = 0L
        private set
    @Volatile var injected: Pair<String, List<Satellite>>? = null
        private set
    @Volatile var engine: String = "off"
        private set
    @Volatile var error: String? = null
        private set
    val statusCallbacks = AtomicLong()
    val fixes = AtomicLong()

    /** The newest fix of the view's own GPS listener. */
    @Volatile var lastFix: Location? = null
        private set

    // ---- time to first fix ----
    @Volatile private var deviceStartAt = 0L
    @Volatile var ttffMs: Long? = null
        private set
    @Volatile var engineTtffMs: Long? = null
        private set

    /** `MapController.startDeviceLocation` registered its listener (the first time counts). */
    fun onDeviceStart() {
        if (deviceStartAt == 0L) deviceStartAt = SystemClock.elapsedRealtime()
    }

    /** The map's listener heard a GPS fix. */
    fun onDeviceFix() {
        if (ttffMs == null && deviceStartAt > 0) ttffMs = SystemClock.elapsedRealtime() - deviceStartAt
    }

    // ---- holding ----
    val held: Boolean get() = synchronized(holders) { holders.isNotEmpty() }
    fun holders(): List<String> = synchronized(holders) { holders.toList() }

    /** T333: the holders that want the status only (a recording's weak-signal watch), not the view's own fixes. */
    private val statusOnly = HashSet<String>()

    /**
     * T333: whether a holder wants the view's own fixes ([lastFix]): every holder but a status-only one. [position] reads the
     * view's own fix only then, so a recording's hold changes no fix the sky views stand on.
     */
    val fixHeld: Boolean get() = synchronized(holders) { holders.any { it !in statusOnly } }

    fun statusOnlyHolders(): List<String> = synchronized(holders) { holders.filter { it in statusOnly } }

    /**
     * [who] wants the GNSS status (true) or no longer (false); the callback and the listener follow the set of holders.
     * T333: a [statusOnly] holder (the recording's watch, whose recorder has its own GPS listener) registers the status
     * callback alone; the view's own GPS listener runs only while some other holder holds.
     */
    fun hold(who: String, on: Boolean, statusOnly: Boolean = false) {
        val (before, after) = synchronized(holders) {
            val b = holdLevel()
            if (on) {
                holders += who
                if (statusOnly) this.statusOnly += who else this.statusOnly -= who
            } else {
                holders -= who
                this.statusOnly -= who
            }
            b to holdLevel()
        }
        if (before != after) follow(after)
    }

    /** 0 nothing held, 1 the status only, 2 the status and the view's own fixes. Under the holders' lock. */
    private fun holdLevel(): Int = when {
        holders.isEmpty() -> 0
        holders.all { it in statusOnly } -> 1
        else -> 2
    }

    @Synchronized
    private fun follow(level: Int) {
        when (level) {
            0 -> stop()
            else -> {
                start(fixes = level == 2)
                if (level == 1) listener?.let { l ->
                    runCatching { manager.removeUpdates(l) }
                    listener = null
                }
            }
        }
    }

    fun permitted(context: Context = app): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val manager: LocationManager get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission") // permitted() first
    @Synchronized
    private fun start(fixes: Boolean) {
        if (!permitted()) {
            error = "no location permission"
            return
        }
        if (callback != null) {
            if (fixes && listener == null) startListener()
            return
        }
        val cb = object : GnssStatus.Callback() {
            override fun onStarted() { engine = "started" }
            override fun onStopped() { engine = "stopped" }
            override fun onFirstFix(ttffMillis: Int) { engineTtffMs = ttffMillis.toLong() }
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                statusCallbacks.incrementAndGet()
                real = convert(status)
                realAt = SystemClock.elapsedRealtime()
            }
        }
        try {
            val m = manager
            if (Build.VERSION.SDK_INT >= 30) m.registerGnssStatusCallback(executor, cb)
            else @Suppress("DEPRECATION") m.registerGnssStatusCallback(cb, Handler(GpsLocationSource.looper))
            callback = cb
            engine = "registered"
            error = null
        } catch (e: SecurityException) {
            error = "location: ${e.message}"
            return
        } catch (e: RuntimeException) {
            error = "${e.javaClass.simpleName}: ${e.message}"
            return
        }
        if (fixes) startListener()
    }

    /** The view's own GPS listener (T333: apart from the status callback, for the holders that want fixes). */
    @SuppressLint("MissingPermission") // permitted() first
    @Synchronized
    private fun startListener() {
        val l = io.github.jasondorjeshort.dystopo.app.tracking.locationListener { loc ->
            fixes.incrementAndGet()
            lastFix = loc
        }
        try {
            val m = manager
            if (m.allProviders.contains(LocationManager.GPS_PROVIDER)) m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, GpsLocationSource.looper)
            listener = l
        } catch (e: SecurityException) {
            error = "location: ${e.message}"
        } catch (e: RuntimeException) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Synchronized
    private fun stop() {
        callback?.let { runCatching { manager.unregisterGnssStatusCallback(it) } }
        listener?.let { runCatching { manager.removeUpdates(it) } }
        callback = null
        listener = null
        engine = "off"
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-gnss").apply { isDaemon = true } }

    fun convert(s: GnssStatus): List<Satellite> = (0 until s.satelliteCount).map { i ->
        Satellite(
            constellation(s.getConstellationType(i)), s.getSvid(i), s.getCn0DbHz(i).toDouble(),
            s.getElevationDegrees(i).toDouble(), s.getAzimuthDegrees(i).toDouble(), s.usedInFix(i),
            s.hasEphemerisData(i), s.hasAlmanacData(i),
            if (s.hasCarrierFrequencyHz(i)) s.getCarrierFrequencyHz(i).toDouble() else null,
        )
    }

    private fun constellation(type: Int): Constellation = when (type) {
        GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
        GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
        GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
        GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
        GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
        GnssStatus.CONSTELLATION_IRNSS -> Constellation.IRNSS
        GnssStatus.CONSTELLATION_SBAS -> Constellation.SBAS
        else -> Constellation.UNKNOWN
    }

    // ---- what the view shows ----

    /** The satellites now: the injected case's, else the phone's. */
    val satellites: List<Satellite> get() = injected?.second ?: real

    /** `device` or `injected:<case>`. */
    val source: String get() = injected?.let { "injected:${it.first}" } ?: "device"

    /** Replaces the phone's status with [case]'s ([GnssCases]); false for a name it does not know. */
    fun inject(case: String): Boolean {
        val h = horizonAtPhone()?.profile
        val sats = GnssCases.of(case, h?.let { p -> { az: Double -> p.angleAt(az) } }) ?: return false
        injected = case.trim().lowercase() to sats
        return true
    }

    fun clear() {
        injected = null
    }

    /** The map's last fix, never its terrain estimate (T203), which drifts while the GPS is poor and is not a fix. */
    private fun mapFix(): Location? = app.map.lastLocation?.takeIf { it.provider != io.github.jasondorjeshort.dystopo.app.tracking.TerrainSource.SOURCE }
        ?: if (app.map.injectedLocation) injectedFix() else null

    /** While fixes are injected (debug), the last one ([io.github.jasondorjeshort.dystopo.app.LastFix]) once the estimate holds the puck. */
    private fun injectedFix(): Location? = io.github.jasondorjeshort.dystopo.app.LastFix.get()?.let { f ->
        Location("dystopo-debug").apply {
            latitude = f.lat
            longitude = f.lon
            f.accuracyM?.let { accuracy = it.toFloat() }
            time = f.at
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - (System.currentTimeMillis() - f.at) * 1_000_000
        }
    }

    /**
     * The position the view describes and the check holds still: while fixes are injected (debug) the injected one, else
     * the view's own GPS fix while the GNSS is held, else the map's (T308: the Horizon view with Satellites off follows
     * the map's fix). Never the terrain estimate; never written to a report. T333: a status-only hold (a recording's)
     * does not count, so recording changes no fix the sky views stand on.
     */
    fun position(): Location? = if (app.map.injectedLocation || !fixHeld) mapFix() else lastFix ?: mapFix()

    /** The fix the summary describes (accuracy, age, height, speed, course): as [position]. */
    fun fix(): Location? = position()

    // ---- the terrain's horizon ----

    /**
     * A horizon at a point, or why there is none ([reason]); [source] what answered in the terrain line's words (T340:
     * [TerrainText.from][io.github.jasondorjeshort.dystopo.app.terrain.TerrainText.from], "offline map", "2 offline maps and
     * the online source").
     */
    class HorizonState(
        val lat: Double, val lon: Double, val profile: HorizonProfile?, val reason: String?, val source: String?, val zoom: Int?, val ms: Long,
        /** T308: the terrain [profile] was swept over, kept for the Horizon view's peaks (`Viewshed.peaks` on the profile). */
        val terrain: io.github.jasondorjeshort.dystopo.dem.Terrain? = null,
        /** T340: the maps open and the network's generation when it was read; null for one not made by a read. */
        val epoch: TerrainRead.Epoch? = null,
        /** T340: what the read took its cells from. */
        val read: TerrainRead.Answered? = null,
    ) {
        /** T340: read again now: a map opened or closed since, or the network came back and this one lacks cells (a "none" included). */
        fun stale(now: TerrainRead.Epoch = TerrainRead.epoch()): Boolean = epoch?.stale(now, profile != null && read?.complete != false) == true
    }

    /**
     * T321 (T317's follow-up): the horizons kept, newest use first, at most [HORIZON_KEEP]: the fix's stays beside a chosen
     * point's, so "Back to here" and each hop between points do not sweep the fix again. When one more is swept, the
     * least recently used goes, never the one near the phone's position.
     */
    private val horizons = ArrayList<HorizonState>()
    @Volatile private var horizonPending = false
    private val horizonThread = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-gnss-horizon").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    val horizonSweeps = AtomicLong()

    /** T321: how many horizons are kept (the fix's and two chosen points': each holds the terrain it read). */
    const val HORIZON_KEEP = 3

    /** The horizon near the phone's position when one is kept, else the newest kept (the summary's height from the map). */
    val horizon: HorizonState?
        get() {
            val p = position()
            return synchronized(horizons) {
                p?.let { f -> horizons.firstOrNull { TrackStats.distanceM(it.lat, it.lon, f.latitude, f.longitude) < HORIZON_MOVE_M } } ?: horizons.firstOrNull()
            }
        }

    /** T321: how many horizons are kept now (`GET /gnss` `horizonsKept`). */
    val horizonsKept: Int get() = synchronized(horizons) { horizons.size }

    /** The horizon at the phone's position (starting its sweep when none is near), or null while it is worked out. */
    fun horizonAtPhone(): HorizonState? {
        val p = position() ?: return null
        return horizonAt(p.latitude, p.longitude)
    }

    fun horizonAt(lat: Double, lon: Double): HorizonState? {
        val now = TerrainRead.epoch()
        synchronized(horizons) {
            horizons.removeAll { it.stale(now) } // T340: no "none" or partial answer kept past a map opening or closing or the network returning
            val i = horizons.indexOfFirst { TrackStats.distanceM(it.lat, it.lon, lat, lon) < HORIZON_MOVE_M }
            if (i >= 0) {
                val h = horizons.removeAt(i)
                horizons.add(0, h)
                return h
            }
        }
        if (!horizonPending) {
            horizonPending = true
            horizonThread.execute {
                try {
                    keep(sweep(lat, lon))
                } finally {
                    horizonPending = false
                }
            }
        }
        return null
    }

    private fun keep(h: HorizonState) {
        val p = position()
        synchronized(horizons) {
            horizons.add(0, h)
            while (horizons.size > HORIZON_KEEP) {
                val near = { s: HorizonState -> p != null && TrackStats.distanceM(s.lat, s.lon, p.latitude, p.longitude) < HORIZON_MOVE_M }
                val drop = horizons.indexOfLast { !near(it) }.takeIf { it > 0 } ?: (horizons.size - 1)
                horizons.removeAt(drop)
            }
        }
    }

    /** Waits up to [ms] for the horizon at the phone's position (the debug routes). */
    fun awaitHorizon(ms: Long): HorizonState? {
        val until = SystemClock.elapsedRealtime() + ms
        while (true) {
            horizonAtPhone()?.let { return it }
            if (SystemClock.elapsedRealtime() > until || position() == null) return null
            Thread.sleep(100)
        }
    }

    /**
     * T340: the horizon at a point over the merged read ([TerrainRead]: the open maps cell by cell, the deepest first, then
     * the online source), at the zoom of the deepest map holding the point ([TerrainRead.pointZoom]). Before T340 it read one
     * pyramid (`ShadeService.pyramidAt`: the map `PackPrecedence` chose, else the online DEM), so a ring leaving that map
     * found no data, and a place outside every map read online here while the Panorama read the maps alone.
     */
    private fun sweep(lat: Double, lon: Double): HorizonState {
        val t0 = SystemClock.elapsedRealtime()
        horizonSweeps.incrementAndGet()
        val epoch = TerrainRead.epoch()
        fun none(why: String, read: TerrainRead.Answered? = null) = HorizonState(lat, lon, null, why, null, null, SystemClock.elapsedRealtime() - t0, epoch = epoch, read = read)
        return try {
            TerrainRead.open().use { r ->
                if (r.sources.isEmpty()) return none(GnssText.NO_TERRAIN)
                val zoom = r.pointZoom(lat, lon, ShadeTiles.POINT_ZOOM) ?: return none(GnssText.NO_TERRAIN_HERE)
                val terrain = Horizon.terrainAround(lat, lon, zoom) { w -> r.read(w) }
                val a = r.answered()
                val profile = try {
                    Horizon.sweep(terrain, lat, lon, observerHeightM = Horizon.EYE_HEIGHT_M)
                } catch (e: IllegalArgumentException) {
                    return none(GnssText.NO_TERRAIN_HERE, a)
                }
                HorizonState(lat, lon, profile, null, a.text, zoom, SystemClock.elapsedRealtime() - t0, terrain, epoch, a)
            }
        } catch (e: Exception) {
            none("${GnssText.NO_TERRAIN_ERROR} (${e.javaClass.simpleName})")
        }
    }

    // ---- reset assistance data ----

    /** The last reset's answer: true when the phone took it, false when it refused; null before any. */
    @Volatile var lastReset: Boolean? = null
        private set

    /** Asks the GPS to forget its assistance data and fetch it again (the phone may refuse). */
    fun resetAssistance(): Boolean {
        val ok = try {
            val m = manager
            val deleted = m.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null)
            runCatching { m.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null) }
            runCatching { m.sendExtraCommand(LocationManager.GPS_PROVIDER, if (Build.VERSION.SDK_INT >= 33) "force_psds_injection" else "force_xtra_injection", null) }
            deleted
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            false
        }
        lastReset = ok
        return ok
    }

    // ---- JSON ----

    fun horizonJson(h: HorizonState? = horizon): Map<String, Any?>? = h?.profile?.let { p ->
        val top = p.highest
        linkedMapOf(
            "source" to h.source, "zoom" to h.zoom, "ms" to h.ms, "stepDeg" to p.stepDeg, "eyeHeightM" to p.observerHeightM,
            "groundM" to Math.round(p.groundM), "highestDeg" to r1(p.angleDeg[top]), "highestBearingDeg" to r1(p.bearingDeg(top)),
            "highestDistanceM" to Math.round(p.distanceM[top]), "bearingsWithoutData" to p.bearingsWithoutData,
            "angleDeg" to p.angleDeg.map(::r1),
            "terrain" to h.read?.json(), // T340: the cells by source, the maps and whether the online source gave any
        )
    }

    /** `GET /gnss`: the status, the summary, the band line, the horizon and the check (no position). */
    fun json(): Map<String, Any?> {
        val sats = satellites
        val h = horizonAtPhone()
        val fn = h?.profile?.let { p -> { az: Double -> p.angleAt(az) } }
        val s = GnssHealth.summarize(sats)
        val f = fix()
        return linkedMapOf(
            "open" to GnssUi.open, "holders" to holders(), "statusOnly" to statusOnlyHolders(), "listening" to (listener != null), "engine" to engine, "error" to error, "permission" to permitted(), // T333: statusOnly, listening
            "source" to source, "statusCallbacks" to statusCallbacks.get(), "fixes" to fixes.get(),
            "statusAgeMs" to if (realAt == 0L) null else SystemClock.elapsedRealtime() - realAt,
            "summary" to linkedMapOf(
                "inView" to s.inView, "used" to s.used, "best4" to s.best4.map(::r1), "best4Mean" to s.best4Mean?.let(::r1),
                "constellations" to s.constellations.map { it.code }, "bands" to s.bands.map { it.label }, "bandLine" to s.bandLine,
                "accuracyM" to f?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()?.let(::r1),
                "fixAgeMs" to f?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 },
                "ttffMs" to ttffMs, "engineTtffMs" to engineTtffMs,
            ),
            "bandLine" to (s.bandLine ?: GnssText.BAND_UNKNOWN),
            "satellites" to GnssHealth.listOrder(sats).map { GnssHealth.json(it, fn) },
            "horizon" to horizonJson(h),
            "horizonReason" to if (h == null) (if (position() == null) GnssText.NO_POSITION else GnssText.HORIZON_WORKING) else h.reason,
            "horizonSweeps" to horizonSweeps.get(), "horizonsKept" to horizonsKept, // T321
            "orientation" to io.github.jasondorjeshort.dystopo.app.AppSettings.skyPlot.key,
            "headingDeg" to GnssUi.headingDeg()?.let(::r1),
            "drawn" to GnssUi.drawn,
            "check" to GnssCheck.json(),
            "lastReset" to lastReset,
        )
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
}
