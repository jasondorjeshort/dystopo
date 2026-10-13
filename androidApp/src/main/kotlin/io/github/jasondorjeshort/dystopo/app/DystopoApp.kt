package io.github.jasondorjeshort.dystopo.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import org.maplibre.android.MapLibre

/**
 * Process-wide state: the opened region pack, the tile service and the loopback server. The server
 * starts with the process so MapLibre can load the style the moment the map view exists.
 */
class DystopoApp : Application() {
    lateinit var tiles: TileService
        private set
    lateinit var server: LocalServer
        private set
    val map = MapController()

    /** T49: every loopback request must carry this per-launch token ([LoopbackToken]). */
    val token = LoopbackToken()
    val startedAt = SystemClock.elapsedRealtime()

    /** T57: `/stats` tile counters for the whole process, shared by every [TileService] it opens. */
    val tileCounters = TileCounters()

    override fun onCreate() {
        super.onCreate()
        instance = this
        io.github.jasondorjeshort.dystopo.app.nav.NavSession.prepare() // T86: its Compose state is made here, on the main thread, before any composition
        MapLibre.getInstance(this)
        MapLibre.setConnected(true) // T38: every tile comes from our loopback server; MapLibre's own offline check would stop even those
        AppSettings.load(this)
        map.onUnitsSeeded = { Thread({ reload() }, "units-reload").start() } // T33: a first fix seeded the units
        io.github.jasondorjeshort.dystopo.app.firstrun.BundledAssets.install(this) // T80: glyphs and sprites from the APK
        tiles = openTiles()
        io.github.jasondorjeshort.dystopo.app.packs.PackManager.get(this).downloads.resumePending() // T29: finish interrupted pack downloads
        val routes = Routes(this)
        // T57: in debug builds the debug routes live on a second loopback server on a random port, announced
        // only in logcat with the token; 8686 then answers like a release build (map routes only, no /health).
        // debugFixedPort=1 keeps everything on 8686 with the /health token bootstrap (pre-T57 behaviour).
        fixedDebugPort = BuildConfig.DEBUG_ENDPOINTS && AppSettings.debugFixedPort
        server = LocalServer(BuildConfig.SERVER_PORT, threads = 8, isMapRequest = { Routes.isMapRequest(it) }, handler = { routes.handle(it, debugRoutes = fixedDebugPort) }, genThreads = TileService.GEN_THREADS, genNice = TileService.GEN_NICE, tileNice = TileService.TILE_NICE, isGenerationRequest = { TileService.isGenerationRequest(it.path) }) // T33: debug routes get their own pool; T63: generation too
        try {
            server.start()
        } catch (e: Exception) {
            Log.e(LocalServer.TAG, "server failed to start on port ${BuildConfig.SERVER_PORT}", e)
        }
        if (BuildConfig.DEBUG_ENDPOINTS) startDebugServer(routes)
        io.github.jasondorjeshort.dystopo.app.update.Updater.start(this) // T83: daily update check (release builds), a verified download kept across restarts
    }

    /** T57: the debug server (null with [fixedDebugPort] or in release builds). */
    var debugServer: LocalServer? = null
        private set

    /** T57: debug routes share the tile server's port 8686 (setting `debugFixedPort`). */
    var fixedDebugPort = false
        private set

    /** Where the debug routes answer: the debug server's random port, or 8686 with [fixedDebugPort]. */
    val debugPort: Int? get() = if (!BuildConfig.DEBUG_ENDPOINTS) null else debugServer?.localPort ?: BuildConfig.SERVER_PORT.takeIf { fixedDebugPort }

    private fun startDebugServer(routes: Routes) {
        if (!fixedDebugPort) {
            val s = LocalServer(0, threads = 2, debugThreads = 3, isMapRequest = { Routes.isMapRequest(it) }, handler = { routes.handle(it, debugRoutes = true) })
            try {
                s.start()
                debugServer = s
            } catch (e: Exception) {
                Log.e(LocalServer.TAG, "debug server failed to start", e)
                return
            }
        }
        // The one place the port and token are announced: logcat is readable by this app and adb, not by other apps.
        Log.i(DEBUG_TAG, "debug server on port $debugPort token ${token.value}")
    }

    /** Pack roots: internal files dir first (adb push + run-as), then the app-specific external dir. */
    fun packRoots() = listOfNotNull(filesDir, getExternalFilesDir(null))

    private fun openTiles(skip: Set<String> = emptySet()): TileService {
        val packs = PackSet.open(packRoots(), skip) // T41: every usable pack
        // T33: the first pack opened seeds the units preference (once; see AppSettings.seedUnits).
        packs.dem?.header?.let { h -> AppSettings.seedUnits((h.minLat + h.maxLat) / 2, (h.minLon + h.maxLon) / 2, "pack:${packs.name ?: "default"}") }
        collectContourGarbage()
        tileCounters.serviceOpens.incrementAndGet()
        return TileService(packs, cacheDir, BuildConfig.SERVER_PORT, token, tileCounters) // T57: cumulative counters
    }

    /** T41: contour (and T49 cliff) caches of every pack file on the device stay (switches, restarts); only orphans go (background). */
    fun collectContourGarbage() {
        val identities = PackSet.catalog(packRoots()).flatMap { it.files }.map(::packIdentity).toSet()
        ContourCache.collectGarbage(this, identities)
    }

    /** Reopens the packs (after pushing new ones) and reloads the map style. [skip]: packs to leave closed (being deleted). */
    @Synchronized
    fun reload(skip: Set<String> = emptySet()) {
        val old = tiles
        tiles = openTiles(skip)
        old.close() // T35: stop the old service's contour warm-up before its packs close
        old.packs.close()
        map.onMain { map.loadStyle(tiles.style()) }
    }

    companion object {
        /** T57: logcat tag of the `debug server on port N token T` line the tools read. */
        const val DEBUG_TAG = "Dystopo"

        lateinit var instance: DystopoApp
            private set
    }
}
