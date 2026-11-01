package io.github.jasondorjeshort.dystopo.app.routing

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Pack
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.app.TileService
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.DemElevation
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.ExposureAlong
import io.github.jasondorjeshort.dystopo.routing.ExposureParams
import io.github.jasondorjeshort.dystopo.routing.ExposureResult
import io.github.jasondorjeshort.dystopo.routing.ExposureText
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * T195: a planned route's terrain on the phone, over `shared`'s [ExposureAlong]: computed from the DEM pack that covers
 * the route (the first in precedence order whose box holds the route's, else the primary pack's) at z14, when a route is
 * planned (the route card, `GET /route`, the printed sheet's margin); never stored (no format change). One worker
 * thread; the last [CACHE] results are kept by the route's points, its mode and the pack set, so the card, the sheet
 * and the debug route share one computation. Not for drive routes (the ground beside a road says little). The words
 * are [ExposureText]'s (pure, `ExposureAlongTest`). No setting (the survey: one practice, ADR 0044).
 */
object RouteExposure {
    /** One route's terrain, the DEM pack read, the time taken, or the error. */
    class Result(val exposure: ExposureResult?, val ms: Long, val pack: String?, val error: String? = null)

    private const val CACHE = 6
    private const val TAG = "DystopoExposure"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-exposure").apply { isDaemon = true } }
    private val cache = object : LinkedHashMap<Triple<List<RoutePoint>, TravelMode, Int>, Future<Result>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<List<RoutePoint>, TravelMode, Int>, Future<Result>>?) = size > CACHE
    }

    /** One sampler per opened DEM pack (its own small tile cache), read under a lock on the worker. */
    private val samplers = java.util.WeakHashMap<Pack, ElevationSampler>()

    @Volatile var computed = 0
        private set

    private fun packs(): PackSet? = runCatching { DystopoApp.instance.tiles.packs }.getOrNull()

    /** Whether [mode] gets the line: hike, bike and crow; not drive. */
    fun applies(mode: TravelMode): Boolean = mode != TravelMode.DRIVE

    /** The computation for [points] in [mode] (started now when new); null for drive, fewer than two points or no packs. */
    fun start(points: List<RoutePoint>, mode: TravelMode): Future<Result>? {
        if (!applies(mode) || points.size < 2) return null
        val p = packs() ?: return null
        val key = Triple(points, mode, System.identityHashCode(p))
        synchronized(cache) {
            cache[key]?.let { return it }
            val f = worker.submit(Callable { compute(points, mode, p) })
            cache[key] = f
            return f
        }
    }

    /** The result when it is ready now, else null (and the computation runs). */
    fun cached(points: List<RoutePoint>, mode: TravelMode): Result? = start(points, mode)?.takeIf { it.isDone }?.let { runCatching { it.get() }.getOrNull() }

    /** Waits up to [ms] for the result (off the main thread); null for drive or on a timeout. */
    fun await(points: List<RoutePoint>, mode: TravelMode, ms: Long = 10_000): Result? =
        runCatching { start(points, mode)?.get(ms, TimeUnit.MILLISECONDS) }.getOrNull()

    private fun demFor(points: List<RoutePoint>, packs: PackSet): Pack? {
        val lat0 = points.minOf { it.lat }; val lat1 = points.maxOf { it.lat }
        val lon0 = points.minOf { it.lon }; val lon1 = points.maxOf { it.lon }
        return packs.demPacks.firstOrNull { d -> d.header.let { h -> h.minLon <= lon0 && h.maxLon >= lon1 && h.minLat <= lat0 && h.maxLat >= lat1 } }
            ?: packs.demPacks.firstOrNull { d -> d.header.let { h -> h.maxLon >= lon0 && h.minLon <= lon1 && h.maxLat >= lat0 && h.minLat <= lat1 } }
    }

    private fun compute(points: List<RoutePoint>, mode: TravelMode, packs: PackSet): Result {
        val t0 = SystemClock.elapsedRealtime()
        val dem = demFor(points, packs)
        if (dem == null) return Result(null, SystemClock.elapsedRealtime() - t0, null).also { computed++ }
        return try {
            val params = ExposureParams()
            val sampler = synchronized(samplers) {
                samplers.getOrPut(dem) {
                    val pyramid = DemPyramid(dem.header.minZoom, dem.header.maxZoom, TileService.demDecoder(), cacheTiles = 24) { z, x, y -> dem.tile(z, x, y) }
                    val s = DemElevation(pyramid, zoom = params.zoom)
                    val lock = Any()
                    ElevationSampler { lat, lon -> synchronized(lock) { s.elevation(lat, lon) } }
                }
            }
            val r = ExposureAlong.find(points, sampler, CostModel(mode), params)
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "${r.samples.size} samples along ${r.lengthM.toInt()} m in $ms ms: over 30 ${r.over30.distanceM.toInt()} m, ridges ${r.ridge.distanceM.toInt()} m")
            Result(r, ms, dem.name)
        } catch (e: Exception) {
            Log.w(TAG, "terrain along the route failed", e)
            Result(null, SystemClock.elapsedRealtime() - t0, dem.name, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            computed++
        }
    }

    /** The card's line for [points] when computed now (the printed sheet's margin), else null. */
    fun cardLine(points: List<RoutePoint>, mode: TravelMode, units: ContourUnits, waitMs: Long = 0): String? {
        val r = if (waitMs > 0) await(points, mode, waitMs) else cached(points, mode)
        return r?.exposure?.let { ExposureText.cardLine(it, units) }
    }

    /** `GET /route` `exposure` and `GET /route/exposure`: the card's and the sheet's words, the figures, the stretches. */
    fun json(points: List<RoutePoint>?, mode: TravelMode?, open: Boolean, waitMs: Long = 10_000): Map<String, Any?> {
        if (points == null || points.size < 2 || mode == null) return linkedMapOf("state" to "noRoute")
        if (!applies(mode)) return linkedMapOf("state" to "mode", "mode" to mode.label)
        val r = await(points, mode, waitMs) ?: return linkedMapOf("state" to "computing")
        val e = r.exposure
        val state = when {
            r.error != null -> "error"
            e == null -> "noDem"
            !e.hasData -> "noData"
            else -> "ready"
        }
        val units = AppSettings.units
        return linkedMapOf<String, Any?>("state" to state, "pack" to r.pack, "ms" to r.ms, "error" to r.error, "open" to open) +
            (e?.json(units) ?: emptyMap()) + linkedMapOf("computed" to computed)
    }
}
