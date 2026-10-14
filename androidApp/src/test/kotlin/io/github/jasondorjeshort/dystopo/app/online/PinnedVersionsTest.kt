package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TileCover
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T95 (the critic's blocking objection to ADR 0027): a pinned area's tiles survive a change of the source's content
 * version and are served at the version they were fetched at, offline and online, until the area is refreshed; then
 * what no pin holds any more goes. The real [OnlineFetcher] and [TileCache] over a fake versioned server.
 */
class PinnedVersionsTest {
    private val dir: File = Files.createTempDirectory("pinned-versions").toFile()
    private val versionFile = File(dir.parentFile, "${dir.name}-versions.json")
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
        versionFile.delete()
    }

    /** A server with `/version`; a tile's body is its URL plus the version it was made at. */
    private class Server(var versions: Map<String, String>) {
        val tileCalls = AtomicInteger()
        fun get(url: String): HttpReply = when {
            url.endsWith("/version") -> HttpReply(200, versions.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }.toByteArray())
            else -> {
                tileCalls.incrementAndGet()
                val v = versions[if ("/osm/" in url) "osm" else "dem"] ?: ""
                HttpReply(200, "$url@$v".toByteArray(), mapOf("x-dystopo-version" to v))
            }
        }
    }

    /** The phone's areas, reduced to one corridor: its cover and the versions it recorded (what [adopt] fills in). */
    private class Pins(val cover: Map<Int, io.github.jasondorjeshort.dystopo.areas.TileSet>) : VersionPins {
        var versions: Map<String, Map<String, String>> = emptyMap()
        var pinned = true
        val adopted = ArrayList<String>()
        override fun pins() = if (pinned) AreaPins(listOf(cover), listOf(versions)) else AreaPins.NONE
        override fun adopt(source: String, name: String, old: String) {
            if (!pinned || versions[source]?.containsKey(name) == true) return
            versions = versions + (source to (versions[source].orEmpty() + (name to old)))
            adopted += "$source/$name@$old"
        }
    }

    // Paradise to Panorama Point (public), 300 m each side.
    private val area = AreaOfInterest(AreaShape.Corridor(listOf(AreaPoint(46.7859, -121.7356), AreaPoint(46.8016, -121.7355), AreaPoint(46.8037, -121.7268)), 300.0), 8, 15)
    private val inArea = TileCover.of(area, 12).tiles().first()
    private val elsewhere = floor(WebMercator.lonToX(6.86, 12)).toInt() to floor(WebMercator.latToY(45.83, 12)).toInt() // Mont Blanc

    private fun file(key: String) = File(dir, key)

    @Test
    fun aPinnedAreaKeepsItsTilesThroughAVersionChangeAndIsServedAtItsVersion() {
        val server = Server(mapOf("dem" to "v.1", "osm" to "o.1"))
        val cache = TileCache(dir, 1L shl 24) { now }.apply { keep = { false } }
        val f = OnlineFetcher("http://10.0.2.2:8775", cache, server::get, clock = { now }, versionFile = versionFile)
        val pins = Pins(AreaPins.cover(area))
        f.pins = pins
        val src = f.serverKey
        val (x, y) = inArea
        val (ox, oy) = elsewhere
        f.get("dem", "12/$x/$y.png", fresh = true)
        f.get("osm", "12/$x/$y.pbf", fresh = true)
        f.get("dem", "12/$ox/$oy.png")
        cache.put("${f.derivedPrefix("contours-v2-ft-512")}/12/$x/$y.pbf", byteArrayOf(1)) // made on the phone from v.1
        pins.versions = f.currentVersions() // the download completed: the area records what it holds
        assertEquals(mapOf(src to mapOf("dem" to "v.1", "osm" to "o.1")), pins.versions)

        // The server builds a pack and picks up a new planet: both versions move on.
        server.versions = mapOf("dem" to "v.2", "osm" to "o.2")
        now += OnlineFetcher.VERSION_CHECK_MS
        f.checkVersions()
        assertEquals("v.2", f.version("dem"))
        assertTrue(file("$src/dem@v.1/12/$x/$y.png").isFile, "the area's DEM tile is kept at its version")
        assertTrue(file("$src/osm@o.1/12/$x/$y.pbf").isFile, "the area's vector tile is kept at its version")
        assertFalse(file("$src/dem@v.1/12/$ox/$oy.png").exists(), "a tile outside every pinned area goes as before")
        assertFalse(file("$src/contours-v2-ft-512@v.1/12/$x/$y.pbf").exists(), "tiles made from the old DEM go (made again from the kept DEM)")
        assertEquals(2L, f.pinnedKept.get())
        assertEquals(emptyList(), pins.adopted, "an area that recorded its versions adopts nothing")

        // Offline after a restart: the area's tiles are found at the version it holds.
        val offline = OnlineFetcher("http://10.0.2.2:8775", cache, { throw ConnectFailed("offline") }, clock = { now }, versionFile = versionFile)
        offline.pins = pins
        assertEquals("v.2", offline.version("dem"), "the new version was saved")
        val dem = assertIs<Fetch.Hit>(offline.get("dem", "12/$x/$y.png"))
        assertTrue(dem.cached && dem.bytes.decodeToString().endsWith("@v.1"))
        assertIs<Fetch.Hit>(offline.get("osm", "12/$x/$y.pbf"))
        assertIs<Fetch.Unavailable>(offline.get("dem", "12/$ox/$oy.png"))
        assertEquals(2L, offline.pinnedHits.get())

        // Online too, the area is served at its own version (no data spent) until it is refreshed...
        val calls = server.tileCalls.get()
        assertTrue(assertIs<Fetch.Hit>(f.get("dem", "12/$x/$y.png")).bytes.decodeToString().endsWith("@v.1"))
        assertEquals(calls, server.tileCalls.get())
        // ...which asks for the current version only (a download again, or the prefetch ahead on the route).
        val fresh = assertIs<Fetch.Hit>(f.get("dem", "12/$x/$y.png", fresh = true))
        assertFalse(fresh.cached)
        assertTrue(fresh.bytes.decodeToString().endsWith("@v.2"))
        assertTrue(assertIs<Fetch.Hit>(f.get("dem", "12/$x/$y.png")).bytes.decodeToString().endsWith("@v.2"), "the current version first once there")
        f.get("osm", "12/$x/$y.pbf", fresh = true)

        // The refresh completed: the area records v.2, and what no pin holds any more goes.
        pins.versions = f.currentVersions()
        assertEquals(2, f.dropUnheld())
        assertFalse(file("$src/dem@v.1/12/$x/$y.png").exists())
        assertFalse(file("$src/osm@o.1/12/$x/$y.pbf").exists())
        assertTrue(file("$src/dem@v.2/12/$x/$y.png").isFile)
        assertTrue(file("$src/osm@o.2/12/$x/$y.pbf").isFile)
    }

    @Test
    fun anAreaFromBeforeT95TakesTheOldVersionAtTheFirstChange() {
        val server = Server(mapOf("dem" to "v.1", "osm" to "o.1"))
        val cache = TileCache(dir, 1L shl 24) { now }.apply { keep = { false } }
        val f = OnlineFetcher("http://h:1", cache, server::get, clock = { now })
        val pins = Pins(AreaPins.cover(area)) // pinned, but no versions recorded
        f.pins = pins
        val (x, y) = inArea
        f.get("dem", "12/$x/$y.png")
        server.versions = mapOf("dem" to "v.2", "osm" to "o.1")
        now += OnlineFetcher.VERSION_CHECK_MS
        f.checkVersions()
        assertEquals(listOf("h_1/dem@v.1"), pins.adopted)
        assertTrue(file("h_1/dem@v.1/12/$x/$y.png").isFile, "kept: the area took the version its tiles were fetched at")
        assertTrue(assertIs<Fetch.Hit>(f.get("dem", "12/$x/$y.png")).bytes.decodeToString().endsWith("@v.1"))
    }

    @Test
    fun unpinnedTilesOfAnOldVersionGoAtTheChangeAndAfterAnUnpin() {
        val server = Server(mapOf("dem" to "v.1"))
        val cache = TileCache(dir, 1L shl 24) { now }.apply { keep = { false } }
        val f = OnlineFetcher("http://h:1", cache, server::get, clock = { now })
        val pins = Pins(AreaPins.cover(area))
        f.pins = pins
        val (x, y) = inArea
        f.get("dem", "12/$x/$y.png")
        pins.versions = f.currentVersions()
        server.versions = mapOf("dem" to "v.2")
        now += OnlineFetcher.VERSION_CHECK_MS
        f.checkVersions()
        assertTrue(file("h_1/dem@v.1/12/$x/$y.png").isFile)
        // Unpinned: nothing reaches the v.1 tile any more (lookups try only what pins hold), so it goes.
        pins.pinned = false
        assertEquals(1, f.dropUnheld())
        assertFalse(file("h_1/dem@v.1/12/$x/$y.png").exists())
        assertEquals(0, f.dropUnheld(), "nothing left to drop")
    }

    @Test
    fun aSourceWithoutVersionsIsLeftAlone() {
        val cache = TileCache(dir, 1L shl 24) { now }.apply { keep = { false } }
        val f = OnlineFetcher("http://h:1", cache, { url -> if (url.endsWith("/version")) HttpReply(404, ByteArray(0)) else HttpReply(200, byteArrayOf(1)) }, clock = { now })
        f.pins = Pins(AreaPins.cover(area))
        val (x, y) = inArea
        f.get("dem", "12/$x/$y.png")
        assertEquals(mapOf("h_1" to mapOf("dem" to "", "osm" to "")), f.currentVersions())
        assertEquals(0, f.dropUnheld())
        assertTrue(file("h_1/dem/12/$x/$y.png").isFile)
    }
}
