package io.github.jasondorjeshort.dystopo.app.routing

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.routing.WaterAlong
import io.github.jasondorjeshort.dystopo.routing.WaterClass
import io.github.jasondorjeshort.dystopo.routing.WaterGaps
import io.github.jasondorjeshort.dystopo.routing.WaterItem
import io.github.jasondorjeshort.dystopo.routing.WaterKind
import io.github.jasondorjeshort.dystopo.routing.WaterStats
import io.github.jasondorjeshort.dystopo.routing.WaterTiles
import io.github.jasondorjeshort.dystopo.routing.isSource
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * T187: water along a planned, kept or navigated route on the phone, over T179's [WaterAlong]: computed from the vector
 * packs on the device when a route is planned ([RouteOverlay.show], the route card, `GET /route`), a trip opens
 * ([io.github.jasondorjeshort.dystopo.app.trips.TripOverlay.show]) or navigation starts
 * ([io.github.jasondorjeshort.dystopo.app.nav.NavSession]); never stored in the synced `path` or `directions` (no format
 * change). One worker thread; the last [CACHE] results are kept by the route's points and the pack set, so the card, the
 * markers and the debug route share one computation.
 *
 * Tiles: each tile under the route from the best vector archive that has it ([PackSet.osmCandidates], as the map reads
 * them), at the deepest zoom of the archives near the route capped at 14; the `drainage` layer comes from the same
 * archive (present in a `devtool drainage-pack` copy only). With the `waterAlongRoute` setting off nothing is computed.
 * The words are [WaterText]'s (pure, `RouteWaterTest`).
 */
object RouteWater {
    /** One route's water: the items in route order, the route's length (haversine, as `alongM`), the counters. */
    class Result(
        val items: List<WaterItem>,
        val lengthM: Double,
        val stats: WaterStats?,
        val ms: Long,
        val packs: List<String>,
        val zoom: Int?,
        val error: String? = null,
    ) {
        val longest: Pair<Double, Double> get() = WaterGaps.longest(items, lengthM)
    }

    private const val CACHE = 6
    private const val TAG = "DystopoWater"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-water").apply { isDaemon = true } }
    private val cache = object : LinkedHashMap<Pair<List<RoutePoint>, Int>, Future<Result>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<List<RoutePoint>, Int>, Future<Result>>?) = size > CACHE
    }

    @Volatile var computed = 0
        private set

    private fun packs(): PackSet? = runCatching { DystopoApp.instance.tiles.packs }.getOrNull()

    /** The computation for [points] (started now when new); null with the setting off, fewer than two points or no packs. */
    fun start(points: List<RoutePoint>): Future<Result>? {
        if (!AppSettings.waterAlongRoute || points.size < 2) return null
        val p = packs() ?: return null
        val key = points to System.identityHashCode(p)
        synchronized(cache) {
            cache[key]?.let { return it }
            val f = worker.submit(Callable { compute(points, p) })
            cache[key] = f
            return f
        }
    }

    /** The result for [points] when it is ready now, else null (and the computation runs). */
    fun cached(points: List<RoutePoint>): Result? = start(points)?.takeIf { it.isDone }?.let { runCatching { it.get() }.getOrNull() }

    /** Waits up to [ms] for [points]' result (off the main thread); null with the setting off or on a timeout. */
    fun await(points: List<RoutePoint>, ms: Long = 10_000): Result? =
        runCatching { start(points)?.get(ms, TimeUnit.MILLISECONDS) }.getOrNull()

    /** Calls [then] on the water worker with [points]' result once it is ready (not with the setting off). */
    fun async(points: List<RoutePoint>, then: (Result) -> Unit) {
        val f = start(points) ?: return
        worker.execute { runCatching { f.get() }.getOrNull()?.let(then) }
    }

    /** The setting changed: the route and trip markers redraw from it (or leave the map). */
    fun onSettings() {
        RouteSession.overlay.refreshWater()
        io.github.jasondorjeshort.dystopo.app.trips.Trips.overlay.refreshWater()
    }

    private fun compute(points: List<RoutePoint>, packs: PackSet): Result {
        val t0 = SystemClock.elapsedRealtime()
        val lengthM = (1 until points.size).sumOf { TrackStats.distanceM(points[it - 1].lat, points[it - 1].lon, points[it].lat, points[it].lon) }
        val lat0 = points.minOf { it.lat }; val lat1 = points.maxOf { it.lat }
        val lon0 = points.minOf { it.lon }; val lon1 = points.maxOf { it.lon }
        val near = packs.osmPacks.filter { a -> a.header.let { h -> h.maxLon >= lon0 && h.minLon <= lon1 && h.maxLat >= lat0 && h.minLat <= lat1 } }
        if (near.isEmpty()) return Result(emptyList(), lengthM, null, SystemClock.elapsedRealtime() - t0, emptyList(), null).also { computed++ }
        val zoom = minOf(14, near.maxOf { it.header.maxZoom })
        val tiles = WaterTiles { z, x, y -> packs.osmCandidates(z, x, y).firstNotNullOfOrNull { it.tile(z, x, y) } }
        return try {
            val r = WaterAlong.find(points, tiles, zoom)
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "${r.items.size} items along ${lengthM.toInt()} m in $ms ms (z$zoom, ${r.stats.tiles} tiles, drainage ${r.stats.drainageLayer})")
            Result(r.items, lengthM, r.stats, ms, near.mapNotNull { packs.packOf(it) }, zoom)
        } catch (e: Exception) {
            Log.w(TAG, "water along the route failed", e)
            Result(emptyList(), lengthM, null, SystemClock.elapsedRealtime() - t0, near.mapNotNull { packs.packOf(it) }, zoom, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            computed++
        }
    }

    /** `GET /route` `water` and `GET /route/water`: the card's words, the list with each row's gap, the counters. */
    fun json(points: List<RoutePoint>?, open: Boolean, waitMs: Long = 10_000): Map<String, Any?> {
        if (!AppSettings.waterAlongRoute) return linkedMapOf("enabled" to false)
        if (points == null || points.size < 2) return linkedMapOf("enabled" to true, "state" to "noRoute")
        val r = await(points, waitMs) ?: return linkedMapOf("enabled" to true, "state" to "computing")
        return linkedMapOf("enabled" to true, "state" to if (r.error != null) "error" else "ready") + resultJson(r, AppSettings.units) + mapOf("open" to open)
    }

    fun resultJson(r: Result, units: ContourUnits): Map<String, Any?> {
        val (gapFrom, gapTo) = r.longest
        return linkedMapOf(
            "card" to WaterText.cardLine(r.items, units), "longest" to WaterText.longestLine(r.items, r.lengthM, units),
            "hint" to WaterText.HINT,
            "lengthM" to r1(r.lengthM), "count" to r.items.size, "sources" to r.items.count { it.isSource },
            "longestGapM" to r1(gapTo - gapFrom), "longestGap" to linkedMapOf("fromM" to r1(gapFrom), "toM" to r1(gapTo)),
            "zoom" to r.zoom, "packs" to r.packs, "ms" to r.ms, "error" to r.error, "stats" to r.stats?.json(),
            "items" to r.items.mapIndexed { i, it ->
                linkedMapOf<String, Any?>("n" to i + 1) + it.json() +
                    linkedMapOf("source" to it.isSource, "gapToNextM" to r1(WaterText.gapToNext(r.items, i, r.lengthM)), "row" to WaterText.row(r.items, i, r.lengthM, units))
            },
        )
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
}

/**
 * T187: the words of water along a route (pure; `RouteWaterTest`; ADR 0031, the owner adjusts them). The class labels are
 * T179's ([WaterItem.label]); nothing here says "reliable", "safe" or "drinking water".
 */
object WaterText {
    /** Under the list: where the words come from. */
    const val HINT = "From the map and the terrain, not reports: check locally, conditions change."

    /** The route card's line, which opens the list: "Water: 3 crossings, next likely flowing in 1.2 km". */
    fun cardLine(items: List<WaterItem>, units: ContourUnits): String {
        if (items.isEmpty()) return "Water: none found along the route"
        val n = items.size
        val noun = if (items.all { it.kind == WaterKind.STREAM }) (if (n == 1) "crossing" else "crossings") else (if (n == 1) "place" else "places")
        val next = items.firstOrNull { it.isSource } ?: return "Water: $n $noun, none likely flowing"
        val at = if (next.alongM < PASS_M) "at the start" else "in ${UnitFormat.distance(next.alongM, units)}"
        return "Water: $n $noun, next ${sourceWords(next)} $at"
    }

    /** The route card's second line: "Longest stretch without water: 6.2 km" (null with no source at all, the card says so). */
    fun longestLine(items: List<WaterItem>, lengthM: Double, units: ContourUnits): String? {
        if (items.none { it.isSource }) return null
        val (a, b) = WaterGaps.longest(items, lengthM)
        return "Longest stretch without water: ${UnitFormat.distance(b - a, units)}"
    }

    /** "likely flowing", "a mapped spring": a source in the card's sentence. */
    fun sourceWords(i: WaterItem): String = when (i.kind) {
        WaterKind.STREAM -> if (i.waterClass == WaterClass.LIKELY_FLOWING) "likely flowing" else i.label.lowercase()
        WaterKind.SPRING -> "a mapped spring"
        WaterKind.TAP -> "a mapped water tap"
        WaterKind.LAKE -> "a lake or pond"
    }

    /** From item [index] to the next item listed, or to the route's end. */
    fun gapToNext(items: List<WaterItem>, index: Int, lengthM: Double): Double =
        ((items.getOrNull(index + 1)?.alongM ?: lengthM) - items[index].alongM).coerceAtLeast(0.0)

    /**
     * One row of the list: "3. 2.4 km  Stream, likely flowing (mapped) · Paradise River · bridge · next 0.8 km", the
     * last row "· end 1.1 km"; a spring, tap or lake off the route says how far ("· 60 m off the route").
     */
    fun row(items: List<WaterItem>, index: Int, lengthM: Double, units: ContourUnits): String {
        val it = items[index]
        val sb = StringBuilder()
        sb.append(index + 1).append(". ").append(UnitFormat.distance(it.alongM, units)).append("  ").append(it.label).append(" (").append(it.basis.label).append(")")
        it.name?.let { n -> sb.append(" · ").append(n) }
        if (it.bridge) sb.append(" · bridge")
        if (it.offsetM >= 10) sb.append(" · ").append(UnitFormat.distance(it.offsetM, units)).append(" off the route")
        val gap = UnitFormat.distance(gapToNext(items, index, lengthM), units)
        sb.append(if (index == items.size - 1) " · end $gap" else " · next $gap")
        return sb.toString()
    }

    /** Navigating: an item this far behind still counts as next (the walker is at it), metres. */
    const val PASS_M = 30.0

    /** Navigating: the index of the next item the card names at [progressM] (not a gully, usually dry), -1 when none is left. */
    fun nextOnCard(items: List<WaterItem>, progressM: Double): Int =
        items.indexOfFirst { it.alongM >= progressM - PASS_M && !(it.kind == WaterKind.STREAM && it.waterClass == WaterClass.USUALLY_DRY) }

    /** The navigation card's line: "Water in 1.2 km: Stream, likely flowing". */
    fun navLine(item: WaterItem, aheadM: Double, units: ContourUnits): String =
        if (aheadM < PASS_M) "Water here: ${item.label}" else "Water in ${UnitFormat.distance(aheadM, units)}: ${item.label}"

    /** How far ahead the spoken line is said, by travel mode, metres. */
    fun leadM(mode: TravelMode): Double = when (mode) {
        TravelMode.BIKE -> 1_000.0
        TravelMode.DRIVE -> 2_000.0
        else -> 400.0
    }

    /**
     * Navigating: the item to say now, or -1: the next source with a stretch of more than [WaterGaps.LONG_M] after it,
     * within [leadM] ahead of [progressM] (or up to [PASS_M] behind) and not said before ([said]); only the next such
     * source is ever considered, so a passed one is never said late.
     */
    fun dueSpoken(items: List<WaterItem>, lengthM: Double, progressM: Double, leadM: Double, said: Set<Int>): Int {
        val k = items.indices.firstOrNull { i -> items[i].isSource && items[i].alongM >= progressM - PASS_M && WaterGaps.after(items, i, lengthM) > WaterGaps.LONG_M } ?: return -1
        if (k in said || items[k].alongM - progressM > leadM) return -1
        return k
    }

    /**
     * The spoken line: "In 400 m, water: stream, likely flowing. Then none for 6.2 km." (or "Then none to the end,
     * 6.2 km."); at the water itself "Water here: ...".
     */
    fun spoken(item: WaterItem, aheadM: Double, gapM: Double, toEnd: Boolean, units: ContourUnits): String {
        val what = item.label.replaceFirstChar { it.lowercase() }
        val head = if (aheadM < PASS_M) "Water here: $what." else "In ${UnitFormat.distance(aheadM, units)}, water: $what."
        val gap = UnitFormat.distance(gapM, units)
        return "$head " + if (toEnd) "Then none to the end, $gap." else "Then none for $gap."
    }
}
