package io.github.jasondorjeshort.dystopo.app.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T212: the cards' caps at large font scales ([CardBands]), on the figures measured at font scale 2.0. */
class CardBandsTest {
    @Test
    fun planningCardLeavesTheScaleBarLineWhenTheRestStillFits() {
        // Portrait, font scale 2.0, display size largest (320 x 711 dp): T192's band was 383.9 dp, and the scale bar's
        // line needs about 50 dp more below the top-left column than the chips leave.
        val (cap, kept) = CardBands.planningCap(band = 383.9f, reserve = 50f, needs = 260f)
        assertTrue(kept)
        assertEquals(333.9f, cap, 0.01f)
    }

    @Test
    fun planningCardTakesTheWholeBandWhenTheLineWouldSqueezeIt() {
        // Landscape at 2.0: a band of 150 dp cannot spare the line and still hold the header, actions and two lines.
        val (cap, kept) = CardBands.planningCap(band = 150f, reserve = 50f, needs = 140f)
        assertFalse(kept)
        assertEquals(150f, cap)
    }

    @Test
    fun aCardGoesCompactOnlyWhenTheHeaderAndActionsLeaveLessThanTwoLines() {
        assertFalse(CardBands.compact(headerPx = 300, footerPx = 200, minBodyPx = 120f, spacingPx = 20f, capPx = 700f))
        assertTrue(CardBands.compact(headerPx = 300, footerPx = 200, minBodyPx = 120f, spacingPx = 20f, capPx = 600f))
    }

    @Test
    fun navigationBandIsWhatTheTallerStackUnderTheCardLeaves() {
        // 711 dp window, the card's top at 98 dp, the credit line's pad 91.6 dp; 160 dp on the left, 138 on the right.
        assertEquals(361.4f, CardBands.navBand(711f, 98f, 91.6f, 160f, 138f), 0.01f)
        assertEquals(0f, CardBands.navBand(300f, 98f, 91.6f, 160f, 138f))
    }

    @Test
    fun warningCardStartsUnderACompassThatReachesIntoItsBand() {
        // The card spans 12 to 308 dp under the chrome at 180 dp; a compass at 272 to 320 dp across, 190 to 238 dp down.
        assertEquals(246f, CardBands.warningTop(chromeTop = 188f, compass = floatArrayOf(272f, 190f, 320f, 238f), cardLeft = 12f, cardRight = 308f, bottomLimit = 560f))
        // Above the chrome (MapLibre's default, top right under the status bar): the card stays where it was.
        assertEquals(188f, CardBands.warningTop(chromeTop = 188f, compass = floatArrayOf(272f, 0f, 320f, 48f), cardLeft = 12f, cardRight = 308f, bottomLimit = 560f))
        // Beside the card, or none drawn: unchanged.
        assertEquals(188f, CardBands.warningTop(chromeTop = 188f, compass = floatArrayOf(312f, 190f, 330f, 238f), cardLeft = 12f, cardRight = 308f, bottomLimit = 560f))
        assertEquals(188f, CardBands.warningTop(chromeTop = 188f, compass = null, cardLeft = 12f, cardRight = 308f, bottomLimit = 560f))
    }

    @Test
    fun warningCardFoldsOnlyWhenItsWholeHeightPassesItsRoom() {
        assertNull(CardBands.warningFits(detail = null, header = 60f, actions = 40f, chrome = 28f, room = 400f))
        assertEquals(true, CardBands.warningFits(detail = 120f, header = 60f, actions = 40f, chrome = 28f, room = 400f))
        assertEquals(false, CardBands.warningFits(detail = 320f, header = 60f, actions = 40f, chrome = 28f, room = 400f))
    }
}
