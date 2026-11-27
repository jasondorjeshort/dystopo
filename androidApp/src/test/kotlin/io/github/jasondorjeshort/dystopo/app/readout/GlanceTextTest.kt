package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.readout.BarometerState
import io.github.jasondorjeshort.dystopo.readout.BarometerStatus
import io.github.jasondorjeshort.dystopo.readout.Readout
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutHeights
import io.github.jasondorjeshort.dystopo.readout.ReadoutOptions
import io.github.jasondorjeshort.dystopo.readout.ReadoutFault
import io.github.jasondorjeshort.dystopo.readout.ReadoutReason
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
        val b = GlanceText.lines(StripText.items(walk.copy(elevation = v(ReadoutEntry.ELEVATION, 4392.0), climb = v(ReadoutEntry.CLIMB, -0.4)), all, ContourUnits.METERS))
        fun shape(l: List<GlanceLine>) = l.map { it.copy(caption = "", value = "", unit = "", trend = null, plusMinus = null, other = null, dimmed = false, colour = "", reason = it.reason?.let { "" }, reasonCode = null) }
        assertEquals(shape(a), shape(b), "the view fits on this shape, which the figures do not change")
        // T363: a state that is not a plain figure adds its reason's row (the type refits when a state comes or goes).
        val c = GlanceText.lines(StripText.items(walk.copy(climb = v(ReadoutEntry.CLIMB, null, ValueState.DASH)), all, ContourUnits.METERS))
        assertTrue(shape(a) != shape(c) && c[1].reason != null)
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

    // T363: a line under each value that is not a plain figure, and the notes: what went wrong in the last minute, what
    // the bar leaves out.
    @Test
    fun aReasonUnderTheValueAndTheLastMinute() {
        val gap = walk.copy(elevation = ReadoutValue(ReadoutEntry.ELEVATION, ValueState.SHOWN, 1650.2, null, "gnss", dimmed = true, reason = ReadoutReason.NO_FIX),
            gapS = 8.0, dimReason = ReadoutReason.NO_FIX)
        val lines = GlanceText.lines(StripText.items(gap, bar, ContourUnits.METERS))
        assertEquals("No GPS fix: the last height, until a fix comes.", lines[0].reason)
        assertEquals("no fix", lines[0].reasonCode)
        assertNull(lines[1].reason, "the climb's figure is plain")
        assertTrue(lines[0].spoken.endsWith(". No GPS fix: the last height, until a fix comes."), lines[0].spoken)
        // The fit reserves every line's reason row always: a reason coming or going never changes the type size or the layout.
        val m = GlanceFit.Measure { text, px, _ -> text.length * px * 0.55f to px * 1.2f }
        val with = GlanceFit.fit(lines, 1000f, 600f, 10f, 900f, m)
        val without = GlanceFit.fit(lines.map { it.copy(reason = null) }, 1000f, 600f, 10f, 900f, m)
        assertEquals(with.valuePx, without.valuePx, "the reason row is reserved with or without a reason")
        // The last minute: a 9 s gap that ended 40 s ago, a jump 20 s ago, a gap still going (left out), one 70 s ago (gone).
        val now = 1_000_000L
        val record = listOf(
            ReadoutFault(ReadoutFault.GAP, now - 70_000 - 9_000, lengthMs = 9_000),
            ReadoutFault(ReadoutFault.GAP, now - 40_000 - 9_000, lengthMs = 9_000),
            ReadoutFault(ReadoutFault.RESTART, now - 20_000, cause = ReadoutFault.CAUSE_JUMP, detail = 3002.0),
            ReadoutFault(ReadoutFault.GAP, now - 6_000, lengthMs = 6_000, ongoing = true),
        )
        val r = walk.copy(recent = record, elapsedMs = now)
        val notes = GlanceText.notes(r, null, listOf("fixAge", "accuracy"), false, ContourUnits.METERS)
        assertEquals(listOf("Position jumped 3.00 km, 20 s ago", "GPS lost for 9 s, 40 s ago"), notes.recent)
        assertEquals("Left off the bar for room: fix age, accuracy.", notes.leftOut)
        val pin = GlanceText.notes(r.copy(recent = emptyList()), now - 12_000 to "barometer", emptyList(), false, ContourUnits.FEET)
        assertEquals(listOf("Set height no longer used, 12 s ago: barometer not in use"), pin.recent)
        assertTrue(GlanceText.notes(r.copy(recent = emptyList()), null, emptyList(), false, ContourUnits.METERS).empty, "nothing went wrong: no notes")
        // T384: on its side the notes keep fewer recent rows (they give way first to the position line).
        assertEquals(1, GlanceText.notes(r, null, emptyList(), false, ContourUnits.METERS, max = 1).recent.size)
    }

    // ---- T384: the GPS while it searches, and the position (Paradise at Mount Rainier, public) ----

    private val paradise = 46.786 to -121.735
    private val withGps = all.copy(accuracy = io.github.jasondorjeshort.dystopo.readout.EntryOptions(shown = true), fixAge = io.github.jasondorjeshort.dystopo.readout.EntryOptions(shown = true))
    private val searching = GlanceText.Gps(searching = true, sinceMs = 260_000, seen = 11, used = 0, bestCn0 = 27.6)

    /** Before any GPS fix: every value "no fix" (no readout yet). */
    private fun noFix(o: ReadoutOptions) = StripText.items(null, o, ContourUnits.METERS)

    @Test
    fun searchingWithoutANetworkPosition() {
        val lines = GlanceText.lines(noFix(withGps), gps = searching, position = null, positionLine = true)
        assertEquals(listOf("elevation", "climb", "speed", "course", "accuracy", "fixAge", "position"), lines.map { it.kind })
        val by = lines.associateBy { it.kind }
        // The GPS area: the accuracy's and the fix age's reason rows say how the search goes.
        assertEquals("GPS searching for 4 min 20 s: 11 seen, none used.", by.getValue("accuracy").reason)
        assertEquals("Best signal 28 dB-Hz: weak (good is 35 or more).", by.getValue("fixAge").reason)
        // Every other "no fix" reads "searching", with its own sentence.
        assertEquals("GPS is searching: shown once it has a fix.", by.getValue("speed").reason)
        assertEquals("searching", by.getValue("speed").reasonCode)
        // No position yet: a dash, and why.
        assertEquals("–", by.getValue("position").value)
        assertEquals("No position yet: GPS is searching.", by.getValue("position").reason)
        assertTrue(by.getValue("position").wide)
        // Not searching (location off, or no device fixes started): "no fix" as before.
        val idle = GlanceText.lines(noFix(withGps), gps = searching.copy(searching = false), positionLine = true).associateBy { it.kind }
        assertEquals("no fix", idle.getValue("speed").reasonCode)
        assertEquals("No position yet: shown with the first fix.", idle.getValue("position").reason)
        // A network fix after the gap dims the values "resuming": while the GPS searches that is not GPS coming back.
        val resuming = walk.copy(speed = ReadoutValue(ReadoutEntry.SPEED, ValueState.SHOWN, 1.3, null, "fix", dimmed = true, reason = ReadoutReason.RESUMING), dimReason = ReadoutReason.RESUMING)
        val speed = GlanceText.lines(StripText.items(resuming, withGps, ContourUnits.METERS), gps = searching).first { it.kind == "speed" }
        assertEquals("searching", speed.reasonCode)
        assertEquals(io.github.jasondorjeshort.dystopo.app.help.ReasonText.SEARCHING_FIGURE, speed.reason)
        assertEquals("resuming", GlanceText.lines(StripText.items(resuming, withGps, ContourUnits.METERS)).first { it.kind == "speed" }.reasonCode)
        // Without a gps reading (the tests before T384), the lines are as they were.
        assertEquals(GlanceText.lines(noFix(withGps)).map { it.reason }, GlanceText.lines(noFix(withGps), gps = searching.copy(searching = false)).map { it.reason })
    }

    @Test
    fun searchingWithANetworkPositionMarkedNotGps() {
        val net = GlanceText.Position(paradise.first, paradise.second, "network", 12_400, 38.0)
        val lines = GlanceText.lines(noFix(withGps), gps = searching.copy(seen = 4, used = 0, bestCn0 = 21.0, sinceMs = 20_000), position = net, positionLine = true).associateBy { it.kind }
        assertEquals("GPS searching for 20 s: 4 seen, none used.", lines.getValue("accuracy").reason)
        assertEquals("Best signal 21 dB-Hz: weak (good is 35 or more).", lines.getValue("fixAge").reason)
        assertEquals("46.78600° N, 121.73500° W", lines.getValue("position").value)
        assertEquals("Not GPS: network position ± 38 m, 12 s ago.", lines.getValue("position").reason)
        val ft = GlanceText.position(net, searching, ContourUnits.FEET)
        assertEquals("Not GPS: network position ± 125 ft, 12 s ago.", ft.reason)
        assertEquals("network", net.source)
        assertEquals("network", GlanceText.positionJson(net, ft)["source"])
        // With the accuracy and the fix age switched off (the bar's defaults on foot), the area is the first lines that read "searching".
        val bare = GlanceText.lines(noFix(bar), gps = searching, position = net, positionLine = true)
        assertEquals(listOf("elevation", "climb", "position"), bare.map { it.kind })
        assertEquals("GPS searching for 4 min 20 s: 11 seen, none used.", bare[0].reason)
        assertEquals("Best signal 28 dB-Hz: weak (good is 35 or more).", bare[1].reason)
        // No satellite heard yet; some used; a strong one; no time known.
        assertEquals("GPS searching for 3 s: no satellites heard yet.", GlanceText.searchingSentence(searching.copy(seen = 0, sinceMs = 3_000)))
        assertEquals("Best signal: none heard yet.", GlanceText.signalSentence(searching.copy(seen = 0)))
        assertEquals("GPS searching: 9 seen, 2 used.", GlanceText.searchingSentence(searching.copy(seen = 9, used = 2, sinceMs = null)))
        assertEquals("Best signal 41 dB-Hz: good (35 or more).", GlanceText.signalSentence(searching.copy(bestCn0 = 41.2)))
        assertEquals("1 h 05 min", GlanceText.duration(3_900_000))
        // Every sentence of the area is one line of the glance view upright at font 1.0 (ReasonText's bound).
        for (t in listOf(GlanceText.searchingSentence(searching.copy(sinceMs = 3_599_000, seen = 88, used = 88)), GlanceText.signalSentence(searching), "Not GPS: network position ± 1.50 km, 59 min ago."))
            assertTrue(t.length <= io.github.jasondorjeshort.dystopo.app.help.ReasonText.MAX_SENTENCE, "${t.length}: $t")
    }

    @Test
    fun thePositionFromGpsWithItsAge() {
        val fix = GlanceText.Position(paradise.first, paradise.second, "gps", 3_400, 4.0)
        val line = GlanceText.position(fix, searching.copy(searching = false), ContourUnits.METERS)
        assertEquals("46.78600° N, 121.73500° W", line.value)
        assertEquals("From GPS, 3 s ago.", line.reason)
        assertEquals("From GPS, 4 min ago.", GlanceText.position(fix.copy(ageMs = 240_000), null, ContourUnits.METERS).reason)
        // An injected fix stands in for a GPS one.
        assertEquals("gps", fix.copy(provider = "debug").source)
        val j = GlanceText.positionJson(fix, line)
        assertEquals("gps", j["source"])
        assertEquals(3L, j["ageS"])
        assertEquals("46.78600° N, 121.73500° W", j["text"])
        // The template is the format's widest text, so the line's size holds as the figures change.
        assertEquals("88.88888° S, 888.88888° W", GlanceText.positionTemplate(io.github.jasondorjeshort.dystopo.geo.CoordinateFormat.DECIMAL))
        assertTrue(line.value.length <= line.valueTemplate.length)
        assertEquals("position", line.spoken.substringBefore(' '), "a screen reader names it though no caption is drawn")
    }

    @Test
    fun thePositionLineGoesAcrossUnderTheFiguresAndHoldsStill() {
        val pos = GlanceText.position(null, null, ContourUnits.METERS)
        val two = GlanceText.lines(StripText.items(walk, bar, ContourUnits.METERS))
        // Wider than tall: the position does not make the two figures three columns' worth: still one column.
        assertEquals(1, GlanceFit.fit(two + pos, 2000f, 1000f, 10f, 2000f, fake).columns)
        // Its value is its share of the figures' size, held to the width, never under the smallest size.
        val f = GlanceFit.fit(two + pos, 1000f, 2000f, 10f, 2000f, fake)
        assertTrue(f.fits)
        val px = GlanceFit.wideValuePx(pos, f.valuePx, 1000f, fake, 10f)
        assertTrue(px <= f.valuePx * GlanceText.POSITION_SHARE + 0.01f && pos.valueTemplate.length * px * 0.6f <= 1000.5f, "$px at ${f.valuePx}")
        assertEquals(31f, GlanceFit.wideValuePx(pos, 20f, 1000f, fake, 31f), "never under the smallest size")
        // A position coming, going or changing source never changes the fit (its rows are reserved).
        val net = GlanceText.position(GlanceText.Position(1.0, 2.0, "network", 0, 30.0), null, ContourUnits.METERS)
        fun shape(l: List<GlanceLine>) = l.map { it.copy(caption = "", value = "", unit = "", trend = null, plusMinus = null, other = null, dimmed = false, colour = "", reason = it.reason?.let { "" }, reasonCode = null) }
        assertEquals(shape(two + pos), shape(two + net))
        assertEquals(GlanceFit.fit(two + pos, 1000f, 2000f, 10f, 2000f, fake), GlanceFit.fit(two + net, 1000f, 2000f, 10f, 2000f, fake))
        // The searching state changes no shape either: its sentences go in rows already reserved.
        val o = withGps
        assertEquals(shape(GlanceText.lines(noFix(o), gps = searching.copy(searching = false), positionLine = true)), shape(GlanceText.lines(noFix(o), gps = searching, positionLine = true)))
    }
}
