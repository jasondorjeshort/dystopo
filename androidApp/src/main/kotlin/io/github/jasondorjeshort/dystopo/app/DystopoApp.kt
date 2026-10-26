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
    /**
     * T144: the published tile service, swapped by [reload]; read without a lock from any thread (the UI thread included).
     * Inside a loopback request it is the service that request holds ([leaseTiles]), so one request reads one service.
     */
    val tiles: TileService get() = leases.held() ?: published

    @Volatile private lateinit var published: TileService
    private val leases = Leases({ published }, TileService::acquire, TileService::release)
    private val reloadLock = Any()
    private val main by lazy { android.os.Handler(mainLooper) }

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
        io.github.jasondorjeshort.dystopo.app.areas.Areas.prepare() // T87: the same for the offline areas' state (AheadPrefetch's thread reads Areas)
        io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.prepare() // T110: the same for the scale bar's option (sync reads it on its threads)
        MapLibre.getInstance(this)
        MapLibre.setConnected(true) // T38: every tile comes from our loopback server; MapLibre's own offline check would stop even those
        AppSettings.load(this)
        map.onUnitsSeeded = { Thread({ reload() }, "units-reload").start() } // T33: a first fix seeded the units
        io.github.jasondorjeshort.dystopo.app.firstrun.BundledAssets.install(this) // T80: glyphs and sprites from the APK
        published = openTiles()
        Thread({ runCatching { published.style() } }, "style-prime").apply { isDaemon = true }.start() // T144: reads each pack's metadata once, off the UI thread, so the map's first style() there takes no pack lock
        MainWatchdog.start(this) // T144: the UI-thread watchdog (every build)
        io.github.jasondorjeshort.dystopo.app.cachebudget.CacheBudget.start(this) // T112: one budget over the caches (its own thread)
        io.github.jasondorjeshort.dystopo.app.packs.PackManager.get(this).downloads.resumePending() // T29: finish interrupted pack downloads
        val routes = Routes(this)
        // T57: in debug builds the debug routes live on a second loopback server on a random port, announced
        // only in logcat with the token; 8686 then answers like a release build (map routes only, no /health).
        // debugFixedPort=1 keeps everything on 8686 with the /health token bootstrap (pre-T57 behaviour).
        fixedDebugPort = BuildConfig.DEBUG_ENDPOINTS && AppSettings.debugFixedPort
        server = LocalServer(BuildConfig.SERVER_PORT, threads = 8, isMapRequest = { Routes.isMapRequest(it) }, handler = { routes.handle(it, debugRoutes = fixedDebugPort) }, lease = ::leaseTiles, genThreads = TileService.GEN_THREADS, genNice = TileService.GEN_NICE, tileNice = TileService.TILE_NICE, isGenerationRequest = { TileService.isGenerationRequest(it.path) }, waitThreads = TileService.WAIT_THREADS, isWaitingRequest = { TileService.isWaitingRequest(it.path) }) // T33: debug routes get their own pool; T63: generation too; T88: waits on the sources too
        try {
            server.start()
        } catch (e: Exception) {
            Log.e(LocalServer.TAG, "server failed to start on port ${BuildConfig.SERVER_PORT}", e)
        }
        if (BuildConfig.DEBUG_ENDPOINTS) startDebugServer(routes)
        io.github.jasondorjeshort.dystopo.app.update.Updater.start(this) // T83: daily update check (release builds), a verified download kept across restarts
    }

    /**
     * T127: the process-wide memory callbacks. The activity is stopped while the app is in the background, so its own
     * callback is not reached then; here every in-memory cache registered with [io.github.jasondorjeshort.dystopo.app.online.HeapGuard]
     * (the decoded DEM tiles of the packs', the online and the direct pyramids, the cliff memo) is dropped from
     * `TRIM_MEMORY_RUNNING_LOW` up, `TRIM_MEMORY_UI_HIDDEN` (the app left the screen) included.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        io.github.jasondorjeshort.dystopo.app.online.HeapGuard.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        io.github.jasondorjeshort.dystopo.app.online.HeapGuard.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
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
            val s = LocalServer(0, threads = 2, debugThreads = 3, isMapRequest = { Routes.isMapRequest(it) }, handler = { routes.handle(it, debugRoutes = true) }, lease = ::leaseTiles)
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

    /**
     * Reopens the packs (after pushing new ones) and reloads the map style. [skip]: packs to leave closed (being deleted).
     *
     * T144: never on the UI thread and never waiting for it. The new service is opened off it (called on the UI thread,
     * the work moves to a thread of its own), published through a volatile field, and the old one disposed: its packs close
     * when the last request reading them releases them ([TileService.dispose]). The style swap is posted to the UI thread,
     * not awaited. The lock only orders reloads; nothing that runs on the UI thread takes it.
     */
    fun reload(skip: Set<String> = emptySet()) {
        if (android.os.Looper.myLooper() == mainLooper) {
            Thread({ reload(skip) }, "tiles-reload").start()
            return
        }
        val next = synchronized(reloadLock) {
            val next = openTiles(skip)
            val old = published
            published = next
            // A request that reloads (POST /reload, /packs/activate) reads the new service from here on.
            leases.swap(next)
            old.dispose()
            next
        }
        showStyle(next)
    }

    /** T144: the style of [service] is made here (off the UI thread) and set on the map in a post, if it is still the published one. */
    fun showStyle(service: TileService = published) {
        val json = service.style()
        main.post { if (published === service) map.loadStyle(json) }
    }

    /**
     * T144: holds the published tile service for one loopback request ([LocalServer] calls it around the handler); the
     * returned handle releases it. Retries when a reload closed the one it read (only possible after a newer one was
     * published). Nested calls on one thread share the outer hold.
     */
    fun leaseTiles(): AutoCloseable = leases.lease()

    /** T144: `GET /health` `tileServices`: `open` (services not yet closed: 1 at rest), opened, closed, deferred closes, the published one's references. */
    fun tileServicesJson(): Map<String, Any?> {
        val opened = tileCounters.serviceOpens.get()
        val closed = tileCounters.serviceCloses.get()
        return linkedMapOf("open" to opened - closed, "opened" to opened, "closed" to closed, "deferredCloses" to tileCounters.deferredCloses.get(), "references" to published.references)
    }

    companion object {
        /** T57: logcat tag of the `debug server on port N token T` line the tools read. */
        const val DEBUG_TAG = "Dystopo"

        lateinit var instance: DystopoApp
            private set
    }
}
