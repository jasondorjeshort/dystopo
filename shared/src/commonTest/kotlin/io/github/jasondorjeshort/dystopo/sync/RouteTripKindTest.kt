package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.RouteLeg
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.ViaPoint
import io.github.jasondorjeshort.dystopo.routing.RouteChip
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.routing.TripKind
import io.github.jasondorjeshort.dystopo.routing.TripKinds
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T278 (ADR 0033): the kind a trip was kept as, the `tripKind` route field ([RoutePlan.tripKind]): it travels with the trip
 * through a sync bundle, last writer wins, a put clears it, compaction keeps the newest, a value this build does not read
 * counts as absent, and an absent kind reads as by the mode ([TripKinds.kept]) so every trip stored before T278 reads as it did.
 */
class RouteTripKindTest {
    private val route = "32780000-0000-4000-8000-000000000001"

    private fun path(mode: String = "hike") = RoutePath(
        mode, listOf(ViaPoint(45.92, 6.87), ViaPoint(45.91, 6.86)),
        listOf(RouteLeg(LegKind.TRAIL, List(5) { RoutePoint(45.92 - 0.002 * it, 6.87 - 0.002 * it, 1040.0) })),
    ).quantized()

    private fun plan(kind: TripKind?, mode: String = "hike") = RoutePlan(route, "Chamonix streets", path(mode), type = mode, tripKind = kind)

    @Test
    fun aKeptWalkReadsWalkOnAnotherPhone() {
        val a = InMemoryTrackStore("dev-a")
        val b = InMemoryTrackStore("dev-b")
        a.putRoutePlan(plan(TripKind.EVERYDAY), 10)
        assertEquals(TripKind.EVERYDAY, a.routePlan(route)!!.tripKind)
        assertEquals("everyday", a.routeList().single().tripKind)
        syncPair(a, b, 20)
        assertEquals(TripKind.EVERYDAY, b.routePlan(route)!!.tripKind, "on the phone that never planned it")
        assertEquals("everyday", b.routeList().single().tripKind)
        assertEquals(RouteChip.WALK, TripKinds.chipOf(TravelMode.HIKE, b.routePlan(route)!!.tripKind))
        assertEquals(a.snapshot(), b.snapshot())

        // Changed on B (the trip card's Hike chip): last writer wins on both.
        assertTrue(b.setField(EntityKind.ROUTE, route, "tripKind", TripKind.BACKCOUNTRY.key, 30))
        syncPair(a, b, 40)
        assertEquals(TripKind.BACKCOUNTRY, a.routePlan(route)!!.tripKind)
        assertEquals(a.snapshot(), b.snapshot())

        // A re-import (a put) clears it like the directions: the trip reads by its mode again.
        a.putRoutePlan(plan(null), 50)
        assertNull(a.routePlan(route)!!.tripKind)
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.kept(TravelMode.HIKE, a.routePlan(route)!!.tripKind))
    }

    @Test
    fun aTripWithoutAKindReadsAsBeforeT278() {
        val a = InMemoryTrackStore("dev-a")
        a.putRoutePlan(plan(null), 10)
        assertNull(a.routePlan(route)!!.tripKind)
        assertNull(a.routeList().single().tripKind)
        assertTrue(a.opsSince(VersionVector.EMPTY).none { (it.body as? SetField)?.field == "tripKind" }, "no field written for no kind")
        // As by the mode: a hike and a bike backcountry (the Hike and Bike chips), a drive everyday, a line backcountry.
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.kept(TravelMode.HIKE, null))
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.kept(TravelMode.BIKE, null))
        assertEquals(TripKind.EVERYDAY, TripKinds.kept(TravelMode.DRIVE, null))
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.kept(TravelMode.CROW, null))
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.kept(null, null), "a mode this build does not know")
        assertEquals(RouteChip.HIKE, TripKinds.chipOf(TravelMode.HIKE, null))
        assertEquals(RouteChip.BIKE, TripKinds.chipOf(TravelMode.BIKE, TripKind.EVERYDAY))
        assertEquals(RouteChip.DRIVE, TripKinds.chipOf(TravelMode.DRIVE, null))
        assertNull(TripKinds.chipOf(null, TripKind.EVERYDAY))
    }

    @Test
    fun aValueThisBuildDoesNotReadIsAbsentAndCompactionKeepsTheNewest() {
        val a = InMemoryTrackStore("dev-a")
        a.putRoutePlan(plan(TripKind.EVERYDAY), 10)
        assertTrue(a.setField(EntityKind.ROUTE, route, "tripKind", "commute", 20), "a newer build's kind is kept in the log")
        assertNull(a.routePlan(route)!!.tripKind)
        assertTrue(a.setField(EntityKind.ROUTE, route, "tripKind", TripKind.EVERYDAY.key, 30))
        val before = a.routePlan(route)
        val r = a.compact(40)
        assertTrue(r.opsDropped >= 2, "the two older tripKind writes: $r")
        assertEquals(before, a.routePlan(route))
        assertEquals(TripKind.EVERYDAY, a.routePlan(route)!!.tripKind)
        assertTrue("tripKind" in EntityKind.ROUTE.fields)
    }
}
