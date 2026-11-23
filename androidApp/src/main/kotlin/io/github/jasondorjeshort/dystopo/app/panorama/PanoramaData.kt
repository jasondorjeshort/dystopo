package io.github.jasondorjeshort.dystopo.app.panorama

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonData
import io.github.jasondorjeshort.dystopo.app.gnss.GnssText
import io.github.jasondorjeshort.dystopo.app.terrain.TerrainRead
import io.github.jasondorjeshort.dystopo.dem.Peak
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.horizon.LayeredSkyline
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * T326: what the Panorama draws from, worked out off the main thread (wave 45's choice (f)): the terrain read to the
 * `viewRange` setting on the viewshed's plan ([Viewshed.plan], [Viewshed.terrain]; the Horizon view's own terrain stops at
 * 30 km) through T340's merged read ([TerrainRead]: the open maps cell by cell, the deepest first, then the online source,
 * as the Horizon view reads it), the skylines swept round the whole circle at the steps
 * the zoom asks for ([LayeredSkyline.sweep], coarse first so a drag round the circle never waits on a sweep, then the
 * step fine enough for the zoom), and the named peaks within the range tested on the farthest skyline.
 *
 * The scenes of two places are kept ([Place]: the fix's and one other), each keyed by the place within [SAME_PLACE_M],
 * the range and the step; a changed `viewRange` makes a new place and sweeps again. [revision] is Compose state bumped on
 * the main thread when a sweep or the peaks land, so the view reads again. T340: a place read before a map opened or
 * closed, or lacking cells (or none) before the network came back, is dropped and read again ([TerrainRead.Epoch]).
 */
object PanoramaData {
    /** Two requests within this of each other (metres) are the same place. */
    const val SAME_PLACE_M = 100.0

    /** How many places are kept: the fix's and one other. */
    const val KEEP = 2

    var revision by mutableStateOf(0)
        private set

    /** Makes the Compose state on the main thread (`MainActivity.onCreate`), before the worker bumps it (T226). */
    fun prepare() {
        revision
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-panorama").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    val sweepsRun = AtomicLong()
    val terrainReads = AtomicLong()

    /** One sweep done: its [stepDeg], the samples and its time (ms), and when it landed after the request (ms). */
    class Sweep(val stepDeg: Double, val samples: Long, val ms: Long, val atMs: Long)

    /**
     * One place's work: the terrain (or why there is none), the tiles and bytes read for it and whether any came from the
     * network (T340: the merged read's [answered]), the skylines by step, the peaks.
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
        /** T340: the maps open and the network's generation when the place was made, and what its read took cells from. */
        val epoch: TerrainRead.Epoch = TerrainRead.epoch()
        @Volatile var answered: TerrainRead.Answered? = null
        internal val watched = java.util.concurrent.atomic.AtomicBoolean(false)
        val skylines = java.util.concurrent.ConcurrentHashMap<Double, LayeredSkyline>()
        val sweeps = java.util.concurrent.CopyOnWriteArrayList<Sweep>()
        internal val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<Double>()
        @Volatile var found: List<Peak>? = null
        @Volatile var peakTiles = 0
        @Volatile var peaks: HorizonData.Peaks? = null
        @Volatile var peaksStep: Double? = null
        @Volatile var state: Gnss.HorizonState? = null

        /** T340: read again now: a map opened or closed since, or the network came back and this read lacked cells or found none. */
        fun stale(now: TerrainRead.Epoch): Boolean =
            pending.isEmpty() && (terrain != null || reason != null) && epoch.stale(now, terrain != null && answered?.complete != false)

        /** The finest skyline swept so far, or null. */
        val finest: LayeredSkyline? get() = skylines.keys.minOrNull()?.let { skylines[it] }

        fun same(lat: Double, lon: Double, rangeM: Double): Boolean =
            rangeM == this.rangeM && TrackStats.distanceM(this.lat, this.lon, lat, lon) <= SAME_PLACE_M

        fun json(): Map<String, Any?> = linkedMapOf(
            "lat" to lat, "lon" to lon, "rangeKm" to rangeM / 1000, "fix" to isFix, "reason" to reason,
            "terrain" to (linkedMapOf<String, Any?>("ms" to terrainMs, "tiles" to tilesRead, "bytes" to bytesRead, "network" to (answered?.online == true), "demPacks" to demPacks,
                "layers" to terrain?.layers?.map { l -> linkedMapOf("zoom" to l.window.zoom, "width" to l.window.width, "height" to l.window.height) }) + (answered?.json() ?: emptyMap()) + (if (terrain == null) mapOf("text" to null) else emptyMap())), // T340: no terrain horizon, no source named
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
        val now = TerrainRead.epoch()
        places.removeAll { it.stale(now) } // T340
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
            p.state = Gnss.HorizonState(p.lat, p.lon, p.finest!!.outer, null, p.answered?.text, null, p.terrainMs + (SystemClock.elapsedRealtime() - t0), terrain, p.epoch, p.answered)
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
            if (p.terrain == null) p.reason = "${GnssText.NO_TERRAIN_ERROR} (${e.javaClass.simpleName})" // T340: the Horizon view's sentences
        } finally {
            p.pending.remove(step)
            main.post { revision++ }
            if (p.pending.isEmpty() && !(p.epoch.network && p.epoch.reachable) && (p.terrain == null || p.answered?.complete == false)) watch(p)
        }
    }

    /**
     * T340: a place read with no network that lacked cells (or found none) is looked at every [WATCH_MS] while kept, and the
     * view read again once the network is back ([Place.stale]): nothing else asks while the view stands still.
     */
    private fun watch(p: Place) {
        if (!p.watched.compareAndSet(false, true)) return
        val check = object : Runnable {
            override fun run() {
                if (!places.contains(p)) return
                if (p.stale(TerrainRead.epoch())) revision++ else main.postDelayed(this, WATCH_MS)
            }
        }
        main.postDelayed(check, WATCH_MS)
    }

    /** T340: how often a place read with no network looks for it, ms. */
    const val WATCH_MS = 2_000L

    /**
     * T340: [p]'s terrain by the merged read ([TerrainRead]), the Horizon view's; where there is none, the Horizon view's own
     * sentences ([GnssText.NO_TERRAIN]: no map and no online source; [GnssText.NO_TERRAIN_HERE]: none at the point). Before
     * T340 it read the offline maps alone and said the viewshed's "No elevation data here: download an offline map...".
     */
    private fun readTerrain(p: Place) {
        val t0 = SystemClock.elapsedRealtime()
        terrainReads.incrementAndGet()
        try {
            TerrainRead.open().use { r ->
                if (r.sources.isEmpty()) {
                    p.reason = GnssText.NO_TERRAIN
                    return
                }
                val terrain = Viewshed.terrain(Viewshed.plan(p.lat, p.lon, p.rangeM)) { w -> r.read(w) }
                val a = r.answered()
                p.answered = a
                p.tilesRead = r.tilesRead
                p.bytesRead = r.bytesRead
                p.demPacks = a.maps
                if (terrain.elevationAt(p.lat, p.lon).isNaN()) p.reason = GnssText.NO_TERRAIN_HERE else p.terrain = terrain
            }
        } finally {
            p.terrainMs = SystemClock.elapsedRealtime() - t0
        }
    }

    /** T340: the plot's line while it has no skyline: the place's sentence, "Reading the terrain..." or "Working out the skyline...". */
    fun waiting(p: Place?): String = when {
        p == null -> GnssText.NO_POSITION
        p.reason != null -> p.reason!!
        p.terrain == null -> PanoramaStrings.READING
        else -> PanoramaStrings.WORKING
    }
}
