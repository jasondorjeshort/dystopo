package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Base64Url
import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RouteDirections
import io.github.jasondorjeshort.dystopo.model.RouteLeg
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.SavedDirections
import io.github.jasondorjeshort.dystopo.model.ViaPoint
import io.github.jasondorjeshort.dystopo.routing.Instruction
import io.github.jasondorjeshort.dystopo.routing.Maneuver
import io.github.jasondorjeshort.dystopo.routing.WayClass
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T108 (ADR 0033): turn directions saved with a trip as the `directions` route field ([RouteDirections]): the codec,
 * the merge (they count only for the path they were made for), sync between replicas, compaction, and a field this
 * build does not know (how a build before T108 treats `directions`) being ignored, kept and forwarded.
 */
class RouteDirectionsTest {
    private val route = "31080000-0000-4000-8000-000000000001"

    private fun path(lat0: Double = 0.0, mode: String = "hike") = RoutePath(
        mode, listOf(ViaPoint(lat0, 0.0), ViaPoint(lat0 + 0.002, 0.002)),
        listOf(RouteLeg(LegKind.TRAIL, List(5) { RoutePoint(lat0 + 0.0005 * it, 0.0005 * it, 1600.0 + it) })),
    ).quantized()

    private fun directions(n: Int = 3) = listOf(
        Instruction(Maneuver.DEPART, Instruction.MAJOR, 0.0, 0.0, 0.0, "Skyline Trail", WayClass.PATH, bearingDeg = 45),
        Instruction(Maneuver.LEFT, Instruction.MAJOR, 123.456, 0.00099876543, 0.00100123456, "Alta Vista Trail", WayClass.FOOTWAY, false, -88, 312, 31.24, 77.77),
        Instruction(Maneuver.VIA, Instruction.NORMAL, 150.05, 0.0011, 0.0011, null, null, true, 170, 5, 20.0, 10.0, via = 1),
        Instruction(Maneuver.ARRIVE, Instruction.MAJOR, 314.4, 0.002, 0.002, null, null, true, 0, 45, 20.0),
    ).take(n + 1)

    private fun plan(p: RoutePath = path(), d: List<Instruction>? = directions()) =
        RoutePlan(route, "Trip", p, type = p.mode, directions = d?.let { SavedDirections.of(it, p) })

    @Test
    fun codecRoundTripsWhatItKeeps() {
        val p = path()
        val saved = SavedDirections.of(directions(), p)
        val text = RouteDirections.encode(saved)!!
        val back = RouteDirections.decode(text)
        assertEquals(saved, back)
        assertEquals(RouteDirections.pathKey(p), back.pathKey)
        // Distances to decimetres, coordinates to 1e-7 degrees; everything else exact.
        assertEquals(123.5, back.list[1].alongM)
        assertEquals(0.0009988, back.list[1].lat)
        assertEquals(31.2, back.list[1].leadM)
        assertEquals(-88, back.list[1].turnDeg)
        assertEquals(WayClass.FOOTWAY, back.list[1].wayClass)
        assertEquals(1, back.list[2].via)
        assertTrue(back.list[2].sameWay)
        assertNull(back.list[2].name)
        // The same plan encodes to the same text on every replica.
        assertEquals(text, RouteDirections.encode(directions(), p))
        // Another path (or another mode) has another key.
        assertTrue(saved.isFor(p))
        assertFalse(saved.isFor(path(0.001)))
        assertFalse(saved.isFor(path(mode = "bike")))
    }

    @Test
    fun newerAndBrokenValuesCountAsAbsent() {
        val text = RouteDirections.encode(directions(), path())!!
        val bytes = Base64Url.decode(text)
        assertNull(RouteDirections.decodeOrNull(Base64Url.encode(bytes.copyOf().also { it[0] = 2 })), "a newer version")
        assertNull(RouteDirections.decodeOrNull(text.dropLast(3)), "truncated")
        assertNull(RouteDirections.decodeOrNull("not base64!"))
        assertNull(RouteDirections.decodeOrNull(null))
        // An instruction type a newer build added (here a "roundabout") makes the value one this build cannot read.
        val future = RouteDirections.encode(listOf(directions()[0].copy(type = Maneuver.CONTINUE)), path())!!
        val raw = Base64Url.decode(future)
        val code = "continue".encodeToByteArray()
        val at = (0..raw.size - code.size).first { i -> code.indices.all { raw[i + it] == code[it] } }
        val swapped = raw.copyOf().also { "roundabt".encodeToByteArray().copyInto(it, at) }
        assertNull(RouteDirections.decodeOrNull(Base64Url.encode(swapped)))
    }

    @Test
    fun aListTooLongToSaveIsNotWritten() {
        val a = InMemoryTrackStore("dev-a")
        a.putRoutePlan(plan(d = null), 10)
        val huge = List(4000) { directions()[1].copy(alongM = it.toDouble(), name = "A very long trail name number $it") }
        assertNull(RouteDirections.encode(huge, path()))
        val ops = a.opCount
        assertFalse(a.setRouteDirections(route, huge, path(), 20))
        assertEquals(ops, a.opCount)
    }

    @Test
    fun directionsTravelWithTheTripAndCountOnlyForTheirPath() {
        val a = InMemoryTrackStore("dev-a")
        val b = InMemoryTrackStore("dev-b")
        a.putRoutePlan(plan(), 10)
        syncPair(a, b, 20)
        val saved = SavedDirections.of(directions(), path()).list
        assertEquals(saved, a.routePlan(route)!!.savedDirections)
        assertEquals(saved, b.routePlan(route)!!.savedDirections, "on the phone that never planned it")
        assertEquals(a.snapshot(), b.snapshot())

        // A path written without directions (the browser, a build before T108): on every replica they no longer count.
        assertTrue(b.setRoutePath(route, path(0.001), 30))
        syncPair(a, b, 40)
        assertNull(a.routePlan(route)!!.directions)
        assertNull(b.routePlan(route)!!.savedDirections)
        assertEquals(a.snapshot(), b.snapshot())

        // Saved again for the new path (what opening the trip where a graph is does).
        assertTrue(a.setRouteDirections(route, directions(2), path(0.001), 50))
        syncPair(a, b, 60)
        assertEquals(SavedDirections.of(directions(2), path(0.001)).list, b.routePlan(route)!!.savedDirections)

        // Two phones editing at once: A's path wins, B's directions are newer: they were made for B's path, so none count.
        assertTrue(a.setRoutePath(route, path(0.003), 71))
        assertTrue(b.setRoutePath(route, path(0.004), 70))
        assertTrue(a.setRouteDirections(route, directions(), path(0.003), 72))
        assertTrue(b.setRouteDirections(route, directions(1), path(0.004), 73))
        syncPair(a, b, 80)
        assertEquals(path(0.003), a.routePlan(route)!!.path)
        assertNull(a.routePlan(route)!!.directions)
        assertEquals(a.snapshot(), b.snapshot())

        // A re-import (a put) clears them like the path.
        a.putRoute(Route(route, "Re-imported", listOf(RoutePoint(0.0, 0.0), RoutePoint(0.001, 0.001))), 90)
        assertNull(a.routePlan(route)!!.directions)

        // A plan copied onto another path keeps the old key: putting it writes directions that do not count.
        val copied = plan().copy(path = path(0.005))
        assertNull(copied.savedDirections)
        a.putRoutePlan(copied, 100)
        assertNull(a.routePlan(route)!!.directions)
    }

    @Test
    fun compactionKeepsTheNewestDirections() {
        val a = InMemoryTrackStore("dev-a")
        a.putRoutePlan(plan(), 10)
        a.setRouteDirections(route, directions(1), path(), 20)
        a.setRouteDirections(route, directions(2), path(), 30)
        val before = a.routePlan(route)
        val r = a.compact(40)
        assertTrue(r.opsDropped >= 2, "the two older directions writes: $r")
        assertEquals(before, a.routePlan(route))
        assertEquals(SavedDirections.of(directions(2), path()).list, a.routePlan(route)!!.savedDirections)
    }

    /**
     * How a build before T108 handles `directions`: a [SetField] with a field outside the kind's fields decodes (the
     * codec has no field list), is stored and forwarded, and changes nothing in the merge. This build treats any field
     * it does not know the same way; `directions` was such a field for v0.1.7-alpha (its ROUTE fields end at
     * `waypoints` and `wpt:`), so a v0.1.7 phone shows a synced trip as before and passes the directions on.
     */
    @Test
    fun aFieldThisBuildDoesNotKnowIsIgnoredKeptAndForwarded() {
        val a = InMemoryTrackStore("dev-a")
        val b = InMemoryTrackStore("dev-b")
        val c = InMemoryTrackStore("dev-c")
        a.putRoutePlan(plan(), 10)
        syncPair(a, b, 20)
        val before = b.routePlan(route)
        // A newer peer's field, written into a bundle as a newer build would send it.
        val future = SetField(EntityKind.ROUTE, route, "voiceCues", "AQID")
        val op = Op(Hlc(30, 0, "dev-z"), future)
        assertEquals(op, OpCodec.decode(OpCodec.encode(op)), "the codec carries any field")
        assertFalse("voiceCues" in EntityKind.ROUTE.fields)
        assertNull(Conflicts.fields(future), "not a field the merge knows: no conflict either")
        val r = b.apply(Bundle("dev-z", VersionVector.EMPTY, VersionVector(mapOf("dev-z" to op.hlc)), listOf(op)), 30)
        assertEquals(1, r.applied)
        assertEquals(before, b.routePlan(route), "ignored by the merge")
        assertFalse(b.setField(EntityKind.ROUTE, route, "voiceCues", "x", 31), "not written locally")
        // ...and forwarded: C learns it from B and ignores it too.
        syncPair(b, c, 40)
        assertTrue(c.opsSince(VersionVector.EMPTY).any { it.body == future })
        assertEquals(before, c.routePlan(route))
        assertEquals(b.snapshot(), c.snapshot())
    }
}
