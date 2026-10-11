package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A tiny trail network around Paradise, Mount Rainier National Park, hand-written from public trail
 * geometry (the same vertices as the Skyline loop fixture, `gpx/skyline-loop.gpx`, rounded to about
 * 10 m): the Skyline Trail from the trailhead up past Alta Vista and Glacier Vista to Panorama Point,
 * the upper Skyline over to the Golden Gate junction, the Golden Gate Trail down to Myrtle Falls, the
 * lower Skyline back by the Stevens-Van Trump memorial, a stub of the Paradise road, and a separate
 * path near Sunrise that nothing connects to. Elevation is interpolated from the
 * fixture's vertex elevations.
 */
class RainierRoutingTest {
    private val skylineWest = listOf(
        46.7865 to -121.7365, 46.7878 to -121.7362, 46.7889 to -121.7370, 46.7895 to -121.7376, 46.7905 to -121.7376,
        46.7913 to -121.7380, 46.7926 to -121.7375, 46.7937 to -121.7365, 46.7941 to -121.7361, // Alta Vista junction
        46.7951 to -121.7356, 46.7957 to -121.7359, 46.7962 to -121.7359, 46.7970 to -121.7359, 46.7982 to -121.7354,
        46.7986 to -121.7345, 46.7997 to -121.7344, 46.8001 to -121.7346, 46.8007 to -121.7346, 46.8010 to -121.7349, // Glacier Vista
        46.8014 to -121.7348, 46.8021 to -121.7344, 46.8029 to -121.7346, 46.8035 to -121.7344, 46.8039 to -121.7338,
        46.8045 to -121.7334, 46.8049 to -121.7331, 46.8039 to -121.7325, 46.8030 to -121.7321, 46.8027 to -121.7313,
        46.8023 to -121.7305, 46.8019 to -121.7301, 46.8021 to -121.7297, 46.8025 to -121.7290, // Panorama Point
    )
    private val upperSkyline = listOf(
        46.8025 to -121.7290, 46.8030 to -121.7298, 46.8042 to -121.7291, 46.8055 to -121.7284, 46.8057 to -121.7271,
        46.8062 to -121.7252, 46.8056 to -121.7234, 46.8045 to -121.7238, 46.8034 to -121.7222, 46.8024 to -121.7214,
        46.8012 to -121.7224, 46.8005 to -121.7240, 46.7998 to -121.7236, 46.7989 to -121.7227, // Golden Gate junction
    )
    private val lowerSkyline = listOf(
        46.7989 to -121.7227, 46.7976 to -121.7220, 46.7964 to -121.7200, 46.7946 to -121.7180, 46.7953 to -121.7168, // memorial
        46.7925 to -121.7200, 46.7915 to -121.7260, 46.7914 to -121.7325, // Myrtle Falls
        46.7890 to -121.7345, 46.7865 to -121.7365,
    )
    private val goldenGate = listOf(46.7989 to -121.7227, 46.7960 to -121.7262, 46.7935 to -121.7300, 46.7914 to -121.7325)
    private val altaVista = listOf(46.7941 to -121.7361, 46.7955 to -121.7340, 46.7975 to -121.7332, 46.7986 to -121.7345)
    private val road = listOf(46.7859 to -121.7420, 46.7862 to -121.7365, 46.7866 to -121.7300, 46.7870 to -121.7200)
    private val trailheadSpur = listOf(46.7862 to -121.7365, 46.7865 to -121.7365)
    private val sunrise = listOf(46.9147 to -121.6427, 46.9180 to -121.6400, 46.9210 to -121.6380)

    /** Elevations of the fixture's vertices (sampled from the public DEM for `skyline-loop.gpx`). */
    private val known: List<Triple<Double, Double, Double>> = skylineWest.zip(
        listOf(
            1656, 1679, 1705, 1722, 1748, 1758, 1771, 1793, 1797, 1809, 1822, 1830, 1839, 1861, 1875, 1896, 1908,
            1921, 1930, 1940, 1948, 1962, 1972, 1985, 1993, 2004, 2023, 2023, 2027, 2030, 2031, 2044, 2058,
        ),
    ) { p, e -> Triple(p.first, p.second, e.toDouble()) } + upperSkyline.drop(1).zip(
        listOf(2078, 2111, 2143, 2147, 2138, 2097, 2058, 2032, 2012, 2000, 1978, 1960, 1951),
    ) { p, e -> Triple(p.first, p.second, e.toDouble()) } + listOf(
        Triple(46.7976, -121.7220, 1923.0), Triple(46.7964, -121.7200, 1899.0), Triple(46.7946, -121.7180, 1854.0),
        Triple(46.7953, -121.7168, 1834.0), Triple(46.7914, -121.7325, 1704.0), Triple(46.7859, -121.7356, 1645.0),
        Triple(46.9147, -121.6427, 1950.0),
    )

    /** Inverse-distance-weighted elevation from [known] (a stand-in for the DEM pack). */
    private val cone = ElevationSampler { lat, lon ->
        var sw = 0.0
        var se = 0.0
        for ((a, b, e) in known) {
            val d = TrackStats.distanceM(lat, lon, a, b)
            if (d < 0.5) return@ElevationSampler e
            val w = 1 / (d * d)
            sw += w
            se += w * e
        }
        se / sw
    }

    fun graph(): RoutingGraph {
        val path = WayAttrs(WayClass.PATH, Surface.UNPAVED, sac = 1)
        fun l(pts: List<Pair<Double, Double>>, a: WayAttrs = path) = WayLine.of(a, *pts.toTypedArray())
        fun named(n: String) = path.copy(name = n) // T49: trail names
        val lines = listOf(
            l(skylineWest, named("Skyline Trail")), l(upperSkyline, named("Skyline Trail")), l(lowerSkyline, named("Skyline Trail")),
            l(goldenGate, named("Golden Gate Trail")), l(altaVista, named("Alta Vista Trail")), l(sunrise),
            l(road, WayAttrs(WayClass.SECONDARY, Surface.PAVED)), l(trailheadSpur, WayAttrs(WayClass.FOOTWAY, Surface.PAVED)),
        )
        return RoutingGraphBuilder(cone).build(lines, GraphSource("paradise", "fixture", "idw", 14, 0))
    }

    private fun length(pts: List<Pair<Double, Double>>) = pts.zipWithNext().sumOf { (a, b) -> TrackStats.distanceM(a.first, a.second, b.first, b.second) }

    private fun nearM(p: Pair<Double, Double>, pts: List<io.github.jasondorjeshort.dystopo.model.RoutePoint>): Double =
        pts.minOf { TrackStats.distanceM(p.first, p.second, it.lat, it.lon) }

    @Test
    fun paradiseToPanoramaPointFollowsTheSkylineTrail() {
        val g = graph()
        val r = Router(g).route(46.7864, -121.7366, 46.8024, -121.7289, TravelMode.HIKE, name = "Paradise to Panorama Point")
        assertIs<RouteResult.Found>(r, "$r")
        val p = r.plan
        val fixture = length(skylineWest)
        assertTrue(kotlin.math.abs(p.distanceM - fixture) <= 0.10 * fixture, "route ${p.distanceM} m vs Skyline segment $fixture m")
        // Up the Skyline Trail: past Glacier Vista, never on the Golden Gate Trail or the road.
        assertTrue(nearM(46.8010 to -121.7349, p.route.points) < 30, "passes Glacier Vista")
        assertTrue(nearM(46.7960 to -121.7262, p.route.points) > 300, "not via Golden Gate")
        assertTrue(WayClass.SECONDARY !in p.metersByClass, "not along the road: ${p.metersByClass}")
        assertTrue(p.metersByClass.getValue(WayClass.PATH) > 0.95 * p.distanceM)
        // T49: the result names the trail, in the JSON and the description, and the names survive the graph file.
        assertEquals(listOf("Skyline Trail"), p.via.map { it.first }, "via ${p.via}")
        assertTrue(p.via.single().second > 0.9 * p.distanceM)
        assertTrue("via Skyline Trail" in p.route.description!!, p.route.description)
        assertEquals(listOf("Skyline Trail"), (p.json()["via"] as List<*>).map { (it as Map<*, *>)["name"] })
        val decoded = RoutingGraph.decode(g.encode())
        assertEquals(g, decoded)
        assertEquals(setOf("Skyline Trail", "Golden Gate Trail", "Alta Vista Trail"), decoded.names.toSet())
        // Net climb as the cone says (the trail climbs past Panorama Point and drops back to it); Naismith
        // gives distance/5 km/h + climb/600 m/h.
        val net = cone.elevation(p.route.points.last().lat, p.route.points.last().lon) - cone.elevation(p.route.points.first().lat, p.route.points.first().lon)
        assertEquals(net, p.ascentM!! - p.descentM!!, 10.0)
        assertTrue(p.ascentM!! > 380, "ascent ${p.ascentM} descent ${p.descentM}")
        assertEquals(p.distanceM / 5000 * 3600 + p.ascentM!! / 600 * 3600, p.timeS, 1.0)
        assertEquals(Router.PROFILE_SAMPLES, p.profile.size)
        assertTrue(p.profile.first().eleM!! < p.profile.last().eleM!!)
        assertEquals("hike", p.route.type)
        assertEquals("Paradise to Panorama Point", p.route.name)
        // The Route goes out as GPX like any other (T24).
        val gpx = Gpx.write(GpxDocument(routes = listOf(p.route)))
        val back = Gpx.parse(gpx).routes.single()
        assertEquals(p.route.points.size, back.points.size)
        assertEquals(p.route.id, back.id)
    }

    @Test
    fun downhillTheOtherWayRoundIsTheSameTrail() {
        val g = graph()
        val r = Router(g).route(46.8024, -121.7289, 46.7864, -121.7366)
        assertIs<RouteResult.Found>(r)
        assertTrue(nearM(46.8010 to -121.7349, r.plan.route.points) < 30)
        assertTrue(r.plan.descentM!! > 380, "descent ${r.plan.descentM}")
    }

    @Test
    fun goldenGateJunctionToMyrtleFallsTakesTheGoldenGateTrail() {
        val r = Router(graph()).route(46.7989, -121.7227, 46.7914, -121.7325)
        assertIs<RouteResult.Found>(r)
        assertEquals(length(goldenGate), r.plan.distanceM, 5.0)
        assertEquals(listOf("Golden Gate Trail"), r.plan.via.map { it.first }) // T49
    }

    @Test
    fun sunriseIsNotConnectedToParadise() {
        val r = Router(graph()).route(46.7864, -121.7366, 46.9180, -121.6400)
        assertIs<RouteResult.Failed>(r)
        assertEquals(RouteFailure.NOT_CONNECTED, r.reason)
        assertTrue(r.message.startsWith("no trail connects start and end"), r.message)
    }

    @Test
    fun graphIsSmallAndRoundTrips() {
        val g = graph()
        assertEquals(1, g.component.toSet().size - 1, "Paradise and Sunrise")
        val bytes = g.encode()
        assertTrue(bytes.size < 2_000, "${bytes.size} bytes")
        assertEquals(g, RoutingGraph.decode(bytes))
        // Junctions: Alta Vista (both ends), Panorama Point, Golden Gate, Myrtle Falls, the trailhead and the road spur.
        val degree3 = (0 until g.nodeCount).count { g.adjStart[it + 1] - g.adjStart[it] >= 3 }
        assertTrue(degree3 >= 5, "junctions: $degree3")
        assertTrue(sqrt(g.componentLengthM.max()) > 0)
    }
}
