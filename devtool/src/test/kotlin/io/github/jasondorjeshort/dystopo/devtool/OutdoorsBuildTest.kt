package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.packs.OsmPackMetadata
import io.github.jasondorjeshort.dystopo.packs.OsmPackWriter
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /build?profile=outdoors` without the network: fake Overpass, fake tilebuild, fake live sources. */
class OutdoorsBuildTest {
    private val dir: File = Files.createTempDirectory("outdoors-build-test").toFile()
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

    private val tileJson = """{"tiles":["https://example.invalid/planet/b7/{z}/{x}/{y}.pbf"],"vector_layers":[{"id":"water","fields":{},"minzoom":0,"maxzoom":14}]}"""

    /** Paradise, Mount Rainier: about 0.012 square degrees. */
    private val bbox = "-121.78,46.76,-121.68,46.88"

    private val ofmFetches = AtomicInteger()

    private fun live() = LiveTiles(
        emptyList(), emptyList(), cacheRoot = File(dir, "cache"), outDir = out, threads = 2,
        generateDem = { LiveTiles.LiveDem(png, listOf("3dep", "geoid18", "egm2008")) },
        fetchOfm = { ofmFetches.incrementAndGet(); gz("fake vector tile".toByteArray()) },
        ofmTileJson = { tileJson },
    )

    private val overpassXml = """<?xml version="1.0" encoding="UTF-8"?>
        <osm version="0.6" generator="Overpass API"><node id="1" lat="46.78" lon="-121.73"><tag k="natural" v="peak"/></node></osm>
    """.trimIndent()

    /** Fake Overpass: counts requests, answers [status] with [body]. */
    private class FakePost(var status: Int = 200, var body: String = "") {
        val calls = AtomicInteger()
        var lastBody: String? = null
        fun post(url: String, body: String, timeout: Duration, into: File): Int {
            calls.incrementAndGet()
            lastBody = body
            into.writeText(this.body)
            return status
        }
    }

    /** Fake tilebuild: writes a small vector archive whose metadata says outdoors, crediting what it is told. */
    private class FakeTilebuild : OutdoorsTileBuilder {
        val calls = AtomicInteger()
        var lastXml: File? = null
        override fun unavailable(): String? = null
        override fun describe() = "fake"
        override fun build(
            osmXml: File, out: File, box: BoundingBox, zooms: IntRange, sourceIds: List<String>,
            cancelled: () -> Boolean, log: (String) -> Unit,
        ): Map<String, Any?> {
            calls.incrementAndGet()
            lastXml = osmXml
            log("fake tilebuild running")
            val writer = OsmPackWriter(Gzip.compress)
            for (z in zooms) for (t in io.github.jasondorjeshort.dystopo.geo.WebMercator.tilesCovering(box, z)) writer.add(t.z, t.x, t.y, "mvt $z".toByteArray())
            val ids = listOf("osm", "osm-water-polygons", "natural-earth", "openmaptiles") + sourceIds
            val registry = SourceRegistry.builtIn
            val meta = OsmPackMetadata(
                name = out.nameWithoutExtension, bounds = listOf(box.west, box.south, box.east, box.north),
                minZoom = zooms.first, maxZoom = zooms.last, vectorLayers = emptyList(),
                attribution = registry.attribution(ids), attributionHtml = registry.attributionHtml(ids),
                provenance = ids, license = "ODbL-1.0", profile = "outdoors", schema = "Dystopo outdoors 1",
            )
            out.parentFile.mkdirs()
            out.outputStream().use { os -> writer.finish(ByteSink { os.write(it) }, meta) }
            return mapOf("totalSeconds" to 1.5, "profile" to "Dystopo outdoors 1")
        }
    }

    private fun await(b: BuildRoutes, id: String): BuildRoutes.Job {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val j = b.job(id)!!
            if (!j.active) return j
            Thread.sleep(20)
        }
        error("build $id did not finish: ${b.job(id)!!.json()}")
    }

    private fun routes(post: FakePost, tb: OutdoorsTileBuilder = FakeTilebuild(), maxArea: Double = 0.05) =
        BuildRoutes(live(), out, OverpassSource(File(dir, "cache/overpass"), "https://overpass.invalid/api/interpreter", maxArea, 60, post::post), tb)

    @Test
    fun outdoorsBuildFetchesOverpassOnceAndCachesIt() {
        val post = FakePost(body = overpassXml)
        val tb = FakeTilebuild()
        val builds = routes(post, tb)
        val job = builds.submit(mapOf("name" to "paradise", "bbox" to bbox, "minZoom" to "10", "maxZoom" to "12", "profile" to "outdoors"))
        val done = await(builds, job.id)
        assertEquals(BuildRoutes.Status.DONE, done.status, done.json().toString())
        assertEquals("outdoors", done.osmProfile)
        assertTrue(done.warnings.isEmpty(), done.warnings.toString())
        assertEquals(1, post.calls.get())
        assertEquals(0, ofmFetches.get(), "no OpenFreeMap tiles for an outdoors pack")
        assertTrue(post.lastBody!!.startsWith("data="), "form-encoded query")
        val osm = File(out, "paradise-outdoors.pmtiles")
        val meta = PmtilesReader.open(FileRangeSource(osm), Gzip.decompressors).metadata()
        assertEquals("outdoors", meta["dystopo:profile"])
        assertTrue("overpass" in done.osmProvenance, done.osmProvenance.toString())
        assertEquals(false, done.overpassInfo!!["cached"])
        assertTrue(done.osm.written.get() > 0)
        assertTrue(out.listFiles()!!.none { it.name.startsWith(".tilebuild") || it.name.endsWith(".building") }, out.list()!!.toList().toString())

        // Rebuild of the same box: the cached answer, no second request.
        val again = builds.submit(mapOf("name" to "paradise", "bbox" to bbox, "minZoom" to "10", "maxZoom" to "12", "profile" to "outdoors", "overwrite" to "1"))
        val done2 = await(builds, again.id)
        assertEquals(BuildRoutes.Status.DONE, done2.status, done2.json().toString())
        assertEquals(1, post.calls.get(), "cache hit on rebuild")
        assertEquals(true, done2.overpassInfo!!["cached"])
        assertEquals(1L, builds.overpass.cacheHits)
        assertEquals(2, tb.calls.get())
    }

    @Test
    fun tooBigBoxIs413UnlessFallbackAsked() {
        val post = FakePost(body = overpassXml)
        val builds = routes(post, maxArea = 0.005)
        val e = assertFailsWith<BuildRoutes.Refused> {
            builds.submit(mapOf("name" to "big", "bbox" to bbox, "minZoom" to "10", "maxZoom" to "11", "profile" to "outdoors"))
        }
        assertEquals(413, e.status)
        assertTrue("square degrees" in e.message!! && "fallback=1" in e.message!!, e.message)
        assertEquals(0, post.calls.get(), "nothing sent for a box over the cap")

        val job = builds.submit(mapOf("name" to "big", "bbox" to bbox, "minZoom" to "10", "maxZoom" to "11", "profile" to "outdoors", "fallback" to "1"))
        val done = await(builds, job.id)
        assertEquals(BuildRoutes.Status.DONE, done.status, done.json().toString())
        assertEquals("openmaptiles", done.osmProfile)
        assertTrue(done.warnings.single().contains("OpenFreeMap"), done.warnings.toString())
        assertEquals(0, post.calls.get())
        assertTrue(ofmFetches.get() > 0)
    }

    @Test
    fun overpassRefusalFallsBackToOpenFreeMapWithoutRetry() {
        val post = FakePost(status = 429, body = "rate limited")
        val builds = routes(post)
        val job = builds.submit(mapOf("name" to "busy", "bbox" to bbox, "minZoom" to "10", "maxZoom" to "11", "profile" to "outdoors"))
        val done = await(builds, job.id)
        assertEquals(BuildRoutes.Status.DONE, done.status, done.json().toString())
        assertEquals(1, post.calls.get(), "one request, never retried")
        assertEquals("openmaptiles", done.osmProfile)
        assertTrue(done.warnings.single().contains("429"), done.warnings.toString())
        val meta = PmtilesReader.open(FileRangeSource(File(out, "busy-outdoors.pmtiles")), Gzip.decompressors).metadata()
        assertEquals("openmaptiles", meta["dystopo:profile"])
        assertNull(File(dir, "cache/overpass").listFiles()?.firstOrNull { it.name.endsWith(".osm") }, "a refusal is not cached")

        // Within the cool-down after a refusal, the next build does not ask Overpass at all.
        post.status = 200
        post.body = overpassXml
        val next = builds.submit(mapOf("name" to "busy2", "bbox" to bbox, "minZoom" to "10", "maxZoom" to "11", "profile" to "outdoors"))
        val done2 = await(builds, next.id)
        assertEquals(1, post.calls.get(), "cool-down: no request")
        assertEquals("openmaptiles", done2.osmProfile)
        assertTrue(done2.warnings.single().contains("not asking again"), done2.warnings.toString())
    }

    @Test
    fun errorRemarkIsARefusalAndNotCached() {
        val post = FakePost(body = """<osm version="0.6"><node id="1" lat="1" lon="1"/><remark> runtime error: Query timed out in "query" at line 3 after 61 seconds. </remark></osm>""")
        val src = OverpassSource(File(dir, "op"), "https://overpass.invalid/x", 0.05, 60, post::post)
        val e = assertFailsWith<OverpassSource.Refused> { src.extract(BoundingBox(-121.78, 46.76, -121.68, 46.88)) }
        assertTrue("timed out" in e.message!!, e.message)
        assertTrue(File(dir, "op").listFiles()!!.isEmpty())
    }

    @Test
    fun badProfileAndMissingTilebuild() {
        val post = FakePost(body = overpassXml)
        val builds = routes(post)
        assertEquals(400, assertFailsWith<BuildRoutes.Refused> { builds.submit(mapOf("name" to "x", "bbox" to bbox, "profile" to "fancy")) }.status)
        val missing = BuildRoutes(
            live(), out, OverpassSource(File(dir, "op2"), "https://overpass.invalid/x", 0.05, 60, post::post),
            TilebuildProcess(home = File(dir, "nowhere")),
        )
        val e = assertFailsWith<BuildRoutes.Refused> { missing.submit(mapOf("name" to "x", "bbox" to bbox, "profile" to "outdoors")) }
        assertEquals(503, e.status)
        assertTrue("installDist" in e.message!!, e.message)
    }

    @Test
    fun queryAsksOnceForCompleteWaysAndAreaRelations() {
        val q = OverpassSource.query(BoundingBox(-121.78, 46.76, -121.68, 46.88), 120)
        assertTrue(q.startsWith("[out:xml][timeout:120][maxsize:"), q)
        assertTrue("[bbox:46.760000,-121.780000,46.880000,-121.680000]" in q, "Overpass bbox order is south,west,north,east: $q")
        assertTrue("protected_area" in q && "multipolygon" in q, q)
        assertEquals(1, Regex("\\bout\\b body;").findAll(q).count(), "a single output statement")
        assertEquals(
            OverpassSource.key(BoundingBox(-121.780001, 46.76, -121.68, 46.88)),
            OverpassSource.key(BoundingBox(-121.78, 46.76, -121.68, 46.88)),
            "cache key rounds to about a metre",
        )
    }
}
