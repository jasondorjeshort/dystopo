package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.RidgeRule
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T196: the way down off a ridge, on synthetic terrain (metres east and north of a centre). */
class WayDownTest {
    private val lat0 = 46.8
    private val lon0 = -121.7
    private val pace = TurnPace.of(CostModel(TravelMode.HIKE, pace = null))

    private fun en(lat: Double, lon: Double): Pair<Double, Double> {
        val n = (lat - lat0) / 180.0 * PI * 6378137.0
        val e = (lon - lon0) / 180.0 * PI * 6378137.0 * cos(lat0 * PI / 180.0)
        return e to n
    }

    private fun terrain(f: (e: Double, n: Double) -> Double) = ElevationSampler { lat, lon -> en(lat, lon).let { (e, n) -> f(e, n) } }

    private fun t(deg: Double) = tan(deg * PI / 180)

    /**
     * A level crest running north and south along e = 0, 2000 m up: the east side falls at [eastDeg] to a valley floor
     * 300 m down, the west side at [westDeg] (to a floor 600 m down).
     */
    private fun ridge(eastDeg: Double = 30.0, westDeg: Double = 8.0) = terrain { e, _ ->
        if (e >= 0) max(1700.0, 2000.0 - e * t(eastDeg)) else max(1400.0, 2000.0 + e * t(westDeg))
    }

    private fun p(e: Double, n: Double = 0.0): NavPoint = RidgeRule.offset(lat0, lon0, e, n).let { (la, lo) -> NavPoint(la, lo) }

    /** A straight line of points from (e0, n0) to (e1, n1). */
    private fun line(e0: Double, n0: Double, e1: Double, n1: Double, n: Int = 60): List<NavPoint> = (0..n).map { k ->
        val f = k.toDouble() / n
        p(e0 + (e1 - e0) * f, n0 + (n1 - n0) * f)
    }

    @Test
    fun onTheCrestTheSteepSideIsTheQuickerWayDown() {
        val dem = ridge()
        val (la, lo) = p(0.0).let { it.lat to it.lon }
        // The way back runs east down the 30 degree side, the route ahead west down the 8 degree side.
        val r = WayDown.find(dem, la, lo, back = line(0.0, 0.0, 2000.0, 0.0), ahead = line(0.0, 0.0, -2000.0, 0.0), pace = pace)
        assertTrue(r.exposed, "the crest is on a ridge: TPI ${r.tpiM}")
        assertTrue(r.tpiM > RidgeRule.THRESHOLD_M)
        assertEquals(2, r.exits.size)
        val q = r.quickest!!
        assertEquals(WayDownDirection.BACK, q.direction, "the nearer exit, down the steep side")
        // 100 m down a 30 degree slope is 173 m out; the first 25 m sample past it is at 175 m.
        assertEquals(175.0, q.distanceM, 1e-6)
        assertTrue(q.dropM >= WayDown.DROP_M, "at least 100 m lower: ${q.dropM}")
        assertFalse(RidgeRule.isRidge(q.tpiM), "off the ridge")
        val west = r.exits[1]
        assertEquals(WayDownDirection.AHEAD, west.direction)
        // 100 m down an 8 degree slope is 712 m out.
        assertTrue(west.distanceM in 712.0..737.5, "west exit at ${west.distanceM}")
        assertTrue(west.timeS > q.timeS)
        // The stretch drawn runs from the walker to the exit.
        assertEquals(la, q.stretch.first().lat, 1e-9)
        assertEquals(q.lat, q.stretch.last().lat, 1e-12)
        assertEquals(q.lon, q.stretch.last().lon, 1e-12)
        assertEquals(175.0, en(q.lat, q.lon).first, 0.5)
        assertEquals(mapOf(WayDownDirection.BACK to WayDownSearch.FOUND, WayDownDirection.AHEAD to WayDownSearch.FOUND), r.searches)
        assertEquals(listOf("${ShadeTextMinutes(q.timeS)} down to lower ground along the way back", "or ${ShadeTextMinutes(west.timeS)} along the route"), WayDownText.cardLines(r))
        assertTrue(WayDownText.spoken(r)!!.endsWith(" down to lower ground along the way back."))
    }

    @Test
    fun theQuickerDirectionComesFirstWhicheverItIs() {
        // The same crest with the sides swapped: now the route ahead (east here, down 30 degrees) is the quicker.
        val dem = ridge()
        val (la, lo) = p(0.0).let { it.lat to it.lon }
        val r = WayDown.find(dem, la, lo, back = line(0.0, 0.0, -2000.0, 0.0), ahead = line(0.0, 0.0, 2000.0, 0.0), pace = pace)
        assertEquals(WayDownDirection.AHEAD, r.quickest!!.direction)
        assertTrue(WayDownText.cardLines(r)[0].endsWith("down to lower ground along the route"))
        assertTrue(WayDownText.cardLines(r)[1].endsWith("along the way back"))
    }

    @Test
    fun aHundredMetresBelowTheCrestIsNotExposed() {
        val dem = ridge()
        // 200 m out on the east side: 115 m below the crest, on an even 30 degree slope.
        val at = p(200.0)
        val r = WayDown.find(dem, at.lat, at.lon, back = line(200.0, 0.0, 2000.0, 0.0), ahead = line(200.0, 0.0, -2000.0, 0.0), pace = pace)
        assertFalse(r.exposed, "an even slope is not a ridge: TPI ${r.tpiM}")
        assertTrue(abs(r.tpiM) < 1.0, "TPI ${r.tpiM}")
        assertTrue(r.exits.isEmpty())
        assertTrue(WayDownText.cardLines(r).isEmpty())
        assertNull(WayDownText.spoken(r))
        // The valley floor is not exposed either.
        val v = p(800.0)
        val rv = WayDown.find(dem, v.lat, v.lon, back = line(800.0, 0.0, 0.0, 0.0), ahead = null, pace = pace)
        assertFalse(rv.exposed)
    }

    @Test
    fun alongALevelCrestNoWayDownWithinTheWindow() {
        val dem = ridge()
        val at = p(0.0)
        // The way back runs along the crest for 6 km; nothing ahead.
        val r = WayDown.find(dem, at.lat, at.lon, back = line(0.0, 0.0, 0.0, 6000.0, 120), ahead = null, pace = pace)
        assertTrue(r.exposed)
        assertTrue(r.exits.isEmpty())
        assertEquals(WayDownSearch.WINDOW, r.searches[WayDownDirection.BACK])
        assertEquals(WayDownSearch.NO_LINE, r.searches[WayDownDirection.AHEAD])
        assertTrue(WayDownText.cardLines(r).isEmpty())
        // A crest line shorter than the window ends before any way down.
        val r2 = WayDown.find(dem, at.lat, at.lon, back = line(0.0, 0.0, 0.0, 1000.0), ahead = null, pace = pace)
        assertEquals(WayDownSearch.LINE_END, r2.searches[WayDownDirection.BACK])
        // A drop of 50 m (the parameter) is reached sooner than 100 m down the same side.
        val r3 = WayDown.find(dem, at.lat, at.lon, back = line(0.0, 0.0, 2000.0, 0.0), ahead = null, pace = pace, params = WayDownParams(dropM = 50.0))
        assertTrue(r3.quickest!!.distanceM in 100.0..150.0, "50 m down 30 degrees is 87 m out: ${r3.quickest!!.distanceM}")
    }

    @Test
    fun noDataIsNeverExposed() {
        val r = WayDown.find(ElevationSampler { _, _ -> Double.NaN }, lat0, lon0, line(0.0, 0.0, 1000.0, 0.0), null, pace)
        assertFalse(r.exposed)
        assertTrue(r.noData)
        assertTrue(r.exits.isEmpty())
    }

    @Test
    fun behindAndOnwardCutALineAtTheWalker() {
        val l = line(0.0, 0.0, 1000.0, 0.0, 10) // vertices every 100 m east
        val b = WayDown.behind(l, 350.0)
        assertEquals(350.0, en(b.first().lat, b.first().lon).first, 1.0) // great-circle along, flat east
        assertEquals(listOf(300.0, 200.0, 100.0, 0.0), b.drop(1).map { kotlin.math.round(en(it.lat, it.lon).first) })
        val o = WayDown.onward(l, 350.0)
        assertEquals(350.0, en(o.first().lat, o.first().lon).first, 1.0)
        assertEquals(listOf(400.0, 500.0, 600.0, 700.0, 800.0, 900.0, 1000.0), o.drop(1).map { kotlin.math.round(en(it.lat, it.lon).first) })
        // Repeated points are dropped, as NavPath drops them.
        val dup = listOf(l[0], l[0], l[1], l[1], l[2])
        assertEquals(3, WayDown.behind(dup, 200.0).size)
        assertEquals(1, WayDown.onward(listOf(l[0]), 0.0).size)
    }

    @Test
    fun theWordsAreTerrainOnly() {
        val dem = ridge()
        val at = p(0.0)
        val r = WayDown.find(dem, at.lat, at.lon, back = line(0.0, 0.0, 2000.0, 0.0), ahead = line(0.0, 0.0, -2000.0, 0.0), pace = pace)
        val words = WayDownText.cardLines(r) + listOfNotNull(WayDownText.spoken(r)) + WayDownText.ACTION + WayDownText.ACTION_HIDE
        for (w in words) for (bad in listOf("safe", "shelter", "danger", "lightning")) assertFalse(w.lowercase().contains(bad), "\"$w\" says $bad")
        // The help text names lightning only to say what it does not tell.
        assertEquals(
            "Shows where the ground is lower than the ridge you are on, and how long the walk is. It does not say where lower or wooded ground is safe from lightning.",
            WayDownText.HELP,
        )
        assertFalse(WayDownText.HELP.contains("shelter") || WayDownText.HELP.contains("danger"))
        // Spoken minutes are words: "N minutes" or "1 minute".
        assertTrue(Regex("""^\d+ minutes? down to lower ground along the way back\.$""").matches(WayDownText.spoken(r)!!), WayDownText.spoken(r)!!)
        // The JSON names the rule and both searches.
        val j = r.json()
        assertEquals(true, j["exposed"])
        assertEquals(WayDown.DROP_M, (j["rule"] as Map<*, *>)["dropM"])
        assertEquals(2, (j["exits"] as List<*>).size)
    }

    private fun ShadeTextMinutes(timeS: Double) = io.github.jasondorjeshort.dystopo.routing.ShadeText.minutes(timeS / 60)
}
