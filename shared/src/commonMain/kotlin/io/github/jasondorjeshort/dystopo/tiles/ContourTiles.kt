package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.MvtEncoder
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.render.ContourSimplify
import io.github.jasondorjeshort.dystopo.render.ContourSpec
import io.github.jasondorjeshort.dystopo.render.ContourStyle
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.Contours
import kotlin.jvm.Synchronized
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/** Parameters for [ContourTiles]. Pixel distances are in screen pixels (DEM pixels of [ContourTiles.demZoom]). */
data class ContourTileOptions(
    val units: ContourUnits = ContourUnits.FEET,
    /** Grid cells traced beyond each tile edge, so lines cross the seam and neighbours join without gaps. */
    val halo: Int = 4,
    /**
     * Screen pixels across one contour tile. 512 (the default) matches MapLibre's vector tiles, which are
     * drawn 512 px wide: contour tile z/x/y then has the [ContourStyle] spec of zoom z+1, the zoom whose
     * DEM pixels are screen pixels. 256 makes contour and DEM zooms equal (the devtool renderer's convention).
     */
    val tileSize: Int = 512,
    /** MVT extent (tile width in geometry units). */
    val extent: Int = 4096,
    /** Douglas-Peucker tolerance in screen pixels (the devtool renderer's default). */
    val simplify: Double = 0.5,
    /** Steep-slope cut of non-index lines in screen pixels, as in the devtool renderer (0 disables). */
    val minSpacing: Double = 3.0,
    val layerName: String = "contours",
    /**
     * Tiles of a zoom below this trace a grid one DEM zoom coarser than the screen (2 screen pixels per
     * cell, a quarter of the cells). See [ContourTiles.plan].
     */
    val halfResolutionBelow: Int = 13,
    /** Tiles of a zoom below this trace a grid two DEM zooms coarser than the screen (4 px per cell). */
    val quarterResolutionBelow: Int = 10,
)

/**
 * How contour tile z/x/y is made: the [ContourStyle] spec zoom ([specZoom], where one DEM pixel is
 * one screen pixel), the DEM zoom actually sampled ([sampleZoom] = [specZoom] - [reduction]), and the
 * sampled window in global pixels of [sampleZoom] (the tile, [gridTileSize] cells across, plus a halo).
 */
data class ContourTilePlan(
    val z: Int,
    val x: Int,
    val y: Int,
    val specZoom: Int,
    val reduction: Int,
    val gridTileSize: Int,
    val halo: Int,
    val windowX: Int,
    val windowY: Int,
) {
    val sampleZoom: Int get() = specZoom - reduction
    val windowSize: Int get() = gridTileSize + 2 * halo

    /** Screen pixels per traced grid cell. */
    val cellScale: Int get() = 1 shl reduction
}

/** Time spent in each step of [ContourTiles.build], in milliseconds. */
data class ContourBuildTimings(val traceMs: Double, val cutMs: Double, val simplifyMs: Double, val encodeMs: Double)

/** One generated contour tile: MVT bytes plus counters for reports. */
class ContourTile(
    val bytes: ByteArray,
    val spec: ContourSpec,
    val lines: Int,
    val indexLines: Int,
    val points: Int,
    val timings: ContourBuildTimings? = null,
)

/**
 * Vector contour tiles from a DEM, the on-device path of docs/PLANNING.md ("contours: generate on
 * the fly"): for tile z/x/y, sample the tile plus a small halo from the [DemPyramid], trace the
 * [ContourStyle] levels for that zoom, cut steep non-index lines, simplify, and encode one MVT
 * layer whose features are linestrings with `ele` (the level in [ContourTileOptions.units], an
 * integer) and `level` (1 for index contours, 0 otherwise) - the attributes the browser demo's
 * maplibre-contour tiles carry, so the same MapLibre style works for both.
 *
 * The contour density on screen matches `render --zoom <specZoom>` of the devtool. At the detailed
 * zooms one DEM pixel is one screen pixel; lower zooms trace a coarser grid (see [plan]), because
 * their intervals are wide (400 ft and more) and the lines they draw are smooth at 2 or 4 screen
 * pixels per cell, while tracing and sampling cost grow with the cell count.
 */
object ContourTiles {
    /**
     * The spec zoom of contour tile zoom [z]: [z] plus log2(tileSize / [demTileSize]), the DEM zoom whose
     * pixels are screen pixels, which picks the [ContourStyle] levels.
     */
    fun demZoom(z: Int, options: ContourTileOptions = ContourTileOptions(), demTileSize: Int = 256): Int {
        var shift = 0
        while ((demTileSize shl shift) < options.tileSize) shift++
        require((demTileSize shl shift) == options.tileSize) { "tileSize ${options.tileSize} is not $demTileSize times a power of two" }
        return z + shift
    }

    /**
     * How many DEM zooms below [demZoom] tile zoom [z] samples: 0 at [ContourTileOptions.halfResolutionBelow]
     * and above, 1 below it, 2 below [ContourTileOptions.quarterResolutionBelow]. The table follows the
     * [ContourStyle] intervals: from z13 tiles (spec z14, 80 ft) on, every screen pixel matters; z10 to z12
     * tiles draw 200 to 1000 ft lines that are 2-cell smooth; z9 and below draw 2000 ft lines only.
     */
    fun reduction(z: Int, options: ContourTileOptions = ContourTileOptions()): Int = when {
        z < options.quarterResolutionBelow -> 2
        z < options.halfResolutionBelow -> 1
        else -> 0
    }

    /**
     * Contour tiles worth generating in the background as soon as a pack opens, so the first views are
     * never cold: the tiles covering [bounds] at contour zooms [demMinZoom] and up, [zoomCount] zooms (the
     * pack's lowest DEM zooms; contour tile z draws at map zoom z to z+1, so these are the fitted
     * whole-pack view and the first zoom-in). Ordered by zoom, then by distance from the centre of
     * [bounds], and capped at [maxTiles] (the far edge of the highest zoom goes first). Then, up to
     * [centreUpTo], the one tile holding the centre at each higher zoom: the view a pack opens on, and
     * one run of every sampling path (full-resolution tracing, the deepest DEM tiles) so the JIT and the
     * DEM cache are warm before the user zooms in.
     */
    fun warmupPlan(bounds: BoundingBox, demMinZoom: Int, zoomCount: Int = 2, maxTiles: Int = 256, centreUpTo: Int = -1): List<TileId> {
        require(zoomCount >= 0 && maxTiles >= 0) { "zoomCount and maxTiles must be >= 0" }
        val centres = (demMinZoom + zoomCount..centreUpTo).map { z ->
            val n = 1 shl z
            TileId(z, WebMercator.lonToX(bounds.centerLon, z).toInt().mod(n), WebMercator.latToY(bounds.centerLat, z).toInt().coerceIn(0, n - 1))
        }
        val out = ArrayList<TileId>()
        for (z in demMinZoom until demMinZoom + zoomCount) {
            val cx = WebMercator.lonToX(bounds.centerLon, z)
            val cy = WebMercator.latToY(bounds.centerLat, z)
            out += WebMercator.tilesCovering(bounds, z).sortedBy { t ->
                // Unwrapped column for boxes across the antimeridian, so distances stay local.
                val x = if (t.x + 0.5 < cx - (1 shl z) / 2.0) t.x + (1 shl z) else t.x
                val dx = x + 0.5 - cx
                val dy = t.y + 0.5 - cy
                dx * dx + dy * dy
            }
        }
        val capped = if (out.size > maxTiles) out.subList(0, maxTiles).toList() else out
        return capped + centres
    }

    /** The plan for tile z/x/y; see [ContourTilePlan]. */
    fun plan(z: Int, x: Int, y: Int, options: ContourTileOptions = ContourTileOptions(), demTileSize: Int = 256): ContourTilePlan {
        val specZoom = demZoom(z, options, demTileSize)
        val r = reduction(z, options).coerceAtMost(specZoom)
        val grid = options.tileSize shr r
        require(grid >= 16 && (grid shl r) == options.tileSize) { "tileSize ${options.tileSize} too small for reduction $r" }
        val h = options.halo
        return ContourTilePlan(z, x, y, specZoom, r, grid, h, x * grid - h, y * grid - h)
    }

    /** Samples the plan's window from [dem]: the grid [build] expects. Thread-safe when [dem] is. */
    fun sample(dem: DemPyramid, plan: ContourTilePlan): ElevationGrid =
        dem.window(plan.sampleZoom, plan.windowX, plan.windowY, plan.windowSize, plan.windowSize)

    /** Samples the window from [dem] and builds contour tile z/x/y; see [build]. */
    fun generate(dem: DemPyramid, z: Int, x: Int, y: Int, options: ContourTileOptions = ContourTileOptions()): ContourTile {
        val plan = plan(z, x, y, options, dem.tileSize)
        return build(sample(dem, plan), plan, options)
    }

    /**
     * Builds a contour tile from [grid], the window of [plan] (tile plus halo on every side) sampled at
     * [ContourTilePlan.sampleZoom]; the [ContourStyle] spec of [ContourTilePlan.specZoom] sets the levels.
     * Pure and thread-safe: tiles can be built concurrently.
     */
    fun build(grid: ElevationGrid, plan: ContourTilePlan, options: ContourTileOptions = ContourTileOptions()): ContourTile {
        val ts = plan.gridTileSize
        val h = plan.halo
        require(grid.width == plan.windowSize && grid.height == plan.windowSize) {
            "grid ${grid.width}x${grid.height} is not a ${ts}px tile with a ${h}px halo"
        }
        val f = plan.cellScale.toDouble()
        val clock = TimeSource.Monotonic
        val t0 = clock.markNow()
        val spec = ContourStyle.forZoom(plan.specZoom, options.units)
        val levels = spec.levelsMeters(grid.min().toDouble(), grid.max().toDouble())
        val traced = Contours.trace(grid, levels)
        val t1 = clock.markNow()
        // Spacing and piece length are screen pixels; the grid has f screen pixels per cell.
        val cut = Contours.cutSteep(traced, grid, spec, options.minSpacing / f, minPieceLength = 12.0 / f)
        val t2 = clock.markNow()
        val simplified = ContourSimplify.simplifyAll(cut, options.simplify / f)
        val t3 = clock.markNow()

        // Grid sample (i, j) is the centre of grid cell (i, j) of the window; the tile starts at cell h.
        val scale = options.extent.toDouble() / ts
        val features = ArrayList<Feature>(simplified.size)
        var points = 0
        var index = 0
        for (line in simplified) {
            val pts = ArrayList<TilePoint>(line.pointCount + 1)
            for (i in 0 until line.pointCount) {
                val p = TilePoint(
                    ((line.points[2 * i] - h + 0.5) * scale).roundToInt(),
                    ((line.points[2 * i + 1] - h + 0.5) * scale).roundToInt(),
                )
                if (pts.isEmpty() || pts.last() != p) pts += p
            }
            if (line.closed && pts.size >= 3 && pts.first() != pts.last()) pts += pts.first()
            if (pts.size < 2) continue
            val isIndex = spec.isIndex(line.level)
            if (isIndex) index++
            points += pts.size
            features += Feature(
                id = null,
                properties = linkedMapOf(
                    "ele" to Value.IntValue((line.level / spec.units.meters).roundToLong()),
                    "level" to Value.IntValue(if (isIndex) 1 else 0),
                ),
                geometry = Geometry.Lines(listOf(pts)),
            )
        }
        val bytes = MvtEncoder.encode(VectorTile(listOf(Layer(options.layerName, features, options.extent))))
        val t4 = clock.markNow()
        val timings = ContourBuildTimings(
            traceMs = (t1 - t0).inWholeMicroseconds / 1000.0,
            cutMs = (t2 - t1).inWholeMicroseconds / 1000.0,
            simplifyMs = (t3 - t2).inWholeMicroseconds / 1000.0,
            encodeMs = (t4 - t3).inWholeMicroseconds / 1000.0,
        )
        return ContourTile(bytes, spec, features.size, index, points, timings)
    }
}

/**
 * Contour generation counters for `/stats` (the Android app and `devtool serve` report the same
 * shape): per tile zoom min/avg/max milliseconds with the sample/trace split, how many tiles were
 * generating at once, and the most recent tiles. Thread-safe.
 */
class ContourTileStats(private val keepRecent: Int = 20) {
    private class ZoomTiming {
        var count = 0L
        var total = 0.0
        var sample = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = 0.0
    }

    private val byZoom = HashMap<Int, ZoomTiming>()
    private val recent = ArrayDeque<Map<String, Any?>>()
    private var inFlight = 0
    private var maxInFlight = 0
    private var count = 0L
    private var total = 0.0
    private var min = Double.POSITIVE_INFINITY
    private var max = 0.0

    /** Marks the start of one tile's generation; returns how many are generating now, this one included. */
    @Synchronized
    fun begin(): Int {
        inFlight++
        if (inFlight > maxInFlight) maxInFlight = inFlight
        return inFlight
    }

    /** Marks the end of a generation started with [begin] that failed or produced nothing. */
    @Synchronized
    fun abort() {
        inFlight--
    }

    /**
     * Records one generated tile and ends its [begin]: [totalMs] from request to bytes, [sampleMs] the DEM
     * sampling part, [concurrent] the value [begin] returned.
     */
    @Synchronized
    fun end(plan: ContourTilePlan, tile: ContourTile, totalMs: Double, sampleMs: Double, concurrent: Int, waitMs: Double = 0.0) {
        inFlight--
        count++
        total += totalMs
        if (totalMs < min) min = totalMs
        if (totalMs > max) max = totalMs
        val zt = byZoom.getOrPut(plan.z) { ZoomTiming() }
        zt.count++
        zt.total += totalMs
        zt.sample += sampleMs
        if (totalMs < zt.min) zt.min = totalMs
        if (totalMs > zt.max) zt.max = totalMs
        val t = tile.timings
        recent.addLast(
            linkedMapOf(
                "tile" to "${plan.z}/${plan.x}/${plan.y}", "sampleZoom" to plan.sampleZoom, "grid" to plan.windowSize,
                "ms" to round1(totalMs), "waitMs" to round1(waitMs), "sampleMs" to round1(sampleMs),
                "traceMs" to t?.let { round1(it.traceMs) }, "cutMs" to t?.let { round1(it.cutMs) },
                "simplifyMs" to t?.let { round1(it.simplifyMs) }, "encodeMs" to t?.let { round1(it.encodeMs) },
                "concurrent" to concurrent, "lines" to tile.lines, "points" to tile.points, "bytes" to tile.bytes.size,
                "interval" to tile.spec.interval,
            ),
        )
        while (recent.size > keepRecent) recent.removeFirst()
    }

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "count" to count,
        "minMs" to if (count == 0L) null else round1(min),
        "avgMs" to if (count == 0L) null else round1(total / count),
        "maxMs" to if (count == 0L) null else round1(max),
        "totalMs" to round1(total),
        "inFlight" to inFlight,
        "maxConcurrent" to maxInFlight,
        "byZoom" to byZoom.keys.sorted().associate { z ->
            val t = byZoom.getValue(z)
            z.toString() to linkedMapOf(
                "count" to t.count, "minMs" to round1(t.min), "avgMs" to round1(t.total / t.count), "maxMs" to round1(t.max),
                "avgSampleMs" to round1(t.sample / t.count),
            )
        },
        "recent" to recent.toList(),
    )

    private fun round1(d: Double) = (d * 10).roundToLong() / 10.0
}

/**
 * Progress of a background contour warm-up ([ContourTiles.warmupPlan]) for `/stats` (`warmup`):
 * state, tiles planned and done (generated, found already cached, failed), time spent and time spent
 * paused while the map's own tile requests were generating. Thread-safe.
 */
class ContourWarmupProgress(val zooms: List<Int>, val planned: Int) {
    enum class State { RUNNING, DONE, STOPPED }

    private val clock = TimeSource.Monotonic
    private val started = clock.markNow()
    private var state = State.RUNNING
    private var finishedMs: Double? = null
    private var generated = 0
    private var cached = 0
    private var failed = 0
    private var pausedMs = 0.0
    private var lastError: String? = null

    val done: Int @Synchronized get() = generated + cached + failed

    @Synchronized
    fun generated() {
        generated++
    }

    @Synchronized
    fun cached() {
        cached++
    }

    @Synchronized
    fun failed(error: Throwable) {
        failed++
        lastError = error.toString()
    }

    @Synchronized
    fun paused(ms: Double) {
        pausedMs += ms
    }

    /** Ends the warm-up: [State.DONE] when every planned tile was handled, else [State.STOPPED]. */
    @Synchronized
    fun finish(stopped: Boolean) {
        if (state != State.RUNNING) return
        state = if (stopped) State.STOPPED else State.DONE
        finishedMs = elapsedMs()
    }

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "state" to state.name.lowercase(),
        "zooms" to zooms,
        "planned" to planned,
        "done" to done,
        "generated" to generated,
        "alreadyCached" to cached,
        "failed" to failed,
        "elapsedMs" to round1(finishedMs ?: elapsedMs()),
        "pausedForMapMs" to round1(pausedMs),
        "lastError" to lastError,
    )

    private fun elapsedMs() = started.elapsedNow().inWholeMicroseconds / 1000.0

    private fun round1(d: Double) = (d * 10).roundToLong() / 10.0
}
