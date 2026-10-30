package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.RainierRoutingTest
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T166: the terrain-matched position on a synthetic profile along a Rainier trail: the Skyline Trail from the
 * Paradise trailhead up to Panorama Point on [RainierRoutingTest]'s fixture network, its elevation from the
 * fixture's DEM stand-in, walked at Tobler's pace with the GPS cut from the first step.
 */
class TerrainFixTest {
    private val fixture = RainierRoutingTest()
    private val graph = fixture.graph()
    private val options = TerrainFixOptions(particles = 400)

    private fun walk(sampler: ElevationSampler): List<TerrainFixReplay.Truth> {
        val r = Router(graph).route(46.7864, -121.7366, 46.8024, -121.7289, TravelMode.HIKE)
        assertIs<RouteResult.Found>(r)
        val line = TerrainFixReplay.toblerTimes(r.plan.route.points.map { Triple(it.lat, it.lon, sampler.elevation(it.lat, it.lon)) })
        return TerrainFixReplay.resample(line, 5.0)
    }

    @Test
    fun theClimbKeepsTheEstimateInsideItsSpread() {
        val truth = walk(fixture.cone)
        val length = truth.last().alongM
        assertTrue(length > 2500, "the climb is ${length} m")
        val run = TerrainFixReplay.runCut(graph, fixture.cone, truth, 0.0, length, options, seed = 3)
        val covered = run.errors.indices.count { run.errors[it] <= run.spreads[it] }.toDouble() / run.errors.size
        val sorted = run.errors.sorted()
        assertTrue(covered >= 0.9, "coverage $covered")
        assertTrue(TerrainFixReplay.quantile(sorted, 0.5) < 30.0, "median error ${TerrainFixReplay.quantile(sorted, 0.5)} m")
        assertTrue(run.errors.last() <= run.spreads.last(), "at the top: error ${run.errors.last()} m, spread ${run.spreads.last()} m")
    }

    @Test
    fun flatGroundWidensTheSpreadInsteadOfDrifting() {
        val flat = ElevationSampler { _, _ -> 1800.0 }
        val terrain = TerrainFixReplay.runCut(graph, fixture.cone, walk(fixture.cone), 0.0, 2500.0, options, seed = 5)
        // The same walk on a flat world, with a pocketed phone's compass: nothing but stride and a poor heading.
        val sensors = TerrainFixReplay.Sensors(headingBiasDeg = 20.0, headingNoiseDeg = 40.0)
        val run = TerrainFixReplay.runCut(graph, flat, walk(flat), 0.0, 2500.0, options, sensors, seed = 5)
        val covered = run.errors.indices.count { run.errors[it] <= run.spreads[it] }.toDouble() / run.errors.size
        assertTrue(covered >= 0.9, "flat coverage $covered")
        val flatSpread = TerrainFixReplay.quantile(run.spreads.sorted(), 0.5)
        val terrainSpread = TerrainFixReplay.quantile(terrain.spreads.sorted(), 0.5)
        assertTrue(flatSpread > terrainSpread, "flat spread $flatSpread m should exceed the climb's $terrainSpread m")
    }

    @Test
    fun replayHelpers() {
        val line = TerrainFixReplay.toblerTimes(listOf(Triple(46.80, -121.73, 1800.0), Triple(46.801, -121.73, 1800.0)))
        // About 111 m on the flat at Tobler's 5.04 km/h: 79 s.
        assertEquals(79.0, line.last().timeS, 1.0)
        val truth = TerrainFixReplay.resample(line, 10.0)
        assertEquals(8, truth.size)
        assertEquals(0.0, truth.first().alongM)
        assertTrue(truth.zipWithNext().all { (a, b) -> b.alongM > a.alongM })
        assertEquals(180.0, TerrainFix.angleDiff(10.0, 190.0))
        assertEquals(20.0, TerrainFix.angleDiff(350.0, 10.0))
        assertEquals(90.0, TerrainFix.bearing(1.0, 0.0), 1e-9)
        // No trail near the seed: the filter says so rather than inventing a position.
        val fix = TerrainFix(graph, fixture.cone, options)
        assertFailsWith<IllegalStateException> { fix.start(46.70, -121.60, 1000.0) }
    }
}
