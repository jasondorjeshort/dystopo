package io.github.jasondorjeshort.dystopo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T162: the open view's rule ([OpenView]) and the last fix's notion of a move ([LastFix.moved]). */
class OpenViewTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L

    // Paradise, Sunrise and the Rainier pack's middle (public places).
    private fun fix(movedAt: Long, at: Long = movedAt) = LastFix.Fix(46.786, -121.7357, 10.0, at, movedAt)
    private fun saved(at: Long) = ViewAt(46.914, -121.643, 13.5, 0.0, at)
    private fun pack(at: Long) = ViewAt(46.83, -121.70, 10.2, 0.0, at)

    @Test
    fun theNewestOfTheThreeWins() {
        assertEquals(OpenView.Source.FIX, OpenView.choose(fix(now - 1000), saved(now - 2000), pack(now - 3000)).source)
        assertEquals(OpenView.Source.SAVED, OpenView.choose(fix(now - 2000), saved(now - 1000), pack(now - 3000)).source)
        assertEquals(OpenView.Source.PACK, OpenView.choose(fix(now - 3000), saved(now - 2000), pack(now - 1000)).source)
    }

    @Test
    fun aPackNeverActivatedLosesToAnythingAndWinsAlone() {
        assertEquals(OpenView.Source.SAVED, OpenView.choose(null, saved(now - 30 * day), pack(0)).source)
        assertEquals(OpenView.Source.PACK, OpenView.choose(null, null, pack(0)).source)
        val world = OpenView.choose(null, null, null)
        assertEquals(OpenView.Source.WORLD, world.source)
        assertNull(world.view)
    }

    @Test
    fun aFixThatOnlyArrivedLaterDoesNotBeatAViewSetAfterItMoved() {
        // The phone lay still (moved 10 min ago, a fix every second since); the user panned to Sunrise 5 min ago.
        val still = fix(movedAt = now - 600_000, at = now - 1000)
        assertEquals(OpenView.Source.SAVED, OpenView.choose(still, saved(now - 300_000), null).source)
    }

    @Test
    fun theFixOpensAtTheSavedZoomElseTheFixZoom() {
        val withSaved = OpenView.choose(fix(now), saved(now - 1000), null).view!!
        assertEquals(13.5, withSaved.zoom)
        assertEquals(46.786, withSaved.lat)
        assertEquals(OpenView.FIX_ZOOM, OpenView.choose(fix(now), null, null).view!!.zoom)
    }

    @Test
    fun aFreshFixRecentresOnceWhenItMovedAfterTheOpenView() {
        val opened = saved(now - 60_000)
        assertTrue(OpenView.recentres(opened, fix(now), 5_000, now))
        assertFalse(OpenView.recentres(opened, fix(now), OpenView.FIX_WINDOW_MS + 1, now), "after the window")
        assertFalse(OpenView.recentres(opened, fix(now - 120_000, at = now), 5_000, now), "the same place as before the view")
    }

    @Test
    fun aSavedViewOlderThanAWeekDoesNotBeatAFreshFix() {
        val old = saved(now - 8 * day)
        val sameOldPlace = fix(movedAt = now - 20 * day, at = now)
        assertTrue(OpenView.recentres(old, sameOldPlace, 5_000, now))
        assertTrue(OpenView.recentres(old, sameOldPlace, OpenView.STALE_FIX_WINDOW_MS, now), "the longer window")
        assertFalse(OpenView.recentres(old, sameOldPlace, OpenView.STALE_FIX_WINDOW_MS + 1, now))
        assertTrue(OpenView.recentres(pack(0), sameOldPlace, 30_000, now), "a pack never activated is stale")
    }

    @Test
    fun aMoveIsMoreThanTheAccuracyAndAtLeastFiftyMetres() {
        val f = LastFix.Fix(46.786, -121.7357, 10.0, now, now)
        val north40m = 46.786 + 40.0 / 111_195.0
        val north80m = 46.786 + 80.0 / 111_195.0
        assertFalse(LastFix.moved(f, north40m, -121.7357, 5.0))
        assertTrue(LastFix.moved(f, north80m, -121.7357, 5.0))
        assertFalse(LastFix.moved(f, north80m, -121.7357, 100.0), "within a network fix's accuracy")
    }
}
