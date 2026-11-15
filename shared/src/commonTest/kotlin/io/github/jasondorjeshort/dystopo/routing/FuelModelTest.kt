package io.github.jasondorjeshort.dystopo.routing

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T284 (ADR 0063): the fuel model ([FuelModel]) and its invariants: the floor and the divisor computed from the
 * parameters (no speed is cheaper), factors under 1 refused, no turn under zero, and on seeded random graphs no directed
 * edge's fuel cost under the potential's factor times its length.
 */
class FuelModelTest {
    private val m = FuelModel.DEFAULT

    @Test
    fun theFloorAndTheDivisorAreComputedAndNoSpeedIsCheaper() {
        // ADR 0063 Decision 3's figures at the first values.
        assertEquals(857.9, m.floorJPerM, 0.1)
        assertEquals(63.2, m.floorMps * 3.6, 0.2)
        assertEquals(1323.9, m.divisorJPerM, 0.1)
        assertEquals(50.2, m.divisorMps * 3.6, 0.2)
        assertEquals(0.648, m.minCostPerM, 0.001)
        // Scanning every speed from 0.5 to 60 m/s finds nothing under either.
        var v = 0.5
        while (v <= 60.0) {
            val flat = m.edgeJ(1000.0, 1000.0 / v, 0.0, 0.0, m.minClassFactor, 1.0) / 1000.0
            assertTrue(flat >= m.divisorJPerM - 1e-9, "flat at $v m/s: $flat")
            val downhill = m.edgeJ(1000.0, 1000.0 / v, 0.0, 1000.0, m.minClassFactor, 1.0) / 1000.0 // the credit at its cap
            assertTrue(downhill >= m.floorJPerM - 1e-9, "downhill at $v m/s: $downhill")
            v += 0.01
        }
        // The floor is reached: at its own speed with the credit at its cap.
        assertEquals(m.floorJPerM, m.edgeJ(1000.0, 1000.0 / m.floorMps, 0.0, 1000.0, m.minClassFactor, 1.0) / 1000.0, 1e-6)
        // The survey's arithmetic: a metre of climb is about 34 m of flat driving at the best speed.
        assertEquals(34.0, m.massKg * m.gravity / m.efficiency / m.divisorJPerM, 0.5)
    }

    @Test
    fun aModelWithAFactorUnderOneIsRefused() {
        assertFailsWith<IllegalArgumentException> { FuelModel(classFactor = FuelModel.FIRST_CLASS_FACTORS + (WayClass.MOTORWAY to 0.99)) }
        assertFailsWith<IllegalArgumentException> { FuelModel(unpavedFactor = 0.9) }
        assertFailsWith<IllegalArgumentException> { FuelModel(classFactor = FuelModel.FIRST_CLASS_FACTORS - WayClass.TRACK) }
        assertFailsWith<IllegalArgumentException> { FuelModel(efficiency = 0.0) }
        // A model with larger factors is accepted, and its hash differs.
        val raised = FuelModel(classFactor = FuelModel.FIRST_CLASS_FACTORS + (WayClass.MINOR to 1.5))
        assertTrue(raised.hash != m.hash)
        assertEquals(m.hash, FuelModel().hash, "the hash is of the parameters, not the instance")
    }

    @Test
    fun noTurnCostsLessThanZero() {
        for (kmh in 5..130) {
            val v = kmh / 3.6
            for (s in listOf(0.0, 0.5, 3.0, 9.0, 25.0)) {
                val j = m.turnJ(s, v)
                assertTrue(j >= 0.0, "a $s s turn onto a $kmh km/h road: $j J")
                if (s == 0.0) assertEquals(0.0, j, "a turn of no seconds costs nothing")
                else assertTrue(j >= m.idleW * s - 1e-9, "at least the idle")
            }
        }
        // Onto a road slower than the turn speed: the idle only (never a credit).
        assertEquals(m.idleW * 3.0, m.turnJ(3.0, 5 / 3.6), 1e-9)
        // The survey's figure: a turn at 20 km/h onto a 50 km/h road regains about 280 m of flat driving.
        assertEquals(280.0, (m.turnJ(1.0, 50 / 3.6) - m.idleW) / m.divisorJPerM, 10.0)
        // Through CostModel: nothing for a turn of no seconds, under both metrics.
        val g = RoutingGraphBuilder().build(listOf(WayLine.of(WayAttrs(WayClass.MINOR, Surface.PAVED), 0.0 to 0.0, 0.0 to 0.01)), GraphSource("f", "fp", "", 14, 0, drive = true))
        val fuel = CostModel(TravelMode.DRIVE, metric = DriveMetric.FUEL)
        assertEquals(0.0, fuel.turnCost(0.0, g, 0))
        assertTrue(fuel.turnCost(3.0, g, 0) > 0)
        assertEquals(CostModel(TravelMode.DRIVE).secondsCost(3.0), CostModel(TravelMode.DRIVE).turnCost(3.0, g, 0))
    }

    /**
     * A jittered lattice of every drivable class over steep ground (descents far past the credit's cap), with tagged
     * speeds of 5 and 130, unpaved edges and one-ways. T286: one line in five a bridge or a tunnel, so its fuel reads the
     * straight grade between its ends, which can be steeper than the terrain's average over the line.
     */
    private fun randomGraph(rnd: Random, n: Int = 7, step: Double = 0.002): RoutingGraph {
        val classes = FuelModel.DRIVABLE
        val peaks = List(3) { Triple(rnd.nextDouble(0.0, n * step), rnd.nextDouble(0.0, n * step), rnd.nextDouble(100.0, 600.0)) }
        val ground = ElevationSampler { lat, lon ->
            peaks.sumOf { (a, b, h) -> h / (1 + ((lat - a) * (lat - a) + (lon - b) * (lon - b)) / (step * step)) }
        }
        fun attrs(): WayAttrs {
            val flags = when (rnd.nextInt(8)) { 0 -> WayAttrs.ONEWAY; 1 -> WayAttrs.ONEWAY_REVERSE; else -> 0 }
            val speed = when (rnd.nextInt(6)) { 0 -> 5; 1 -> 130; 2 -> 30 + 10 * rnd.nextInt(8); else -> 0 }
            val brunnel = rnd.nextInt(5) == 0 // T286
            return WayAttrs(
                classes[rnd.nextInt(classes.size)], if (rnd.nextInt(4) == 0) Surface.UNPAVED else Surface.PAVED, flags = flags, maxspeedKmh = speed,
                brunnel = brunnel, tunnel = brunnel && rnd.nextBoolean(),
            )
        }
        val lines = ArrayList<WayLine>()
        fun p(i: Int, j: Int) = (i * step + rnd.nextDouble(-0.2, 0.2) * step) to (j * step + rnd.nextDouble(-0.2, 0.2) * step)
        val pts = Array(n) { i -> Array(n) { j -> p(i, j) } }
        for (i in 0 until n) for (j in 0 until n) {
            if (j + 1 < n && rnd.nextInt(10) < 8) lines += WayLine.of(attrs(), pts[i][j], pts[i][j + 1])
            if (i + 1 < n && rnd.nextInt(10) < 8) lines += WayLine.of(attrs(), pts[i][j], pts[i + 1][j])
        }
        return RoutingGraphBuilder(ground).build(lines, GraphSource("fuel-random.pmtiles", "fp", "", 14, 0, drive = true))
    }

    @Test
    fun noDirectedEdgeCostsLessThanTheFactorTimesItsLengthOnRandomGraphs() {
        val model = CostModel(TravelMode.DRIVE, metric = DriveMetric.FUEL)
        val factor = model.minWeight
        assertEquals(m.minCostPerM, factor)
        var edges = 0
        var steepDown = 0
        var slow = 0
        var fast = 0
        var unpaved = 0
        var bridges = 0
        var tunnels = 0
        val seen = HashSet<WayClass>()
        var closest = Double.POSITIVE_INFINITY
        for (seed in 1..20) {
            val g = randomGraph(Random(seed))
            for (e in 0 until g.edgeCount) {
                if (!model.usable(g, e)) continue
                seen += g.wayClass(e)
                if (g.speedKmh(e) == 5) slow++
                if (g.speedKmh(e) == 130) fast++
                if (g.surface(e) == Surface.UNPAVED) unpaved++
                when (g.brunnel(e)) { WayAttrs.BRIDGE -> bridges++; WayAttrs.TUNNEL -> tunnels++ } // T286
                for (fwd in listOf(true, false)) {
                    if (!model.allows(g, e, fwd)) continue
                    val c = model.partCost(g, e, if (fwd) 0.0 else 1.0, if (fwd) 1.0 else 0.0)
                    val len = g.edgeLength[e].toDouble()
                    assertTrue(c >= factor * len - 1e-9, "seed $seed edge $e ${g.wayClass(e)} fwd $fwd: $c < $factor x $len")
                    closest = minOf(closest, c / len)
                    // Partial edges too, as at a route's ends.
                    for (f in listOf(0.1, 0.5, 0.9)) assertTrue(model.partCost(g, e, if (fwd) 0.0 else 1.0, if (fwd) f else 1 - f) >= factor * len * f - 1e-9)
                    edges++
                    val down = model.dropM(g, e, if (fwd) 0.0 else 1.0, if (fwd) 1.0 else 0.0) // T286: a marked edge's straight grade
                    if (model.fuel.massKg * model.fuel.gravity * down > 300.0 * len) steepDown++ // past the cap of a slow car's resistance
                }
            }
        }
        assertTrue(seen.containsAll(FuelModel.DRIVABLE), "every class: $seen")
        assertTrue(edges > 1000 && steepDown > 50 && slow > 20 && fast > 20 && unpaved > 50, "edges $edges, steep descents $steepDown, 5 km/h $slow, 130 km/h $fast, unpaved $unpaved")
        assertTrue(bridges > 30 && tunnels > 30, "marked edges: bridges $bridges, tunnels $tunnels")
        assertTrue(closest < factor * 1.6, "some edge comes near the floor: $closest per metre, factor $factor")
    }
}
