package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.aircraft.AircraftFetch
import io.github.jasondorjeshort.dystopo.aircraft.AircraftFixtures
import io.github.jasondorjeshort.dystopo.aircraft.AircraftGeo
import io.github.jasondorjeshort.dystopo.aircraft.AircraftNow
import io.github.jasondorjeshort.dystopo.aircraft.AircraftPoll
import io.github.jasondorjeshort.dystopo.aircraft.AircraftReport
import io.github.jasondorjeshort.dystopo.aircraft.AircraftRules
import io.github.jasondorjeshort.dystopo.aircraft.AircraftTracker
import io.github.jasondorjeshort.dystopo.aircraft.RouteTable
import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.HorizonProfile
import io.github.jasondorjeshort.dystopo.dem.Peak
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.dem.TerrainFixture
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.nav.Sun
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T308: the Horizon view's shared pieces: the layer state, the sun's path and its terrain times (equal to the turn-around
 * clock's, which reaches [Sun.onTerrain] through [ShadeTiles.pointAnswer]), and the peaks the rim names (on the skyline
 * only, one per 30 degree sector), with [Viewshed.peaks] on the plot's own [io.github.jasondorjeshort.dystopo.dem.HorizonProfile].
 */
class HorizonViewTest {
    // Paradise on Rainier (a public place in areas.json).
    private val lat = 46.786
    private val lon = -121.735

    @Test
    fun theLayersAreAllOnByDefaultAndEachTurnsAlone() {
        val d = HorizonLayers.DEFAULT
        assertEquals(listOf(HorizonLayer.SATELLITES, HorizonLayer.PEAKS, HorizonLayer.SUN, HorizonLayer.FLIGHTS), d.on)
        val noSats = d.with(HorizonLayer.SATELLITES, false)
        assertFalse(noSats.satellites)
        assertTrue(noSats.peaks && noSats.sun)
        assertEquals(mapOf("satellites" to false, "peaks" to true, "sun" to true, "flights" to true), noSats.json())
        assertEquals(d, noSats.with(HorizonLayer.SATELLITES, true))
        assertEquals(HorizonLayers(false, false, true, false), HorizonLayers.parse("sun"))
        assertEquals(HorizonLayers(true, false, true, false), HorizonLayers.parse(" Satellites,sun "))
        assertEquals(HorizonLayers(false, false, false, true), HorizonLayers.parse("flights"))
        assertEquals(d, HorizonLayers.parse("all"))
        assertEquals(emptyList(), HorizonLayers.parse("none")!!.on)
        assertNull(HorizonLayers.parse("satellites,moon"))
        assertEquals(HorizonLayer.PEAKS, HorizonLayer.of("PEAKS"))
        assertEquals(listOf("Satellites", "Peaks", "Sun", "Flights"), HorizonLayer.entries.map { it.label })
        // T312: the Flights button and layer only while Flight tracking is on; the stored choice is kept while it is off.
        assertEquals(listOf(HorizonLayer.SATELLITES, HorizonLayer.PEAKS, HorizonLayer.SUN), HorizonLayers.buttons(false))
        assertEquals(HorizonLayer.entries, HorizonLayers.buttons(true))
        assertFalse(d.effective(false).flights)
        assertEquals(d, d.effective(true))
    }

    @Test
    fun theSunsPathOverAFlatHorizonRunsFromSunriseToSunset() {
        val s = SunPath.of(lat, lon, 2026, 11, 19, null)
        assertFalse(s.onTerrain)
        val flat = s.terrain.flat
        val rise = assertNotNull(flat.sunriseMs)
        val set = assertNotNull(flat.sunsetMs)
        assertTrue(s.path.all { it.altitudeDeg >= SunPath.LOWEST_DEG })
        assertTrue(s.path.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs == SunPath.STEP_MS }, "one unbroken arc, every five minutes")
        assertTrue(abs(s.path.first().timeMs - rise) < 15 * 60_000L, "the arc starts at sunrise")
        assertTrue(abs(s.path.last().timeMs - set) < 15 * 60_000L, "and ends at sunset")
        val top = s.path.maxBy { it.altitudeDeg }
        // Mid-November at 46.8 N: the noon sun about 90 - 46.8 - 19.5 = 23.7 degrees up, due south.
        assertEquals(23.7, top.altitudeDeg, 1.0)
        assertEquals(180.0, top.azimuthDeg, 2.0)
        assertTrue(abs(top.timeMs - flat.noonMs) <= SunPath.STEP_MS)
        assertTrue(s.path.filter { it.altitudeDeg > 0.5 }.all { it.clear }, "nothing blocks a flat horizon")
        // The flat horizon stands in for the terrain: its times are within a minute of the almanac's.
        assertTrue(abs(s.terrain.sunsetMs!! - set) < 90_000L, "${s.terrain.sunsetMs} against $set")
        // Hour marks fall on whole hours of the clock given (UTC-8 here) and only while the sun is up.
        val marks = SunPath.hourMarks(s, lat, lon, -8 * 3_600_000L, null)
        assertTrue(marks.size in 8..10, "${marks.size} hour marks")
        assertTrue(marks.all { (it.timeMs - 8 * 3_600_000L) % 3_600_000L == 0L && it.altitudeDeg >= 0 })
    }

    @Test
    fun theViewsTerrainSunsetOverARidgeIsThePointAnswers() {
        // A ridge 500 m high, its crest 2.5 km west of the point: the sun leaves early in the afternoon.
        val f = TerrainFixture(lat, lon) { e, _ -> 500 * exp(-((e + 2500) / 400).let { it * it }) }
        val terrain = Horizon.terrainAround(lat, lon, 14) { w -> f.grid(w) }
        val profile = Horizon.sweep(terrain, lat, lon, observerHeightM = Horizon.EYE_HEIGHT_M)
        val view = SunPath.of(lat, lon, 2026, 11, 19, profile::angleAt)
        assertTrue(view.onTerrain)
        val answer = ShadeTiles.pointAnswer(terrain, lat, lon, view.terrain.flat.noonMs)
        val sunset = assertNotNull(view.terrain.sunsetMs)
        assertEquals(answer.day.sunsetMs, sunset, "the same terrain sunset as the turn-around clock's")
        assertEquals(answer.day.sunriseMs, view.terrain.sunriseMs)
        val earlier = (view.terrain.flat.sunsetMs!! - sunset) / 60_000.0
        assertTrue(earlier > 30, "the ridge takes the sun $earlier min early")
        // The arc is clear before the terrain sunset and behind the ridge after it (a sample's centre, a minute's slack).
        assertTrue(view.path.filter { it.timeMs in view.terrain.flat.noonMs..sunset - 120_000L }.all { it.clear })
        assertTrue(view.path.filter { it.timeMs > sunset + 120_000L }.none { it.clear })
    }

    /** Metres east and north of the fixture's centre to a lat, lon. */
    private fun TerrainFixture.latLon(east: Double, north: Double): Pair<Double, Double> {
        val z = 20
        val (x, y) = at(z, east, north)
        return WebMercator.yToLat(y / 256, z) to WebMercator.xToLon(x / 256, z)
    }

    @Test
    fun peaksAreTestedOnThePlotsOwnHorizon() {
        // A ridge 200 m high 1 km east with a knob 3 km east behind it (hidden), and a dome 4.5 km west, 700 m (on the skyline).
        val f = TerrainFixture(lat, lon) { e, n ->
            fun cone(ce: Double, cn: Double, h: Double, rad: Double) = (h * (1 - sqrt((e - ce) * (e - ce) + (n - cn) * (n - cn)) / rad)).coerceAtLeast(0.0)
            200 * exp(-((e - 1000) / 150).let { it * it }) + cone(3000.0, 0.0, 150.0, 400.0) + cone(-4500.0, 0.0, 700.0, 1500.0)
        }
        val terrain = Horizon.terrainAround(lat, lon, 14) { w -> f.grid(w) }
        val profile = Horizon.sweep(terrain, lat, lon, observerHeightM = Horizon.EYE_HEIGHT_M)
        val (eLat, eLon) = f.latLon(3000.0, 0.0)
        val (wLat, wLon) = f.latLon(-4500.0, 0.0)
        val (nLat, nLon) = f.latLon(0.0, 40_000.0)
        val views = Viewshed.peaks(terrain, profile, listOf(Peak("East Knob", "peak", eLat, eLon, 150.0), Peak("West Dome", "volcano", wLat, wLon, null), Peak("Far Peak", "peak", nLat, nLon, 500.0)))
        assertEquals(listOf("East Knob", "West Dome"), views.map { it.peak.name }, "beyond the plot's 30 km range dropped")
        assertFalse(views[0].visible)
        assertTrue(views[1].skyline)
        assertEquals(270.0, views[1].bearingDeg, 0.5)
        assertEquals(listOf("West Dome"), PeakRim.skyline(views).map { it.peak.name })
        assertEquals(listOf("West Dome"), PeakRim.candidates(views).map { it.peak.name }, "the knob, hidden far more than 60 m, is no candidate")
        assertEquals(listOf("West Dome"), PeakRim.labels(views).map { it.name })
    }

    /** A peak [distance] m out at [bearing], [hiddenM] metres out of sight (0: in sight), on the skyline or not. */
    private fun view(name: String, bearing: Double, ele: Double, distance: Double = 5000.0, hiddenM: Double = 0.0, skyline: Boolean = hiddenM == 0.0, angle: Double = 3.0, clear: Double = 1.0): PeakView {
        val clearance = if (hiddenM == 0.0) clear else -kotlin.math.atan(hiddenM / distance) * 180 / kotlin.math.PI
        return PeakView(Peak(name, "peak", 0.0, 0.0, ele), distance, bearing, "", ele, false, angle, hiddenM == 0.0, clearance, skyline)
    }

    // A 400 px plot at 1 px per dp, north up; names 6 px a letter, 9 px above the baseline and 3 below.
    private val g = RimGeometry.of(400.0, 400.0, 1.0, 0.0)
    private val width = { s: String -> 6.0 * s.length }
    private fun placed(peaks: List<PeakView>, geom: RimGeometry = g, blocked: List<RimBox> = emptyList(), lines: List<List<RimPt>> = emptyList()) =
        HorizonLabels.place(geom, HorizonLabels.rank(PeakRim.labels(peaks)), width, -9.0, 3.0, blocked, lines)
    private fun names(peaks: List<PeakView>, geom: RimGeometry = g) = placed(peaks, geom).map { it.label.name }

    @Test
    fun justOutOfSightIsSixtyMetresOfHeightNotAnAngle() {
        assertEquals(-45.0, PeakRim.hiddenLimitDeg(60.0), 1e-9)
        assertEquals(-0.1375, PeakRim.hiddenLimitDeg(25_000.0), 1e-3)
        val near = view("Near Knob", 10.0, 1900.0, distance = 800.0, hiddenM = 40.0)
        val deep = view("Deep Knob", 100.0, 1900.0, distance = 800.0, hiddenM = 200.0)
        val far = view("Far Hidden", 200.0, 1900.0, distance = 25_000.0, hiddenM = 59.0)
        val open = PeakView(Peak("No Blocker", "peak", 0.0, 0.0, 10.0), 50.0, 0.0, "", 10.0, false, 1.0, false, 90.0, false)
        assertEquals(listOf("No Blocker", "Near Knob", "Far Hidden"), PeakRim.candidates(listOf(far, deep, near, open)).map { it.peak.name }, "closest first; 200 m hidden left out")
        assertTrue(PeakRim.inSight(open))
        assertFalse(PeakRim.inSight(near))
        val labels = PeakRim.labels(listOf(near, far))
        assertTrue(labels.all { it.outOfSight && it.kind == LabelKind.PEAK })
    }

    @Test
    fun aClosePeakJustOutOfSightOutranksAFartherLowerOne() {
        val summit = view("The Summit", 200.0, 4000.0, distance = 15_000.0, angle = 12.0)
        val farLow = view("Far Low", 12.0, 1800.0, distance = 20_000.0, angle = 2.0, clear = 0.3)
        val near = view("Near Knob", 10.0, 1900.0, distance = 800.0, hiddenM = 40.0, angle = 2.0)
        val deep = view("Deep Knob", 11.0, 1950.0, distance = 600.0, hiddenM = 200.0)
        val ranked = HorizonLabels.rank(PeakRim.labels(listOf(summit, farLow, near, deep)))
        assertEquals(listOf("The Summit", "Near Knob", "Far Low"), ranked.map { it.label.name }, "the summit first, then the near knob; 200 m hidden left out")
        assertTrue(ranked[0].score > HorizonLabels.DOMINANT_BONUS)
        // Two degrees apart, the knob holds the stretch: the far one is inside its density gap and goes to the list.
        assertEquals(listOf("The Summit", "Near Knob"), names(listOf(summit, farLow, near, deep)))
    }

    @Test
    fun aVeryClosePeakJustOutOfSightIsNamedBesideTheSummitAndVisiblePeaks() {
        // T309's fix round (Alta Vista from Paradise): 0.7 km off and 11 m out of sight, beside a summit and visible peaks at 3 to 7 km.
        val summit = view("The Summit", 350.0, 4300.0, distance = 7500.0, angle = 20.0)
        val knoll = view("Close Knoll", 2.0, 1800.0, distance = 700.0, hiddenM = 11.0, angle = 13.0)
        val visible = (0 until 6).map { view("Visible $it", 5.0 + it * 4.0, 2000.0 + it * 50, distance = 3000.0 + it * 800, angle = 12.0, clear = 1.4) }
        val ps = placed(listOf(summit, knoll) + visible)
        val names = ps.map { it.label.name }
        assertTrue("Close Knoll" in names, "the close knoll named: $names")
        assertTrue("The Summit" in names, "and the summit: $names")
        assertTrue(ps.first { it.label.name == "Close Knoll" }.label.outOfSight)
        for (n in ps) assertTrue(ps.none { it !== n && it.box.overlaps(n.box) } || n.label.dominant || n.label.name == "Close Knoll", "${n.label.name} over another name")
    }

    @Test
    fun theScoreFavoursCloserHigherAboveTheHorizonAndProminent() {
        val ctx = HorizonLabels.Context(1000.0, 3000.0)
        fun l(d: Double = 5000.0, h: Double = 1.0, a: Double = 3.0, ele: Double = 2000.0, prom: Double = 100.0) =
            HorizonLabel("x", LabelKind.PEAK, 0.0, a, d, h, ele, prom)
        val base = HorizonLabels.score(l(), ctx).score
        assertTrue(HorizonLabels.score(l(d = 1000.0), ctx).score > base)
        assertTrue(HorizonLabels.score(l(h = 1.4), ctx).score > base)
        assertTrue(HorizonLabels.score(l(a = 5.0), ctx).score > base)
        assertTrue(HorizonLabels.score(l(prom = 500.0), ctx).score > base)
        assertTrue(HorizonLabels.score(l(ele = 2900.0), ctx).score > base)
        assertTrue(HorizonLabels.score(l(h = -0.2), ctx).score < HorizonLabels.score(l(h = 1.4), ctx).score, "just out of sight counts less than well in view")
        assertEquals(100.0, HorizonLabels.prominence(2100.0, listOf(1900.0, 2000.0, 2500.0)), 1e-9)
        assertEquals(0.0, HorizonLabels.prominence(1800.0, listOf(1900.0, 2000.0)), 1e-9)
    }

    @Test
    fun theHighestSkylinePeakIsNamedEvenWithACloserCandidateBesideIt() {
        val giant = view("Mount Giant", 20.0, 4300.0, distance = 14_000.0, angle = 14.0)
        val mid = view("Mid Hill", 21.0, 1800.0, distance = 3000.0, skyline = false, angle = 14.0)
        val close = view("Close Hill", 60.0, 1500.0, distance = 900.0, angle = 4.0)
        assertEquals(listOf("Mount Giant", "Close Hill"), names(listOf(mid, close, giant)), "the summit keeps its own; the hill beside it is inside its gap")
        // Even where every place is taken by a ring or hour label, the summit and the closest candidate are drawn.
        val everywhere = listOf(RimBox(-1e4, -1e4, 1e4, 1e4))
        assertEquals(listOf("Mount Giant", "Close Hill"), placed(listOf(mid, close, giant), blocked = everywhere).map { it.label.name })
    }

    @Test
    fun aCrowdedStretchOfTheRimGetsWiderGapsThanASparseOne() {
        // A big plot, so the boxes leave the density gap to decide: twenty peaks over 30 degrees in the north, three in the south.
        val big = RimGeometry.of(4000.0, 4000.0, 1.0, 0.0)
        val crowd = (0 until 20).map { view("C$it", 345.0 + it * 1.5, 1500.0, distance = 3000.0 + it * 10) }
        val sparse = (0 until 3).map { view("S$it", 175.0 + it * 3.5, 1500.0, distance = 3000.0 + it * 10) }
        val p = placed(crowd + sparse, big)
        val inCrowd = p.filter { it.label.name.startsWith("C") }
        val inSparse = p.filter { it.label.name.startsWith("S") }
        assertEquals(3, inSparse.size, "an empty stretch takes close neighbours")
        assertTrue(inCrowd.size in 2..10, "${inCrowd.size} of 20 named in the crowd")
        assertTrue(inCrowd.minOf { it.gapDeg } > inSparse.maxOf { it.gapDeg })
        fun minApart(ps: List<HorizonLabels.Placed>) = ps.flatMap { a -> ps.filter { it !== a }.map { HorizonLabels.apart(a.label.bearingDeg, it.label.bearingDeg) } }.min()
        assertTrue(minApart(inCrowd) > minApart(inSparse), "${minApart(inCrowd)} against ${minApart(inSparse)}")
        assertEquals(HorizonLabels.GAP_MIN_DEG, HorizonLabels.gapDeg(1))
    }

    @Test
    fun namesSpreadAroundTheRim() {
        // A crowd of close peaks in the first sector and a far one in every other: the crowd may not take the plot.
        val crowd = (0 until 12).map { view("Crowd $it", 2.0 + it * 2.3, 1500.0, distance = 1000.0 + it * 50) }
        val spread = (1 until 12 step 2).map { view("Far $it", it * 30.0 + 15, 2000.0, distance = 20_000.0) }
        val placed = names(crowd + spread)
        for (s in spread) assertTrue(s.peak.name in placed, "${s.peak.name} named: $placed")
        assertTrue(placed.count { it.startsWith("Crowd") } in 2..6, "the crowd thinned, not emptied: $placed")
    }

    @Test
    fun noNameCrossesARingLabelAnHourLabelOrTheSunsArc() {
        // Names all round the rim, and a winter sun low in the south with hour labels on its dots.
        val peaks = (0 until 36).map { view("Peak number $it", it * 10.0 + 3, 1500.0 + it, distance = 1000.0 + it * 300) }
        val arc = (100..260 step 5).map { az -> g.at(18 * kotlin.math.sin((az - 100) * kotlin.math.PI / 160), az.toDouble()) }
        val hours = (110..250 step 20).map { az -> g.hourLabel(18 * kotlin.math.sin((az - 100) * kotlin.math.PI / 160), az.toDouble(), 12.0, -8.0, 2.0) }
        val rings = listOf(0.0, 30.0, 60.0).map { el -> g.ringLabel(el, 18.0, -9.0, 3.0) }
        val free = placed(peaks)
        assertTrue(free.any { n -> (rings + hours).any { it.overlaps(n.box) } || n.box.crosses(arc) }, "without them some name would sit on one")
        val ps = placed(peaks, blocked = rings + hours, lines = listOf(arc))
        assertTrue(ps.size >= 6, "${ps.size} named")
        for (n in ps) {
            assertTrue((rings + hours).none { it.overlaps(n.box) } || n.label.dominant, "${n.label.name} over a label")
            assertTrue(!n.box.crosses(arc) || n.label.dominant, "${n.label.name} on the sun's arc")
            assertTrue(ps.none { it !== n && it.box.overlaps(n.box) }, "${n.label.name} over another name")
        }
    }

    @Test
    fun aNameTriesTheSidesOfItsTriangleBeforeGoingFartherInAndGetsALeaderWhenFar() {
        // A ring of labels: the inward place of one blocked, the name goes beside its triangle (close, no leader).
        val peaks = listOf(view("Solo Peak", 90.0, 2000.0, distance = 3000.0, angle = 2.0))
        val at = g.at(2.0, 90.0)
        val inwardBox = RimBox(at.x - 25, at.y - 5, at.x - 10, at.y + 7)
        val p = placed(peaks, blocked = listOf(inwardBox)).single()
        assertNull(p.leader, "beside its triangle: ${p.box}")
        assertTrue(p.box.top > at.y || p.box.bottom < at.y, "${p.box} beside $at")
        // Blocked inward and beside but not farther in: placed farther in, with a leader line from the triangle.
        val besides = listOf(RimBox(at.x - 1, at.y - 100, at.x + 50, at.y - 3), RimBox(at.x - 1, at.y + 3, at.x + 50, at.y + 100), RimBox(at.x - 16, at.y - 5, at.x + 8, at.y + 7))
        val far = placed(peaks, blocked = besides).single()
        val leader = assertNotNull(far.leader, "far from its triangle: ${far.box}")
        assertEquals(at, leader[0])
        // No name crosses another's leader, and no leader crosses another name.
        val ring = (0 until 36).map { view("Peak number $it", it * 10.0 + 3, 1500.0 + it, distance = 1000.0 + it * 300) }
        val ps = placed(ring, blocked = (0 until 36).map { g.at(2.0, it * 10.0 + 3).let { a -> RimBox(a.x - 1, a.y - 1, a.x + 1, a.y + 1) } })
        for (n in ps) for (o in ps) if (n !== o) {
            n.leader?.let { assertFalse(o.box.crosses(it), "${n.label.name}'s leader over ${o.label.name}") }
        }
    }

    @Test
    fun aBoxCrossesALineThroughItButNotOneBeside() {
        val b = RimBox(0.0, 0.0, 10.0, 10.0)
        assertTrue(b.crosses(listOf(RimPt(-5.0, 5.0), RimPt(15.0, 5.0))), "straight through")
        assertTrue(b.crosses(listOf(RimPt(-5.0, -4.0), RimPt(15.0, 14.0))), "corner to corner")
        assertFalse(b.crosses(listOf(RimPt(-5.0, 12.0), RimPt(15.0, 30.0))), "below it")
        assertFalse(b.crosses(listOf(RimPt(12.0, -5.0), RimPt(30.0, 5.0))), "beside it")
        assertTrue(b.crosses(listOf(RimPt(5.0, 5.0))), "a point inside")
    }

    @Test
    fun apartAndSkyline() {
        assertEquals(0.2, HorizonLabels.apart(359.9, 0.1), 1e-9)
        assertEquals(180.0, HorizonLabels.apart(90.0, 270.0), 1e-9)
        val peaks = listOf(view("B", 90.0, 1.0), view("A", 10.0, 1.0), view("Off", 50.0, 1.0, skyline = false))
        assertEquals(listOf("A", "B"), PeakRim.skyline(peaks).map { it.peak.name })
    }

    // --- T312: the Flights layer ---

    /** A profile from Paradise at [groundM] (eye height 0): every bearing [angle] degrees up, its ridge [ridgeM] away. */
    private fun profile(angle: Double, ridgeM: Double, groundM: Double = 0.0) =
        HorizonProfile(lat, lon, groundM, 0.0, 1.0, 30_000.0, DoubleArray(360) { angle }, DoubleArray(360) { ridgeM }, DoubleArray(360) { 30_000.0 }, 0, 0)

    private fun report(hex: String, bearing: Double, distanceM: Double, altM: Double, call: String? = hex.uppercase(), seenPos: Double = 0.0, ground: Boolean = false): AircraftReport {
        val (la, lo) = AircraftGeo.move(lat, lon, bearing, distanceM)
        return AircraftReport(hex, call, null, null, null, la, lo, altM / AircraftRules.FEET_TO_M, null, ground, 0.0, 0.0, 0.0, seenPos)
    }

    /** The tracker's view of [reports] polled at [atMs], seen [laterMs] after, the undulation 0 (geometric heights as given). */
    private fun now(reports: List<AircraftReport>, atMs: Long = T0, laterMs: Long = 0, routes: RouteTable? = null): List<AircraftNow> {
        val t = AircraftTracker()
        t.onFetch(AircraftFetch.Ok(AircraftPoll(atMs, reports), 100, null), atMs, 0.0)
        return t.view(atMs + laterMs, null, routes)
    }

    private fun flights(v: List<AircraftNow>, p: HorizonProfile?, range: SkyRange = SkyRange.ALL, at: Long = T0) =
        HorizonFlights.of(v, p?.let { HorizonFlights.Eye.of(it) } ?: HorizonFlights.Eye(lat, lon, 0.0), p, range, at)

    @Test
    fun anAircraft10KmUp50KmAwayStandsAtItsHandComputedAngle() {
        // Seen from sea level over a flat horizon: atan2((R+h) cos θ - R, (R+h) sin θ), θ = 50 km / R = 0.0078480 rad:
        // (R+h) cos θ - R = 9803.5 m, (R+h) sin θ = 50078.5 m, atan(0.19576) = 11.076° (a flat earth would say 11.310°).
        val f = flights(now(listOf(report("a1", 90.0, 50_000.0, 10_000.0))), profile(0.0, 0.0)).single()
        assertEquals(11.076, f.elevationDeg, 2e-3)
        assertEquals(90.0, f.bearingDeg, 0.01)
        assertEquals(50_000.0, f.distanceM, 1.0)
        assertFalse(f.hollow)
        // The eye is the profile's (its ground plus eye height): from 1650 m the same aircraft stands at 9.2475° (T311's hand case).
        assertEquals(9.2475, flights(now(listOf(report("a1", 90.0, 50_000.0, 10_000.0))), profile(0.0, 0.0, groundM = 1650.0)).single().elevationDeg, 2e-3)
    }

    @Test
    fun belowTheSkylineBeyondTheRidgeIsHollowNearerThanTheRidgeIsSolid() {
        // A skyline 5° up all round, its ridge 10 km out. 30 km out at 1118 m: about 2° up and behind the ridge: hidden.
        // 5 km out at 177 m (a helicopter): also about 2° up, below the ridge line, but in front of the ridge: in sight.
        val p = profile(5.0, 10_000.0)
        val fs = flights(now(listOf(report("far", 0.0, 30_000.0, 1118.0), report("heli", 180.0, 5_000.0, 177.0), report("above", 90.0, 30_000.0, 4000.0))), p)
        val far = fs.first { it.hex == "far" }
        val heli = fs.first { it.hex == "heli" }
        val above = fs.first { it.hex == "above" }
        assertTrue(far.elevationDeg < 5.0 && heli.elevationDeg < 5.0, "both below the skyline: ${far.elevationDeg}, ${heli.elevationDeg}")
        assertTrue(far.hidden && far.hollow, "below the skyline and beyond the ridge: hollow")
        assertFalse(heli.hidden || heli.hollow, "below the ridge line but nearer than the ridge: solid")
        assertFalse(above.hollow, "above the skyline: solid (${above.elevationDeg}°)")
        assertEquals("heli", fs.first().hex, "closest first")
        // The test's pieces: the refraction lifts a target 30 km out by k θ / 2 = 0.018°; the ridge's distance decides.
        assertEquals(0.0175, HorizonFlights.refractionDeg(30_000.0), 1e-3)
        assertTrue(HorizonFlights.hiddenByTerrain(p, 0.0, 4.9, 10_001.0))
        assertFalse(HorizonFlights.hiddenByTerrain(p, 0.0, 4.9, 9_999.0))
        assertFalse(HorizonFlights.hiddenByTerrain(p, 0.0, 5.1, 20_000.0))
        // Without a profile nothing is hidden; without an eye nothing is drawn.
        assertFalse(flights(now(listOf(report("far", 0.0, 30_000.0, 1118.0))), null).single().hollow)
        assertEquals(emptyList(), HorizonFlights.of(now(listOf(report("far", 0.0, 30_000.0, 1118.0))), null, p, SkyRange.ALL, T0))
    }

    @Test
    fun aStalePositionIsHollow() {
        val p = profile(0.0, 0.0)
        val fresh = flights(now(listOf(report("a1", 90.0, 20_000.0, 9000.0, seenPos = 2.0)), laterMs = 20_000), p, at = T0 + 20_000).single()
        assertFalse(fresh.stale || fresh.hollow, "22 s old: solid")
        val stale = flights(now(listOf(report("a1", 90.0, 20_000.0, 9000.0, seenPos = 2.0)), laterMs = 35_000), p, at = T0 + 35_000).single()
        assertTrue(stale.stale && stale.hollow && !stale.hidden, "37 s old (over T311's 30 s): hollow, though in sight")
    }

    @Test
    fun theTailIsTheLast90Seconds() {
        // Polled every 10 s for 3 minutes, flying east at 300 kt 20 km north of the point.
        val t = AircraftTracker()
        val (la0, lo0) = AircraftGeo.move(lat, lon, 0.0, 20_000.0)
        for (i in 0..18) {
            val (la, lo) = AircraftGeo.move(la0, lo0, 90.0, 300 * AircraftRules.KNOT_TO_MPS * 10.0 * i)
            val r = AircraftReport("a1", "ASA1", null, null, null, la, lo, 30_000.0, null, false, 90.0, 300.0, 0.0, 0.0)
            t.onFetch(AircraftFetch.Ok(AircraftPoll(T0 + i * 10_000L, listOf(r)), 100, null), T0 + i * 10_000L, 0.0)
        }
        val at = T0 + 185_000L
        val f = flights(t.view(at, null, null), profile(0.0, 0.0), at = at).single()
        assertTrue(f.tailS in 60.0..HorizonFlights.TAIL_S, "the tail spans 60 to 90 s: ${f.tailS}")
        assertEquals(10, f.tail.size, "nine polls within 90 s and the moved-up head")
        assertEquals(f.bearingDeg, f.tail.last().bearingDeg, 1e-6, "the tail ends at the dot")
        assertTrue(f.tail.first().bearingDeg < f.bearingDeg, "and trails behind it (flying east, north of the point)")
    }

    @Test
    fun aircraftOnTheGroundAreLeftOut() {
        val all = now(listOf(report("taxi", 0.0, 8_000.0, 120.0, ground = true), report("air", 0.0, 8_000.0, 2000.0)))
        assertEquals(listOf("air"), flights(all, profile(0.0, 0.0)).map { it.hex })
        assertEquals(0, HorizonFlights.unplaced(all))
    }

    @Test
    fun theLabelIsCallSignAndDestinationCode() {
        // ASA1409 flies PDX > SEA in the fixture's table; one between them heading north is on its route.
        val table = RouteTable.parse(AircraftFixtures.table().encodeToByteArray())
        val onRoute = AircraftReport("b1", "ASA1409", null, null, null, 46.52, -122.45, 20_000.0, null, false, 6.0, 400.0, 0.0, 0.0)
        val fs = flights(now(listOf(onRoute, report("c1", 0.0, 9000.0, 3000.0, call = "N123AB")), routes = table), profile(0.0, 0.0))
        assertEquals("ASA1409 > SEA", fs.first { it.hex == "b1" }.label)
        assertEquals("N123AB", fs.first { it.hex == "c1" }.label, "no plausible route: the call sign alone")
    }

    @Test
    fun theSkyRangeFilters() {
        // Overhead: 10 km up 4 km away (68°); low and near: 30 km away at 2 km (3.5°); far: 120 km away at 11 km (4.7°).
        val v = now(listOf(report("over", 0.0, 4_000.0, 10_000.0), report("near", 90.0, 30_000.0, 2000.0), report("far", 180.0, 120_000.0, 11_000.0)))
        fun hexes(r: SkyRange) = flights(v, profile(0.0, 0.0), r).map { it.hex }
        assertEquals(listOf("over"), hexes(SkyRange.OVERHEAD), "above 45°")
        assertEquals(listOf("over", "near"), hexes(SkyRange.NEAR), "within 50 km")
        assertEquals(listOf("over", "near", "far"), hexes(SkyRange.ALL))
        assertEquals(SkyRange.ALL, SkyRange.DEFAULT)
        assertEquals(SkyRange.NEAR, SkyRange.of(" Near "))
        assertNull(SkyRange.of("far"))
        assertEquals(listOf("Overhead", "Near", "All"), SkyRange.entries.map { it.label })
        assertTrue(SkyRange.OVERHEAD.admits(45.0, 1e6) && !SkyRange.OVERHEAD.admits(44.9, 0.0))
        assertTrue(SkyRange.NEAR.admits(0.0, 50_000.0) && !SkyRange.NEAR.admits(89.0, 50_001.0))
    }

    @Test
    fun peakNamesStayPutWhileAircraftMoveAndNoAircraftNameCoversOne() {
        val peaks = listOf(
            view("Summit", 10.0, 4300.0, distance = 12_000.0, angle = 12.0), view("Ridge", 60.0, 2400.0, distance = 6000.0),
            view("Knob", 100.0, 2000.0, distance = 3000.0), view("Dome", 200.0, 2600.0, distance = 9000.0), view("Spire", 300.0, 2800.0, distance = 15_000.0),
        )
        val ranked = HorizonLabels.rank(PeakRim.labels(peaks))
        val alone = HorizonLabels.place(g, ranked, width, -9.0, 3.0)
        fun pass(shiftDeg: Double): HorizonLabels.Layers {
            // Six aircraft, two of them right by peaks, moved by [shiftDeg] of bearing between the polls.
            val v = now(listOf(0.0, 15.0, 62.0, 140.0, 205.0, 250.0).mapIndexed { i, b -> report("f$i", b + shiftDeg, 20_000.0 + 3000.0 * i, 6000.0, call = "FLT$i") })
            return HorizonLabels.placeLayers(g, ranked, HorizonLabels.rank(HorizonFlights.labels(flights(v, profile(0.0, 0.0)))), width, -9.0, 3.0)
        }
        val a = pass(0.0)
        val b = pass(7.0)
        fun sig(l: List<HorizonLabels.Placed>) = l.map { Triple(it.label.name, it.x, it.y) }
        assertTrue(alone.size >= 3, "peaks named: ${alone.map { it.label.name }}")
        assertEquals(sig(alone), sig(a.peaks), "the first pass is the peaks alone")
        assertEquals(sig(a.peaks), sig(b.peaks), "peak names unchanged across two polls with moving aircraft")
        assertTrue(a.aircraft.isNotEmpty() && b.aircraft.isNotEmpty(), "aircraft named too")
        assertTrue(a.aircraft.all { it.label.kind == LabelKind.AIRCRAFT })
        for (l in listOf(a, b)) for (x in l.aircraft) assertTrue(l.peaks.none { it.box.overlaps(x.box) || it.mark.overlaps(x.box) }, "${x.label.name} covers no peak name or triangle")
        assertEquals(sig(alone), sig(HorizonLabels.placeLayers(g, ranked, emptyList(), width, -9.0, 3.0).peaks))
    }

    @Test
    fun flightNamesKeepOffTheRingLabelsTheCompassLettersAndTheNorthMark() {
        // Flights right by the 60° ring label (drawn at 45° on screen), under N on the rim, by E and by S.
        fun f(name: String, bearing: Double, angle: Double, d: Double) = HorizonLabel(name, LabelKind.AIRCRAFT, bearing, angle, d, 90.0, 9000.0, 0.0)
        val flights = listOf(f("RING60", 45.0, 61.0, 5000.0), f("NORTH1", 0.0, 1.0, 9000.0), f("NORTH2", 4.0, 3.0, 12000.0), f("EAST1", 90.0, 1.0, 15000.0), f("SOUTH1", 180.0, 1.5, 20000.0))
        val letter = { s: String -> 9.0 * s.length }
        val compass = g.compassBoxes(letter, -11.0, 4.0)
        val rings = listOf(0.0 to "0°", 30.0 to "30°", 60.0 to "60°").map { (el, l) -> g.ringLabel(el, width(l), -9.0, 3.0) }
        val out = HorizonLabels.placeLayers(g, emptyList(), HorizonLabels.rank(flights), width, -9.0, 3.0, blocked = rings, aircraftBlocked = compass)
        assertTrue(out.aircraft.size >= 3, "flights named: ${out.aircraft.map { it.label.name }}")
        for (p in out.aircraft) {
            assertTrue(rings.none { it.overlaps(p.box) }, "${p.label.name} off the ring labels")
            assertTrue(compass.none { it.overlaps(p.box) }, "${p.label.name} off N E S W and the north mark")
        }
        // One flight at a time near N (bearings 3 to 30°, 0.5 to 5° up; ABX404 > CVG on the emulator stood over N so):
        // without the compass boxes some name takes a place over N or its mark; with them none does.
        val near = (1..10).flatMap { i -> listOf(0.5, 2.0, 4.0, 5.0).map { e -> f("ABX404 > CVG", 3.0 * i, e, 70_000.0) } }
        fun one(l: HorizonLabel, blocks: List<RimBox>) = HorizonLabels.placeLayers(g, emptyList(), HorizonLabels.rank(listOf(l)), width, -9.0, 3.0, blocked = rings, aircraftBlocked = blocks).aircraft
        assertTrue(near.any { l -> one(l, emptyList()).any { p -> compass.any { it.overlaps(p.box) } } }, "the case is one the blocks change")
        assertTrue(near.none { l -> one(l, compass).any { p -> compass.any { it.overlaps(p.box) } } })
        // The boxes stand where SkyPlot draws: N centred above the rim at the top, its mark across the rim.
        val n = g.compassLabel(0.0, 9.0, -11.0, 4.0)
        assertTrue(n.bottom < g.cy - g.radius + 15.0 && abs((n.left + n.right) / 2 - g.cx) < 1e-9)
        assertTrue(g.northMark().contains(RimPt(g.cx, g.cy - g.radius)))
        // Peaks are not affected: the compass boxes go to the second pass only.
        val peaks = HorizonLabels.rank(PeakRim.labels(listOf(view("Top", 2.0, 3000.0, distance = 4000.0, angle = 1.0))))
        assertEquals(
            HorizonLabels.place(g, peaks, width, -9.0, 3.0).map { it.x to it.y },
            HorizonLabels.placeLayers(g, peaks, HorizonLabels.rank(flights), width, -9.0, 3.0, aircraftBlocked = compass).peaks.map { it.x to it.y },
        )
    }

    private companion object {
        const val T0 = 1_000_000L
    }
}
