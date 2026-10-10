package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Copernicus DEM GLO-30: global ~30 m, EGM2008 heights, 1°x1° Cloud-Optimized GeoTIFFs on AWS
 * Open Data. It's a surface model (DSM): heights include forest canopy and buildings, so it sits
 * above bare-earth DEMs like 3DEP in forests. Cells with no file (open ocean) are NaN.
 */
class CopernicusSource(private val cacheDir: File = Caches.dir("copernicus30")) : ElevationSource {
    override val id = "copernicus"
    override val registryIds = listOf("copernicus")

    /** Cells are named by their south-west corner. */
    private fun cellName(lat: Int, lon: Int): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return "Copernicus_DSM_COG_10_$ns${abs(lat).toString().padStart(2, '0')}_00_$ew${abs(lon).toString().padStart(3, '0')}_00_DEM"
    }

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val lons = DoubleArray(window.width) { window.lonAt(it) }
        val lats = DoubleArray(window.height) { window.latAt(it) }

        // Windows crossing the antimeridian run past 180 (see BoundingBox.unwrapped); cells are keyed
        // by wrapped longitude, and each cell prefetches the window's range shifted into its own.
        val cells = HashMap<Pair<Int, Int>, RemoteTiff?>()
        val shifts = HashMap<Pair<Int, Int>, Double>()
        for (lat in floor(lats.last()).toInt()..floor(lats.first()).toInt()) {
            for (lon in floor(lons.first()).toInt()..floor(lons.last()).toInt()) {
                val key = lat to wrapLon(lon.toDouble()).toInt()
                val name = cellName(key.first, key.second)
                shifts[key] = key.second.toDouble() - lon
                cells[key] = try {
                    RemoteTiff("https://copernicus-dem-30m.s3.amazonaws.com/$name/$name.tif", File(cacheDir, name))
                } catch (e: IllegalStateException) {
                    null // no file: open ocean
                }
            }
        }
        val level = cells.values.filterNotNull().firstOrNull()?.levelFor(OverviewLevels.targetMeters(window), 111_320.0) ?: 0
        for ((key, cell) in cells) {
            val shift = shifts.getValue(key)
            cell?.prefetch(level, lons.first() + shift, lats.last(), lons.last() + shift, lats.first())
        }

        val values = FloatArray(window.width * window.height)
        for (j in 0 until window.height) {
            for (i in 0 until window.width) {
                values[j * window.width + i] = sampleAcrossCells(cells, level, lats[j], wrapLon(lons[i]))
            }
        }
        val counters = mapOf(
            "cells" to cells.size.toLong(),
            "missingCells" to cells.values.count { it == null }.toLong(),
            "overviewLevel" to level.toLong(),
            "tilesDecoded" to cells.values.sumOf { it?.tilesDecoded ?: 0 }.toLong(),
            "rangeRequests" to cells.values.sumOf { it?.source?.fetches ?: 0L },
            "noDataCells" to values.count { it.isNaN() }.toLong(),
        )
        val grid = ElevationGrid(window.width, window.height, window.cellSizeMeters, values)
        return ElevationSource.Result(grid, counters, ElevationSource.provenanceIfData(grid, registryIds))
    }

    /**
     * Cells are pixel-is-point with no overlap: a cell's rows run from its top edge down to one
     * pixel short of its bottom edge, and the next cell south starts exactly there. So a cell
     * covers latitudes (south, north] and longitudes [west, east). Small epsilon absorbs
     * floating-point error in pixel-center coordinates.
     */
    private fun cellKey(lat: Double, lon: Double): Pair<Int, Int> =
        (ceil(lat - 1e-9).toInt() - 1) to floor(wrapLon(lon) + 1e-9).toInt()

    /**
     * Bilinear sample that reads neighbors from adjacent cells when the four surrounding pixels
     * straddle a cell boundary, so 1° boundaries are seamless.
     */
    private fun sampleAcrossCells(cells: Map<Pair<Int, Int>, RemoteTiff?>, level: Int, lat: Double, lon: Double): Float {
        val cell = cells[cellKey(lat, lon)] ?: return Float.NaN
        val l = cell.level(level)
        val col = l.colOf(lon)
        val row = l.rowOf(lat)
        val c0 = floor(col).toInt()
        val r0 = floor(row).toInt()
        val fx = (col - c0).toFloat()
        val fy = (row - r0).toFloat()
        fun v(c: Int, r: Int): Float {
            if (c in 0 until l.width && r in 0 until l.height) return cell.at(level, c, r)
            // Outside this cell: find the pixel at the same lattice position in the neighbor.
            val plat = l.yOf(r.toDouble())
            val plon = l.xOf(c.toDouble())
            val other = cells[cellKey(plat, plon)] ?: return cell.at(level, c, r)
            val ol = other.level(level)
            return other.at(level, Math.round(ol.colOf(wrapLon(plon))).toInt(), Math.round(ol.rowOf(plat)).toInt())
        }
        return (v(c0, r0) * (1 - fx) + v(c0 + 1, r0) * fx) * (1 - fy) + (v(c0, r0 + 1) * (1 - fx) + v(c0 + 1, r0 + 1) * fx) * fy
    }

    companion object {
        /** Longitude in [-180, 180): cells are named and georeferenced in that range. */
        fun wrapLon(lon: Double): Double = lon - 360.0 * floor((lon + 180.0) / 360.0)
    }
}
