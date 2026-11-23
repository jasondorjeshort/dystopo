package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.math.floor

/**
 * T340: one place elevation comes from for [MergedTerrain]: an offline map's DEM ([bounds] its box) or the online source
 * ([bounds] null: it answers anywhere it can). [maxZoom] is the deepest zoom it stores, [reach] how far its tiles reach past
 * its box (a map holds whole tiles: [MergedTerrain.wholeTileReach]); [read] gives a window's elevations (NaN where it has
 * none), or null when it has nothing for the window at all.
 */
class ElevationSource(
    val name: String,
    val maxZoom: Int,
    val bounds: PackBounds?,
    val reach: PackBounds? = bounds,
    val read: (MercatorWindow) -> ElevationGrid?,
) {
    val online: Boolean get() = bounds == null
}

/**
 * T340: the terrain read for a chosen point, cell by cell, that the Horizon view, the Panorama and the viewshed share (the
 * owner's rule of 2026-11-22: "look at the highest resolution available at the point and expect to step down to a coarser
 * pack once it leaves the finer one"). For each cell of a window:
 *
 * 1. the offline maps whose box holds the cell, the deepest first ([ORDER]: deepest, then the smaller box, then the name;
 *    which map the user chose last plays no part, unlike `PackPrecedence`, whose order this leaves alone);
 * 2. then the online source, where it is in the list (the caller leaves it out offline, or where the data rules forbid it);
 * 3. then what a map's whole tiles hold past its box ([ElevationSource.reach]), the deepest first: a margin can be as coarse
 *    as the map's first zoom, so the online source, which has full detail there, goes before it.
 *
 * Each source is asked only for the rectangle of the cells still missing that it may answer, so a window wholly inside a
 * fine map never reaches a coarser map or the network. [Tally] counts the cells each source gave.
 */
object MergedTerrain {
    /** The [Tally] key of the online source's cells. */
    const val ONLINE = "online"

    private fun area(b: PackBounds) = (b.maxLon - b.minLon) * (b.maxLat - b.minLat)

    /** The order the maps are asked in: the offline maps deepest first (then the smaller box, then the name), the online source last. */
    val ORDER: Comparator<ElevationSource> = compareBy<ElevationSource>(
        { if (it.online) 1 else 0 },
        { -it.maxZoom },
        { it.bounds?.let(::area) ?: Double.MAX_VALUE },
        { it.name },
    )

    fun order(sources: List<ElevationSource>): List<ElevationSource> = sources.sortedWith(ORDER)

    /** The box a map's tiles cover: [bounds] widened to the whole tiles of its first zoom [minZoom]. */
    fun wholeTileReach(bounds: PackBounds, minZoom: Int): PackBounds {
        val z = minZoom.coerceIn(0, 22)
        val n = 1 shl z
        val x0 = floor(WebMercator.lonToX(bounds.minLon, z)).coerceIn(0.0, n - 1.0)
        val x1 = floor(WebMercator.lonToX(bounds.maxLon, z)).coerceIn(0.0, n - 1.0)
        val y0 = floor(WebMercator.latToY(bounds.maxLat, z)).coerceIn(0.0, n - 1.0)
        val y1 = floor(WebMercator.latToY(bounds.minLat, z)).coerceIn(0.0, n - 1.0)
        return PackBounds(WebMercator.xToLon(x0, z), WebMercator.yToLat(y1 + 1, z), WebMercator.xToLon(x1 + 1, z), WebMercator.yToLat(y0, z))
    }

    private fun holds(b: PackBounds, lon: Double, lat: Double) = lon >= b.minLon && lon <= b.maxLon && lat >= b.minLat && lat <= b.maxLat

    /**
     * The zoom to read a point's terrain at, at most [cap]: the deepest map whose box holds ([lat], [lon]), else the deepest
     * whose tiles reach it, else the online source's; null when nothing in [sources] answers there.
     */
    fun pointZoom(sources: List<ElevationSource>, lat: Double, lon: Double, cap: Int): Int? {
        val maps = sources.filter { !it.online }
        val z = maps.filter { holds(it.bounds!!, lon, lat) }.maxOfOrNull { it.maxZoom }
            ?: maps.filter { it.reach != null && holds(it.reach, lon, lat) }.maxOfOrNull { it.maxZoom }
            ?: sources.filter { it.online }.maxOfOrNull { it.maxZoom }
        return z?.let { minOf(it, cap) }
    }

    /** Cells each source gave ([cells], by name, in the order they first gave any), the cells none had, the reads made. */
    class Tally {
        val cells = LinkedHashMap<String, Long>()
        var missing = 0L
        var windows = 0
        var reads = 0

        fun add(name: String, n: Long) {
            if (n > 0) cells[name] = (cells[name] ?: 0L) + n
        }

        /** The maps that gave cells (the online source left out). */
        val maps: List<String> get() = cells.keys.filter { it != ONLINE }
        val online: Boolean get() = (cells[ONLINE] ?: 0L) > 0
        val complete: Boolean get() = missing == 0L

        fun json(): Map<String, Any?> = linkedMapOf("cells" to LinkedHashMap(cells), "missing" to missing, "windows" to windows, "reads" to reads)
    }

    /** Window [w] read cell by cell from [sources] (in any order: [order] is applied), counting into [tally]. */
    fun read(w: MercatorWindow, sources: List<ElevationSource>, tally: Tally? = null): ElevationGrid {
        val ordered = order(sources)
        val values = FloatArray(w.width * w.height) { Float.NaN }
        val lons = DoubleArray(w.width) { w.lonAt(it) }
        val lats = DoubleArray(w.height) { w.latAt(it) }
        val maps = ordered.filter { !it.online }
        for (s in maps) fill(w, values, lons, lats, s, s.bounds, tally)
        for (s in ordered.filter { it.online }) fill(w, values, lons, lats, s, null, tally)
        for (s in maps) if (s.reach != null && s.reach != s.bounds) fill(w, values, lons, lats, s, s.reach, tally)
        tally?.let { t ->
            t.windows++
            t.missing += values.count { it.isNaN() }
        }
        return ElevationGrid(w.width, w.height, w.cellSizeMeters, values)
    }

    /** Fills the missing cells of [values] inside [box] (anywhere when null) from [s], reading only their rectangle. */
    private fun fill(w: MercatorWindow, values: FloatArray, lons: DoubleArray, lats: DoubleArray, s: ElevationSource, box: PackBounds?, tally: Tally?) {
        val cols = BooleanArray(w.width) { box == null || (lons[it] >= box.minLon && lons[it] <= box.maxLon) }
        val rows = BooleanArray(w.height) { box == null || (lats[it] >= box.minLat && lats[it] <= box.maxLat) }
        var c0 = Int.MAX_VALUE
        var c1 = -1
        var r0 = Int.MAX_VALUE
        var r1 = -1
        for (j in 0 until w.height) {
            if (!rows[j]) continue
            val base = j * w.width
            for (i in 0 until w.width) {
                if (cols[i] && values[base + i].isNaN()) {
                    if (i < c0) c0 = i
                    if (i > c1) c1 = i
                    if (j < r0) r0 = j
                    if (j > r1) r1 = j
                }
            }
        }
        if (c1 < 0) return
        val sw = c1 - c0 + 1
        val sh = r1 - r0 + 1
        tally?.let { it.reads++ }
        val g = s.read(MercatorWindow(w.zoom, w.x0 + c0, w.y0 + r0, sw, sh, w.tileSize)) ?: return
        require(g.width == sw && g.height == sh) { "${s.name} answered ${g.width}x${g.height} for ${sw}x$sh" }
        var took = 0L
        for (j in r0..r1) {
            if (!rows[j]) continue
            for (i in c0..c1) {
                val k = j * w.width + i
                if (!cols[i] || !values[k].isNaN()) continue
                val v = g.values[(j - r0) * sw + (i - c0)]
                if (!v.isNaN()) {
                    values[k] = v
                    took++
                }
            }
        }
        tally?.add(if (s.online) ONLINE else s.name, took)
    }
}
