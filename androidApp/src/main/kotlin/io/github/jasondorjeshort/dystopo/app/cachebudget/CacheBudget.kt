package io.github.jasondorjeshort.dystopo.app.cachebudget

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.ContourCache
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.LocalServer
import io.github.jasondorjeshort.dystopo.app.MapViewport
import io.github.jasondorjeshort.dystopo.app.nav.NavSession
import io.github.jasondorjeshort.dystopo.app.online.OnlineFetcher
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * T112: one cache budget over the phone's caches (ADR 0034, amending ADR 0025 principle 3), a layer over the per-kind caps rather than a new store. The total ([AppSettings.cacheBudgetMb], a
 * preset or a custom size on the Storage screen, `POST /storage?budgetMb=`) spans the kinds of [CacheKind]: tiles
 * downloaded into the online cache (its unpinned DEM, map and glyph entries), the contour, shade and cliff tiles the
 * phone made (the contour cache, and the online cache's contour and shade entries, [onlineDerived]) and the phone's own
 * source downloads. Pinned offline areas and packs stay outside with their own budget. Each cache keeps its own cap
 * (`onlineCacheMb`, `contourCacheMb`) as a ceiling; setting the total sets both to it.
 *
 * The caches report every write ([io.github.jasondorjeshort.dystopo.app.online.TileCache.onWrite]); when the kinds
 * together pass the total, a run on this object's thread evicts by [BudgetPlan.next] (derived data first, from the kind
 * furthest above its floor, oldest-used first, never below a floor, never the view or the route ahead: [CacheGuard])
 * until they fit with [BudgetPlan.slackBytes] to spare. A check every [TICK_S] seconds catches what grows without
 * reporting (the source downloads).
 */
object CacheBudget {
    const val TICK_S = 30L

    /** After a run that could not fit (everything left is in view, ahead or pinned), writes wait this long to try again. */
    const val STUCK_BACKOFF_MS = 30_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cache-budget").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val queued = AtomicBoolean()

    @Volatile var engine: BudgetEngine? = null
        private set
    @Volatile var lastRun: BudgetEngine.Run? = null
        private set
    @Volatile private var started = false
    val runs = AtomicLong()
    val pokes = AtomicLong()

    /** The view and the route ahead: the map's visible box and the navigated route's next 3 km. */
    val guard = CacheGuard(
        view = { runCatching { MapViewport.bounds }.getOrNull() },
        route = {
            NavSession.navigator?.let { nav ->
                CacheGuard.RouteAhead(nav, nav.state?.progress ?: 0.0) { nav.path.points.map { AreaPoint(it.lat, it.lon) } }
            }
        },
    )

    /** An online cache entry derived from fetched DEM tiles (`<source>/contours-.../...`, `<source>/shade-.../...`). */
    fun onlineDerived(key: String): Boolean {
        val route = key.substringAfter('/', "").substringBefore('/')
        return OnlineFetcher.DERIVED_FROM_DEM.any { route.startsWith(it) }
    }

    /** Hooks the caches up on this object's thread (the contour cache's index walks its directory) and checks once. */
    @Synchronized
    fun start(app: DystopoApp) {
        if (started) return
        started = true
        executor.execute {
            try {
                val online = OnlineService.get(app).cache
                val derived = ContourCache.get(app)
                val e = BudgetEngine(
                    listOf(
                        TileCacheStore(online, fetched = CacheKind.ONLINE, derived = CacheKind.DERIVED, derivedKey = ::onlineDerived),
                        TileCacheStore(derived, fetched = null, derived = CacheKind.DERIVED) { true },
                        DirStore(CacheKind.SOURCES, File(app.cacheDir, "sources"), keepNames = setOf("planet.json")),
                    ),
                    { AppSettings.cacheBudgetMb * BudgetPlan.MB },
                    guard,
                )
                engine = e
                online.onWrite = ::poke
                derived.onWrite = ::poke
                run(e)
            } catch (t: Throwable) {
                Log.e(LocalServer.TAG, "cache budget failed to start", t)
            }
        }
        // A task that throws would cancel the schedule, so nothing escapes.
        executor.scheduleWithFixedDelay({ runCatching { engine?.let { e -> if (e.refreshedOverBytes() > 0) run(e) } } }, TICK_S, TICK_S, TimeUnit.SECONDS)
    }

    /** A cache wrote a tile: schedule a run when the kinds are over the total (one at a time; backs off when stuck). */
    fun poke() {
        val e = engine ?: return
        pokes.incrementAndGet()
        val last = lastRun
        if (last != null && !last.fits && System.currentTimeMillis() - last.at < STUCK_BACKOFF_MS) return
        if (e.overBytes() <= 0) return
        if (queued.compareAndSet(false, true)) executor.execute {
            queued.set(false)
            runCatching { run(e) }
        }
    }

    private fun run(e: BudgetEngine): BudgetEngine.Run {
        val r = e.enforce()
        if (r.steps > 0 || lastRun == null) lastRun = r
        runs.incrementAndGet()
        return r
    }

    /**
     * Sets the total to [mb] (the caller checked [BudgetPlan.RANGE_MB]), evicts down to it at once on this object's
     * thread, then sets both caps to it (a lower total trims through the budget first, so the view's tiles stay).
     * Returns the run.
     */
    fun set(app: DystopoApp, mb: Long): BudgetEngine.Run? {
        AppSettings.setCacheBudgetMb(mb)
        start(app)
        val run = executor.submit<BudgetEngine.Run?> {
            val e = engine ?: return@submit null
            val r = run(e)
            lastRun = r
            AppSettings.setCapsFromBudget(mb)
            OnlineService.get(app).cache.resize(mb * BudgetPlan.MB)
            ContourCache.resize(mb)
            r
        }
        return run.get(60, TimeUnit.SECONDS)
    }

    /** `GET /storage`'s `budget`: the total, each kind against its floor and share, the last run, the guard. */
    fun json(): Map<String, Any?> {
        val total = AppSettings.cacheBudgetMb * BudgetPlan.MB
        val e = engine
        val used = e?.used()
        val sum = used?.values?.sum()
        return linkedMapOf(
            "totalMb" to AppSettings.cacheBudgetMb,
            "preset" to BudgetPlan.presetOf(AppSettings.cacheBudgetMb)?.key,
            "presets" to BudgetPlan.Preset.entries.map { mapOf("key" to it.key, "label" to it.label, "mb" to it.mb) },
            "rangeMb" to listOf(BudgetPlan.RANGE_MB.first, BudgetPlan.RANGE_MB.last),
            "freePercents" to BudgetPlan.FREE_PERCENTS,
            "usedBytes" to sum,
            "overBytes" to sum?.let { it - total },
            "slackBytes" to BudgetPlan.slackBytes(total),
            "kinds" to CacheKind.entries.map { k ->
                val b = used?.get(k)
                linkedMapOf(
                    "kind" to k.key, "label" to k.label, "bytes" to b,
                    "floorBytes" to BudgetPlan.floorBytes(k, total), "floorShare" to k.floorShare,
                    "share" to b?.let { if (total > 0) it.toDouble() / total else null },
                    "derived" to k.derived,
                )
            },
            "rule" to "derived first, then the kind furthest above its floor; least recently used first; never below a floor, in view or ahead on the route",
            "runs" to runs.get(), "pokes" to pokes.get(),
            "lastRun" to lastRun?.json(),
            "guard" to guard.json(),
            "loaded" to (e != null),
        )
    }
}
