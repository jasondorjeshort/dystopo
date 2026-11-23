package io.github.jasondorjeshort.dystopo.app.panorama

import kotlin.test.Test
import kotlin.test.assertEquals

/** T330: the plot's spoken label in Follow (wave 46's task text: "Following the phone" before the facing, with the tilt). */
class PanoramaFollowTextTest {
    @Test
    fun theTiltReadsUpDownOrLevel() {
        assertEquals("12° up", PanoramaStrings.tilt(12.3))
        assertEquals("5° down", PanoramaStrings.tilt(-4.6))
        assertEquals("level", PanoramaStrings.tilt(0.4))
        assertEquals("level", PanoramaStrings.tilt(-0.4))
        assertEquals("90° up", PanoramaStrings.tilt(90.0))
    }

    @Test
    fun followingComesBeforeTheFacing() {
        assertEquals("Following the phone; Facing NNW 347°, 12° up, 60° across", PanoramaStrings.following(347.2, 12.0, 60.0))
        assertEquals("Facing NNW 347°, 60° across", PanoramaStrings.facing(347.2, 60.0))
    }

    // T351: where Follow phone stands is the overlays' rule's now (PanoramaOverlaysTest in shared).
}
