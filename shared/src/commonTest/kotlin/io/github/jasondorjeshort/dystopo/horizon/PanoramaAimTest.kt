package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T324: the Panorama's aim ([PanoramaAim]): the opening, the drag, the pinch and the zoom step, turn-to; and the edge arrow ([OffView]). */
class PanoramaAimTest {
    private val w = 1080.0
    private val h = 810.0
    private val dp = 2.625
    private fun near(a: Double, b: Double, tol: Double, what: String = "") = assertTrue(abs(a - b) <= tol, "$what $a against $b")
    private fun apart(a: Double, b: Double) = HorizonLabels.apart(a, b)

    @Test
    fun theLevelLineStandsTwoThirdsDownAtOpeningUprightAndOnItsSide() {
        for ((pw, ph) in listOf(w to h, 2400.0 to 1080.0 - 150)) {
            val aim = PanoramaAim.opening(pw, ph, 347.0)
            assertEquals(60.0, aim.fovDeg)
            assertEquals(347.0, aim.bearingDeg)
            val level = assertNotNull(PanoramaScene.level(aim.lens(pw, ph, dp)))
            near(level[1], ph * 2 / 3, 1e-6, "level y")
            // And the level ahead projects there.
            near(assertNotNull(aim.lens(pw, ph, dp).mark(0.0, 347.0)).y, ph * 2 / 3, 1e-6)
        }
        near(PanoramaAim.opening(w, h, -10.0).bearingDeg, 350.0, 1e-9)
    }

    @Test
    fun aDragKeepsTheDirectionUnderTheFinger() {
        for (start in listOf(PanoramaAim.opening(w, h, 347.0), PanoramaAim(120.0, 30.0, 40.0), PanoramaAim(5.0, -20.0, 100.0))) {
            val (el0, az0) = start.lens(w, h, dp).ray(w / 2, h / 2)
            for ((dx, dy) in listOf(100.0 to 0.0, 0.0 to 100.0, -100.0 to 0.0, 0.0 to -100.0, 70.0 to -70.0)) {
                val after = start.drag(dx, dy, w).lens(w, h, dp)
                val (el, az) = after.ray(w / 2 + dx, h / 2 + dy)
                near(el, el0, 0.1, "elevation under the finger ($dx, $dy) from $start")
                assertTrue(apart(az, az0) <= 0.1, "bearing under the finger ($dx, $dy) from $start: $az against $az0")
            }
        }
        // A drag to the left turns the view right.
        assertTrue(PanoramaAim.opening(w, h, 10.0).drag(-100.0, 0.0, w).bearingDeg in 10.0..30.0)
    }

    @Test
    fun theBearingWrapsThroughNorthAndTheTiltStopsAtItsLimits() {
        val a = PanoramaAim(355.0, 0.0, 60.0)
        val turned = a.drag(-200.0, 0.0, w)
        assertTrue(turned.bearingDeg in 0.0..20.0, "through north: ${turned.bearingDeg}")
        var b = PanoramaAim(10.0, 0.0, 60.0)
        repeat(40) { b = b.drag(30.0, 0.0, w) }
        assertTrue(b.bearingDeg in 0.0..360.0)
        // Down and down: the tilt stops at 90; up and up: at -45.
        var up = PanoramaAim(10.0, 80.0, 60.0)
        repeat(10) { up = up.drag(0.0, 300.0, w) }
        assertEquals(90.0, up.tiltDeg)
        var down = PanoramaAim(10.0, 0.0, 60.0)
        repeat(10) { down = down.drag(0.0, -300.0, w) }
        assertEquals(-45.0, down.tiltDeg)
        near(down.bearingDeg, 10.0, 1e-6, "a vertical drag leaves the bearing")
    }

    @Test
    fun thePinchAndTheZoomStepStopAtTheirLimits() {
        var a = PanoramaAim.opening(w, h, 0.0)
        repeat(20) { a = a.zoom(true, w, h) }
        assertEquals(PanoramaAim.MIN_FOV_DEG, a.fovDeg)
        repeat(20) { a = a.zoom(false, w, h) }
        assertEquals(PanoramaAim.MAX_FOV_DEG, a.fovDeg)
        assertEquals(PanoramaAim.MIN_FOV_DEG, PanoramaAim.opening(w, h, 0.0).pinch(50.0, 100.0, 100.0, w, h).fovDeg)
        assertEquals(PanoramaAim.MAX_FOV_DEG, PanoramaAim.opening(w, h, 0.0).pinch(0.01, 100.0, 100.0, w, h).fovDeg)
        // One step in from 60: the focal length grows by the step.
        val z = PanoramaAim(0.0, 0.0, 60.0).zoom(true, w, h)
        near(z.focalPx(w), PanoramaAim(0.0, 0.0, 60.0).focalPx(w) * PanoramaAim.ZOOM_STEP, 1e-6)
        // A pinch about a focus keeps the direction under it.
        val start = PanoramaAim.opening(w, h, 200.0)
        val (el0, az0) = start.lens(w, h, dp).ray(300.0, 200.0)
        val (el, az) = start.pinch(1.7, 300.0, 200.0, w, h).lens(w, h, dp).ray(300.0, 200.0)
        near(el, el0, 0.01); assertTrue(apart(az, az0) < 0.01)
    }

    @Test
    fun turnToTakesTheShorterWayAndTiltsOnlyWhenItMust() {
        val a = PanoramaAim.opening(w, h, 350.0)
        val t = a.turnTo(5.0, 20.0, w, h)
        near(t.turnDeg, 30.0, 1e-9, "right, through north")
        assertEquals(20.0, t.aim.bearingDeg)
        assertEquals(a.tiltDeg, t.aim.tiltDeg, "a mark in the view's height leaves the tilt")
        near(a.turnTo(0.0, 200.0, w, h).turnDeg, -150.0, 1e-9, "left")
        val high = a.turnTo(60.0, 10.0, w, h)
        assertEquals(60.0, high.aim.tiltDeg)
        val below = a.turnTo(-70.0, 10.0, w, h)
        assertEquals(PanoramaAim.MIN_TILT_DEG, below.aim.tiltDeg, "stops at -45")
        // Under the tape counts as off the view when the caller says so.
        val tape = 60.0
        val f = a.focalPx(w)
        val elUnderTape = a.tiltDeg + kotlin.math.atan((h / 2 - 20) / f) * 180 / kotlin.math.PI
        assertEquals(a.tiltDeg, a.turnTo(elUnderTape, 0.0, w, h).aim.tiltDeg)
        assertEquals(elUnderTape, a.turnTo(elUnderTape, 0.0, w, h, tape).aim.tiltDeg)
    }

    @Test
    fun t329FollowsLimitsTheFingersAndTheWayBackToDrag() {
        val f = PanoramaAim.follow(120.0, -80.0, 30.0, 60.0)
        assertTrue(f.follow)
        near(f.tiltDeg, -80.0, 1e-12); near(f.rollDeg, 30.0, 1e-12)
        PanoramaAim.follow(0.0, -90.0, 0.0, 60.0); PanoramaAim.follow(0.0, 90.0, 170.0, 60.0)
        assertTrue(runCatching { PanoramaAim(0.0, -60.0, 60.0) }.isFailure, "Drag keeps its -45")
        assertTrue(runCatching { PanoramaAim(0.0, 0.0, 60.0, rollDeg = 5.0) }.isFailure, "Drag has no roll")
        // The fingers change the field of view alone; a drag and a turn-to turn nothing.
        assertEquals(f, f.drag(100.0, -40.0, w))
        val z = f.zoom(true, w, h)
        assertEquals(f.axes, z.axes); near(z.focalPx(w), f.focalPx(w) * PanoramaAim.ZOOM_STEP, 1e-6)
        val p = f.pinch(1.3, 100.0, 700.0, w, h)
        assertEquals(f.copy(fovDeg = p.fovDeg), p)
        val t = f.turnTo(0.0, 200.0, w, h)
        assertEquals(f, t.aim); near(t.turnDeg, 80.0, 1e-9)
        // Back in Drag: the roll goes to zero and the tilt to Drag's limits at once; the bearing and the field of view stay.
        val d = f.toDrag()
        assertTrue(!d.follow)
        assertEquals(PanoramaAim(120.0, PanoramaAim.MIN_TILT_DEG, 60.0), d)
        // The lens of a Follow aim made from angles is the Drag aim's at no roll.
        val a = PanoramaAim.follow(33.0, 12.0, 0.0, 60.0).lens(w, h, dp)
        val b = PanoramaAim(33.0, 12.0, 60.0).lens(w, h, dp)
        val pa = a.project(20.0, 50.0)!!
        val pb = b.project(20.0, 50.0)!!
        near(pa.x, pb.x, 1e-9); near(pa.y, pb.y, 1e-9)
    }

    @Test
    fun t329AimAtPutsADirectionAtAnOffsetOnARolledScreen() {
        for (roll in listOf(30.0, -75.0, 140.0)) {
            for ((sx, sy) in listOf(0.2 to 0.1, -0.3 to 0.25, 0.0 to -0.4)) {
                val aim = PanoramaAim.aimAt(25.0, 70.0, sx, sy, 60.0, 0.0, roll)
                assertTrue(aim.follow)
                near(aim.rollDeg, roll, 1e-6, "roll")
                val lens = aim.lens(w, h, dp)
                val pt = lens.project(25.0, 70.0)!!
                near(pt.x, w / 2 + sx * lens.focalPx, 1e-6, "x at roll $roll ($sx, $sy)")
                near(pt.y, h / 2 - sy * lens.focalPx, 1e-6, "y at roll $roll ($sx, $sy)")
            }
        }
    }

    @Test
    fun t329TheArrowReadsByTheScreensAxesAtARoll() {
        val keep = RimBox(12.0, 70.0, w - 12, h - 12)
        // On its side clockwise (roll 90): what stands 60 degrees right of the view is toward the screen's top.
        val side = PanoramaAim.follow(90.0, 0.0, 90.0, 60.0).lens(w, h, dp)
        val up = assertNotNull(OffView.of(side, 0.0, 150.0, "Mount Rainier", keep))
        assertEquals(OffSide.UP, up.side); assertEquals("Mount Rainier, 60° up", up.text); assertEquals(70.0, up.y); assertEquals(-1.0, up.dirY)
        // And what stands above the view is toward the screen's left.
        val left = assertNotNull(OffView.of(side, 60.0, 90.0, "Sun", keep))
        assertEquals(OffSide.LEFT, left.side); assertEquals("Sun, 60° left", left.text); assertEquals(12.0, left.x)
        // Rolled 30: a peak 40 degrees right on the level is to the right and a little up the screen.
        val rolled = PanoramaAim.follow(90.0, 0.0, 30.0, 60.0).lens(w, h, dp)
        val r = assertNotNull(OffView.of(rolled, 0.0, 130.0, "Peak", keep))
        assertEquals(OffSide.RIGHT, r.side); assertEquals(w - 12, r.x); assertTrue(r.y < h / 2)
        // Looking straight down: something ahead, on the level or a little below it, is up the screen.
        val down = PanoramaAim.follow(0.0, -90.0, 0.0, 60.0).lens(w, h, dp)
        val ahead = assertNotNull(OffView.of(down, -10.0, 0.0, "Ahead", keep))
        assertEquals(OffSide.UP, ahead.side); assertEquals("Ahead, 80° up", ahead.text)
        val level = assertNotNull(OffView.of(down, 0.0, 0.0, "Level", keep))
        assertEquals(OffSide.UP, level.side); assertEquals("Level, 90° up", level.text)
    }

    @Test
    fun t329TheCorrectionFollowsTheFingerAlongTheLevelLine() {
        for ((tilt, roll) in listOf(0.0 to 0.0, 0.0 to 30.0, 30.0 to 0.0, 25.0 to -40.0)) {
            val axes = LensAxes.of(200.0, tilt, roll)
            val lens = PanoramaAim.follow(axes, 60.0).lens(w, h, dp)
            val (el0, az0) = lens.ray(w / 2, h / 2)
            // 40 px along the drawn level line's direction (its slope is the roll's).
            val r = roll * kotlin.math.PI / 180
            val (dx, dy) = 40.0 * kotlin.math.cos(r) to -40.0 * kotlin.math.sin(r)
            val c = HeadingCorrection.dragDeg(lens, dx, dy)
            assertTrue(c < 0, "a drag along the line to the right turns the view west: $c")
            val after = PanoramaAim.follow(PhoneAttitude.turned(axes, c), 60.0).lens(w, h, dp)
            val p = after.project(el0, az0)!!
            val along = (p.x - w / 2) * kotlin.math.cos(r) - (p.y - h / 2) * kotlin.math.sin(r)
            near(along, 40.0, 1.0, "tilt $tilt roll $roll: the centre's ground followed the finger")
            // Across the line: nothing.
            near(HeadingCorrection.dragDeg(lens, -dy, dx), 0.0, 1e-9, "across")
            // The other way: east.
            assertTrue(HeadingCorrection.dragDeg(lens, -dx, -dy) > 0)
        }
        // The wrap, the line, the action's step.
        near(HeadingCorrection.add(179.0, 3.0), -178.0, 1e-9)
        near(HeadingCorrection.add(-179.5, -1.0), 179.5, 1e-9)
        assertEquals("Compass adjusted 4° east", HeadingCorrection.line(4.2))
        assertEquals("Compass adjusted 12° west", HeadingCorrection.line(-11.6))
        assertEquals("Compass adjusted 0°", HeadingCorrection.line(0.3))
        assertEquals(1.0, HeadingCorrection.ACTION_STEP_DEG)
    }

    @Test
    fun offTheViewLeftRightUpDownAndBehind() {
        val lens = PanoramaAim(90.0, 0.0, 60.0).lens(w, h, dp)
        val keep = RimBox(12.0, 70.0, w - 12, h - 12)
        assertNull(OffView.of(lens, 0.0, 95.0, "On", keep), "on the view: no arrow")
        val right = assertNotNull(OffView.of(lens, 5.0, 130.0, "Sun", keep))
        assertEquals(OffSide.RIGHT, right.side)
        assertEquals("Sun, 40° right", right.text)
        assertEquals(w - 12, right.x); assertEquals(1.0, right.dirX)
        assertTrue(right.y < h / 2, "above the centre row, as the sun is above the level")
        val left = assertNotNull(OffView.of(lens, 0.0, 20.0, "West Peak", keep))
        assertEquals(OffSide.LEFT, left.side); assertEquals("West Peak, 70° left", left.text); assertEquals(12.0, left.x)
        val up = assertNotNull(OffView.of(lens, 40.0, 85.0, "Mount Rainier", keep))
        assertEquals(OffSide.UP, up.side); assertEquals("Mount Rainier, 40° up", up.text)
        assertEquals(70.0, up.y); assertTrue(up.x < w / 2 && up.x > 12, "over its bearing's column")
        val down = assertNotNull(OffView.of(lens, -35.0, 100.0, "Lake", keep))
        assertEquals(OffSide.DOWN, down.side); assertEquals("Lake, 35° down", down.text); assertEquals(h - 12, down.y)
        val behind = assertNotNull(OffView.of(lens, 0.0, 271.0, "Behind", keep))
        assertEquals(OffSide.LEFT, behind.side); assertEquals("Behind, 179° left", behind.text)
        val exactly = assertNotNull(OffView.of(lens, 0.0, 270.0, "Opposite", keep))
        assertEquals("Opposite, 180° right", exactly.text)
    }
}
