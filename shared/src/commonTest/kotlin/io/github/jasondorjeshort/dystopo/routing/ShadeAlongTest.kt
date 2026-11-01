package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.TerrainFixture
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.NavPoint
import io.github.jasondorjeshort.dystopo.nav.Sun
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import io.github.jasondorjeshort.dystopo.nav.TurnPace
import io.github.jasondorjeshort.dystopo.render.CastShadow
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T194: terrain shade along a route at the time the walker reaches each point. A synthetic east-west ridge (800 m high,
 * 3 km across, a cosine-squared profile) at 46.8 N: a trail on the flat 1.8 km north of its crest is in its shade at
 * noon in winter (the crest stands 24 degrees up, the sun 19.8) and in sun at noon in summer (66.6 degrees); a trail over
 * the ridge from the north gets the sun near the crest. The stretches, the words and the spoken line's rule on
 * hand-made samples; the hot-day rule; T194's low-sun cap.
 */
class ShadeAlongTest {
    private val ridge = TerrainFixture(46.8, -121.7) { _, n -> if (abs(n) >= 1500) 0.0 else 800.0 * cos(PI * n / 3000).let { it * it } }

    /** One metre a second on any ground: 25 m samples 25 s apart, 20 minutes 1,200 m. */
    private val pace = TurnPace(false) { l, _, _ -> l }

    private fun point(east: Double, north: Double): NavPoint {
        val (gx, gy) = ridge.at(14, east, north)
        return NavPoint(WebMercator.yToLat(gy / 256, 14), WebMercator.xToLon(gx / 256, 14), ridge.heightAt(east, north))
    }

    private fun path(vararg pts: Pair<Double, Double>): NavPath = NavPath("test", pts.map { (e, n) -> point(e, n) })

    private fun compute(p: NavPath, startMs: Long, frozen: Boolean = false): ShadeAlongResult =
        ShadeAlong.compute(p, TurnAround(p, pace), 0.0, startMs, 14, read = ridge::grid, frozen = frozen)

    /** A quarter of an hour before solar noon at the ridge (about 20:07 UTC at 121.7 W in June and December alike), so a 33 minute walk straddles it. */
    private fun noon(year: Int, month: Int, day: Int): Long = Sun.epochDay(year, month, day) * 86_400_000L + (20 * 60 + 7) * 60_000L - 15 * 60_000L

    private val northTrail get() = path(-1000.0 to 1800.0, 1000.0 to 1800.0)

    @Test
    fun aNorthSideTrailIsInTerrainShadeAtNoonInWinter() {
        val r = compute(northTrail, noon(2026, 12, 21))
        assertTrue(r.samples.all { it.light == ShadeLight.SHADE }, "lights ${r.samples.map { it.light }.distinct()}")
        assertEquals(1, r.stretches.size)
        assertEquals(ShadeLight.SHADE, r.stretches[0].light)
        assertTrue(r.stretches[0].open)
        assertEquals(2000.0, r.stretches[0].lengthM, 5.0) // great-circle metres against the fixture's flat ones
        assertEquals(r.stretches[0].lengthM / 60, r.stretches[0].minutes, 0.01) // a metre a second
        assertEquals(81, r.samples.size) // every 25 m, both ends
        assertNull(r.firstSunLoss)
        assertNull(r.firstSunGain)
        assertEquals("In terrain shade for 33 min more", ShadeText.cardLine(r, 0.0, ContourUnits.METERS))
        assertEquals(14, r.stats.zoom)
        assertEquals(11, r.stats.coarseZoom)
    }

    @Test
    fun theSameTrailIsInSunAtNoonInSummer() {
        val r = compute(northTrail, noon(2026, 6, 21))
        assertTrue(r.samples.all { it.light == ShadeLight.SUN })
        assertEquals(listOf(ShadeLight.SUN), r.stretches.map { it.light })
        assertEquals("No terrain shade ahead", ShadeText.cardLine(r, 500.0, ContourUnits.METERS))
        assertEquals(-1, ShadeText.dueSpoken(r, 0.0))
    }

    @Test
    fun aTrailOverTheRidgeFromTheNorthGetsTheSunNearTheCrest() {
        // North to south over the crest at noon in winter: the cast shadow on the flat (it reaches 2.2 km north), the north face turned from the
        // sun, then sun from where the face is no steeper than the sun's 19.8 degrees (the profile's slope is
        // atan(0.838 sin(2 pi n / 3000)): about 212 m north of the crest) over the crest and down the south face.
        val p = path(0.0 to 2100.0, 0.0 to -2000.0)
        val r = compute(p, noon(2026, 12, 21))
        assertEquals(listOf(ShadeLight.SHADE, ShadeLight.SUN), r.stretches.map { it.light })
        val gain = r.firstSunGain!!
        assertEquals(ShadeLight.SUN, gain.light)
        assertTrue(gain.alongM in 1840.0..1935.0, "sun at ${gain.alongM} m (1,888 m expected)")
        assertNull(r.firstSunLoss)
        // The card from the start: in shade for the 2 km or so to it; the spoken line before that sun (it lasts over
        // 20 minutes at a metre a second), within 400 m and once.
        assertEquals("In terrain shade for ${ShadeText.minutes(gain.alongM / 60)} more", ShadeText.cardLine(r, 0.0, ContourUnits.METERS))
        assertEquals(-1, ShadeText.dueSpoken(r, 1000.0))
        assertEquals(1, ShadeText.dueSpoken(r, gain.alongM - 300))
        assertEquals(-1, ShadeText.dueSpoken(r, gain.alongM - 300, saidUntilM = r.stretches[1].toM))
        assertTrue(ShadeText.spoken(r, 1, 300.0, ContourUnits.METERS).startsWith("In 300 m, the terrain shade ends. Then sun to the end, "))
        // Walked the other way after noon, the walker loses the sun on the north face.
        val back = path(0.0 to -2000.0, 0.0 to 2100.0)
        val rb = compute(back, noon(2026, 12, 21))
        assertEquals(listOf(ShadeLight.SUN, ShadeLight.SHADE), rb.stretches.map { it.light })
        assertTrue(rb.firstSunLoss!!.alongM in 2150.0..2270.0, "shade at ${rb.firstSunLoss!!.alongM} m (2,212 m expected)")
        assertEquals("terrain", rb.firstSunLoss!!.json()["cause"])
        assertTrue(ShadeText.cardLine(rb, 0.0, ContourUnits.METERS)!!.matches(Regex("Terrain shade in 2\\.\\d+ km for \\d+ min")), ShadeText.cardLine(rb, 0.0, ContourUnits.METERS))
    }

    @Test
    fun arrivalTimesFollowThePaceAndFrozenSamplesShareTheStart() {
        val t0 = noon(2026, 6, 21)
        val r = compute(northTrail, t0)
        assertEquals(t0, r.samples.first().atMs)
        assertEquals((t0 + 1_000_000L).toDouble(), r.timeAt(1000.0).toDouble(), 2_000.0)
        val f = compute(northTrail, t0, frozen = true)
        assertTrue(f.samples.all { it.atMs == t0 })
        assertTrue(f.frozen)
        // From part-way along: the first sample is there and at the start instant.
        val p = northTrail
        val mid = ShadeAlong.plan(p, TurnAround(p, pace), 1234.0, t0)
        assertEquals(1234.0, mid.first().alongM, 1e-9)
        assertEquals(t0, mid.first().atMs)
        assertEquals(p.totalM, mid.last().alongM, 1e-6)
        assertEquals(t0 + ((p.totalM - 1234.0) * 1000).toLong(), mid.last().atMs)
    }

    // ---- the stretches, the words and the spoken line on hand-made samples ----

    /** Samples every 25 m at a metre a second over runs of (light, metres); the stretches by [ShadeAlong.stretches]. */
    private fun made(vararg runs: Pair<ShadeLight, Double>, t0: Long = 1_800_000_000_000L): ShadeAlongResult {
        val samples = ArrayList<ShadeSample>()
        var at = 0.0
        for ((light, len) in runs) {
            val end = at + len
            while (at < end - 1e-9) {
                samples += ShadeSample(at, 46.8, -121.7, null, t0 + (at * 1000).toLong(), light, 30.0, 180.0)
                at += 25.0
            }
        }
        val total = samples.last().alongM + 12.5
        val st = ShadeAlong.stretches(samples, 0.0, total)
        val (loss, gain) = ShadeAlong.events(st, samples)
        return ShadeAlongResult(0.0, total, t0, samples, st, loss, gain, ShadeAlongStats(samples.size, 0, 14, 11, 0, 0.0, 0.0, 0.0, false))
    }

    @Test
    fun stretchesShorterThan100mAreAbsorbed() {
        val s = ShadeLight.SUN
        val h = ShadeLight.SHADE
        // 75 m of shade inside sun goes; 100 m stays.
        assertEquals(listOf(s), made(s to 500.0, h to 75.0, s to 500.0).stretches.map { it.light })
        assertEquals(listOf(s, h, s), made(s to 500.0, h to 100.0, s to 500.0).stretches.map { it.light })
        // A short run between two different lights joins the longer neighbour.
        val r = made(s to 300.0, h to 50.0, ShadeLight.DOWN to 600.0)
        assertEquals(listOf(s, ShadeLight.DOWN), r.stretches.map { it.light })
        assertEquals(287.5, r.stretches[1].fromM, 1e-9)
        assertEquals(1, r.stretches[1].absorbed)
        // A flicker of 25 m runs along a ridge crest is one stretch of the longer light around it.
        val flicker = made(h to 400.0, s to 25.0, h to 25.0, s to 25.0, h to 50.0, s to 600.0)
        assertEquals(listOf(h, s), flicker.stretches.map { it.light })
        // The first stretch is the open one; boundaries at the midpoints between samples, the times interpolated.
        val two = made(h to 500.0, s to 1500.0)
        assertTrue(two.stretches[0].open)
        assertTrue(!two.stretches[1].open)
        assertEquals(487.5, two.stretches[1].fromM, 1e-9)
        assertEquals(487_500L, two.stretches[1].startMs - two.startMs)
        assertEquals(487.5, two.firstSunGain!!.alongM, 1e-9)
    }

    @Test
    fun theCardLines() {
        val m = ContourUnits.METERS
        val r = made(ShadeLight.SUN to 1000.0, ShadeLight.SHADE to 600.0, ShadeLight.SUN to 400.0, ShadeLight.DOWN to 500.0)
        // The shade starts at 987.5 m and lasts 600 s; ahead distances to 50 m below a kilometre (987.5 m reads 1.00 km).
        assertEquals("Terrain shade in 1.00 km for 10 min", ShadeText.cardLine(r, 0.0, m))
        assertEquals("Terrain shade in 400 m for 10 min", ShadeText.cardLine(r, 575.0, m))
        assertEquals("Terrain shade just ahead for 10 min", ShadeText.cardLine(r, 960.0, m))
        assertEquals("In terrain shade for 5 min more", ShadeText.cardLine(r, 1287.5, m))
        assertEquals("No terrain shade before sunset", ShadeText.cardLine(r, 1700.0, m))
        assertNull(ShadeText.cardLine(r, 2100.0, m)) // the sun is down
        assertNull(ShadeText.cardLine(r, 9999.0, m)) // past the end
        assertEquals("1 h 5 min", ShadeText.minutes(65.2))
        assertEquals("1 min", ShadeText.minutes(0.2))
        assertEquals("1 hour 5 minutes", ShadeText.minutesSpoken(65.0))
        assertEquals("25 minutes", ShadeText.minutesSpoken(25.4))
    }

    @Test
    fun theSpokenLineBeforeALongSunnyStretch() {
        val m = ContourUnits.METERS
        // Shade, then 1,500 m (25 min) of sun, then shade: said within 400 m of the sun, once, never late.
        val r = made(ShadeLight.SHADE to 2000.0, ShadeLight.SUN to 1500.0, ShadeLight.SHADE to 500.0)
        val sunAt = r.stretches[1].fromM
        assertEquals(-1, ShadeText.dueSpoken(r, sunAt - 401))
        assertEquals(1, ShadeText.dueSpoken(r, sunAt - 399))
        assertEquals(1, ShadeText.dueSpoken(r, sunAt + 29)) // at it
        assertEquals(-1, ShadeText.dueSpoken(r, sunAt + 31)) // passed: never late
        assertEquals(-1, ShadeText.dueSpoken(r, sunAt - 100, saidUntilM = r.stretches[1].toM)) // said already
        assertEquals("In 400 m, the terrain shade ends. Then sun for 25 minutes.", ShadeText.spoken(r, 1, 399.0, m))
        assertEquals("The terrain shade ends here. Then sun for 25 minutes.", ShadeText.spoken(r, 1, 10.0, m))
        // 15 minutes of sun is not announced; the next long one is (only the next long one is ever considered).
        val r2 = made(ShadeLight.SHADE to 500.0, ShadeLight.SUN to 900.0, ShadeLight.SHADE to 500.0, ShadeLight.SUN to 1500.0)
        assertEquals(-1, ShadeText.dueSpoken(r2, 400.0))
        assertEquals(3, ShadeText.dueSpoken(r2, r2.stretches[3].fromM - 200))
        assertEquals("In 200 m, the terrain shade ends. Then sun to the end, 25 minutes.", ShadeText.spoken(r2, 3, 200.0, m))
        // The open first stretch (the walker already in the sun) and sun after night are never announced.
        assertEquals(-1, ShadeText.dueSpoken(made(ShadeLight.SUN to 3000.0), 0.0))
        assertEquals(-1, ShadeText.dueSpoken(made(ShadeLight.DOWN to 500.0, ShadeLight.SUN to 3000.0), 200.0))
    }

    @Test
    fun theHotDayRuleAndTheSetting() {
        // 28 C at a station 500 m up is 21.5 C 1 km higher: not hot; 30 C at 600 m is 26.1 C at 1,200 m: hot.
        assertEquals(21.5, HotDay.atHeight(28.0, 500.0, 1500.0), 1e-9)
        assertTrue(!HotDay.hot(HotDay.atHeight(28.0, 500.0, 1500.0)))
        assertTrue(HotDay.hot(HotDay.atHeight(30.0, 600.0, 1200.0)))
        assertTrue(HotDay.hot(25.0))
        assertTrue(!HotDay.hot(24.9))
        assertTrue(!HotDay.hot(null))
        assertTrue(!HotDay.allows(ShadeSpoken.OFF, 40.0))
        assertTrue(HotDay.allows(ShadeSpoken.ALWAYS, null))
        assertTrue(HotDay.allows(ShadeSpoken.HOT, 26.0))
        assertTrue(!HotDay.allows(ShadeSpoken.HOT, null)) // no temperature known: not a hot day
        assertEquals(ShadeSpoken.HOT, ShadeSpoken.DEFAULT)
        assertEquals(ShadeSpoken.HOT, ShadeSpoken.parse("On hot days"))
        assertEquals(ShadeSpoken.ALWAYS, ShadeSpoken.parse(" always "))
        assertEquals(ShadeSpoken.OFF, ShadeSpoken.parse("off"))
        assertNull(ShadeSpoken.parse("sometimes"))
        assertNull(ShadeSpoken.parse(null))
    }

    @Test
    fun theLowSunRangeCap() {
        // T188's follow-up: below 5 degrees a ray looks 15 km at most; above, the relief rule up to 30 km.
        assertEquals(Horizon.LOW_SUN_RANGE_M, CastShadow.rangeFor(4.0))
        assertEquals(Horizon.LOW_SUN_RANGE_M, CastShadow.rangeFor(0.5))
        assertEquals(Horizon.DEFAULT_RANGE_M, CastShadow.rangeFor(4.0, lowSunM = 0.0)) // uncapped: the "before"
        assertEquals(Horizon.DEFAULT_RANGE_M, CastShadow.rangeFor(5.0)) // 45.7 km by the relief rule, capped at 30
        assertEquals(CastShadow.RELIEF_M / tan(10.0 * PI / 180), CastShadow.rangeFor(10.0), 1e-6)
        assertEquals(Horizon.DEFAULT_RANGE_M, CastShadow.rangeFor(-1.0)) // no ray is marched at night anyway
    }
}
