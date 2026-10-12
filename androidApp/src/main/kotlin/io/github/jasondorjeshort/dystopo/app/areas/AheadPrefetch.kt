package io.github.jasondorjeshort.dystopo.app.areas

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.nav.NavSession
import io.github.jasondorjeshort.dystopo.app.online.Fetch
import io.github.jasondorjeshort.dystopo.app.online.OnlineFetcher
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.Polyline
import io.github.jasondorjeshort.dystopo.areas.TileCover
import io.github.jasondorjeshort.dystopo.nav.Navigator
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * T75: "ahead on the route". While navigating and online, the line ahead of the walker is cut into segments of
 * [SEGMENT_M] metres and every segment that starts within [AHEAD_M] of the current progress is fetched into the
 * tile cache: the DEM tiles at z[MIN_ZOOM] to z15 and the vector tiles at z[MIN_ZOOM] to z14 within [BUFFER_M] of
 * the line (one tile of margin), the zooms a walker looks at. Each segment is pinned while navigation runs (a
 * transient pin in [Areas]) and released when navigation stops. When the walker reaches a segment, the check
 * `cachedAtArrival` says whether all of its tiles were on the device by then (a pack or the cache): the point of
 * the feature, measured.
 *
 * Polled every [POLL_MS] on its own thread; nothing is fetched while the fetcher is offline, or with no server and
 * no direct sources. `GET /areas/prefetch` is [json]; `POST /areas/prefetch?on=0|1` turns it off and on.
 */
object AheadPrefetch {
    const val SEGMENT_M = 1000.0
    const val AHEAD_M = 3000.0
    const val BUFFER_M = 300.0
    const val MIN_ZOOM = 12
    const val POLL_MS = 2000L

    private val app: DystopoApp get() = DystopoApp.instance

    @Volatile var enabled = true

    private class Tile(val route: String, val ext: String, val z: Int, val x: Int, val y: Int) {
        val path get() = "$z/$x/$y.$ext"
    }

    private class Segment(val index: Int, val fromM: Double, val toM: Double, val tiles: List<Tile>, val area: AreaOfInterest) {
        @Volatile var fetchedAt = 0L
        @Volatile var progressAtFetchM = 0.0
        @Volatile var fetched = 0
        @Volatile var alreadyCached = 0
        @Volatile var inPacks = 0
        @Volatile var failed = 0
        @Volatile var bytes = 0L
        @Volatile var ms = 0L
        @Volatile var arrivedAt = 0L
        @Volatile var cachedAtArrival: Boolean? = null
        @Volatile var missingAtArrival = 0

        fun json(): Map<String, Any?> = linkedMapOf(
            "index" to index, "fromM" to fromM.toInt(), "toM" to toM.toInt(), "tiles" to tiles.size,
            "fetched" to fetched, "alreadyCached" to alreadyCached, "inPacks" to inPacks, "failed" to failed, "bytes" to bytes, "ms" to ms,
            "fetchedAt" to fetchedAt.takeIf { it > 0 }, "progressAtFetchM" to progressAtFetchM.toInt(),
            "aheadByM" to (fromM - progressAtFetchM).toInt().coerceAtLeast(0),
            "arrivedAt" to arrivedAt.takeIf { it > 0 }, "secondsBeforeArrival" to if (arrivedAt > 0 && fetchedAt > 0) (arrivedAt - fetchedAt) / 1000.0 else null,
            "cachedAtArrival" to cachedAtArrival, "missingAtArrival" to missingAtArrival,
        )
    }

    @Volatile private var navigator: Navigator? = null
    private val segments = java.util.concurrent.ConcurrentSkipListMap<Int, Segment>()
    @Volatile private var points: List<AreaPoint> = emptyList()
    @Volatile private var cum: DoubleArray = DoubleArray(0)
    @Volatile private var progressM = 0.0
    @Volatile private var routeName: String? = null
    @Volatile private var lastSkip: String? = null
    @Volatile private var runs = 0
    @Volatile private var started = false
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "area-ahead").apply { isDaemon = true } }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        Thread({
            while (true) {
                try {
                    Thread.sleep(POLL_MS)
                    tick()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    Log.w(Areas.TAG, "prefetch ahead failed", e)
                    lastSkip = e.message ?: e.javaClass.simpleName
                }
            }
        }, "area-ahead-poll").apply { isDaemon = true }.start()
    }

    private fun tick() {
        val nav = NavSession.navigator
        if (nav == null) {
            if (navigator != null) {
                navigator = null
                Areas.unpinTransient("ahead:")
            }
            return
        }
        if (nav !== navigator) {
            // A new navigation: forget the last one's segments.
            navigator = nav
            segments.clear()
            Areas.unpinTransient("ahead:")
            points = nav.path.points.map { AreaPoint(it.lat, it.lon) }
            cum = Polyline.cumulative(points)
            routeName = nav.path.name
        }
        val progress = nav.state?.progress ?: 0.0
        progressM = progress
        val service = OnlineService.get(app)
        val fetcher = service.fetcher
        // Arrivals: was everything there when the walker got to the segment?
        for (s in segments.values) {
            if (s.arrivedAt == 0L && progress >= s.fromM) {
                s.arrivedAt = System.currentTimeMillis()
                val missing = s.tiles.count { t -> !present(t, fetcher) }
                s.missingAtArrival = missing
                s.cachedAtArrival = missing == 0
            }
        }
        // Segments well behind the walker are released.
        for (s in segments.values) if (s.toM < progress - SEGMENT_M) Areas.unpinTransient("ahead:${s.index}")
        if (!enabled) { lastSkip = "turned off"; return }
        if (fetcher == null) { lastSkip = "no online source"; return }
        if (fetcher.state == "offline") { lastSkip = "offline: ${fetcher.lastError}"; return }
        val total = cum.lastOrNull() ?: return
        val first = floor(progress / SEGMENT_M).toInt()
        val last = floor(minOf(total - 0.01, progress + AHEAD_M) / SEGMENT_M).toInt()
        for (k in first..last) {
            if (segments.containsKey(k) || navigator !== nav) continue
            fetchSegment(k, total, progress, fetcher)
        }
        lastSkip = null
        runs++
    }

    private fun present(t: Tile, fetcher: OnlineFetcher?): Boolean {
        val packs = runCatching { app.tiles.packs }.getOrNull()
        val inPack = if (t.route == AreaManifest.ROUTE_DEM) packs?.demFor(t.z, t.x, t.y) != null else packs?.osmFor(t.z, t.x, t.y) != null
        return inPack || (fetcher != null && Areas.cached(t.route, t.path)) // T74: versioned keys, every source
    }

    private fun fetchSegment(k: Int, total: Double, progress: Double, fetcher: OnlineFetcher) {
        val from = k * SEGMENT_M
        val to = minOf(total, from + SEGMENT_M)
        val line = Polyline.slice(points, from, to, cum)
        val area = AreaOfInterest(AreaShape.Corridor(line, BUFFER_M), MIN_ZOOM, 15)
        val tiles = ArrayList<Tile>()
        for (z in MIN_ZOOM..15) for ((x, y) in TileCover.of(area, z, margin = 1).tiles()) {
            tiles += Tile(AreaManifest.ROUTE_DEM, "png", z, x, y)
            if (z <= AreaManifest.OSM_MAX_ZOOM) tiles += Tile(AreaManifest.ROUTE_OSM, "pbf", z, x, y)
        }
        val s = Segment(k, from, to, tiles, area)
        segments[k] = s
        Areas.pinTransient("ahead:$k", AreaPins.cover(area))
        val started = System.currentTimeMillis()
        s.progressAtFetchM = progress
        val packs = runCatching { app.tiles.packs }.getOrNull()
        val futures = tiles.map { t ->
            pool.submit(Callable {
                val inPack = if (t.route == AreaManifest.ROUTE_DEM) packs?.demFor(t.z, t.x, t.y) != null else packs?.osmFor(t.z, t.x, t.y) != null
                if (inPack) return@Callable "pack" to 0
                val transform: (ByteArray) -> ByteArray = if (t.route == AreaManifest.ROUTE_OSM) OnlineFetcher::gunzipIfNeeded else { b -> b }
                when (val f = fetcher.get(t.route, t.path, transform)) {
                    is Fetch.Hit -> (if (f.cached) "cached" else "fetched") to f.bytes.size
                    Fetch.Missing -> "missing" to 0
                    is Fetch.Unavailable -> "failed" to 0
                }
            })
        }
        for (f in futures) {
            val (what, n) = runCatching { f.get() }.getOrDefault("failed" to 0)
            when (what) {
                "pack" -> s.inPacks++
                "cached" -> s.alreadyCached++
                "fetched" -> { s.fetched++; s.bytes += n }
                "failed" -> s.failed++
            }
        }
        s.ms = System.currentTimeMillis() - started
        s.fetchedAt = System.currentTimeMillis()
        // A segment that could not be fetched whole is tried again on a later poll.
        if (s.failed > 0) segments.remove(k)
        Log.i(Areas.TAG, "ahead segment $k (${from.toInt()}-${to.toInt()} m, walker at ${progress.toInt()} m): ${tiles.size} tiles, ${s.fetched} fetched, ${s.alreadyCached} cached, ${s.inPacks} in packs, ${s.failed} failed, ${s.ms} ms")
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled, "navigating" to (navigator != null), "route" to routeName, "progressM" to progressM.toInt(),
        "lengthM" to cum.lastOrNull()?.toInt(), "segmentM" to SEGMENT_M, "aheadM" to AHEAD_M, "bufferM" to BUFFER_M, "zooms" to listOf(MIN_ZOOM, 15),
        "polls" to runs, "lastSkip" to lastSkip,
        "segments" to segments.values.map { it.json() },
        "arrived" to segments.values.count { it.arrivedAt > 0 }, "cachedAtArrival" to segments.values.count { it.cachedAtArrival == true },
    )
}
