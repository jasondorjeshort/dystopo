package io.github.jasondorjeshort.dystopo.app.tracks

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.store.TrackSummary
import io.github.jasondorjeshort.dystopo.sync.RouteListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T262: the Tracks and routes list's rows and words (ADR 0031's strings, pure). */
class TracksListTest {
    private val t1 = TrackSummary("00000000-0000-4000-8000-0000000000a1", "Morning walk", 1_000L, 2_000L, false, 40, 0, Activity.FOOT)
    private val t2 = TrackSummary("00000000-0000-4000-8000-0000000000a2", "Imported", 3_000L, 3_000L, false, 10, 0, null)
    private val live = TrackSummary("00000000-0000-4000-8000-0000000000a3", "Now", 5_000L, 5_500L, true, 5, 0, Activity.BIKE)
    private val r1 = RouteListItem("00000000-0000-4000-8000-0000000000b1", "To the lake", "hike", 3200.0, 150.0, 20.0, 3600.0, 3, 1, 0.0, 4_000L, createdAt = 4_000L)
    private val r2 = RouteListItem("00000000-0000-4000-8000-0000000000b2", "", "bike", 900.0, null, null, 300.0, 2, 0, 0.0, 6_000L, createdAt = 2_000L)

    private fun rows(filter: TracksUi.Filter, hiddenTracks: Set<String> = emptySet(), hiddenRoutes: Set<String> = emptySet(), sort: TracksUi.Sort = TracksUi.Sort.NEWEST) = listRows(
        listOf(live, t2, t1), mapOf(t1.id to 2000.0, t2.id to 500.0), live.id, 120.0, hiddenTracks, listOf(r1, r2), hiddenRoutes, filter,
        ContourUnits.METERS, { ms -> "d$ms" }, { r -> "${r.distanceM.toInt()} m" }, sort,
    )

    @Test
    fun theThreeSorts() {
        // T272: names A to Z ignoring case ("Imported", "Morning walk", "Now", "Route", "To the lake").
        assertEquals(listOf(t2.id, t1.id, live.id, r2.id, r1.id), rows(TracksUi.Filter.ALL, sort = TracksUi.Sort.NAME).map { it.id })
        // Longest first: r1 3200, t1 2000, r2 900, t2 500, the recording's live 120.
        assertEquals(listOf(r1.id, t1.id, r2.id, t2.id, live.id), rows(TracksUi.Filter.ALL, sort = TracksUi.Sort.LENGTH).map { it.id })
        assertEquals(listOf(3200.0, 2000.0, 900.0, 500.0, 120.0), rows(TracksUi.Filter.ALL, sort = TracksUi.Sort.LENGTH).map { it.lengthM })
        assertEquals(rows(TracksUi.Filter.ALL).map { it.id }, rows(TracksUi.Filter.ALL, sort = TracksUi.Sort.NEWEST).map { it.id })
        // A row with no length goes last; equal names go newest first.
        val a = ListRow("track", "a", "Same", "", null, true, false, 1L, lengthM = null)
        val b = ListRow("track", "b", "same", "", null, true, false, 2L, lengthM = 10.0)
        assertEquals(listOf("b", "a"), sortRows(listOf(a, b), TracksUi.Sort.LENGTH).map { it.id })
        assertEquals(listOf("b", "a"), sortRows(listOf(a, b), TracksUi.Sort.NAME).map { it.id })
        assertEquals(listOf("Newest", "Name", "Length"), TracksUi.Sort.entries.map { sortLabel(it) })
    }

    @Test
    fun selectModesWords() {
        assertEquals("3 selected", selectedLine(3))
        assertEquals("Delete 2 tracks and 1 route? They are deleted on your other devices when they sync.", bulkDeleteQuestion(2, 1))
        assertEquals("Delete 1 track? It is deleted on your other devices when they sync.", bulkDeleteQuestion(1, 0))
        assertEquals("Delete 3 routes? They are deleted on your other devices when they sync.", bulkDeleteQuestion(0, 3))
        assertEquals(listOf("Show", "Hide", "Export GPX", "Share GPX", "Delete"), SELECT_ACTIONS)
    }

    @Test
    fun allIsNewestFirstWithBothKinds() {
        val all = rows(TracksUi.Filter.ALL)
        assertEquals(listOf(live.id, r1.id, t2.id, r2.id, t1.id), all.map { it.id })
        assertEquals(listOf("track", "route", "track", "route", "track"), all.map { it.kind })
        assertTrue(all.all { it.shown })
    }

    @Test
    fun theFiltersKeepTheirKind() {
        assertEquals(listOf(live.id, t2.id, t1.id), rows(TracksUi.Filter.TRACKS).map { it.id })
        assertEquals(listOf(r1.id, r2.id), rows(TracksUi.Filter.ROUTES).map { it.id })
    }

    @Test
    fun theLines() {
        val all = rows(TracksUi.Filter.ALL).associateBy { it.id }
        val km2 = io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(2000.0, ContourUnits.METERS)
        assertEquals("On foot · d1000 · $km2", all.getValue(t1.id).line)
        assertEquals("Track · d3000 · 500 m", all.getValue(t2.id).line)
        assertEquals("Bike · d5000 · 120 m · recording", all.getValue(live.id).line, "the recording's live distance")
        assertTrue(all.getValue(live.id).recording)
        assertEquals("Hike route · d4000 · 3200 m", all.getValue(r1.id).line)
        assertEquals("1 via point · 1 waypoint", all.getValue(r1.id).counts)
        assertEquals("Route", all.getValue(r2.id).name, "an unnamed route reads as the trip card names it")
        assertEquals("Bike route · d2000 · 900 m", all.getValue(r2.id).line)
    }

    @Test
    fun hiddenRowsStayListedMarkedHidden() {
        val all = rows(TracksUi.Filter.ALL, hiddenTracks = setOf(t1.id), hiddenRoutes = setOf(r2.id)).associateBy { it.id }
        assertEquals(5, all.size, "a hidden item is still in the list")
        assertFalse(all.getValue(t1.id).shown)
        assertFalse(all.getValue(r2.id).shown)
        assertTrue(all.getValue(t2.id).shown)
        assertEquals("Hidden", shownWord(false))
        assertEquals("Shown", shownWord(true))
        assertEquals("Hide Morning walk on the map", eyeAction(true, "Morning walk"))
        assertEquals("Show Morning walk on the map", eyeAction(false, "Morning walk"))
    }

    @Test
    fun menusAndQuestions() {
        assertEquals(listOf("Statistics", "Rename", "Export GPX", "Share GPX", "Go", "Activity", "Report", "Delete"), menuOf("track"))
        assertFalse("Delete" in menuOf("track", recording = true), "a recording ends on its panel, with Discard")
        assertEquals(listOf("Open", "Rename", "Export GPX", "Share GPX", "Go", "Share link", "Delete"), menuOf("route"))
        assertEquals("Delete the track Morning walk? It is deleted on your other devices when they sync.", deleteQuestion("track", "Morning walk"))
        assertEquals("Delete the route To the lake? It is deleted on your other devices when they sync.", deleteQuestion("route", "To the lake"))
        assertEquals("Tracks · 3", filterLabel(TracksUi.Filter.TRACKS, 3))
        assertEquals("All · 0", filterLabel(TracksUi.Filter.ALL, 0))
        assertEquals(TRACKS_EMPTY, emptyLine(TracksUi.Filter.TRACKS))
        assertEquals("track:x", rowKey("track", "x"))
    }

    @Test
    fun noOtherProductNames() {
        val words = listOf(TITLE, TRACKS_EMPTY, ALL_EMPTY, NOTHING_SELECTED, SHARE_GPX, bulkDeleteQuestion(1, 1)) + TRACKS_LIST_HELP.map { it.text(ContourUnits.METERS) }
        for (w in words) assertFalse(Regex("(?i)gaia|osmand|locus|strava|komoot|garmin|alltrails|dropbox|google").containsMatchIn(w), w)
    }
}
