package io.github.jasondorjeshort.dystopo.app.controls

import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import io.github.jasondorjeshort.dystopo.app.controls.Orientation.FACING
import io.github.jasondorjeshort.dystopo.app.controls.Orientation.FREE
import io.github.jasondorjeshort.dystopo.app.controls.Orientation.NORTH_UP
import io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.TapAction.ADVANCE
import io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.TapAction.NONE
import io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.TapAction.RECENTER
import io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.TapAction.ZOOM
import io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.AtRestBearing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T100: the recentre button's tap rule (owner, 2026-10-13), navigation's camera modes as a view of it, the heading rule. */
class OrientationRulesTest {
    @Test fun offCentreATapOnlyRecentresAndKeepsEveryMode() {
        for (o in Orientation.entries) for (compass in listOf(true, false)) {
            val t = OrientationRules.tap(o, centred = false, compass = compass)
            assertEquals(RECENTER, t.action, "$o compass=$compass")
            assertEquals(o, t.next, "$o compass=$compass")
        }
    }

    @Test fun centredATapAdvances() {
        assertEquals(OrientationRules.Tap(ADVANCE, FACING), OrientationRules.tap(NORTH_UP, centred = true, compass = true))
        assertEquals(OrientationRules.Tap(ADVANCE, NORTH_UP), OrientationRules.tap(FACING, centred = true, compass = true))
        assertEquals(OrientationRules.Tap(ADVANCE, NORTH_UP), OrientationRules.tap(FREE, centred = true, compass = true))
    }

    @Test fun withoutACompassFacingIsNeverOfferedAndFreeRotateNeverByTap() {
        assertEquals(NORTH_UP, OrientationRules.tap(NORTH_UP, centred = true, compass = false).next)
        assertEquals(NORTH_UP, OrientationRules.tap(FACING, centred = true, compass = false).next) // entered from the nav card
        assertEquals(NORTH_UP, OrientationRules.tap(FREE, centred = true, compass = false).next)
        // No tap sequence from north-up ever reaches free rotate.
        var o = NORTH_UP
        repeat(10) { o = OrientationRules.tap(o, centred = true, compass = true).next; assertTrue(o != FREE) }
    }

    @Test fun centredMeansFollowingOrTheFixWithinTheTolerance() {
        assertTrue(OrientationRules.centred(following = true, offsetPx = 500.0, tolerancePx = 16.0)) // the camera is on its way
        assertTrue(OrientationRules.centred(following = false, offsetPx = 1.2, tolerancePx = 16.0))
        assertFalse(OrientationRules.centred(following = false, offsetPx = 451.9, tolerancePx = 16.0))
        assertFalse(OrientationRules.centred(following = false, offsetPx = null, tolerancePx = 16.0))
    }

    @Test fun atRestFollowingStopsOnlyWhenTheCentreWasMovedAndCatchesUpWhenOnlyTheFixMovedOn() {
        val tol = 15.8
        // A two-finger turn at driving speed: the centre stayed where our last move put it, the fix moved 82 px on.
        assertEquals(OrientationRules.AtRest.CATCH_UP, OrientationRules.atRest(driftPx = 0.4, fixOffsetPx = 82.0, tolerancePx = tol))
        // A turn with the fix standing still.
        assertEquals(OrientationRules.AtRest.KEEP, OrientationRules.atRest(driftPx = 0.4, fixOffsetPx = 1.2, tolerancePx = tol))
        // Someone else moved the centre (a two-finger drag, a route fit, POST /camera), whatever the fix did.
        assertEquals(OrientationRules.AtRest.STOP, OrientationRules.atRest(driftPx = 300.0, fixOffsetPx = 82.0, tolerancePx = tol))
        assertEquals(OrientationRules.AtRest.STOP, OrientationRules.atRest(driftPx = 300.0, fixOffsetPx = 1.0, tolerancePx = tol))
        // Before any move of ours the fix's offset decides, as before.
        assertEquals(OrientationRules.AtRest.STOP, OrientationRules.atRest(driftPx = null, fixOffsetPx = 82.0, tolerancePx = tol))
        assertEquals(OrientationRules.AtRest.KEEP, OrientationRules.atRest(driftPx = null, fixOffsetPx = 1.2, tolerancePx = tol))
        assertEquals(OrientationRules.AtRest.KEEP, OrientationRules.atRest(driftPx = 0.0, fixOffsetPx = null, tolerancePx = tol))
    }

    @Test fun navigationCameraModesAreAViewOfTheOneState() {
        assertEquals(CameraMode.FREE, OrientationRules.cameraMode(FACING, following = false))
        assertEquals(CameraMode.FOLLOW, OrientationRules.cameraMode(NORTH_UP, following = true))
        assertEquals(CameraMode.FOLLOW, OrientationRules.cameraMode(FREE, following = true))
        assertEquals(CameraMode.HEADING_UP, OrientationRules.cameraMode(FACING, following = true))
        // Setting a mode, then reading it back, gives the same mode from every starting orientation.
        for (mode in CameraMode.entries) for (o in Orientation.entries) {
            val (next, following) = OrientationRules.fromCameraMode(mode, o)
            assertEquals(mode, OrientationRules.cameraMode(next, following), "$mode from $o")
        }
        assertEquals(FACING to false, OrientationRules.fromCameraMode(CameraMode.FREE, FACING)) // a pan keeps facing
        assertEquals(FREE to true, OrientationRules.fromCameraMode(CameraMode.FOLLOW, FREE))
    }

    @Test fun bearingsAndTurns() {
        assertEquals(0.0, OrientationRules.bearing(NORTH_UP, 37.0, 120.0))
        assertEquals(37.0, OrientationRules.bearing(FREE, 37.0, 120.0))
        assertEquals(120.0, OrientationRules.bearing(FACING, 37.0, 120.0))
        assertEquals(37.0, OrientationRules.bearing(FACING, 37.0, null))
        assertTrue(OrientationRules.turns(358.0, 1.0)) // 3 degrees the short way round
        assertFalse(OrientationRules.turns(359.5, 0.5)) // 1 degree
        assertTrue(OrientationRules.turns(10.0, 190.0))
        assertEquals(-10.0, OrientationRules.rel(350.0))
    }

    @Test fun headingIsSimulatedThenCourseWhileMovingThenCompassThenLastCourse() {
        fun h(sim: Double? = null, injected: Boolean = false, course: Double? = null, ageMs: Long? = 0, speed: Double? = null, compass: Double? = null, fresh: Boolean = false) =
            OrientationRules.heading(sim, injected, course, ageMs, speed, compass, fresh)
        assertEquals(HeadingReading(90.0, "simulated"), h(sim = 90.0, injected = true, course = 10.0, speed = 3.0, compass = 200.0, fresh = true))
        // A simulated heading is ignored once the device's own fixes are back.
        assertEquals("compass", h(sim = 90.0, injected = false, compass = 200.0, fresh = true)?.source)
        // Moving: the course over ground wins over the compass.
        assertEquals(HeadingReading(10.0, "course"), h(course = 10.0, speed = 1.4, compass = 200.0, fresh = true))
        // Slow or still: the compass.
        assertEquals(HeadingReading(200.0, "compass"), h(course = 10.0, speed = 0.3, compass = 200.0, fresh = true))
        // An old course does not count as moving.
        assertEquals("compass", h(course = 10.0, ageMs = 60_000, speed = 1.4, compass = 200.0, fresh = true)?.source)
        // No compass: the last course; nothing at all: none.
        assertEquals(HeadingReading(10.0, "course"), h(course = 10.0, speed = 0.0))
        assertNull(h())
        assertEquals(350.0, h(course = -10.0, speed = 2.0)!!.deg)
    }

    // ---- T176: the tap zooms in to the close zoom, the layouts, the lock ----

    /** One row per tap case: (orientation, centred, zoom, close zoom, layout, locked) to the expected tap. */
    @Test fun aTapZoomsInNeverOutAndAdvancesOnlyOnOneButtonUnlocked() {
        data class Row(val o: Orientation, val centred: Boolean, val zoom: Double?, val close: Double?, val layout: Int, val locked: Boolean, val want: OrientationRules.Tap, val why: String)
        val rows = listOf(
            Row(NORTH_UP, false, 10.0, 14.0, 1, false, OrientationRules.Tap(RECENTER, NORTH_UP, 14.0), "off centre and far out: recentre and zoom in"),
            Row(FACING, false, 10.0, 14.0, 2, false, OrientationRules.Tap(RECENTER, FACING, 14.0), "off centre keeps the mode on any layout"),
            Row(NORTH_UP, false, 16.5, 14.0, 1, false, OrientationRules.Tap(RECENTER, NORTH_UP, null), "off centre and closer than close: recentre, keep the zoom (never out)"),
            Row(NORTH_UP, false, 13.97, 14.0, 1, false, OrientationRules.Tap(RECENTER, NORTH_UP, null), "within CLOSE_EPS counts as close"),
            Row(NORTH_UP, true, 10.0, 14.0, 1, false, OrientationRules.Tap(ZOOM, NORTH_UP, 14.0), "centred but far out: only zoom in"),
            Row(FACING, true, 12.0, 14.0, 3, true, OrientationRules.Tap(ZOOM, FACING, 14.0), "centred far out zooms on any layout, locked too"),
            Row(NORTH_UP, true, 14.0, 14.0, 1, false, OrientationRules.Tap(ADVANCE, FACING, null), "centred and close, one button: advance"),
            Row(FACING, true, 15.0, 14.0, 1, false, OrientationRules.Tap(ADVANCE, NORTH_UP, null), "facing back to north-up"),
            Row(FREE, true, 14.0, 14.0, 1, false, OrientationRules.Tap(ADVANCE, NORTH_UP, null), "free rotate back to north-up"),
            Row(NORTH_UP, true, 14.0, 14.0, 2, false, OrientationRules.Tap(NONE, NORTH_UP, null), "two buttons: the orientation button owns the advance"),
            Row(NORTH_UP, true, 14.0, 14.0, 3, false, OrientationRules.Tap(NONE, NORTH_UP, null), "three buttons: likewise"),
            Row(NORTH_UP, true, 14.0, 14.0, 1, true, OrientationRules.Tap(NONE, NORTH_UP, null), "the lock: a tap no longer advances"),
            Row(NORTH_UP, false, 9.0, null, 1, false, OrientationRules.Tap(RECENTER, NORTH_UP, null), "navigation (no close zoom): recentre, keep its zoom"),
            Row(NORTH_UP, true, 9.0, null, 1, false, OrientationRules.Tap(ADVANCE, FACING, null), "navigation, centred: close counts, advance as before T176"),
            Row(NORTH_UP, true, 9.0, null, 2, false, OrientationRules.Tap(NONE, NORTH_UP, null), "navigation on two buttons: nothing"),
            Row(NORTH_UP, false, null, 14.0, 1, false, OrientationRules.Tap(RECENTER, NORTH_UP, null), "no camera zoom known: no zoom step"),
        )
        for (r in rows) assertEquals(r.want, OrientationRules.tap(r.o, r.centred, compass = true, zoom = r.zoom, closeZoom = r.close, layout = r.layout, locked = r.locked), r.why)
        // A tap never zooms out, whatever the zoom.
        for (z in listOf(0.0, 5.5, 13.9, 14.0, 17.0, 22.0)) for (c in listOf(true, false)) {
            val t = OrientationRules.tap(NORTH_UP, c, compass = true, zoom = z, closeZoom = 14.0)
            assertTrue(t.zoomTo == null || t.zoomTo!! > z, "zoom $z centred $c: ${t.zoomTo}")
        }
    }

    /** One row per lock case. */
    @Test fun theLockKeepsTheModeAndTurnsABearingBackToNorth() {
        // Turning it on: free rotate becomes north-up; north-up and facing stay as chosen.
        assertEquals(NORTH_UP, OrientationRules.onLock(FREE))
        assertEquals(NORTH_UP, OrientationRules.onLock(NORTH_UP))
        assertEquals(FACING, OrientationRules.onLock(FACING))
        // At rest: a north-up map turned off north is free unlocked, turned back to north locked.
        assertEquals(AtRestBearing.TO_FREE, OrientationRules.atRestBearing(NORTH_UP, 30.0, locked = false))
        assertEquals(AtRestBearing.TURN_NORTH, OrientationRules.atRestBearing(NORTH_UP, 30.0, locked = true))
        assertEquals(AtRestBearing.KEEP, OrientationRules.atRestBearing(NORTH_UP, 0.2, locked = true), "within half a degree is north")
        // A free map turned back to north is north-up, and under the lock a free map cannot stay free.
        assertEquals(AtRestBearing.TO_NORTH_UP, OrientationRules.atRestBearing(FREE, 0.0, locked = false))
        assertEquals(AtRestBearing.KEEP, OrientationRules.atRestBearing(FREE, 40.0, locked = false))
        assertEquals(AtRestBearing.TO_NORTH_UP, OrientationRules.atRestBearing(FREE, 40.0, locked = true))
        // Facing turns itself to the heading: the lock leaves it alone.
        assertEquals(AtRestBearing.KEEP, OrientationRules.atRestBearing(FACING, 120.0, locked = true))
        assertEquals(AtRestBearing.KEEP, OrientationRules.atRestBearing(FACING, 120.0, locked = false))
        // The orientation button still changes the mode under the lock (it is not a tap of the position button).
        assertEquals(FACING, OrientationRules.next(NORTH_UP, compass = true))
        assertEquals(NORTH_UP, OrientationRules.next(FACING, compass = true))
        // A long press toggles the lock on one and two buttons only.
        assertTrue(Recenter.longPressLocks(1))
        assertTrue(Recenter.longPressLocks(2))
        assertFalse(Recenter.longPressLocks(3))
    }

    @Test fun theRoomDropsTheExtraButtonsBeforeTheColumn() {
        assertEquals(3, OrientationRules.shownButtons(3) { true })
        assertEquals(2, OrientationRules.shownButtons(3) { it <= 2 }, "the lock goes first")
        assertEquals(1, OrientationRules.shownButtons(3) { it <= 1 }, "then the orientation button")
        assertEquals(0, OrientationRules.shownButtons(2) { false }, "then the whole column")
        assertEquals(1, OrientationRules.shownButtons(1) { true })
        assertEquals(3, OrientationRules.shownButtons(7) { true }, "a layout outside 1..3 is clamped")
        assertEquals(1, OrientationRules.shownButtons(0) { true })
    }

    @Test fun parsing() {
        assertEquals(NORTH_UP, Orientation.parse("north-up"))
        assertEquals(FREE, Orientation.parse("FREE"))
        assertEquals(FACING, Orientation.parse("heading-up"))
        assertNull(Orientation.parse("sideways"))
        for (o in Orientation.entries) assertEquals(o, Orientation.parse(o.key))
    }
}
