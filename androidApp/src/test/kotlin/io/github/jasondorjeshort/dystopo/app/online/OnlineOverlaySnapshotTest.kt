package io.github.jasondorjeshort.dystopo.app.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T144: the online indicator's snapshot ([OnlineUi]), made off the UI thread and only read by the composable: the lines it
 * shows for each state of the connection and of "Download this area". T236: every cell of proposal 0056's two chip tables.
 */
class OnlineOverlaySnapshotTest {
    private fun view(source: String) = OnlineView(51.48, -0.01, 13.0, io.github.jasondorjeshort.dystopo.packs.PackBounds(-0.05, 51.46, 0.03, 51.5), source)
    private fun view(source: String, kept: Boolean) = view(source).withSource(source, kept)

    @Test
    fun hiddenWithNoServerAndNothingRunning() {
        assertTrue(OnlineUi("none", null, mapOf("state" to "idle"), running = false).hidden)
        assertFalse(OnlineUi("none", null, mapOf("state" to "downloading"), running = true).hidden)
    }

    /**
     * T236: proposal 0056's chip table, cell by cell, as the proposal writes it ("n/a": a combination the code cannot reach).
     * Columns: pack, network from public sources, network from your server, cache inside a downloaded map, cache otherwise, no data;
     * T255: and a view cut from a wider zoom (T254), drawn from what the phone holds whatever the connection.
     */
    private val table: Map<OnlineUi.Row, Pair<OnlineUi.Dot, List<String>>> = linkedMapOf(
        OnlineUi.Row.ONLINE to (OnlineUi.Dot.GREEN to listOf("Online · on this phone", "Online · from public sources", "Online · from your server", "Online · on this phone", "Online · viewed earlier", "Online · no data here", "Online · from a wider zoom")),
        OnlineUi.Row.PAUSED to (OnlineUi.Dot.GREY to listOf("Paused · on this phone", "Paused · viewed earlier", "n/a", "Paused · on this phone", "Paused · viewed earlier", "Paused · no data here", "Paused · from a wider zoom")),
        OnlineUi.Row.SERVER_OFFLINE to (OnlineUi.Dot.RED to listOf("Server offline · on this phone", "n/a", "Server offline · viewed earlier", "Server offline · on this phone", "Server offline · viewed earlier", "Server offline · no data here", "Server offline · from a wider zoom")),
        OnlineUi.Row.OFFLINE_RED to (OnlineUi.Dot.RED to listOf("Offline · on this phone", "n/a", "Offline · viewed earlier", "Offline · on this phone", "Offline · viewed earlier", "Offline · no data here", "Offline · from a wider zoom")),
        OnlineUi.Row.OFFLINE_GREY to (OnlineUi.Dot.GREY to listOf("Offline · on this phone", "Offline · viewed earlier", "n/a", "Offline · on this phone", "Offline · viewed earlier", "Offline · no data here", "Offline · from a wider zoom")),
        OnlineUi.Row.CONNECTING to (OnlineUi.Dot.GREY to listOf("Connecting · on this phone", "Connecting · viewed earlier", "Connecting · viewed earlier", "Connecting · on this phone", "Connecting · viewed earlier", "Connecting · no data here", "Connecting · from a wider zoom")),
        OnlineUi.Row.NO_SERVER to (OnlineUi.Dot.GREY to listOf("No server · on this phone", "n/a", "n/a", "n/a", "n/a", "No server · no data here", "n/a")),
    )

    private enum class Mode { SERVER, DIRECT, NONE }

    private val server = "http://192.0.2.10:8765"

    /** The snapshot for [row] in column [col] (0..6 as in [table]); the public column reads the sources directly, the server column has a server set. */
    private fun cell(row: OnlineUi.Row, col: Int): OnlineUi {
        val mode = when (col) {
            1 -> Mode.DIRECT
            2 -> Mode.SERVER
            else -> when (row) {
                OnlineUi.Row.PAUSED, OnlineUi.Row.OFFLINE_GREY -> Mode.DIRECT
                OnlineUi.Row.NO_SERVER -> Mode.NONE
                else -> Mode.SERVER
            }
        }
        val state = when (row) {
            OnlineUi.Row.ONLINE, OnlineUi.Row.PAUSED -> "online"
            OnlineUi.Row.SERVER_OFFLINE, OnlineUi.Row.OFFLINE_RED, OnlineUi.Row.OFFLINE_GREY -> "offline"
            OnlineUi.Row.CONNECTING -> "unknown"
            OnlineUi.Row.NO_SERVER -> "none"
        }
        val v = when (col) {
            0 -> view(OnlineTiles.PACK)
            1, 2 -> view(OnlineService.ONLINE)
            3 -> view(OnlineService.CACHED, kept = true)
            4 -> view(OnlineService.CACHED)
            6 -> view(OnlineTiles.WIDER)
            else -> null
        }
        return OnlineUi(
            state, v, mapOf("state" to "idle"), running = mode == Mode.NONE,
            notice = if (row == OnlineUi.Row.PAUSED) "Paused: the session limit is reached." else null,
            direct = mode == Mode.DIRECT, network = row != OnlineUi.Row.OFFLINE_RED, serverSet = mode == Mode.SERVER,
            server = if (mode == Mode.SERVER) server else null,
        )
    }

    @Test
    fun everyCellOfTheChipTable() {
        var pinned = 0
        for ((row, expected) in table) {
            val (dot, cells) = expected
            for ((col, want) in cells.withIndex()) {
                if (want == "n/a") continue
                val u = cell(row, col)
                assertEquals(row, u.row, "row of $row column $col")
                assertEquals(want, u.text, "$row column $col")
                assertEquals(dot, u.label.second, "dot of $row column $col")
                pinned++
            }
        }
        assertEquals(40, pinned, "every reachable cell of the table")
    }

    @Test
    fun theRedDotStaysWhereItWasAndOnlyTheWordChanges() {
        // T192: no server set (the public sources directly): offline is grey, not an error; with a server it is red either way.
        assertEquals("Offline" to OnlineUi.Dot.GREY, OnlineUi("offline", null, emptyMap(), false, direct = true).label)
        assertEquals("Server offline" to OnlineUi.Dot.RED, OnlineUi("offline", null, emptyMap(), false, network = true).label)
        assertEquals("Offline" to OnlineUi.Dot.RED, OnlineUi("offline", null, emptyMap(), false, network = false).label)
        // A fetcher of nearby phones alone (no server, direct off): the grey row, and its network column names the phone.
        val peers = OnlineUi("offline", view(OnlineService.ONLINE), emptyMap(), false, direct = false, serverSet = false)
        assertEquals("Offline · viewed earlier" to OnlineUi.Dot.GREY, peers.text to peers.label.second)
        assertEquals("Online · from a nearby phone", OnlineUi("online", view(OnlineService.ONLINE), emptyMap(), false, serverSet = false).text)
        // With no network the state is never Online (the phone's own reader answers its probe without one), and no download is offered.
        val noNet = OnlineUi("online", view(OnlineService.CACHED), mapOf("state" to "idle"), false, direct = true, network = false)
        assertEquals("Offline · viewed earlier" to OnlineUi.Dot.GREY, noNet.text to noNet.label.second)
        assertFalse(noNet.offerDownload)
        assertEquals(OnlineUi.Row.OFFLINE_RED, OnlineUi("online", null, emptyMap(), false, network = false).row)
        // ADR 0023: Paused (the session limit or mobile data off) and Connecting keep their words and grey.
        assertEquals("Paused" to OnlineUi.Dot.GREY, OnlineUi("online", view(OnlineService.ONLINE), emptyMap(), false, notice = "limit", direct = true).label)
        assertEquals("Connecting" to OnlineUi.Dot.GREY, OnlineUi("unknown", null, emptyMap(), false).label)
    }

    /** T236: the card's second table: the first sentence and link by state, the second sentence and link by source. */
    @Test
    fun theCardExplainsBothParts() {
        val states = linkedMapOf(
            OnlineUi.Row.ONLINE to ("The phone can reach the map sources." to null),
            OnlineUi.Row.PAUSED to ("Paused: the session limit is reached." to "Settings › Sources ›"),
            OnlineUi.Row.SERVER_OFFLINE to ("Your server at $server is not answering. It may be off or out of reach." to "Settings › Sources ›"),
            OnlineUi.Row.OFFLINE_RED to ("No connection, so your server at $server cannot be reached. The map still works where it is on this phone." to "Settings › Sources ›"),
            // T255 (T236's follow-up): no server set, a network, and the public sources failing: not "No connection".
            OnlineUi.Row.OFFLINE_GREY to ("The public map sources are not answering. The map still works where it is on this phone." to null),
            OnlineUi.Row.CONNECTING to ("Checking the connection." to null),
            OnlineUi.Row.NO_SERVER to ("No server is set and the public sources are off." to "Settings › Sources ›"),
        )
        for ((row, want) in states) {
            val u = cell(row, 5) // no data: the source's own link is Offline maps, so a state's link here shows it comes first
            assertEquals(want.first, u.stateSentence, "$row")
            assertEquals(want.second ?: "Offline maps ›", u.link?.text, "link of $row")
        }
        val sources = listOf(
            0 to ("This part of the map is saved on this phone as an offline map." to "Offline maps ›"),
            1 to ("This part of the map is coming over the network and is kept only until space is needed." to null),
            2 to ("This part of the map is coming over the network and is kept only until space is needed." to null),
            3 to ("This part of the map is kept on this phone as map tiles only, so search and routes do not work here." to "Offline maps ›"),
            4 to ("You viewed this part earlier. It stays until space is needed." to "Offline maps ›"),
            5 to ("Nothing on this phone covers this view." to "Offline maps ›"),
            6 to ("This part of the map is cut from a wider zoom kept on this phone, so it shows less detail." to "Offline maps ›"),
        )
        for ((col, want) in sources) {
            val u = cell(OnlineUi.Row.ONLINE, col)
            assertEquals(want.first, u.sourceSentence, "column $col")
            assertEquals(want.second, u.link?.text, "link of column $col")
        }
        // A stale network memo reads as viewed earlier on the card too.
        assertEquals("You viewed this part earlier. It stays until space is needed.", cell(OnlineUi.Row.CONNECTING, 2).sourceSentence)
        val json = cell(OnlineUi.Row.SERVER_OFFLINE, 4).chipJson(open = true)
        assertEquals("Server offline · viewed earlier", json["text"])
        assertEquals("red", json["dot"])
        assertEquals(true, json["open"])
        assertEquals("Settings › Sources ›", json["link"])
    }

    /** T255: the grey row's two first sentences (no network at all, or a network and the public sources failing), and the wider column's JSON. */
    @Test
    fun noConnectionOnlyWithoutANetworkAndAWiderViewIsNotNoData() {
        val noNet = OnlineUi("offline", view(OnlineService.CACHED), emptyMap(), false, direct = true, network = false)
        assertEquals(OnlineUi.Row.OFFLINE_GREY, noNet.row)
        assertEquals("No connection. The map still works where it is on this phone.", noNet.stateSentence)
        val failing = OnlineUi("offline", view(OnlineService.CACHED), emptyMap(), false, direct = true, network = true)
        assertEquals(OnlineUi.Row.OFFLINE_GREY, failing.row)
        assertEquals(OnlineUi.SOURCES_NOT_ANSWERING, failing.stateSentence)
        // A fetcher of nearby phones alone: no public sources to blame.
        assertEquals("No connection. The map still works where it is on this phone.", OnlineUi("offline", null, emptyMap(), false, direct = false, serverSet = false).stateSentence)
        val wider = OnlineUi("offline", view(OnlineTiles.WIDER), emptyMap(), false, direct = true, network = false).chipJson(open = true)
        assertEquals("Offline · from a wider zoom", wider["text"])
        assertEquals("wider", wider["column"])
        assertEquals(listOf("No connection. The map still works where it is on this phone.", OnlineUi.CUT_FROM_WIDER), wider["detail"])
        assertEquals("Offline maps ›", wider["link"])
        // T264: the built-in world overview names itself.
        val world = OnlineUi("offline", view(OnlineTiles.WORLD), emptyMap(), false, direct = true, network = false).chipJson(open = true)
        assertEquals("Offline · from the world overview", world["text"])
        assertEquals("world", world["column"])
        assertEquals(listOf("No connection. The map still works where it is on this phone.", OnlineUi.FROM_WORLD), world["detail"])
        assertEquals("Offline maps ›", world["link"])
    }

    /** T236 with T242: the card's figure reads the cache's getters, and says the indexing line while they are partial. */
    @Test
    fun theCardsFigureWaitsForTheIndex() {
        fun figure(partial: Boolean) = OnlineUi(
            "online", view(OnlineService.CACHED), emptyMap(), false,
            cache = OnlineUi.CacheFigures(bytes = 300L * 1024 * 1024, pinnedBytes = 180L * 1024 * 1024, capMb = 2048, partial = partial),
        ).cacheLine
        assertEquals(TileCache.INDEXING_LINE, figure(partial = true))
        assertEquals("Viewed earlier: 120 MB of 2 GB", figure(partial = false))
        assertNull(OnlineUi("online", null, emptyMap(), false).cacheLine)
    }

    @Test
    fun downloadOfferedOnlyOnlineOverNonPackGroundWithNothingRunning() {
        assertTrue(OnlineUi("online", view(OnlineService.CACHED), mapOf("state" to "idle"), false).offerDownload)
        assertFalse(OnlineUi("online", view(OnlineTiles.PACK), mapOf("state" to "idle"), false).offerDownload)
        assertFalse(OnlineUi("offline", view(OnlineService.CACHED), mapOf("state" to "idle"), false).offerDownload)
        assertFalse(OnlineUi("online", null, mapOf("state" to "idle"), false).offerDownload)
        assertFalse(OnlineUi("online", view(OnlineService.CACHED), mapOf("state" to "building"), true).offerDownload)
    }

    @Test
    fun progressWhileRunning() {
        val d = mapOf<String, Any?>("state" to "building", "name" to "view-x", "percent" to 24.6, "packLabel" to "outdoors, Overpass")
        assertEquals("Building on server view-x (outdoors, Overpass): 25%", OnlineUi("online", null, d, true).progress)
        assertEquals("Building on this phone view-x (outdoors, Overpass): 25%", OnlineUi("online", null, d, true, direct = true).progress)
        assertEquals("Downloading view-x: 60%", OnlineUi("online", null, mapOf("state" to "downloading", "name" to "view-x", "percent" to 60), true).progress)
        assertNull(OnlineUi("online", null, d, false).progress)
    }

    @Test
    fun savedAsAnOfflineMapOnceTheViewIsThePacksAndFailuresInRed() {
        val done = mapOf<String, Any?>("state" to "done", "name" to "view-x", "packLabel" to "outdoors, Overpass")
        assertEquals("Saved as an offline map: view-x · outdoors, Overpass" to null, OnlineUi("online", view(OnlineTiles.PACK), done, false).result)
        assertNull(OnlineUi("online", view(OnlineService.CACHED), done, false).result, "not before the map shows the pack")
        assertEquals("Download failed: HTTP 503" to OnlineUi.Dot.RED, OnlineUi("online", null, mapOf("state" to "failed", "error" to "HTTP 503"), false).result)
        assertNull(OnlineUi("online", null, mapOf("state" to "idle"), false).result)
    }
}
