package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.Datums
import io.github.jasondorjeshort.dystopo.dem.GeoidModel
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.io.File

/**
 * A geoid grid from the PROJ data CDN (public-domain grids converted to Cloud-Optimized GeoTIFF),
 * read with range requests. Longitudes are wrapped to whatever range the grid uses (GEOID18 uses 0..360).
 */
class ProjGeoid(url: String, cacheDir: File) : GeoidModel {
    private val grid = RemoteTiff(url, cacheDir)

    private fun wrap(lon: Double): Double? {
        val e = grid.tiff.extent
        for (candidate in doubleArrayOf(lon, lon + 360, lon - 360)) if (candidate >= e[0] && candidate <= e[2]) return candidate
        return null
    }

    fun prefetch(west: Double, south: Double, east: Double, north: Double) {
        val w = wrap(west) ?: return
        val e = wrap(east) ?: return
        grid.prefetch(0, minOf(w, e), south, maxOf(w, e), north)
    }

    /** NaN outside the grid. */
    override fun undulation(lat: Double, lon: Double): Double {
        val x = wrap(lon) ?: return Double.NaN
        return grid.sample(0, x, lat).toDouble()
    }
}

object Geoids {
    /** NGS GEOID18 (CONUS): NAVD88 relative to the NAD83(2011) ellipsoid. */
    val geoid18 by lazy { ProjGeoid("https://cdn.proj.org/us_noaa_g2018u0.tif", Caches.dir("geoids/us_noaa_g2018u0")) }

    /** NGA EGM2008, 2.5' grid, relative to the WGS84 ellipsoid. */
    val egm2008 by lazy { ProjGeoid("https://cdn.proj.org/us_nga_egm08_25.tif", Caches.dir("geoids/us_nga_egm08_25")) }

    /** Registry ids of the geoids used for NAVD88 -> EGM2008 conversion; their credit comes from the registry. */
    val REGISTRY_IDS = listOf("geoid18", "egm2008")

    private val offsetCache = HashMap<MercatorWindow, FloatArray>()

    /**
     * Per-pixel meters to add to NAVD88 heights to get EGM2008 heights. Geoids vary over
     * kilometers, so offsets are computed on a coarse lattice and interpolated. NaN where
     * GEOID18 has no coverage (outside CONUS). Memoized per window.
     */
    @Synchronized
    fun navd88ToEgm2008(window: MercatorWindow): FloatArray = offsetCache.getOrPut(window) { computeOffsets(window, 16) }

    private fun computeOffsets(window: MercatorWindow, step: Int): FloatArray {
        geoid18.prefetch(window.lonAt(0), window.latAt(window.height - 1), window.lonAt(window.width - 1), window.latAt(0))
        egm2008.prefetch(window.lonAt(0), window.latAt(window.height - 1), window.lonAt(window.width - 1), window.latAt(0))
        val lw = (window.width - 1) / step + 2
        val lh = (window.height - 1) / step + 2
        val lattice = DoubleArray(lw * lh)
        for (b in 0 until lh) for (a in 0 until lw) {
            // The last lattice column/row may lie just past the window edge; lonAt/latAt extrapolate.
            // Keeping every lattice point on a multiple of `step` from the window origin means windows
            // whose origins are 16-aligned (tile exports) sample identical points, so a cell's offset
            // doesn't depend on which window computed it.
            val lat = window.latAt(b * step)
            val lon = window.lonAt(a * step)
            lattice[b * lw + a] = Datums.navd88ToEgm2008(lat, lon, geoid18, egm2008)
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
        return out
    }

    /** Adds [offsets] to [values] in place; returns the mean offset applied (meters) and cells left unconverted. */
    fun apply(values: FloatArray, offsets: FloatArray): Pair<Double, Long> {
        var sum = 0.0
        var n = 0L
        var unconverted = 0L
        for (k in values.indices) {
            if (values[k].isNaN()) continue
            val o = offsets[k]
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
