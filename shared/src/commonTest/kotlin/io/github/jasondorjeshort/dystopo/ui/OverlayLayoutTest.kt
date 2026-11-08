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

    // ---- T239 (Layout B): the latch, and the placement around cards drawn by their own code ----

    @Test
    fun theLatchHoldsTheShapeUntilTheWindowTheFontOrTheCardsChange() {
        // A window on its side, narrow enough that a wider track bar would undo the side column.
        val base = input(600f, 360f, true, 1f, 0, 1, false, true)
        val first = OverlayLayout.place(base)
        assertTrue(first.sideColumn)
        val wider = base.copy(trackBar = OverlaySize(340f, 36f))
        assertFalse(OverlayLayout.place(wider).sideColumn, "on its own the wider bar takes the side column away")
        // The chips re-measured at a new width within the same window, font scale and cards: the shape holds.
        val held = OverlayLayout.place(wider, first.latch)
        assertTrue(held.sideColumn)
        assertEquals(first.latch, held.latch)
        assertEquals(emptyList(), held.overlaps())
        // A new window, font scale or set of cards decides again.
        assertFalse(OverlayLayout.place(wider.copy(windowHeight = 361f), first.latch).sideColumn)
        assertFalse(OverlayLayout.place(wider.copy(fontScale = 1.5f), first.latch).sideColumn)
        val withCard = wider.copy(warning = OverlayCard(120f, 90f, 60f, 420f))
        assertEquals(setOf(OverlayIds.WARNING_CARD), OverlayLayout.place(withCard, first.latch).latch?.cards)
        assertFalse(OverlayLayout.place(withCard, first.latch).sideColumn)
    }

    /** [i] with its cards drawn where the rule itself would put them (the warning left out: it follows the chips in the app). */
    private fun drawnFrom(i: OverlayInput): OverlayInput {
        val o = OverlayLayout.place(i)
        fun drawn(c: OverlayCard?, id: String) = c?.let { card -> o[id]?.rect?.let { card.copy(drawn = it) } }
        return i.copy(
            navigation = drawn(i.navigation, OverlayIds.NAV_CARD), warning = null, planning = drawn(i.planning, OverlayIds.PLANNING_CARD),
            trip = drawn(i.trip, OverlayIds.TRIP_CARD), update = drawn(i.update, OverlayIds.UPDATE_CARD), cardsDrawn = true,
        )
    }

    @Test
    fun aroundDrawnCardsTheRectanglesNeverIntersect() = grid { i, _, at ->
        val j = drawnFrom(i)
        val o = OverlayLayout.place(j)
        assertEquals(emptyList(), o.overlaps(), "drawn: $at")
        for (e in o.elements) {
            val r = e.rect ?: continue
            assertTrue(r.left >= 0f && r.top >= 0f && r.right <= j.windowWidth + 0.01f && r.bottom <= j.windowHeight + 0.01f, "drawn: $at: ${e.id} $r")
        }
        assertTrue(o.isShown(OverlayIds.CREDIT), at)
        for ((id, c) in listOf(OverlayIds.NAV_CARD to j.navigation, OverlayIds.PLANNING_CARD to j.planning, OverlayIds.TRIP_CARD to j.trip)) {
            val d = c?.drawn ?: continue
            assertEquals(d, o[id]?.rect, "drawn: $at: $id stays where it is drawn")
        }
    }

    @Test
    fun aroundDrawnCardsTheLadderHoldsInEachColumn() = grid { i, _, at ->
        val j = drawnFrom(i)
        val o = OverlayLayout.place(j)
        val lockGone = j.buttons >= 3 && !o.isShown(OverlayIds.LOCK)
        val orientationGone = j.buttons >= 2 && !o.isShown(OverlayIds.ORIENTATION)
        if (orientationGone && j.buttons >= 3 && o.isShown(OverlayIds.POSITION)) assertTrue(lockGone, "drawn: $at: the lock before the orientation button")
        // Where both columns have room, the ladder takes the scale bar only after the other buttons (a column with no room at
        // any rung hides whole, `noRoom`, and the other keeps its own).
        if (o.isShown(OverlayIds.POSITION) && o[OverlayIds.SCALE_BAR]?.reason == OverlayLayout.REASON_ORDER) {
            if (j.buttons >= 2) assertTrue(orientationGone, "drawn: $at: the orientation button before the scale bar")
        }
        if (o.entriesDrawn in 1 until j.strip.size) assertFalse(o.isShown(OverlayIds.SCALE_BAR), "drawn: $at: the scale bar before the entries")
    }

    /** The emulator's upright window at font scale 2.0 with a planning card drawn tall (`GET /layout` on a Pixel 6, dp). */
    private fun uprightPlanning(planningTop: Float) = OverlayInput(
        windowWidth = 411.4f, windowHeight = 914.3f, insetTop = 48.8f, insetBottom = 24f, fontScale = 2f,
        search = OverlaySize(OverlayLayout.SEARCH_WIDTH, 63.2f), topRow = OverlaySize(236.6f, 38.1f), credit = OverlaySize(399.2f, 55.6f),
        online = OverlaySize(200.4f, 32.4f), trackBar = OverlaySize(106.7f, 82.3f), routes = OverlaySize(86.9f, 38.1f),
        scaleBar = OverlaySize(79.6f, 41.9f), strip = List(4) { OverlaySize(59.1f, 66.4f) }, buttons = 1,
        planning = OverlayCard(600f, 140f, 44f, 395.4f, drawn = OverlayRect(8f, planningTop, 403.4f, 822.5f)), cardsDrawn = true,
    )

    @Test
    fun aDrawnCardThatLeavesThePositionColumnNoRoomHidesItAlone() {
        // The position button has no room between the chips and the card (the card's own cap leaves the scale bar's line
        // room on the left, beside the chips): the column hides alone, and the scale bar and the strip stay above the card.
        val o = OverlayLayout.place(uprightPlanning(291f))
        assertEquals(emptyList(), o.overlaps())
        assertEquals(OverlayState.HIDDEN, o[OverlayIds.POSITION]?.state)
        assertEquals(OverlayLayout.REASON_NO_ROOM, o[OverlayIds.POSITION]?.reason)
        assertTrue(o.isShown(OverlayIds.SCALE_BAR))
        assertEquals(4, o.entriesDrawn)
        val strip = assertNotNull(o[OverlayIds.STRIP]?.rect)
        val online = assertNotNull(o[OverlayIds.ONLINE]?.rect)
        assertTrue(strip.top >= online.bottom, "the strip under the online column: $strip $online")
        assertTrue(o[OverlayIds.SCALE_BAR]!!.rect!!.bottom <= 291f, "above the card")
        // With the card lower (it follows the chips a frame later), the button has its room again.
        assertTrue(OverlayLayout.place(uprightPlanning(330f)).isShown(OverlayIds.POSITION))
    }

    @Test
    fun theChipsStartUnderADrawnNavigationCard() {
        val nav = OverlayRect(8f, 126f, 403f, 380f)
        val i = uprightPlanning(291f).copy(planning = null, navigation = OverlayCard(270f, 150f, 70f, 395f, drawn = nav))
        val o = OverlayLayout.place(i)
        assertEquals(emptyList(), o.overlaps())
        assertEquals(nav.bottom + 6f, o[OverlayIds.TRACK_BAR]?.rect?.top)
        assertEquals(nav.bottom + 6f, o[OverlayIds.ONLINE]?.rect?.top)
        // On its side the chips' column starts under the card only where the card reaches it.
        val side = input(800f, 360f, true, 1f, 0, 1, false, true).copy(cardsDrawn = true)
        val narrow = OverlayLayout.place(side.copy(navigation = OverlayCard(200f, 150f, 70f, 400f, drawn = OverlayRect(100f, 90f, 500f, 260f))))
        assertTrue(narrow.sideColumn)
        assertTrue(narrow[OverlayIds.TRACK_BAR]!!.rect!!.top < 260f, "beside a card that does not reach the column")
        val wide = OverlayLayout.place(side.copy(navigation = OverlayCard(200f, 150f, 70f, 700f, drawn = OverlayRect(50f, 90f, 750f, 260f))))
        assertTrue(wide[OverlayIds.TRACK_BAR]!!.rect!!.top >= 266f, "under a card that reaches it")
        assertEquals(emptyList(), wide.overlaps())
    }

    @Test
    fun theChipsDoNotYieldToTheWarningItFollowsThem() {
        // The app draws the warning under the chips a frame after they move: one drawn where they go does not hide them.
        val i = uprightPlanning(291f).copy(planning = null, warning = OverlayCard(150f, 110f, 80f, 395f, drawn = OverlayRect(8f, 130f, 403f, 300f)))
        val o = OverlayLayout.place(i)
        assertTrue(o.isShown(OverlayIds.TRACK_BAR))
        assertTrue(o.isShown(OverlayIds.ROUTES))
        // The lower columns keep under it.
        val strip = o[OverlayIds.STRIP]?.rect
        if (strip != null) assertTrue(strip.top >= 306f)
        val bar = o[OverlayIds.SCALE_BAR]?.rect
        if (bar != null) assertTrue(bar.top >= 306f)
    }

    @Test
    fun anOpenPanelTakesWhatTheChipsAndThePositionButtonLeave() {
        val base = uprightPlanning(291f).copy(planning = null)
        val o = OverlayLayout.place(base.copy(trackPanel = 2000f))
        val bar = assertNotNull(o[OverlayIds.TRACK_BAR]?.rect)
        val button = assertNotNull(o[OverlayIds.POSITION]?.rect)
        assertTrue(o.isShown(OverlayIds.ROUTES), "the chip under the bar keeps its place")
        assertTrue(bar.height > 82.3f + 100f, "the panel opens: ${bar.height}")
        assertTrue(o[OverlayIds.ROUTES]!!.rect!!.bottom + 6f <= button.top + 0.01f, "and leaves the button its place")
        assertEquals(emptyList(), o.overlaps())
        assertEquals(82.3f + (bar.height - 82.3f), o.trackRoom, 0.01f)
        // A short panel is drawn whole.
        val short = OverlayLayout.place(base.copy(trackPanel = 120f))
        assertEquals(82.3f + 120f, short[OverlayIds.TRACK_BAR]!!.rect!!.height, 0.01f)
        assertTrue(short.trackRoom > 82.3f + 120f)
    }

    @Test
    fun theStripStaysShortOfTheChipsOrUnderThem() {
        // A narrow upright window with the strip wide enough to reach the chips' edge: it never reaches under a chip.
        for (w in 300..420 step 10) for (scale in listOf(1f, 2f)) {
            val i = input(w.toFloat(), 700f, false, scale, 4, 1, false, true).let { drawnFrom(it) }
            val o = OverlayLayout.place(i)
            val strip = o[OverlayIds.STRIP]?.rect ?: continue
            for (chip in listOf(OverlayIds.TRACK_BAR, OverlayIds.ROUTES, OverlayIds.COMPASS)) {
                val c = o[chip]?.rect ?: continue
                assertFalse(strip.intersects(c), "$w at $scale: the strip $strip reaches $chip $c")
            }
            assertEquals(i.strip.size.coerceAtMost(o.entriesDrawn), o.entriesDrawn)
            assertTrue(o.stripRoom >= strip.width - 0.01f, "the strip's rows wrap at its room")
        }
    }

    @Test
    fun whereNoEntryFitsTheScaleBarStaysAlone() {
        // The emulator on its side at font scale 2.0 with a planning card drawn: the strip's entries are taller than the room
        // above the card, the scale bar is not; the ladder would take the bar first, but its going frees nothing there.
        val i = OverlayInput(
            windowWidth = 914.3f, windowHeight = 411.4f, insetLeft = 48.8f, insetTop = 24f, insetBottom = 24f, fontScale = 2f,
            search = OverlaySize(OverlayLayout.SEARCH_WIDTH, 40.8f), topRow = OverlaySize(236.6f, 38.1f), credit = OverlaySize(849f, 29.7f),
            online = OverlaySize(200.4f, 32.4f), trackBar = OverlaySize(197.7f, 38.1f), routes = OverlaySize(86.9f, 38.1f),
            scaleBar = OverlaySize(79.6f, 41.9f), strip = List(4) { OverlaySize(59.1f, 66.4f) }, buttons = 1,
            planning = OverlayCard(300f, 140f, 44f, 420f, drawn = OverlayRect(247.2f, 170.3f, 667.4f, 345.5f)), cardsDrawn = true,
        )
        val o = OverlayLayout.place(i)
        assertTrue(o.sideColumn)
        assertEquals(emptyList(), o.overlaps())
        assertTrue(o.isShown(OverlayIds.SCALE_BAR))
        assertEquals(OverlayLayout.REASON_NO_ROOM, o[OverlayIds.STRIP]?.reason)
        assertEquals(0, o.entriesDrawn)
        assertTrue(o.isShown(OverlayIds.POSITION), "the card does not reach the chips' column, so the button keeps its corner")
        assertTrue(o[OverlayIds.SCALE_BAR]!!.rect!!.bottom <= 170.3f - 6f + 0.01f)
    }
}
