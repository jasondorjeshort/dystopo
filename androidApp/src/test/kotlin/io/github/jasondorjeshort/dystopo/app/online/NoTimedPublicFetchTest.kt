package io.github.jasondorjeshort.dystopo.app.online

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T355: the public vector source's terms allow automated collection only for offline downloads, so nothing asks it on a
 * timer and nothing refreshes from it by itself; a download's requests are marked as one. Read from the sources, as
 * `RetiredNounsTest` reads them: these are rules about which paths exist, not about one function's output.
 */
class NoTimedPublicFetchTest {
    private val main = File("..", "androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app")
    private fun source(path: String) = File(main, path).readText()

    /** The body of `fun [name](` in [text], braces matched. */
    private fun body(text: String, name: String): String {
        val at = Regex("""fun (<[^>]*> )?$name\(""").find(text)?.range?.first ?: error("no fun $name")
        val open = text.indexOf('{', at)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        error("unbalanced fun $name")
    }

    private val timer = Regex("""scheduleWithFixedDelay|scheduleAtFixedRate|\.schedule\(|postDelayed|setInitialDelay|Timer\(""")

    /** A file that can start a request to the public vector source: its tiles, the fetch behind, the probe, the TileJSON. */
    private val reachesTheSource = Regex("""get\("osm"|fetchBehind\(|\.probe\(|ofmGet\(|openFreeMap\(""")

    @Test
    fun onlyNamedTimersReachThePublicSource() {
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { timer.containsMatchIn(it) && reachesTheSource.containsMatchIn(it) } }
            .map { it.name }.sorted().toList()
        // OnlineService: the probe (`live/info`, answered without a request in direct mode, below) and the stall recovery
        // (only tiles a view asked for and could not get, below). A new timer that reaches the source needs its reason here.
        assertEquals(listOf("OnlineService.kt"), files)
    }

    @Test
    fun theProbeAsksThePublicSourceNothing() {
        val info = body(source("online/DirectSources.kt"), "info")
        for (call in listOf("SourceIo.", "ofmGet", "http.", "openFreeMap(")) assertFalse(call in info, "live/info must not make a request: $call")
        assertTrue("hasNetwork()" in info)
    }

    @Test
    fun theStallRecoveryRetriesOnlyWhatAViewAskedFor() {
        val retry = body(source("online/OnlineService.kt"), "retryStalled")
        assertTrue("stalledOsm.due(" in retry && Regex("""for \(path in due\)""").containsMatchIn(retry), "the recovery fetches only the tiles views recorded")
        assertEquals(1, Regex("""get\("osm"""").findAll(source("online/OnlineService.kt")).count(), "one vector fetch in OnlineService, the recovery's")
    }

    @Test
    fun theFetchBehindIsStartedByTheViewOncePerTile() {
        val callers = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> Regex("""\.fetchBehind\(""").containsMatchIn(f.readText()) }.map { it.name }.toList()
        // T358: and from the point read (`terrain/TerrainRead.kt` `PointRead`): an elevation tile a reader asked for at its point
        // (the readout at a fix, the long-press card), once per tile like the view's, never on a timer nor a vector tile.
        assertEquals(listOf("TerrainRead.kt", "TileService.kt"), callers.sorted(), "T341's fetch behind starts only from a view's tile request or a point read")
        assertTrue("if (!behind.add(path)) return" in body(source("online/OnlineTiles.kt"), "fetchBehind"), "once per tile")
    }

    @Test
    fun everyPublicSourceRequestPassesTheLimiter() {
        val direct = source("online/DirectSources.kt")
        assertEquals(1, Regex("""http\.ofm\(""").findAll(direct).count())
        assertTrue("http.ofm(" in body(direct, "ofmAttempts"))
        assertTrue("ofmLimits.run(" in body(direct, "ofmGet"))
        // The TileJSON: its declaration and one fetch, through ofmGet.
        assertEquals(2, Regex("""OFM_TILEJSON""").findAll(direct).count())
        assertTrue("const val OFM_TILEJSON" in direct && "ofmGet(OFM_TILEJSON)" in direct)
    }

    @Test
    fun downloadsMarkTheirFetches() {
        for (f in listOf("areas/AreaDownload.kt", "areas/AheadPrefetch.kt", "online/LocalPackBuild.kt")) {
            assertTrue(Regex("""DirectSources\.download \{ fetcher\.get\(""").containsMatchIn(source(f)), "$f: the download's fetch is marked")
        }
    }
}
