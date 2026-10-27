package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.FileItem
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.server.BuildRoutes
import io.github.jasondorjeshort.dystopo.server.LiveTiles
import io.github.jasondorjeshort.dystopo.server.PackCatalog
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T75: `POST /area` without the network (fake live DEM and OpenFreeMap, real archives in a temp out/). */
class AreaRoutesTest {
    private val dir: File = Files.createTempDirectory("area-routes-test").toFile()
    private val out = File(dir, "out")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val png: ByteArray = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).let { img ->
        for (j in 0 until 256) for (i in 0 until 256) img.setRGB(i, j, Terrarium.encode(1500f + i))
        ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun live() = LiveTiles(
        emptyList(), emptyList(), cacheRoot = File(dir, "cache"), outDir = out, threads = 2,
        generateDem = { LiveTiles.LiveDem(png, listOf("copernicus", "egm2008")) },
        fetchOfm = { gz("fake vector tile".toByteArray()) },
        ofmTileJson = { """{"tiles":["https://example.invalid/b7/{z}/{x}/{y}.pbf"],"vector_layers":[{"id":"water","fields":{}}]}""" },
    )

    // Paradise to Panorama Point (public trails), as a corridor.
    private val corridorBody = """{"kind":"corridor","line":[[-121.7356,46.7859],[-121.7355,46.8016],[-121.7268,46.8037]],"bufferM":300,"minZoom":8,"maxZoom":15,"name":"Skyline part"}"""

    @Test
    fun corridorIsTileListsWithEstimates() {
        val builds = BuildRoutes(live(), out)
        val (status, json) = builds.area(emptyMap(), corridorBody)
        assertEquals(200, status)
        val m = AreaManifest.parse(MiniJson.encode(json))
        assertEquals(AreaManifest.STATUS_READY, m.status)
        assertEquals("Skyline part", m.name)
        val dem = m.tiles("dem")!!
        assertEquals(LiveTiles.LIVE_MIN_ZOOM..15, dem.zooms)
        assertEquals(8..14, m.tiles("osm")!!.zooms)
        assertTrue(dem.tiles.getValue(15).size in 10..200, "z15 ${dem.tiles.getValue(15).size}")
        assertTrue(m.items.any { it.kind == "glyphs" } && m.items.any { it.kind == "sprite" })
        assertTrue(m.bytes > 0 && !m.bytesExact)
        // The same corridor by query parameters is the same area.
        val (_, q) = builds.area(mapOf("line" to "46.7859,-121.7356;46.8016,-121.7355;46.8037,-121.7268", "bufferM" to "300", "maxZoom" to "15"), "")
        assertEquals(m.id, q["id"])
    }

    @Test
    fun boxBuildsAPackThenListsItsFilesWithHashes() {
        val builds = BuildRoutes(live(), out)
        val body = """{"kind":"box","bbox":[-121.76,46.78,-121.72,46.81],"minZoom":10,"maxZoom":11}"""
        val (s1, j1) = builds.area(mapOf("name" to "paradise"), body)
        assertEquals(202, s1)
        assertEquals(AreaManifest.STATUS_BUILDING, j1["status"])
        val id = (j1["build"] as Map<*, *>)["id"].toString()
        val deadline = System.currentTimeMillis() + 30_000
        while (builds.job(id)!!.active && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(BuildRoutes.Status.DONE, builds.job(id)!!.status, builds.job(id)!!.json().toString())
        val (s2, j2) = builds.area(mapOf("name" to "paradise"), body)
        assertEquals(200, s2)
        val m = AreaManifest.parse(MiniJson.encode(j2))
        val packs = m.packs
        assertEquals(listOf("dem", "outdoors"), packs.map { it.role })
        for (p: FileItem in packs) {
            val f = File(out, p.file)
            assertEquals(f.length(), p.bytes)
            assertEquals(PackCatalog.hashFile(f), p.sha256)
            assertEquals("/out/${f.name}", p.url)
        }
        assertTrue(m.bytesExact)
        assertTrue(m.items.any { it.kind == "graph" } && m.items.any { it.kind == "search" })
        assertEquals("paradise", j2["pack"])
    }

    @Test
    fun badAndOversizedAreasAreRefused() {
        val builds = BuildRoutes(live(), out, maxAreaTiles = 50)
        assertEquals(400, assertFailsWith<BuildRoutes.Refused> { builds.area(emptyMap(), """{"kind":"corridor","line":[]}""") }.status)
        assertEquals(400, assertFailsWith<BuildRoutes.Refused> { builds.area(mapOf("bbox" to "1,2,3"), "") }.status)
        assertEquals(400, assertFailsWith<BuildRoutes.Refused> { builds.area(emptyMap(), "{not json") }.status)
        assertEquals(413, assertFailsWith<BuildRoutes.Refused> { builds.area(emptyMap(), corridorBody) }.status)
        // T140: a polygon over the cap gets the polygon's remedy.
        val big = assertFailsWith<BuildRoutes.Refused> { builds.area(emptyMap(), polygonBody) }
        assertEquals(413, big.status)
        assertTrue(big.message!!.endsWith("lower maxZoom or draw a smaller area"), big.message)
    }

    // T140: a drawn ring around Paradise (public), as GeoJSON closes it.
    private val polygonBody = """{"kind":"polygon","rings":[[[-121.76,46.77],[-121.71,46.77],[-121.71,46.80],[-121.76,46.80],[-121.76,46.77]]],"minZoom":8,"maxZoom":15,"name":"Paradise drawn"}"""

    @Test
    fun aPolygonIsTilesAsAVersion2ManifestAndNeverAPack() {
        val builds = BuildRoutes(live(), out)
        val (status, json) = builds.area(emptyMap(), polygonBody)
        assertEquals(200, status)
        assertEquals(2, json["version"])
        val m = AreaManifest.parse(MiniJson.encode(json))
        assertEquals("polygon", m.area.kind)
        assertEquals(LiveTiles.LIVE_MIN_ZOOM..15, m.tiles("dem")!!.zooms)
        assertEquals(8..14, m.tiles("osm")!!.zooms)
        assertTrue(m.packs.isEmpty() && m.tileCount > 0)
        // The same ring as query parameters (latitude first, open) is the same area.
        val (_, q) = builds.area(mapOf("ring" to "46.77,-121.76;46.77,-121.71;46.80,-121.71;46.80,-121.76", "maxZoom" to "15"), "")
        assertEquals(m.id, q["id"])
        // pack=1 on a polygon is refused with the sentence, in the query or the body.
        for ((params, body) in listOf(mapOf("pack" to "1") to polygonBody, emptyMap<String, String>() to """{"area":$polygonBody,"pack":1}""")) {
            val e = assertFailsWith<BuildRoutes.Refused> { builds.area(params, body) }
            assertEquals(400, e.status)
            assertEquals("a pack is a box; download a box for the trail graph", e.message)
        }
        // An unknown kind draws the sentence a phone falls back on.
        val k = assertFailsWith<BuildRoutes.Refused> { builds.area(emptyMap(), """{"kind":"region","code":"X"}""") }
        assertEquals(400, k.status)
        assertTrue(k.message!!.startsWith("kind must be"), k.message)
        // GET /area: the manifest version and the shapes this server reads.
        val help = builds.areaHelp()
        assertEquals(2, help["manifestVersion"])
        assertEquals(listOf("box", "corridor", "polygon"), help["shapes"])
    }

    @Test
    fun aPolygonOverTheTileLimitIsRefusedQuickly() {
        val builds = BuildRoutes(live(), out)
        // Most of North America at z8 to z16: millions of tiles. T152: its sets are rows, so they are filled and counted at
        // once, and the server's own tile limit (DYSTOPO_AREA_MAX_TILES) refuses it; no heap check comes first.
        val body = """{"kind":"polygon","rings":[[[-125,25],[-70,25],[-70,50],[-125,50]]],"minZoom":8,"maxZoom":16}"""
        val t0 = System.currentTimeMillis()
        val e = assertFailsWith<BuildRoutes.Refused> { builds.area(emptyMap(), body) }
        assertEquals(413, e.status)
        assertTrue(e.message!!.startsWith("the area needs ") && "DYSTOPO_AREA_MAX_TILES" in e.message!!, e.message)
        assertTrue(System.currentTimeMillis() - t0 < 5000, "rows, not tiles: ${System.currentTimeMillis() - t0} ms")
    }
}
