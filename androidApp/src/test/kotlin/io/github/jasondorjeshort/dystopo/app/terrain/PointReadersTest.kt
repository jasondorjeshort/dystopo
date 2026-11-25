package io.github.jasondorjeshort.dystopo.app.terrain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T358 (ADR 0066 build task 4): every reader of one point's elevation asks the one point read (`PointRead` in
 * `app/terrain/TerrainRead.kt`), in the mode the owner's answer to proposal 0066's Question (a) gives it: held now with the
 * fetch behind for the readout, the pressure card, the long-press card and the route tools; held only for the position
 * estimate, the ground under each aircraft and a dropped waypoint. A source read in the manner of `ComposeStatePrepareTest`:
 * each reader file names the read or one of its delegates, each delegate asks the read in its mode, every caller of a
 * delegate is a reader named here, and no reader opens a map's pyramid of its own. A regex read, not a parse.
 */
class PointReadersTest {
    private val app = File("../androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app")

    private fun text(path: String): String = File(app, path).also { assertTrue(it.isFile, "missing $path: moved?") }.readText()

    /** [path]'s code with comments blanked (KDoc names the old reads). */
    private fun code(path: String): String = text(path).lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") || it.trimStart().startsWith("//") }
        .joinToString("\n") { it.substringBefore(" // ") }

    private fun asks(mode: String) = Regex("""PointRead\.(sampler|at|pyramidAt)\([^\n]*PointRead\.Mode\.$mode""")

    /** The readers, by file, and what each must call. */
    private val behind = mapOf(
        "readout/LiveReadout.kt" to listOf(asks("BEHIND")),
        "readout/PressureCard.kt" to listOf(asks("BEHIND")),
        "options/ShadeService.kt" to listOf(asks("BEHIND")),
        "routing/RouteExposure.kt" to listOf(asks("BEHIND")),
        "routing/RouteBestStart.kt" to listOf(Regex("""shade\.pyramidAt\(""")),
        "routing/RoutePlanner.kt" to listOf(Regex("""Trips\.demSampler\(\)""")),
        "nav/NavShade.kt" to listOf(Regex("""shade\.pyramidAt\(""")),
        "tracking/LongPressMenu.kt" to listOf(Regex("""shade\.pyramidAt\("""), Regex("""LiveReadout\.demAt\(""")),
        "controls/ScaleBarRoutes.kt" to listOf(Regex("""live\.demAt\("""), Regex("""pointRead\.at\(""")),
    )
    private val held = mapOf(
        "tracking/TerrainSource.kt" to listOf(asks("HELD")),
        "aircraft/AircraftPoller.kt" to listOf(Regex("""Trips\.elevationAt\(""")),
        "tracking/Tracking.kt" to listOf(Regex("""Trips\.elevationAt\(""")),
    )

    @Test
    fun eachReaderAsksThePointRead() {
        for ((path, wants) in behind + held) {
            val c = code(path)
            for (w in wants) assertTrue(w.containsMatchIn(c), "$path does not call ${w.pattern}")
        }
    }

    @Test
    fun theDelegatesAskInTheirMode() {
        val trips = code("trips/Trips.kt")
        assertTrue(Regex("""val held = PointRead\.sampler\(PointRead\.Mode\.HELD""").containsMatchIn(trips), "Trips' held sampler")
        assertTrue(Regex("""val behind = PointRead\.sampler\(PointRead\.Mode\.BEHIND""").containsMatchIn(trips), "Trips' behind sampler")
        assertTrue(Regex("""fun elevation\(\): ElevationSampler\? = held\.""").containsMatchIn(trips), "Trips.elevation() reads held only")
        assertTrue(Regex("""fun demSampler\(\): ElevationSampler\? = behind\.""").containsMatchIn(trips), "Trips.demSampler() reads held now, fetch behind")
        assertTrue(Regex("""fun elevationAt\([^)]*\): Double\? =\s*elevation\(\)""").containsMatchIn(trips), "Trips.elevationAt reads elevation() (held only)")
        assertTrue(Regex("""RouteEditor\(graph, demSampler\(\)\)""").containsMatchIn(trips), "the editor's straight legs read as a route tool")
        assertTrue(Regex("""fun demAt\([^)]*\): Double = dem\.elevation""").containsMatchIn(code("readout/LiveReadout.kt")), "LiveReadout.demAt reads the readout's own sampler")
        assertTrue(Regex("""fun pyramidAt\([^)]*\): DemPyramid\? =\s*runCatching \{ io\.github\.jasondorjeshort\.dystopo\.app\.terrain\.PointRead\.pyramidAt""").containsMatchIn(code("options/ShadeService.kt")), "ShadeService.pyramidAt asks the point read first")
    }

    @Test
    fun everyCallerOfADelegateIsAReaderNamedHere() {
        val files = app.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val named = (behind + held).keys + setOf("trips/Trips.kt", "terrain/TerrainRead.kt")
        val delegates = mapOf(
            "Trips.elevationAt(" to setOf("aircraft/AircraftPoller.kt", "tracking/Tracking.kt"),
            "Trips.demSampler(" to setOf("routing/RoutePlanner.kt"),
            "pyramidAt(" to setOf("options/ShadeService.kt", "readout/PressureCard.kt", "routing/RouteExposure.kt", "routing/RouteBestStart.kt", "nav/NavShade.kt", "tracking/LongPressMenu.kt", "terrain/TerrainRead.kt"),
            "PointRead." to named,
        )
        for ((call, allowed) in delegates) {
            val callers = files.filter { f -> code(f.relativeTo(app).invariantSeparatorsPath).contains(call) }.map { it.relativeTo(app).invariantSeparatorsPath }.toSet()
            assertEquals(emptySet(), callers - allowed, "callers of $call that are not readers named here: add them to the table with their mode")
        }
    }

    @Test
    fun noReaderOpensAMapOfItsOwn() {
        val own = Regex("""DemPyramid\([^\n]*\.header\.|packs\.dem\b|demPacks""")
        for (path in (behind + held).keys - setOf("routing/RoutePlanner.kt")) { // RoutePlanner's routing graph reads its own sources (ADR 0066: out of scope)
            val hits = code(path).lines().filter { own.containsMatchIn(it) }
            assertEquals(emptyList(), hits, "$path reads a map's elevation itself")
        }
    }
}
