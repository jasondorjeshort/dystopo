package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.routing.DriveMetric
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
}
