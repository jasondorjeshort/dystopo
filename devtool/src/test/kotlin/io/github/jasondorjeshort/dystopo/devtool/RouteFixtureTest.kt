package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.routing.DriveMetric
import io.github.jasondorjeshort.dystopo.routing.DrivePreference
import io.github.jasondorjeshort.dystopo.routing.LinePrice
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.TileRouter
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.routing.WholeGraph
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** T284: the committed synthetic fixture keeps showing least time and least fuel apart (ADR 0063: no public drive need). */
class RouteFixtureTest {
    @Test
    fun theFuelPassFixtureDiffersBetweenTimeAndFuelBothWays() {
        val file = listOf(File("fixtures/fuel-pass.json"), File("devtool/fixtures/fuel-pass.json")).first { it.isFile }
        val fx = RouteFixture.load(file)
        assertEquals(setOf("west-east", "east-west"), fx.drives.keys)
        for ((label, ends) in fx.drives) {
            val (a, b) = ends
            fun plan(metric: DriveMetric) = TileRouter(WholeGraph(fx.graph), metric = metric).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE)
                .let { assertIs<RouteResult.Found>(it, "$label $metric: $it").plan }
            val time = plan(DriveMetric.TIME)
            val fuel = plan(DriveMetric.FUEL)
            assertTrue(time.via.any { it.first == "Pass Road" } && fuel.via.any { it.first == "Valley Road" }, "$label: ${time.via} / ${fuel.via}")
            assertTrue(fuel.timeS > time.timeS && fuel.fuelIndex!! < time.fuelIndex!! * 0.95, "$label: ${fuel.timeS} s ${fuel.fuelIndex} J against ${time.timeS} s ${time.fuelIndex} J")
        }
    }

    /**
     * T290: on the fixture, each drive's cost table sums to its plan's `timeS`, `turnCostS`, `cost` and `fuelIndex` (within
     * one part in a million) under time, fuel and quick (quick: edges only, no turn rows); each preference's route priced
     * under another is never cheaper than that one's own route, and a route's own line priced again is its plan.
     */
    @Test
    fun theFuelPassTablesSumToThePlans() {
        val file = listOf(File("fixtures/fuel-pass.json"), File("devtool/fixtures/fuel-pass.json")).first { it.isFile }
        val fx = RouteFixture.load(file)
        fun close(expected: Double, actual: Double, label: String) =
            assertTrue(kotlin.math.abs(expected - actual) <= 1e-6 * maxOf(1.0, kotlin.math.abs(expected)), "$label: plan $expected, table $actual")
        for ((label, ends) in fx.drives) {
            val (a, b) = ends
            val plans = DrivePreference.entries.associateWith { p ->
                val router = TileRouter(WholeGraph(fx.graph), driveTurns = p.turns, metric = p.metric, keepTrace = true)
                router.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE).let { assertIs<RouteResult.Found>(it, "$label ${p.key}: $it").plan }
            }
            for ((p, plan) in plans) {
                val router = plan.trace!!.router
                val price = router.price(plan)
                val what = "$label ${p.key}"
                close(plan.timeS, price.timeS, "$what timeS")
                close(plan.cost, price.cost, "$what cost")
                close(plan.fuelIndex!!, price.fuelJ, "$what fuelIndex")
                assertEquals(plan.turns, price.turns, "$what turns")
                if (p == DrivePreference.QUICK) assertTrue(price.joins.none { it.kind == "turn" }, "$what: no turn rows")
                else close(plan.turnCostS!!, price.turnS, "$what turnCostS")
                assertEquals(plan.route.points.size, price.points, what)
                for ((q, other) in plans) if (q != p) {
                    val theirs = other.trace!!.router.price(other, p)
                    assertTrue(theirs.cost >= price.cost * (1 - 1e-9), "$what: the ${q.key} route costs ${theirs.cost} under ${p.key}, its own ${price.cost}")
                }
                val line = router.priceLine(plan.route.points.map { it.lat to it.lon }, p)
                assertIs<LinePrice.Priced>(line, what)
                close(plan.cost, line.price.cost, "$what from its line")
            }
        }
    }
}
