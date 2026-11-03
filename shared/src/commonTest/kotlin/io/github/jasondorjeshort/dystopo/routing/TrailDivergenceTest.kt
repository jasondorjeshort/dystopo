package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T213: the divergence detector on a synthetic trail (2.2 km due north from (0, 0)) and synthetic walks along it, some
 * of which leave it by 60 m for 300 m: the thresholds (off by the larger of twice the accuracy and 25 m, for 100 m),
 * the agreement rule (two days, or one track at 10 m or better all the way) and the hedged strings.
 */
class TrailDivergenceTest {
    private val path = WayAttrs(WayClass.PATH, name = "Test trail")
    private val source = GraphSource("test", "fp", "", 14, 0)
    private val flat = ElevationSampler { _, _ -> 1500.0 }
    private val lats = (0..100).map { it * 0.0002 }
    private val graph by lazy {
        RoutingGraphBuilder(flat).build(listOf(WayLine.of(path, *lats.map { it to 0.0 }.toTypedArray())), source)
    }
    private val snapper by lazy { TrackMatch.snapper(graph) }
    private val day0 = 1_780_000_000_000L
    private val dayMs = 86_400_000L
    private val mPerDeg = 111_195.0

    /**
     * A walk north along the trail, a fix every 3 s at 1.3 m/s with Gaussian noise of [noiseM] reporting [accuracyM],
     * east of it by [detourM] from [fromM] for [lenM] (30 m ramps either side).
     */
    private fun walk(
        id: String, startMs: Long, detourM: Double = 60.0, fromM: Double = 800.0, lenM: Double = 300.0,
        accuracyM: Double? = 12.0, noiseM: Double = 3.0, seed: Int = 1, activity: Activity? = Activity.FOOT, totalM: Double = 2000.0,
    ): Track {
        val rnd = Random(seed)
        fun gauss(): Double = sqrt(-2 * ln(rnd.nextDouble().coerceAtLeast(1e-12))) * cos(2 * PI * rnd.nextDouble())
        val ramp = 30.0
        fun east(s: Double): Double = when {
            s < fromM - ramp || s > fromM + lenM + ramp -> 0.0
            s < fromM -> detourM * (s - (fromM - ramp)) / ramp
            s > fromM + lenM -> detourM * ((fromM + lenM + ramp) - s) / ramp
            else -> detourM
        }
        val pts = ArrayList<TrackPoint>()
        var s = 10.0
        var t = startMs
        while (s < totalM) {
            val lat = s / mPerDeg + noiseM * gauss() / mPerDeg
            val lon = (east(s) + noiseM * gauss()) / mPerDeg
            pts += TrackPoint(lat, lon, 1500.0, t, accuracyM = accuracyM)
            s += 1.3 * 3
            t += 3000
        }
        return Track(id, "synthetic $id", listOf(TrackSegment(pts)), activity = activity)
    }

    private fun detect(vararg t: Track) = TrailDivergence.detect(t.toList(), snapper, TrailDivergence.edges(graph))

    @Test
    fun threeAgreeingTracksOnDifferentDaysAreOneRecordTheFaithfulOneIsNot() {
        val r = detect(
            walk("a", day0, seed = 1), walk("b", day0 + dayMs, seed = 2), walk("c", day0 + 2 * dayMs, seed = 3),
            walk("faithful", day0 + 3 * dayMs, detourM = 0.0, seed = 4),
        )
        assertEquals(1, r.confirmed.size, "${r.json()}")
        val d = r.confirmed.single()
        assertEquals(3, d.tracks)
        assertEquals(3, d.days)
        assertEquals("days", d.confirmedBy)
        assertFalse("faithful" in d.trackIds)
        assertTrue(d.lengthM in 280.0..420.0, "length ${d.lengthM}")
        assertTrue(assertNotNull(d.medianOffsetM) in 50.0..70.0, "offset ${d.medianOffsetM}")
        val e = assertNotNull(d.edge)
        assertEquals("Test trail", e.name)
        assertEquals(WayClass.PATH, e.wayClass)
        assertTrue(e.distanceM in 45.0..75.0, "the midpoint is about 60 m off: ${e.distanceM}")
        // The midpoint sits halfway along the detour, about 950 m north.
        assertTrue(d.mid.lat * mPerDeg in 900.0..1000.0, "${d.mid}")
        assertEquals(0, r.records.count { !it.confirmed })
        assertEquals(3, r.candidates.size)
    }

    @Test
    fun oneOrdinaryTrackStaysUnconfirmed() {
        val r = detect(walk("a", day0))
        assertTrue(r.confirmed.isEmpty())
        val u = r.records.single()
        assertNull(u.confirmedBy)
        assertEquals(1, u.tracks)
    }

    @Test
    fun oneTrackAtTenMetresOrBetterAllTheWayConfirmsAlone() {
        val d = detect(walk("a", day0, accuracyM = 5.0)).confirmed.single()
        assertEquals("accuracy", d.confirmedBy)
        assertEquals(1, d.tracks)
        // One fix without an accuracy is not "good the whole way".
        val t = walk("b", day0, accuracyM = 5.0)
        val pts = t.points.toMutableList()
        val i = pts.indexOfFirst { it.lon * mPerDeg > 50 }
        pts[i + 3] = pts[i + 3].copy(accuracyM = null)
        assertTrue(detect(t.copy(segments = listOf(TrackSegment(pts)))).confirmed.isEmpty())
    }

    @Test
    fun twoTracksTheSameDayAreNotEnoughTwoDaysAre() {
        assertTrue(detect(walk("a", day0, seed = 1), walk("b", day0 + 3_600_000, seed = 2)).confirmed.isEmpty())
        val d = detect(walk("a", day0, seed = 1), walk("b", day0 + dayMs, seed = 2)).confirmed.single()
        assertEquals(2, d.tracks)
        assertEquals(2, d.days)
    }

    @Test
    fun shortWobblesAndDriftAreNotDivergences() {
        // A wobble 40 m out and back over 70 m of trail: about 50 m of it beyond 25 m, under the 100 m length.
        val wobble = detect(walk("a", day0, detourM = 40.0, lenM = 10.0, seed = 1), walk("b", day0 + dayMs, detourM = 40.0, lenM = 10.0, seed = 2))
        assertTrue(wobble.records.isEmpty(), "${wobble.json()}")
        // 40 m off: beyond 25 m, but within twice a 30 m accuracy (drift under trees).
        val drift = detect(walk("a", day0, detourM = 40.0, accuracyM = 30.0, seed = 1), walk("b", day0 + dayMs, detourM = 40.0, accuracyM = 30.0, seed = 2))
        assertTrue(drift.records.isEmpty(), "${drift.json()}")
        // The same 40 m with a 12 m accuracy is off the line.
        assertEquals(1, detect(walk("a", day0, detourM = 40.0, seed = 1), walk("b", day0 + dayMs, detourM = 40.0, seed = 2)).confirmed.size)
        // Within 25 m with no accuracy reported: on the line.
        val near = detect(walk("a", day0, detourM = 15.0, accuracyM = null, seed = 1), walk("b", day0 + dayMs, detourM = 15.0, accuracyM = null, seed = 2))
        assertTrue(near.records.isEmpty(), "${near.json()}")
    }

    @Test
    fun twoPlacesAreTwoRecords() {
        val r = detect(
            walk("a", day0, fromM = 400.0, seed = 1), walk("b", day0 + dayMs, fromM = 400.0, seed = 2),
            walk("c", day0, fromM = 1400.0, seed = 3), walk("d", day0 + dayMs, fromM = 1400.0, seed = 4),
        )
        assertEquals(2, r.confirmed.size, "${r.json()}")
        assertEquals(setOf(setOf("a", "b"), setOf("c", "d")), r.confirmed.map { it.trackIds.toSet() }.toSet())
        assertTrue(r.confirmed[0].id != r.confirmed[1].id)
    }

    @Test
    fun notTrailsAndOutsideTheNetworkAreNotRead() {
        val road = detect(walk("a", day0, activity = Activity.ROAD, seed = 1), walk("b", day0 + dayMs, activity = Activity.ROAD, seed = 2))
        assertTrue(road.records.isEmpty())
        assertEquals(2, road.counts["notTrails"])
        val outside = TrailDivergence.detect(listOf(walk("a", day0, seed = 1), walk("b", day0 + dayMs, seed = 2)), snapper, covers = { _, lon -> lon * mPerDeg < 20 })
        assertTrue(outside.records.isEmpty(), "a stretch past the coverage's edge is not one")
        // A walk entirely off the network touches no mapped line: nothing.
        val far = walk("far", day0, detourM = 0.0).let { t -> t.copy(segments = listOf(TrackSegment(t.points.map { it.copy(lon = it.lon + 300 / mPerDeg) }))) }
        assertTrue(detect(far, far.copy(id = "far2", segments = listOf(TrackSegment(far.points.map { it.copy(time = it.time!! + dayMs) })))).records.isEmpty())
    }

    @Test
    fun theStringsAreHedged() {
        assertEquals("Your tracks leave the mapped trail here", DivergenceText.TITLE)
        assertEquals("2 of your tracks follow a line about 140 m long that is not on the map", DivergenceText.body(2, 137.0, ContourUnits.METERS))
        assertEquals("One of your tracks follows a line about 460 ft long that is not on the map", DivergenceText.body(1, 140.0, ContourUnits.FEET))
        assertEquals("3 of your tracks follow a line about 1.2 km long that is not on the map", DivergenceText.body(3, 1210.0, ContourUnits.METERS))
        for (s in listOf(DivergenceText.TITLE, DivergenceText.body(2, 300.0, ContourUnits.METERS), DivergenceText.SETTING)) {
            assertFalse(Regex("error|wrong|mistake", RegexOption.IGNORE_CASE).containsMatchIn(s), s)
        }
    }

    @Test
    fun aRecordRoundTripsThroughItsJson() {
        val d = detect(walk("a", day0, seed = 1), walk("b", day0 + dayMs, seed = 2)).confirmed.single()
        val back = assertNotNull(TrailDivergence.Divergence.fromJson(MiniJson.parseObject(MiniJson.encode(d.json()))))
        assertEquals(d.id, back.id)
        assertEquals(d.tracks, back.tracks)
        assertEquals(d.confirmedBy, back.confirmedBy)
        assertEquals(d.line.size, back.line.size)
        assertEquals((d.lengthM * 10).roundToLong(), (back.lengthM * 10).roundToLong())
        assertEquals(d.startsMs.map { it / 1000 }, back.startsMs.map { it / 1000 })
        assertTrue(TrailDivergence.agree(d.line, back.line))
        assertEquals(WayClass.PATH, back.edge?.wayClass)
    }
}
