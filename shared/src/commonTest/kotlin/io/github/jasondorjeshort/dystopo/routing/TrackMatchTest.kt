package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T164: the round trip through the whole chain: tracks walked at a known pace ([SyntheticWalk]) along a synthetic
 * trail over rolling terrain, cut at their stops, matched onto the graph and fitted, give the curve back within 10 %;
 * the router then times routes on the fit.
 */
class TrackMatchTest {
    private val path = WayAttrs(WayClass.PATH)
    private val source = GraphSource("test", "fp", "", 14, 0)
    private val truth = PaceFit(TravelMode.HIKE, 1.3, 3.0, -0.06, 0, 0.0)

    /** About 6.7 km due north from (0, 0), a vertex every 22 m; up to a 30 % grade, both ways. */
    private val lats = (0..300).map { it * 0.0002 }
    private fun height(lat: Double): Double {
        val s = lat * 111_195
        return 1500 + 190 * sin(2 * PI * s / 4000)
    }
    private val ele = ElevationSampler { lat, _ -> height(lat) }
    private val graph by lazy { RoutingGraphBuilder(ele).build(listOf(WayLine.of(path, *lats.map { it to 0.0 }.toTypedArray())), source) }
    private val line = lats.map { TrackPoint(it, 0.0, height(it)) }

    private fun walks(n: Int = 5): List<Track> = (0 until n).map { i ->
        SyntheticWalk.track("t$i", "synthetic $i", line, truth, 1_780_000_000_000L + i * 86_400_000L, seed = 10 + i)
    }

    @AfterTest
    fun reset() = PaceFits.clearActive()

    private fun within(want: Double, got: Double, share: Double, what: String) =
        assertTrue(abs(got - want) <= share * abs(want), "$what: want $want within ${share * 100} %, got $got")

    @Test
    fun roundTripRecoversTheCurveWithin10Percent() {
        val report = PaceFits.fromTracks(walks(), TrackMatch.snapper(graph))
        val f = assertNotNull(report.fits[TravelMode.HIKE])
        within(truth.peakMps, f.peakMps, 0.10, "peak")
        within(truth.width, f.width, 0.10, "width")
        within(truth.peakGrade, f.peakGrade, 0.10, "peak grade")
        assertEquals(5, f.tracks)
        assertTrue(f.metres > 20_000, "${f.metres}")
        assertTrue(f.ready)
        for (row in report.rows) {
            val m = assertNotNull(row.match)
            assertEquals(1, m.stops, "the 5-minute stop at the far end is cut: ${m.json()}")
            assertEquals(0, m.offNetwork)
            assertEquals(TravelMode.HIKE, row.mode)
        }
        assertNull(report.fits[TravelMode.BIKE])
    }

    @Test
    fun throughTheRoutersSnapToo() {
        // Router.nearest's EdgeSnap carries the network's height, so a router over tiles matches as one graph does.
        val viaRouter = PaceFits.fromTracks(walks(), TrackMatch.snapper(Router(graph))).fits[TravelMode.HIKE]
        val viaGraph = PaceFits.fromTracks(walks(), TrackMatch.snapper(graph)).fits[TravelMode.HIKE]
        assertEquals(assertNotNull(viaGraph).peakMps, assertNotNull(viaRouter).peakMps, 1e-9)
        val s = assertNotNull(Router(graph).nearest(0.01, 0.0001, 50.0))
        assertEquals(height(0.01), assertNotNull(s.ele), 0.5)
    }

    @Test
    fun offTheNetworkAndFlaggedFixesAreLeftOut() {
        val t = walks(1)[0]
        val pts = t.segments[0].points
        // 100 fixes 300 m east of the trail (a detour the graph does not have), and 50 flagged as inaccurate.
        val moved = pts.mapIndexed { i, p ->
            when (i) {
                in 200 until 300 -> p.copy(lon = p.lon + 0.0027)
                in 600 until 650 -> p.copy(flags = TrackPoint.INACCURATE)
                else -> p
            }
        }
        val m = TrackMatch.match(t.copy(segments = listOf(TrackSegment(moved))), TrackMatch.snapper(graph))
        assertTrue(m.offNetwork >= 90, "${m.json()}")
        assertEquals(50, m.flagged)
        assertTrue(m.pieces.all { it.lengthM <= TrackMatch.PIECE_M + 30 }, "no piece spans the detour")
        // Without a snapper the track's own heights are used and nothing is off the network.
        val own = TrackMatch.match(t, null)
        assertEquals(0, own.offNetwork)
        assertTrue(own.pieces.size > 40)
    }

    @Test
    fun theFitFlowsIntoRoutesAndSummaries() {
        val standard = assertIs<RouteResult.Found>(Router(graph).route(0.0, 0.0, 0.05, 0.0)).plan
        assertNull(standard.pace)
        val fit = assertNotNull(PaceFits.fromTracks(walks(), TrackMatch.snapper(graph)).fits[TravelMode.HIKE])
        PaceFits.setActive(listOf(fit))
        val mine = assertIs<RouteResult.Found>(Router(graph).route(0.0, 0.0, 0.05, 0.0)).plan
        assertEquals(fit, mine.pace)
        assertTrue(mine.json().containsKey("pace"))
        assertTrue(abs(mine.timeS - standard.timeS) > 300, "standard ${standard.timeS} s, fitted ${mine.timeS} s")
        // The walk itself took about what the fit says: the truth's time along the same line.
        val truthS = truth.pathTimeS(mine.route.points.map { TrackPoint(it.lat, it.lon, it.ele) })
        within(truthS, mine.timeS, 0.08, "fitted route time against the truth's")
        // The trip summary (the card's and a kept trip's number) reads the same fit.
        val summary = RouteSummary.of(RoutePath.planned(mine.route.points, "hike", mine.timeS), emptyList(), samples = 0)
        within(mine.timeS, summary.timeS, 0.03, "summary")
        // Each edge's time on the fit, and its slope cost.
        val m = CostModel(TravelMode.HIKE)
        val e = (0 until graph.edgeCount).maxBy { graph.edgeAscent[it] }
        assertTrue(m.edgeTimeS(graph, e, graph.edgeLength[e].toDouble()) > graph.edgeLength[e] / fit.flatMps)
        assertTrue(m.slopePenalty(graph.edgeLength[e].toDouble(), graph.edgeAscent[e].toDouble(), graph.edgeDescent[e].toDouble()) > 0)
        PaceFits.clearActive()
        val back = assertIs<RouteResult.Found>(Router(graph).route(0.0, 0.0, 0.05, 0.0)).plan
        assertEquals(standard.timeS, back.timeS, 1e-9, "the standard estimate is back")
    }
}
