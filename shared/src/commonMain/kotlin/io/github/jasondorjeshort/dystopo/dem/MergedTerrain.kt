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
        val lons = DoubleArray(w.width) { w.lonAt(it) }
        val lats = DoubleArray(w.height) { w.latAt(it) }
        // A window no box's band reaches (no cell centre on a box's edge or within bandM inside it) reads every cell whole:
        // the same answer, with no weight to work out and no state beyond the values.
        val band = if (bandM > 0 && ordered.any { s -> s.bounds?.let { reachesBand(it, lons, lats, bandM) } == true }) bandM else 0.0
        val st = Blend(n, band > 0, tally != null, ordered.size)
        for ((si, s) in ordered.withIndex()) blend(w, st, lons, lats, s, si, band, tally)
        val values = st.finish()
        tally?.let { t -> for ((si, s) in ordered.withIndex()) t.add(if (s.online) ONLINE else s.name, st.counts!![si]) }
        val maps = ordered.filter { !it.online }
        for (s in maps) if (s.reach != null && s.reach != s.bounds) fill(w, values, lons, lats, s, s.reach, tally)
        tally?.let { t ->
            t.windows++
            t.missing += values.count { it.isNaN() }
        }
        return ElevationGrid(w.width, w.height, w.cellSizeMeters, values)
    }

    /**
     * T357: a read's state. [values] holds each cell's weighted sum so far, NaN while no source has given it anything. A cell
     * given only part of its weight gets a slot (where a band can be reached: [slot] holds the slot plus one, 0 for none)
     * keeping the weight still to give ([rem]) and, for the tally, the largest part it was given and by which source
     * ([share], [owner]: the source's index plus one); a cell is open while its value is NaN or its slot's [rem] is above 0.
     * A cell given all of its weight at once by one source takes no slot and is counted at once in [counts]. Only the band's
     * cells take slots, so the state beyond the values is one integer per cell.
     */
    private class Blend(n: Int, banded: Boolean, private val tallied: Boolean, sources: Int) {
        val values = FloatArray(n).also { it.fill(Float.NaN) }
        val slot: IntArray? = if (banded) IntArray(n) else null
        private var cells = IntArray(if (banded) 1024 else 0)
        private var rem = FloatArray(cells.size)
        private var share = FloatArray(if (tallied) cells.size else 0)
        private var owner = IntArray(if (tallied) cells.size else 0)
        private var used = 0
        val counts: LongArray? = if (tallied) LongArray(sources) else null

        /** A cell some source has given a value is still open: part of its weight is left. */
        fun touchedOpen(k: Int): Boolean {
            val q = if (slot != null) slot[k] - 1 else -1
            return q >= 0 && rem[q] > 0f
        }

        fun open(k: Int): Boolean = values[k].isNaN() || touchedOpen(k)

        /** Gives cell [k] source [si]'s value [v] at weight [a] (above 0) of what is left. */
        fun give(k: Int, si: Int, v: Float, a: Float) {
            val acc = values[k]
            val untouched = acc.isNaN()
            var q = if (slot != null) slot[k] - 1 else -1
            val r = if (untouched) 1f else if (q >= 0) rem[q] else 0f
            if (r <= 0f) return
            val part = r * a
            values[k] = (if (untouched) 0f else acc) + part * v
            val left = if (a >= 1f) 0f else r - part
            if (untouched && left == 0f) {
                counts?.let { it[si]++ }
                return
            }
            if (q < 0) {
                if (used == cells.size) {
                    val size = cells.size * 2
                    cells = cells.copyOf(size)
                    rem = rem.copyOf(size)
                    if (tallied) {
                        share = share.copyOf(size)
                        owner = owner.copyOf(size)
                    }
                }
                q = used++
                cells[q] = k
                slot!![k] = q + 1
            }
            rem[q] = left
            if (tallied && part > share[q]) {
                share[q] = part
                owner[q] = si + 1
            }
        }

        /** The read's values: a cell given part of its weight is its sum over the weight given; a cell given none is NaN. */
        fun finish(): FloatArray {
            for (q in 0 until used) {
                val k = cells[q]
                val r = rem[q]
                if (r > 0f) values[k] = if (r >= 1f) Float.NaN else values[k] / (1f - r)
                if (tallied && owner[q] > 0) counts!![owner[q] - 1]++
            }
            return values
        }
    }

    /** T357: whether a cell centre of the window ([lons] west to east, [lats] north to south) may lie on [b] or within [bandM] inside it. */
    private fun reachesBand(b: PackBounds, lons: DoubleArray, lats: DoubleArray, bandM: Double): Boolean {
        val w0 = lons.first()
        val w1 = lons.last()
        val n0 = lats.first()
        val s0 = lats.last()
        if (w1 < b.minLon || w0 > b.maxLon || n0 < b.minLat || s0 > b.maxLat) return false // the window misses the box
        val i = EdgeBlend.inner(b, bandM) ?: return true
        return !(w0 > i.minLon && w1 < i.maxLon && s0 > i.minLat && n0 < i.maxLat) // not wholly inside the inner box
    }

    /** T357: the first index of ascending [a] whose value is at or above [x] ([inclusive]) or above it, else [a]'s size. */
    private fun firstFrom(a: DoubleArray, x: Double, inclusive: Boolean): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (if (inclusive) a[mid] >= x else a[mid] > x) hi = mid else lo = mid + 1
        }
        return lo
    }

    /**
     * T357: gives [s] (index [si] in the order) its share of the cells still open inside its box: each takes [s]'s value at
     * its [EdgeBlend.weight] of the weight still to give; only the rectangle of those cells is read. The box is a run of the
     * window's columns and rows; in each row the cells past the band (weight 1) are one run, and the weight is worked out
     * only for the band's cells, from the row's and the column's distances to the sides ([EdgeBlend.weightOfM], the same
     * number [EdgeBlend.weight] gives).
     */
    private fun blend(w: MercatorWindow, st: Blend, lons: DoubleArray, lats: DoubleArray, s: ElevationSource, si: Int, bandM: Double, tally: Tally?) {
        val box = s.bounds
        val whole = box == null || bandM <= 0 // the online source, or no band in the window: every cell it holds at weight 1
        val width = w.width
        // The box's columns and rows (its edge included when whole; a cell on the edge has weight 0 otherwise).
        var ci0 = 0
        var ci1 = width - 1
        var rj0 = 0
        var rj1 = w.height - 1
        if (box != null) {
            ci0 = firstFrom(lons, box.minLon, inclusive = whole)
            ci1 = firstFrom(lons, box.maxLon, inclusive = !whole) - 1
            // Latitudes fall from row to row.
            while (rj0 <= rj1 && (if (whole) lats[rj0] > box.maxLat else lats[rj0] >= box.maxLat)) rj0++
            while (rj1 >= rj0 && (if (whole) lats[rj1] < box.minLat else lats[rj1] <= box.minLat)) rj1--
        }
        if (ci0 > ci1 || rj0 > rj1) return
        if (si == 0) {
            tally?.let { it.reads++ }
            return blendRect(w, st, lats, s, si, bandM, whole, ci0, ci1, rj0, rj1) // every cell is open
        }
        var any = false
        // The cells still open, read block by block: the rectangle of each block's open cells (a band's thin ring is read as
        // its strips, not as the whole window it surrounds). A source gives a cell the same value from any window.
        for (bj in rj0..rj1 step BLOCK) {
            val bj1 = minOf(bj + BLOCK - 1, rj1)
            for (bi in ci0..ci1 step BLOCK) {
                val bi1 = minOf(bi + BLOCK - 1, ci1)
                var c0 = Int.MAX_VALUE
                var c1 = -1
                var r0 = Int.MAX_VALUE
                var r1 = -1
                for (j in bj..bj1) {
                    val base = j * width
                    var first = -1
                    for (i in bi..bi1) {
                        if (st.open(base + i)) {
                            first = i
                            break
                        }
                    }
                    if (first < 0) continue
                    var last = first
                    for (i in bi1 downTo first) {
                        if (st.open(base + i)) {
                            last = i
                            break
                        }
                    }
                    if (first < c0) c0 = first
                    if (last > c1) c1 = last
                    if (j < r0) r0 = j
                    r1 = j
                }
                if (c1 >= 0) {
                    any = true
                    blendRect(w, st, lats, s, si, bandM, whole, c0, c1, r0, r1)
                }
            }
        }
        if (any) tally?.let { it.reads++ } // one read of the source, as before, however many blocks it took
    }

    /** T357: the cells of [s]'s box are read and given by blocks of at most this many columns and rows ([blend]). */
    private const val BLOCK = 128

    /** T357: [blend] over the window's columns [c0]..[c1] and rows [r0]..[r1], all inside [s]'s box: one read of [s]. */
    private fun blendRect(w: MercatorWindow, st: Blend, lats: DoubleArray, s: ElevationSource, si: Int, bandM: Double, whole: Boolean, c0: Int, c1: Int, r0: Int, r1: Int) {
        val box = s.bounds
        val width = w.width
        val sw = c1 - c0 + 1
        val sh = r1 - r0 + 1
        val g = s.read(MercatorWindow(w.zoom, w.x0 + c0, w.y0 + r0, sw, sh, w.tileSize)) ?: return
        require(g.width == sw && g.height == sh) { "${s.name} answered ${g.width}x${g.height} for ${sw}x$sh" }
        val gv = g.values
        val values = st.values
        var took = 0L
        val lonM = if (whole) DoubleArray(0) else DoubleArray(sw) { EdgeBlend.lonInsideDeg(box!!, w.lonAt(c0 + it)) * EdgeBlend.M_PER_DEG }
        for (j in r0..r1) {
            val base = j * width
            val gBase = (j - r0) * sw - c0
            // The run of columns at weight 1 in this row: ia..ib (none when ia > ib).
            var ia = c0
            var ib = c1
            var latM = 0.0
            var cosLat = 0.0
            if (!whole) {
                latM = EdgeBlend.latInsideM(box!!, lats[j])
                cosLat = EdgeBlend.cosLat(lats[j])
                if (latM < bandM) {
                    ia = c1 + 1
                } else {
                    while (ia <= c1 && lonM[ia - c0] * cosLat < bandM) ia++
                    while (ib >= ia && lonM[ib - c0] * cosLat < bandM) ib--
                }
            }
            for (i in c0..c1) {
                val k = base + i
                val untouched = values[k].isNaN()
                if (!untouched && !st.touchedOpen(k)) continue // done
                val v = gv[gBase + i]
                if (v.isNaN()) continue
                if (i in ia..ib) {
                    // Weight 1 on a cell no source has touched: its value whole (0f + 1f * 1f * v, as [Blend.give] makes it).
                    if (untouched) {
                        values[k] = 0f + v
                        took++
                    } else {
                        st.give(k, si, v, 1f)
                    }
                } else {
                    val a = EdgeBlend.weightOfM(latM, lonM[i - c0], cosLat, bandM).toFloat()
                    if (a > 0f) st.give(k, si, v, a)
                }
            }
        }
        st.counts?.let { it[si] += took }
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
