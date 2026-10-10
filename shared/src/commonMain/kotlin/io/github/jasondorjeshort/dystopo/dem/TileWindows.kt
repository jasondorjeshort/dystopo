package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.ceil

/**
 * One XYZ tile rendered through a [MercatorWindow] that is [halo] cells bigger on every side.
 *
 * Compositing is not a per-cell operation: [Composite.over] weights each cell by its distance to
 * the nearest no-data cell of the top layer, so a cell near a tile edge needs to see the coverage
 * pattern beyond the edge to get the same weight it would get in one big render. Rendering the
 * haloed window and cropping back to the tile ([interior]) makes every tile's cells identical to a
 * seamless whole-area render, so adjacent tiles agree exactly along their shared borders.
 */
data class TileWindow(val tile: TileId, val halo: Int, val tileSize: Int = 256) {
    init {
        require(halo >= 0) { "halo must be >= 0, got $halo" }
        require(tileSize > 0) { "tileSize must be positive" }
    }

    /** The tile plus its halo, in global pixel coordinates at the tile's zoom. */
    val window: MercatorWindow = MercatorWindow(
        zoom = tile.z,
        x0 = tile.x * tileSize - halo,
        y0 = tile.y * tileSize - halo,
        width = tileSize + 2 * halo,
        height = tileSize + 2 * halo,
        tileSize = tileSize,
    )

    /** Crops a grid rendered through [window] back to the tile's [tileSize] x [tileSize] cells. */
    fun interior(grid: ElevationGrid): ElevationGrid {
        require(grid.width == window.width && grid.height == window.height) {
            "grid ${grid.width}x${grid.height} does not match window ${window.width}x${window.height}"
        }
        // The halo is symmetric, so the window's middle row is the tile's middle row and the
        // cropped grid keeps the right cellSizeMeters.
        return grid.crop(halo, halo, tileSize, tileSize)
    }
}

object TileWindows {
    /**
     * Halos are rounded up to a multiple of this many cells. Reason: the datum-conversion step in
     * the devtool sources interpolates geoid offsets on a lattice with a 16-cell step anchored at
     * the window origin. Tile origins are multiples of the tile size, so a halo that is a multiple
     * of 16 keeps that lattice aligned to global pixel coordinates in every window, and the
     * interpolated offset for a given cell is the same no matter which tile's window produced it.
     */
    const val HALO_ALIGN = 16

    /**
     * Halo (cells) that makes [Composite.over] with a blend band of [featherMeters] window-independent
     * for cells of [cellSizeMeters].
     *
     * Why `ceil(featherMeters / cellSizeMeters)` is enough: the weight of a cell saturates at 1 once
     * its distance to the nearest no-data cell reaches `featherCells`. The two-pass chamfer transform
     * in [Composite.distanceToNoData] is exact within a rectangle, and any no-data cell outside the
     * window is at least `halo + 1` cells from every interior cell. So for an interior cell, either
     * its nearest no-data cell is inside the window and the distance is exact, or every no-data
     * cell is at distance >= halo + 1 > featherCells in both the windowed and the global transform,
     * and the weight is 1 either way. A minimum of one cell keeps tile-edge cells from ever being
     * treated as "adjacent to the window boundary". The result is then rounded up to [HALO_ALIGN].
     */
    fun haloCells(featherMeters: Double, cellSizeMeters: Double, align: Int = HALO_ALIGN): Int {
        require(cellSizeMeters > 0) { "cellSizeMeters must be positive" }
        require(align >= 1) { "align must be >= 1" }
        val needed = maxOf(1, ceil(featherMeters / cellSizeMeters).toInt())
        return (needed + align - 1) / align * align
    }

    /**
     * One ground cell size to use for a whole zoom level of an export: the cell size of the area's
     * window at that zoom (taken at the area's middle latitude). Blend bands and halos derived from a
     * per-tile cell size would differ slightly between tile rows, and adjacent tiles would then blend
     * the same cell with different weights.
     */
    fun referenceCellSizeMeters(bbox: BoundingBox, zoom: Int, tileSize: Int = 256): Double =
        MercatorWindow.forBbox(bbox, zoom, tileSize).cellSizeMeters

    /** Tile windows for every tile at [zoom] that intersects [bbox], with a halo sized for [featherMeters]. */
    fun forBbox(bbox: BoundingBox, zoom: Int, featherMeters: Double, tileSize: Int = 256): List<TileWindow> {
        val halo = haloCells(featherMeters, referenceCellSizeMeters(bbox, zoom, tileSize))
        return WebMercator.tilesCovering(bbox, zoom).map { TileWindow(it, halo, tileSize) }
    }

    /**
     * Terrarium ARGB pixels for [grid], row-major. Terrarium has no no-data value, so NaN cells are
     * written as [noDataMeters] (default 0 m: in the `best` source the only no-data left is open
     * ocean, where 0 is right).
     */
    fun encodeTerrarium(grid: ElevationGrid, noDataMeters: Float = 0f): IntArray =
        IntArray(grid.values.size) { i ->
            val v = grid.values[i]
            Terrarium.encode(if (v.isNaN()) noDataMeters else v)
        }
}
