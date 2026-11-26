package io.github.jasondorjeshort.dystopo.aircraft

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T365: the guess's error on synthetic tracks (no feed data). */
class GuessErrorTest {
    private fun report(hex: String, lat: Double, lon: Double, track: Double, gsMps: Double, altFt: Double = 35_000.0, vr: Double = 0.0, seen: Double = 0.0) =
        AircraftReport(hex, null, null, null, null, lat, lon, null, altFt, false, track, gsMps / AircraftRules.KNOT_TO_MPS, vr, seen)

    @Test
    fun straightFlightHasNoError() {
        val v = 250.0
        val r = report("syn001", 46.0, -121.0, 63.0, v)
        for (t in listOf(30.0, 120.0, 300.0, 600.0)) {
            val (lat, lon) = AircraftGeo.move(46.0, -121.0, 63.0, v * t)
            val e = GuessError.errorM(r, 0, lat, lon, (t * 1000).toLong(), capS = t)
            assertTrue(e < 1.0, "straight at $t s: $e m")
        }
    }

    @Test
    fun aThirtyDegreeTurnAtTheReportIsTheFindingsGeometry() {
        // The finding: a 30 degree course change at the report puts the truth 2 sin(15°) v t = 0.52 v t from the guess.
        val chord = 2 * sin(15.0 * PI / 180.0)
        for ((v, t) in listOf(250.0 to 120.0, 150.0 to 300.0, 100.0 to 30.0)) {
            val r = report("syn002", 46.0, -121.0, 10.0, v)
            val (lat, lon) = AircraftGeo.move(46.0, -121.0, 40.0, v * t)
            val e = GuessError.errorM(r, 0, lat, lon, (t * 1000).toLong(), capS = t)
            assertEquals(chord * v * t, e, chord * v * t * 0.005, "v $v, t $t")
        }
        // The finding's cell: cruise, a 30 degree turn, 2 min: 15.5 km.
        val r = report("syn003", 46.0, -121.0, 0.0, 250.0)
        val (lat, lon) = AircraftGeo.move(46.0, -121.0, 30.0, 250.0 * 120)
        assertEquals(15.5, GuessError.errorM(r, 0, lat, lon, 120_000, 120.0) / 1000, 0.1)
    }

    @Test
    fun theTrackersCapStopsTheGuess() {
        val r = report("syn004", 46.0, -121.0, 90.0, 200.0)
        val (lat, lon) = AircraftGeo.move(46.0, -121.0, 90.0, 200.0 * 120)
        // Capped at 30 s the guess stops 90 s short: 18 km.
        assertEquals(18_000.0, GuessError.errorM(r, 0, lat, lon, 120_000, 30.0), 20.0)
    }

    @Test
    fun samplesPairEachFixWithTheTruthNearItsHorizon() {
        // One aircraft, straight, polled every 30 s with a 2 s old position; a second turning at 1 degree a second.
        val polls = (0 until 30).map { i ->
            val t = i * 30.0
            val a = AircraftGeo.move(46.0, -121.0, 0.0, 250.0 * (t - 2)) // along a meridian: a great circle whose bearing stays 0
            var lat = 46.2; var lon = -121.2; var track = 0.0
            for (s in 0 until (t - 2).toInt().coerceAtLeast(0)) { val p = AircraftGeo.move(lat, lon, track, 150.0); lat = p.first; lon = p.second; track += 1.0 }
            AircraftPoll((t * 1000).toLong(), listOf(
                report("syn005", a.first, a.second, 0.0, 250.0, seen = 2.0),
                report("syn006", lat, lon, track % 360, 150.0, altFt = 8000.0, seen = 2.0),
            ))
        }
        val tracks = GuessError.tracks(polls)
        assertEquals(30, tracks.getValue("syn005").size)
        // A position the feed repeats with a growing seen_pos is one fix.
        val again = polls + AircraftPoll(polls.last().atMs + 10_000, listOf(polls.last().aircraft[0].copy(seenPosS = 12.0)))
        assertEquals(30, GuessError.tracks(again).getValue("syn005").size)
        val s120 = GuessError.samples(tracks, 120.0, 15.0)
        val straight = s120.filter { it.hex == "syn005" }
        val turning = s120.filter { it.hex == "syn006" }
        assertTrue(straight.isNotEmpty() && straight.all { it.errorM < 5 && abs(it.ageS - 120) <= 15 })
        assertTrue(turning.all { it.phase == FlightPhase.TERMINAL })
        val s300 = GuessError.samples(tracks, 300.0, 15.0).filter { it.hex == "syn006" }
        assertTrue(GuessError.stats(s300).medianM > GuessError.stats(turning).medianM * 1.5)
    }

    @Test
    fun phasesAndQuantiles() {
        assertEquals(FlightPhase.CRUISE, FlightPhase.of(report("a", 0.0, 0.0, 0.0, 230.0)))
        assertEquals(FlightPhase.CLIMB_DESCENT, FlightPhase.of(report("a", 0.0, 0.0, 0.0, 230.0, vr = -1200.0)))
        assertEquals(FlightPhase.TERMINAL, FlightPhase.of(report("a", 0.0, 0.0, 0.0, 230.0, altFt = 5000.0)))
        assertEquals(FlightPhase.TERMINAL, FlightPhase.of(report("a", 0.0, 0.0, 0.0, 80.0)))
        val xs = (1..100).map { it.toDouble() }
        assertEquals(50.0, GuessError.quantile(xs, 0.5))
        assertEquals(90.0, GuessError.quantile(xs, 0.9))
        assertEquals(99.0, GuessError.quantile(xs, 0.99))
        assertEquals(0.573, GuessError.angleDeg(100.0, 10_000.0), 0.001)
    }
}
