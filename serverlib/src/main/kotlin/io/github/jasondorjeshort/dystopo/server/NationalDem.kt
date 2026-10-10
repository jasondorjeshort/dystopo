package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.dem.Datums
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.NationalGeoid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Pieces shared by the national bare-earth sources outside the US and Switzerland ([NorwaySource],
 * [AustriaSource]): a small per-host I/O pool, the geoid offset lattice, and an all-NaN result.
 */
object NationalDem {
    /**
     * Coarsest output cell, in meters, for which a national 1 m source is read. Above it Copernicus
     * (30 m) already samples every output cell (same threshold as swissALTI3D: z12 and up).
     */
    const val MAX_USEFUL_CELL_METERS = 30.0

    /** Simultaneous requests to one national service. */
    const val MAX_PARALLEL = 4

    fun useful(window: MercatorWindow): Boolean = OverviewLevels.targetMeters(window) <= MAX_USEFUL_CELL_METERS

    /** West, south, east, north of a window's pixel centers. */
    fun bounds(window: MercatorWindow): DoubleArray =
        doubleArrayOf(window.lonAt(0), window.latAt(window.height - 1), window.lonAt(window.width - 1), window.latAt(0))

    fun intersects(box: DoubleArray, coverage: DoubleArray): Boolean =
        box[2] >= coverage[0] && box[0] <= coverage[2] && box[3] >= coverage[1] && box[1] <= coverage[3]

    fun nanResult(window: MercatorWindow, counters: Map<String, Long>): ElevationSource.Result {
        val grid = ElevationGrid(window.width, window.height, window.cellSizeMeters, FloatArray(window.width * window.height) { Float.NaN })
        return ElevationSource.Result(grid, counters + ("noDataCells" to grid.values.size.toLong()), emptyList())
    }

    private val grids = ConcurrentHashMap<String, ProjGeoid>()

    /** A national geoid grid from the PROJ CDN, opened once per process. */
    fun grid(geoid: NationalGeoid): ProjGeoid =
        grids.computeIfAbsent(geoid.file) { ProjGeoid("https://cdn.proj.org/$it", Caches.dir("geoids/${it.removeSuffix(".tif")}")) }

    /**
     * Per-pixel meters to add to national heights to get EGM2008 ([Datums.nationalToEgm2008]),
     * computed on a 16-pixel lattice aligned to the window origin and interpolated (both geoids vary
     * over kilometres). NaN where the national grid has no value.
     */
    fun offsets(window: MercatorWindow, geoid: NationalGeoid): FloatArray {
        val national = grid(geoid)
        val step = 16
        val lw = (window.width - 1) / step + 2
        val lh = (window.height - 1) / step + 2
        val west = window.lonAt(0)
        val east = window.lonAt((lw - 1) * step)
        val south = window.latAt((lh - 1) * step)
        val north = window.latAt(0)
        national.prefetch(west, south, east, north)
        Geoids.egm2008.prefetch(west, south, east, north)
        val lattice = DoubleArray(lw * lh)
        for (b in 0 until lh) for (a in 0 until lw) {
            lattice[b * lw + a] = Datums.nationalToEgm2008(window.latAt(b * step), window.lonAt(a * step), national, Geoids.egm2008, geoid.nad83Frame)
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

    /**
     * Adds [offsets] to [values] in place (NaN offset: the cell can't be put in EGM2008 and becomes
     * NaN). Returns the mean offset applied in meters, cells converted, and cells left unconverted.
     */
    fun applyOffsets(values: FloatArray, offsets: FloatArray): Triple<Double, Long, Long> {
        var sum = 0.0
        var n = 0L
        var unconverted = 0L
        for (k in values.indices) {
            if (values[k].isNaN()) continue
            val o = offsets[k]
            if (o.isNaN()) {
                values[k] = Float.NaN
                unconverted++
            } else {
                values[k] += o
                sum += o
                n++
            }
        }
        return Triple(if (n == 0L) 0.0 else sum / n, n, unconverted)
    }

    /** A fixed pool of [MAX_PARALLEL] daemon threads, so one source never has more requests in flight to its host. */
    class IoPool(name: String) {
        private val pool = Executors.newFixedThreadPool(MAX_PARALLEL) { r -> Thread(r, name).apply { isDaemon = true } }

        /** [f] over [items] on the pool; rethrows the first failure. Tasks must not wait on other tasks of the same pool. */
        fun <T, R> map(items: Collection<T>, f: (T) -> R): List<R> =
            items.map { pool.submit(Callable { f(it) }) }.map {
                try {
                    it.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
    }
}
