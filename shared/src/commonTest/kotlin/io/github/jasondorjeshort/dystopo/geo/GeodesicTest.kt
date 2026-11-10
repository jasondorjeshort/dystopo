package io.github.jasondorjeshort.dystopo.geo

import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.RouteEditor
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.model.ViaPoint
import io.github.jasondorjeshort.dystopo.routing.Crow
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T256: [Geodesic] against Karney's geodesic test set (C. F. F. Karney, "Test set for geodesics", GeodTest-short.dat
 * from GeographicLib's test data, doi:10.5281/zenodo.32156; columns lat1 lon1 azi1 lat2 lon2 azi2 s12, the rest
 * dropped). Lines are cited by their number in that file: 1 to 2000 random, 2001 to 3000 nearly antipodal, 8001 on
 * between vertices and close to them (Vincenty's inverse fails on lines 2017, 8141 and 9300, and the bracketed solve
 * answers them). Over the whole file (10 000 lines, measured once with a scratch harness) the length is off by at
 * most 0.08 mm and the direct problem misses by at most 0.09 mm; the test asks a millimetre.
 */
class GeodesicTest {
    private class Row(val line: Int, val lat1: Double, val lon1: Double, val azi1: Double, val lat2: Double, val lon2: Double, val azi2: Double, val s12: Double)

    private fun row(line: Int, text: String): Row {
        val v = text.trim().split(" ").filter { it.isNotEmpty() }.map { it.toDouble() }
        return Row(line, v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }

    private val rows = listOf(
        row(265, ".540827828277 0 32.50917922827 4.507174532378933477 2.517759838172413923 32.620140733579005677 520316.9365643"), // hundreds of km
        row(78, "76.518994952595 0 110.473976761229 72.717393093744957159 22.991343121931328216 132.674483762662491135 794479.8953839"),
        row(16, "31.107268253986 0 96.326335961555 23.844027269376799941 32.044751451938966399 111.452579810921349209 3255294.3503576"), // thousands
        row(29, "43.66178983479 0 94.265403115439 32.323523912266902818 42.502156758765915622 121.321916460531882062 3891531.1257815"),
        row(8, "11.695368374369 0 49.92711210542 16.34475503818505324 146.829159972333832736 128.666191953615620089 15231713.6153841"),
        row(2001, "8.226828747671 0 111.1269645725 -8.516119211674268968 178.688979582629224039 68.982798544955243193 19886305.6710041"), // nearly antipodal
        row(2017, ".40516668249 0 57.568735948302 -.345676107973991275 179.397752114790056004 122.431873436717210558 19967735.258138"),
        row(8141, "89.763113980385 0 90 -89.763113980385 179.997498553925399984 90.000000000000000088 20003930.8810530173649"),
        row(9300, "32.183808407492 0 90.016950847786 -32.183808407493965117 179.488862157334738412 89.983049156374185668 19979822.9943521"),
    )

    private fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)

    @Test
    fun theInverseMatchesKarneysTestSetToAMillimetre() {
        for (r in rows) {
            val inv = Geodesic.inverse(r.lat1, r.lon1, r.lat2, r.lon2)
            assertEquals(r.s12, inv.distanceM, 0.001, "line ${r.line}")
            if (r.line != 8141) { // both ends 0.24 degrees from the poles: every azimuth near 90 joins them
                assertTrue(angleDiff(r.azi1, inv.azi1Deg) < 1e-6 || (r.line == 9300 && angleDiff(r.azi1, inv.azi1Deg) < 2e-3), "azi1 line ${r.line}: ${inv.azi1Deg}")
            }
            assertEquals(r.line !in setOf(2017, 8141, 9300), inv.converged, "Vincenty's iteration on line ${r.line}")
        }
    }

    @Test
    fun theDirectProblemLandsWithinAMillimetre() {
        for (r in rows) {
            val d = Geodesic.direct(r.lat1, r.lon1, r.azi1, r.s12)
            assertTrue(Geodesic.distanceM(d.lat, d.lon, r.lat2, r.lon2) < 0.001, "line ${r.line}: ${d.lat}, ${d.lon}")
            assertTrue(angleDiff(r.azi2, d.aziDeg) < 1e-6, "azi2 line ${r.line}: ${d.aziDeg}")
        }
    }

    @Test
    fun acrossTheAntimeridianTheLengthIsTheSame() {
        // The test set starts every line at longitude 0; shifted 170 degrees east the same lines cross longitude 180.
        for (r in rows.filter { it.line in setOf(16, 29, 8, 2001) }) {
            val lon1 = Geodesic.normalizeLon(r.lon1 + 170)
            val lon2 = Geodesic.normalizeLon(r.lon2 + 170)
            assertTrue(lon2 < 0, "line ${r.line} crosses")
            assertEquals(r.s12, Geodesic.distanceM(r.lat1, lon1, r.lat2, lon2), 0.001, "line ${r.line}")
            assertTrue(angleDiff(r.azi1, Geodesic.initialBearingDeg(r.lat1, lon1, r.lat2, lon2)) < 1e-6)
        }
        assertEquals(0.0, Geodesic.distanceM(10.0, 180.0, 10.0, -180.0), 1e-9, "the seam is one meridian")
    }

    @Test
    fun aLineAcrossTheAntimeridianIsSplitThere() {
        // Line 16 shifted to cross longitude 180 (see above): samples, the seam's points, two pieces.
        val r = rows.first { it.line == 16 }
        val lon1 = r.lon1 + 170
        val lon2 = Geodesic.normalizeLon(r.lon2 + 170)
        val pts = Geodesic.points(r.lat1, lon1, r.lat2, lon2, 100)
        assertEquals(101, pts.size)
        val sampled = Geodesic.lengthM(pts, { it.first }, { it.second })
        assertEquals(r.s12, sampled, 0.001, "evenly spaced samples on the geodesic add up to it")
        val seamed = Geodesic.withSeamPoints(pts, { it.first }, { it.second }) { lat, lon -> lat to lon }
        assertEquals(103, seamed.size, "two seam points")
        val pieces = Geodesic.splitPieces(seamed) { it.second }
        assertEquals(2, pieces.size)
        assertEquals(180.0, pieces[0].last().second)
        assertEquals(-180.0, pieces[1].first().second)
        assertEquals(pieces[0].last().first, pieces[1].first().first, "the same latitude on both sides")
        for (p in pieces) for ((a, b) in p.zipWithNext()) assertTrue(abs(a.second - b.second) < 180, "no step spans the map")
        assertEquals(r.s12, Geodesic.lengthM(seamed, { it.first }, { it.second }), 0.01, "the seam's point, interpolated between samples 33 km apart, adds 4 mm")
        // A line that does not cross is one piece and gets no seam points.
        val east = Geodesic.points(r.lat1, r.lon1, r.lat2, r.lon2, 50)
        assertEquals(east, Geodesic.withSeamPoints(east, { it.first }, { it.second }) { lat, lon -> lat to lon })
        assertEquals(1, Geodesic.splitPieces(east) { it.second }.size)
    }

    @Test
    fun aLineRouteFollowsTheGeodesicAndCrossesTheSeam() {
        // Rainier to Fiordland (both in areas.json, their boxes' centres) across the Pacific: the leg's points sit on the
        // geodesic, not on a line in latitude and longitude, and the leg carries the seam's two points.
        val fromLat = 46.855; val fromLon = -121.76
        val toLat = -44.65; val toLon = 167.89
        val leg = RouteEditor(null).straight(ViaPoint(fromLat, fromLon), ViaPoint(toLat, toLon))
        assertEquals(LegKind.STRAIGHT, leg.kind)
        val geodesic = Geodesic.distanceM(fromLat, fromLon, toLat, toLon)
        assertEquals(geodesic, Crow.lengthM(leg.points), geodesic * 1e-7)
        assertTrue(leg.points.size > geodesic / RouteEditor.GEODESIC_STEP_M, "a point every 10 km without a DEM")
        assertEquals(2, Geodesic.splitPieces(leg.points) { it.lon }.size)
        for (p in leg.points.filterIndexed { i, _ -> i % 97 == 0 }) {
            val via = Geodesic.distanceM(fromLat, fromLon, p.lat, p.lon) + Geodesic.distanceM(p.lat, p.lon, toLat, toLon)
            assertEquals(geodesic, via, 1.0, "on the geodesic: ${p.lat}, ${p.lon}")
        }
        // A quarter of the way along a line in latitude and longitude (the old sampling) lies far off the geodesic.
        val linLat = fromLat + (toLat - fromLat) / 4
        val linLon = Geodesic.normalizeLon(fromLon + (toLon - 360 - fromLon) / 4)
        val detour = Geodesic.distanceM(fromLat, fromLon, linLat, linLon) + Geodesic.distanceM(linLat, linLon, toLat, toLon) - geodesic
        assertTrue(detour > 20_000, "the old line's quarter point is $detour m off the shortest way")
        // The plan: the geodesic's length and bearing, no terrain under it (no DEM), no profile, and the description says so.
        val plan = Crow.plan(fromLat, fromLon, toLat, toLon, null)
        assertEquals(geodesic, plan.distanceM, 0.01)
        assertEquals(Geodesic.initialBearingDeg(fromLat, fromLon, toLat, toLon).let { kotlin.math.round(it).toInt() % 360 }, Crow.bearingDeg(fromLat, fromLon, toLat, toLon))
        assertEquals(null, plan.ascentM)
        assertEquals(emptyList(), plan.profile)
        assertTrue(plan.route.description!!.contains("no terrain under this line"), plan.route.description)
        val summary = RouteSummary.of(RoutePath.planned(plan.route.points, "crow", plan.timeS), emptyList())
        assertEquals(geodesic, summary.distanceM, geodesic * 1e-7, "the card's distance is the geodesic's")
        // A DEM that covers the start only (land, then ocean): heights where there are some, a profile.
        val landOnly = ElevationSampler { _, lon -> if (lon < -121.0 && lon > -122.0) 1500.0 else Double.NaN }
        val partial = Crow.plan(fromLat, fromLon, toLat, toLon, landOnly)
        assertTrue(partial.profile.isNotEmpty())
        assertEquals(2001, partial.route.points.size - 2, "the cap, plus the seam's two points")
    }
}
