package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContourWarmupTest {
    private val rainier = BoundingBox(-122.05, 46.6, -121.4, 47.1)

    @Test
    fun plansTheTwoLowestPackZoomsCentreFirst() {
        val plan = ContourTiles.warmupPlan(rainier, demMinZoom = 8)
        assertEquals(setOf(8, 9), plan.map { it.z }.toSet())
        // Every tile covering the bounds at those zooms, once, lower zoom first.
        val expected = (8..9).flatMap { WebMercator.tilesCovering(rainier, it) }
        assertEquals(expected.toSet(), plan.toSet())
        assertEquals(expected.size, plan.size)
        assertEquals(plan.map { it.z }.sorted(), plan.map { it.z })
        // Within a zoom the tile holding the centre comes first.
        for (z in 8..9) {
            val first = plan.first { it.z == z }
            val cx = WebMercator.lonToX(rainier.centerLon, z).toInt()
            val cy = WebMercator.latToY(rainier.centerLat, z).toInt()
            assertEquals(TileId(z, cx, cy), first)
        }
    }

    @Test
    fun centreTilesOfHigherZoomsComeLast() {
        val plan = ContourTiles.warmupPlan(rainier, demMinZoom = 8, centreUpTo = 15)
        val base = ContourTiles.warmupPlan(rainier, demMinZoom = 8)
        assertEquals(base, plan.take(base.size))
        val centres = plan.drop(base.size)
        assertEquals((10..15).toList(), centres.map { it.z })
        for (t in centres) {
            assertEquals(WebMercator.lonToX(rainier.centerLon, t.z).toInt(), t.x)
            assertEquals(WebMercator.latToY(rainier.centerLat, t.z).toInt(), t.y)
        }
    }

    @Test
    fun capKeepsLowZoomsAndTheCentre() {
        val all = ContourTiles.warmupPlan(rainier, demMinZoom = 11, zoomCount = 3)
        val capped = ContourTiles.warmupPlan(rainier, demMinZoom = 11, zoomCount = 3, maxTiles = 10)
        assertEquals(10, capped.size)
        assertEquals(all.take(10), capped)
        assertTrue(ContourTiles.warmupPlan(rainier, 8, zoomCount = 0).isEmpty())
    }

    @Test
    fun antimeridianBoxIsOrderedByRealDistance() {
        val fiji = BoundingBox.unwrapped(179.0, -18.5, -179.0, -16.0)
        val plan = ContourTiles.warmupPlan(fiji, demMinZoom = 9, zoomCount = 1)
        assertEquals(WebMercator.tilesCovering(fiji, 9).toSet(), plan.toSet())
        // The centre is on 180 degrees: the first tiles sit either side of the seam, not at a far edge.
        val n = 1 shl 9
        assertTrue(plan.take(2).all { it.x == n - 1 || it.x == 0 }, "first tiles ${plan.take(2)}")
    }

    @Test
    fun progressCountsAndFinishes() {
        val p = ContourWarmupProgress(listOf(8, 9), planned = 3)
        p.generated()
        p.cached()
        p.failed(IllegalStateException("boom"))
        p.paused(12.5)
        assertEquals(3, p.done)
        assertEquals("running", p.json()["state"])
        p.finish(stopped = false)
        val json = p.json()
        assertEquals("done", json["state"])
        assertEquals(1, json["generated"])
        assertEquals(1, json["alreadyCached"])
        assertEquals(1, json["failed"])
        assertEquals(12.5, json["pausedForMapMs"])
        p.finish(stopped = true)
        assertEquals("done", p.json()["state"], "finish is final")
    }
}
