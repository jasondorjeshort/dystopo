package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.app.LocationPuck
import io.github.jasondorjeshort.dystopo.app.aircraft.AircraftLayer
import io.github.jasondorjeshort.dystopo.app.divergence.DivergenceLayer
import io.github.jasondorjeshort.dystopo.app.nav.NavMapLayer
import io.github.jasondorjeshort.dystopo.app.packs.CatalogOverlay
import io.github.jasondorjeshort.dystopo.app.readout.WayDownWatch
import io.github.jasondorjeshort.dystopo.app.routing.RouteOverlay
import io.github.jasondorjeshort.dystopo.app.routing.WaterMarkers
import io.github.jasondorjeshort.dystopo.app.search.SearchMarker
import io.github.jasondorjeshort.dystopo.app.trips.TripOverlay
import io.github.jasondorjeshort.dystopo.app.viewshed.ViewshedOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T383: the names table ([MapMarks]) against the overlays' own layer id constants. [POINT_LAYERS] is every layer an overlay
 * draws a point or a dash in, by its constant: each must have a name, and the table must name no layer outside it. A new
 * overlay's point layer is added here by its author; the one that is not shows on the emulator as `unnamed` in
 * `GET /map/marks`, which turns the a11y tool's `longpress` screen red in the sweep.
 */
class MapMarksTest {
    /** Every point layer of the overlays (the position, flights and the pack screen's label among them), by constant. */
    private val POINT_LAYERS = listOf(
        LocationPuck.ACCURACY, LocationPuck.RING, LocationPuck.DOT,
        SearchMarker.HALO, SearchMarker.DOT, SearchMarker.LABEL,
        NavMapLayer.TARGET, NavMapLayer.REJOIN,
        WayDownWatch.END,
        ViewshedOverlay.OBSERVER, ViewshedOverlay.PEAK_DOT, ViewshedOverlay.PEAK_NAME,
        TrackOverlay.POINTS, TrackOverlay.LIVE_POINTS, TrackOverlay.LABELS, TrackOverlay.GAP, TrackOverlay.LIVE_GAP,
        TripOverlay.POINTS, TripOverlay.NUMBERS, TripOverlay.LABELS,
        RouteOverlay.POINTS,
        WaterMarkers(RouteOverlay.SOURCE).layer, WaterMarkers(TripOverlay.SOURCE).layer,
        DivergenceLayer.DOTS,
        AircraftLayer.PLANES, AircraftLayer.RING, AircraftLayer.RING_OUTER,
        CatalogOverlay.LABEL,
    )

    @Test
    fun everyPointLayerHasAName() {
        val unnamed = POINT_LAYERS.filter { MapMarks.nameOf(it) == null }
        assertTrue(unnamed.isEmpty(), "point layers with no name in MapMarks: $unnamed")
        for (id in POINT_LAYERS) assertTrue(id.startsWith(MapMarks.PREFIX), "$id lacks the prefix GET /map/marks lists by")
    }

    @Test
    fun theTableNamesOnlyLayersTheOverlaysDraw() {
        val stale = MapMarks.ALL.flatMap { it.layers }.toSet() - POINT_LAYERS.toSet()
        assertTrue(stale.isEmpty(), "the table names layers no overlay constant has: $stale")
        assertEquals(WaterMarkers(RouteOverlay.SOURCE).layer, MapMarks.ROUTE_WATER)
        assertEquals(WaterMarkers(TripOverlay.SOURCE).layer, MapMarks.TRIP_WATER)
        assertEquals(MapMarks.ALL.size, MapMarks.ALL.map { it.key }.toSet().size, "a key is used twice")
    }

    @Test
    fun flightsThePickerAndLabelsAreElsewhereNotMarks() {
        for (id in listOf(AircraftLayer.PLANES, AircraftLayer.RING, "dystopo-picker-vertex", "dystopo-areas-label", CatalogOverlay.LABEL)) {
            assertNotNull(MapMarks.elsewhereOf(id), id)
            assertTrue(MapMarks.ALL.none { id in it.layers }, id)
        }
        assertNull(MapMarks.nameOf("dystopo-something-new"))
        assertNull(MapMarks.elsewhereOf("dystopo-catalog-label-2"))
    }

    private fun line(key: String, vararg props: Pair<String, String>, track: String? = null): String {
        val m = MapMarks.MARKS.single { it.key == key }
        val p = props.toMap()
        assertTrue(m.matches { p[it] }, "$key does not match $p")
        return m.line({ p[it] }, MapMarks.Facts { track })
    }

    @Test
    fun eachLineIsANameAndOneFact() {
        assertEquals("Track start, Paradise loop", line("track.start", "kind" to "start", "id" to "t1", track = "Paradise loop"))
        assertEquals("Track end", line("track.end", "kind" to "end", "id" to "t1"))
        assertEquals("Track head, Skyline Trail", line("track.head", "kind" to "current", "id" to "t2", track = "Skyline Trail"))
        assertEquals("Gap in the track, no fixes along the dashes", line("track.gap", "kind" to "gap"))
        assertEquals("Waypoint, Panorama Point", line("waypoint", "kind" to "waypoint", "name" to "Panorama Point"))
        assertEquals("Waypoint, no name yet", line("waypoint", "kind" to "waypoint", "name" to ""))
        assertEquals("Trip waypoint, Camp Muir", line("trip.waypoint", "kind" to "wpt", "name" to "Camp Muir"))
        assertEquals("Trip start, point 1", line("trip.point", "kind" to "start", "label" to "1"))
        assertEquals("Trip point, number 2", line("trip.point", "kind" to "via", "label" to "2"))
        assertEquals("Trip end, point 3", line("trip.point", "kind" to "end", "label" to "3"))
        assertEquals("Route start", line("route.end", "kind" to "start"))
        assertEquals("Route end", line("route.end", "kind" to "end"))
        assertEquals("Water, a spring", line("water", "icon" to WaterMarkers.SPRING))
        assertEquals("Water, a stream that may run dry", line("water", "icon" to WaterMarkers.STREAM_MAY_DRY))
        assertEquals("Off the mapped trail, 2 of your tracks", line("divergence", "kind" to "dot", "tracks" to "2.0"))
        assertEquals("Search result, Paradise", line("search", "name" to "Paradise"))
        assertEquals("Search result", line("search"))
        assertEquals("Rejoin point, where you get back on the route", line("nav.target", "kind" to "target"))
        assertEquals("Way back to the route, a straight line to it", line("nav.rejoin", "kind" to "rejoin"))
        assertEquals("Lower ground, where the way down ends", line("wayDown"))
        assertEquals("Viewpoint, the view is worked out from here", line("viewshed.observer", "kind" to "observer"))
        assertEquals("Peak in view, Mount Rainier", line("viewshed.peak", "name" to "Mount Rainier"))
        for (m in MapMarks.MARKS) assertTrue(m.name.isNotBlank() && m.colour.startsWith("#"), m.key)
        assertEquals(WaterMarkers.ICONS.toSet(), MapMarks.WATER_FACT.keys, "every water icon has its fact")
    }

    @Test
    fun aSharedLayersKindsPickTheirOwnMark() {
        val waypoint = MapMarks.MARKS.single { it.key == "waypoint" }
        val start = MapMarks.MARKS.single { it.key == "track.start" }
        val p = mapOf("kind" to "start")
        assertTrue(start.matches { p[it] })
        assertTrue(!waypoint.matches { p[it] })
    }

    @Test
    fun theOrderOfPrecedence() {
        assertEquals(
            listOf(
                "position", "search", "nav.target", "wayDown", "viewshed.observer", "viewshed.peak", "waypoint", "trip.waypoint",
                "track.head", "track.start", "track.end", "trip.point", "route.end", "water", "divergence", "track.gap", "nav.rejoin",
            ),
            MapMarks.ALL.map { it.key },
        )
        // A waypoint on the track's head: the waypoint is named, though the head is nearer the press.
        fun hit(key: String, px: Float) = MapMarks.MARKS.single { it.key == key }.let { MapMarks.Hit(it, it.name, it.layers[0], px) }
        val got = MapMarks.ordered(listOf(hit("track.head", 1f), hit("track.gap", 0f), hit("waypoint", 9f), hit("waypoint", 3f)))
        assertEquals(listOf("waypoint", "waypoint", "track.head", "track.gap"), got.map { it.mark.key })
        assertEquals(3f, got[0].px)
    }
}
