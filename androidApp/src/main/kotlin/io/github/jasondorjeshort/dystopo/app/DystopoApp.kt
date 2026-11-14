package io.github.jasondorjeshort.dystopo.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.launch.AppLaunch
import io.github.jasondorjeshort.dystopo.launch.Launch
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

    /** T228: the start trace begins here, with a thread of its own; what ran before this is loading and field initialisers. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        StartupTrace.attach(base)
        // T266: the launch's list and runner are made on a thread of their own while the system installs the content providers,
        // so onCreate finds them made (their classes' loading cost the main thread about 12 ms on the emulator).
        Thread({ AppLaunch.runner }, "launch-make").apply { isDaemon = true }.start()
        growFdTable() // T280
    }

    // T228: StartupTrace.mark names each step of the launch; a step lasts until the next mark (marks only, nothing moved).
    // T266 (ADR 0061 step 1): each step is the body of a declared stage (`shared` launch.Launch), bound here and run by
    // AppLaunch.runner on the thread it declares; the lines and their order are the ones before, nothing moved.
    override fun onCreate() {
        StartupTrace.mark("DystopoApp.onCreate")
        super.onCreate()
        instance = this
        AppLaunch.install(this, SystemClock.elapsedRealtimeNanos())
        StartupRoutes.stallPoint("appOnCreate")
        val launch = AppLaunch.runner
        launch.run(Launch.PREPARE) {
            StartupTrace.mark("the Compose state's prepare")
            if (BuildConfig.DEBUG) io.github.jasondorjeshort.dystopo.app.debug.StrictModeCounts.install() // T150: counted by app frame, no penalty; release builds untouched
            io.github.jasondorjeshort.dystopo.app.nav.NavSession.prepare() // T86: its Compose state is made here, on the main thread, before any composition
            io.github.jasondorjeshort.dystopo.app.areas.Areas.prepare() // T87: the same for the offline areas' state (AheadPrefetch's thread reads Areas)
            io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.prepare() // T110: the same for the scale bar's option (sync reads it on its threads)
            StartupTrace.mark("Tracking.prepare")
            io.github.jasondorjeshort.dystopo.app.tracking.Tracking.prepare() // T226: the same for the tracks' and trips' state (threads reach Tracking at launch)
        }
        launch.run(Launch.MAPLIBRE) {
            StartupTrace.mark("MapLibre.getInstance")
            MapLibre.getInstance(this)
            MapLibre.setConnected(true) // T38: every tile comes from our loopback server; MapLibre's own offline check would stop even those
        }
        launch.run(Launch.SETTINGS) {
            StartupTrace.mark("AppSettings.load")
            AppSettings.load(this)
            map.onUnitsSeeded = { Thread({ reload() }, "units-reload").start() } // T33: a first fix seeded the units
        }
        StartupTrace.mark("Tracking.start")
        launch.start(Launch.TRACKING) { io.github.jasondorjeshort.dystopo.app.tracking.Tracking.start(this) } // T229: built on a worker, so neither the first frame nor the corridors' thread waits on a build
        launch.start(Launch.WORLD_COPY) { io.github.jasondorjeshort.dystopo.app.firstrun.WorldOverview.copy(this) } // T264: the world overview's copy, on a worker
        launch.run(Launch.ASSETS) {
            StartupTrace.mark("BundledAssets.install")
            io.github.jasondorjeshort.dystopo.app.firstrun.BundledAssets.install(this) // T80: glyphs and sprites from the APK
        }
        val packs = launch.run(Launch.PACKS) { openPacks() }
        val online = launch.run(Launch.ONLINE_SERVICE) {
            StartupTrace.mark("OnlineService.get") // T228, a mark of its own: it runs on the main thread at launch
            io.github.jasondorjeshort.dystopo.app.online.OnlineService.create(this)
        }
        // T266: the cache's index (T267's move, then T242's walk, on the cache's own thread) is a slot of its own, never waited for here.
        launch.begin(Launch.CACHE_INDEX, "the cache's index thread")
        online.cache.whenReady("launch-cacheIndex") { launch.end(Launch.CACHE_INDEX, online.cache) }
        published = launch.run(Launch.TILES_SERVICE) { tileService(packs) }
        launch.run(Launch.WATCHERS) {
            StartupTrace.mark("style-prime, watchdog, reports and cache budget start")
            Thread({ runCatching { published.style() } }, "style-prime").apply { isDaemon = true }.start() // T144: reads each pack's metadata once, off the UI thread, so the map's first style() there takes no pack lock
            MainWatchdog.start(this) // T144: the UI-thread watchdog (every build)
            io.github.jasondorjeshort.dystopo.app.reports.Reports.start(this) // T145: the crash handler, and the exit records read on a worker
            io.github.jasondorjeshort.dystopo.app.cachebudget.CacheBudget.start(this) // T112: one budget over the caches (its own thread)
            StartupTrace.mark("MemorySampler.start")
            io.github.jasondorjeshort.dystopo.app.reports.MemorySampler.start(this) // T258: schedules a sample a minute on its own thread (the diagnostic dump's memory history)
        }
        launch.run(Launch.DOWNLOADS_STAGE) {
            StartupTrace.mark("PackManager.get")
            val manager = io.github.jasondorjeshort.dystopo.app.packs.PackManager.create(this)
            StartupTrace.mark("resumePending")
            manager.downloads.resumePending() // T29: finish interrupted pack downloads
            manager
        }
        launch.run(Launch.SERVER) {
            StartupTrace.mark("Routes and the loopback servers")
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
        }
        launch.run(Launch.UPDATER) {
            StartupTrace.mark("Updater.start")
            io.github.jasondorjeshort.dystopo.app.update.Updater.start(this) // T83: daily update check (release builds), a verified download kept across restarts
        }
        StartupTrace.appCreated()
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

    private fun openTiles(skip: Set<String> = emptySet()): TileService = tileService(openPacks(skip))

    /** The `packs` stage's body (T266), and a reload's first half. */
    private fun openPacks(skip: Set<String> = emptySet()): PackSet {
        StartupTrace.mark("PackSet.open")
        val packs = PackSet.open(packRoots(), skip) // T41: every usable pack
        // T33: the first pack opened seeds the units preference (once; see AppSettings.seedUnits).
        packs.dem?.header?.let { h -> AppSettings.seedUnits((h.minLat + h.maxLat) / 2, (h.minLon + h.maxLon) / 2, "pack:${packs.name ?: "default"}") }
        collectContourGarbage()
        return packs
    }

    /** The `tiles` stage's body (T266), and a reload's second half. */
    private fun tileService(packs: PackSet): TileService {
        StartupTrace.mark("TileService")
        tileCounters.serviceOpens.incrementAndGet()
        return TileService(packs, cacheDir, BuildConfig.SERVER_PORT, token, tileCounters) // T57: cumulative counters
    }

    /** T41: contour (and T49 cliff) caches of every pack file on the device stay (switches, restarts); only orphans go (background). */
    fun collectContourGarbage() {
        StartupTrace.mark("collectContourGarbage's identities")
        val identities = PackSet.catalog(packRoots()).flatMap { it.files }.map(::packIdentity).toSet()
        StartupTrace.mark("ContourCache.collectGarbage")
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

        /**
         * T280: the slots the process's table of open files gets at once ([growFdTable]). The kernel starts a process with
         * 64 and doubles the table each time a file is opened past its size (the app holds about 210 after a launch on the
         * emulator, so 128, then 256); each growth waits for an RCU grace period (`synchronize_rcu` in `expand_fdtable`), and
         * every thread that opens a file meanwhile waits with it. At launch the growth past 64 fell inside the `world` and
         * `assets` stages: their marker reads blocked in `open()` (`expand_files`) for 30 to 60 ms on the sweep's emulator,
         * where a grace period took 62 ms, while the start trace's sampler waited out the growth.
         */
        const val FD_TABLE_SLOTS = 512

        /** T280: the growth's time in ms (null until it ends, -1 when it failed), for the start trace's header. */
        @Volatile var fdTableMs: Double? = null
            private set

        /**
         * T280: grows the table of open files to [FD_TABLE_SLOTS] once, on a thread of its own at the start of the process
         * (a duplicate of fd 0 at slot [FD_TABLE_SLOTS] - 1, closed at once; the table never shrinks), so the launch pays one
         * grace period here, before `onCreate`, instead of two inside its stages. Android 11 and later (`Os.fcntlInt` is
         * public from API 30); older phones grow the table as before.
         */
        private fun growFdTable() {
            if (android.os.Build.VERSION.SDK_INT >= 30) growFdTable30()
        }

        @androidx.annotation.RequiresApi(30)
        private fun growFdTable30() = Thread({
            val t0 = System.nanoTime()
            fdTableMs = runCatching {
                val fd = android.system.Os.fcntlInt(java.io.FileDescriptor.`in`, android.system.OsConstants.F_DUPFD, FD_TABLE_SLOTS - 1)
                android.os.ParcelFileDescriptor.adoptFd(fd).close()
                Math.round((System.nanoTime() - t0) / 1e5) / 10.0
            }.onFailure { Log.w(DEBUG_TAG, "the table of open files not grown", it) }.getOrDefault(-1.0)
        }, "fd-table").apply { isDaemon = true }.start()

        lateinit var instance: DystopoApp
            private set
    }
}
