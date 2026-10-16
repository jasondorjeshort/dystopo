package io.github.jasondorjeshort.dystopo.app.cachebudget

import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.Polyline
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * T112: what the cache budget never evicts: the tiles of the current view ([view], grown by [VIEW_MARGIN] of its size
 * on every side, so a small pan stays warm) and those within [BUFFER_M] of the next [AHEAD_M] of a navigated route
 * ([route]; the prefetch ahead pins its own DEM and map tiles, this also keeps the contour and shade tiles made there).
 * A cache key names its tile in its last three segments (`.../<z>/<x>/<y>.<ext>`, also a halo variant
 * `<y>.halo-....pbf`); a key without one (a glyph range) is never spared by this. Pure apart from the two providers, so
 * it is unit-tested.
 */
class CacheGuard(
    private val view: () -> PackBounds?,
    private val route: () -> RouteAhead? = { null },
) {
    /** A navigated route: [id] changes with the route, [progressM] along it, its [points] read only when the cover changes. */
    class RouteAhead(val id: Any, val progressM: Double, val points: () -> List<AreaPoint>)

    /** Keys spared since start (in view or ahead), for `GET /storage`. */
    val spared = AtomicLong()

    @Volatile private var aheadMemo: Triple<Any, Long, AreaPins>? = null

    /** The cover of the route ahead, recomputed when the route changes or the walker moves on by [AHEAD_STEP_M]. */
    fun ahead(): AreaPins? {
        val r = route() ?: return null
        val bucket = (r.progressM / AHEAD_STEP_M).toLong()
        aheadMemo?.let { (id, b, pins) -> if (id === r.id && b == bucket) return pins }
        val points = r.points()
        if (points.size < 2) return null
        val cum = Polyline.cumulative(points)
        val from = bucket * AHEAD_STEP_M // from the bucket's start, so the walker's own spot stays covered until the next one
        val to = minOf(cum.last(), r.progressM + AHEAD_M)
        if (to <= from) return null
        val line = Polyline.slice(points, from, to, cum)
        if (line.size < 2) return null
        val pins = AreaPins(listOf(AreaPins.cover(AreaOfInterest(AreaShape.Corridor(line, BUFFER_M), 0, AHEAD_MAX_ZOOM))))
        aheadMemo = Triple(r.id, bucket, pins)
        return pins
    }

    /** The view box grown by [VIEW_MARGIN] (null: no view yet). */
    fun viewBox(): PackBounds? {
        val v = view() ?: return null
        val dx = (v.maxLon - v.minLon) * VIEW_MARGIN
        val dy = (v.maxLat - v.minLat) * VIEW_MARGIN
        return PackBounds(v.minLon - dx, (v.minLat - dy).coerceAtLeast(-85.06), v.maxLon + dx, (v.maxLat + dy).coerceAtMost(85.06))
    }

    /** A predicate for one eviction pass: the view and the route ahead read once, then checked per key. */
    fun predicate(): (String) -> Boolean {
        val box = viewBox()
        val ahead = ahead()
        if (box == null && ahead == null) return { false }
        return { key ->
            val t = tileOf(key)
            val keep = t != null && ((box != null && overlaps(t, box)) || (ahead != null && ahead.contains(t.first, t.second, t.third)))
            if (keep) spared.incrementAndGet()
            keep
        }
    }

    fun json(): Map<String, Any?> {
        val box = view()
        return linkedMapOf(
            "view" to box?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
            "viewMargin" to VIEW_MARGIN,
            "routeAhead" to (route() != null),
            "aheadM" to AHEAD_M, "bufferM" to BUFFER_M,
            "spared" to spared.get(),
        )
    }

    companion object {
        const val VIEW_MARGIN = 0.5
        const val AHEAD_M = 3000.0
        const val BUFFER_M = 300.0
        const val AHEAD_STEP_M = 100.0
        const val AHEAD_MAX_ZOOM = 15

        /** The tile z/x/y a cache key ends in (`.../<z>/<x>/<y>.<ext>`, halo variants included), or null. */
        fun tileOf(key: String): Triple<Int, Int, Int>? {
            val parts = key.split('/')
            if (parts.size < 4) return null
            val z = parts[parts.size - 3].toIntOrNull() ?: return null
            val x = parts[parts.size - 2].toIntOrNull() ?: return null
            val y = parts[parts.size - 1].substringBefore('.').toIntOrNull() ?: return null
            if (z !in 0..30 || x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) return null
            return Triple(z, x, y)
        }

        /** Tile z/x/y's box in degrees. */
        fun tileBox(z: Int, x: Int, y: Int): PackBounds {
            val n = (1 shl z).toDouble()
            fun lat(yy: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * yy / n))))
            return PackBounds(x / n * 360 - 180, lat(y + 1), (x + 1) / n * 360 - 180, lat(y))
        }

        fun overlaps(t: Triple<Int, Int, Int>, box: PackBounds): Boolean {
            val b = tileBox(t.first, t.second, t.third)
            return b.maxLon > box.minLon && b.minLon < box.maxLon && b.maxLat > box.minLat && b.minLat < box.maxLat
        }
    }
}
