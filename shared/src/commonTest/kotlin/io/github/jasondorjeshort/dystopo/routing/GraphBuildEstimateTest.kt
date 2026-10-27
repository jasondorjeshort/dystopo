package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T149: the graph build's heap estimate (finding 2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone).
 * [RoutingGraphBuilder.measure] reads an archive's size without building; the fitted peak against T149's measurement.
 */
class GraphBuildEstimateTest {
    private val extent = 4096

    private fun path(vararg pts: Pair<Int, Int>) = Feature(null, mapOf("class" to Value.StringValue("path")), Geometry.Lines(listOf(pts.map { TilePoint(it.first, it.second) })))
    private fun water(vararg pts: Pair<Int, Int>) = Feature(null, mapOf("class" to Value.StringValue("river")), Geometry.Lines(listOf(pts.map { TilePoint(it.first, it.second) })))

    /** An archive of z14 tiles, each with [perTile] routable lines of [vertices] points and one line in another layer. */
    private fun archive(tiles: Int, perTile: Int, vertices: Int): Pair<PmtilesReader, Long> {
        val w = PmtilesWriter(TileType.MVT)
        val x0 = 2700
        val y0 = 5800
        for (i in 0 until tiles) {
            val lines = List(perTile) { k -> path(*Array(vertices) { v -> (v * 40) to (k * 50 + v) }) }
            w.addTile(14, x0 + i, y0, VectorTile(listOf(Layer("transportation", lines, extent), Layer("waterway", listOf(water(0 to 0, 9 to 9, 99 to 99)), extent))).encode())
        }
        val chunks = ArrayList<ByteArray>()
        w.finish(ByteSink { chunks += it }, "{}", minLon = -121.75, minLat = 46.78, maxLon = -121.70, maxLat = 46.81, minZoom = 14, maxZoom = 14)
        val bytes = ByteArray(chunks.sumOf { it.size }).also { var p = 0; for (c in chunks) { c.copyInto(it, p); p += c.size } }
        return PmtilesReader.open(ByteRangeSource { offset, length ->
            bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), offset + length).toInt())
        }) to bytes.size.toLong()
    }

    @Test
    fun measureCountsTheRoutableVerticesOfEveryTileWhenTheSampleCoversThem() {
        val (reader, bytes) = archive(tiles = 10, perTile = 3, vertices = 7)
        val size = RoutingGraphBuilder.measure(reader, bytes)
        assertEquals(14, size.zoom)
        assertEquals(10, size.tiles)
        assertEquals(10, size.sampledTiles)
        assertEquals(10L * 3 * 7, size.vertices) // the waterway line is not routable and not counted
        assertEquals(bytes, size.archiveBytes)
    }

    @Test
    fun aSampleScalesToEveryTile() {
        val (reader, bytes) = archive(tiles = 40, perTile = 2, vertices = 5)
        val size = RoutingGraphBuilder.measure(reader, bytes, sample = 8)
        assertEquals(40, size.tiles)
        assertEquals(8, size.sampledTiles)
        assertEquals(8L * 2 * 5, size.sampledVertices)
        assertEquals(40L * 2 * 5, size.vertices)
    }

    @Test
    fun theMeasuredVerticesMatchWhatTheBuildReads() {
        val (reader, bytes) = archive(tiles = 6, perTile = 2, vertices = 4)
        val builder = RoutingGraphBuilder()
        builder.build(reader, "t.pmtiles")
        // Clipping to the tile square can add or drop a point per line; here every line lies inside its tile.
        assertEquals(builder.stats!!.inputVertices.toLong(), RoutingGraphBuilder.measure(reader, bytes).vertices)
    }

    /**
     * T149's measurement: per pack, the vertices [RoutingGraphBuilder.measure] read on the emulator and the heap's growth
     * through the build there (used bytes from its start to its peak, garbage included; API 35, 576 MB heap). The
     * Washington 3 degree box does not fit that heap (the guard refuses it); its figure is the smallest JVM heap that
     * built it, the upper end of the range found (2 GB failed; 2.25 GB built in 1,127 s, 2.5 GB in 729 s, 2.75 GB in 489 s).
     */
    private val measured = listOf(
        Triple("rainier-outdoors", 57_826L, 65_069_056L), // 2.6 MB archive, 480 z14 tiles: 3,526 nodes, 3,793 edges, 5.8 s
        Triple("washington 1 x 0.7 degree", 346_512L, 143_216_640L), // 8.9 MB, 2,255 tiles: 35,644 nodes, 39,467 edges, 17.6 s
        Triple("washington 3 degree", 7_910_526L, 2_304L shl 20), // 124 MB, 24,266 tiles: 1,934,402 nodes, 2,443,472 edges (JVM)
    )

    @Test
    fun theEstimateCoversEachMeasuredBuildWithinTheStatedFactor() {
        // The factor T149 states: the estimate (fitted peak times the 1.5 margin) is at least the measured peak and at
        // most twice it; the fitted peak alone is within 0.8x and 1.1x of it.
        for ((name, vertices, peak) in measured) {
            val estimate = RoutingGraphBuilder.estimatePeakBytes(vertices).toDouble() / peak
            val fitted = RoutingGraphBuilder.fittedPeakBytes(vertices).toDouble() / peak
            assertTrue(estimate in 1.0..2.0, "$name: estimate ${RoutingGraphBuilder.estimatePeakBytes(vertices)} is ${estimate}x the measured $peak")
            assertTrue(fitted in 0.8..1.1, "$name: fitted ${RoutingGraphBuilder.fittedPeakBytes(vertices)} is ${fitted}x the measured $peak")
        }
    }

    @Test
    fun theEstimateIsTheFittedPeakWithTheMargin() {
        assertEquals(RoutingGraphBuilder.ESTIMATE_BASE_BYTES, RoutingGraphBuilder.fittedPeakBytes(0))
        val v = 1_000_000L
        val fitted = RoutingGraphBuilder.fittedPeakBytes(v)
        assertEquals((fitted * RoutingGraphBuilder.ESTIMATE_MARGIN).toLong(), RoutingGraphBuilder.estimatePeakBytes(v))
        assertTrue(RoutingGraphBuilder.estimatePeakBytes(2 * v) > RoutingGraphBuilder.estimatePeakBytes(v))
    }
}
