package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.dem.TerrainFixture
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.nav.Deadline
import io.github.jasondorjeshort.dystopo.nav.DeadlineKind
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.NavPoint
import io.github.jasondorjeshort.dystopo.nav.Sun
import io.github.jasondorjeshort.dystopo.nav.TerrainSunset
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import io.github.jasondorjeshort.dystopo.nav.TurnPace
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T209: the start-time sweep on a synthetic valley at 46.8 N running north and south: a floor 3 km wide, each wall 150 m
 * high rising over 150 m (45 degrees), flat beyond. A trail 50 m from the foot of the east wall is in that wall's shade
 * (37 degrees up) from sunrise until mid-morning, and the far west wall stands only 2.7 degrees up from it, clear of the
 * sun by the first start; the trail by the west wall is the mirror. So on a hot day the east trail starts in the morning
 * and the west trail in the afternoon; on a cool day the east trail starts after its morning shade.
 */
class BestStartTest {
    private val valley = TerrainFixture(46.8, -121.7) { e, _ ->
        val d = abs(e)
        when {
            d <= 1500 -> 0.0
            d >= 1650 -> 150.0
            else -> d - 1500
        }
    }

    /** One metre a second: the 2 km trails take 33 minutes. */
    private val pace = TurnPace(false) { l, _, _ -> l }

    private fun point(east: Double, north: Double): NavPoint {
        val (gx, gy) = valley.at(14, east, north)
        return NavPoint(WebMercator.yToLat(gy / 256, 14), WebMercator.xToLon(gx / 256, 14), valley.heightAt(east, north))
    }

    private fun path(east: Double) = NavPath("test", listOf(point(east, -1000.0), point(east, 1000.0)))

    private val eastTrail = path(1450.0)
    private val westTrail = path(-1450.0)

    private val june = Sun.epochDay(2026, 6, 21) * 86_400_000L + 20 * 3_600_000L // about solar noon at 121.7 W
    private val day = Sun.dayAt(46.8, -121.7, june)
    private val sunset = Deadline(DeadlineKind.SUNSET, day.sunsetMs, "sunset, flat horizon", day.sunsetMs)

    private fun sunsetOf(atMs: Long): Deadline = Sun.dayAt(46.8, -121.7, atMs).let { Deadline(DeadlineKind.SUNSET, it.sunsetMs, "sunset, flat horizon", it.sunsetMs) }

    private fun sweep(
        p: NavPath, aim: BestStartAim, deadline: Deadline? = sunset, margin: Int = 30, current: Long? = null,
        from: Long = day.sunriseMs!!, to: Long = day.sunsetMs!!,
    ) = BestStart.sweep(p, TurnAround(p, pace), aim, from, to, deadline, margin, 14, valley::grid, current)

    private val clock: (Long) -> String = { ms ->
        val local = ms - 8 * 3_600_000L
        val m = ((local / 60_000) % 1440 + 1440) % 1440
        "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    @Test
    fun onAHotDayTheEastTrailStartsInTheMorningAndTheWestTrailInTheAfternoon() {
        val east = sweep(eastTrail, BestStartAim.SHADE)
        assertEquals(BestStartKind.PICK, east.kind, east.json(clock).toString())
        val e = east.pick!!
        assertTrue(e.startMs < day.noonMs - 2 * 3_600_000L, "east trail at ${clock(e.startMs)}")
        assertTrue(e.fraction(BestStartAim.SHADE) > 0.95, "east trail ${e.json(clock)}")
        val west = sweep(westTrail, BestStartAim.SHADE)
        assertEquals(BestStartKind.PICK, west.kind)
        val w = west.pick!!
        assertTrue(w.startMs > day.noonMs + 2 * 3_600_000L, "west trail at ${clock(w.startMs)}")
        assertTrue(w.fraction(BestStartAim.SHADE) > 0.95, "west trail ${w.json(clock)}")
        // The grid every 30 minutes over the day, the refinement either side of the pick, every start marched once.
        assertTrue(east.candidates.count { it.pass == "grid" } in 28..34, "${east.candidates.size} starts")
        assertTrue(east.candidates.any { it.pass == "refine" })
        assertEquals(east.candidates.size, east.candidates.map { it.startMs }.toSet().size)
        assertEquals(east.candidates.sortedBy { it.startMs }.map { it.startMs }, east.candidates.map { it.startMs })
        // The line: the time first, the reason second, terrain shade in full.
        val line = ShadeText.bestStartLine(east, clock)!!
        assertTrue(line.matches(Regex("Start at \\d{1,2}:\\d{2} for the most terrain shade \\(about \\d+ min in shade\\)")), line)
        // One terrain read for every start: the samples' places do not move, the coarse layer is the union of the suns.
        assertEquals(0, east.stats!!.rereads)
        assertEquals(false, east.stats!!.coarsened)
        assertEquals(11, east.stats!!.coarseZoom)
    }

    @Test
    fun onACoolDayTheEastTrailStartsOutOfItsMorningShade() {
        val r = sweep(eastTrail, BestStartAim.SUN)
        val p = r.pick!!
        assertTrue(p.fraction(BestStartAim.SUN) > 0.95, p.json(clock).toString())
        assertTrue(p.startMs > day.sunriseMs!! + 3 * 3_600_000L, "at ${clock(p.startMs)}")
        // Many starts are all sun: the tie goes to the earliest without a start of the walker's own.
        assertTrue(r.candidates.filter { it.deadlineMet && it.sunMin >= p.sunMin - BestStart.TIE_MIN }.all { it.startMs >= p.startMs })
        assertEquals("Start at ${clock(p.startMs)} for the most sun", ShadeText.bestStartLine(r, clock))
    }

    @Test
    fun theWalkersOwnStartWinsATie() {
        val first = sweep(eastTrail, BestStartAim.SUN)
        val mine = first.pick!!.startMs + 2 * 3_600_000L // later in the day, still all sun on the east trail
        val r = sweep(eastTrail, BestStartAim.SUN, current = mine)
        assertEquals(BestStartKind.CURRENT, r.kind, r.json(clock)["table"].toString())
        assertEquals(mine, r.pick!!.startMs)
        assertEquals("Your start time is the sunniest", ShadeText.bestStartLine(r, clock))
        val hot = sweep(eastTrail, BestStartAim.SHADE, current = sweep(eastTrail, BestStartAim.SHADE).pick!!.startMs)
        assertEquals("Your start time is the shadiest", ShadeText.bestStartLine(hot, clock))
    }

    @Test
    fun aDeadlineThatBindsIsWhatTheLineSays() {
        // On a cool day the east trail wants a start after its morning shade; a deadline two and a half hours after
        // sunrise leaves only shaded starts, so the latest that finishes in time is the line.
        val at = day.sunriseMs!! + 150 * 60_000L
        val early = Deadline(DeadlineKind.TERRAIN, at, Deadline.BEHIND_RIDGE, day.sunsetMs, at, TerrainSunset.BEHIND)
        val r = sweep(eastTrail, BestStartAim.SUN, deadline = early, margin = 0)
        assertEquals(BestStartKind.DEADLINE, r.kind, r.json(clock).toString())
        val latest = r.latestStartMs!!
        assertTrue(latest + TurnAround(eastTrail, pace).totalS * 1000 <= at, "the latest start finishes in time")
        assertTrue(at - (latest + TurnAround(eastTrail, pace).totalS * 1000) < 5 * 60_000, "and is the latest on 5 minutes")
        assertEquals(latest, r.pick!!.startMs)
        assertEquals("Start by ${clock(latest)} to finish before terrain sunset", ShadeText.bestStartLine(r, clock))
        assertTrue(r.candidates.filter { it.startMs > latest }.none { it.deadlineMet })
        // No start of the day finishes in time: the line still says by when, and nothing is picked.
        val dawn = Deadline(DeadlineKind.SUNSET, day.sunriseMs!! + 10 * 60_000L, "sunset, flat horizon", day.sunriseMs)
        val none = sweep(eastTrail, BestStartAim.SHADE, deadline = dawn, margin = 0)
        assertEquals(BestStartKind.DEADLINE, none.kind)
        assertNull(none.pick)
        assertTrue(none.candidates.none { it.deadlineMet })
        assertTrue(ShadeText.bestStartLine(none, clock)!!.endsWith("to finish before sunset"))
    }

    @Test
    fun theSweepIsDeterministicAndAgreesWithTheRouteAnswer() {
        val a = sweep(eastTrail, BestStartAim.SHADE)
        val b = sweep(eastTrail, BestStartAim.SHADE)
        assertEquals(a.candidates.map { it.json() }, b.candidates.map { it.json() })
        assertEquals(a.pick!!.startMs, b.pick!!.startMs)
        // Each start's minutes are what ShadeAlong.compute gives for that start with its own terrain read.
        for (c in a.candidates.filterIndexed { i, _ -> i % 5 == 0 }) {
            val r = ShadeAlong.compute(eastTrail, TurnAround(eastTrail, pace), 0.0, c.startMs, 14, valley::grid)
            val shade = r.stretches.filter { it.light == ShadeLight.SHADE }.sumOf { it.minutes }
            assertEquals(shade, c.shadeMin, 0.5, "at ${clock(c.startMs)}")
        }
    }

    @Test
    fun aRouteWithNoTerrainShadeSaysSoAndNoDaylightSaysNothing() {
        val flat = TerrainFixture(46.8, -121.7) { _, _ -> 100.0 }
        val p = NavPath("flat", listOf(point(0.0, -1000.0), point(0.0, 1000.0)))
        val r = BestStart.sweep(p, TurnAround(p, pace), BestStartAim.SHADE, day.noonMs - 3_600_000L, day.noonMs + 3_600_000L, sunset, 30, 14, flat::grid)
        assertEquals(BestStartKind.NONE, r.kind)
        assertEquals("No terrain shade along this route today", ShadeText.bestStartLine(r, clock))
        // An empty window and no start of the walker's own: nothing to say.
        val empty = BestStart.sweep(p, TurnAround(p, pace), BestStartAim.SUN, day.noonMs + 60_000L, day.noonMs + 120_000L, sunset, 30, 14, flat::grid)
        assertEquals(BestStartKind.NO_DAY, empty.kind)
        assertNull(ShadeText.bestStartLine(empty, clock))
    }

    @Test
    fun afterSunsetThePhoneSweepsTomorrow() {
        val evening = day.sunsetMs!! + 3_600_000L
        val r = BestStart.suggest(eastTrail, TurnAround(eastTrail, pace), BestStartAim.SHADE, evening, ::sunsetOf, 30, 14, valley::grid)
        assertTrue(r.tomorrow)
        assertNull(r.currentMs)
        assertTrue(r.fromMs > evening)
        val line = ShadeText.bestStartLine(r, clock)
        assertNotNull(line)
        assertTrue(line.startsWith("Start at ") && " tomorrow for the most terrain shade" in line, line)
        // In the morning the sweep is today's, from now on, with now as the walker's start.
        val morning = day.sunriseMs!! + 30 * 60_000L
        val m = BestStart.suggest(eastTrail, TurnAround(eastTrail, pace), BestStartAim.SHADE, morning, ::sunsetOf, 30, 14, valley::grid)
        assertEquals(false, m.tomorrow)
        assertEquals(morning, m.currentMs)
        assertTrue(m.candidates.all { it.startMs >= morning })
        assertEquals(BestStartKind.CURRENT, m.kind, m.json(clock)["table"].toString())
    }

    /** T218: the deadline at [at] with the walk at the east trail's pace; the sweep from sunrise with the walker's own start [current]. */
    private fun early(at: Long) = Deadline(DeadlineKind.TIME, at, DeadlineKind.TIME.label)

    private val walkMs = (TurnAround(eastTrail, pace).totalS * 1000).toLong()

    @Test
    fun startByShowsOnlyWhenTheFinishIsNearTheDeadline() {
        // The east trail is in shade until mid-morning, so on a cool day a deadline then binds: the later a start that
        // meets it, the more sun, and a start that misses it has more.
        val at = day.sunriseMs!! + 264 * 60_000L
        val oneHourBefore = at - 60 * 60_000L - walkMs
        val near = sweep(eastTrail, BestStartAim.SUN, deadline = early(at), margin = 0, current = oneHourBefore)
        assertEquals(BestStartKind.DEADLINE, near.kind, near.json(clock).toString())
        assertTrue(ShadeText.bestStartLine(near, clock)!!.startsWith("Start by "))
        // The same day and deadline with the walker's own start three hours clear of it: no "Start by", the sun line instead.
        val threeHoursBefore = at - 3 * 3_600_000L - walkMs
        val far = sweep(eastTrail, BestStartAim.SUN, deadline = early(at), margin = 0, current = threeHoursBefore)
        assertEquals(BestStartKind.PICK, far.kind, far.json(clock).toString())
        assertTrue(!ShadeText.bestStartLine(far, clock)!!.startsWith("Start by "), ShadeText.bestStartLine(far, clock))
        // The suppressed line never names a start that misses the deadline.
        assertTrue(far.pick!!.deadlineMet)
        // The edge: exactly the constant is near, a minute further is not.
        val edge = at - BestStart.START_BY_WITHIN_MIN * 60_000L - walkMs
        assertEquals(BestStartKind.DEADLINE, sweep(eastTrail, BestStartAim.SUN, deadline = early(at), margin = 0, current = edge).kind)
        assertTrue(sweep(eastTrail, BestStartAim.SUN, deadline = early(at), margin = 0, current = edge - 60_000L).kind != BestStartKind.DEADLINE)
        // Past it: the walker's own start already misses the deadline.
        val past = sweep(eastTrail, BestStartAim.SUN, deadline = early(at), margin = 0, current = at)
        assertEquals(BestStartKind.DEADLINE, past.kind)
    }

    @Test
    fun aTypedDeadlineEarlierThanTheTerrainSunsetMovesTheLatestStartAndIsNamed() {
        val terrain = Deadline(DeadlineKind.TERRAIN, day.sunsetMs!! - 3_600_000L, Deadline.BEHIND_RIDGE, day.sunsetMs, day.sunsetMs!! - 3_600_000L, TerrainSunset.BEHIND)
        val at = day.sunriseMs!! + 264 * 60_000L
        val byTerrain = sweep(eastTrail, BestStartAim.SUN, deadline = terrain, margin = 0)
        val typed = sweep(eastTrail, BestStartAim.SUN, deadline = early(at), margin = 0)
        assertTrue(typed.latestStartMs!! < byTerrain.latestStartMs!!, "${typed.latestStartMs} against ${byTerrain.latestStartMs}")
        assertTrue(typed.latestStartMs!! + walkMs <= at)
        assertEquals(BestStartKind.DEADLINE, typed.kind, typed.json(clock).toString())
        assertEquals("Start by ${clock(typed.latestStartMs!!)} to finish before your ${clock(at)} turn-back time", ShadeText.bestStartLine(typed, clock))
        // Each deadline's own words.
        val civil = Deadline(DeadlineKind.CIVIL, at, "civil twilight, flat horizon", day.sunsetMs)
        assertEquals("Start by ${clock(typed.latestStartMs!!)} to finish before the end of civil twilight", ShadeText.bestStartLine(sweep(eastTrail, BestStartAim.SUN, deadline = civil, margin = 0), clock))
        val flat = Deadline(DeadlineKind.SUNSET, at, "sunset, flat horizon", at)
        assertEquals("Start by ${clock(typed.latestStartMs!!)} to finish before sunset", ShadeText.bestStartLine(sweep(eastTrail, BestStartAim.SUN, deadline = flat, margin = 0), clock))
    }

    @Test
    fun whenEveryStartIsAsSunnyThereIsNoLine() {
        val flat = TerrainFixture(46.8, -121.7) { _, _ -> 100.0 }
        val p = NavPath("flat", listOf(point(0.0, -1000.0), point(0.0, 1000.0)))
        val r = BestStart.sweep(p, TurnAround(p, pace), BestStartAim.SUN, day.sunriseMs!!, day.sunsetMs!!, sunset, 30, 14, flat::grid)
        assertEquals(BestStartKind.ANY, r.kind, r.json(clock)["table"].toString())
        assertEquals("any", r.json(clock)["kind"])
        assertNull(ShadeText.bestStartLine(r, clock))
        // The walker's own start does not change that: "Your start time is the sunniest" would say nothing.
        val mine = BestStart.sweep(p, TurnAround(p, pace), BestStartAim.SUN, day.sunriseMs!!, day.sunsetMs!!, sunset, 30, 14, flat::grid, currentMs = day.noonMs)
        assertEquals(BestStartKind.ANY, mine.kind)
        // On a hot day with no shade anywhere the line still says so.
        val hot = BestStart.sweep(p, TurnAround(p, pace), BestStartAim.SHADE, day.sunriseMs!!, day.sunsetMs!!, sunset, 30, 14, flat::grid)
        assertEquals(BestStartKind.NONE, hot.kind)
    }
}
