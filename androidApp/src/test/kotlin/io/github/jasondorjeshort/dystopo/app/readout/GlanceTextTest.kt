package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.readout.BarometerState
import io.github.jasondorjeshort.dystopo.readout.BarometerStatus
import io.github.jasondorjeshort.dystopo.readout.Readout
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutHeights
import io.github.jasondorjeshort.dystopo.readout.ReadoutOptions
import io.github.jasondorjeshort.dystopo.readout.ReadoutValue
import io.github.jasondorjeshort.dystopo.readout.ValueState
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T210: the glance view's lines (the bar's entries in their fixed order, the deadline first while the clock runs) and the
 * largest type that fits; T204's follow-up, the bar's description of the entries drawn only.
 */
class GlanceTextTest {
    private fun v(e: ReadoutEntry, value: Double?, state: ValueState = ValueState.SHOWN) = ReadoutValue(e, state, value, null, "fix", false)

    private val walk = Readout(
        0L, Activity.FOOT,
        v(ReadoutEntry.SPEED, 1.3), v(ReadoutEntry.CLIMB, 0.09), v(ReadoutEntry.COURSE, 245.4), v(ReadoutEntry.ELEVATION, 1650.2),
        null, ReadoutHeights(null, null, null), BarometerStatus(BarometerState.OFF), null, null, emptyMap(),
    )
    private val all = ReadoutOptions.defaults(Activity.FOOT)
    private val bar = ReadoutOptions.phoneDefaults(Activity.FOOT)
    private fun clock(ms: Long) = "%d:%02d".format(ms / 3_600_000 % 24, ms / 60_000 % 60)

    @Test
    fun theLinesAreTheBarsEntriesInTheirOrder() {
        val every = GlanceText.lines(StripText.items(walk, all, ContourUnits.METERS))
        assertEquals(listOf("elevation", "climb", "speed", "course"), every.map { it.kind })
        assertEquals(listOf("1650 m", "↑320 m/h", "4.5 km/h", "WSW 245°"), every.map { it.full })
        // On foot the bar's defaults are elevation and climb: the glance shows the same two, no list of its own.
        assertEquals(listOf("elevation", "climb"), GlanceText.lines(StripText.items(walk, bar, ContourUnits.METERS)).map { it.kind })
        // An entry switched off is gone from both.
        val noSpeed = all.with(ReadoutEntry.SPEED, all.speed.copy(shown = false))
        assertEquals(listOf("elevation", "climb", "course"), GlanceText.lines(StripText.items(walk, noSpeed, ContourUnits.METERS)).map { it.kind })
        // The templates are the bar's, so the fit holds while the figures change.
        val e = every[0]
        assertEquals("8888", e.valueTemplate)
        assertEquals("m", e.unitTemplate)
    }

    @Test
    fun theDeadlineComesFirstWithTheTimeLeft() {
        val h = 3_600_000L
        val now = 12 * h
        val ahead = GlanceText.deadline("ahead", "normal", now + h + 20 * 60_000, now + 5 * h, now, ::clock)
        assertEquals("turn back by 13:20", ahead.caption)
        assertEquals("1 h 20 min", ahead.full)
        val margin = GlanceText.deadline("margin", "amber", now + 25 * 60_000 + 59_000, now + 5 * h, now, ::clock)
        assertEquals("25 min", margin.full)
        assertEquals("amber", margin.colour)
        val turn = GlanceText.deadline("turn", "red", now - 60_000, now + 6 * h + 52 * 60_000, now, ::clock)
        assertEquals("turn back now, out by", turn.caption)
        assertEquals("18:52", turn.full)
        val back = GlanceText.deadline("returning", "normal", null, now + 6 * h + 5 * 60_000, now, ::clock)
        assertEquals("out by 18:05", back.spoken)
        val lines = GlanceText.lines(StripText.items(walk, bar, ContourUnits.METERS), ahead)
        assertEquals(listOf(GlanceLine.DEADLINE, "elevation", "climb"), lines.map { it.kind })
        assertEquals("turn back by 13:20 1 h 20 min · elevation 1650 m · climb ↑320 m/h", GlanceText.description(lines))
        // Every deadline text fits its templates (the widest caption, "88 h 88" with "min").
        for (l in listOf(ahead, margin, turn, back)) {
            assertTrue(l.caption.length <= l.captionTemplate.length, l.caption)
            assertTrue(l.value.length <= l.valueTemplate.length, l.value)
        }
    }

    @Test
    fun timeLeft() {
        assertEquals("0" to "min", GlanceText.left(-5_000))
        assertEquals("59" to "min", GlanceText.left(59 * 60_000 + 59_000))
        assertEquals("1 h 00" to "min", GlanceText.left(60 * 60_000))
        assertEquals("10 h 05" to "min", GlanceText.left(605 * 60_000))
    }

    /** A fake font: every character 0.6 of the size wide, the line 1.2 of it high. */
    private val fake = GlanceFit.Measure { text, px, _ -> text.length * px * 0.6f to px * 1.2f }

    @Test
    fun theLargestTypeThatFits() {
        val two = GlanceText.lines(StripText.items(walk, bar, ContourUnits.METERS))
        val f = GlanceFit.fit(two, 1000f, 2000f, 10f, 2000f, fake)
        assertTrue(f.fits)
        assertEquals(1, f.columns)
        assertTrue(GlanceFit.fitsAt(two, 1000f, 2000f, f.valuePx, 1, fake))
        assertFalse(GlanceFit.fitsAt(two, 1000f, 2000f, f.valuePx * 1.01f, 1, fake), "the largest: one percent more does not fit")
        // A phone held upright: four lines and the deadline are bound by the height, and the type is smaller than for two.
        val five = GlanceText.lines(StripText.items(walk, all, ContourUnits.FEET), GlanceText.deadline("ahead", "normal", 2 * 3_600_000L, 0, 0, ::clock))
        val g = GlanceFit.fit(five, 1000f, 2000f, 10f, 2000f, fake)
        assertTrue(g.fits && g.valuePx < f.valuePx)
        // Wider than tall with three lines or more: two columns.
        assertEquals(2, GlanceFit.fit(five, 2000f, 1000f, 10f, 2000f, fake).columns)
        assertEquals(1, GlanceFit.fit(two, 2000f, 1000f, 10f, 2000f, fake).columns)
        // No room at all: the smallest size, reported as not fitting.
        val none = GlanceFit.fit(five, 50f, 50f, 10f, 2000f, fake)
        assertFalse(none.fits)
        assertEquals(10f, none.valuePx)
    }

    @Test
    fun theFitHoldsWhileTheFiguresChange() {
        val a = GlanceText.lines(StripText.items(walk, all, ContourUnits.METERS))
        val b = GlanceText.lines(StripText.items(walk.copy(elevation = v(ReadoutEntry.ELEVATION, 4392.0), climb = v(ReadoutEntry.CLIMB, null, ValueState.DASH)), all, ContourUnits.METERS))
        fun shape(l: List<GlanceLine>) = l.map { it.copy(caption = "", value = "", unit = "", trend = null, plusMinus = null, other = null, dimmed = false, colour = "") }
        assertEquals(shape(a), shape(b), "the view fits on this shape, which the figures do not change")
        assertEquals(GlanceFit.fit(a, 1000f, 2000f, 10f, 2000f, fake), GlanceFit.fit(b, 1000f, 2000f, 10f, 2000f, fake))
    }

    @Test
    fun theBarsDescriptionReadsTheEntriesDrawn() { // T204's follow-up
        val items = StripText.items(walk, all, ContourUnits.METERS)
        assertEquals("elevation 1650 m · climb ↑320 m/h · speed 4.5 km/h · course WSW 245°", StripText.line(items))
        assertEquals("elevation 1650 m", StripText.line(items.take(1)))
        assertEquals("elevation 1650 m · Set at Camp Muir", StripText.line(items.take(1), "Set at Camp Muir"))
        assertNull(StripText.line(emptyList()))
    }

    /** T218: the deadline line reserves the clock's longest form, in both its value and its caption. */
    @Test
    fun theDeadlineLineHoldsTheLongestClock() {
        val s = io.github.jasondorjeshort.dystopo.nav.ClockText.SPACE
        val longest = io.github.jasondorjeshort.dystopo.nav.ClockText.LONGEST
        val m = GlanceFit.Measure { text, px, _ -> text.length * px * 0.6f to px * 1.2f }
        val line = GlanceText.deadline("turn", "red", 0L, 0L, 0L) { longest }
        assertEquals(longest, line.value)
        assertEquals(longest, line.valueAlso)
        val v = 100f
        assertTrue(GlanceFit.lineSize(line, v, m).first >= longest.length * v * 0.6f)
        val ahead = GlanceText.deadline("ahead", "normal", 3_600_000L, 0L, 0L) { longest }
        assertEquals("turn back by 12:59${s}PM", ahead.caption)
        assertEquals(ahead.caption, ahead.captionAlso)
        // Fitted to a narrow column, what it can draw fits, in each state.
        val fit = GlanceFit.fit(listOf(line, ahead), 320f, 900f, 12f, 320f, m)
        assertTrue(fit.fits)
        for (l in listOf(line, ahead)) {
            assertTrue(l.value.length * fit.valuePx * 0.6f <= 320f, l.value)
            assertTrue(l.caption.length * fit.captionPx * 0.6f <= 320f, l.caption)
        }
    }
}
