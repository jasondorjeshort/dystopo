package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiff.GeoTiff
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.ceil
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

    private val deflate: Decompressor = { input, size ->
        val inflater = Inflater()
        inflater.setInput(input)
        val out = ByteArray(size)
        var n = 0
        while (n < size && !inflater.finished()) n += inflater.inflate(out, n, size - n)
        inflater.end()
        out
    }

    /** USGS names cells by their north-west corner: n47w122 covers 46..47°N, 121..122°W. */
    private fun cellName(lat: Double, lon: Double): String {
        val n = floor(lat).toInt() + 1
        val w = floor(-lon).toInt() + 1
        require(n > 0 && w > 0) { "3DEP cell naming here only handles the north-west hemisphere" }
        return "n${n}w${w.toString().padStart(3, '0')}"
    }

    private fun url(cell: String) =
        "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current/$cell/USGS_13_$cell.tif"

    private class Cell(val tiff: GeoTiff, val source: CachedHttpRangeSource) {
        val tiles = ConcurrentHashMap<Int, FloatArray>()
        fun tile(tx: Int, ty: Int): FloatArray = tiles.getOrPut(ty * tiff.tilesAcross + tx) { tiff.readTile(tx, ty) }
    }

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val lons = DoubleArray(window.width) { window.lonAt(it) }
        val lats = DoubleArray(window.height) { window.latAt(it) }

        // Open every 1° cell the window touches (rows run north to south, columns west to east).
        val cells = HashMap<String, Cell>()
        for (lat in floor(lats.last()).toInt()..floor(lats.first()).toInt()) {
            for (lon in floor(lons.first()).toInt()..floor(lons.last()).toInt()) {
                val name = cellName(lat + 0.5, lon + 0.5)
                cells.getOrPut(name) {
                    val src = CachedHttpRangeSource(url(name), File(cacheDir, name))
                    Cell(GeoTiff.open(src, mapOf(8 to deflate, 32946 to deflate)), src)
                }
            }
        }

        // Prefetch the internal tiles each cell needs, in parallel.
        for (cell in cells.values) {
            val t = cell.tiff
            val c0 = floor(t.colOf(lons.first())).toInt().coerceIn(0, t.width - 1)
            val c1 = ceil(t.colOf(lons.last())).toInt().coerceIn(0, t.width - 1)
            val r0 = floor(t.rowOf(lats.first())).toInt().coerceIn(0, t.height - 1)
            val r1 = ceil(t.rowOf(lats.last())).toInt().coerceIn(0, t.height - 1)
            val needed = (r0 / t.tileHeight..r1 / t.tileHeight).flatMap { ty ->
                (c0 / t.tileWidth..c1 / t.tileWidth).map { tx -> tx to ty }
            }
            needed.parallelStream().forEach { (tx, ty) -> cell.tile(tx, ty) }
        }

        var noData = 0L
        val values = FloatArray(window.width * window.height)
        for (j in 0 until window.height) {
            val lat = lats[j]
            for (i in 0 until window.width) {
                val lon = lons[i]
                val cell = cells.getValue(cellName(lat, lon))
                val v = bilinear(cell, cell.tiff.colOf(lon), cell.tiff.rowOf(lat))
                if (v.isNaN()) noData++
                values[j * window.width + i] = v
            }
        }
        val counters = mapOf(
            "cells" to cells.size.toLong(),
            "tilesDecoded" to cells.values.sumOf { it.tiles.size }.toLong(),
            "rangeRequests" to cells.values.sumOf { it.source.fetches },
            "noDataCells" to noData,
        )
        return ElevationSource.Result(ElevationGrid(window.width, window.height, window.cellSizeMeters, values), counters)
    }

    private fun sample(cell: Cell, col: Int, row: Int): Float {
        val t = cell.tiff
        val c = col.coerceIn(0, t.width - 1)
        val r = row.coerceIn(0, t.height - 1)
        val v = cell.tile(c / t.tileWidth, r / t.tileHeight)[(r % t.tileHeight) * t.tileWidth + c % t.tileWidth]
        val nd = t.noData
        return if (v.isNaN() || (nd != null && abs(v - nd) < 1e-3f)) Float.NaN else v
    }

    private fun bilinear(cell: Cell, col: Double, row: Double): Float {
        val c0 = floor(col).toInt()
        val r0 = floor(row).toInt()
        val fx = (col - c0).toFloat()
        val fy = (row - r0).toFloat()
        val a = sample(cell, c0, r0)
        val b = sample(cell, c0 + 1, r0)
        val c = sample(cell, c0, r0 + 1)
        val d = sample(cell, c0 + 1, r0 + 1)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }
}
