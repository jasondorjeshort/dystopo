package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.horizon.PanoramaOverlays.Item
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T351: the Panorama's overlays placed by one rule ([PanoramaOverlays]): no two boxes overlap and each stays on the plot,
 * at font 1.0 and 2.0, upright and on its side, with a target off the view (its arrow at every edge), a correction set
 * and the weak-compass line on. The plates are modelled on the app's own (their texts, sizes and paddings), each glyph
 * half its text size wide.
 */
class PanoramaOverlaysTest {
    private val dp = 2.625 // a Pixel 6

    /** A plate of [chars] glyphs at [sp] (scaled by [font]) on [lines] lines at most, [pad] dp a side and [extra] dp of button beside it, at least [minH] dp tall; [under] puts the button under the text. */
    private fun plate(chars: Int, sp: Double, font: Double, pad: Double = 12.0, extra: Double = 0.0, minH: Double = 48.0, lines: Int = 9, under: Double = 0.0) =
        PanoramaOverlays.Plate { maxW ->
            val glyph = 0.5 * sp * font * dp
            val lineH = 1.3 * sp * font * dp
            val room = maxW - (2 * pad + extra) * dp
            val natural = chars * glyph
            val perLine = max(1.0, floor(room / glyph))
            val n = min(lines.toDouble(), ceil(chars / perLine))
            val textW = min(natural, perLine * glyph)
            val w = textW + (2 * pad + extra) * dp
            PanoramaOverlays.Size(min(maxW, w), max(minH * dp, n * lineH + 8 * dp) + under * dp)
        }

    private fun button(text: String, font: Double) = plate(text.length, 14.0, font, lines = 1)

    /** The switch at its anchor: 8 dp in, 48 dp tall, 298 dp wide at font 1.0 and 335.6 at 2.0 (map chrome's cap at 1.3; T344's measures). */
    private fun switchBox(h: Double, font: Double): RimBox {
        val wDp = if (font > 1.3) 335.6 else 298.0
        return RimBox(8 * dp, h - 56 * dp, (8 + wDp) * dp, h - 8 * dp)
    }

    private fun input(wDp: Double, hDp: Double, font: Double, arrow: PanoramaOverlays.Arrow?, c: Case = Case.EVERYTHING): PanoramaOverlays.Input {
        val w = wDp * dp
        val h = hDp * dp
        val backButton = 0.5 * 14 * font * 12 + 24 // "Back to here"
        val viewFrom = 0.5 * 14 * font * 14 + 24 // "View from here"
        return PanoramaOverlays.Input(
            w, h, dp, switchBox(h, font),
            layers = button("Layers", font), list = button("List view", font),
            followWidth = (0.5 * 14 * font * 12 + 24) * dp,
            // "At Mount Rainier, 7.6 km NNW of you" bold 16 sp with "Back to here" beside it.
            place = if (c.place) plate(35, 16.0, font, extra = backButton) else null,
            // "Mount Rainier Â· 14,410 ft Â· 12.4 mi NNE 23Â°" 15 sp with Ã— and "View from here" (under it above font 1.3).
            picked = if (c.picked) plate(44, 15.0, font, extra = 24.0 + if (font > 1.3) 0.0 else viewFrom, under = if (font > 1.3) 52.0 else 0.0) else null,
            // "Compass needs calibrating: move the phone in a figure 8", 13 sp.
            hint = if (c.hint) plate(55, 13.0, font, minH = 0.0) else null,
            // "Flights: ADSB.lol contributors (ODbL)", 10 dp (not scaled), two lines at most.
            credit = if (c.credit) plate(37, 10.0, 1.0, pad = 4.0, minH = 0.0, lines = 2) else null,
            // "Compass adjusted 180Â° west" 13 sp with Reset, two lines at most.
            correction = if (c.correction) plate(26, 13.0, font, pad = 10.0, extra = 0.5 * 14 * font * 5 + 24, lines = 2) else null,
            arrow = arrow,
        )
    }

    /** "Mount Rainier, 40Â° left" with its glyph, 14 sp, two lines at most. */
    private fun arrowPlate(font: Double) = plate(23 + 2, 14.0, font, pad = 10.0, lines = 2)

    private fun arrows(wDp: Double, hDp: Double, font: Double): List<PanoramaOverlays.Arrow?> {
        val w = wDp * dp
        val h = hDp * dp
        val m = 6 * dp
        val top = 28 * dp + m
        val out = ArrayList<PanoramaOverlays.Arrow?>()
        out += null
        for (f in listOf(0.0, 0.1, 0.3, 0.5, 0.7, 0.9, 1.0)) {
            val y = top + (h - m - top) * f
            out += PanoramaOverlays.Arrow(OffSide.LEFT, m, y, arrowPlate(font))
            out += PanoramaOverlays.Arrow(OffSide.RIGHT, w - m, y, arrowPlate(font))
            val x = m + (w - 2 * m) * f
            out += PanoramaOverlays.Arrow(OffSide.UP, x, top, arrowPlate(font))
            out += PanoramaOverlays.Arrow(OffSide.DOWN, x, h - m, arrowPlate(font))
        }
        return out
    }

    /** Windows (dp): a Pixel 6 upright and on its side, less the system bars, and a small phone both ways. */
    private val windows = listOf(411.0 to 866.0, 866.0 to 387.0, 360.0 to 692.0, 692.0 to 336.0)

    /** Which plates show: the place line (a chosen point), a picked peak, the weak-compass line, a correction, the flights' credit. */
    enum class Case(val place: Boolean, val picked: Boolean, val hint: Boolean, val correction: Boolean, val credit: Boolean) {
        /** Follow at the fix, a peak the target off the view, a correction set, the weak-compass line on, Flights on (T331's a11y screen and more). */
        FOLLOW_AT_FIX(false, true, true, true, true),
        FOLLOW_NO_FLIGHTS(false, true, true, true, false),
        /** Drag at a chosen point, a peak picked, Flights on. */
        DRAG_AT_POINT(true, true, false, false, true),
        /** Everything at once (Follow at a chosen point): not promised on its side at font 2.0, where it does not fit (see the KDoc). */
        EVERYTHING(true, true, true, true, true),
        NOTHING(false, false, false, false, false),
    }

    /**
     * Where the rule promises no overlap: everywhere at font 1.0; at font 2.0, on a Pixel 6 (the emulator's window) every
     * case upright and all but everything at once on its side; on a 360 dp phone upright the cases without a chosen
     * point's line (it wraps to five lines beside "Back to here"), and on its side (336 dp tall) only the bare lens. The
     * PanoramaOverlays KDoc says so.
     */
    private fun promised(wDp: Double, hDp: Double, font: Double, c: Case): Boolean = when {
        font < 1.3 -> true
        wDp >= 400 && hDp > wDp -> true
        wDp > hDp && hDp >= 380 -> c != Case.EVERYTHING
        hDp > wDp -> !c.place
        else -> c == Case.NOTHING
    }

    @Test
    fun noTwoOverlaysOverlapAtEitherFontEitherWayUp() {
        for ((wDp, hDp) in windows) for (font in listOf(1.0, 2.0)) for (c in Case.entries) for (a in arrows(wDp, hDp, font)) {
            if (!promised(wDp, hDp, font, c)) continue
            val l = PanoramaOverlays.place(input(wDp, hDp, font, a, c))
            val what = "window ${wDp}x$hDp dp, font $font, $c, arrow ${a?.side} at ${a?.x?.let { it / dp }?.toInt()},${a?.y?.let { it / dp }?.toInt()}"
            assertNull(l.overlap(), "$what: ${l.overlap()} overlap (${l.overlap()?.let { (p, q) -> "${l[p]} ${l[q]}" }})")
            for ((k, b) in l.boxes) {
                assertTrue(b.left >= -0.01 && b.top >= -0.01 && b.right <= wDp * dp + 0.01 && b.bottom <= hDp * dp + 0.01, "$what: $k off the plot $b")
            }
            if (a != null) assertNotNull(l[Item.ARROW], what)
        }
    }

    @Test
    fun everyBoxButTheTapeIsGivenToTheSceneArrowIncluded() {
        val a = PanoramaOverlays.Arrow(OffSide.LEFT, 6 * dp, 300 * dp, arrowPlate(1.0))
        val l = PanoramaOverlays.place(input(411.0, 866.0, 1.0, a))
        assertEquals(l.boxes.size - 1, l.keepOff.size)
        assertTrue(l[Item.ARROW]!! in l.keepOff, "the arrow's box keeps names off (T331's follow-up)")
        assertTrue(l[Item.TAPE]!! !in l.keepOff)
    }

    @Test
    fun uprightTheButtonsStandAtTheEdgesAndTheLandIsLeftFree() {
        val l = PanoramaOverlays.place(input(411.0, 866.0, 1.0, null, Case.FOLLOW_AT_FIX))
        val w = 411 * dp
        // The zoom buttons and Follow phone at the lower right, clear of the switch at the lower left.
        assertEquals(w - 8 * dp, l[Item.ZOOM_OUT]!!.right, 0.01)
        assertTrue(l[Item.FOLLOW]!!.bottom < l[Item.ZOOM_IN]!!.top)
        // Layers and List view side by side at the upper right, under the tape.
        assertEquals(l[Item.LAYERS]!!.top, l[Item.LIST]!!.top, 0.01)
        assertTrue(l[Item.LAYERS]!!.top >= 22 * dp)
        // Half the window at least is free land between the upper and the lower overlays.
        assertTrue(l.free.bottom - l.free.top > 0.5 * 866 * dp, "free ${l.free}")
    }

    @Test
    fun followPhoneStandsAboveTheZoomButtonsRightAligned() {
        for ((wDp, hDp) in windows) {
            val l = PanoramaOverlays.place(input(wDp, hDp, 1.0, null, Case.NOTHING))
            val f = l[Item.FOLLOW]!!
            val zin = l[Item.ZOOM_IN]!!
            assertEquals(zin.right, f.right, 0.01)
            assertEquals(48 * dp, f.bottom - f.top, 0.01)
            assertEquals(zin.top - 8 * dp, f.bottom, 0.01)
            assertEquals(l[Item.ZOOM_OUT]!!.top - 8 * dp, zin.bottom, 0.01)
        }
    }

    @Test
    fun theCorrectionStandsAtTheLowerLeftBesideOrAboveTheSwitchAndTheCreditBelowIt() {
        for ((wDp, hDp) in windows) for (font in listOf(1.0, 2.0)) {
            val i = input(wDp, hDp, font, null, Case.FOLLOW_AT_FIX)
            val l = PanoramaOverlays.place(i)
            val c = l[Item.CORRECTION]!!
            val sw = l[Item.SWITCH]!!
            val beside = c.left >= sw.right
            // Whole beside the switch: as wide as above it, never squeezed (on the emulator a squeezed plate read "Co m").
            val stackWidth = min(0.75 * wDp * dp, l[Item.ZOOM_IN]!!.left - 12 * dp)
            if (beside) assertTrue(c.right - c.left >= i.correction!!.size(stackWidth).width - 0.5, "window $wDp x $hDp font $font: correction squeezed to $c")
            assertTrue(beside || c.bottom <= sw.top, "window $wDp x $hDp font $font: $c not beside or above $sw")
            if (!beside) assertEquals(6 * dp, c.left, 0.01)
            assertTrue(c.right <= l[Item.ZOOM_IN]!!.left || c.bottom <= l[Item.FOLLOW]!!.top)
        }
    }

    @Test
    fun aNarrowWindowLiftsTheLowerRightColumnAboveTheSwitch() {
        // At font 2.0 the switch is 335.6 dp wide: on a 360 dp window it reaches under the zoom buttons' column.
        val l = PanoramaOverlays.place(input(360.0, 692.0, 2.0, null))
        assertTrue(l[Item.ZOOM_OUT]!!.bottom <= l[Item.SWITCH]!!.top, "${l[Item.ZOOM_OUT]} over ${l[Item.SWITCH]}")
    }

    @Test
    fun anArrowOverAButtonMovesAlongItsEdge() {
        // A target off to the right at the height of the zoom buttons: the plate moves up its edge, not across the plot.
        val h = 866 * dp
        val w = 411 * dp
        val a = PanoramaOverlays.Arrow(OffSide.RIGHT, w - 6 * dp, h - 90 * dp, arrowPlate(1.0))
        val l = PanoramaOverlays.place(input(411.0, 866.0, 1.0, a, Case.NOTHING))
        val b = l[Item.ARROW]!!
        assertEquals(w - 6 * dp, b.right, 0.01)
        assertTrue(b.bottom <= l[Item.FOLLOW]!!.top, "$b")
    }
}
