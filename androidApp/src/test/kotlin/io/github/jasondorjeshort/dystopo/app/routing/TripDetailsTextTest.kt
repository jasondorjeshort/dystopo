package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.app.trips.tripModeWord
import io.github.jasondorjeshort.dystopo.app.tracks.TracksUi
import io.github.jasondorjeshort.dystopo.app.tracks.listRows
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.TripKind
import io.github.jasondorjeshort.dystopo.routing.WaterBasis
import io.github.jasondorjeshort.dystopo.routing.WaterClass
import io.github.jasondorjeshort.dystopo.routing.WaterItem
import io.github.jasondorjeshort.dystopo.routing.WaterKind
import io.github.jasondorjeshort.dystopo.sync.RouteListItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** T278: the Trip details row's words, a kept trip's word on its card and in the Tracks and routes list (ADR 0031, pure). */
class TripDetailsTextTest {
    private fun item(kind: WaterKind) = WaterItem(100.0, kind, WaterClass.LIKELY_FLOWING, WaterBasis.MAPPED, null, null, false, false, false, null, 0.0, 46.8, -121.7)

    @Test
    fun theSummaryReadsWhatAnswered() {
        val streams = List(3) { item(WaterKind.STREAM) }
        assertEquals("3 water crossings · steepest slope 38 degrees", TripDetailsText.summary(streams, 38, working = false))
        assertEquals("1 water crossing", TripDetailsText.summary(listOf(item(WaterKind.STREAM)), null, working = true))
        assertEquals("2 water places · steepest slope 12 degrees", TripDetailsText.summary(listOf(item(WaterKind.STREAM), item(WaterKind.SPRING)), 12, working = false))
        assertEquals("No water found · steepest slope 5 degrees", TripDetailsText.summary(emptyList(), 5, working = false))
        assertEquals("steepest slope 41 degrees", TripDetailsText.summary(null, 41, working = true))
        assertEquals(TripDetailsText.WORKING, TripDetailsText.summary(null, null, working = true))
        assertEquals(TripDetailsText.PLAIN, TripDetailsText.summary(null, null, working = false))
        assertEquals("Trip details", TripDetailsText.TITLE)
        assertEquals("Water, terrain and start time", TripDetailsText.PLAIN)
        assertEquals("Working out water and terrain", TripDetailsText.WORKING)
    }

    @Test
    fun aKeptWalkIsNamedAWalk() {
        assertEquals("Walk", tripModeWord("hike", TripKind.EVERYDAY))
        assertEquals("Hike", tripModeWord("hike", TripKind.BACKCOUNTRY))
        assertEquals("Hike", tripModeWord("hike", null), "a trip kept before T278 reads as before")
        assertEquals("Bike", tripModeWord("bike", TripKind.EVERYDAY))
        assertEquals("Drive", tripModeWord("drive", null))
        assertEquals("Line", tripModeWord("crow", null))
        assertEquals("Ski (unknown mode)", tripModeWord("ski", null))
        val walk = RouteListItem("00000000-0000-4000-8000-0000000000c1", "Chamonix streets", "hike", 2283.0, 20.0, 20.0, 1800.0, 2, 0, 0.0, 1_000L, createdAt = 1_000L, tripKind = "everyday")
        val before = walk.copy(id = "00000000-0000-4000-8000-0000000000c2", tripKind = null, createdAt = 500L)
        val rows = listRows(emptyList(), emptyMap(), null, 0.0, emptySet(), listOf(walk, before), emptySet(), TracksUi.Filter.ROUTES, ContourUnits.METERS, { "d" }, { "2.3 km" })
        assertEquals(listOf("Walk route · d · 2.3 km", "Hike route · d · 2.3 km"), rows.map { it.line })
    }
}
