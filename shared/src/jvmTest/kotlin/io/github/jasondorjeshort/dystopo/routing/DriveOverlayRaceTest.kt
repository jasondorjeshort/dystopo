package io.github.jasondorjeshort.dystopo.routing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T102 (T97's follow-up): [DriveOverlay] built a level-1 cell twice when a route raced the server's warm-up for the
 * same cell (no lock between the cache check and the build). Two threads ask for one cell at once; the base tile's
 * loader holds the first thread until the second arrives too (or half a second passes). With the build lock the
 * second waits for the first build and takes its cell; without it (the default, for one thread) both build.
 */
class DriveOverlayRaceTest {
    private val source = GraphSource("race-outdoors.pmtiles", "fp", "", 14, 0, drive = true)

    /** Tertiary roads every 0.05 degrees across a few z11 tiles near the equator (a tile is about 0.18 degrees). */
    private fun grid(): RoutingGraph {
        val road = WayAttrs(WayClass.TERTIARY, Surface.PAVED, name = "Grid Road")
        val lines = ArrayList<WayLine>()
        var v = 0.0
        while (v <= 0.4 + 1e-9) {
            lines += WayLine.of(road, 0.0 to v, 0.4 to v)
            lines += WayLine.of(road, v to 0.0, v to 0.4)
            v += 0.05
        }
        return RoutingGraphBuilder().build(lines, source)
    }

    /** Two threads ask [DriveOverlay.piece] for one cell at once; returns how many builds the overlay counted. */
    private fun race(withBuildLock: Boolean): Long {
        val tg = TiledGraph(listOf(GraphTileFile.open(GraphTileFile.encode(grid()))))
        val (x, y) = tg.tilesIn(0.15, 0.15, 0.25, 0.25).first { (x, y) -> tg.pieces(x, y).singleOrNull()?.let { DriveOverlay().build(it) } != null }
        val base = tg.pieces(x, y).single()
        val cacheMonitor = Any()
        val buildMonitor = Any()
        val overlay = if (withBuildLock) {
            DriveOverlay(lock = { b -> synchronized(cacheMonitor) { b() } }, buildLock = { b -> synchronized(buildMonitor) { b() } })
        } else {
            DriveOverlay(lock = { b -> synchronized(cacheMonitor) { b() } })
        }
        val bothLoading = CountDownLatch(2)
        val go = CountDownLatch(1)
        val load = {
            bothLoading.countDown()
            bothLoading.await(500, TimeUnit.MILLISECONDS) // the window between the cache check and the build
            base
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val asks = (1..2).map { pool.submit<GraphPiece?> { go.await(); overlay.piece(x, y, 0, source, load) } }
            go.countDown()
            val pieces = asks.map { it.get(20, TimeUnit.SECONDS) }
            assertTrue(pieces.all { it?.overlay?.cell != null }, "both threads get the cell")
        } finally {
            pool.shutdownNow()
        }
        return overlay.built
    }

    @Test
    fun aRouteRacingTheWarmUpBuildsTheCellOnce() {
        assertEquals(1, race(withBuildLock = true), "with the build lock the second thread takes the first one's cell")
        // The race itself: without the lock both threads pass the cache check before either has kept a cell.
        assertEquals(2, race(withBuildLock = false), "without the build lock the cell is built twice")
    }
}
