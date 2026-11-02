package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.packs.DerivedFile
import io.github.jasondorjeshort.dystopo.packs.PackCatalog as SharedCatalog
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.MemoryTileStaging
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.GraphTileFile
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T189 (ADR 0049's B): the catalog lists a pack's matching routing graph under `derived`, outside its files and hash. */
class ShippedGraphCatalogTest {
    private val dir: File = Files.createTempDirectory("shipped-graph").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // The z14 tile holding Paradise (public: Mount Rainier National Park), as RouteRoutesTest.
    private val z = 14
    private val tx = WebMercator.lonToX(-121.735, z).toInt()
    private val ty = WebMercator.latToY(46.79, z).toInt()

    private fun line(name: String, vararg p: Pair<Int, Int>) = Feature(
        null, mapOf("class" to Value.StringValue("path"), "name" to Value.StringValue(name)),
        Geometry.Lines(listOf(p.map { TilePoint(it.first, it.second) })),
    )

    private fun write(file: File, type: TileType, tiles: Map<Triple<Int, Int, Int>, ByteArray>, minZoom: Int) {
        val w = PmtilesWriter(type, staging = MemoryTileStaging())
        for ((t, b) in tiles) w.addTile(t.first, t.second, t.third, b)
        val west = WebMercator.xToLon(tx.toDouble(), z); val east = WebMercator.xToLon(tx + 1.0, z)
        val north = WebMercator.yToLat(ty.toDouble(), z); val south = WebMercator.yToLat(ty + 1.0, z)
        file.outputStream().buffered().use { os ->
            w.finish(ByteSink { os.write(it) }, "{}", west, south, east, north, centerZoom = z, minZoom = minZoom, maxZoom = z,
                centerLon = (west + east) / 2, centerLat = (south + north) / 2)
        }
    }

    private fun vector(extra: Boolean = false): File {
        val lines = listOf(line("Main Trail", 400 to 2048, 3600 to 2048), line("Side Trail", 2048 to 2048, 2048 to 600)) +
            if (extra) listOf(line("New Trail", 500 to 3700, 1200 to 3700)) else emptyList()
        return File(dir, "t-outdoors.pmtiles").also { write(it, TileType.MVT, mapOf(Triple(z, tx, ty) to VectorTile(listOf(Layer("transportation", lines))).encode()), z) }
    }

    /** A plane rising 1 m per pixel eastwards from 1600 m (as RouteRoutesTest). T202: [small], the z14 tile alone (another fingerprint). */
    private fun dem(small: Boolean = false): File = File(dir, "t-dem.pmtiles").also { f ->
        val img = java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (j in 0 until 256) for (i in 0 until 256) img.setRGB(i, j, io.github.jasondorjeshort.dystopo.dem.Terrarium.encode(1600f + i))
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        if (small) write(f, TileType.PNG, mapOf(Triple(z, tx, ty) to png), z)
        else write(f, TileType.PNG, mapOf(Triple(z, tx, ty) to png, Triple(z - 1, tx / 2, ty / 2) to png), z - 1)
    }

    /** The graph built without the DEM (a planner given no DEM file). */
    private fun build(): File {
        RoutePlanner(dir, ElevationSampler { _, _ -> 1700.0 }).graph(RoutePlanner.Pack("t", File(dir, "t-outdoors.pmtiles"), null, BoundingBox(-122.0, 46.0, -121.0, 47.0)))
        return File(dir, "t-outdoors-graph.idx")
    }

    @Suppress("UNCHECKED_CAST")
    private fun entry(c: PackCatalog) = c.packs().single { it["name"] == "t" }

    @Test
    fun aMatchingGraphIsListedUnderDerivedOutsideTheHash() {
        dem()
        vector()
        val catalog = PackCatalog(dir)
        val before = entry(catalog)
        assertNull(before["derived"], "no graph yet: no `derived` key")
        // A graph built without the DEM does not match a pack that has one (its elevation fingerprint is empty).
        val g = build()
        assertTrue(g.isFile)
        assertNull(entry(catalog)["derived"])
        assertTrue(catalog.graphProblem(File(dir, "t-outdoors.pmtiles"), File(dir, "t-dem.pmtiles"))!!.startsWith("elevation fingerprint mismatch"))
        // Built by the planner with the DEM beside it (the pack build's phase), it matches.
        g.delete()
        RoutePlanner(dir, ElevationSampler { _, _ -> 1700.0 }).graph(RoutePlanner.Pack("t", File(dir, "t-outdoors.pmtiles"), File(dir, "t-dem.pmtiles"), BoundingBox(-122.0, 46.0, -121.0, 47.0)))
        val after = entry(catalog)
        assertEquals(before["contentHash"], after["contentHash"], "the graph is not part of the pack's identity")
        assertEquals(before["files"], after["files"])
        assertEquals(before["bytes"], after["bytes"])
        assertEquals(before["builtAt"], after["builtAt"])
        @Suppress("UNCHECKED_CAST")
        val d = (after["derived"] as List<Map<String, Any?>>).single()
        assertEquals("graph", d["kind"])
        assertEquals("t-outdoors-graph.idx", d["file"])
        assertEquals("/out/t-outdoors-graph.idx", d["url"])
        assertEquals(g.length(), d["bytes"])
        assertEquals(PackCatalog.hashFile(g), d["sha256"])
        assertEquals("t-outdoors-graph.idx.sha256", d["sidecar"])
        assertTrue(PackCatalog.sidecar(g).readText().startsWith(PackCatalog.hashFile(g)))
        assertEquals("DYSG", d["format"])
        assertEquals(GraphTileFile.VERSION, d["version"])
        // The shared parser (the phone's) reads it back, with the same hash as before.
        val parsed = SharedCatalog.parse(MiniJson.parseObject(MiniJson.encode(catalog.json()))).packs.single { it.name == "t" }
        assertEquals(before["contentHash"], parsed.hash)
        val shipped = assertNotNull(parsed.graph)
        assertEquals(DerivedFile.KIND_GRAPH, shipped.kind)
        assertEquals(11, shipped.tileZoom)
        assertTrue(shipped.fingerprint!!.startsWith("v1:"))
        assertTrue(shipped.demFingerprint!!.startsWith("v1:"))
        // warm() hashes the graph too.
        PackCatalog.sidecar(g).delete()
        catalog.warm().join()
        assertTrue(PackCatalog.sidecar(g).isFile)
    }

    @Test
    fun aStaleOrUnreadableGraphIsLeftOut() {
        dem()
        vector()
        val catalog = PackCatalog(dir)
        RoutePlanner(dir, ElevationSampler { _, _ -> 1700.0 }).graph(RoutePlanner.Pack("t", File(dir, "t-outdoors.pmtiles"), File(dir, "t-dem.pmtiles"), BoundingBox(-122.0, 46.0, -121.0, 47.0)))
        assertNotNull(entry(catalog)["derived"])
        // The pack rebuilt with another vector archive: the old graph no longer matches.
        Thread.sleep(20)
        vector(extra = true).setLastModified(System.currentTimeMillis() + 2000)
        assertNull(entry(catalog)["derived"])
        assertTrue(catalog.graphProblem(File(dir, "t-outdoors.pmtiles"), File(dir, "t-dem.pmtiles"))!!.startsWith("fingerprint mismatch"))
        // A file that is not a graph of this version.
        File(dir, "t-outdoors-graph.idx").writeBytes(byteArrayOf(0x44, 0x59, 0x53, 0x47, 7, 0, 0, 0, 4))
        assertNull(entry(catalog)["derived"])
        assertFalse(catalog.graphProblem(File(dir, "t-outdoors.pmtiles"), File(dir, "t-dem.pmtiles")) == null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun line(state: Map<String, Any?>) = (state["packs"] as List<Map<String, Any?>>).single { it["pack"] == "t" }

    /** T202: the warm thread builds a missing graph through the planner, the catalog lists it, and a refresh rebuilds a deleted one. */
    @Test
    fun warmBuildsAMissingGraphAndRefreshRebuildsADeletedOne() {
        dem()
        vector()
        val planner = RoutePlanner(dir, ElevationSampler { _, _ -> 1700.0 })
        val catalog = PackCatalog(dir, planner = { planner })
        val g = File(dir, "t-outdoors-graph.idx")
        assertFalse(g.isFile)
        catalog.warm().join()
        assertTrue(g.isFile, "warm() built the graph beside the pack")
        assertTrue(PackCatalog.sidecar(g).readText().startsWith(PackCatalog.hashFile(g)), "and its sidecar")
        assertNotNull(entry(catalog)["derived"], "listed with no route requested")
        val state = catalog.graphState()
        assertEquals(1, state["built"]); assertEquals(0, state["failed"]); assertEquals(0, state["pending"])
        assertEquals("start", state["trigger"])
        assertEquals("built", line(state)["state"]); assertEquals("missing", line(state)["reason"])
        assertEquals(1, (catalog.warmState()["graphs"] as Map<*, *>)["built"])
        // A second pass finds it current and builds nothing.
        val mtime = g.lastModified()
        val again = catalog.fillGraphs("again")
        assertEquals(0, again["built"]); assertEquals(1, again["skipped"]); assertEquals(mtime, g.lastModified())
        // Deleted while the planner holds it open: a refresh rebuilds the file (the planner does not answer from memory).
        g.delete(); PackCatalog.sidecar(g).delete()
        assertEquals(true, (catalog.refresh()["refresh"] as Map<*, *>)["graphsQueued"])
        catalog.queueGraphs("wait")!!.get()
        assertTrue(g.isFile, "rebuilt after the refresh")
        assertNotNull(entry(catalog)["derived"])
        assertEquals(2, catalog.graphState()["totalBuilt"])
    }

    /** T202: a graph built against another DEM is stale; the pass rebuilds it and the old bytes are gone. */
    @Test
    fun aStaleGraphIsRebuiltByThePass() {
        dem(small = true)
        vector()
        val planner = RoutePlanner(dir, ElevationSampler { _, _ -> 1700.0 })
        val catalog = PackCatalog(dir, planner = { planner })
        catalog.fillGraphs("start")
        val g = File(dir, "t-outdoors-graph.idx")
        val oldHash = PackCatalog.hashFile(g)
        // The DEM swapped for the full one: the elevation fingerprint no longer matches.
        Thread.sleep(20)
        dem().setLastModified(System.currentTimeMillis() + 2000)
        assertNull(entry(catalog)["derived"])
        assertTrue(catalog.graphProblem(File(dir, "t-outdoors.pmtiles"), File(dir, "t-dem.pmtiles"))!!.startsWith("elevation fingerprint mismatch"))
        val state = catalog.fillGraphs("refresh")
        assertEquals(1, state["built"])
        assertTrue(line(state)["reason"].toString().startsWith("elevation fingerprint mismatch"))
        assertFalse(oldHash == PackCatalog.hashFile(g), "the old bytes are gone")
        @Suppress("UNCHECKED_CAST")
        val d = (entry(catalog)["derived"] as List<Map<String, Any?>>).single()
        assertEquals(PackCatalog.hashFile(g), d["sha256"])
    }

    /** T202: with buildGraphs off nothing is built and the catalog lists no graph. */
    @Test
    fun buildGraphsOffBuildsNothing() {
        dem()
        vector()
        val catalog = PackCatalog(dir, planner = { RoutePlanner(dir) }).apply { buildGraphs = false }
        catalog.warm().join()
        assertNull(catalog.queueGraphs("refresh"))
        assertEquals(false, (catalog.refresh()["refresh"] as Map<*, *>)["graphsQueued"])
        assertFalse(File(dir, "t-outdoors-graph.idx").isFile)
        assertNull(entry(catalog)["derived"])
        assertEquals("off", catalog.graphState()["state"])
    }
}
