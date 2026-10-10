package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * USGS 3DEP 1/3 arc-second (~10 m) seamless DEM, US only. Published as 1°x1° Cloud-Optimized
 * GeoTIFFs (NAD83 geographic, NAVD88 meters). Only the internal tiles covering the window are
 * fetched, via HTTP range requests, and cached under [cacheDir]. Cells with no file are NaN.
 *
 * Heights are converted from NAVD88 (or the local US datum) to EGM2008 with the hybrid geoid that
 * covers each cell (see [Geoids]); cells no grid covers are NaN, and a window no grid touches isn't
 * fetched at all (`datumUncovered`).
 * Resampled to Web Mercator with bilinear interpolation. NAD83 vs WGS84 differs horizontally by
 * ~1 m, well under a pixel, and is ignored.
 *
 * [destripe] (T70, off by default; `DYSTOPO_3DEP_DESTRIPE=1` turns it on for every 3DEP 1/3" read)
 * runs [Destripe] on the native grid before resampling, where the product's period-2 striping is
 * detected (Rainier's upper glaciers); only full-resolution reads (overview level 0) are filtered.
 */
class ThreeDepSource(
    private val cacheDir: File = Caches.dir("3dep13"),
    private val destripe: Boolean = System.getenv("DYSTOPO_3DEP_DESTRIPE") == "1",
) : ElevationSource {
    override val id = "3dep"
    override val registryIds = listOf("3dep") + Geoids.REGISTRY_IDS

    /** USGS names cells by their north-west corner: n47w122 covers 46..47°N, 121..122°W. Null outside the NW hemisphere. */
    private fun cellName(lat: Double, lon: Double): String? {
        val n = floor(lat).toInt() + 1
        val w = floor(-lon).toInt() + 1
        if (n <= 0 || w <= 0) return null
        return "n${n}w${w.toString().padStart(3, '0')}"
    }

    private fun url(cell: String) =
        "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current/$cell/USGS_13_$cell.tif"

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        if (!Geoids.covers(window)) {
            // No geoid grid to put these heights in the common datum: don't download what we'd discard.
            val grid = ElevationGrid(window.width, window.height, window.cellSizeMeters, FloatArray(window.width * window.height) { Float.NaN })
            return ElevationSource.Result(grid, mapOf("datumUncovered" to 1L, "noDataCells" to grid.values.size.toLong()), emptyList())
        }
        val lons = DoubleArray(window.width) { window.lonAt(it) }
        val lats = DoubleArray(window.height) { window.latAt(it) }

        // Open every 1° cell the window touches (rows run north to south, columns west to east).
        val cells = HashMap<String, RemoteTiff?>()
        for (lat in floor(lats.last()).toInt()..floor(lats.first()).toInt()) {
            for (lon in floor(lons.first()).toInt()..floor(lons.last()).toInt()) {
                val name = cellName(lat + 0.5, lon + 0.5) ?: continue
                cells[name] = try {
                    RemoteTiff(url(name), File(cacheDir, name))
                } catch (e: IllegalStateException) {
                    null // no 3DEP file for this cell
                }
            }
        }
        val present = cells.values.filterNotNull()
        val level = present.firstOrNull()?.levelFor(OverviewLevels.targetMeters(window), 111_320.0) ?: 0
        for (cell in present) cell.prefetch(level, lons.first(), lats.last(), lons.last(), lats.first())

        // Filtered native-grid copies per cell (destripe only); other cells are sampled directly.
        val destriped = HashMap<RemoteTiff, Destripe.Native>()
        var stripeStats = Destripe.Stats()
        if (destripe && level == 0) {
            for (cell in present) {
                val n = Destripe.Native.read(cell, lons.first(), lats.last(), lons.last(), lats.first(), margin = Destripe.BLOCK)
                stripeStats += Destripe.filter(n.values, n.width, n.height)
                destriped[cell] = n
            }
        }
        val values = FloatArray(window.width * window.height)
        for (j in 0 until window.height) {
            for (i in 0 until window.width) {
                val cell = cellName(lats[j], lons[i])?.let { cells[it] }
                values[j * window.width + i] = cell?.let { c -> destriped[c]?.sample(lons[i], lats[j]) ?: c.sample(level, lons[i], lats[j]) } ?: Float.NaN
            }
        }
        val offsets = if (present.isEmpty()) null else Geoids.navd88ToEgm2008(window)
        val (meanOffset, unconverted) = if (offsets == null) 0.0 to 0L else Geoids.apply(values, offsets)

        val counters = mapOf(
            "cells" to cells.size.toLong(),
            "missingCells" to cells.values.count { it == null }.toLong(),
            "overviewLevel" to level.toLong(),
            "tilesDecoded" to present.sumOf { it.tilesDecoded }.toLong(),
            "rangeRequests" to present.sumOf { it.source.fetches },
            "datumOffsetMeanCm" to Math.round(meanOffset * 100),
            "datumUnconvertedCells" to unconverted,
            "noDataCells" to values.count { it.isNaN() }.toLong(),
        ) + (if (destriped.isEmpty()) emptyMap() else mapOf(
            "destripeBlocks" to stripeStats.blocks.toLong(),
            "destripeBlocksFiltered" to stripeStats.filtered.toLong(),
            "destripeMeanChangeMm" to Math.round(stripeStats.meanChange * 1000),
            "destripeMaxChangeCm" to Math.round(stripeStats.maxChange * 100.0),
        ))
        val grid = ElevationGrid(window.width, window.height, window.cellSizeMeters, values)
        // Credit only the geoid grids this window used.
        return ElevationSource.Result(grid, counters, ElevationSource.provenanceIfData(grid, listOf("3dep") + offsets?.registryIds.orEmpty()))
    }
}

/**
 * T70: a directional filter for the period-2 striping of the 3DEP 1/3" product. On Rainier's upper
 * glaciers the native grid carries a coherent alternating component along rows or columns (pixel-scale
 * beads and stripes in the hillshade, noisy slope bands) in a few percent of 16x16 blocks; elsewhere
 * the alternating part is the incoherent texture of real terrain.
 *
 * Detection, per [BLOCK]x[BLOCK] block and per pattern (columns alternating, rows alternating, and a
 * checkerboard): `d` is the cell minus the mean of its neighbours along the axis (all four for the
 * checkerboard), and the coherence is |median of d times the pattern's sign| over median |d| (medians,
 * so a cliff along the axis does not read as a stripe). Uncorrelated texture gives about 0.07 (block
 * median measured there); striped blocks 0.3 to 0.55, 1 to 2 percent of blocks per pattern. The
 * strength ramps from 0 at [ON] to 1 at [FULL] (and with the amplitude from [MIN_AMPLITUDE] to twice
 * it, so centimetre noise is never "destriped"); the block's stripe amplitude times the
 * strength is interpolated bilinearly between block centres (no block edges) and the stripe is
 * subtracted along that axis only. Unlike a smoothing kernel this moves a cell by at most the stripe's
 * own amplitude, so cliffs, ridges and summits keep their shape. Blocks with no-data are left alone.
 * Off by default.
 */
object Destripe {
    const val BLOCK = 16
    const val ON = 0.25
    const val FULL = 0.40

    /** Stripe amplitudes (metres) below this are ignored, and full strength needs twice it. */
    const val MIN_AMPLITUDE = 0.05

    data class Stats(val blocks: Int = 0, val filtered: Int = 0, val meanChange: Double = 0.0, val maxChange: Double = 0.0) {
        operator fun plus(o: Stats): Stats {
            val n = blocks + o.blocks
            return Stats(n, filtered + o.filtered, if (n == 0) 0.0 else (meanChange * blocks + o.meanChange * o.blocks) / n, maxOf(maxChange, o.maxChange))
        }
    }

    /** The patterns: stripes across x (columns alternate), across y (rows alternate), and a checkerboard. */
    const val X = 0
    const val Y = 1
    const val CHECKER = 2

    /**
     * High-pass at (i, j) for [pattern]: the cell minus the mean of its two neighbours along the axis
     * (four for the checkerboard); null at the edge, NaN with no-data around. A pure pattern `a * sign`
     * gives `2a * sign` in all three cases.
     */
    private fun highPass(v: FloatArray, w: Int, h: Int, pattern: Int, i: Int, j: Int): Double? {
        val c = v[j * w + i].toDouble()
        return when (pattern) {
            X -> if (i < 1 || i >= w - 1) null else c - (v[j * w + i - 1] + v[j * w + i + 1]) / 2.0
            Y -> if (j < 1 || j >= h - 1) null else c - (v[(j - 1) * w + i] + v[(j + 1) * w + i]) / 2.0
            else -> if (i < 1 || i >= w - 1 || j < 1 || j >= h - 1) null
            else c - (v[j * w + i - 1] + v[j * w + i + 1] + v[(j - 1) * w + i] + v[(j + 1) * w + i]) / 4.0
        }
    }

    /** +1 or -1: the pattern's sign at (i, j), from the global index parity so neighbouring blocks agree. */
    private fun sign(pattern: Int, i: Int, j: Int): Int = if (((when (pattern) { X -> i; Y -> j; else -> i + j }) and 1) == 0) 1 else -1

    /**
     * Per block (`[by][bx]`), the coherence of [pattern] ([X], [Y] or [CHECKER]) and its amplitude: the
     * offset `a` in `z = terrain + a * sign(i, j)`.
     */
    fun measure(v: FloatArray, w: Int, h: Int, axis: Int): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val bh = h / BLOCK
        val bw = w / BLOCK
        val coh = Array(bh) { DoubleArray(bw) }
        val amp = Array(bh) { DoubleArray(bw) }
        val signed = DoubleArray(BLOCK * BLOCK)
        val absolute = DoubleArray(BLOCK * BLOCK)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var n = 0
            var bad = false
            for (j in by * BLOCK until (by + 1) * BLOCK) for (i in bx * BLOCK until (bx + 1) * BLOCK) {
                val d = highPass(v, w, h, axis, i, j) ?: continue
                if (d.isNaN()) {
                    bad = true
                    continue
                }
                signed[n] = sign(axis, i, j) * d
                absolute[n] = abs(d)
                n++
            }
            if (bad || n < BLOCK) continue
            // Medians, not means: a cliff or ridge along the axis is a large d in one or two cells per
            // row, which a mean reads as a coherent stripe; a real stripe moves most cells.
            val med = median(signed, n)
            val scale = median(absolute, n)
            if (scale <= 1e-6) continue
            coh[by][bx] = abs(med) / scale
            // For a pure stripe d = 2a * sign, so the signed median is 2a.
            amp[by][bx] = med / 2
        }
        return coh to amp
    }

    private fun median(a: DoubleArray, n: Int): Double {
        val s = a.copyOf(n)
        s.sort()
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Coherence only (see [measure]). */
    fun coherence(v: FloatArray, w: Int, h: Int, axis: Int): Array<DoubleArray> = measure(v, w, h, axis).first

    /**
     * Filters [v] (row-major, [w]x[h]) in place and says what it did: subtracts each detected pattern,
     * `strength * a * sign(i, j)`, one pattern after the other, so a cell moves by at most the patterns'
     * own amplitudes (cliffs and summits are never smoothed).
     */
    fun filter(v: FloatArray, w: Int, h: Int): Stats {
        val bw = w / BLOCK
        val bh = h / BLOCK
        if (bw == 0 || bh == 0) return Stats()
        var filtered = 0
        var sum = 0.0
        var max = 0.0
        var changed = 0
        for (axis in listOf(X, Y, CHECKER)) {
            val (coh, amp) = measure(v, w, h, axis)
            val stripe = Array(bh) { by ->
                DoubleArray(bw) { bx ->
                    val a = amp[by][bx]
                    // Coherent and big enough to matter: centimetre patterns are noise, not the striping.
                    ((coh[by][bx] - ON) / (FULL - ON)).coerceIn(0.0, 1.0) * ((abs(a) - MIN_AMPLITUDE) / MIN_AMPLITUDE).coerceIn(0.0, 1.0) * a
                }
            }
            filtered += stripe.sumOf { row -> row.count { it != 0.0 } }
            if (stripe.all { row -> row.all { it == 0.0 } }) continue
            for (j in 0 until h) for (i in 0 until w) {
                val a = interpolate(stripe, (i + 0.5) / BLOCK - 0.5, (j + 0.5) / BLOCK - 0.5)
                if (a == 0.0 || v[j * w + i].isNaN()) continue
                val delta = sign(axis, i, j) * a
                v[j * w + i] = (v[j * w + i] - delta).toFloat()
                sum += abs(delta)
                changed++
                if (abs(delta) > max) max = abs(delta)
            }
        }
        return Stats(bw * bh, filtered, if (changed == 0) 0.0 else sum / changed, max)
    }

    private fun interpolate(g: Array<DoubleArray>, x: Double, y: Double): Double {
        val w = g[0].size
        val h = g.size
        val cx = x.coerceIn(0.0, (w - 1).toDouble())
        val cy = y.coerceIn(0.0, (h - 1).toDouble())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = minOf(x0 + 1, w - 1)
        val y1 = minOf(y0 + 1, h - 1)
        val fx = cx - x0
        val fy = cy - y0
        return (g[y0][x0] * (1 - fx) + g[y0][x1] * fx) * (1 - fy) + (g[y1][x0] * (1 - fx) + g[y1][x1] * fx) * fy
    }

    /** A native-resolution copy (level 0) of one 3DEP cell around a lon/lat box, sampled like [RemoteTiff.sample]. */
    class Native(private val tiff: RemoteTiff, private val col0: Int, private val row0: Int, val width: Int, val height: Int, val values: FloatArray) {
        fun sample(x: Double, y: Double): Float {
            val l = tiff.level(0)
            val col = l.colOf(x)
            val row = l.rowOf(y)
            if (col < -1.0 || row < -1.0 || col > l.width.toDouble() || row > l.height.toDouble()) return Float.NaN
            val c0 = floor(col).toInt()
            val r0 = floor(row).toInt()
            val fx = (col - c0).toFloat()
            val fy = (row - r0).toFloat()
            return (at(c0, r0) * (1 - fx) + at(c0 + 1, r0) * fx) * (1 - fy) + (at(c0, r0 + 1) * (1 - fx) + at(c0 + 1, r0 + 1) * fx) * fy
        }

        private fun at(col: Int, row: Int): Float {
            val l = tiff.level(0)
            val i = col.coerceIn(0, l.width - 1) - col0
            val j = row.coerceIn(0, l.height - 1) - row0
            // Outside the copy (not for in-window samples, which have a block of margin): the unfiltered cell.
            if (i < 0 || j < 0 || i >= width || j >= height) return tiff.at(0, col, row)
            return values[j * width + i]
        }

        companion object {
            fun read(tiff: RemoteTiff, minX: Double, minY: Double, maxX: Double, maxY: Double, margin: Int): Native {
                val l = tiff.level(0)
                val c0 = (floor(l.colOf(minX)).toInt() - margin).coerceIn(0, l.width - 1)
                val c1 = (ceil(l.colOf(maxX)).toInt() + margin).coerceIn(0, l.width - 1)
                val r0 = (floor(l.rowOf(maxY)).toInt() - margin).coerceIn(0, l.height - 1)
                val r1 = (ceil(l.rowOf(minY)).toInt() + margin).coerceIn(0, l.height - 1)
                val w = c1 - c0 + 1
                val h = r1 - r0 + 1
                return Native(tiff, c0, r0, w, h, FloatArray(w * h) { k -> tiff.at(0, c0 + k % w, r0 + k / w) })
            }
        }
    }
}
