package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.OsmPackMetadata
import io.github.jasondorjeshort.dystopo.packs.OsmPackWriter
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T64: OSM extracts on disk (PBF header, sidecars, coverage) and outdoors builds from them, offline. */
class ExtractsTest {
    private val dir: File = Files.createTempDirectory("extracts-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // ---- a synthetic .osm.pbf header (the first blob only, which is all Extracts reads) ----

    private fun varint(o: ByteArrayOutputStream, v: Long) {
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            o.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        o.write(x.toInt())
    }

    private fun tag(o: ByteArrayOutputStream, field: Int, wire: Int) = varint(o, (field shl 3 or wire).toLong())
    private fun bytesField(o: ByteArrayOutputStream, field: Int, b: ByteArray) {
        tag(o, field, 2); varint(o, b.size.toLong()); o.write(b)
    }
    private fun zigzag(v: Long) = (v shl 1) xor (v shr 63)

    /** A PBF whose header says [bbox] (w, s, e, n) and the replication time [ts] (seconds), zlib-compressed. */
    private fun pbf(file: File, bbox: BoundingBox?, ts: Long?) {
        val block = ByteArrayOutputStream()
        if (bbox != null) {
            val b = ByteArrayOutputStream()
            listOf(bbox.west, bbox.east, bbox.north, bbox.south).forEachIndexed { i, d -> tag(b, i + 1, 0); varint(b, zigzag(Math.round(d * 1e9))) }
            bytesField(block, 1, b.toByteArray())
        }
        bytesField(block, 4, "OsmSchema-V0.6".toByteArray())
        bytesField(block, 16, "test".toByteArray())
        if (ts != null) { tag(block, 32, 0); varint(block, ts) }
        val raw = block.toByteArray()
        val z = Deflater().run { setInput(raw); finish(); val buf = ByteArray(raw.size + 64); val n = deflate(buf); end(); buf.copyOf(n) }
        val blob = ByteArrayOutputStream().also { tag(it, 2, 0); varint(it, raw.size.toLong()); bytesField(it, 3, z) }.toByteArray()
        val header = ByteArrayOutputStream().also { bytesField(it, 1, "OSMHeader".toByteArray()); tag(it, 3, 0); varint(it, blob.size.toLong()) }.toByteArray()
        file.parentFile.mkdirs()
        DataOutputStream(file.outputStream()).use { it.writeInt(header.size); it.write(header); it.write(blob); it.write(ByteArray(100)) }
    }

    /** Roughly Washington state. */
    private val wa = BoundingBox(-124.85, 45.54, -116.91, 49.01)
    private val tsOct8 = 1791490866L // 2026-10-08T20:21:06Z

    /** Mount Rainier National Park and surroundings: far over the Overpass cap. */
    private val rainierPark = "-122.05,46.6,-121.4,47.1"

    @Test
    fun readsBboxAndDateFromThePbfHeader() {
        val f = File(dir, "extracts/washington-261008.osm.pbf")
        pbf(f, wa, tsOct8)
        val h = Extracts.readHeader(f)
        val b = assertNotNull(h.bbox)
        assertEquals(wa.west, b.west, 1e-7); assertEquals(wa.south, b.south, 1e-7); assertEquals(wa.east, b.east, 1e-7); assertEquals(wa.north, b.north, 1e-7)
        assertEquals(tsOct8, h.replicationTimestamp)
        assertEquals("test", h.writingProgram)

        val e = Extracts(dir = File(dir, "extracts")).all().single()
        assertEquals("washington", e.name)
        assertEquals("2026-10-08T20:21:06Z", e.date)
        assertEquals("pbf-header", e.dateSource)
        assertEquals("pbf-header", e.bboxSource)
        assertEquals("washington 2026-10-08", e.label)
    }

    @Test
    fun sidecarAndConfigOverrideAndProblemsAreListed() {
        val ex = File(dir, "extracts")
        pbf(File(ex, "region-latest.osm.pbf"), null, null) // no bbox anywhere: a problem, never used
        pbf(File(ex, "small.osm.pbf"), wa, tsOct8)
        File(ex, "small.json").writeText("""{"name": "rainier-area", "bbox": [-122.5, 46.4, -121.0, 47.3], "date": "2026-09-01"}""")
        val other = File(dir, "elsewhere/big.osm.pbf").also { pbf(it, BoundingBox(-125.0, 42.0, -116.0, 49.5), tsOct8) }
        val extracts = Extracts(listOf(Extracts.Configured(other, name = "pnw")), ex)
        val all = extracts.all()
        assertEquals(listOf("pnw", "rainier-area"), all.map { it.name })
        assertTrue(extracts.problems.keys.single().endsWith("region-latest.osm.pbf"), extracts.problems.toString())
        val small = all.last()
        assertEquals("sidecar", small.bboxSource)
        assertEquals("2026-09-01", small.date)
        // The smallest extract that holds the box wins; a box beyond it goes to the bigger one; none past both.
        assertEquals("rainier-area", extracts.covering(Extracts.parseBbox(rainierPark))?.name)
        assertEquals("pnw", extracts.covering(BoundingBox(-123.5, 46.0, -122.0, 47.0))?.name)
        assertNull(extracts.covering(BoundingBox(6.8, 45.8, 7.0, 45.9)))
        // A new file dropped in is found without a restart.
        pbf(File(ex, "tiny.osm.pbf"), BoundingBox(-121.9, 46.7, -121.6, 46.95), null)
        assertEquals("tiny", extracts.covering(BoundingBox(-121.8, 46.75, -121.7, 46.85))?.name)
        assertEquals("file-mtime", extracts.all().first { it.name == "tiny" }.dateSource)
        assertEquals("washington", Extracts.regionName(File("washington-2026-10-08.osm.pbf")))
    }

    @Test
    fun notAPbfIsAProblem() {
        val ex = File(dir, "extracts").apply { mkdirs() }
        File(ex, "junk.osm.pbf").writeText("hello, not protobuf")
        val extracts = Extracts(dir = ex)
        assertTrue(extracts.all().isEmpty())
        assertTrue("no bbox" in extracts.problems.values.single(), extracts.problems.toString())
    }

    // ---- builds ----

    private val png: ByteArray = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).let { img ->
        for (j in 0 until 256) for (i in 0 until 256) img.setRGB(i, j, Terrarium.encode(1500f + i))
        ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private val ofmFetches = AtomicInteger()

    private fun live() = LiveTiles(
        emptyList(), emptyList(), cacheRoot = File(dir, "cache"), outDir = File(dir, "out"), threads = 2,
        generateDem = { LiveTiles.LiveDem(png, listOf("3dep", "geoid18", "egm2008")) },
        fetchOfm = { ofmFetches.incrementAndGet(); null },
        ofmTileJson = { """{"tiles":["https://example.invalid/planet/b7/{z}/{x}/{y}.pbf"],"vector_layers":[]}""" },
    )

    /** Fake tilebuild: records its input and writes a small outdoors archive. */
    private class FakeTilebuild : OutdoorsTileBuilder {
        val inputs = mutableListOf<File>()
        val sourceIds = mutableListOf<List<String>>()
        override fun unavailable(): String? = null
        override fun describe() = "fake"
        override fun build(
            osmXml: File, out: File, box: BoundingBox, zooms: IntRange, sourceIds: List<String>,
            cancelled: () -> Boolean, log: (String) -> Unit,
        ): Map<String, Any?> {
            inputs += osmXml
            this.sourceIds += sourceIds
            val writer = OsmPackWriter(Gzip.compress)
            for (z in zooms.first..minOf(zooms.last, 10)) for (t in WebMercator.tilesCovering(box, z)) writer.add(t.z, t.x, t.y, "mvt".toByteArray())
            val ids = listOf("osm", "osm-water-polygons", "openmaptiles") + sourceIds
            val registry = SourceRegistry.builtIn
            val meta = OsmPackMetadata(
                name = out.nameWithoutExtension, bounds = listOf(box.west, box.south, box.east, box.north),
                minZoom = zooms.first, maxZoom = zooms.last, vectorLayers = emptyList(),
                attribution = registry.attribution(ids), attributionHtml = registry.attributionHtml(ids),
                provenance = ids, license = "ODbL-1.0", profile = "outdoors", schema = "Dystopo outdoors 1",
            )
            out.parentFile.mkdirs()
            out.outputStream().use { os -> writer.finish(ByteSink { os.write(it) }, meta) }
            return mapOf("totalSeconds" to 1.0, "profile" to "Dystopo outdoors 1")
        }
    }

    private class NoOverpass {
        val calls = AtomicInteger()
        fun post(url: String, body: String, timeout: Duration, into: File): Int { calls.incrementAndGet(); return 500 }
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

    @Test
    fun outdoorsBuildUsesACoveringExtractWithoutOverpass() {
        val ex = File(dir, "cache/extracts")
        val pbfFile = File(ex, "washington-261008.osm.pbf").also { pbf(it, wa, tsOct8) }
        val overpass = NoOverpass()
        val tb = FakeTilebuild()
        val builds = BuildRoutes(
            live(), File(dir, "out"), OverpassSource(File(dir, "cache/overpass"), "https://overpass.invalid/x", 0.05, 60, overpass::post), tb,
            extracts = Extracts(dir = ex),
        )

        val sources = builds.sources(mapOf("bbox" to rainierPark))
        @Suppress("UNCHECKED_CAST") val outdoors = sources["outdoors"] as Map<String, Any?>
        assertEquals("extract", outdoors["source"])
        assertEquals("outdoors", outdoors["profile"])
        @Suppress("UNCHECKED_CAST") assertEquals(true, (sources["extracts"] as List<Map<String, Any?>>).single()["covers"])
        @Suppress("UNCHECKED_CAST") assertEquals(false, (sources["overpass"] as Map<String, Any?>)["underCap"])

        val job = builds.submit(mapOf("name" to "rainier-park", "bbox" to rainierPark, "minZoom" to "8", "maxZoom" to "10", "profile" to "outdoors"))
        val done = await(builds, job.id)
        assertEquals(BuildRoutes.Status.DONE, done.status, done.json().toString())
        assertEquals("extract", done.osmSource)
        assertEquals("outdoors", done.osmProfile)
        assertEquals(pbfFile.absolutePath, tb.inputs.single().absolutePath)
        assertEquals(listOf(emptyList()), tb.sourceIds, "an extract credits osm (the profile's own), not overpass")
        assertEquals(0, overpass.calls.get())
        assertEquals(0, ofmFetches.get())
        @Suppress("UNCHECKED_CAST") val prov = done.json()["provenance"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val osmExtract = prov["osmExtract"] as Map<String, Any?>
        assertEquals("washington", osmExtract["name"])
        assertEquals("2026-10-08T20:21:06Z", osmExtract["date"])
        assertTrue("osm" in done.osmProvenance, done.osmProvenance.toString())
        assertTrue(File(dir, "out/rainier-park-outdoors.pmtiles").isFile)

        // source=overpass skips the extract: the park box is over the cap, so 413 (no request sent).
        val e = assertFailsWith<BuildRoutes.Refused> {
            builds.submit(mapOf("name" to "p2", "bbox" to rainierPark, "minZoom" to "8", "maxZoom" to "10", "profile" to "outdoors", "source" to "overpass"))
        }
        assertEquals(413, e.status)
        assertEquals(0, overpass.calls.get())
        assertEquals(400, assertFailsWith<BuildRoutes.Refused> { builds.submit(mapOf("name" to "p3", "bbox" to rainierPark, "profile" to "outdoors", "source" to "planet")) }.status)
    }

    @Test
    fun boxOutsideEveryExtractFallsBackOrIsRefused() {
        val ex = File(dir, "cache/extracts")
        pbf(File(ex, "washington-261008.osm.pbf"), wa, tsOct8)
        val builds = BuildRoutes(
            live(), File(dir, "out"), OverpassSource(File(dir, "cache/overpass"), "https://overpass.invalid/x", 0.05, 60, NoOverpass()::post), FakeTilebuild(),
            extracts = Extracts(dir = ex),
        )
        val alps = "6.6,45.6,7.2,46.0" // Mont Blanc massif: over the cap and outside Washington
        val e = assertFailsWith<BuildRoutes.Refused> { builds.submit(mapOf("name" to "mb", "bbox" to alps, "minZoom" to "8", "maxZoom" to "9", "profile" to "outdoors")) }
        assertEquals(413, e.status)
        assertTrue("no configured OSM extract covers" in e.message!! && "washington" in e.message!!, e.message)
        val forced = assertFailsWith<BuildRoutes.Refused> {
            builds.submit(mapOf("name" to "mb", "bbox" to "6.85,45.8,6.9,45.85", "minZoom" to "8", "maxZoom" to "9", "profile" to "outdoors", "source" to "extract"))
        }
        assertEquals(413, forced.status)

        @Suppress("UNCHECKED_CAST") val out = builds.sources(mapOf("bbox" to alps))["outdoors"] as Map<String, Any?>
        assertEquals("openfreemap", out["source"])
        assertEquals(true, out["needsFallback"])

        val job = builds.submit(mapOf("name" to "mb", "bbox" to alps, "minZoom" to "8", "maxZoom" to "9", "profile" to "outdoors", "fallback" to "1"))
        assertEquals("openfreemap", job.osmSource)
        assertEquals("openmaptiles", job.osmProfile)
        assertTrue(job.warnings.single().contains("OpenFreeMap"), job.warnings.toString())
        await(builds, job.id)
    }
}
