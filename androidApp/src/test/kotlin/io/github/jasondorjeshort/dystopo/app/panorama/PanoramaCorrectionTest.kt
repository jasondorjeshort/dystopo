package io.github.jasondorjeshort.dystopo.app.panorama

import io.github.jasondorjeshort.dystopo.horizon.HeadingCorrection
import io.github.jasondorjeshort.dystopo.horizon.LensView
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaCorrection.Closed
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaCorrection.Dragged
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaCorrection.LeftFollow
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaCorrection.Reset
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaCorrection.Set
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaCorrection.Stepped
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaCorrection.Targeted
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaCorrection.Zoomed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T331: the heading correction's lifetime (wave 46's choice (f)) and the weak-compass line's rule (choice (h)). */
class PanoramaCorrectionTest {
    private fun run(start: Double?, vararg events: PanoramaCorrection.Event, following: Boolean = true): Double? =
        events.fold(start) { c, e -> PanoramaCorrection.next(c, following, e) }

    @Test
    fun aDragSetsItAndAZoomAndATargetKeepIt() {
        // A drag to the right on a level lens 1000 px wide at 60 degrees: the view turns west by the drag's angle.
        val lens: LensView = PanoramaAim.follow(0.0, 0.0, 0.0, 60.0).lens(1000.0, 600.0, 2.0)
        val deg = HeadingCorrection.dragDeg(lens, 100.0, 0.0)
        assertTrue(deg < 0, "a drag right gives west: $deg")
        val c = run(null, Dragged(deg))!!
        assertEquals(deg, c, 1e-9)
        assertEquals(c, run(c, Zoomed, Targeted, Zoomed))
        assertEquals(HeadingCorrection.add(c, deg), run(c, Dragged(deg))!!, 1e-9)
    }

    @Test
    fun theActionsStepADegreeEachWay() {
        assertEquals(1.0, run(null, Stepped(right = true)))
        assertEquals(-1.0, run(null, Stepped(right = false)))
        assertEquals(0.0, run(null, Stepped(right = true), Stepped(right = false)), "back to none still shows its line: 0, not null")
        assertEquals(-179.0, run(180.0, Stepped(right = true)), "wraps")
    }

    @Test
    fun resetDragAndCloseDropIt() {
        for (end in listOf(Reset, LeftFollow, Closed)) assertNull(run(4.0, Zoomed, end), "$end")
        assertNull(run(null, Stepped(right = true), Closed, Zoomed))
    }

    @Test
    fun nothingSetsItOutsideFollow() {
        assertNull(run(null, Dragged(-3.0), Stepped(right = true), Set(5.0), following = false))
        assertEquals(12.0, run(null, Set(372.0)))
        assertNull(run(12.0, Set(null)))
    }

    @Test
    fun theWeakCompassLineShowsAfterTwoSecondsLowWhileFollowing() {
        var s = CompassHint.State()
        s = CompassHint.next(s, true, 1, 10_000)
        assertFalse(s.shown)
        s = CompassHint.next(s, true, 0, 11_900)
        assertFalse(s.shown, "low for 1.9 s")
        s = CompassHint.next(s, true, 1, 12_001)
        assertTrue(s.shown, "low or unreliable for over 2 s")
        assertFalse(CompassHint.next(s, true, 2, 12_100).shown, "medium takes it away at once")
        assertFalse(CompassHint.next(s, false, 0, 12_100).shown, "leaving Follow takes it away")
        assertFalse(CompassHint.next(s, true, null, 12_100).shown, "no status: no line")
        // A good reading in between starts the two seconds again.
        var t = CompassHint.next(CompassHint.State(), true, 0, 0)
        t = CompassHint.next(t, true, 3, 1_500)
        t = CompassHint.next(t, true, 0, 1_600)
        assertFalse(CompassHint.next(t, true, 0, 3_000).shown)
        assertTrue(CompassHint.next(t, true, 0, 3_700).shown)
        assertEquals("Compass needs calibrating: move the phone in a figure 8", CompassHint.LINE)
    }
}
