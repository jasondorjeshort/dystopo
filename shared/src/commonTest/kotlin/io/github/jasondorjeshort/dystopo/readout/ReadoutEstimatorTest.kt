package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.NavPoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T160's replay tests: synthetic walks, a drive in the wrong activity, a flight; each prints its numbers (the
 * report's) and asserts the Done-when thresholds.
 */
class ReadoutEstimatorTest {
    private fun feed(est: ReadoutEstimator, fixes: List<LocationFix>, pressure: List<PressureSample> = emptyList(), along: ((LocationFix) -> Double?)? = null, each: (LocationFix, Readout) -> Unit) {
        val pressureByT = pressure.associateBy { it.timeMs }
        for (f in fixes) {
            pressureByT[f.time]?.let(est::onPressure)
            est.onFix(f, along?.invoke(f))
            each(f, est.readout(f.time))
        }
    }

    private val winding = { s: Int -> 90 + 40 * sin(s / 40.0) }

    @Test
    fun walkSpeedWithinATenthsOfTheTruthAndADashWhenStill() {
        val sim = ReadoutSim(1)
        val legs = listOf(ReadoutSim.Leg(600, 1.3, course = winding), ReadoutSim.Leg(120, 0.0), ReadoutSim.Leg(300, 1.1, course = winding))
        val truth = sim.journey(legs)
        val est = ReadoutEstimator(Activity.FOOT)
        var worst = 0.0
        var checked = 0
        var dashes = 0
        var frozenCourse: Double? = null
        feed(est, sim.fixes(truth)) { f, r ->
            val i = ((f.time - ReadoutSim.T0) / 1000).toInt()
            val tr = truth[i]
            val intoWalk = if (i < 600) i else if (i >= 720) i - 720 else -1
            if (intoWalk >= 30) {
                assertEquals(ValueState.SHOWN, r.speed.state, "speed at $i s")
                assertEquals("fix", r.speed.source)
                worst = maxOf(worst, abs(r.speed.value!! - tr.speed))
                checked++
            }
            if (i in 605 until 720) {
                assertEquals(ValueState.DASH, r.speed.state, "dash at $i s")
                assertTrue(r.course.frozen, "course frozen at $i s")
                val c = frozenCourse ?: r.course.value!!.also { frozenCourse = it }
                assertEquals(c, r.course.value)
                dashes++
            }
        }
        println("T160 walk: worst speed error ${worst} m/s over $checked readouts, $dashes dashes while still")
        assertTrue(worst <= 0.3, "worst speed error $worst")
    }

    @Test
    fun walkWithoutFixSpeedsUsesDistanceOverThirtySeconds() {
        val sim = ReadoutSim(2)
        val truth = sim.journey(listOf(ReadoutSim.Leg(900, 1.3, course = { 90.0 })))
        val est = ReadoutEstimator(Activity.FOOT)
        val errs = ArrayList<Double>()
        feed(est, sim.fixes(truth, withSpeed = false)) { f, r ->
            val i = ((f.time - ReadoutSim.T0) / 1000).toInt()
            if (i >= 60) {
                assertEquals("positions", r.speed.source)
                errs += abs(r.speed.value!! - 1.3)
            }
        }
        val mean = errs.average()
        val p95 = errs.sorted()[(errs.size * 0.95).toInt()]
        println("T160 walk without fix speeds: mean error $mean m/s, 95th percentile $p95 m/s")
        assertTrue(mean < 0.15 && p95 < 0.3, "mean $mean p95 $p95")
    }

    @Test
    fun alongRouteSpeedWhenNavigating() {
        val sim = ReadoutSim(3)
        // A switchback line: the walker follows it exactly, so the along-route distance is the trail's.
        val truth = sim.journey(listOf(ReadoutSim.Leg(600, 1.2, course = { s -> if ((s / 100) % 2 == 0) 45.0 else 135.0 })))
        val path = NavPath("zigzag", truth.filterIndexed { i, _ -> i % 10 == 0 }.map { val (la, lo) = ReadoutSim.latLon(it.east, it.north); NavPoint(la, lo) })
        val est = ReadoutEstimator(Activity.FOOT)
        var lastAlong: Double? = null
        var worst = 0.0
        feed(est, sim.fixes(truth), along = { f -> path.project(f.lat, f.lon, lastAlong).alongM.also { lastAlong = it } }) { f, r ->
            val i = ((f.time - ReadoutSim.T0) / 1000).toInt()
            if (i in 70..580) {
                assertEquals("route", r.speed.source, "at $i s")
                worst = maxOf(worst, abs(r.speed.value!! - 1.2))
            }
            if (i == 30) assertEquals("fix", r.speed.source) // the long window not yet filled
        }
        println("T160 along-route: worst speed error $worst m/s")
        assertTrue(worst <= 0.3, "worst $worst")
    }

    /**
     * [settleStartS], [settleStepS]: seconds after the start and after the step from which the offset error stays
     * under 5 m to the end of that half; [within]: the share of readouts from 10 min into each half on with the error
     * under 5 m; [errAt10]: the larger of the two errors 10 min into each half.
     */
    private class Convergence(val settleStartS: Long?, val settleStepS: Long?, val within: Double, val errAt10: Double, val worstEleM: Double, val readout: Readout)

    /** A 40 min walk up and down with the barometer offset at 40 m, stepping to 10 m at 20 min over [rampS] seconds. */
    private fun converge(seed: Int, rampS: Int): Convergence {
        val sim = ReadoutSim(seed)
        val legs = listOf(ReadoutSim.Leg(1200, 1.2, climb = 0.1, course = winding), ReadoutSim.Leg(1200, 1.2, climb = -0.08, course = winding))
        val truth = sim.journey(legs)
        val step = ReadoutSim.T0 + 1_200_000
        val offset = { t: Long -> if (t < step) 40.0 else 10.0 + 30.0 * (1 - ((t - step) / 1000.0 / maxOf(rampS, 1)).coerceAtMost(1.0)) }
        val est = ReadoutEstimator(Activity.FOOT)
        var settle1: Long? = null
        var settle2: Long? = null
        var late = 0
        var lateWithin = 0
        var errAt10 = 0.0
        var worstEle = 0.0
        feed(est, sim.fixes(truth, slowSigma = 3.0), sim.pressure(truth, offset = offset)) { f, r ->
            val err = abs(r.barometer.offsetM?.let { it - offset(f.time) } ?: 99.0)
            val since = f.time - if (f.time < step) ReadoutSim.T0 else step
            if (f.time < step) { if (err >= 5) settle1 = null else if (settle1 == null) settle1 = since }
            else { if (err >= 5) settle2 = null else if (settle2 == null) settle2 = since }
            if (since == 600_000L) errAt10 = maxOf(errAt10, err)
            if (since >= 600_000) { late++; if (err < 5) lateWithin++ }
            if (r.barometer.state == BarometerState.IN_USE && since > 600_000) {
                worstEle = maxOf(worstEle, abs(r.elevation.value!! - truth[((f.time - ReadoutSim.T0) / 1000).toInt()].ele))
            }
        }
        return Convergence(settle1?.div(1000), settle2?.div(1000), lateWithin.toDouble() / late, errAt10, worstEle, est.readout(ReadoutSim.T0 + 2_400_000))
    }

    @Test
    fun barometerOffsetConvergesWithinFiveMetresOfASteppedTruth() {
        for (ramp in listOf(0, 120)) {
            val c = converge(4, ramp)
            println("T160 barometer, step over $ramp s: within 5 m of the offset from ${c.settleStartS} s after the start and ${c.settleStepS} s after the 30 m step; worst elevation error once settled ${c.worstEleM} m; counters ${c.readout.counters}")
            assertTrue((c.settleStartS ?: 9999) < 600 && (c.settleStepS ?: 9999) < 600, "settled after ${c.settleStartS} and ${c.settleStepS} s")
            assertEquals("barometer", c.readout.elevation.source)
            assertTrue(c.worstEleM < 8, "worst elevation ${c.worstEleM}")
        }
        // Twenty more noise draws each. The GNSS heights carry 5 m of white and 3 m of two-minute error, so about 2.5 m
        // of offset error remains at best and an unlucky draw sits near 3 sigma for minutes: the median settles within
        // 10 min, and none is worse than 8 m at 10 min.
        for (ramp in listOf(0, 120)) {
            val runs = (1..20).map { converge(100 + it, ramp) }
            val worstAt10 = runs.maxOf { it.errAt10 }
            val within = runs.minOf { it.within }
            println("T160 barometer, 20 seeds, step over $ramp s: settled (start/step, s) ${runs.map { "${it.settleStartS}/${it.settleStepS}" }}; worst error at 10 min $worstAt10 m; least share within 5 m after 10 min $within; worst elevation ${runs.maxOf { it.worstEleM }} m; suspects ${runs.sumOf { it.readout.counters.getValue("suspects") }}, recalibrations ${runs.sumOf { it.readout.counters.getValue("recalibrations") }}")
            val median = runs.map { maxOf(it.settleStartS ?: 9999, it.settleStepS ?: 9999) }.sorted()[runs.size / 2]
            println("T160 barometer, 20 seeds, step over $ramp s: median settling $median s")
            assertTrue(median < 600, "median settling $median s")
            assertTrue(worstAt10 < 8, "error at 10 min $worstAt10 m")
        }
    }

    @Test
    fun gnssOnlyHeightFollowsASummit() {
        // No barometer (most phones): up 0.15 m/s for 10 min, down for 10 min; the height must not overshoot the top.
        val sim = ReadoutSim(12)
        val truth = sim.journey(listOf(ReadoutSim.Leg(600, 1.2, climb = 0.15, course = winding), ReadoutSim.Leg(600, 1.2, climb = -0.15, course = winding)))
        val est = ReadoutEstimator(Activity.FOOT)
        var sq = 0.0
        var n = 0
        var worst = 0.0
        feed(est, sim.fixes(truth, slowSigma = 3.0)) { f, r ->
            val i = ((f.time - ReadoutSim.T0) / 1000).toInt()
            if (i >= 60) {
                val e = r.elevation.value!! - truth[i].ele
                sq += e * e; n++; worst = maxOf(worst, abs(e))
            }
            assertEquals("gnss", r.elevation.source)
        }
        val rms = kotlin.math.sqrt(sq / n)
        println("T160 GNSS-only summit: elevation RMS $rms m, worst $worst m")
        assertTrue(rms < 6 && worst < 15, "rms $rms worst $worst")
    }

    @Test
    fun suspectRuleDropsTheBarometerAtTwentyMetresPerSecondAndReopens() {
        val sim = ReadoutSim(5)
        val legs = listOf(ReadoutSim.Leg(300, 1.3), ReadoutSim.Leg(180, 20.0), ReadoutSim.Leg(300, 1.3))
        val truth = sim.journey(legs)
        val driveStart = ReadoutSim.T0 + 300_000
        val driveEnd = ReadoutSim.T0 + 480_000
        // In a car the barometer reads the cabin's dynamic pressure: 25 m off and noisy.
        val pressure = sim.pressure(truth, offset = { t -> if (t in driveStart until driveEnd) 55.0 + 8 * sim.gauss() else 30.0 })
        val est = ReadoutEstimator(Activity.FOOT)
        var baroAtDriveStart = -1L
        var firstSuspect: Long? = null
        var reopened: Long? = null
        feed(est, sim.fixes(truth), pressure) { f, r ->
            if (f.time == driveStart + 1000) baroAtDriveStart = r.counters.getValue("baroUpdates")
            if (r.barometer.state == BarometerState.SUSPECT && firstSuspect == null) firstSuspect = f.time
            if (f.time in driveStart + 2000 until driveEnd + 59_000) {
                assertEquals(BarometerState.SUSPECT, r.barometer.state, "at ${(f.time - ReadoutSim.T0) / 1000} s")
                assertEquals("speed", r.barometer.reason)
                assertEquals(baroAtDriveStart, r.counters.getValue("baroUpdates"))
                assertEquals("gnss", r.elevation.source)
            }
            if (f.time > driveEnd && r.barometer.state != BarometerState.SUSPECT && reopened == null) reopened = f.time
        }
        val end = est.readout(ReadoutSim.T0 + 780_000)
        println("T160 suspect: dropped ${(firstSuspect!! - driveStart) / 1000} s into the drive, reopened ${(reopened!! - driveEnd) / 1000} s after it; end ${end.barometer}; counters ${end.counters}")
        assertTrue(firstSuspect!! <= driveStart + 2000)
        assertTrue(reopened!! in driveEnd + 59_000..driveEnd + 62_000, "reopened at $reopened")
        assertEquals(1L, end.counters["suspects"])
        assertEquals(1L, end.counters["reopens"])
        assertTrue(end.counters.getValue("baroUpdates") > baroAtDriveStart + 200)
        assertEquals(BarometerState.IN_USE, end.barometer.state)
    }

    @Test
    fun disagreementAndJitterAlsoMakeTheBarometerSuspect() {
        val sim = ReadoutSim(6)
        val truth = sim.journey(listOf(ReadoutSim.Leg(600, 1.0)))
        // The barometer climbs 2 m/s from minute 4 (a lift the GNSS does not see, say): two 60 s windows disagree.
        val lift = ReadoutSim.T0 + 240_000
        val est = ReadoutEstimator(Activity.FOOT)
        val reasons = LinkedHashSet<String>()
        var firstAt: Long? = null
        feed(est, sim.fixes(truth, eleSigma = 2.0, vAcc = 4.0), sim.pressure(truth, offset = { t -> 30.0 + if (t > lift) (t - lift) / 1000.0 * 2 else 0.0 })) { f, r ->
            r.barometer.reason?.let { reasons += it; if (firstAt == null) firstAt = f.time }
            if (f.time < lift) assertEquals(null, r.barometer.reason)
        }
        println("T160 disagreement: suspect ${(firstAt!! - lift) / 1000} s after the lift started, reasons $reasons, counters ${est.readout(ReadoutSim.T0 + 600_000).counters}")
        assertTrue("disagreement" in reasons, "reasons $reasons")
        assertTrue(firstAt!! - lift <= 125_000)

        val est2 = ReadoutEstimator(Activity.FOOT)
        val reasons2 = LinkedHashSet<String>()
        feed(est2, sim.fixes(truth), sim.pressure(truth, sigma = 2.0)) { _, r -> r.barometer.reason?.let { reasons2 += it } }
        assertEquals(setOf("jitter"), reasons2)
    }

    @Test
    fun flightShowsFeetPerMinuteFromGnssOnly() {
        val sim = ReadoutSim(7)
        val climb = 1000 * 0.3048 / 60 // 1000 ft/min
        val legs = listOf(ReadoutSim.Leg(300, 60.0, climb = climb), ReadoutSim.Leg(300, 60.0))
        val truth = sim.journey(legs, startEle = 300.0)
        val est = ReadoutEstimator(Activity.AIR)
        val opts = est.options
        var worst = 0.0
        var levelHidden = 0
        var levelChecked = 0
        // A cabin barometer: given, never used.
        feed(est, sim.fixes(truth, posSigma = 5.0, vAcc = 5.0), sim.pressure(truth, sigma = 3.0)) { f, r ->
            val i = ((f.time - ReadoutSim.T0) / 1000).toInt()
            assertEquals(BarometerState.OFF, r.barometer.state)
            if (i in 60 until 300) {
                assertEquals(ValueState.SHOWN, r.climb.state, "climb at $i s")
                assertEquals("gnss", r.climb.source)
                val label = ReadoutText.of(r.climb, opts, ContourUnits.FEET)!!
                assertEquals("ft/min", label.unit)
                worst = maxOf(worst, abs(label.text.toDouble() - 1000))
            }
            if (i >= 360) { levelChecked++; if (r.climb.state == ValueState.HIDDEN) levelHidden++ }
        }
        val end = est.readout(ReadoutSim.T0 + 600_000)
        println("T160 flight: worst climb error $worst ft/min while climbing; climb hidden in $levelHidden of $levelChecked level readouts; counters ${end.counters}")
        assertTrue(worst <= 150, "worst $worst ft/min")
        assertTrue(levelHidden >= levelChecked * 0.8, "hidden $levelHidden of $levelChecked")
        assertEquals(0L, end.counters["baroUpdates"])
        assertEquals("gnss", end.elevation.source)
    }

    @Test
    fun gapDimsAndTheSpikeAfterItIsDropped() {
        val sim = ReadoutSim(8)
        val truth = sim.journey(listOf(ReadoutSim.Leg(120, 1.3)))
        val est = ReadoutEstimator(Activity.FOOT)
        feed(est, sim.fixes(truth)) { _, _ -> }
        val r = est.readout(ReadoutSim.T0 + 140_000)
        assertTrue(r.speed.dimmed && r.course.dimmed && r.elevation.dimmed)
        assertEquals(21.0, r.gapS)
        val last = sim.fixes(truth).last()
        est.onFix(last.copy(time = ReadoutSim.T0 + 141_000, speedMps = 45.0))
        val after = est.readout(ReadoutSim.T0 + 141_000)
        assertNull(after.gapS)
        assertEquals(1L, after.counters["spikesDropped"])
        assertTrue(after.speed.value!! < 2)
    }

    @Test
    fun roadUsesNoBarometerAndDemOnlyOnFoot() {
        val sim = ReadoutSim(9)
        val truth = sim.journey(listOf(ReadoutSim.Leg(300, 15.0)))
        val road = ReadoutEstimator(ActivityProfile.of(Activity.ROAD), dem = { _, _ -> 1600.0 })
        feed(road, sim.fixes(truth), sim.pressure(truth)) { _, r -> assertEquals(BarometerState.OFF, r.barometer.state) }
        val r = road.readout(ReadoutSim.T0 + 300_000)
        assertEquals(0L, r.counters["demUpdates"])
        assertEquals("gnss", r.elevation.source)
        assertEquals(ValueState.HIDDEN, r.climb.state)
        assertEquals("off", r.climb.reason)

        // The barometer switch (T161's) off on foot.
        val off = ReadoutEstimator(ActivityProfile.of(Activity.FOOT), ReadoutOptions.defaults(Activity.FOOT).copy(barometer = false))
        feed(off, sim.fixes(truth), sim.pressure(truth)) { _, _ -> }
        assertEquals(BarometerState.OFF, off.readout(ReadoutSim.T0 + 300_000).barometer.state)
    }

    @Test
    fun demPullsABiasedGnssHeightOnFootAndIsGatedOnSlopesAndStructures() {
        val sim = ReadoutSim(10)
        val truth = sim.journey(listOf(ReadoutSim.Leg(2400, 1.3, course = winding)))
        val flat = ElevationSampler { _, _ -> 1600.0 }
        val est = ReadoutEstimator(Activity.FOOT, dem = flat)
        val fixes = sim.fixes(truth, eleBias = 20.0)
        feed(est, fixes) { _, _ -> }
        val r = est.readout(ReadoutSim.T0 + 2_400_000)
        val demUpdates = r.counters.getValue("demUpdates")
        println("T160 DEM: ${demUpdates} DEM updates over 3.1 km; elevation ${r.elevation.value} m against a truth of 1600 m and GNSS 20 m high; source ${r.elevation.source}")
        assertTrue(demUpdates >= 15, "dem updates $demUpdates")
        assertTrue(abs(r.elevation.value!! - 1600) < 8, "elevation ${r.elevation.value}")
        assertEquals("gnss+dem", r.elevation.source)
        assertEquals(1600.0, r.heights.demM)

        // A 30 degree slope, or a bridge near: no DEM updates.
        val steep = ElevationSampler { lat, _ -> 1600.0 + (lat - ReadoutSim.LAT0) * 111_195.0 * 0.58 }
        val est2 = ReadoutEstimator(Activity.FOOT, dem = steep)
        feed(est2, fixes) { _, _ -> }
        assertEquals(0L, est2.readout(ReadoutSim.T0 + 2_400_000).counters["demUpdates"])
        val est3 = ReadoutEstimator(ActivityProfile.of(Activity.FOOT), dem = flat, nearStructure = { _, _ -> true })
        feed(est3, fixes) { _, _ -> }
        assertEquals(0L, est3.readout(ReadoutSim.T0 + 2_400_000).counters["demUpdates"])
    }

    @Test
    fun stationPressureSeedsTheOffset() {
        val sim = ReadoutSim(11)
        val truth = sim.journey(listOf(ReadoutSim.Leg(60, 1.0)))
        // A station 20 km away, an hour old, reporting the sea-level pressure that gives an offset of 30 m.
        val p = ReadoutSim.hPaAt(1630.0)
        val qnh = solveQnh(p, 1600.0)
        val est = ReadoutEstimator(Activity.FOOT)
        est.onStationPressure(StationPressure("TEST", qnh, ReadoutSim.LAT0 + 0.18, ReadoutSim.LON0, ReadoutSim.T0 - 3_600_000))
        est.onPressure(PressureSample(ReadoutSim.T0, p))
        val r0 = est.readout(ReadoutSim.T0)
        // Height from the barometer and the prior before any fix.
        assertEquals("barometer", r0.elevation.source)
        assertTrue(abs(r0.elevation.value!! - 1600) < 1, "elevation ${r0.elevation.value}")
        assertTrue(abs(r0.barometer.offsetM!! - 30) < 0.5)
        assertEquals(1L, r0.counters["priors"])
        assertEquals(listOf(STATION_SOURCE_ID), r0.provenance)
        // The same report again is not counted twice.
        est.onStationPressure(StationPressure("TEST", qnh, ReadoutSim.LAT0 + 0.18, ReadoutSim.LON0, ReadoutSim.T0 - 3_600_000))
        feed(est, sim.fixes(truth), sim.pressure(truth)) { _, _ -> }
        assertEquals(1L, est.readout(ReadoutSim.T0 + 60_000).counters["priors"])
    }

    private fun solveQnh(hPa: Double, heightM: Double): Double {
        var lo = 900.0
        var hi = 1100.0
        repeat(60) { val mid = (lo + hi) / 2; if (Altimeter.heightM(hPa, mid) < heightM) lo = mid else hi = mid }
        return (lo + hi) / 2
    }
}
