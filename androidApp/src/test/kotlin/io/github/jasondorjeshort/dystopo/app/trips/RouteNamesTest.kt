package io.github.jasondorjeshort.dystopo.app.trips

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T219: the name of a route kept without one, a route row's counts, and the empty list's text. */
class RouteNamesTest {
    @Test
    fun twoNamedEndsAreJoined() = assertEquals("Paradise to Panorama Point", routeNameFor("Paradise", "Panorama Point", "Nov 3"))

    @Test
    fun oneNamedEndSaysWhichEnd() {
        assertEquals("Route from Paradise", routeNameFor("Paradise", null, "Nov 3"))
        assertEquals("Route to Panorama Point", routeNameFor(null, "Panorama Point", "Nov 3"))
        assertEquals("Route to Panorama Point", routeNameFor("  ", "Panorama Point", "Nov 3"))
    }

    @Test
    fun noNamedEndFallsBackToTheDate() = assertEquals("Route, Nov 3", routeNameFor(null, null, "Nov 3"))

    @Test
    fun aLoopIsNotPlaceToItself() = assertEquals("Route from Paradise", routeNameFor("Paradise", "paradise", "Nov 3"))

    @Test
    fun aPlainRouteHasNoCountsLine() = assertNull(routeRowCounts(2, 0, null)) // the store counts its two ends as via points

    @Test
    fun countsShowOnlyWhenNotZero() {
        assertEquals("1 via point", routeRowCounts(3, 0, null))
        assertEquals("2 via points · 1 waypoint", routeRowCounts(4, 1, null))
        assertEquals("3 waypoints · 200 ft off trail", routeRowCounts(2, 3, "200 ft"))
        assertEquals("200 ft off trail", routeRowCounts(0, 0, "200 ft")) // an old row with no ends counted
    }

    @Test
    fun theEmptyListNamesTheCardsVerbAndNoRoute() {
        assertEquals("No saved routes yet. Long-press the map to set a start and an end, then tap Save.", ROUTES_EMPTY)
        assertTrue("Save" in ROUTES_EMPTY)
        assertFalse("POST" in ROUTES_EMPTY)
    }
}
