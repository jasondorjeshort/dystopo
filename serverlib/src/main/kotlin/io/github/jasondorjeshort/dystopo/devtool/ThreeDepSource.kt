package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.io.File
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
 */
class ThreeDepSource(private val cacheDir: File = Caches.dir("3dep13")) : ElevationSource {
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

        val values = FloatArray(window.width * window.height)
        for (j in 0 until window.height) {
            for (i in 0 until window.width) {
                val cell = cellName(lats[j], lons[i])?.let { cells[it] }
                values[j * window.width + i] = cell?.sample(level, lons[i], lats[j]) ?: Float.NaN
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
        )
        val grid = ElevationGrid(window.width, window.height, window.cellSizeMeters, values)
        // Credit only the geoid grids this window used.
        return ElevationSource.Result(grid, counters, ElevationSource.provenanceIfData(grid, listOf("3dep") + offsets?.registryIds.orEmpty()))
    }
}
