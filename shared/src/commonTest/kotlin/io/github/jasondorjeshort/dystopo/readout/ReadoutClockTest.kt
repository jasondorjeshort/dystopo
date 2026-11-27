package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.store.LocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T385: the readout's time base ([ReadoutClock]) and the GPS clock check ([GpsClockCheck]), driven the way the phone's
 * readout drives them, with the estimator behind (finding `docs/findings/2026-11-26-gps-time-fault-restarts-the-readout-
 * over-and-over.md`). Fixes near Paradise (a public place); elapsed time and the phone's clock made up beside them.
 */
class ReadoutClockTest {
    private val wall0 = 1_795_000_000_000L // the phone's clock at elapsed e0 (2026-11-18, after GpsClockCheck.NOT_BEFORE_MS)
    private val e0 = 5_000_000L

    /** The phone as the readout sees it: elapsed time, the phone's clock ([phoneSkewMs] off the truth), the readout's pieces. */
    private inner class Phone(val phoneSkewMs: Long = 0L) {
        val clock = ReadoutClock()
        val check = GpsClockCheck()
        val est = ReadoutEstimator(Activity.FOOT)
        val flags = ArrayList<GpsClockCheck.Flag>()

        fun el(s: Double) = e0 + (s * 1000).toLong()
        fun wall(s: Double) = wall0 + (s * 1000).toLong() + phoneSkewMs

        /** A device GPS fix taken at [s] (true time), its own time [receiverOffMs] off the truth, received 300 ms later. */
        fun deviceFix(s: Double, receiverOffMs: Long = 0L, stamp: Long? = el(s), ele: Double = 1650.0) {
            val nowS = s + 0.3
            val own = wall0 + (s * 1000).toLong() + receiverOffMs
            flags += check.onFix(own, GpsClockCheck.phoneTime(stamp, el(nowS), wall(nowS)), el(nowS))
            val t = clock.deviceFix(stamp, el(nowS), wall(nowS))
            val (lat, lon) = ReadoutSim.latLon(0.0, 0.0)
            est.onFix(LocationFix(lat, lon, ele, t, accuracyM = 5.0, speedMps = 0.0, verticalAccuracyM = 6.0), elapsedMs = el(nowS))
        }

        /** A barometer sample taken at [s], stamped on elapsed realtime as the sensor's event is. */
        fun pressure(s: Double, hPa: Double = 830.0) {
            if (!clock.known) return
            est.onPressure(PressureSample(clock.at(el(s)), hPa, PressureSampleSource.SENSOR), el(s + 0.1))
        }

        fun read(s: Double) = est.readout(clock.at(el(s)), el(s))
    }

    /** One fix a second and four barometer samples between, for [range], the receiver [off] at each second. */
    private fun Phone.walk(range: IntRange, off: (Int) -> Long = { 0L }) {
        for (i in range) {
            deviceFix(i.toDouble(), off(i))
            for (q in 1..4) pressure(i + q * 0.2)
        }
    }

    @Test
    fun aFix46MinutesOffAmongGoodOnesIsFlaggedAndUsedAndNothingRestarts() {
        val p = Phone()
        val off = -46 * 60_000L
        // Good fixes, then the owner's case: the receiver 46 min behind for two minutes, then good again.
        p.walk(0..60)
        p.walk(61..180) { off }
        val during = p.read(180.0)
        p.walk(181..240)
        val r = p.read(240.0)
        assertEquals(0L, r.counters["clockResets"] ?: 0L, "no restart")
        assertEquals(0L, r.counters["lateInputs"] ?: 0L, "no barometer sample late")
        assertEquals(ValueState.SHOWN, during.elevation.state)
        assertFalse(during.elevation.dimmed, "the height keeps updating while the receiver is off")
        assertNull(during.gapS)
        // Every off fix flagged; the fault was a disagreement (46 min is not impossible on its own) and ended.
        assertEquals(120, p.flags.count { it.off })
        assertTrue(p.flags.none { it.implausible })
        assertNull(p.check.fault)
        val f = p.check.ended.single()
        assertEquals(GpsClockFault.DISAGREE, f.kind)
        assertEquals(p.el(61.3), f.sinceElapsedMs, "the fault starts at its first flagged fix")
        assertEquals(p.el(182.3), f.endElapsedMs, "and ends at the second good fix")
    }

    @Test
    fun theTimeBaseBeforeT385RestartedAtEverySample() {
        // The rule this task replaces: the offset from every fix's own time, the samples on the phone's clock plus it.
        val est = ReadoutEstimator(Activity.FOOT)
        var offset = 0L
        val (lat, lon) = ReadoutSim.latLon(0.0, 0.0)
        for (i in 0..120) {
            val own = wall0 + i * 1000L + if (i > 60 && i % 2 == 0) -46 * 60_000L else 0L // a bad fix among good ones
            offset = own - (wall0 + i * 1000L)
            est.onFix(LocationFix(lat, lon, 1650.0, own, accuracyM = 5.0, speedMps = 0.0), elapsedMs = e0 + i * 1000L)
            est.onPressure(PressureSample(wall0 + i * 1000L + 500 + offset, 830.0, PressureSampleSource.SENSOR), e0 + i * 1000L + 500)
        }
        assertTrue((est.readout(wall0 + 120_000, e0 + 120_000).counters["clockResets"] ?: 0L) >= 25, "the finding's repeat: a restart at each bad fix")
    }

    @Test
    fun aSteadyLeapSecondOffsetIsADisagreementAndTheReadoutWorks() {
        val p = Phone()
        p.walk(0..120) { -18_000L }
        val r = p.read(120.0)
        assertEquals(0L, r.counters["clockResets"] ?: 0L)
        assertFalse(r.elevation.dimmed)
        assertEquals(GpsClockFault.DISAGREE, p.check.fault?.kind)
        assertFalse(p.check.fault!!.lasted(p.el(120.0)), "a disagreement never names the reset")
    }

    @Test
    fun aPhoneClockTenMinutesWrongWithAGoodReceiverKeepsANormalReadout() {
        val p = Phone(phoneSkewMs = -10 * 60_000L)
        p.walk(0..120)
        val r = p.read(120.0)
        assertEquals(0L, r.counters["clockResets"] ?: 0L)
        assertNull(r.gapS)
        assertFalse(r.elevation.dimmed)
        // The receiver is right, the phone is not: never "unreliable"; the readout's clock is the phone's, steady.
        assertEquals(GpsClockFault.DISAGREE, p.check.fault?.kind)
        assertEquals(p.wall(120.0), r.timeMs)
    }

    @Test
    fun aReceiverAtTheRolloverIsUnreliableAndNamesTheResetOnceItHasLasted() {
        val p = Phone()
        p.walk(0..10)
        p.walk(11..100) { -GpsClockCheck.ROLLOVER_MS }
        val f = assertNotNull(p.check.fault)
        assertEquals(GpsClockFault.UNRELIABLE, f.kind)
        assertFalse(f.lasted(p.el(11.3 + 59.0)))
        assertTrue(f.lasted(p.el(11.3 + 60.0)))
        assertEquals(0L, p.read(100.0).counters["clockResets"] ?: 0L)
        assertFalse(p.read(100.0).elevation.dimmed)
    }

    @Test
    fun oneStrayFixIsFlaggedButNoFault() {
        val p = Phone()
        p.walk(0..30) { if (it == 10 || it == 20) 7_000L else 0L }
        assertEquals(2, p.flags.count { it.off })
        assertNull(p.check.fault)
        assertTrue(p.check.ended.isEmpty())
    }

    @Test
    fun aFixWithNoUsableElapsedStampIsOrderedByItsReceipt() {
        val p = Phone()
        p.walk(0..10)
        p.deviceFix(11.0, stamp = null)
        p.deviceFix(12.0, stamp = 0L)
        p.deviceFix(13.0, stamp = p.el(13.0) + 60_000L) // in the future
        p.walk(14..30)
        assertEquals(3L, p.clock.stampsMissing)
        assertEquals(0L, p.read(30.0).counters["clockResets"] ?: 0L)
        assertTrue(p.flags.none { it.off }, "the receipt is close enough: no flag")
        assertEquals(p.el(11.3), ReadoutClock.fixElapsed(null, p.el(11.3)) ?: p.el(11.3))
        assertNull(ReadoutClock.fixElapsed(0L, 100L))
        assertNull(ReadoutClock.fixElapsed(101L, 100L))
        assertEquals(100L, ReadoutClock.fixElapsed(100L, 100L))
    }

    @Test
    fun aReplayKeepsItsOwnTimesAndTheDeviceTakesItsBaseBackAfter() {
        val c = ReadoutClock()
        val est = ReadoutEstimator(Activity.FOOT)
        val (lat, lon) = ReadoutSim.latLon(0.0, 0.0)
        val file0 = wall0 - 400L * 86_400_000L // a replay recorded a year and more ago
        for (i in 0..60) {
            val t = c.injectedFix(file0 + i * 1000L, e0 + i * 1000L)
            assertEquals(file0 + i * 1000L, t, "a replay's fix keeps its own time")
            est.onFix(LocationFix(lat, lon, 1650.0, t, accuracyM = 5.0, source = "debug"), elapsedMs = e0 + i * 1000L)
            est.onPressure(PressureSample(file0 + i * 1000L + 500, 830.0, PressureSampleSource.REPLAY), e0 + i * 1000L + 500)
        }
        assertFalse(c.deviceBase)
        assertEquals(file0 + 60_500, c.at(e0 + 60_500), "a read during the replay is on its clock")
        assertEquals(0L, est.readout(c.at(e0 + 61_000), e0 + 61_000).counters["clockResets"] ?: 0L)
        // The first device fix after it takes the phone's clock as the base, whatever the fix's own time.
        val t = c.deviceFix(e0 + 70_000, e0 + 70_300, wall0 + 70_300)
        assertTrue(c.deviceBase)
        assertEquals(wall0 + 70_000, t)
        assertEquals(wall0 + 70_300 - (e0 + 70_300), c.offsetMs)
        assertEquals(0L, c.minusWall(e0 + 80_000, wall0 + 80_000))
        // The phone's clock set back an hour later does not move the base.
        assertEquals(wall0 + 90_000, c.deviceFix(e0 + 90_000, e0 + 90_100, wall0 - 3_600_000L))
    }

    @Test
    fun implausibleTimesAndTheTrackPointsTime() {
        val now = 1_795_000_000_000L // 2026-11-18
        assertTrue(GpsClockCheck.implausible(0L, now), "the epoch")
        assertTrue(GpsClockCheck.implausible(315_964_800_000L, now), "the GPS epoch, 1980")
        assertTrue(GpsClockCheck.implausible(now - GpsClockCheck.ROLLOVER_MS, now), "1024 weeks early")
        assertFalse(GpsClockCheck.implausible(now - 46 * 60_000L, now), "46 min is possible on its own")
        assertFalse(GpsClockCheck.implausible(now + 600_000L, now))
        // A track point: the receiver's own time while it agrees, the phone's for a flagged fix.
        assertEquals(now + 4_000L, GpsClockCheck.trackTime(now + 4_000L, now))
        assertEquals(now, GpsClockCheck.trackTime(now - 46 * 60_000L, now))
        assertEquals(now, GpsClockCheck.trackTime(now - 18_000L, now))
        assertEquals(10_000L - 300L, GpsClockCheck.phoneTime(9_700L, 10_000L, 10_000L))
        assertEquals(10_000L, GpsClockCheck.phoneTime(null, 10_000L, 10_000L))
    }
}
