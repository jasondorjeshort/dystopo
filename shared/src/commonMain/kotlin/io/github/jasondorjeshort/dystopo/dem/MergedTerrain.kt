package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.Holdings
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
    /** T354: the map chosen last (`POST /packs/activate`): first between two maps of equal depth (ADR 0066). */
    val chosen: Boolean = false,
    val read: (MercatorWindow) -> ElevationGrid?,
) {
    val online: Boolean get() = bounds == null
}

/**
 * T340: the terrain read for a chosen point, cell by cell, that the Horizon view, the Panorama and the viewshed share (the
 * owner's rule of 2026-11-22: "look at the highest resolution available at the point and expect to step down to a coarser
 * pack once it leaves the finer one"). For each cell of a window:
 *
 * 1. the offline maps whose box holds the cell, the deepest first ([ORDER]: the lookup's rule, `Holdings.depthFirst`, which
 *    `PackPrecedence` shares since T354: deepest, then the map chosen last, then the smaller box, then the name);
 * 2. then the online source, where it is in the list (the caller leaves it out offline, or where the data rules forbid it);
 *    T354: last whatever its depth while `onlineByDepth` is false (every caller today); true ranks it among the maps by its
 *    stored maximum, ahead of a map only where it stores deeper (ADR 0066 decision 2, T368 turns it on);
 * 3. then what a map's whole tiles hold past its box ([ElevationSource.reach]), the deepest first: a margin can be as coarse
 *    as the map's first zoom, so the online source, which has full detail there, goes before it.
 *
 * Each source is asked only for the rectangle of the cells still missing that it may answer, so a window wholly inside a
 * fine map never reaches a coarser map or the network. [Tally] counts the cells each source gave.
 *
 * T357 (ADR 0066 decision 4): near a box's edge a cell blends that source with the ones after it by [EdgeBlend.weight], the
 * weight [MergedTile] composes the map's edge tiles with, so the map and the sky views agree there. The promise above holds
 * from [EdgeBlend.BAND_M] inside a fine map's box; inside that band the coarser map is read too.
 */
object MergedTerrain {
    /** The [Tally] key of the online source's cells. */
    const val ONLINE = "online"

    private fun area(b: PackBounds) = (b.maxLon - b.minLon) * (b.maxLat - b.minLat)

    /**
     * T354: the lookup's rule ([Holdings.depthFirst]): deepest first, then the map chosen last, then a map before the online
     * source, then the smaller box, then the name. With [onlineByDepth] false the online source is last whatever its depth.
     */
    fun order(onlineByDepth: Boolean): Comparator<ElevationSource> {
        val rule = Holdings.depthFirst<ElevationSource>({ it.maxZoom }, { it.chosen }, { if (it.online) 1 else 0 }, { it.bounds?.let(::area) ?: Double.MAX_VALUE }, { it.name })
        return if (onlineByDepth) rule else compareBy<ElevationSource> { if (it.online) 1 else 0 }.then(rule)
    }

    /** The order the maps are asked in today: the offline maps deepest first, the online source last ([order] with `onlineByDepth` false). */
    val ORDER: Comparator<ElevationSource> = order(onlineByDepth = false)

    fun order(sources: List<ElevationSource>, onlineByDepth: Boolean = false): List<ElevationSource> = sources.sortedWith(order(onlineByDepth))

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
     * The zoom to read a point's terrain at, at most [cap] ([Holdings.pointZoom], the lookup's rule): the deepest map whose box
     * holds ([lat], [lon]), else the deepest whose tiles reach it, else the online source's; with [onlineByDepth] the online
     * source's stored maximum counts beside the maps' and the deeper wins. Null when nothing in [sources] answers there.
     */
    fun pointZoom(sources: List<ElevationSource>, lat: Double, lon: Double, cap: Int, onlineByDepth: Boolean = false): Int? {
        val maps = sources.filter { !it.online }
        return Holdings.pointZoom(
            maps.filter { holds(it.bounds!!, lon, lat) }.map { it.maxZoom },
            maps.filter { it.reach != null && holds(it.reach, lon, lat) }.map { it.maxZoom },
            sources.filter { it.online }.maxOfOrNull { it.maxZoom }, onlineByDepth, cap,
        )
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

    /**
     * Window [w] read cell by cell from [sources] (in any order: [order] is applied with [onlineByDepth]), counting into
     * [tally]: each source over its box in that order (the online source anywhere), then the maps' margins.
     *
     * T357 (ADR 0066 decision 4): a cell near the edge of a source's box takes that source's value at [EdgeBlend.weight] and
     * the rest from the sources after it (each blended the same way at its own edges), so a finer map fades into the coarser
     * one over [bandM] metres inside its box instead of stepping at the box. A cell [bandM] or more inside a source's box takes
     * that source's value alone, so a window that far inside a fine map still never reaches a coarser map or the network;
     * only the band reads both. Where a later source has no value the earlier ones' answer stands. [bandM] 0 is the read
     * before T357 (the first source holding the cell gives all of it). A cell counts in [tally] for the source that gave it
     * the largest share.
     */
    fun read(w: MercatorWindow, sources: List<ElevationSource>, tally: Tally? = null, onlineByDepth: Boolean = false, bandM: Double = EdgeBlend.BAND_M): ElevationGrid {
        val ordered = order(sources, onlineByDepth)
        val n = w.width * w.height
        val acc = FloatArray(n) // the weighted sum so far
        val rem = FloatArray(n) { 1f } // the weight still to give: 1 nothing yet, 0 done
        val owner = if (tally != null) IntArray(n) { -1 } else null // the source that gave the largest share, and that share
        val share = if (tally != null) FloatArray(n) else null
        val lons = DoubleArray(w.width) { w.lonAt(it) }
        val lats = DoubleArray(w.height) { w.latAt(it) }
        for ((si, s) in ordered.withIndex()) blend(w, acc, rem, owner, share, lons, lats, s, si, bandM, tally)
        val values = FloatArray(n) { k -> if (rem[k] >= 1f) Float.NaN else if (rem[k] <= 0f) acc[k] else acc[k] / (1f - rem[k]) }
        if (tally != null) {
            val counts = LongArray(ordered.size)
            for (k in 0 until n) if (owner!![k] >= 0) counts[owner[k]]++
            for ((si, s) in ordered.withIndex()) tally.add(if (s.online) ONLINE else s.name, counts[si])
        }
        val maps = ordered.filter { !it.online }
        for (s in maps) if (s.reach != null && s.reach != s.bounds) fill(w, values, lons, lats, s, s.reach, tally)
        tally?.let { t ->
            t.windows++
            t.missing += values.count { it.isNaN() }
        }
        return ElevationGrid(w.width, w.height, w.cellSizeMeters, values)
    }

    /**
     * T357: gives [s] (index [si] in the order) its share of the cells still open inside its box: each takes [s]'s value at
     * its [EdgeBlend.weight] of the weight still to give ([rem]); only the rectangle of those cells is read.
     */
    private fun blend(
        w: MercatorWindow, acc: FloatArray, rem: FloatArray, owner: IntArray?, share: FloatArray?, lons: DoubleArray, lats: DoubleArray,
        s: ElevationSource, si: Int, bandM: Double, tally: Tally?,
    ) {
        val box = s.bounds
        val whole = box == null || bandM <= 0 // the online source, or the read before T357: every cell it holds at weight 1
        // A cell inside the box (on its edge too when whole; its weight is 0 there otherwise) and the row's and column's
        // distances to the box's sides for the weight ([EdgeBlend.weightOf]).
        val cols = BooleanArray(w.width) { box == null || (if (whole) lons[it] >= box.minLon && lons[it] <= box.maxLon else lons[it] > box.minLon && lons[it] < box.maxLon) }
        val rows = BooleanArray(w.height) { box == null || (if (whole) lats[it] >= box.minLat && lats[it] <= box.maxLat else lats[it] > box.minLat && lats[it] < box.maxLat) }
        var c0 = Int.MAX_VALUE
        var c1 = -1
        var r0 = Int.MAX_VALUE
        var r1 = -1
        for (j in 0 until w.height) {
            if (!rows[j]) continue
            val base = j * w.width
            for (i in 0 until w.width) {
                if (cols[i] && rem[base + i] > 0f) {
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
        val lonDeg = DoubleArray(sw) { if (whole) 0.0 else EdgeBlend.lonInsideDeg(box!!, lons[c0 + it]) }
        for (j in r0..r1) {
            if (!rows[j]) continue
            val latM = if (whole) 0.0 else EdgeBlend.latInsideM(box!!, lats[j])
            val cosLat = if (whole) 0.0 else EdgeBlend.cosLat(lats[j])
            for (i in c0..c1) {
                val k = j * w.width + i
                if (!cols[i] || rem[k] <= 0f) continue
                val v = g.values[(j - r0) * sw + (i - c0)]
                if (v.isNaN()) continue
                val a = if (whole) 1f else EdgeBlend.weightOf(latM, lonDeg[i - c0], cosLat, bandM).toFloat()
                if (a <= 0f) continue
                val part = rem[k] * a
                acc[k] += part * v
                rem[k] = if (a >= 1f) 0f else rem[k] - part
                if (owner != null && part > share!![k]) {
                    share[k] = part
                    owner[k] = si
                }
            }
        }
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
