package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.app.MapLayout
import io.github.jasondorjeshort.dystopo.app.MeasuredControls
import io.github.jasondorjeshort.dystopo.ui.OverlayCard
import io.github.jasondorjeshort.dystopo.ui.OverlayInput
import io.github.jasondorjeshort.dystopo.ui.OverlayLayout
import io.github.jasondorjeshort.dystopo.ui.OverlayRect
import io.github.jasondorjeshort.dystopo.ui.OverlaySize
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T371: the readout bar's measure and the layout rule, driven as the app's layout drives them ([StripExchange],
 * [MapLayout.stripLimit], [OverlayLayout.place]) until the strip's limit stops changing. The app remeasures the strip when
 * the rule's limit for it changes and asks the rule again when its sizes change; a limit that comes back unchanged ends
 * the exchange. The scene of the freeze (font scale 2.0, the track bar's panel open, a route card up; the rule's input as
 * the app logged it) went round without end; with the exchange it ends in a few rounds on the smaller answer, and in every
 * scene where the exchange settles without it, it settles on the same limit and the same sizes, so the bar's look is
 * unchanged wherever it settled before.
 */
class StripExchangeTest {
    /** A Pixel 6 class window: 1080 px wide at 2.625 px per dp. */
    private val d = 2.625f
    private val windowPx = 1080

    /** The plate's horizontal padding (6 dp each side, rounded per side as Compose does). */
    private val padPx = 2 * Math.round(6 * d)

    /**
     * Entry sizes (px) as the emulator measured them (`GET /readout` `layout.entrySizesPx`): elevation, climb, speed,
     * course, pressure, accuracy, fix age; the same with the "?" marks shown and without (the mark takes no room, T363).
     */
    private val seven2 = listOf(371 to 161, 302 to 158, 268 to 145, 336 to 145, 394 to 158, 213 to 145, 191 to 145)
    private val seven1 = listOf(184 to 85, 161 to 83, 140 to 77, 187 to 77, 209 to 83, 114 to 77, 98 to 77)
    private val three2 = listOf(seven2[0], seven2[1], seven2[4])
    private val three1 = listOf(seven1[0], seven1[1], seven1[4])

    /** An entry measured within [w] px: whole when it fits, else wrapped to [w] in as many lines as it needs. */
    private fun sizesAt(entries: List<Pair<Int, Int>>, w: Int): List<OverlaySize> = entries.map { (ew, eh) ->
        if (ew <= w) OverlaySize(ew / d, eh / d) else OverlaySize(w / d, eh * ceil(ew.toDouble() / w).toFloat() / d)
    }

    /** A length the app logged in dp, given here in px (its value at [d]). */
    private fun p(px: Int) = px / d

    /** The rule's input at the freeze (font scale 2.0, as logged by the app), its strip replaced per round. */
    private val freeze = OverlayInput(
        windowWidth = 411.42856f, windowHeight = 914.2857f, insetTop = p(128), insetBottom = 24f, fontScale = 2f,
        search = OverlaySize(220f, p(166)), topRow = OverlaySize(236.57143f, p(100)), credit = OverlaySize(399.2381f, p(146)),
        online = OverlaySize(253.71428f, p(85)), trackBar = OverlaySize(339.0476f, p(242)), routes = OverlaySize(p(228), p(100)),
        activity = OverlaySize(p(226), p(100)), scaleBar = OverlaySize(p(383), p(110)), horizonSwitch = OverlaySize(335.61905f, 48f),
        stripCore = 2, buttons = 1, offCentre = true,
        planning = OverlayCard(585.5238f, 252.71428f, p(244), 395.42856f, drawn = OverlayRect(8f, 363.8095f, 403.42856f, 766.4762f), keep = 252.71428f),
        trackPanel = p(185), cardsDrawn = true,
    )

    private class Run(val settled: Boolean, val rounds: Int, val limit: MeasuredControls.Limit?, val sizes: List<OverlaySize>)

    /** The app's exchange from the strip's first measure (no limit yet), with [exchange] or without (the code before T371). */
    private fun drive(input: OverlayInput, entries: List<Pair<Int, Int>>, exchange: StripExchange?, maxRounds: Int = 40): Run {
        var limit: MeasuredControls.Limit? = null
        var sizes = emptyList<OverlaySize>()
        for (round in 1..maxRounds) {
            val maxW = (limit?.maxWidthPx ?: windowPx) - padPx
            sizes = sizesAt(entries, exchange?.entryWidth(maxW) ?: maxW)
            val next = MapLayout.stripLimit(OverlayLayout.place(input.copy(strip = sizes)), d)
            if (next == limit) return Run(true, round, limit, sizes)
            limit = next
        }
        return Run(false, maxRounds, limit, sizes)
    }

    @Test
    fun theFreezesSceneWentRoundWithoutEnd() {
        val r = drive(freeze, three2, exchange = null)
        assertTrue(!r.settled, "the code before T371 settles here, so the test no longer holds the freeze")
    }

    @Test
    fun theFreezesSceneEndsOnTheSmallerAnswer() {
        val x = StripExchange()
        val r = drive(freeze, three2, x)
        assertTrue(r.settled, "not settled in ${r.rounds} rounds")
        assertTrue(r.rounds <= 8, "${r.rounds} rounds")
        assertEquals(1, x.caps)
        // The smaller of the two answers it went between: the strip beside the chips with two entries, or hidden.
        val beside = MapLayout.stripLimit(OverlayLayout.place(freeze.copy(strip = sizesAt(three2, windowPx - padPx))), d)
        assertNotNull(beside)
        assertTrue(r.limit == null || (r.limit.entries ?: 0) < (beside.entries ?: 0), "ended on ${r.limit}, not below $beside")
        // The next message starts afresh: the cap is gone.
        x.reset()
        assertNull(x.capPx)
        assertEquals(windowPx - padPx, x.entryWidth(windowPx - padPx))
    }

    /**
     * Scenes around the freeze's, at font scale 1.0 and 2.0 with three and seven entries: the track bar's panel narrow to
     * wide or none, the route card at many heights or none, one to three position buttons, centred or not. Every one
     * settles in a bounded number of rounds with the exchange, and where it settled without, on the same limit and sizes.
     */
    @Test
    fun everySceneSettlesAndOneThatSettledBeforeIsUnchanged() {
        var scenes = 0
        var wentRound = 0
        for ((scale, sets) in listOf(2f to listOf(three2, seven2), 1f to listOf(three1, seven1))) {
            val k = if (scale == 2f) 1f else 0.6f // the chips and cards a little smaller at 1.0
            for (entries in sets) for (bar in listOf<Float?>(null, 120f, 200f, 260f, 339.0476f)) for (cardTop in listOf<Float?>(null) + (200..800 step 25).map { it.toFloat() })
                for (buttons in 1..3) for (offCentre in listOf(false, true)) {
                    val input = freeze.copy(
                        fontScale = scale, buttons = buttons, offCentre = offCentre,
                        trackBar = bar?.let { OverlaySize(it, p(242) * k) }, trackPanel = if (bar == null) 0f else p(185) * k,
                        stripCore = 2, scaleBar = OverlaySize(p(383) * (if (scale == 2f) 1f else 0.8f), p(110) * k),
                        planning = cardTop?.let { t -> freeze.planning!!.copy(drawn = OverlayRect(8f, t, 403.42856f, minOf(t + 402.6667f * k, 842f))) },
                    )
                    scenes++
                    val before = drive(input, entries, exchange = null)
                    val after = drive(input, entries, StripExchange())
                    assertTrue(after.settled && after.rounds <= 12, "scale $scale, ${entries.size} entries, bar $bar, card at $cardTop, $buttons buttons: ${after.rounds} rounds")
                    if (before.settled) {
                        assertEquals(before.limit, after.limit, "scale $scale, ${entries.size} entries, bar $bar, card at $cardTop: the limit changed")
                        assertEquals(before.sizes, after.sizes, "scale $scale, ${entries.size} entries, bar $bar, card at $cardTop: the sizes changed")
                    } else wentRound++
                }
        }
        println("T371: $scenes scenes, $wentRound went round without the exchange")
        assertTrue(scenes > 1000)
        assertTrue(wentRound > 0, "no scene went round without the exchange: the scenes no longer reach the freeze")
    }

    @Test
    fun anExchangeThatSettlesIsNeverCapped() {
        val x = StripExchange()
        // The width asked again and again (a settled strip measured again in the same message) is no return.
        repeat(20) { assertEquals(850, x.entryWidth(850)) }
        // One step to a new width and back is a single return: still the rule's width.
        assertEquals(204, x.entryWidth(204))
        assertEquals(850, x.entryWidth(850))
        assertNull(x.capPx)
        // The second return caps at the smallest width asked, and the cap only narrows from then on in the message.
        assertEquals(204, x.entryWidth(204))
        assertEquals(204, x.capPx)
        assertEquals(204, x.entryWidth(1048))
        assertEquals(102, x.entryWidth(102))
        assertEquals(102, x.entryWidth(850))
        assertEquals(1, x.caps)
    }

    @Test
    fun manyDifferentWidthsAreCappedToo() {
        val x = StripExchange()
        for (w in 900 downTo 900 - 7 * 10 step 10) assertEquals(w, x.entryWidth(w))
        assertEquals(830, x.entryWidth(1000), "the ninth width asked caps at the smallest")
        assertEquals(830, x.capPx)
    }

    @Test
    fun theClearingIsScheduledOncePerMessage() {
        var scheduled = 0
        val x = StripExchange { scheduled++ }
        x.entryWidth(850)
        x.entryWidth(204)
        assertEquals(1, scheduled)
        x.reset()
        x.entryWidth(850)
        assertEquals(2, scheduled)
    }
}
