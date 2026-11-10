package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T257: the course as a compass point and degrees ("SSE 163°"), true or magnetic; the pressure, the GNSS accuracy and
 * the fix age as text and from the estimator; a climb past four digits in thousands; the stored entry options before
 * and after T257 (ADR 0033).
 */
class ReadoutCourseTest {
    private val foot = ReadoutOptions.defaults(Activity.FOOT)
    private fun course(deg: Double, o: ReadoutOptions = foot, decl: Double? = null) =
        ReadoutText.of(ReadoutValue(ReadoutEntry.COURSE, ValueState.SHOWN, deg, 4.0), o, ContourUnits.METERS, decl)!!

    @Test
    fun theSixteenPoints() {
        assertEquals(16, ReadoutText.POINTS.size)
        assertEquals(listOf("N", "N", "NNE", "NNE", "NE", "SSE", "S", "WNW", "NNW", "N"), listOf(0.0, 11.2, 11.3, 33.7, 33.8, 163.0, 180.0, 292.5, 348.7, 348.8).map(ReadoutText::point))
        assertEquals("N", ReadoutText.point(-5.0))
        assertEquals("N", ReadoutText.point(360.0))
        assertEquals("E", ReadoutText.point(450.0))
    }

    @Test
    fun theCourseReadsThePointAndTheDegrees() {
        assertEquals("SSE 163°", course(163.0).full)
        assertEquals("SSE", course(163.0).compact, "the bar's shorter form: the point alone")
        assertEquals("N 0°", course(359.7).full)
        assertEquals("N 11°", course(11.2).full, "the point is the rounded degrees'")
        val noDeg = foot.with(ReadoutEntry.COURSE, foot.course.copy(degrees = false))
        assertEquals("SSE", course(163.0, noDeg).full)
        assertNull(course(163.0, noDeg).compact, "nothing to drop")
        assertEquals("–", ReadoutText.of(ReadoutValue(ReadoutEntry.COURSE, ValueState.DASH), noDeg, ContourUnits.METERS)!!.full)
        assertEquals("–°", ReadoutText.of(ReadoutValue(ReadoutEntry.COURSE, ValueState.DASH), foot, ContourUnits.METERS)!!.full)
        val pm = foot.with(ReadoutEntry.COURSE, foot.course.copy(plusMinus = true))
        assertEquals("SSE 163° ± 4", course(163.0, pm).full)
        assertNull(ReadoutText.other(ReadoutValue(ReadoutEntry.COURSE, ValueState.SHOWN, 163.0), foot, ContourUnits.METERS))
    }

    @Test
    fun magneticIsMarkedAndNeverSilent() {
        val mag = foot.with(ReadoutEntry.COURSE, foot.course.copy(magnetic = true))
        // A declination of 15° east: magnetic = true - 15.
        assertEquals("SSE 148° M", course(163.0, mag, 15.0).full)
        assertEquals("SSE M", course(163.0, mag, 15.0).compact)
        // No declination (a pole's blackout, outside the model's years): true, marked so.
        assertEquals("SSE 163° T", course(163.0, mag, null).full)
        assertEquals("SSE 163°", course(163.0, foot, 15.0).full, "true north by default, the declination unused and no mark")
        assertEquals("N 355° M", course(5.0, mag, 10.0).full)
    }

    @Test
    fun theDeclinationIsTheWorldMagneticModels() {
        // Rainier (areas.json) in 2026: about 15° east.
        val t2026 = 1_782_000_000_000L
        val d = assertNotNull(ReadoutText.declination(46.85, -121.76, t2026))
        assertTrue(d in 13.5..16.5, "Rainier's declination $d")
        // Near the north dip pole the horizontal field is too weak: no declination.
        assertNull(ReadoutText.declination(85.9, 139.0, t2026))
        // Outside the model's years: none.
        assertNull(ReadoutText.declination(46.85, -121.76, 2_000_000_000_000L + 1_000_000_000_000L))
        assertNull(ReadoutText.declination(Double.NaN, 0.0, t2026))
    }

    @Test
    fun pressureAccuracyAndAgeInTheUsersUnits() {
        fun t(e: ReadoutEntry, v: Double, u: ContourUnits = ContourUnits.METERS) = ReadoutText.of(ReadoutValue(e, ValueState.SHOWN, v), foot, u)!!.full
        assertEquals("1013 hPa", t(ReadoutEntry.PRESSURE, 1013.25))
        assertEquals("29.92 inHg", t(ReadoutEntry.PRESSURE, 1013.25, ContourUnits.FEET))
        assertEquals("21.40 inHg", t(ReadoutEntry.PRESSURE, 724.7, ContourUnits.FEET))
        assertEquals("29.92 inHg", ReadoutText.other(ReadoutValue(ReadoutEntry.PRESSURE, ValueState.SHOWN, 1013.25), foot, ContourUnits.METERS)!!.full)
        assertEquals("– hPa", ReadoutText.of(ReadoutValue(ReadoutEntry.PRESSURE, ValueState.DASH), foot, ContourUnits.METERS)!!.full)
        assertEquals("5 m", t(ReadoutEntry.ACCURACY, 4.6))
        assertEquals("15 ft", t(ReadoutEntry.ACCURACY, 4.6, ContourUnits.FEET))
        assertEquals(listOf("0 s", "59 s", "1 min", "59 min", "1 h", "47 h", "2 d", "99 d"),
            listOf(0.4, 59.9, 60.0, 3599.0, 3600.0, 47.9 * 3600, 48.0 * 3600, 400.0 * 86400).map { t(ReadoutEntry.FIX_AGE, it) })
        assertNull(ReadoutText.other(ReadoutValue(ReadoutEntry.FIX_AGE, ValueState.SHOWN, 3.0), foot, ContourUnits.METERS))
    }

    @Test
    fun aClimbPastFourDigitsReadsInThousands() { // T238's note: a replayed jump's six-digit climb
        fun c(mps: Double, u: ContourUnits = ContourUnits.FEET) = ReadoutText.of(ReadoutValue(ReadoutEntry.CLIMB, ValueState.SHOWN, mps), foot, u)!!.full
        assertEquals("9990 ft/h", c(0.8458))
        assertEquals("10k ft/h", c(0.8467))
        assertEquals("-11k ft/h", c(-0.96))
        assertEquals("123k ft/h", c(10.45))
        assertEquals("999k ft/h", c(1000.0), "capped")
        for (v in listOf(0.0, 0.3, 2.7, 10.0, 100.0, 1e4, -1e4)) for (u in ContourUnits.entries) {
            val text = ReadoutText.of(ReadoutValue(ReadoutEntry.CLIMB, ValueState.SHOWN, v), foot, u)!!.text
            assertTrue(text.removePrefix("-").length <= 4, "$v $u: '$text' longer than the template's four places")
        }
    }

    @Test
    fun aValueStoredBeforeT257ReadsAsBefore() { // ADR 0033
        val old = "shown=1;pace=0;damping=medium;pm=0;knots=0"
        val d = EntryOptions(shown = true)
        val o = EntryOptions.decode(old, d)
        assertTrue(o.degrees && !o.magnetic, "an old course value: degrees on, true north")
        assertEquals(old, o.encode(), "byte for byte while the new options sit at their defaults")
        assertEquals("shown=1;pace=0;damping=medium;pm=0;knots=0;deg=0;mag=1", o.copy(degrees = false, magnetic = true).encode())
        // A newer build's key is kept through a decode and an encode.
        val newer = EntryOptions.decode("shown=1;pace=0;damping=low;pm=0;knots=0;mag=1;hue=red", d)
        assertEquals("hue=red", newer.extra)
        assertEquals("shown=1;pace=0;damping=low;pm=0;knots=0;mag=1;hue=red", newer.encode())
        assertEquals("hue=blue", EntryOptions.decode("hue=blue", newer).extra, "a key given again takes its new value")
        assertEquals("hue=red", EntryOptions.decode("pm=1", newer).extra, "an edit keeps the base's unknown keys")
    }

    @Test
    fun theEstimatorReportsAccuracyAgeAndPressure() {
        val all = ReadoutOptions.defaults(Activity.FOOT).let { it.copy(accuracy = it.accuracy.copy(shown = true), fixAge = it.fixAge.copy(shown = true)) }
        val est = ReadoutEstimator(io.github.jasondorjeshort.dystopo.model.ActivityProfile.of(Activity.FOOT), all)
        val t0 = ReadoutSim.T0
        assertEquals("no fix", est.readout(t0).accuracy.reason)
        assertEquals("no samples", est.readout(t0).pressure.reason)
        est.onFix(LocationFix(ReadoutSim.LAT0, ReadoutSim.LON0, 1600.0, t0, accuracyM = 6.5, speedMps = 1.2, courseDeg = 163.0))
        est.onPressure(PressureSample(t0 + 500, 842.3))
        val r = est.readout(t0 + 4_000)
        assertEquals(6.5, r.accuracy.value)
        assertEquals(4.0, r.fixAge.value)
        assertEquals(842.3, r.pressure.value)
        assertEquals("sensor", r.pressure.source)
        assertEquals(ValueState.DASH, est.readout(t0 + 500 + ElevationFilter.BARO_FRESH_MS + 1).pressure.state, "a silent barometer: a dash")
        val off = ReadoutEstimator(io.github.jasondorjeshort.dystopo.model.ActivityProfile.of(Activity.FOOT))
        off.onFix(LocationFix(ReadoutSim.LAT0, ReadoutSim.LON0, 1600.0, t0, accuracyM = 6.5))
        assertEquals(ValueState.HIDDEN, off.readout(t0).accuracy.state, "the extended entries are off by default")
    }
}
