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
import kotlin.test.assertTrue

/** T161: the strip's words over T160's text: captions, order, the climb's arrow, the dash, the gap, units and knots. */
class StripTextTest {
    private fun v(e: ReadoutEntry, value: Double?, state: ValueState = ValueState.SHOWN, sigma: Double? = null, dimmed: Boolean = false) =
        ReadoutValue(e, state, value, sigma, "fix", dimmed)

    private fun readout(
        a: Activity, speed: ReadoutValue, climb: ReadoutValue, course: ReadoutValue, elevation: ReadoutValue, gapS: Double? = null,
    ) = Readout(0L, a, speed, climb, course, elevation, gapS, ReadoutHeights(null, null, null), BarometerStatus(BarometerState.OFF), null, null, emptyMap())

    private val walk = readout(
        Activity.FOOT,
        v(ReadoutEntry.SPEED, 1.3), v(ReadoutEntry.CLIMB, -0.1), v(ReadoutEntry.COURSE, 245.4), v(ReadoutEntry.ELEVATION, 1650.2),
    )

    @Test
    fun aWalkReadsSpeedCourseElevationThenClimb() {
        val items = StripText.items(walk, ReadoutOptions.defaults(Activity.FOOT), ContourUnits.METERS)
        assertEquals(listOf("speed", "course", "elevation", "climb"), items.map { it.caption })
        assertEquals(listOf("5 km/h", "245°", "1650 m", "↓360 m/h"), items.map { it.full })
        val ft = StripText.items(walk, ReadoutOptions.defaults(Activity.FOOT), ContourUnits.FEET)
        assertEquals(listOf("3 mph", "245°", "5414 ft", "↓1180 ft/h"), ft.map { it.full })
    }

    @Test
    fun stillIsADashAndNearZeroClimbIsLeftOut() {
        val still = walk.copy(
            speed = v(ReadoutEntry.SPEED, null, ValueState.DASH),
            climb = v(ReadoutEntry.CLIMB, 0.001, ValueState.HIDDEN),
        )
        val items = StripText.items(still, ReadoutOptions.defaults(Activity.FOOT), ContourUnits.METERS)
        assertEquals(listOf("– km/h", "245°", "1650 m"), items.map { it.full })
    }

    @Test
    fun aGapComesFirstAndTheValuesDim() {
        val gap = walk.copy(speed = v(ReadoutEntry.SPEED, 1.3, dimmed = true), gapS = 75.0)
        val items = StripText.items(gap, ReadoutOptions.defaults(Activity.FOOT), ContourUnits.METERS)
        assertEquals("no fix", items.first().caption)
        assertEquals("1 min", items.first().value)
        assertTrue(items[1].dimmed)
        assertEquals(listOf("8 s", "1 min", "2 h", null), listOf(8.4, 60.0, 7300.0, null).map { StripText.gap(it) })
    }

    @Test
    fun knotsAloftPaceOnFootAndThePlusMinus() {
        val flight = readout(
            Activity.AIR,
            v(ReadoutEntry.SPEED, 51.4), v(ReadoutEntry.CLIMB, 2.54), v(ReadoutEntry.COURSE, 90.0), v(ReadoutEntry.ELEVATION, 1500.0, sigma = 12.0),
        )
        val air = ReadoutOptions.defaults(Activity.AIR)
        assertEquals(listOf("185 km/h", "90°", "1500 m", "↑152 m/min"), StripText.items(flight, air, ContourUnits.METERS).map { it.full })
        val knots = air.with(ReadoutEntry.SPEED, air.speed.copy(knots = true)).with(ReadoutEntry.ELEVATION, air.elevation.copy(plusMinus = true))
        assertEquals(listOf("100 kn", "90°", "4921 ft ± 39", "↑500 ft/min"), StripText.items(flight, knots, ContourUnits.FEET).map { it.full })
        val foot = ReadoutOptions.defaults(Activity.FOOT)
        val pace = foot.with(ReadoutEntry.SPEED, foot.speed.copy(pace = true))
        val p = StripText.items(walk, pace, ContourUnits.METERS).first()
        assertEquals("pace", p.caption)
        assertEquals("12:50 /km", p.full)
    }

    @Test
    fun everyEntryOffDrawsNothing() {
        val off = ReadoutOptions.defaults(Activity.FOOT).let { o -> ReadoutEntry.entries.fold(o) { acc, e -> acc.with(e, acc[e].copy(shown = false)) } }
        fun h(e: ReadoutEntry) = v(e, null, ValueState.HIDDEN)
        val hidden = readout(Activity.FOOT, h(ReadoutEntry.SPEED), h(ReadoutEntry.CLIMB), h(ReadoutEntry.COURSE), h(ReadoutEntry.ELEVATION), gapS = 30.0)
        assertTrue(StripText.items(hidden, off, ContourUnits.METERS).isEmpty(), "no gap item alone")
    }
}
