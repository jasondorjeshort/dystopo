package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
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
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Parameters for [ContourTiles]. Pixel distances are in DEM pixels. */
data class ContourTileOptions(
    val units: ContourUnits = ContourUnits.FEET,
    /** DEM pixels traced beyond each tile edge, so lines cross the seam and neighbours join without gaps. */
    val halo: Int = 4,
    /**
     * DEM pixels across one contour tile. 512 (the default) matches MapLibre's vector tiles, which are
     * drawn 512 px wide: contour tile z/x/y is then traced from the 2x2 DEM tiles of zoom z+1 below it,
     * one DEM pixel per screen pixel, with the [ContourStyle] spec of zoom z+1. 256 makes contour and DEM
     * zooms equal (the devtool renderer's convention).
     */
    val tileSize: Int = 512,
    /** MVT extent (tile width in geometry units). */
    val extent: Int = 4096,
    /** Douglas-Peucker tolerance in DEM pixels (the devtool renderer's default). */
    val simplify: Double = 0.5,
    /** Steep-slope cut of non-index lines, as in the devtool renderer (0 disables). */
    val minSpacing: Double = 3.0,
    val layerName: String = "contours",
)

/** One generated contour tile: MVT bytes plus counters for reports. */
class ContourTile(
    val bytes: ByteArray,
    val spec: ContourSpec,
    val lines: Int,
    val indexLines: Int,
    val points: Int,
)

/**
 * Vector contour tiles from a DEM, the on-device path of docs/PLANNING.md ("contours: generate on
 * the fly"): for tile z/x/y, sample the tile plus a small halo from the [DemPyramid], trace the
 * [ContourStyle] levels for that zoom, cut steep non-index lines, simplify, and encode one MVT
 * layer whose features are linestrings with `ele` (the level in [ContourTileOptions.units], an
 * integer) and `level` (1 for index contours, 0 otherwise) - the attributes the browser demo's
 * maplibre-contour tiles carry, so the same MapLibre style works for both.
 *
 * The contour density on screen matches `render --zoom <DEM zoom>` of the devtool, because one DEM
 * pixel is one screen pixel (see [ContourTileOptions.tileSize]).
 */
object ContourTiles {
    /** The DEM zoom a contour tile of zoom [z] is traced at: [z] plus log2(tileSize / [demTileSize]). */
    fun demZoom(z: Int, options: ContourTileOptions = ContourTileOptions(), demTileSize: Int = 256): Int {
        var shift = 0
        while ((demTileSize shl shift) < options.tileSize) shift++
        require((demTileSize shl shift) == options.tileSize) { "tileSize ${options.tileSize} is not $demTileSize times a power of two" }
        return z + shift
    }

    /** The DEM window (global pixels at [demZoom]) a tile needs: the tile plus [ContourTileOptions.halo]. */
    fun windowFor(x: Int, y: Int, options: ContourTileOptions = ContourTileOptions()): IntArray {
        val ts = options.tileSize
        val h = options.halo
        return intArrayOf(x * ts - h, y * ts - h, ts + 2 * h, ts + 2 * h)
    }

    /** Samples the window from [dem] and builds contour tile z/x/y; see [build]. */
    fun generate(dem: DemPyramid, z: Int, x: Int, y: Int, options: ContourTileOptions = ContourTileOptions()): ContourTile {
        val dz = demZoom(z, options, dem.tileSize)
        val w = windowFor(x, y, options)
        return build(dem.window(dz, w[0], w[1], w[2], w[3]), dz, options)
    }

    /**
     * Builds a contour tile from [grid], the tile's window from [windowFor] (tile plus halo on every
     * side) sampled at DEM zoom [demZoom], whose [ContourStyle] spec sets the levels.
     */
    fun build(grid: ElevationGrid, demZoom: Int, options: ContourTileOptions = ContourTileOptions()): ContourTile {
        val ts = options.tileSize
        val h = options.halo
        require(grid.width == ts + 2 * h && grid.height == ts + 2 * h) {
            "grid ${grid.width}x${grid.height} is not a ${ts}px tile with a ${h}px halo"
        }
        val spec = ContourStyle.forZoom(demZoom, options.units)
        val levels = spec.levelsMeters(grid.min().toDouble(), grid.max().toDouble())
        val traced = Contours.trace(grid, levels)
        val cut = Contours.cutSteep(traced, grid, spec, options.minSpacing)
        val simplified = ContourSimplify.simplifyAll(cut, options.simplify)

        // Grid sample (i, j) is the centre of DEM pixel (i, j) of the window; the tile starts at pixel h.
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
        return ContourTile(bytes, spec, features.size, index, points)
    }
}
