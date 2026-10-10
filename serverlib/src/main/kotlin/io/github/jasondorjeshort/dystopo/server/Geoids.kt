package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.dem.Datums
import io.github.jasondorjeshort.dystopo.dem.GeoidModel
import io.github.jasondorjeshort.dystopo.dem.HybridGeoid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.io.File

/**
 * A geoid grid from the PROJ data CDN (public-domain grids converted to Cloud-Optimized GeoTIFF),
 * read with range requests. Longitudes are wrapped to whatever range the grid uses (GEOID18 uses
 * 0..360; the Alaska grid runs 172..234).
 */
class ProjGeoid(url: String, cacheDir: File) : GeoidModel {
    private val grid = RemoteTiff(url, cacheDir)

    private fun wrap(lon: Double): Double? {
        val e = grid.tiff.extent
        for (candidate in doubleArrayOf(lon, lon + 360, lon - 360)) if (candidate >= e[0] && candidate <= e[2]) return candidate
        return null
    }

    /**
     * Reads the grid blocks under the box ahead of sampling. A box that only partly overlaps the grid
     * (straddling its longitude edge, or crossing the grid's wrap seam) is clamped to the overlapping
     * pieces rather than skipped.
     */
    fun prefetch(west: Double, south: Double, east: Double, north: Double) {
        val e = grid.tiff.extent
        val s = maxOf(south, e[1])
        val n = minOf(north, e[3])
        if (s > n) return
        for ((w0, e0) in overlapRanges(west, east, e[0], e[2])) grid.prefetch(0, w0, s, e0, n)
    }

    companion object {
        /**
         * The pieces of longitude range [west, east] (east >= west, east possibly past 180) that fall
         * inside a grid spanning [gridWest, gridEast], each in the grid's own longitude frame. Tries the
         * range shifted by -360, 0 and +360, so 0..360 grids and antimeridian boxes both work.
         */
        fun overlapRanges(west: Double, east: Double, gridWest: Double, gridEast: Double): List<Pair<Double, Double>> {
            val out = ArrayList<Pair<Double, Double>>()
            val unwrappedEast = if (east < west) east + 360 else east
            for (shift in doubleArrayOf(-360.0, 0.0, 360.0)) {
                val w = maxOf(west + shift, gridWest)
                val e = minOf(unwrappedEast + shift, gridEast)
                if (w <= e) out += w to e
            }
            return out
        }
    }

    /** NaN outside the grid. */
    override fun undulation(lat: Double, lon: Double): Double {
        val x = wrap(lon) ?: return Double.NaN
        return grid.sample(0, x, lat).toDouble()
    }
}

object Geoids {
    /** NGA EGM2008, 2.5' grid, relative to the WGS84 ellipsoid. */
    val egm2008 by lazy { ProjGeoid("https://cdn.proj.org/us_nga_egm08_25.tif", Caches.dir("geoids/us_nga_egm08_25")) }

    private val hybrids = HashMap<String, ProjGeoid>()

    /** The PROJ CDN grid for [geoid], opened on first use. */
    @Synchronized
    fun grid(geoid: HybridGeoid): ProjGeoid = hybrids.getOrPut(geoid.registryId) {
        ProjGeoid("https://cdn.proj.org/${geoid.file}", Caches.dir("geoids/${geoid.file.removeSuffix(".tif")}"))
    }

    /**
     * Registry ids of every geoid that can take part in the US -> EGM2008 conversion (a source's full
     * credit). Results credit only the grids a window actually used ([Offsets.registryIds]).
     */
    val REGISTRY_IDS = Datums.HYBRID_GEOIDS.map { it.registryId } + "egm2008"

    /** Per-cell meters to add (NaN where no grid covers); [registryIds] are the geoids that produced them. */
    class Offsets(val values: FloatArray, val registryIds: List<String>)

    private val offsetCache = HashMap<MercatorWindow, Offsets>()

    /**
     * True if some hybrid geoid overlaps [window]. Where none does, US heights can't be put in the
     * common datum, so the USGS sources skip fetching them and report `datumUncovered`.
     */
    fun covers(window: MercatorWindow): Boolean = Datums.hybridGeoidsIntersecting(
        window.lonAt(0), window.latAt(window.height - 1), window.lonAt(window.width - 1), window.latAt(0),
    ).isNotEmpty()

    /**
     * Per-pixel meters to add to NAVD88 (and sibling US datum) heights to get EGM2008 heights. Geoids
     * vary over kilometers, so offsets are computed on a coarse lattice and interpolated. Each lattice
     * point uses the hybrid geoid that covers it ([Datums.hybridGeoidFor] order); NaN where none does.
     * Memoized per window.
     */
    @Synchronized
    fun navd88ToEgm2008(window: MercatorWindow): Offsets = offsetCache.getOrPut(window) { computeOffsets(window, 16) }

    private fun computeOffsets(window: MercatorWindow, step: Int): Offsets {
        val lw = (window.width - 1) / step + 2
        val lh = (window.height - 1) / step + 2
        // The last lattice column/row may lie just past the window edge; lonAt/latAt extrapolate.
        // Keeping every lattice point on a multiple of `step` from the window origin means windows
        // whose origins are 16-aligned (tile exports) sample identical points, so a cell's offset
        // doesn't depend on which window computed it.
        val west = window.lonAt(0)
        val east = window.lonAt((lw - 1) * step)
        val south = window.latAt((lh - 1) * step)
        val north = window.latAt(0)
        val candidates = Datums.hybridGeoidsIntersecting(west, south, east, north)
        for (g in candidates) grid(g).prefetch(west, south, east, north)
        if (candidates.isNotEmpty()) egm2008.prefetch(west, south, east, north)
        val used = LinkedHashSet<String>()
        val lattice = DoubleArray(lw * lh)
        for (b in 0 until lh) for (a in 0 until lw) {
            val lat = window.latAt(b * step)
            val lon = window.lonAt(a * step)
            val geoid = candidates.firstOrNull { it.covers(lat, lon) }
            val v = if (geoid == null) Double.NaN else Datums.navd88ToEgm2008(lat, lon, grid(geoid), egm2008, geoid.frame)
            if (geoid != null && !v.isNaN()) used += geoid.registryId
            lattice[b * lw + a] = v
        }
        val out = FloatArray(window.width * window.height)
        for (j in 0 until window.height) {
            val b = j / step
            val fy = (j - b * step).toDouble() / step
            for (i in 0 until window.width) {
                val a = i / step
                val fx = (i - a * step).toDouble() / step
                val v00 = lattice[b * lw + a]
                val v10 = lattice[b * lw + a + 1]
                val v01 = lattice[(b + 1) * lw + a]
                val v11 = lattice[(b + 1) * lw + a + 1]
                out[j * window.width + i] = ((v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy).toFloat()
            }
        }
        return Offsets(out, if (used.isEmpty()) emptyList() else used.toList() + "egm2008")
    }

    /** Adds [offsets] to [values] in place; returns the mean offset applied (meters) and cells left unconverted. */
    fun apply(values: FloatArray, offsets: Offsets): Pair<Double, Long> {
        var sum = 0.0
        var n = 0L
        var unconverted = 0L
        for (k in values.indices) {
            if (values[k].isNaN()) continue
            val o = offsets.values[k]
            if (o.isNaN()) {
                values[k] = Float.NaN // can't place this cell in the common datum; let a fallback fill it
                unconverted++
            } else {
                values[k] += o
                sum += o
                n++
            }
        }
        return (if (n == 0L) 0.0 else sum / n) to unconverted
    }
}
