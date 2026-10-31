package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.packs.OsmPackMetadata
import io.github.jasondorjeshort.dystopo.packs.OsmPackWriter
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T189 (ADR 0049's B): the pack build's `graph` phase after `publish`, without the network: the graph is written and
 * listed under the catalog's `derived`, the pack's files and hash are those of a build without the phase, and a failed
 * phase is a note on a `done` job, never a failed pack.
 */
class GraphPhaseTest {
    private val dir: File = Files.createTempDirectory("graph-phase-test").toFile()
    private val out = File(dir, "out")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val png: ByteArray = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).let { img ->
        for (j in 0 until 256) for (i in 0 until 256) img.setRGB(i, j, Terrarium.encode(1500f + i))
        ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private val live by lazy {
        LiveTiles(
            emptyList(), emptyList(), cacheRoot = File(dir, "cache"), outDir = out, threads = 2,
            generateDem = { LiveTiles.LiveDem(png, listOf("3dep", "geoid18", "egm2008")) },
            fetchOfm = { error("no OpenFreeMap in this test") },
            ofmTileJson = { """{"tiles":["https://example.invalid/{z}/{x}/{y}.pbf"],"vector_layers":[]}""" },
        )
    }

    /** Paradise, Mount Rainier (public). */
    private val bbox = "-121.75,46.78,-121.72,46.80"

    /** A tilebuild stand-in writing an outdoors archive whose every tile has two crossing paths (deterministic bytes). */
    private class TrailTilebuild : OutdoorsTileBuilder {
        override fun unavailable(): String? = null
        override fun describe() = "fake with trails"
        override fun build(osmXml: File, out: File, box: BoundingBox, zooms: IntRange, sourceIds: List<String>, cancelled: () -> Boolean, log: (String) -> Unit): Map<String, Any?> {
            val writer = OsmPackWriter(Gzip.compress)
            fun line(name: String, a: Pair<Int, Int>, b: Pair<Int, Int>) = Feature(
                null, mapOf("class" to Value.StringValue("path"), "name" to Value.StringValue(name)),
                Geometry.Lines(listOf(listOf(TilePoint(a.first, a.second), TilePoint(b.first, b.second)))),
            )
            for (z in zooms) for (t in WebMercator.tilesCovering(box, z)) {
                writer.add(t.z, t.x, t.y, VectorTile(listOf(Layer("transportation", listOf(line("East West", 0 to 2048, 4096 to 2048), line("North South", 2048 to 0, 2048 to 4096))))).encode())
            }
            val ids = listOf("osm") + sourceIds
            val registry = SourceRegistry.builtIn
            val meta = OsmPackMetadata(
                name = out.nameWithoutExtension, bounds = listOf(box.west, box.south, box.east, box.north),
                minZoom = zooms.first, maxZoom = zooms.last, vectorLayers = emptyList(),
                attribution = registry.attribution(ids), attributionHtml = registry.attributionHtml(ids),
                provenance = ids, license = "ODbL-1.0", profile = "outdoors", schema = "Dystopo outdoors 1",
            )
            out.parentFile.mkdirs()
            out.outputStream().use { os -> writer.finish(ByteSink { os.write(it) }, meta) }
            return mapOf("totalSeconds" to 0.1, "profile" to "Dystopo outdoors 1")
        }
    }

    private val overpassXml = """<?xml version="1.0" encoding="UTF-8"?><osm version="0.6"><node id="1" lat="46.79" lon="-121.73"/></osm>"""

    private fun routes() = BuildRoutes(
        live, out,
        OverpassSource(File(dir, "cache/overpass"), "https://overpass.invalid/api/interpreter", 0.05, 60) { _: String, _: String, _: Duration, into: File ->
            into.parentFile.mkdirs(); into.writeText(overpassXml); 200
        },
        TrailTilebuild(),
    )

    private fun await(b: BuildRoutes, id: String): BuildRoutes.Job {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val j = b.job(id)!!
            if (!j.active) return j
            Thread.sleep(20)
        }
        error("build $id did not finish: ${b.job(id)!!.json()}")
    }

    private fun build(b: BuildRoutes, vararg extra: Pair<String, String>): BuildRoutes.Job =
        await(b, b.submit(mapOf("name" to "paradise", "bbox" to bbox, "minZoom" to "10", "maxZoom" to "12", "profile" to "outdoors", "overwrite" to "1") + extra).id)

    @Suppress("UNCHECKED_CAST")
    private fun entry() = (MiniJson.parseObject(MiniJson.encode(live.catalog.json()))["packs"] as List<Map<String, Any?>>).single { it["name"] == "paradise" }

    @Test
    fun graphPhaseWritesAndListsTheGraphWithoutMovingThePack() {
        val b = routes()
        // A build without the phase first: the pack as v0.1.14's catalog saw it.
        val plain = build(b, "graph" to "0")
        assertEquals(BuildRoutes.Status.DONE, plain.status, plain.json().toString())
        assertEquals("skipped", plain.graph!!["state"])
        assertEquals("graph=0", plain.graph!!["reason"])
        val graphFile = File(out, "paradise-outdoors-graph.idx")
        assertTrue(!graphFile.exists())
        val before = entry()
        assertNull(before["derived"])

        val shipped = build(b)
        assertEquals(BuildRoutes.Status.DONE, shipped.status, shipped.json().toString())
        val g = shipped.graph!!
        assertEquals("done", g["state"], g.toString())
        assertEquals(true, g["listed"], g.toString())
        assertEquals(6, g["version"])
        assertEquals(11, g["tileZoom"])
        assertTrue((g["nodes"] as Int) > 0, g.toString())
        assertTrue(graphFile.isFile)
        assertEquals(PackCatalog.hashFile(graphFile), g["sha256"])
        assertTrue(PackCatalog.sidecar(graphFile).isFile)
        assertTrue(shipped.timings.containsKey("graphMs"))
        val after = entry()
        assertEquals(before["files"].toString().replace(Regex("\"modified\": \"[^\"]*\"|modified=[^,}]*"), ""),
            after["files"].toString().replace(Regex("\"modified\": \"[^\"]*\"|modified=[^,}]*"), ""), "the archives are byte-identical")
        assertEquals(before["contentHash"], after["contentHash"], "the pack's hash does not move with the graph")
        assertEquals(before["bytes"], after["bytes"])
        @Suppress("UNCHECKED_CAST")
        val d = (after["derived"] as List<Map<String, Any?>>).single()
        assertEquals("paradise-outdoors-graph.idx", d["file"])
        assertEquals(graphFile.length(), (d["bytes"] as Number).toLong())

        // The area manifest's graph item carries it, onDevice false.
        val (status, manifest) = b.area(mapOf("bbox" to bbox, "minZoom" to "10", "maxZoom" to "12", "name" to "paradise"), "")
        assertEquals(200, status, manifest.toString())
        @Suppress("UNCHECKED_CAST")
        val item = (manifest["items"] as List<Map<String, Any?>>).single { it["kind"] == "graph" }
        assertEquals(false, item["onDevice"], item.toString())
        assertEquals("/out/paradise-outdoors-graph.idx", item["url"])
        assertEquals(d["sha256"], item["sha256"])
    }

    @Test
    fun aFailedGraphPhaseIsANoteNeverAFailedPack() {
        // A directory where the graph file goes: the build cannot write it.
        File(out, "paradise-outdoors-graph.idx").apply { mkdirs() }.resolve("blocker").writeText("x")
        val b = routes()
        val job = build(b)
        assertEquals(BuildRoutes.Status.DONE, job.status, job.json().toString())
        assertEquals("failed", job.graph!!["state"], job.graph.toString())
        assertTrue((job.graph!!["note"] as String).contains("phones build their own"))
        assertTrue(File(out, "paradise-dem.pmtiles").isFile && File(out, "paradise-outdoors.pmtiles").isFile, "the archives stay published")
        assertNull(entry()["derived"])
        assertTrue(job.warnings.isEmpty(), job.warnings.toString())
    }
}
