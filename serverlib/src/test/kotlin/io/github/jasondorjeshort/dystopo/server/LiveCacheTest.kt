package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpServer
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.style.AssetManifest
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.Delete
import io.github.jasondorjeshort.dystopo.sync.EntityKind
import io.github.jasondorjeshort.dystopo.sync.Hlc
import io.github.jasondorjeshort.dystopo.sync.Op
import io.github.jasondorjeshort.dystopo.sync.VersionVector
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T74: the live server's content versions, asset manifest, strict glyph and units answers, derived cache and caps. */
class LiveCacheTest {
    private val dir: File = Files.createTempDirectory("live-cache-test").toFile()
    private val servers = ArrayList<HttpServer>()
    private val client = HttpClient.newHttpClient()

    @AfterTest
    fun cleanUp() {
        servers.forEach { it.stop(0) }
        dir.deleteRecursively()
    }

    private fun slopePng(base: Float): ByteArray {
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        for (j in 0 until 256) for (i in 0 until 256) img.setRGB(i, j, Terrarium.encode(base + i * 2f + j))
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun live(renders: AtomicInteger = AtomicInteger(), caps: LiveTiles.CacheCaps = LiveTiles.CacheCaps(), tileRoot: File = File(dir, "tiles")) = LiveTiles(
        emptyList(), emptyList(), cacheRoot = File(dir, "cache"), outDir = File(dir, "out"), threads = 2,
        generateDem = { renders.incrementAndGet(); LiveTiles.LiveDem(slopePng(1000f), listOf("copernicus")) },
        fetchOfm = { null },
        ofmTileJson = { """{"tiles":["https://tiles.example/planet/20260101_001/{z}/{x}/{y}.pbf"]}""" },
        tileCacheRoot = tileRoot, caps = caps,
    )

    private fun serve(live: LiveTiles, glyphs: File): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        server.createContext("/", live.handler(glyphs) { "http://127.0.0.1:${server.address.port}" })
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun get(url: String): HttpResponse<ByteArray> = client.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    private fun glyphDir(): File = File(dir, "glyphs").apply {
        File(this, "Noto Sans Regular").mkdirs()
        File(this, "Noto Sans Regular/0-255.pbf").writeBytes(LiveTiles.emptyGlyphRange("Noto Sans Regular", "0-255") + byteArrayOf(0x12, 0))
        File(this, "Noto Sans Regular/256-511.pbf").writeBytes(ByteArray(0)) // an empty file is not a range
    }

    @Test
    fun glyphsAreARangeFileOrA404NeverAnEmpty200() {
        val base = serve(live(), glyphDir())
        val real = get("$base/glyphs/Noto%20Sans%20Regular/0-255.pbf")
        assertEquals(200, real.statusCode())
        assertTrue(real.body().isNotEmpty())
        // A range the server has no file for (or an empty file): a valid PBF with no glyphs, never zero bytes.
        for (range in listOf("256-511", "5120-5375")) {
            val r = get("$base/glyphs/Noto%20Sans%20Regular/$range.pbf")
            assertEquals(200, r.statusCode())
            assertTrue(r.body().isNotEmpty(), range)
        }
        assertEquals(404, get("$base/glyphs/Noto%20Sans%20Regular/99999999999999-1.pbf").statusCode())
        assertEquals(404, get("$base/glyphs/Some%20Other%20Font/0-255.pbf").statusCode())
    }

    @Test
    fun unknownUnitsAreA400() {
        val base = serve(live(), glyphDir())
        assertEquals(400, get("$base/style.json?units=furlongs").statusCode())
        assertEquals(400, get("$base/contours/12/655/1431.pbf?units=parsecs").statusCode())
        assertEquals(400, get("$base/live/units?units=x").statusCode())
        assertEquals(200, get("$base/style.json?units=m").statusCode())
    }

    @Test
    fun versionsAndTheAssetManifest() {
        val lt = live()
        val base = serve(lt, glyphDir())
        val v = MiniJson.parseObject(get("$base/version").body().decodeToString())
        assertTrue((v["dem"] as String).startsWith(LiveTiles.CACHE_VERSION + "."))
        assertEquals("20260101_001.${(v["osm"] as String).substringAfter('.')}", v["osm"])
        val dem = get("$base/dem/12/655/1431.png")
        assertEquals(v["dem"], dem.headers().firstValue(LiveTiles.VERSION_HEADER).orElse(null))
        val m = AssetManifest.parse(get("$base${AssetManifest.ROUTE}").body().decodeToString())
        assertEquals(v["assets"], m.version)
        assertEquals(listOf("glyphs/Noto Sans Regular/0-255.pbf") + io.github.jasondorjeshort.dystopo.style.Sprites.FILES.map { "sprites/$it" }.sorted(), m.files.map { it.path })
        val sprite = m.files.first { it.path == "sprites/sprite.png" }
        assertEquals(LiveTiles.sha256(get("$base${sprite.url}").body()), sprite.sha256)
        // A changed sprite sheet changes the manifest (after its 30 s memo; a new server instance here).
        File(dir, "out/sprite.png").appendBytes(byteArrayOf(0))
        val again = serve(live(), File(dir, "glyphs"))
        assertNotEquals(m.version, AssetManifest.parse(get("$again${AssetManifest.ROUTE}").body().decodeToString()).version)
    }

    @Test
    fun aNewPackChangesTheDemVersion() {
        val lt = live()
        val before = lt.demVersion
        val pack = File(dir, "out/extra-dem.pmtiles")
        pack.parentFile.mkdirs()
        val w = io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter(io.github.jasondorjeshort.dystopo.pmtiles.TileType.PNG, staging = io.github.jasondorjeshort.dystopo.pmtiles.MemoryTileStaging())
        w.addTile(10, 163, 357, slopePng(10f))
        pack.outputStream().buffered().use { os ->
            w.finish(io.github.jasondorjeshort.dystopo.pmtiles.ByteSink { os.write(it) }, "{}", -122.0, 46.0, -121.0, 47.0, centerZoom = 10, minZoom = 10, maxZoom = 10, centerLon = -121.5, centerLat = 46.5)
        }
        lt.addPack(pack)
        assertNotEquals(before, lt.demVersion)
    }

    @Test
    fun contoursAndShadeComeFromTheDerivedCacheAndStoresStayUnderTheirCaps() {
        val renders = AtomicInteger()
        val caps = LiveTiles.CacheCaps(liveMb = 1, openfreemapMb = 1, derivedMb = 1)
        val lt = live(renders, caps)
        val first = lt.contours(12, 655, 1431)!!
        val n = renders.get()
        val warm = lt.contours(12, 655, 1431)!!
        assertEquals(LiveTiles.DERIVED_CACHE, warm.source)
        assertTrue(first.bytes.contentEquals(warm.bytes))
        assertEquals(n, renders.get())
        lt.shade(12, 655, 1431, mapOf("hillshade" to "multi"))
        assertEquals(LiveTiles.DERIVED_CACHE, lt.shade(12, 655, 1431, mapOf("hillshade" to "multi"))!!.source)
        // Another interval is another key.
        assertNotEquals(LiveTiles.DERIVED_CACHE, lt.contours(12, 655, 1431, query = mapOf("interval" to "100"))!!.source)
        // Many live tiles: the live store stays under 1 MB.
        for (x in 600 until 640) lt.dem(12, x, 1431)
        val deadline = System.currentTimeMillis() + 10_000
        while (!lt.liveCache.indexed && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val cap = lt.liveCache.bytes / 2 // the fake tiles compress well: a cap below 1 MB to see eviction
        lt.liveCache.resize(cap)
        for (x in 640 until 660) lt.dem(12, x, 1431)
        assertTrue(lt.liveCache.bytes <= cap, "live cache ${lt.liveCache.bytes} over $cap")
        assertTrue(lt.liveCache.evictions.get() > 0)
        val onDisk = File(dir, "tiles/live").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(lt.liveCache.bytes, onDisk)
        val stats = lt.stats()
        @Suppress("UNCHECKED_CAST")
        val c = stats["caches"] as Map<String, Map<String, Any?>>
        assertEquals(cap, c.getValue("live")["maxBytes"])
        assertEquals(1024L * 1024, c.getValue("derived")["maxBytes"])
        assertNotNull(c.getValue("derived")["hits"])
    }

    @Test
    fun pushesAreCappedBeforeTheyInflate() {
        // 8 MiB of zeros inflates past a 1 MiB cap: refused with 413 without inflating it all.
        val zeros = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { g -> val b = ByteArray(1 shl 20); repeat(8) { g.write(b) } } }.toByteArray()
        val r = SyncRoutes.checkPush(zeros, maxInflated = 1L shl 20)
        assertTrue(r is SyncRoutes.PushCheck.Refused && r.status == 413, "$r")
        assertEquals(400, (SyncRoutes.checkPush(byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)) as SyncRoutes.PushCheck.Refused).status)
        val ok = Bundle("dev", VersionVector.EMPTY, VersionVector.EMPTY, emptyList()).encode()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(ok) } }.toByteArray()
        val checked = SyncRoutes.checkPush(gz)
        assertTrue(checked is SyncRoutes.PushCheck.Ok && checked.raw.contentEquals(ok))
        assertTrue(SyncRoutes.checkPush(ok) is SyncRoutes.PushCheck.Ok)
        assertFalse(SyncRoutes.checkPush(ok, maxInflated = 4) is SyncRoutes.PushCheck.Ok)
    }

    @Test
    fun pushesAreCappedAtOneHundredThousandOperations() {
        // T92: the T74 operation cap at its real value. 100,000 operations (the largest page a pull hands out) pass,
        // one more is a 413 that says to push in pages; both well under the 64 MiB inflate cap, so the count decides.
        val device = "5b8e2f0a-6c1d-4f3e-9a7b-2d4c6e8f0a1b"
        fun bundle(n: Int): ByteArray {
            val ops = List(n) { i -> Op(Hlc(1_760_000_000_000L + i / 1000, i % 1000, device), Delete(EntityKind.ROUTE, "r$i")) }
            val raw = Bundle(device, VersionVector.EMPTY, VersionVector.EMPTY, ops).encode()
            return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
        }
        assertEquals(100_000, SyncRoutes.MAX_PUSH_OPS)
        val atCap = SyncRoutes.checkPush(bundle(SyncRoutes.MAX_PUSH_OPS))
        assertTrue(atCap is SyncRoutes.PushCheck.Ok && atCap.ops == 100_000, "$atCap")
        val over = SyncRoutes.checkPush(bundle(SyncRoutes.MAX_PUSH_OPS + 1))
        assertTrue(over is SyncRoutes.PushCheck.Refused && over.status == 413 && "100001 operations" in over.message && "pages" in over.message, "$over")
    }
}
