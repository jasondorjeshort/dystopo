package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.io.File
import kotlin.math.floor

/**
 * USGS 3DEP 1/3 arc-second (~10 m) seamless DEM, US only. Published as 1°x1° Cloud-Optimized
 * GeoTIFFs (NAD83 geographic, NAVD88 meters). Only the internal tiles covering the window are
 * fetched, via HTTP range requests, and cached under [cacheDir].
 *
 * Resampled to Web Mercator with bilinear interpolation. NAD83 vs WGS84 differs by ~1 m here,
 * well under a pixel, and is ignored for now.
 */
class ThreeDepSource(private val cacheDir: File = File("cache/3dep13")) : ElevationSource {
    override val id = "3dep"
    override val attribution = "Elevation: USGS 3D Elevation Program (3DEP) 1/3 arc-second DEM, public domain."

    /** USGS names cells by their north-west corner: n47w122 covers 46..47°N, 121..122°W. */
    private fun cellName(lat: Double, lon: Double): String {
        val n = floor(lat).toInt() + 1
        val w = floor(-lon).toInt() + 1
        require(n > 0 && w > 0) { "3DEP cell naming here only handles the north-west hemisphere" }
        return "n${n}w${w.toString().padStart(3, '0')}"
    }

    private fun url(cell: String) =
        "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current/$cell/USGS_13_$cell.tif"

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val lons = DoubleArray(window.width) { window.lonAt(it) }
        val lats = DoubleArray(window.height) { window.latAt(it) }

        // Open every 1° cell the window touches (rows run north to south, columns west to east).
        val cells = HashMap<String, RemoteTiff>()
        for (lat in floor(lats.last()).toInt()..floor(lats.first()).toInt()) {
            for (lon in floor(lons.first()).toInt()..floor(lons.last()).toInt()) {
                val name = cellName(lat + 0.5, lon + 0.5)
                cells[name] = RemoteTiff(url(name), File(cacheDir, name))
            }
        }
        for (cell in cells.values) cell.prefetch(0, lons.first(), lats.last(), lons.last(), lats.first())

        var noData = 0L
        val values = FloatArray(window.width * window.height)
        for (j in 0 until window.height) {
            for (i in 0 until window.width) {
                val v = cells.getValue(cellName(lats[j], lons[i])).sample(0, lons[i], lats[j])
                if (v.isNaN()) noData++
                values[j * window.width + i] = v
            }
        }
        val counters = mapOf(
            "cells" to cells.size.toLong(),
            "tilesDecoded" to cells.values.sumOf { it.tilesDecoded }.toLong(),
            "rangeRequests" to cells.values.sumOf { it.source.fetches },
            "noDataCells" to noData,
        )
        return ElevationSource.Result(ElevationGrid(window.width, window.height, window.cellSizeMeters, values), counters)
    }
}
