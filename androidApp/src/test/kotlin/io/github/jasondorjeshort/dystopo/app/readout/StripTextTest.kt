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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T161: the bar's words over T160's text: captions, order, the climb's arrow, the dash, the gap, units and knots.
 * T177: every shown entry always drawn (value, zero or dash), elevation and climb first, the widths fixed by templates,
 * the gap in the first caption, both-units mode.
 */
class StripTextTest {
    private fun v(e: ReadoutEntry, value: Double?, state: ValueState = ValueState.SHOWN, sigma: Double? = null, dimmed: Boolean = false, reason: String? = null) =
        ReadoutValue(e, state, value, sigma, "fix", dimmed, reason = reason)

    private fun readout(
        a: Activity, speed: ReadoutValue, climb: ReadoutValue, course: ReadoutValue, elevation: ReadoutValue, gapS: Double? = null,
    ) = Readout(0L, a, speed, climb, course, elevation, gapS, ReadoutHeights(null, null, null), BarometerStatus(BarometerState.OFF), null, null, emptyMap())

    private val walk = readout(
        Activity.FOOT,
        v(ReadoutEntry.SPEED, 1.3), v(ReadoutEntry.CLIMB, -0.1), v(ReadoutEntry.COURSE, 245.4), v(ReadoutEntry.ELEVATION, 1650.2),
    )

    private val all = ReadoutOptions.defaults(Activity.FOOT)
    private val bar = ReadoutOptions.phoneDefaults(Activity.FOOT)

    @Test
    fun theBarOnFootIsElevationThenClimb() {
        val items = StripText.items(walk, bar, ContourUnits.METERS)
        assertEquals(listOf("elevation", "climb"), items.map { it.caption })
        assertEquals(listOf("1650 m", "↓360 m/h"), items.map { it.full })
        assertEquals(listOf("value", "value"), items.map { it.state })
        val every = StripText.items(walk, all, ContourUnits.FEET)
        assertEquals(listOf("elevation", "climb", "speed", "course"), every.map { it.caption }, "the other entries after the two")
        assertEquals(listOf("5414 ft", "↓1180 ft/h", "3.0 mph", "WSW 245°"), every.map { it.full }) // T257: the point and the degrees
    }

    @Test
    fun theClimbsThreeStatesAndADashBeforeAnything() {
        // T250: standing still the climb reads 0 when the estimate can tell (the speed a dash), "?" when it cannot, and a
        // dash only while the sources contradict.
        val still = walk.copy(speed = v(ReadoutEntry.SPEED, null, ValueState.DASH), climb = v(ReadoutEntry.CLIMB, 0.001, ValueState.ZERO, reason = "still"))
        val s = StripText.items(still, all, ContourUnits.METERS)
        assertEquals(listOf("1650 m", "0 m/h", "– km/h", "WSW 245°"), s.map { it.full })
        assertEquals(listOf("value", "zero", "dash", "value"), s.map { it.state })
        val unsure = StripText.items(walk.copy(climb = v(ReadoutEntry.CLIMB, 0.01, ValueState.UNKNOWN, reason = "uncertain")), bar, ContourUnits.METERS)
        assertEquals(listOf("1650 m", "? m/h"), unsure.map { it.full })
        assertEquals("unknown", unsure[1].state)
        val contra = StripText.items(walk.copy(climb = v(ReadoutEntry.CLIMB, null, ValueState.DASH, reason = "contradiction")), bar, ContourUnits.FEET)
        assertEquals(listOf("5414 ft", "– ft/h"), contra.map { it.full })
        assertEquals("dash", contra[1].state)
        val flat = walk.copy(climb = v(ReadoutEntry.CLIMB, -0.004, ValueState.ZERO, reason = "near zero"))
        val f = StripText.items(flat, bar, ContourUnits.METERS)
        assertEquals(listOf("1650 m", "0 m/h"), f.map { it.full })
        assertEquals("zero", f[1].state)
        // Before a fix (no readout yet, or the estimator's "no fix" and "no height yet"): every shown entry a dash, none
        // left out; T250: the climb "?" (too little data).
        val none = StripText.items(null, bar, ContourUnits.METERS)
        assertEquals(listOf("– m", "? m/h"), none.map { it.full })
        fun h(e: ReadoutEntry, why: String) = v(e, null, ValueState.HIDDEN, reason = why)
        val noFix = readout(Activity.FOOT, h(ReadoutEntry.SPEED, "no fix"), h(ReadoutEntry.CLIMB, "no height yet"), h(ReadoutEntry.COURSE, "no fix"), h(ReadoutEntry.ELEVATION, "no height yet"))
        assertEquals(listOf("dash", "unknown", "dash", "dash"), StripText.items(noFix, all, ContourUnits.METERS).map { it.state })
        assertEquals(listOf("– m", "? m/h", "– km/h", "–°"), StripText.items(noFix, all, ContourUnits.METERS).map { it.full }) // T204: no stray space in the course's dash
    }

    @Test
    fun theWidthIsFixedByTemplatesWhateverTheValue() {
        for (u in ContourUnits.entries) {
            val a = StripText.items(walk, all, u)
            val b = StripText.items(null, all, u)
            assertEquals(a.map { it.template }, b.map { it.template }, "$u: the templates do not depend on the values")
            assertEquals(a.map { it.captionTemplate }, b.map { it.captionTemplate })
            for (it in a) assertTrue(it.template.text.length >= it.value.length, "$u ${it.entry}: '${it.value}' wider than '${it.template.text}'")
        }
        assertEquals("28888", StripText.template(ReadoutEntry.ELEVATION, all, ContourUnits.FEET).text)
        assertEquals("↑8880", StripText.template(ReadoutEntry.CLIMB, all, ContourUnits.METERS).text)
        assertEquals(StripText.GAP_TEMPLATE, StripText.items(walk, bar, ContourUnits.METERS).first().captionTemplate)
    }

    @Test
    fun aGapIsTheFirstCaptionAndTheValuesDim() {
        val gap = walk.copy(elevation = v(ReadoutEntry.ELEVATION, 1650.2, dimmed = true), gapS = 75.0)
        val items = StripText.items(gap, bar, ContourUnits.METERS)
        assertEquals(2, items.size, "no item of its own")
        assertEquals("no fix 1 min", items.first().caption)
        assertTrue(items.first().dimmed)
        assertEquals(listOf("8 s", "1 min", "2 h", null), listOf(8.4, 60.0, 7300.0, null).map { StripText.gap(it) })
    }

    @Test
    fun bothUnitsPutsTheOtherSystemUnderEachValue() {
        val items = StripText.items(walk, all, ContourUnits.METERS, both = true)
        assertEquals(listOf("5414 ft", "↓1180 ft/h", "3.0 mph", null), items.map { it.other })
        assertEquals(listOf("28888 ft", "↑8880 ft/h", "88.5 mph", null), items.map { it.otherTemplate })
        assertNull(StripText.items(walk, all, ContourUnits.METERS).first().other, "one unit: no second line")
        val zero = walk.copy(climb = v(ReadoutEntry.CLIMB, 0.0, ValueState.ZERO))
        assertEquals("0 ft/h", StripText.items(zero, bar, ContourUnits.METERS, both = true)[1].other)
    }

    @Test
    fun knotsAloftPaceOnFootAndThePlusMinus() {
        val flight = readout(
            Activity.AIR,
            v(ReadoutEntry.SPEED, 51.4), v(ReadoutEntry.CLIMB, 2.54), v(ReadoutEntry.COURSE, 90.0), v(ReadoutEntry.ELEVATION, 1500.0, sigma = 12.0),
        )
        val air = ReadoutOptions.defaults(Activity.AIR)
        assertEquals(listOf("1500 m", "↑152 m/min", "185 km/h", "E 90°"), StripText.items(flight, air, ContourUnits.METERS).map { it.full })
        val knots = air.with(ReadoutEntry.SPEED, air.speed.copy(knots = true)).with(ReadoutEntry.ELEVATION, air.elevation.copy(plusMinus = true))
        assertEquals(listOf("4921 ft ± 39", "↑500 ft/min", "100 kn", "E 90°"), StripText.items(flight, knots, ContourUnits.FEET).map { it.full })
        val pace = all.with(ReadoutEntry.SPEED, all.speed.copy(pace = true))
        val p = StripText.items(walk, pace, ContourUnits.METERS)[2]
        assertEquals("pace", p.caption)
        assertEquals("12:50 /km", p.full)
    }

    @Test
    fun theTrendArrowSitsBesideTheElevationOnly() { // T178
        val items = StripText.items(walk, bar, ContourUnits.METERS, trend = "↘", trendLabel = "falling slowly", trendSlot = true)
        assertEquals("↘", items[0].trend)
        assertEquals("falling slowly", items[0].trendLabel)
        assertTrue(items[0].trendSlot)
        assertNull(items[1].trend)
        assertTrue(!items[1].trendSlot)
        // No slot (the barometer switch off): no arrow even when one is known; a slot with no arrow yet reserves the room.
        assertNull(StripText.items(walk, bar, ContourUnits.METERS, trend = "↘", trendSlot = false)[0].trend)
        val waiting = StripText.items(walk, bar, ContourUnits.METERS, trendSlot = true)[0]
        assertTrue(waiting.trendSlot)
        assertNull(waiting.trend)
    }

    @Test
    fun thePressureAndTheExtendedEntriesComeAfterTheCourse() { // T257
        val withP = walk.copy(
            pressure = v(ReadoutEntry.PRESSURE, 842.3), accuracy = v(ReadoutEntry.ACCURACY, 4.6), fixAge = v(ReadoutEntry.FIX_AGE, 3.0),
        )
        val ext = all.copy(accuracy = all.accuracy.copy(shown = true), fixAge = all.fixAge.copy(shown = true))
        val items = StripText.items(withP, ext, ContourUnits.METERS, trend = "↘", trendLabel = "falling slowly", trendSlot = true, pressureDrawn = true)
        assertEquals(listOf("elevation", "climb", "speed", "course", "pressure", "accuracy", "fix age"), items.map { it.caption })
        assertEquals(listOf("1650 m", "↓360 m/h", "4.5 km/h", "WSW 245°", "842 hPa", "5 m", "3 s"), items.map { it.full })
        // The trend's arrow goes with the pressure while it is drawn; the elevation keeps the known height's slot.
        assertEquals(listOf(null, null, null, null, "↘", null, null), items.map { it.trend })
        assertTrue(items[0].pinSlot && !items[0].trendSlot && items[4].trendSlot)
        assertEquals("WSW", items[3].compact)
        assertEquals("WNW", items[3].compactTemplate)
        assertEquals("elevation 1650 m · climb ↓360 m/h · speed 4.5 km/h · course WSW · pressure 842 hPa, pressure falling slowly",
            StripText.line(items.take(5), short = true))
        // No barometer: no pressure entry, the arrow back beside the elevation.
        val noBaro = StripText.items(withP, ext, ContourUnits.FEET, trend = "↘", trendSlot = true, pressureDrawn = false)
        assertEquals(listOf("5414 ft", "↓1180 ft/h", "3.0 mph", "WSW 245°", "15 ft", "3 s"), noBaro.map { it.full })
        assertEquals("↘", noBaro[0].trend)
        // Before a sample, a dash; inHg in feet units, hPa under it in both-units mode.
        val dash = StripText.items(walk, all, ContourUnits.FEET, both = true, pressureDrawn = true).last()
        assertEquals("– inHg", dash.full)
        assertEquals("dash", dash.state)
        assertEquals("1088 hPa", dash.otherTemplate)
    }

    @Test
    fun everyEntrysWidestTextFitsItsTemplate() { // T257: the template tests, every entry and option
        val values = mapOf(
            ReadoutEntry.ELEVATION to listOf(-430.0, 0.0, 1650.2, 8848.9),
            ReadoutEntry.CLIMB to listOf(-1e4, -9.0, -0.5, 0.0, 0.27, 2.77, 30.0, 1e4),
            ReadoutEntry.SPEED to listOf(0.0, 0.3, 2.7, 26.8, 120.0),
            ReadoutEntry.COURSE to (0..359 step 7).map { it.toDouble() },
            ReadoutEntry.PRESSURE to listOf(300.0, 724.7, 1013.25, 1099.9),
            ReadoutEntry.ACCURACY to listOf(0.5, 4.6, 65.0, 2500.0),
            ReadoutEntry.FIX_AGE to listOf(0.0, 59.0, 3599.0, 86_399.0, 1e9),
        )
        val on = io.github.jasondorjeshort.dystopo.readout.EntryOptions(shown = true)
        val widest = HashMap<ReadoutEntry, String>()
        for (a in Activity.entries) for (u in ContourUnits.entries) for (variant in 0 until 6) {
            var o = ReadoutOptions.defaults(a).copy(accuracy = on, fixAge = on)
            o = when (variant) {
                1 -> o.with(ReadoutEntry.SPEED, o.speed.copy(pace = true))
                2 -> o.with(ReadoutEntry.SPEED, o.speed.copy(knots = true))
                3 -> ReadoutEntry.entries.fold(o) { acc, e -> acc.with(e, acc[e].copy(plusMinus = true)) }
                4 -> o.with(ReadoutEntry.COURSE, o.course.copy(magnetic = true))
                5 -> o.with(ReadoutEntry.COURSE, o.course.copy(degrees = false, magnetic = true))
                else -> o
            }
            for ((e, vs) in values) {
                val oe = o.with(e, o[e].copy(shown = true))
                val t = StripText.template(e, oe, u)
                for (x in vs) for (decl in listOf(null, 15.2)) {
                    val l = io.github.jasondorjeshort.dystopo.readout.ReadoutText.of(ReadoutValue(e, ValueState.SHOWN, x, if (e == ReadoutEntry.CLIMB) 0.05 else 3.0), oe, u, decl)!!
                    val text = when {
                        e != ReadoutEntry.CLIMB || l.text == "0" -> l.text
                        l.text.startsWith("-") -> StripText.DOWN + l.text.removePrefix("-")
                        else -> StripText.UP + l.text
                    }
                    val where = "$a $u v$variant $e $x"
                    assertTrue(text.length <= t.text.length, "$where: '$text' wider than '${t.text}'")
                    assertTrue(l.unit.length <= t.unit.length, "$where: unit '${l.unit}' wider than '${t.unit}'")
                    assertTrue((l.plusMinus?.length ?: 0) <= (t.plusMinus?.length ?: 0), "$where: '${l.plusMinus}' against '${t.plusMinus}'")
                    l.compact?.let { c -> assertTrue(c.length <= StripText.courseTemplate(oe, degrees = false).length, "$where: compact '$c'") }
                    val full = (text + " " + l.unit).trim()
                    if (full.length > (widest[e]?.length ?: 0)) widest[e] = full
                }
            }
        }
        println("T257 widest texts: " + ReadoutEntry.entries.joinToString(" | ") { "${it.code} '${widest[it]}'" })
    }

    @Test
    fun everyEntryOffDrawsNothing() {
        val off = all.let { o -> ReadoutEntry.entries.fold(o) { acc, e -> acc.with(e, acc[e].copy(shown = false)) } }
        assertTrue(StripText.items(walk.copy(gapS = 30.0), off, ContourUnits.METERS).isEmpty(), "no gap item alone")
    }
}
