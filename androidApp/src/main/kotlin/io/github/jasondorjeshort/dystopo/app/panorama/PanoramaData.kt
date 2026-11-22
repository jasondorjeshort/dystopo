package io.github.jasondorjeshort.dystopo.app.panorama

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Pack
import io.github.jasondorjeshort.dystopo.app.TileService
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonData
import io.github.jasondorjeshort.dystopo.app.viewshed.ViewshedText
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Peak
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.horizon.LayeredSkyline
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * T326: what the Panorama draws from, worked out off the main thread (wave 45's choice (f)): the terrain read to the
 * `viewRange` setting as `viewshed/ViewshedSession.kt` reads it ([Viewshed.plan], [Viewshed.terrain] over the offline
 * maps' elevation; the Horizon view's own terrain stops at 30 km), the skylines swept round the whole circle at the steps
 * the zoom asks for ([LayeredSkyline.sweep], coarse first so a drag round the circle never waits on a sweep, then the
 * step fine enough for the zoom), and the named peaks within the range tested on the farthest skyline.
 *
 * The scenes of two places are kept ([Place]: the fix's and one other), each keyed by the place within [SAME_PLACE_M],
 * the range and the step; a changed `viewRange` makes a new place and sweeps again. [revision] is Compose state bumped on
 * the main thread when a sweep or the peaks land, so the view reads again.
 */
object PanoramaData {
    /** Two requests within this of each other (metres) are the same place. */
    const val SAME_PLACE_M = 100.0

    /** How many places are kept: the fix's and one other. */
    const val KEEP = 2

    var revision by mutableStateOf(0)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-panorama").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    val sweepsRun = AtomicLong()
    val terrainReads = AtomicLong()

    /** One sweep done: its [stepDeg], the samples and its time (ms), and when it landed after the request (ms). */
    class Sweep(val stepDeg: Double, val samples: Long, val ms: Long, val atMs: Long)

    /**
     * One place's work: the terrain (or why there is none), the tiles and bytes read for it and whether any came from the
     * network (never: offline maps only, as the viewshed reads them), the skylines by step, the peaks.
     */
    class Place(val lat: Double, val lon: Double, val rangeM: Double, val isFix: Boolean) {
        val createdMs = SystemClock.elapsedRealtime()
        @Volatile var usedMs = createdMs
        @Volatile var terrain: Terrain? = null
        @Volatile var reason: String? = null
        @Volatile var terrainMs = 0L
        @Volatile var tilesRead = 0
        @Volatile var bytesRead = 0L
        @Volatile var demPacks: List<String> = emptyList()
        val skylines = java.util.concurrent.ConcurrentHashMap<Double, LayeredSkyline>()
        val sweeps = java.util.concurrent.CopyOnWriteArrayList<Sweep>()
        internal val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<Double>()
        @Volatile var found: List<Peak>? = null
        @Volatile var peakTiles = 0
        @Volatile var peaks: HorizonData.Peaks? = null
        @Volatile var peaksStep: Double? = null
        @Volatile var state: Gnss.HorizonState? = null

        /** The finest skyline swept so far, or null. */
        val finest: LayeredSkyline? get() = skylines.keys.minOrNull()?.let { skylines[it] }

        fun same(lat: Double, lon: Double, rangeM: Double): Boolean =
            rangeM == this.rangeM && TrackStats.distanceM(this.lat, this.lon, lat, lon) <= SAME_PLACE_M

        fun json(): Map<String, Any?> = linkedMapOf(
            "lat" to lat, "lon" to lon, "rangeKm" to rangeM / 1000, "fix" to isFix, "reason" to reason,
            "terrain" to linkedMapOf("ms" to terrainMs, "tiles" to tilesRead, "bytes" to bytesRead, "network" to false, "demPacks" to demPacks,
                "layers" to terrain?.layers?.map { l -> linkedMapOf("zoom" to l.window.zoom, "width" to l.window.width, "height" to l.window.height) }),
            "sweeps" to sweeps.map { s -> linkedMapOf("stepDeg" to s.stepDeg, "samples" to s.samples, "ms" to s.ms, "atMs" to s.atMs) },
            "pending" to pending.sorted(),
            "peaks" to linkedMapOf("tiles" to peakTiles, "read" to found?.size, "candidates" to peaks?.candidates?.size, "testedOnStepDeg" to peaksStep, "ms" to peaks?.ms),
        )
    }

    private val places = java.util.concurrent.CopyOnWriteArrayList<Place>()

    /** The places kept, most recently used first (`GET /panorama`). */
    val kept: List<Place> get() = places.sortedByDescending { it.usedMs }

    /**
     * The place at ([lat], [lon]) to [rangeM] ([isFix]: the view stands at the phone's fix), its missing [steps] queued in
     * order (the coarse first). Main thread; never blocks. A place of another range is dropped (the setting changed).
     */
    fun request(lat: Double, lon: Double, rangeM: Double, steps: List<Double>, isFix: Boolean): Place {
        places.removeAll { it.rangeM != rangeM }
        val p = places.firstOrNull { it.same(lat, lon, rangeM) } ?: Place(lat, lon, rangeM, isFix).also { n ->
            places += n
            while (places.size > KEEP) {
                val drop = places.filter { it !== n }.sortedWith(compareBy<Place> { it.isFix }.thenBy { it.usedMs }).first()
                places.remove(drop)
            }
        }
        p.usedMs = SystemClock.elapsedRealtime()
        for (s in steps) if (!p.skylines.containsKey(s) && p.pending.add(s)) worker.execute { work(p, s) }
        return p
    }

    private fun work(p: Place, step: Double) {
        try {
            if (!places.contains(p)) return
            if (p.terrain == null && p.reason == null) readTerrain(p)
            val terrain = p.terrain ?: return
            val t0 = SystemClock.elapsedRealtime()
            val sky = LayeredSkyline.sweep(terrain, p.lat, p.lon, LayeredSkyline.ranges(p.rangeM), step, Viewshed.EYE_HEIGHT_M)
            sweepsRun.incrementAndGet()
            p.skylines[step] = sky
            p.sweeps += Sweep(step, sky.profiles.sumOf { it.samples }, SystemClock.elapsedRealtime() - t0, SystemClock.elapsedRealtime() - p.createdMs)
            val finestStep = p.skylines.keys.min()
            p.state = Gnss.HorizonState(p.lat, p.lon, p.finest!!.outer, null, "offline map", null, p.terrainMs + (SystemClock.elapsedRealtime() - t0), terrain)
            // The peaks: read once per place and range, tested again on each finer skyline.
            if (p.found == null) {
                val (found, tiles) = HorizonData.readPeaksAt(p.lat, p.lon, p.rangeM, Viewshed.peakZoom(p.lat, p.rangeM))
                p.found = found
                p.peakTiles = tiles
            }
            if (p.peaksStep == null || finestStep < p.peaksStep!!) {
                p.peaks = HorizonData.peaksOn(p.state!!, terrain, p.finest!!.outer, p.found.orEmpty(), p.peakTiles)
                p.peaksStep = finestStep
            }
        } catch (e: Exception) {
            android.util.Log.w("Dystopo", "panorama sweep failed", e)
            if (p.terrain == null) p.reason = "${ViewshedText.NO_DEM} (${e.javaClass.simpleName})"
        } finally {
            p.pending.remove(step)
            main.post { revision++ }
        }
    }

    private fun readTerrain(p: Place) {
        val t0 = SystemClock.elapsedRealtime()
        terrainReads.incrementAndGet()
        val app = DystopoApp.instance
        val plan = Viewshed.plan(p.lat, p.lon, p.rangeM)
        val packs = app.tiles.packs.demPacks
        val pyramids = HashMap<Pack, DemPyramid>()
        val used = LinkedHashSet<String>()
        val terrain = Viewshed.terrain(plan) { w -> readMerged(w, packs, pyramids, used, p) }
        p.terrainMs = SystemClock.elapsedRealtime() - t0
        p.demPacks = used.toList()
        if (terrain.elevationAt(p.lat, p.lon).isNaN()) p.reason = ViewshedText.NO_DEM else p.terrain = terrain
    }

    /** [w] from every elevation pack overlapping it, the first with data winning per cell (as `ViewshedSession` reads it), counting tiles and bytes. */
    private fun readMerged(w: MercatorWindow, packs: List<Pack>, pyramids: HashMap<Pack, DemPyramid>, used: MutableSet<String>, place: Place): ElevationGrid {
        val west = WebMercator.xToLon(w.x0.toDouble() / w.tileSize, w.zoom)
        val east = WebMercator.xToLon((w.x0 + w.width).toDouble() / w.tileSize, w.zoom)
        val north = WebMercator.yToLat(w.y0.toDouble() / w.tileSize, w.zoom)
        val south = WebMercator.yToLat((w.y0 + w.height).toDouble() / w.tileSize, w.zoom)
        val values = FloatArray(w.width * w.height) { Float.NaN }
        var missing = values.size
        for (pk in packs) {
            if (missing == 0) break
            val h = pk.header
            if (h.maxLon < west || h.minLon > east || h.maxLat < south || h.minLat > north) continue
            val pyr = pyramids.getOrPut(pk) {
                DemPyramid(h.minZoom, h.maxZoom, TileService.demDecoder(), cacheTiles = 48) { z, x, y ->
                    pk.tile(z, x, y)?.also { b -> place.tilesRead++; place.bytesRead += b.size }
                }
            }
            val g = pyr.window(w.zoom, w.x0, w.y0, w.width, w.height)
            var took = false
            for (k in values.indices) {
                if (values[k].isNaN() && !g.values[k].isNaN()) { values[k] = g.values[k]; missing--; took = true }
            }
            if (took) used += DystopoApp.instance.tiles.packs.packOf(pk) ?: pk.name
        }
        return ElevationGrid(w.width, w.height, w.cellSizeMeters, values)
    }
}
