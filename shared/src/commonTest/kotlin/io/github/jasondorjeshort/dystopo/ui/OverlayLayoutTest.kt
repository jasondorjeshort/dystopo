package io.github.jasondorjeshort.dystopo.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T224: [OverlayLayout] over a grid of phone windows, portrait and landscape, at font scales 1.0, 1.5 and 2.0, with every
 * combination of cards. Sizes follow what `GET /layout` read on an emulator (the chips capped at 1.3, the cards and the
 * strip at the system's non-linear curve).
 */
class OverlayLayoutTest {
    private class Phone(val w: Float, val h: Float)

    private val phones = listOf(Phone(320f, 568f), Phone(360f, 640f), Phone(360f, 740f), Phone(393f, 851f), Phone(411f, 731f), Phone(412f, 915f), Phone(448f, 998f))

    /** The system's curve, near enough: larger type grows less (Android 14). */
    private fun curve(scale: Float) = when {
        scale <= 1f -> 1f
        scale <= 1.5f -> 1.4f
        else -> 1.75f
    }

    private fun input(w: Float, h: Float, landscape: Boolean, scale: Float, cards: Int, buttons: Int, offCentre: Boolean, online: Boolean): OverlayInput {
        val k = curve(scale)
        val chip = minOf(k, 1.3f)
        fun card(natural: Float, least: Float, headline: Float, width: Float) = OverlayCard(natural * k, least * k, headline * k, minOf(width, w - 16f))
        return OverlayInput(
            windowWidth = w, windowHeight = h,
            insetTop = 24f, insetBottom = if (landscape) 0f else 48f, insetRight = if (landscape) 48f else 0f,
            fontScale = scale,
            search = OverlaySize(OverlayLayout.SEARCH_WIDTH, 40f * chip),
            topRow = OverlaySize(150f * chip, 36f * chip),
            credit = OverlaySize(minOf(w - 12f, 340f * k), if (k > 1.5f && w < 500f) 36f else 20f * k),
            online = if (online) OverlaySize(140f * chip, 66f * chip) else null,
            trackBar = OverlaySize(170f * chip, 36f * chip),
            routes = OverlaySize(70f * chip, 36f * chip),
            scaleBar = OverlaySize(112f, 36f * k),
            strip = listOf(OverlaySize(84f * k, 40f * k), OverlaySize(70f * k, 40f * k), OverlaySize(70f * k, 40f * k), OverlaySize(60f * k, 40f * k)),
            buttons = buttons, offCentre = offCentre,
            navigation = if (cards and 1 != 0) card(230f, 150f, 70f, 520f) else null,
            warning = if (cards and 2 != 0) card(190f, 110f, 80f, 420f) else null,
            planning = if (cards and 4 != 0) card(320f, 140f, 44f, 420f) else null,
            trip = if (cards and 8 != 0) card(240f, 120f, 40f, 440f) else null,
            update = if (cards and 16 != 0) card(110f, 90f, 50f, 440f) else null,
        )
    }

    private fun grid(each: (OverlayInput, OverlayPlacement, String) -> Unit) {
        for (p in phones) for (landscape in listOf(false, true)) for (scale in listOf(1f, 1.5f, 2f)) for (cards in 0 until 32) {
            for (buttons in 1..3) for (off in listOf(false, true)) for (online in listOf(false, true)) {
                val w = if (landscape) p.h else p.w
                val h = if (landscape) p.w else p.h
                val i = input(w, h, landscape, scale, cards, buttons, off, online)
                each(i, OverlayLayout.place(i), "${w}x$h scale $scale cards $cards buttons $buttons off $off online $online")
            }
        }
    }

    private fun OverlayPlacement.isShown(id: String) = this[id]?.rect != null

    @Test
    fun theRectanglesNeverIntersect() = grid { _, o, at ->
        assertEquals(emptyList(), o.overlaps(), at)
    }

    @Test
    fun everyRectangleStaysInTheWindow() = grid { i, o, at ->
        for (e in o.elements) {
            val r = e.rect ?: continue
            assertTrue(r.left >= 0f && r.top >= 0f && r.right <= i.windowWidth + 0.01f && r.bottom <= i.windowHeight + 0.01f, "$at: ${e.id} $r")
            assertTrue(r.width >= 0f && r.height > 0f, "$at: ${e.id} $r")
        }
    }

    @Test
    fun theNeverHiddenAreShown() = grid { i, o, at ->
        assertTrue(o.isShown(OverlayIds.CREDIT), at)
        assertTrue(o.isShown(OverlayIds.SEARCH), at)
        if (i.navigation != null) assertTrue(o.isShown(OverlayIds.NAV_CARD), at)
        if (i.warning != null) assertTrue(o.isShown(OverlayIds.WARNING_CARD), at)
        if (i.offCentre) assertTrue(o.isShown(OverlayIds.POSITION), at)
        if (i.planning != null && i.navigation == null) assertTrue(o.isShown(OverlayIds.PLANNING_CARD), at)
        if (i.planning != null && i.navigation != null) assertEquals(OverlayLayout.REASON_NAVIGATING, o[OverlayIds.PLANNING_CARD]?.reason, at)
    }

    @Test
    fun theHidingOrderHolds() = grid { i, o, at ->
        val lockGone = i.buttons >= 3 && !o.isShown(OverlayIds.LOCK)
        val orientationGone = i.buttons >= 2 && !o.isShown(OverlayIds.ORIENTATION)
        val barGone = !o.isShown(OverlayIds.SCALE_BAR)
        val entriesLeftOut = o.entriesDrawn < i.strip.size
        val stripGone = !o.isShown(OverlayIds.STRIP)
        if (orientationGone && i.buttons >= 3) assertTrue(lockGone, "$at: the lock before the orientation button")
        if (barGone) {
            if (i.buttons >= 2) assertTrue(orientationGone, "$at: the orientation button before the scale bar")
            if (i.buttons >= 3) assertTrue(lockGone, at)
        }
        if (entriesLeftOut) assertTrue(barGone, "$at: the scale bar before the entries")
        if (stripGone) assertEquals(0, o.entriesDrawn, at)
        if (!o.isShown(OverlayIds.POSITION)) {
            assertFalse(i.offCentre, at)
            assertTrue(stripGone, "$at: the position button after the elevation")
        }
    }

    @Test
    fun theElevationIsTheLastEntryToGo() {
        // Tall enough for the plate with the elevation alone and nothing more: the first entry is the one drawn.
        val base = input(360f, 640f, false, 2f, 2 or 4, 1, false, false)
        var seen = false
        for (h in 640..1000 step 2) {
            val o = OverlayLayout.place(base.copy(windowHeight = h.toFloat()))
            if (o.entriesDrawn == 1) {
                seen = true
                assertEquals(base.strip[0].height + 6f, o[OverlayIds.STRIP]?.rect?.height, "the elevation alone at $h")
            }
        }
        assertTrue(seen, "some height leaves the elevation alone")
    }

    @Test
    fun theLadderOnlyWalksDownAsTheRoomShrinks() {
        val base = input(360f, 800f, false, 2f, 2 or 4, 3, false, false)
        var last = Int.MAX_VALUE
        for (h in 800 downTo 340 step 4) {
            val o = OverlayLayout.place(base.copy(windowHeight = h.toFloat()))
            val score = o.buttonsDrawn * 100 + (if (o.isShown(OverlayIds.SCALE_BAR)) 50 else 0) + o.entriesDrawn
            assertTrue(score <= last, "at height $h: $score after $last")
            last = score
        }
    }

    @Test
    fun cardsYieldBeforeAnythingIsHidden() {
        // A warning that would take the whole band scrolls, and the buttons and the scale bar stay.
        val i = input(393f, 851f, false, 2f, 2, 3, false, true).let { it.copy(warning = it.warning!!.copy(natural = 2000f)) }
        val o = OverlayLayout.place(i)
        assertEquals(OverlayState.COMPACT, o[OverlayIds.WARNING_CARD]?.state)
        assertEquals(OverlayLayout.REASON_SCROLLS, o[OverlayIds.WARNING_CARD]?.reason)
        assertTrue(o.isShown(OverlayIds.LOCK))
        assertTrue(o.isShown(OverlayIds.SCALE_BAR))
        assertEquals(4, o.entriesDrawn)
    }

    @Test
    fun aHeadlineStaysWholeOnACommonPhone() {
        for (landscape in listOf(false, true)) for (scale in listOf(1f, 1.5f, 2f)) for (cards in listOf(1, 2, 4, 3, 6)) {
            val w = if (landscape) 800f else 360f
            val h = if (landscape) 360f else 800f
            val o = OverlayLayout.place(input(w, h, landscape, scale, cards, 1, true, true))
            for (id in listOf(OverlayIds.NAV_CARD, OverlayIds.WARNING_CARD)) {
                val e = o[id] ?: continue
                assertTrue(e.reason != OverlayLayout.REASON_HEADLINE_SCROLLS, "$w x $h scale $scale cards $cards: $id ${e.rect}")
            }
        }
    }

    @Test
    fun aPhoneOnItsSideKeepsTheChipsInTheirOwnColumn() {
        // Navigating at 2.0 on a phone on its side: the card takes the rest, and the chips stay beside it.
        val o = OverlayLayout.place(input(800f, 360f, true, 2f, 1, 1, true, false))
        assertTrue(o.sideColumn)
        val nav = assertNotNull(o[OverlayIds.NAV_CARD]?.rect)
        val bar = assertNotNull(o[OverlayIds.TRACK_BAR]?.rect)
        assertTrue(bar.left >= nav.right, "$bar beside $nav")
        assertTrue(o.isShown(OverlayIds.POSITION))
    }

    @Test
    fun headlinesThatDoNotFitBesideTheChipsTakeTheirWidth() {
        // Navigating with a warning at 2.0 on a phone on its side: two columns, and the chips give up their width.
        val o = OverlayLayout.place(input(800f, 360f, true, 2f, 3, 1, true, false))
        assertEquals(2, o.cardColumns)
        assertEquals(OverlayLayout.REASON_NO_ROOM, o[OverlayIds.TRACK_BAR]?.reason)
        assertEquals(OverlayLayout.REASON_NO_ROOM, o[OverlayIds.ROUTES]?.reason)
        assertTrue(o.isShown(OverlayIds.POSITION))
    }

    @Test
    fun aChipWithNoRoomHidesAndSaysSo() {
        // A portrait window too short for the bar under a tall card: the chips hide, the off-centre button stays.
        val i = input(360f, 420f, false, 2f, 1, 1, true, true).copy(windowWidth = 270f)
        val o = OverlayLayout.place(i)
        assertEquals(emptyList(), o.overlaps())
        assertEquals(OverlayState.HIDDEN, o[OverlayIds.TRACK_BAR]?.state)
        assertEquals(OverlayLayout.REASON_NO_ROOM, o[OverlayIds.TRACK_BAR]?.reason)
        assertEquals(OverlayState.HIDDEN, o[OverlayIds.ROUTES]?.state, "the Routes chip hides with the bar it sits under")
        assertTrue(o.isShown(OverlayIds.POSITION))
    }

    @Test
    fun searchNarrowsForTheTopRowAndNeverShrinksBelowItsLeast() {
        val o = OverlayLayout.place(input(360f, 640f, false, 2f, 0, 1, false, false))
        val s = assertNotNull(o[OverlayIds.SEARCH])
        assertEquals(OverlayState.COMPACT, s.state)
        assertEquals(OverlayLayout.REASON_NARROWED, s.reason)
        assertTrue(s.rect!!.width >= OverlayLayout.SEARCH_MIN)
        // Too narrow for both on one line: the top row takes a line of its own, Search its full width, nothing hides.
        val small = OverlayLayout.place(input(320f, 568f, false, 2f, 0, 1, false, false))
        val row = assertNotNull(small[OverlayIds.SETTINGS_ROW]?.rect)
        assertTrue(row.top >= small[OverlayIds.SEARCH]!!.rect!!.bottom)
        assertEquals(emptyList(), small.overlaps())
    }

    @Test
    fun theInventoryIdsAreNamed() {
        for (id in listOf(OverlayIds.SETTINGS_ROW, OverlayIds.SEARCH, OverlayIds.ONLINE, OverlayIds.TRACK_BAR, OverlayIds.ROUTES, OverlayIds.NAV_CARD, OverlayIds.WARNING_CARD, OverlayIds.PLANNING_CARD, OverlayIds.POSITION, OverlayIds.ORIENTATION, OverlayIds.LOCK, OverlayIds.STRIP, OverlayIds.CREDIT)) {
            assertTrue(id.startsWith("M"), id)
            assertTrue(id in OverlayIds.CONTROLS[id].orEmpty(), id)
            assertNotNull(OverlayIds.NAMES[id], id)
        }
    }
}
