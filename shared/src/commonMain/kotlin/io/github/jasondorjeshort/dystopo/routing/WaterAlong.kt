package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.MvtDecoder
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.search.LayerFilter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** T179: the bytes of tile ([z], [x], [y]) of a vector pack, or null where the pack has none (a `PmtilesReader::tile`). */
fun interface WaterTiles {
    fun tile(z: Int, x: Int, y: Int): ByteArray?
}

/** T179: what a [WaterItem] is. [key] is the JSON value. */
enum class WaterKind(val key: String) {
    /** The route crosses a mapped `waterway` line or a `drainage` line derived from the terrain. */
    STREAM("stream"),

    /** A mapped spring (`outdoor_poi` class `spring`) near the route. */
    SPRING("spring"),

    /** A mapped tap or fountain (`outdoor_poi` class `drinking_water`) near the route; no claim about the water. */
    TAP("tap"),

    /** A mapped lake or pond (`water` class `lake` or `pond`) near the route. */
    LAKE("lake"),
}

/**
 * T179: how likely a crossed stream is to carry water, the survey's three words (2026-10-30; no app derives this from
 * terrain, so it is a hint, never a report, and never "reliable" or "safe"). [label] is the user-visible text.
 */
enum class WaterClass(val key: String, val label: String) {
    LIKELY_FLOWING("likely_flowing", "Stream, likely flowing"),
    MAY_RUN_DRY("may_run_dry", "Stream, may run dry"),
    USUALLY_DRY("usually_dry", "Gully, usually dry"),
}

/** T179: where a [WaterItem]'s class (or, for points and lakes, the item itself) comes from. [label] is user-visible. */
enum class WaterBasis(val key: String, val label: String) {
    MAPPED("mapped", "mapped"),
    TERRAIN("terrain", "from terrain"),
}

/**
 * T179: one place along a route where there may be water, from [WaterAlong.find].
 *
 * - [alongM]: distance along the route (haversine, as `Directions` measures) to the crossing, or to the point of the
 *   route nearest a spring, tap or lake.
 * - [waterClass]: streams only (null for the other kinds); [basis] says whether it was read from the map's tags or
 *   from the terrain's catchment.
 * - [name]: the mapped name (a merged crossing takes the mapped line's), or null.
 * - [catchmentKm2]: the terrain catchment of the derived line crossed (the `drainage` layer's `catchment_km2`, taken
 *   at the line's downstream end, so an upper bound for the crossing itself and a lower bound where the catchment
 *   leaves the pack); null when no derived line was crossed there.
 * - [bridge]: the route passes over the water here (a mapped bridge on a way within [WaterParams.bridgeM], or the
 *   waterway in a culvert): water below, maybe out of reach.
 * - [merged]: a mapped line and a derived one crossed within [WaterParams.mergeM] along the route, shown as one.
 * - [intermittent]: the mapped line or lake is tagged intermittent (seasonal).
 * - [mappedClass]: the `waterway` class (river, stream, canal, ditch, drain) or the `water` class, when mapped.
 * - [offsetM]: 0 for a crossing; how far off the route a spring, tap or lake is.
 * - [lat], [lon]: the crossing, or the spring, tap or lake point nearest the route.
 */
data class WaterItem(
    val alongM: Double,
    val kind: WaterKind,
    val waterClass: WaterClass?,
    val basis: WaterBasis,
    val name: String?,
    val catchmentKm2: Double?,
    val bridge: Boolean,
    val merged: Boolean,
    val intermittent: Boolean,
    val mappedClass: String?,
    val offsetM: Double,
    val lat: Double,
    val lon: Double,
) {
    /** The user-visible kind or class ("Stream, likely flowing", "Mapped spring", ...). */
    val label: String
        get() = when (kind) {
            WaterKind.STREAM -> (waterClass ?: WaterClass.USUALLY_DRY).label
            WaterKind.SPRING -> "Mapped spring"
            WaterKind.TAP -> "Mapped water tap"
            WaterKind.LAKE -> "Lake or pond"
        }

    fun json(): Map<String, Any?> = linkedMapOf(
        "alongM" to r1(alongM), "kind" to kind.key, "class" to waterClass?.key, "label" to label, "basis" to basis.key,
        "basisLabel" to basis.label, "name" to name, "catchmentKm2" to catchmentKm2?.let { kotlin.math.round(it * 100) / 100 },
        "bridge" to bridge, "merged" to merged, "intermittent" to intermittent, "mappedClass" to mappedClass,
        "offsetM" to r1(offsetM), "lat" to r6(lat), "lon" to r6(lon),
    )

    private fun r1(v: Double) = kotlin.math.round(v * 10) / 10
    private fun r6(v: Double) = kotlin.math.round(v * 1e6) / 1e6
}

/**
 * T179: the numbers of [WaterAlong.find]. The class thresholds are the survey's starting values (no published standard
 * gives one: the literature supports only the ordering), kept after the truth table against USGS NHD flow codes on
 * Rainier and Glacier/Waterton (finding `docs/findings/2026-10-30-water-class-from-terrain-against-nhd.md`).
 */
data class WaterParams(
    /** A derived line with a smaller catchment is "Gully, usually dry", km². */
    val dryBelowKm2: Double = 0.5,
    /**
     * A derived line with at least this catchment is "Stream, likely flowing", km². The survey started at 5; the truth
     * table kept the largest recall with at least 0.8 of "likely flowing" points coded perennial in all three samples
     * (1.5 km²) and rounded up for the layer's downstream-end catchment, which reads high at a crossing.
     */
    val flowingFromKm2: Double = 2.0,
    /** A derived crossing with a smaller catchment (and no mapped line beside it) is left out: under channel size, km². */
    val dropBelowKm2: Double = 0.1,
    /** A derived crossing this close along the route to a mapped one is the same water, metres. */
    val mergeM: Double = 30.0,
    /** Springs, taps and lakes this close to the route are listed, metres. */
    val nearM: Double = 100.0,
    /** A mapped bridge this close to a crossing carries the route over it, metres. */
    val bridgeM: Double = 15.0,
    /** Crossings of the same water (or of derived lines) this close along the route are one (a stream beside a trail), metres. */
    val sameCrossingM: Double = 30.0,
    /** Pieces of one lake (tile cuts) this close along the route are one, metres. */
    val sameLakeM: Double = 200.0,
) {
    init {
        require(dropBelowKm2 <= dryBelowKm2 && dryBelowKm2 <= flowingFromKm2) { "class thresholds out of order" }
        require(mergeM >= 0 && nearM >= 0 && bridgeM >= 0 && sameCrossingM >= 0 && sameLakeM >= 0) { "distances must not be negative" }
    }
}

/** T179: counters of one [WaterAlong.find]. */
data class WaterStats(
    val zoom: Int,
    /** Tiles under the route (plus [WaterParams.nearM]) read, and those that failed to decode. */
    val tiles: Int,
    val tileErrors: Int,
    /** True when any tile read had a `drainage` layer (a pack `devtool drainage-pack` wrote). */
    val drainageLayer: Boolean,
    /** Crossings of `waterway` and of `drainage` lines, after the same-water grouping. */
    val waterwayCrossings: Int,
    val drainageCrossings: Int,
    /** Derived crossings folded into a mapped one, and derived crossings left out under [WaterParams.dropBelowKm2]. */
    val mergedDrainage: Int,
    val droppedDrainage: Int,
    val springs: Int,
    val taps: Int,
    val lakes: Int,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "zoom" to zoom, "tiles" to tiles, "tileErrors" to tileErrors, "drainageLayer" to drainageLayer,
        "waterwayCrossings" to waterwayCrossings, "drainageCrossings" to drainageCrossings, "mergedDrainage" to mergedDrainage,
        "droppedDrainage" to droppedDrainage, "springs" to springs, "taps" to taps, "lakes" to lakes,
    )
}

class WaterAlongResult(val items: List<WaterItem>, val stats: WaterStats)

/**
 * T179: water along a route, computed on demand from the pack (nothing stored, no format change; the phone half is
 * T187's). [find] takes
 * - a route line (`RoutePath.points`, a GPX track's points),
 * - a tile source ([WaterTiles], the pack's tiles at [find]'s `zoom`, its last zoom capped at 14 as
 *   [RoutingGraphBuilder] reads them: every line there is whole and unmerged),
 * - a DEM class source: the tiles holding the `drainage` layer (by default the same pack; the layer is present only
 *   in packs `devtool drainage-pack` wrote, `tilebuild/PROFILE.md`),
 *
 * and returns the items in route order: each crossing of a `waterway` or `drainage` line, and the springs, taps and
 * lakes within [WaterParams.nearM]. Only the tiles under the line are read, cut to the layers used ([LayerFilter])
 * before [MvtDecoder] runs.
 *
 * The class of a crossing:
 * - a mapped line alone: `river` or `canal`, or `stream` without `intermittent`, is "likely flowing"; `intermittent=1`,
 *   `ditch` and `drain` "may run dry" ([WaterBasis.MAPPED]);
 * - a derived line alone: its catchment, at least [WaterParams.flowingFromKm2] "likely flowing", from
 *   [WaterParams.dryBelowKm2] "may run dry", below that "Gully, usually dry", and under [WaterParams.dropBelowKm2]
 *   left out ([WaterBasis.TERRAIN]);
 * - both within [WaterParams.mergeM] along the route: one item with the mapped name and the derived catchment (the
 *   largest crossed there), classed by the catchment, `intermittent=1` capping it at "may run dry" (then
 *   [WaterBasis.MAPPED]); a mapped line without the tag does not raise the terrain's class.
 *
 * Words describe the evidence ("mapped", "from terrain"); nothing here says the water is drinkable or that it runs.
 */
object WaterAlong {
    /** The vector layers read. */
    private val LAYERS = setOf("waterway", "drainage", "outdoor_poi", "water", "water_name", "transportation")
    private val DRAINAGE_ONLY = setOf("drainage")
    private const val UNITS = 4096
    private const val EARTH_M = 6378137.0

    /** The class of a derived line with catchment [km2], or null when it is under [WaterParams.dropBelowKm2]. */
    fun classOf(km2: Double, params: WaterParams = WaterParams()): WaterClass? = when {
        km2 >= params.flowingFromKm2 -> WaterClass.LIKELY_FLOWING
        km2 >= params.dryBelowKm2 -> WaterClass.MAY_RUN_DRY
        km2 >= params.dropBelowKm2 -> WaterClass.USUALLY_DRY
        else -> null
    }

    /** The class a mapped `waterway` gives on its own (no terrain beside it). */
    fun mappedClassOf(waterwayClass: String?, intermittent: Boolean): WaterClass = when {
        waterwayClass == "ditch" || waterwayClass == "drain" -> WaterClass.MAY_RUN_DRY
        intermittent -> WaterClass.MAY_RUN_DRY
        else -> WaterClass.LIKELY_FLOWING
    }

    /**
     * The water along [route] from the pack [tiles] at [zoom] (its max zoom, capped at 14); [drainage] holds the
     * `drainage` layer (the same pack by default, null to read none).
     */
    fun find(
        route: List<RoutePoint>,
        tiles: WaterTiles,
        zoom: Int,
        params: WaterParams = WaterParams(),
        drainage: WaterTiles? = tiles,
    ): WaterAlongResult {
        val line = RouteLine.of(route, zoom)
        if (line == null) return WaterAlongResult(emptyList(), WaterStats(zoom, 0, 0, false, 0, 0, 0, 0, 0, 0, 0))
        val scan = Scan(line, params)
        var errors = 0
        var hasDrainage = false
        val keys = line.tilesNear(params.nearM)
        for ((key, segs) in keys) {
            val tx = (key shr 32).toInt()
            val ty = key.toInt()
            try {
                val bytes = tiles.tile(zoom, tx, ty)
                if (bytes != null) {
                    val decoded = MvtDecoder.decode(LayerFilter.filter(bytes, if (drainage === tiles) LAYERS else LAYERS - DRAINAGE_ONLY))
                    if (decoded.layer("drainage") != null) hasDrainage = true
                    scan.tile(tx, ty, segs, decoded.layers.associateBy { it.name })
                }
                if (drainage != null && drainage !== tiles) {
                    val other = drainage.tile(zoom, tx, ty)
                    if (other != null) {
                        val decoded = MvtDecoder.decode(LayerFilter.filter(other, DRAINAGE_ONLY))
                        if (decoded.layer("drainage") != null) hasDrainage = true
                        scan.tile(tx, ty, segs, decoded.layers.associateBy { it.name })
                    }
                }
            } catch (e: IllegalArgumentException) {
                errors++ // one bad tile must not sink the list
            }
        }
        return scan.finish(keys.size, errors, hasDrainage, zoom)
    }

    /** The route in world units of the zoom (4096 per tile) with the distance along it at each vertex. */
    internal class RouteLine(val xs: DoubleArray, val ys: DoubleArray, val lats: DoubleArray, val lons: DoubleArray, val along: DoubleArray, val zoom: Int) {
        val n: Int get() = xs.size
        private val world = UNITS.toDouble() * (1L shl zoom)

        /** Metres per world unit at [lat]. */
        fun metresPerUnit(lat: Double): Double = 2 * PI * EARTH_M * cos(lat * PI / 180) / world

        /** Along-route distance at parameter [u] of segment [i]. */
        fun alongAt(i: Int, u: Double): Double = along[i] + u * (along[i + 1] - along[i])

        fun latAt(y: Double): Double = WebMercator.yToLat(y / UNITS, zoom)
        fun lonAt(x: Double): Double = WebMercator.xToLon(x / UNITS, zoom)

        /** Tiles within [padM] of a segment, each with the segments that come that close to it, in a stable order. */
        fun tilesNear(padM: Double): LinkedHashMap<Long, IntArray> {
            val lists = LinkedHashMap<Long, ArrayList<Int>>()
            for (i in 0 until n - 1) {
                val pad = padM / metresPerUnit(max(abs(lats[i]), abs(lats[i + 1]))) + 1.0
                val x0 = floor((min(xs[i], xs[i + 1]) - pad) / UNITS).toInt()
                val x1 = floor((max(xs[i], xs[i + 1]) + pad) / UNITS).toInt()
                val y0 = floor((min(ys[i], ys[i + 1]) - pad) / UNITS).toInt()
                val y1 = floor((max(ys[i], ys[i + 1]) + pad) / UNITS).toInt()
                val maxTile = (1 shl zoom) - 1
                for (ty in max(0, y0)..min(maxTile, y1)) for (tx in max(0, x0)..min(maxTile, x1)) {
                    lists.getOrPut((tx.toLong() shl 32) or ty.toLong()) { ArrayList() }.add(i)
                }
            }
            val out = LinkedHashMap<Long, IntArray>(lists.size * 2)
            for ((k, v) in lists) out[k] = v.toIntArray()
            return out
        }

        companion object {
            fun of(route: List<RoutePoint>, zoom: Int): RouteLine? {
                val pts = ArrayList<RoutePoint>(route.size)
                for (p in route) if (pts.isEmpty() || pts.last().lat != p.lat || pts.last().lon != p.lon) pts += p
                if (pts.size < 2) return null
                val n = pts.size
                val along = DoubleArray(n)
                for (i in 1 until n) along[i] = along[i - 1] + TrackStats.distanceM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
                return RouteLine(
                    DoubleArray(n) { WebMercator.lonToX(pts[it].lon, zoom) * UNITS },
                    DoubleArray(n) { WebMercator.latToY(pts[it].lat, zoom) * UNITS },
                    DoubleArray(n) { pts[it].lat }, DoubleArray(n) { pts[it].lon }, along, zoom,
                )
            }
        }
    }

    /** A crossing before grouping. */
    private class Hit(
        val alongM: Double, val x: Double, val y: Double, val name: String?, val cls: String?, val intermittent: Boolean,
        val catchmentKm2: Double?, var bridge: Boolean,
    )

    /** A point or lake near the route before grouping. */
    private class Near(val alongM: Double, val offsetM: Double, val x: Double, val y: Double, val name: String?, val cls: String?, val intermittent: Boolean)

    private class Scan(val line: RouteLine, val p: WaterParams) {
        val waterway = ArrayList<Hit>()
        val drainage = ArrayList<Hit>()
        val springs = ArrayList<Near>()
        val taps = ArrayList<Near>()
        val lakes = ArrayList<Near>()

        fun tile(tx: Int, ty: Int, segs: IntArray, layers: Map<String, io.github.jasondorjeshort.dystopo.mvt.Layer>) {
            val ox = tx.toDouble() * UNITS
            val oy = ty.toDouble() * UNITS
            val mpu = line.metresPerUnit(line.latAt(oy + UNITS / 2.0))
            val bridges = layers["transportation"]?.let { l -> linesOf(l, ox, oy) { brunnel(it) == "bridge" } } ?: emptyList()

            layers["waterway"]?.let { l ->
                val sc = UNITS.toDouble() / l.extent
                for (f in l.features) {
                    val g = f.geometry as? Geometry.Lines ?: continue
                    val name = text(f, "name")
                    val cls = text(f, "class")
                    val inter = flag(f, "intermittent")
                    val culvert = brunnel(f) == "tunnel"
                    for (pl in g.lines) crossings(pl, sc, ox, oy, segs) { a, x, y ->
                        waterway += Hit(a, x, y, name, cls, inter, null, culvert || nearAny(bridges, x, y, p.bridgeM / mpu))
                    }
                }
            }
            layers["drainage"]?.let { l ->
                val sc = UNITS.toDouble() / l.extent
                for (f in l.features) {
                    val g = f.geometry as? Geometry.Lines ?: continue
                    val km2 = f.properties["catchment_km2"]?.asDouble() ?: continue
                    for (pl in g.lines) crossings(pl, sc, ox, oy, segs) { a, x, y ->
                        drainage += Hit(a, x, y, null, text(f, "class"), false, km2, nearAny(bridges, x, y, p.bridgeM / mpu))
                    }
                }
            }
            layers["outdoor_poi"]?.let { l ->
                val sc = UNITS.toDouble() / l.extent
                for (f in l.features) {
                    val cls = text(f, "class")
                    val into = when (cls) { "spring" -> springs; "drinking_water" -> taps; else -> continue }
                    val g = f.geometry as? Geometry.Points ?: continue
                    for (pt in g.points) {
                        if (pt.x < 0 || pt.y < 0 || pt.x >= l.extent || pt.y >= l.extent) continue // its own tile only
                        val x = ox + pt.x * sc
                        val y = oy + pt.y * sc
                        val (a, d) = nearest(x, y, segs)
                        if (d * mpu <= p.nearM) into += Near(a, d * mpu, x, y, text(f, "name"), cls, false)
                    }
                }
            }
            layers["water"]?.let { l ->
                val sc = UNITS.toDouble() / l.extent
                val labels = layers["water_name"]?.features?.mapNotNull { f ->
                    val pt = (f.geometry as? Geometry.Points)?.points?.firstOrNull() ?: return@mapNotNull null
                    Triple(ox + pt.x * sc, oy + pt.y * sc, text(f, "name"))
                } ?: emptyList()
                for (f in l.features) {
                    val cls = text(f, "class")
                    if (cls != "lake" && cls != "pond") continue
                    val g = f.geometry as? Geometry.Polygons ?: continue
                    for (poly in g.polygons) {
                        val rings = (listOf(poly.exterior) + poly.interiors).map { r -> ring(r, sc, ox, oy) }
                        val best = lakeNearest(rings, segs) ?: continue
                        if (best.third * mpu > p.nearM) continue
                        val name = labels.firstOrNull { (lx, ly, nm) -> nm != null && inside(rings, lx, ly) }?.third
                        lakes += Near(best.first, best.third * mpu, best.second.first, best.second.second, name, cls, flag(f, "intermittent"))
                    }
                }
            }
        }

        /** Calls [hit] (along, x, y) for each crossing of [pl] with the route segments [segs] inside the tile's own square. */
        private inline fun crossings(pl: List<TilePoint>, sc: Double, ox: Double, oy: Double, segs: IntArray, hit: (Double, Double, Double) -> Unit) {
            val m = pl.size
            val fx = DoubleArray(m) { ox + pl[it].x * sc }
            val fy = DoubleArray(m) { oy + pl[it].y * sc }
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (k in 0 until m) { minX = min(minX, fx[k]); maxX = max(maxX, fx[k]); minY = min(minY, fy[k]); maxY = max(maxY, fy[k]) }
            for (i in segs) {
                val ax = line.xs[i]; val ay = line.ys[i]; val bx = line.xs[i + 1]; val by = line.ys[i + 1]
                if (max(ax, bx) < minX || min(ax, bx) > maxX || max(ay, by) < minY || min(ay, by) > maxY) continue
                for (k in 0 until m - 1) {
                    val cx = fx[k]; val cy = fy[k]; val dx = fx[k + 1]; val dy = fy[k + 1]
                    val rx = bx - ax; val ry = by - ay; val sx = dx - cx; val sy = dy - cy
                    val den = rx * sy - ry * sx
                    if (den == 0.0) continue // parallel: a touch along a shared stretch is caught at its ends
                    val qx = cx - ax; val qy = cy - ay
                    val u = (qx * sy - qy * sx) / den
                    val t = (qx * ry - qy * rx) / den
                    if (u < 0.0 || u > 1.0 || t < 0.0 || t > 1.0) continue
                    val x = ax + u * rx
                    val y = ay + u * ry
                    if (x < ox || y < oy || x >= ox + UNITS || y >= oy + UNITS) continue // a buffer copy: its own tile counts it
                    hit(line.alongAt(i, u), x, y)
                }
            }
        }

        /** (along, distance in world units) of the route point nearest ([x], [y]) among [segs]. */
        private fun nearest(x: Double, y: Double, segs: IntArray): Pair<Double, Double> {
            var bestD = Double.MAX_VALUE
            var bestA = 0.0
            for (i in segs) {
                val (u, d) = pointSeg(x, y, line.xs[i], line.ys[i], line.xs[i + 1], line.ys[i + 1])
                if (d < bestD) { bestD = d; bestA = line.alongAt(i, u) }
            }
            return bestA to bestD
        }

        /** The route's closest approach to a lake: (along, the lake point, distance in units); 0 when the route is inside. */
        private fun lakeNearest(rings: List<DoubleArray>, segs: IntArray): Triple<Double, Pair<Double, Double>, Double>? {
            var best: Triple<Double, Pair<Double, Double>, Double>? = null
            for (i in segs) {
                val ax = line.xs[i]; val ay = line.ys[i]; val bx = line.xs[i + 1]; val by = line.ys[i + 1]
                if (inside(rings, ax, ay)) {
                    if (best == null || best.third > 0.0) best = Triple(line.along[i], ax to ay, 0.0)
                    continue
                }
                for (r in rings) {
                    val k = r.size / 2
                    for (j in 0 until k) {
                        val cx = r[2 * j]; val cy = r[2 * j + 1]
                        val dx = r[(2 * j + 2) % r.size]; val dy = r[(2 * j + 3) % r.size]
                        val u = crossAt(ax, ay, bx, by, cx, cy, dx, dy)
                        if (u >= 0.0) {
                            best = Triple(line.alongAt(i, u), (ax + u * (bx - ax)) to (ay + u * (by - ay)), 0.0)
                            continue
                        }
                        // The closest pair of two segments that do not cross is at an end of one of them.
                        val c1 = pointSeg(cx, cy, ax, ay, bx, by)
                        if (best == null || c1.second < best.third) best = Triple(line.alongAt(i, c1.first), cx to cy, c1.second)
                        val c2 = pointSeg(dx, dy, ax, ay, bx, by)
                        if (c2.second < best.third) best = Triple(line.alongAt(i, c2.first), dx to dy, c2.second)
                        val c3 = pointSeg(ax, ay, cx, cy, dx, dy)
                        if (c3.second < best.third) best = Triple(line.along[i], (cx + c3.first * (dx - cx)) to (cy + c3.first * (dy - cy)), c3.second)
                        val c4 = pointSeg(bx, by, cx, cy, dx, dy)
                        if (c4.second < best.third) best = Triple(line.along[i + 1], (cx + c4.first * (dx - cx)) to (cy + c4.first * (dy - cy)), c4.second)
                    }
                }
            }
            return best
        }

        fun finish(tiles: Int, errors: Int, hasDrainage: Boolean, zoom: Int): WaterAlongResult {
            val mapped = group(waterway, sameWater = true)
            val derived = group(drainage, sameWater = false)
            // Each derived crossing joins the nearest mapped one within mergeM along the route.
            val joined = HashMap<Int, ArrayList<Hit>>()
            val alone = ArrayList<Hit>()
            for (d in derived) {
                var bestK = -1
                var bestGap = Double.MAX_VALUE
                for ((k, m) in mapped.withIndex()) {
                    val gap = abs(m.alongM - d.alongM)
                    if (gap <= p.mergeM && gap < bestGap) { bestGap = gap; bestK = k }
                }
                if (bestK >= 0) joined.getOrPut(bestK) { ArrayList() } += d else alone += d
            }
            val items = ArrayList<WaterItem>()
            for ((k, m) in mapped.withIndex()) {
                val with = joined[k]
                val km2 = with?.maxOf { it.catchmentKm2 ?: 0.0 }
                val (cls, basis) = if (km2 == null) {
                    mappedClassOf(m.cls, m.intermittent) to WaterBasis.MAPPED
                } else {
                    val terrain = classOf(km2, p) ?: WaterClass.USUALLY_DRY
                    if (m.intermittent && terrain == WaterClass.LIKELY_FLOWING) WaterClass.MAY_RUN_DRY to WaterBasis.MAPPED else terrain to WaterBasis.TERRAIN
                }
                items += WaterItem(
                    m.alongM, WaterKind.STREAM, cls, basis, m.name, km2, m.bridge || with?.any { it.bridge } == true, with != null,
                    m.intermittent, m.cls, 0.0, line.latAt(m.y), line.lonAt(m.x),
                )
            }
            var dropped = 0
            for (d in alone) {
                val cls = classOf(d.catchmentKm2 ?: 0.0, p)
                if (cls == null) { dropped++; continue }
                items += WaterItem(d.alongM, WaterKind.STREAM, cls, WaterBasis.TERRAIN, null, d.catchmentKm2, d.bridge, false, false, null, 0.0, line.latAt(d.y), line.lonAt(d.x))
            }
            fun points(list: List<Near>, kind: WaterKind) {
                for (s in list) items += WaterItem(s.alongM, kind, null, WaterBasis.MAPPED, s.name, null, false, false, s.intermittent, s.cls, s.offsetM, line.latAt(s.y), line.lonAt(s.x))
            }
            points(springs, WaterKind.SPRING)
            points(taps, WaterKind.TAP)
            val lakeGroups = groupLakes()
            points(lakeGroups, WaterKind.LAKE)
            items.sortWith(compareBy<WaterItem> { it.alongM }.thenBy { it.kind.ordinal })
            val stats = WaterStats(
                zoom, tiles, errors, hasDrainage, mapped.size, derived.size, derived.size - alone.size, dropped,
                springs.size, taps.size, lakeGroups.size,
            )
            return WaterAlongResult(items, stats)
        }

        /**
         * Crossings within [WaterParams.sameCrossingM] along the route that are the same water (mapped: the same name
         * and class; derived: any) become one: the mapped one keeps the first crossing, a derived group its largest catchment.
         */
        private fun group(hits: List<Hit>, sameWater: Boolean): List<Hit> {
            val sorted = hits.sortedBy { it.alongM }
            val out = ArrayList<Hit>()
            val open = ArrayList<Hit>() // the last kept hit of each water still within reach
            for (h in sorted) {
                val prev = open.lastOrNull { o ->
                    h.alongM - o.alongM <= p.sameCrossingM && (!sameWater || (o.name == h.name && o.cls == h.cls))
                }
                if (prev == null) {
                    out += h
                    open += h
                } else if (!sameWater && (h.catchmentKm2 ?: 0.0) > (prev.catchmentKm2 ?: 0.0)) {
                    val i = out.indexOf(prev)
                    val keep = Hit(prev.alongM, prev.x, prev.y, null, h.cls, false, h.catchmentKm2, prev.bridge || h.bridge)
                    out[i] = keep
                    open[open.indexOf(prev)] = keep
                } else {
                    prev.bridge = prev.bridge || h.bridge
                }
                open.removeAll { h.alongM - it.alongM > p.sameCrossingM }
            }
            return out
        }

        /** Pieces of one lake (cut by tiles) within [WaterParams.sameLakeM] along the route: the closest one, with any name. */
        private fun groupLakes(): List<Near> {
            val sorted = lakes.sortedBy { it.alongM }
            val out = ArrayList<Near>()
            for (l in sorted) {
                val k = out.indexOfLast { o -> l.alongM - o.alongM <= p.sameLakeM && (o.name == null || l.name == null || o.name == l.name) }
                if (k < 0) { out += l; continue }
                val o = out[k]
                val closer = if (l.offsetM < o.offsetM) l else o
                out[k] = Near(closer.alongM, closer.offsetM, closer.x, closer.y, o.name ?: l.name, closer.cls, o.intermittent || l.intermittent)
            }
            return out
        }

        private fun linesOf(l: io.github.jasondorjeshort.dystopo.mvt.Layer, ox: Double, oy: Double, keep: (Feature) -> Boolean): List<DoubleArray> {
            val sc = UNITS.toDouble() / l.extent
            val out = ArrayList<DoubleArray>()
            for (f in l.features) {
                if (!keep(f)) continue
                val g = f.geometry as? Geometry.Lines ?: continue
                for (pl in g.lines) out += DoubleArray(pl.size * 2) { if (it % 2 == 0) ox + pl[it / 2].x * sc else oy + pl[it / 2].y * sc }
            }
            return out
        }

        private fun nearAny(lines: List<DoubleArray>, x: Double, y: Double, maxD: Double): Boolean {
            for (l in lines) for (k in 0 until l.size / 2 - 1) {
                if (pointSeg(x, y, l[2 * k], l[2 * k + 1], l[2 * k + 2], l[2 * k + 3]).second <= maxD) return true
            }
            return false
        }

        private fun ring(r: List<TilePoint>, sc: Double, ox: Double, oy: Double) =
            DoubleArray(r.size * 2) { if (it % 2 == 0) ox + r[it / 2].x * sc else oy + r[it / 2].y * sc }
    }

    /** Even-odd test of ([x], [y]) against [rings] (an exterior and its holes). */
    private fun inside(rings: List<DoubleArray>, x: Double, y: Double): Boolean {
        var c = false
        for (r in rings) {
            val k = r.size / 2
            var j = k - 1
            for (i in 0 until k) {
                val xi = r[2 * i]; val yi = r[2 * i + 1]; val xj = r[2 * j]; val yj = r[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
                j = i
            }
        }
        return c
    }

    /** The parameter along a-b where it crosses c-d, or -1 when it does not. */
    private fun crossAt(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Double {
        val rx = bx - ax; val ry = by - ay; val sx = dx - cx; val sy = dy - cy
        val den = rx * sy - ry * sx
        if (den == 0.0) return -1.0
        val qx = cx - ax; val qy = cy - ay
        val u = (qx * sy - qy * sx) / den
        val t = (qx * ry - qy * rx) / den
        return if (u in 0.0..1.0 && t in 0.0..1.0) u else -1.0
    }

    /** (parameter along a-b, distance) from ([px], [py]) to segment a-b. */
    private fun pointSeg(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double> {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val u = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + u * dx - px
        val qy = ay + u * dy - py
        return u to sqrt(qx * qx + qy * qy)
    }

    private fun text(f: Feature, key: String): String? = (f.properties[key] as? Value.StringValue)?.value?.takeIf { it.isNotEmpty() }
    private fun brunnel(f: Feature): String? = text(f, "brunnel")
    private fun flag(f: Feature, key: String): Boolean = when (val v = f.properties[key]) {
        null -> false
        is Value.BoolValue -> v.value
        is Value.StringValue -> v.value == "1" || v.value == "yes" || v.value == "true"
        else -> (v.asDouble() ?: 0.0) != 0.0
    }
}
