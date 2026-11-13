package io.github.jasondorjeshort.dystopo.app.tracking

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T277: the track profile panel's ways to close: Close at the header's right edge as a 48 dp target, a swipe down, and
 * Back before the track bar's own Back. The overlap with the lower columns is `MapLayoutTest`'s.
 */
class ProfilePanelTest {
    private val code = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app/tracking/TrackControls.kt").readText().replace("\r\n", "\n")
    private val panel = code.substringAfter("private fun ProfilePanel(").substringBefore("val s = p.stats")

    @Test
    fun aSwipeDownClosesTheProfileAndASwipeUpDoesNot() {
        assertTrue(swipeHides(60f, 48f, downOnly = true))
        assertFalse(swipeHides(-60f, 48f, downOnly = true), "up: the map's own gesture, not a close")
        assertFalse(swipeHides(30f, 48f, downOnly = true), "short of the threshold")
        // The recording panel (T253) keeps either way.
        assertTrue(swipeHides(-60f, 48f, downOnly = false))
        assertTrue(swipeHides(60f, 48f, downOnly = false))
    }

    @Test
    fun closeIsLastInTheHeaderAsA48DpTargetAndTheNameTakesTheRest() {
        assertEquals("Close", PROFILE_CLOSE)
        assertTrue("Row(Modifier.fillMaxWidth()" in panel, panel)
        assertTrue("modifier = Modifier.weight(1f), maxLines = 1" in panel, "the name takes the free width on one line")
        val fit = panel.indexOf("HeaderAction(\"Fit\")")
        val close = panel.indexOf("HeaderAction(PROFILE_CLOSE")
        assertTrue(fit in 0 until close, "Fit, then Close at the right edge")
        assertEquals("}", panel.substring(close).substringAfter("closeProfile() }").trim().take(1), "nothing after Close in the row")
        val action = code.substringAfter("private fun HeaderAction(").substringBefore("\n}\n")
        assertTrue("sizeIn(minWidth = 48.dp, minHeight = 48.dp)" in action, action)
        assertTrue(".hideOnSwipe(downOnly = true) { closeProfile() }" in panel)
    }

    @Test
    fun backClosesTheProfileBeforeTheTrackBar() {
        // Compose asks the Back handler added last first: the panel's comes after the bar's in the composition.
        val bar = code.indexOf("BackHandler(enabled = barOpen")
        val profile = code.indexOf("BackHandler { closeProfile() }")
        assertTrue(bar in 0 until profile, "bar $bar, profile $profile")
        assertTrue(profile > code.indexOf("private fun ProfilePanel("))
    }
}
