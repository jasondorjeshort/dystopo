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

    // ---- T203: the live rules ----

    private val liveOptions = TerrainLiveOptions(fix = options)

    private fun walk1s(): List<TerrainFixReplay.Truth> {
        val r = Router(graph).route(46.7864, -121.7366, 46.8024, -121.7289, TravelMode.HIKE)
        assertIs<RouteResult.Found>(r)
        val line = TerrainFixReplay.toblerTimes(r.plan.route.points.map { Triple(it.lat, it.lon, fixture.cone.elevation(it.lat, it.lon)) })
        return TerrainFixReplay.resample(line, 1.0)
    }

    private fun live(o: TerrainLiveOptions = liveOptions) = TerrainLive(o, { _, _, _ -> graph }, fixture.cone)

    /** Feeds [truth] steps [from] until [to] (exclusive): the barometer and a compass every second, fixes where [fix] gives an accuracy. */
    private fun feed(l: TerrainLive, truth: List<TerrainFixReplay.Truth>, from: Int, to: Int, fix: (Int) -> Double?, compass: (Int) -> Double = { bearingAt(truth, it) }) {
        for (k in from until to) {
            val p = truth[k]
            val t = (p.timeS * 1000).toLong()
            l.onHeading(t, compass(k))
            l.onPressure(t, p.ele)
            fix(k)?.let { acc -> l.onFix(t, p.lat, p.lon, acc, 50.0) }
        }
    }

    private fun bearingAt(truth: List<TerrainFixReplay.Truth>, k: Int): Double {
        val a = truth[maxOf(0, k - 5)]
        val b = truth[maxOf(1, k)]
        return io.github.jasondorjeshort.dystopo.gpx.TrackStats.bearingDeg(a.lat, a.lon, b.lat, b.lon)
    }

    @Test
    fun theMinutesWaitThenTheSeedAtTheLastGoodFixAndAGoodFixReseeds() {
        val truth = walk1s()
        val l = live()
        val cut = 400
        feed(l, truth, 0, cut, { 5.0 })
        assertEquals(TerrainState.IDLE, l.state)
        // 59 s without a fix: waiting, no estimate yet.
        feed(l, truth, cut, cut + 59, { null })
        assertEquals(TerrainState.WAITING, l.state)
        assertEquals(0L, l.starts)
        // The minute: seeded at the last good fix, the minute caught up.
        feed(l, truth, cut + 59, cut + 61, { null })
        assertEquals(TerrainState.ESTIMATING, l.state)
        val seed = l.seed!!
        assertEquals((truth[cut - 1].timeS * 1000).toLong(), seed.timeMs)
        assertEquals("good-minutes", seed.paceFrom)
        assertTrue(l.steps >= 11, "the minute caught up in ${l.steps} steps")
        assertEquals(liveOptions.fix.particles, l.particles)
        // Poor fixes do not end it; a good one does, and the next estimate starts from it (re-seeded).
        feed(l, truth, cut + 61, cut + 100, { 200.0 })
        assertEquals(TerrainState.ESTIMATING, l.state)
        feed(l, truth, cut + 100, cut + 101, { 5.0 })
        assertEquals(TerrainState.IDLE, l.state)
        assertEquals(1L, l.reseeds)
        feed(l, truth, cut + 101, cut + 170, { null })
        assertEquals(TerrainState.ESTIMATING, l.state)
        assertEquals(2L, l.starts)
        assertEquals((truth[cut + 100].timeS * 1000).toLong(), l.seed!!.timeMs)
    }

    @Test
    fun theStrideIsThePaceOfTheLastGoodMinutes() {
        val truth = walk1s()
        val cut = 500
        val l = live()
        feed(l, truth, 0, cut, { 5.0 })
        feed(l, truth, cut, cut + 61, { null })
        val seed = l.seed!!
        val back = truth[cut - 1 - 180]
        val trueMps = (truth[cut - 1].alongM - back.alongM) / (truth[cut - 1].timeS - back.timeS)
        assertTrue(kotlin.math.abs(seed.paceMps - trueMps) / trueMps < 0.15, "pace ${seed.paceMps} against $trueMps m/s")
        // With no good minutes before the seed, the default pace.
        val l2 = live()
        feed(l2, truth, 0, 1, { 5.0 })
        feed(l2, truth, 1, 63, { null })
        assertEquals("default", l2.seed!!.paceFrom)
        assertEquals(liveOptions.defaultPaceMps, l2.seed!!.paceMps)
    }

    @Test
    fun theHeadingTermOnlyWhileTheCompassIsSteadyAlongTheWay() {
        val truth = walk1s()
        val cut = 400
        // Steady along the way: used at every step.
        val steady = live()
        feed(steady, truth, 0, cut, { 5.0 })
        feed(steady, truth, cut, cut + 120, { null })
        assertEquals("steady", steady.seed!!.heading)
        assertEquals(steady.steps, steady.headingSteps)
        // A phone turned about after the seed: dropped from the start.
        val spun = live()
        feed(spun, truth, 0, cut, { 5.0 })
        feed(spun, truth, cut, cut + 120, { null }) { k -> (k * 97.0) % 360 }
        assertEquals("turning", spun.seed!!.heading)
        assertEquals(0L, spun.headingSteps)
        // A pocketed phone pointing across the way: dropped.
        val across = live()
        feed(across, truth, 0, cut, { 5.0 })
        feed(across, truth, cut, cut + 120, { null }) { k -> (bearingAt(truth, k) + 90) % 360 }
        assertEquals("not-along", across.seed!!.heading)
        // Steady at the seed, then turned about: dropped for those steps only.
        val later = live()
        feed(later, truth, 0, cut, { 5.0 })
        feed(later, truth, cut, cut + 200, { null }) { k -> if (k in cut + 100 until cut + 150) (k * 97.0) % 360 else bearingAt(truth, k) }
        assertEquals("steady", later.seed!!.heading)
        assertTrue(later.headingSteps in 1 until later.steps, "${later.headingSteps} of ${later.steps} steps")
    }

    @Test
    fun theEstimateGivesUpPastItsSpreadLimit() {
        val truth = walk1s()
        // A flat world and no heading: nothing but the pace, the cloud fans out; with a 40 m limit it gives up soon.
        val flat = ElevationSampler { _, _ -> 1800.0 }
        val o = TerrainLiveOptions(giveUpM = 40.0, fix = options.copy(useHeading = false))
        val l = TerrainLive(o, { _, _, _ -> graph }, flat)
        for (k in truth.indices) {
            val p = truth[k]
            val t = (p.timeS * 1000).toLong()
            l.onPressure(t, 1800.0)
            if (k < 300) l.onFix(t, p.lat, p.lon, 5.0, 50.0)
            if (l.state == TerrainState.GAVE_UP) break
        }
        assertEquals(TerrainState.GAVE_UP, l.state)
        assertEquals("spread", l.reason)
        assertEquals(0, l.particles)
        // The default limit is the finding's 300 m, and a good fix ends the give-up.
        assertEquals(300.0, TerrainLiveOptions().giveUpM)
        assertEquals(60_000L, TerrainLiveOptions().waitMs)
        val last = truth.last()
        l.onFix((last.timeS * 1000).toLong() + 1000, last.lat, last.lon, 5.0, 50.0)
        assertEquals(TerrainState.IDLE, l.state)
    }

    @Test
    fun noEstimateWithoutPressureATrailOrAGraph() {
        val truth = walk1s()
        val noBaro = live()
        for (k in 0 until 200) {
            val p = truth[k]
            noBaro.onFix((p.timeS * 1000).toLong(), p.lat, p.lon, 5.0, 50.0)
        }
        noBaro.tick((truth[199].timeS * 1000).toLong() + 61_000)
        assertEquals(TerrainState.NO_FIX, noBaro.state)
        assertEquals("no-pressure", noBaro.reason)
        val far = live()
        far.onPressure(0, 1000.0)
        far.onFix(0, 46.70, -121.60, 5.0, 50.0)
        far.onPressure(61_000, 1000.0)
        assertEquals(TerrainState.NO_FIX, far.state)
        assertEquals("no-trail", far.reason)
        val noGraph = TerrainLive(liveOptions, { _, _, _ -> null }, fixture.cone)
        noGraph.onPressure(0, 1000.0)
        noGraph.onFix(0, truth[0].lat, truth[0].lon, 5.0, 50.0)
        noGraph.onPressure(61_000, 1000.0)
        assertEquals("no-graph", noGraph.reason)
    }

    @Test
    fun aClockThatGoesBackStartsAfresh() {
        val truth = walk1s()
        val l = live()
        // The device's own fixes, a day later than the replay's file times.
        l.onPressure(86_400_000L, 1000.0)
        l.onFix(86_400_000L, truth[0].lat, truth[0].lon, 5.0, 50.0)
        feed(l, truth, 0, 400, { 5.0 })
        assertEquals(1L, l.rewinds)
        assertEquals(400L, l.goodFixes)
        feed(l, truth, 400, 470, { null })
        assertEquals(TerrainState.ESTIMATING, l.state)
    }

    @Test
    fun theLiveReplayHoldsTheClimb() {
        val truth = walk1s()
        val length = truth.last().alongM
        val run = TerrainFixReplay.runLiveCut(graph, fixture.cone, truth, 600.0, length, liveOptions, seed = 3)
        assertEquals(60.0, run.shownAfterS!!, 2.0)
        assertTrue(run.errors.isNotEmpty())
        val median = TerrainFixReplay.quantile(run.errors.sorted(), 0.5)
        assertTrue(median < 40.0, "median $median m")
        assertTrue(run.spreads.all { it >= liveOptions.minSpreadM })
    }
}
