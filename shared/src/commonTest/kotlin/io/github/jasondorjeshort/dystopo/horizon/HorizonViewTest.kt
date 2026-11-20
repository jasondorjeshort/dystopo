package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.Horizon
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
        assertEquals(listOf(HorizonLayer.SATELLITES, HorizonLayer.PEAKS, HorizonLayer.SUN), d.on)
        val noSats = d.with(HorizonLayer.SATELLITES, false)
        assertFalse(noSats.satellites)
        assertTrue(noSats.peaks && noSats.sun)
        assertEquals(mapOf("satellites" to false, "peaks" to true, "sun" to true), noSats.json())
        assertEquals(d, noSats.with(HorizonLayer.SATELLITES, true))
        assertEquals(HorizonLayers(false, false, true), HorizonLayers.parse("sun"))
        assertEquals(HorizonLayers(true, false, true), HorizonLayers.parse(" Satellites,sun "))
        assertEquals(d, HorizonLayers.parse("all"))
        assertEquals(emptyList(), HorizonLayers.parse("none")!!.on)
        assertNull(HorizonLayers.parse("satellites,moon"))
        assertEquals(HorizonLayer.PEAKS, HorizonLayer.of("PEAKS"))
        assertEquals(listOf("Satellites", "Peaks", "Sun"), HorizonLayer.entries.map { it.label })
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
}
