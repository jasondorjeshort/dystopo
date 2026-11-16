package io.github.jasondorjeshort.dystopo.routing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T291: a drive as a speed profile ([SpeedProfile]): the textbook cases under flat limits, a bend held at the lateral
 * limit, a jittered straight read as straight, a steep descent at idle fuel, and on a small town's drives the profile's
 * time never under the free-flow edge time.
 */
class SpeedProfileTest {
    private val flat = ProfileLimits.flat(lateral = 2.0, accel = 1.5, brake = 2.0)
    private val fuel = FuelModel.DEFAULT

    private fun close(expected: Double, actual: Double, tol: Double, label: String) =
        assertTrue(abs(expected - actual) <= tol, "$label: expected $expected within $tol, got $actual")

    private fun steps(lengthM: Double, roadMps: Double, riseM: Double = 0.0): List<ProfileStep> {
        val n = (lengthM / 10).toInt()
        return List(n) { ProfileStep(lengthM / n, roadMps, riseM / n) }
    }

    private fun open(n: Int) = DoubleArray(n) { Double.POSITIVE_INFINITY }

    /** Points in metres east ([xs]) and north ([ys]) of 0 N 0 E, as latitudes and longitudes. */
    private fun latLon(xs: DoubleArray, ys: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val r = SpeedProfile.Line.EARTH_M
        return DoubleArray(ys.size) { ys[it] / r * 180 / PI } to DoubleArray(xs.size) { xs[it] / r * 180 / PI }
    }

    /** One table row over the whole line, of [lengthM] at [kmh], with [riseM] of net climb. */
    private fun oneRow(lengthM: Double, kmh: Double, points: Int, riseM: Double = 0.0): RoutePrice {
        val part = PricePart(
            "edge", WayClass.SECONDARY, null, 0, 0.0, 1.0, false, 0, lengthM, kmh, lengthM / (kmh / 3.6), 0.0, 0.0,
            maxOf(0.0, riseM), maxOf(0.0, -riseM), 0, points - 1,
        )
        return RoutePrice(DrivePreference.TIME, DrivingSide.RIGHT, listOf(part), emptyList(), points)
    }

    @Test
    fun aStraightLevelRoadAtOneSpeedIsLengthOverSpeedAndTheSteadyFuel() {
        // On the grid alone, and laid on a line of points through [SpeedProfile.of], ends flying.
        val run = SpeedProfile.solve(steps(1000.0, 20.0), open(101), flat, fuel)
        close(50.0, run.timeS, 1e-9, "grid time")
        close(fuel.edgeJ(1000.0, 50.0, 0.0, 0.0, 1.0, 1.0), run.fuelJ, 1e-6, "grid fuel")
        val xs = DoubleArray(51) { it * 20.0 }
        val (lats, lons) = latLon(xs, DoubleArray(51))
        val p = SpeedProfile.of(oneRow(1000.0, 72.0, 51), lats, lons, flat, fuel, restAtEnds = false)
        close(1000.0 / 20.0, p.timeS, 1.0, "laid time")
        close(fuel.edgeJ(1000.0, 50.0, 0.0, 0.0, 1.0, 1.0), p.fuelJ, 1e-6 * p.fuelJ, "laid fuel")
        // With the class factor kept, the resistance carries the secondary class's unsteadiness, as the table model's does.
        close(fuel.edgeJ(1000.0, 50.0, 0.0, 0.0, fuel.unsteadiness(WayClass.SECONDARY), 1.0), p.fuelKeptJ, 1e-6 * p.fuelKeptJ, "kept fuel")
        assertEquals(0, p.holdCounts()[Hold.BEND], "a straight has no bend")
    }

    @Test
    fun aStopInTheMiddleCostsTheTextbookTime() {
        // 2 km at 20 m/s with a stop at 1 km: cruise time plus v / 2a to regain and v / 2b to brake.
        val caps = open(201).also { it[100] = 0.0 }
        val run = SpeedProfile.solve(steps(2000.0, 20.0), caps, flat, fuel)
        close(100.0 + 20.0 / (2 * 1.5) + 20.0 / (2 * 2.0), run.timeS, 0.05, "time with a stop")
        assertEquals(0.0, run.speeds[100], "stopped at the stop")
        // The kinetic energy to regain 20 m/s is in the fuel: more than the flying cruise's by at least m v^2 / 2 / eta
        // less the idle and resistance saved while slower.
        val steady = SpeedProfile.solve(steps(2000.0, 20.0), open(201), flat, fuel)
        assertTrue(run.fuelJ > steady.fuelJ, "a stop costs fuel")
    }

    @Test
    fun aShortBlockNeverReachesItsSpeed() {
        // 100 m at 50 km/h from rest to rest: the peak is sqrt(2 L a b / (a + b)), the time vp / a + vp / b.
        val caps = open(11).also { it[0] = 0.0; it[10] = 0.0 }
        val road = 50 / 3.6
        val run = SpeedProfile.solve(steps(100.0, road), caps, flat, fuel)
        val vp = sqrt(2 * 100.0 * 1.5 * 2.0 / 3.5)
        assertTrue(vp < road, "the textbook peak is under the road's speed")
        assertTrue(run.speeds.max() < road - 0.5, "the profile never reaches ${road}: ${run.speeds.max()}")
        close(vp / 1.5 + vp / 2.0, run.timeS, 0.05, "short block time")
    }

    @Test
    fun aBendOfKnownRadiusIsHeldAtTheLateralLimit() {
        // 300 m straight, a quarter circle of 100 m radius in points 2 m apart, 300 m straight; 90 km/h road.
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (i in 0..150) { xs += i * 2.0; ys += 0.0 }
        val r = 100.0
        val arcPts = (PI / 2 * r / 2).toInt()
        for (i in 1..arcPts) { val t = i * (PI / 2) / arcPts; xs += 300.0 + r * sin(t); ys += r - r * cos(t) }
        for (i in 1..150) { xs += 400.0; ys += 100.0 + i * 2.0 }
        val (lats, lons) = latLon(xs.toDoubleArray(), ys.toDoubleArray())
        val length = 600.0 + PI / 2 * r
        val p = SpeedProfile.of(oneRow(length, 90.0, xs.size), lats, lons, flat, fuel, restAtEnds = false)
        val expected = sqrt(2.0 * r) // 14.1 m/s, 51 km/h
        // Mid-arc the profile runs at the bend's speed.
        val mid = p.stations.indices.minBy { abs(p.stations[it] - (300.0 + PI / 4 * r)) }
        close(expected, p.run.speeds[mid], 0.03 * expected, "mid-arc speed")
        assertEquals(Hold.BEND, p.holds[mid])
        assertTrue(p.holdCounts().getValue(Hold.BEND) >= 1)
        assertTrue(p.timeS > length / 25.0, "the bend costs time")
    }

    @Test
    fun aStraightLineOfCloselySpacedPointsReadsAsStraight() {
        // Points 1 m apart, each 0.2 m to one side or the other of the line, on a 90 km/h road.
        val n = 1001
        val xs = DoubleArray(n) { it * 1.0 }
        val ys = DoubleArray(n) { if (it % 2 == 0) 0.2 else -0.2 }
        val (lats, lons) = latLon(xs, ys)
        val p = SpeedProfile.of(oneRow(1000.0, 90.0, n), lats, lons, flat, fuel, restAtEnds = false)
        assertEquals(0, p.holdCounts().getValue(Hold.BEND), "no bend on a jittered straight")
        close(40.0, p.timeS, 1.0, "jittered straight time")
    }

    @Test
    fun aDescentSteeperThanTheResistanceCostsIdleFuelOnly() {
        // 10 % down at 20 m/s: m g sin is about 1,570 N against 390 N of resistance.
        val run = SpeedProfile.solve(steps(1000.0, 20.0, riseM = -100.0), open(101), flat, fuel)
        close(fuel.idleW * run.timeS, run.fuelJ, 1e-6, "idle fuel only")
        close(fuel.idleW * run.timeS, run.fuelKeptJ, 1e-6, "idle fuel only, class kept")
    }

    @Test
    fun turnSpeedsFollowTheAngle() {
        val t = TurnSpeeds.FIRST
        assertEquals(90.0, t.of(10.0, false, false, 90.0), "no limit under 20 degrees")
        assertEquals(35.0, t.of(45.0, false, false, 90.0), 1e-9)
        assertEquals(20.0, t.of(90.0, false, false, 90.0), 1e-9)
        assertEquals(15.0, t.of(-90.0, true, false, 90.0), 1e-9)
        assertEquals(12.0, t.of(135.0, true, false, 90.0), 1e-9)
        assertEquals(8.0, t.of(179.0, false, true, 90.0), 1e-9)
        assertEquals(10.0, t.of(90.0, false, false, 10.0), 1e-9, "never above the road's speed")
        assertTrue(ProfileLimits.LOW.turns.kerb90Kmh < t.kerb90Kmh && ProfileLimits.HIGH.turns.kerb90Kmh > t.kerb90Kmh)
        // The bend speed solves v^2 = a(v) r: 100 m at 2.0 m/s2 (under 60 km/h).
        assertEquals(sqrt(200.0), ProfileLimits.FIRST.bendMps(100.0), 1e-6)
    }

    /** The town of [RoutePriceTest], smaller: a grid of minor streets with a named main street and an avenue. */
    private fun town(): RoutingGraph {
        val minor = WayAttrs(WayClass.MINOR, Surface.PAVED)
        val main = WayAttrs(WayClass.SECONDARY, Surface.PAVED, name = "Main Street")
        val avenue = WayAttrs(WayClass.TERTIARY, Surface.PAVED, name = "Hill Avenue")
        val lines = ArrayList<WayLine>()
        for (i in 0..8) {
            val v = i * 0.002
            lines += WayLine.of(if (i == 4) main else minor, v to 0.0, v to 0.016)
            lines += WayLine.of(if (i == 3) avenue else minor, 0.0 to v, 0.016 to v)
        }
        lines += WayLine.of(WayAttrs(WayClass.MINOR, Surface.UNPAVED), 0.001 to 0.001, 0.015 to 0.015)
        val ground = ElevationSampler { lat, lon -> 100.0 + 40.0 * sin(lat * 400) * cos(lon * 300) }
        return RoutingGraphBuilder(ground).build(lines, GraphSource("test", "fp", "", 14, 0, drive = true))
    }

    @Test
    fun theProfileIsNeverQuickerThanFreeFlowOnATownsDrives() {
        val g = town()
        val drives = listOf(
            (0.0005 to 0.0003) to (0.0155 to 0.0159),
            (0.0159 to 0.0151) to (0.0011 to 0.0007),
            (0.0083 to 0.0001) to (0.0039 to 0.0157),
        )
        var turnsSeen = 0
        for ((a, b) in drives) for (pref in listOf(DrivePreference.TIME, DrivePreference.FUEL)) {
            val router = TileRouter(WholeGraph(g), driveTurns = true, metric = pref.metric, keepTrace = true)
            val r = router.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE)
            assertIs<RouteResult.Found>(r)
            val plan = r.plan
            val price = router.price(plan, DrivePreference.TIME)
            val lats = DoubleArray(plan.route.points.size) { plan.route.points[it].lat }
            val lons = DoubleArray(plan.route.points.size) { plan.route.points[it].lon }
            for (limits in listOf(ProfileLimits.LOW, ProfileLimits.FIRST, ProfileLimits.HIGH)) {
                val p = SpeedProfile.of(price, lats, lons, limits)
                val label = "$a -> $b ${pref.key} ${limits.key}"
                assertTrue(p.integralS >= price.edgeS - 1e-6, "$label: ${p.integralS} under free flow ${price.edgeS}")
                assertTrue(p.timeS >= p.integralS, label)
                close(price.lengthM, p.lengthM, 1e-6, "$label length")
                assertTrue(p.fuelJ > 0 && p.fuelKeptJ >= p.fuelJ, label)
                turnsSeen += p.turns.size
            }
            // Gentler limits are never quicker.
            val low = SpeedProfile.of(price, lats, lons, ProfileLimits.LOW)
            val high = SpeedProfile.of(price, lats, lons, ProfileLimits.HIGH)
            assertTrue(low.integralS >= high.integralS, "low limits are slower")
        }
        assertTrue(turnsSeen > 0, "the town's drives price turns")
    }
}
