package io.github.jasondorjeshort.dystopo.app.controls

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T346: the zoom label beside the scale bar reads "z" and the camera's zoom to one decimal, cut rather than rounded, so
 * its whole number is the zoom the map is at; a whole zoom keeps its ".0". An install that never touched the row
 * "Show zoom level" follows [ScaleBarSettings.ZOOM_LABEL_DEFAULT]; one that did keeps its choice.
 */
class ZoomLabelTextTest {
    @Test
    fun wholeZoomsKeepTheirTenth() {
        assertEquals("z0.0", zoomLabelText(0.0))
        assertEquals("z8.0", zoomLabelText(8.0))
        assertEquals("z12.0", zoomLabelText(12.0))
        assertEquals("z22.0", zoomLabelText(22.0))
    }

    @Test
    fun fractionalZoomsAreCutToATenth() {
        assertEquals("z8.4", zoomLabelText(8.4))
        assertEquals("z8.4", zoomLabelText(8.49))
        assertEquals("z7.9", zoomLabelText(7.97), "not z8.0: the map is still at zoom 7")
        assertEquals("z8.4", zoomLabelText(8.399999999999), "the camera's sums a hair under a tenth")
        assertEquals("z12.3", zoomLabelText(12.35))
        assertEquals("z0.5", zoomLabelText(0.5))
        assertEquals("z0.0", zoomLabelText(-0.2), "never negative")
    }

    @Test
    fun theWidestTextIsNoWiderThanItsReservation() {
        // The label's width is measured from "z00.0" (ScaleBar.kt); every zoom the camera takes gives at most as many characters.
        for (t in 0..250) assertEquals(true, zoomLabelText(t / 10.0).length <= "z00.0".length, "$t")
    }

    @Test
    fun anUntouchedInstallFollowsTheDefault() {
        assertEquals(true, ScaleBarSettings.ZOOM_LABEL_DEFAULT, "on through alpha and beta (the owner, 2026-11-22)")
        assertEquals(ScaleBarSettings.ZOOM_LABEL_DEFAULT, ScaleBarSettings.showZoomFrom(null))
        assertEquals(false, ScaleBarSettings.showZoomFrom(false), "a choice made keeps")
        assertEquals(true, ScaleBarSettings.showZoomFrom(true))
    }
}
