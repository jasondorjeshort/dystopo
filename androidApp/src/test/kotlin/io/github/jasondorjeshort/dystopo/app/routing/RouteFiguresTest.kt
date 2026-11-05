package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.PaceRange
import io.github.jasondorjeshort.dystopo.routing.TimeEstimate
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T217: the planning card's figures ([RouteFigures]), its actions ([CardActions]) and its lines' states ([LineState]). */
class RouteFiguresTest {
    private val ft = ContourUnits.FEET
    private val m = ContourUnits.METERS
    private val range = TimeEstimate(16200.0, 16200.0, 15000.0, 17400.0, 0.9, 1.1, "fit")
    private val typical = TimeEstimate(4500.0, 4500.0, null, null, null, null, "tracks")

    @Test
    fun noDescentPrintsNoDownAndNeverAZero() {
        assertEquals("2.90 km · up 402 m · 1:15", RouteFigures.line(2900.0, 402.0, 0.0, "1:15", m))
        assertEquals("1.8 mi · up 1319 ft · 1:15", RouteFigures.line(2900.0, 402.0, 0.0, "1:15", ft))
        assertEquals("2.90 km · up 402 m · 1:15", RouteFigures.line(2900.0, 402.0, null, "1:15", m))
    }

    @Test
    fun aSmallDescentIsLeftOutAndThirtyMetresIsShown() {
        assertEquals("1.8 mi · up 1319 ft · 1:15", RouteFigures.line(2900.0, 402.0, 29.9, "1:15", ft))
        assertEquals("1.8 mi · up 1319 ft, down 98 ft · 1:15", RouteFigures.line(2900.0, 402.0, 30.0, "1:15", ft))
        assertEquals("2.90 km · up 402 m, down 30 m · 1:15", RouteFigures.line(2900.0, 402.0, 30.0, "1:15", m))
    }

    @Test
    fun noClimbAtAllOrNoHeightsLeavesTheDistanceAndTheTime() {
        assertEquals("2.90 km · 0:35", RouteFigures.line(2900.0, 0.2, 5.0, "0:35", m))
        assertEquals("2.90 km · 0:35", RouteFigures.line(2900.0, null, null, "0:35", m))
        assertEquals("2.90 km · down 402 m · 0:50", RouteFigures.line(2900.0, 0.0, 402.0, "0:50", m))
    }

    @Test
    fun metricFiguresReadInMetresAndKilometres() {
        val metres = ContourUnits.METERS
        // The distance by UnitFormat's rule: metres under 1 km, then two, one and no decimals.
        assertEquals("800 m · up 40 m · 0:15", RouteFigures.line(800.0, 40.0, 0.0, "0:15", metres))
        assertEquals("12.4 km · up 950 m, down 610 m · about 4:30", RouteFigures.line(12_400.0, 950.0, 610.0, "about 4:30", metres))
        assertEquals("120 km · up 950 m, down 610 m · 2:10", RouteFigures.line(120_000.0, 950.0, 610.0, "2:10", metres))
        // The 30 m floor under "down", in metres.
        assertEquals("2.90 km · up 402 m · 1:15", RouteFigures.line(2900.0, 402.0, 29.9, "1:15", metres))
        assertEquals("2.90 km · up 402 m, down 30 m · 1:15", RouteFigures.line(2900.0, 402.0, 30.0, "1:15", metres))
        assertEquals("2.90 km · up 402 m, down 31 m · 1:15", RouteFigures.line(2900.0, 402.0, 31.0, "1:15", metres))
        assertEquals("2.90 km · down 30 m · typical 0:35", RouteFigures.line(2900.0, 0.0, 30.0, "typical 0:35", metres))
    }

    @Test
    fun theTimeKeepsTheHedgeTheSettingChooses() {
        assertEquals("about 4:30", RouteFigures.time(16200.0, range, rangeForm = true))
        assertEquals("typical 1:15", RouteFigures.time(4500.0, typical, rangeForm = true))
        assertEquals("4:30", RouteFigures.time(16200.0, range, rangeForm = false), "the setting's one number")
        assertEquals("1:15", RouteFigures.time(4500.0, typical, rangeForm = false))
        assertEquals("1:15", RouteFigures.time(4500.0, null, rangeForm = true), "drive and crow have no estimate")
        // The estimate the card really takes without a fit is the typical form.
        assertEquals("typical 1:15", RouteFigures.time(4500.0, PaceRange.estimate(4500.0, null), rangeForm = true))
    }

    @Test
    fun eachModeHasItsBasis() {
        assertEquals("Based on a typical hiking pace", RouteFigures.basis(TravelMode.HIKE))
        assertEquals("Based on a typical cycling pace", RouteFigures.basis(TravelMode.BIKE))
        assertEquals("Based on typical driving speeds", RouteFigures.basis(TravelMode.DRIVE))
        assertEquals("Based on a typical hiking pace along the straight line", RouteFigures.basis(TravelMode.CROW))
        assertEquals("Based on your last 5 hikes", RouteFigures.basis(TravelMode.HIKE, fitTracks = 5))
        assertEquals("Based on your last 1 ride", RouteFigures.basis(TravelMode.BIKE, fitTracks = 1))
        assertEquals("Based on a typical hiking pace (your setting)", RouteFigures.basis(TravelMode.HIKE, bySetting = true))
        for (mode in TravelMode.entries) assertFalse("Standard" in RouteFigures.basis(mode), mode.label)
    }

    @Test
    fun aRouteHasOnePrimaryActionThenSaveClearAndTheOverflow() {
        val actions = CardActions.of("route", canTryCrow = false)
        assertEquals(listOf("go", "save", "clear", "more"), actions.map { it.id })
        assertEquals(listOf("go"), actions.filter { it.primary }.map { it.id })
        assertEquals(listOf("Go", "Save", "Clear", "More actions"), actions.map { it.label })
        assertEquals(listOf("filled", "outlined", "text", "overflow"), actions.map { it.style })
        assertEquals(listOf("Export GPX"), actions.last().items.map { it.label })
        val more = actions.last().json()
        assertEquals(false, more["primary"])
        assertEquals("exportGpx", (more["items"] as List<*>).map { (it as Map<*, *>)["id"] }.single())
    }

    @Test
    fun theOtherPhasesOfferWhatTheyDidBefore() {
        assertEquals(emptyList(), CardActions.of("idle", canTryCrow = false).map { it.id })
        assertEquals(listOf("clear"), CardActions.of("planning", canTryCrow = false).map { it.id })
        assertEquals(listOf("turnOnLocation", "clear"), CardActions.of("noLocation", canTryCrow = false).map { it.id })
        assertEquals(listOf("tryCrow", "clear"), CardActions.of("failed", canTryCrow = true).map { it.id })
        assertEquals(listOf("clear"), CardActions.of("failed", canTryCrow = false).map { it.id })
        assertTrue(CardActions.of("failed", canTryCrow = true).none { it.primary })
    }

    @Test
    fun aLineIsWorkingUntilItsAnswerAndThenReadyOrNone() {
        val pending = CompletableFuture<String>()
        assertEquals("none", LineState.of(null as CompletableFuture<String>?, "Working") { it }["state"], "not started: the mode, the setting, no packs")
        val working = LineState.of(pending, "Working") { it }
        assertEquals("working", working["state"])
        assertEquals("Working", working["text"])
        pending.complete("Water: 2 places")
        val ready = LineState.of(pending, "Working") { it }
        assertEquals("ready", ready["state"])
        assertEquals("Water: 2 places", ready["text"])
        assertEquals("none", LineState.of(pending, "Working") { null }["state"], "an answer with no line to show")
        assertEquals("none", LineState.of(CompletableFuture.failedFuture<String>(RuntimeException("x")), "Working") { it }["state"])
    }
}
