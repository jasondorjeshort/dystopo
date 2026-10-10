package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.server.LiveTiles.Tile
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.MvtDecoder
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.MemoryTileStaging
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live mode without the network: fake DEM renderer and OpenFreeMap fetcher, a real one-tile pack. */
class LiveTilesTest {
    private val dir: File = Files.createTempDirectory("live-tiles-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A Terrarium PNG of a plane tilted west to east (elevation = [base] + column * 2 m). */
    private fun slopePng(base: Float): ByteArray {
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        for (j in 0 until 256) for (i in 0 until 256) img.setRGB(i, j, Terrarium.encode(base + i * 2f))
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun pack(file: File, type: TileType, tiles: Map<TileId, ByteArray>, z: Int): File {
        val w = PmtilesWriter(type, staging = MemoryTileStaging())
        for ((t, b) in tiles) w.addTile(t.z, t.x, t.y, b)
        val t = tiles.keys.first()
        val west = WebMercator.xToLon(t.x.toDouble(), z)
        val east = WebMercator.xToLon(t.x + 1.0, z)
        val north = WebMercator.yToLat(t.y.toDouble(), z)
        val south = WebMercator.yToLat(t.y + 1.0, z)
        file.outputStream().buffered().use { os ->
            w.finish(ByteSink { os.write(it) }, "{}", west, south, east, north,
                centerZoom = z, minZoom = z, maxZoom = z, centerLon = (west + east) / 2, centerLat = (south + north) / 2)
        }
        return file
    }

    @Test
    fun labelsFollowProvenance() {
        assertEquals("live-3dep", LiveTiles.labelFor(listOf("3dep", "geoid18", "egm2008")))
        assertEquals("live-3dep", LiveTiles.labelFor(listOf("copernicus", "3dep-1m")))
        assertEquals("live-copernicus", LiveTiles.labelFor(listOf("copernicus")))
        // T50: a Swiss tile feathered into Copernicus at the border still says which source carried it.
        assertEquals("live-swissalti", LiveTiles.labelFor(listOf("swissalti3d", "copernicus", "egm2008")))
        assertEquals("live-nodata", LiveTiles.labelFor(emptyList()))
    }

    private fun window(p: io.github.jasondorjeshort.dystopo.tiles.ContourTilePlan) = intArrayOf(p.windowX, p.windowY, p.windowSize, p.windowSize)

    @Test
    fun demTilesAContourTileSamples() {
        val opts = ContourTileOptions()
        // z13 and deeper: one DEM pixel per screen pixel, the 2x2 tiles below plus a halo ring: 16 tiles one zoom down.
        val p13 = ContourTiles.plan(13, 10, 20, opts)
        val t13 = LiveTiles.demTilesFor(p13.sampleZoom, window(p13), 15)
        assertEquals(16, t13.size)
        assertEquals(setOf(14), t13.map { it.z }.toSet())
        assertEquals((19..22).toSet(), t13.map { it.x }.toSet())
        // z12 samples at half resolution: its own zoom, 3x3 tiles.
        val p12 = ContourTiles.plan(12, 10, 20, opts)
        val t12 = LiveTiles.demTilesFor(p12.sampleZoom, window(p12), 15)
        assertEquals(setOf(12), t12.map { it.z }.toSet())
        assertEquals(9, t12.size)
        // Past the live maximum the window reads ancestors: z16 pixels from z15 tiles.
        val p15 = ContourTiles.plan(15, 100, 100, opts)
        val deep = LiveTiles.demTilesFor(p15.sampleZoom, window(p15), 15)
        assertTrue(deep.all { it.z == 15 })
        assertTrue(deep.size in 1..9)
        // At the world edge nothing outside 0..n-1 is asked for.
        val p8 = ContourTiles.plan(8, 0, 0, opts)
        assertTrue(LiveTiles.demTilesFor(p8.sampleZoom, window(p8), 15).all { it.x >= 0 && it.y >= 0 })
    }

    @Test
    fun parsesOpenFreeMapTileJson() {
        val (template, build) = LiveTiles.parseOfmTileJson(
            """{"tilejson":"3.0.0","tiles":["https://tiles.openfreemap.org/planet/20261004_113936_pt/{z}/{x}/{y}.pbf"],"maxzoom":14}""",
        )
        assertEquals("https://tiles.openfreemap.org/planet/20261004_113936_pt/{z}/{x}/{y}.pbf", template)
        assertEquals("20261004_113936_pt", build)
    }

    @Test
    fun gunzipsOnlyGzip() {
        val raw = "vector tile".toByteArray()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
        assertContentEquals(raw, LiveTiles.gunzipIfNeeded(gz))
        assertContentEquals(raw, LiveTiles.gunzipIfNeeded(raw))
    }

    @Test
    fun packFirstThenLiveCachedOnDisk() {
        val packed = TileId(14, 2620, 5720)
        val demPack = pack(File(dir, "a-dem.pmtiles"), TileType.PNG, mapOf(packed to slopePng(1000f)), 14)
        val renders = AtomicInteger()
        val fake: (TileId) -> LiveTiles.LiveDem = { t ->
            renders.incrementAndGet()
            LiveTiles.LiveDem(slopePng(100f * t.x % 3000), listOf("copernicus", "egm2008"))
        }
        val cache = File(dir, "cache")
        val live = LiveTiles(listOf(demPack), emptyList(), cacheRoot = cache, threads = 2, generateDem = fake)

        assertEquals("pack", live.dem(14, 2620, 5720)!!.source)
        assertEquals(0, renders.get())
        val first = live.dem(14, 2621, 5720)!!
        assertEquals("live-copernicus", first.source)
        assertFalse(first.cached)
        assertTrue(live.dem(14, 2621, 5720)!!.cached)
        assertEquals(1, renders.get())
        assertNull(live.dem(LiveTiles.LIVE_MIN_ZOOM - 1, 0, 0))
        assertNull(live.dem(LiveTiles.LIVE_MAX_ZOOM + 1, 0, 0))

        // A new server over the same cache: warm from disk, label kept, no render.
        val again = LiveTiles(emptyList(), emptyList(), cacheRoot = cache, threads = 2, generateDem = fake)
        val warm = again.dem(14, 2621, 5720)!!
        assertTrue(warm.cached)
        assertEquals("live-copernicus", warm.source)
        assertContentEquals(first.bytes, warm.bytes)
        assertEquals(1, renders.get())

        // Contours over the mixed pack/live DEM: 16 DEM tiles at z14, one render each, lines in the tile.
        val c = live.contours(13, 1310, 2860)!!
        val decoded = MvtDecoder.decode(c.bytes)
        assertTrue(decoded.layers.single { it.name == "contours" }.features.isNotEmpty())
        assertEquals(15, renders.get()) // 14 new: of the 16, one is the pack tile and 2621/5720 was rendered above

        val sources = live.sourcesIn(LiveTiles.clampBox(listOf(WebMercator.xToLon(2620.2, 14), WebMercator.yToLat(5720.8, 14), WebMercator.xToLon(2621.8, 14), WebMercator.yToLat(5720.2, 14))), 14, 14)
        @Suppress("UNCHECKED_CAST")
        val demCounts = sources["dem"] as Map<String, Int>
        assertEquals(mapOf("live-copernicus" to 1, "pack" to 1), demCounts)
    }

    private fun tileAt(lat: Double, lon: Double, z: Int) =
        TileId(z, kotlin.math.floor(WebMercator.lonToX(lon, z)).toInt(), kotlin.math.floor(WebMercator.latToY(lat, z)).toInt())

    private fun mvt(vararg layers: String): ByteArray = VectorTile(
        layers.map { name -> Layer(name, listOf(Feature(1L, mapOf("class" to Value.StringValue(name)), Geometry.Points(listOf(TilePoint(10, 10)))))) },
    ).encode()

    private fun ofmLive(cache: File, osmPacks: List<File>, tile: ByteArray, fetches: AtomicInteger = AtomicInteger()) = LiveTiles(
        emptyList(), osmPacks, cacheRoot = cache, threads = 2,
        generateDem = { error("no DEM in this test") },
        fetchOfm = { fetches.incrementAndGet(); tile },
        ofmTileJson = { """{"tiles":["https://example.invalid/planet/b1/{z}/{x}/{y}.pbf"]}""" },
    )

    @Test
    fun mergesLayersByName() {
        val merged = LiveTiles.mergeMvtLayers(mvt("water", "transportation"), mvt("transportation", "outdoor_poi", "natural_line"))
        val names = MvtDecoder.decode(merged).layers.map { it.name }
        assertEquals(listOf("water", "transportation", "outdoor_poi", "natural_line"), names)
        assertEquals(listOf("water", "transportation"), LiveTiles.mvtLayers(mvt("water", "transportation")).map { it.name })
        // Nothing new: the base tile unchanged.
        val base = mvt("water")
        assertContentEquals(base, LiveTiles.mergeMvtLayers(base, mvt("water")))
    }

    @Test
    fun packEdgeTilesFallThroughToOpenFreeMap() {
        val z = 12
        val inner = TileId(z, 655, 1430)
        val edge = TileId(z, 656, 1430)
        // A pack that wholly holds `inner` and only the western half of `edge`.
        val w = PmtilesWriter(TileType.MVT, staging = MemoryTileStaging())
        w.addTile(z, inner.x, inner.y, mvt("transportation", "outdoor_poi"))
        w.addTile(z, edge.x, edge.y, mvt("transportation", "outdoor_poi"))
        val west = WebMercator.xToLon(inner.x.toDouble(), z)
        val east = WebMercator.xToLon(edge.x + 0.5, z)
        val north = WebMercator.yToLat(inner.y.toDouble(), z)
        val south = WebMercator.yToLat(inner.y + 1.0, z)
        val file = File(dir, "edge-outdoors.pmtiles")
        file.outputStream().buffered().use { os ->
            w.finish(ByteSink { os.write(it) }, "{}", west, south, east, north, centerZoom = z, minZoom = z, maxZoom = z, centerLon = (west + east) / 2, centerLat = (south + north) / 2)
        }
        val fetches = AtomicInteger()
        val live = ofmLive(File(dir, "cache"), listOf(file), mvt("water", "transportation"), fetches)

        val whole = live.osm(inner.z, inner.x, inner.y)!!
        assertEquals("pack", whole.source)
        assertEquals(0, fetches.get())
        assertEquals(listOf("osm", "openmaptiles"), whole.provenance)

        val part = live.osm(edge.z, edge.x, edge.y)!!
        assertEquals("pack+openfreemap", part.source)
        assertEquals(1, fetches.get())
        // OpenFreeMap's base layers (complete across the tile) plus the pack's outdoors-only layer.
        assertEquals(listOf("water", "transportation", "outdoor_poi"), MvtDecoder.decode(part.bytes).layers.map { it.name })
        assertEquals(setOf("osm", "openmaptiles", "openfreemap"), part.provenance.toSet())
    }

    @Test
    fun attributionCreditsOnlyWhatTheViewUsed() {
        val cache = File(dir, "cache")
        val live = LiveTiles(
            emptyList(), emptyList(), cacheRoot = cache, threads = 2,
            generateDem = { LiveTiles.LiveDem(slopePng(500f), listOf("copernicus", "egm2008")) },
            fetchOfm = { mvt("water") },
            ofmTileJson = { """{"tiles":["https://example.invalid/planet/b1/{z}/{x}/{y}.pbf"]}""" },
        )
        // A view in the Alps: z12 DEM and OSM tiles around Mont Blanc.
        val t = tileAt(45.83, 6.86, 12)
        live.dem(t.z, t.x, t.y)
        live.osm(t.z, t.x, t.y)
        val box = LiveTiles.clampBox(listOf(WebMercator.xToLon(t.x + 0.4, 12), WebMercator.yToLat(t.y + 0.6, 12), WebMercator.xToLon(t.x + 0.6, 12), WebMercator.yToLat(t.y + 0.4, 12)))
        val r = live.sourcesIn(box, 12, 12)
        @Suppress("UNCHECKED_CAST")
        val ids = r["registryIds"] as List<String>
        assertTrue("copernicus" in ids && "egm2008" in ids && "openfreemap" in ids && "osm" in ids, "$ids")
        assertFalse(ids.any { it.startsWith("3dep") || it.startsWith("geoid") }, "no US sources in an Alps view: $ids")
        val html = r["attribution"] as String
        assertTrue("Copernicus" in html && "OpenStreetMap" in html, html)
    }

    @Test
    fun contourUnitsFollowTheSettingOrTheRegion() {
        assertEquals(io.github.jasondorjeshort.dystopo.render.ContourUnits.METERS, LiveTiles.parseUnits("m"))
        assertEquals(io.github.jasondorjeshort.dystopo.render.ContourUnits.FEET, LiveTiles.parseUnits("feet"))
        assertNull(LiveTiles.parseUnits("furlongs"))
        val live = LiveTiles(emptyList(), emptyList(), cacheRoot = File(dir, "cache"), threads = 2, generateDem = { LiveTiles.LiveDem(slopePng(1000f), listOf("copernicus")) })
        fun eles(t: Tile) = MvtDecoder.decode(t.bytes).layers.single { it.name == "contours" }.features.map { (it.properties.getValue("ele") as Value.IntValue).value }
        val alps = tileAt(45.83, 6.86, 13)
        // Default in the Alps: metres. The plane rises 2 m per pixel, so z13 tiles (z14 spec) have 20 m lines there, 80 ft in feet.
        val metric = eles(live.contours(alps.z, alps.x, alps.y)!!)
        assertTrue(metric.isNotEmpty() && metric.all { it % 20 == 0L }, "$metric")
        val feet = eles(live.contours(alps.z, alps.x, alps.y, io.github.jasondorjeshort.dystopo.render.ContourUnits.FEET)!!)
        assertTrue(feet.isNotEmpty() && feet.all { it % 80 == 0L }, "$feet")
        assertTrue(feet.max() > metric.max() * 3, "feet values are about 3.28x metres: $feet vs $metric")
    }

    @Test
    fun liveDemLevelIsOnePerZoom() {
        // Every tile of a zoom shares one reference cell size, whatever its latitude.
        assertEquals(LiveTiles.referenceCellMeters(12), LiveTiles.referenceCellMeters(12))
        assertEquals(WebMercator.metersPerPixel(45.0, 12, 256), LiveTiles.referenceCellMeters(12), 1e-9)
        assertEquals(LiveTiles.referenceCellMeters(11) / 2, LiveTiles.referenceCellMeters(12), 1e-9)
    }

    @Test
    fun openFreeMapFetchedOncePerTile() {
        val fetches = AtomicInteger()
        val tile = "mvt".toByteArray()
        val live = LiveTiles(
            emptyList(), emptyList(), cacheRoot = File(dir, "cache"), threads = 2,
            generateDem = { error("no DEM in this test") },
            fetchOfm = { url ->
                fetches.incrementAndGet()
                if (url.contains("/14/0/0.pbf")) null else ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(tile) } }.toByteArray()
            },
            ofmTileJson = { """{"tiles":["https://example.invalid/planet/b1/{z}/{x}/{y}.pbf"]}""" },
        )
        val a = live.osm(12, 655, 1430)!!
        assertEquals("openfreemap", a.source)
        assertContentEquals(tile, a.bytes)
        assertTrue(live.osm(12, 655, 1430)!!.cached)
        assertEquals(1, fetches.get())
        assertEquals(0, live.osm(14, 0, 0)!!.bytes.size) // no tile upstream: empty, cached
        assertEquals(0, live.osm(14, 0, 0)!!.bytes.size)
        assertEquals(2, fetches.get())
        assertNull(live.osm(15, 0, 0))
        assertTrue(File(dir, "cache/openfreemap/b1/12/655/1430.pbf").isFile)
    }

    @Test
    fun staleLiveDemCachesAreDeleted() {
        val cache = File(dir, "prune-cache")
        val old = File(cache, "live/best-v1/12/1/2.png").apply { parentFile.mkdirs(); writeBytes(ByteArray(100)) }
        File(cache, "live/best-v1/12/1/3.png").writeBytes(ByteArray(50))
        val current = File(cache, "live/${LiveTiles.CACHE_VERSION}/12/1/2.png").apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) }
        val other = File(cache, "live/notes").apply { mkdirs() }
        assertEquals(listOf("best-v1"), LiveTiles.staleDemCaches(File(cache, "live")).map { it.name })
        val live = LiveTiles(emptyList(), emptyList(), cacheRoot = cache, outDir = File(dir, "out"), threads = 1)
        assertEquals(listOf("best-v1"), live.pruneStaleDemCaches().map { it.name })
        val deadline = System.currentTimeMillis() + 10_000
        while (File(cache, "live/best-v1").exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(old.exists())
        assertFalse(File(cache, "live/best-v1").exists())
        assertTrue(current.isFile)
        assertTrue(other.isDirectory)
        while (System.currentTimeMillis() < deadline) {
            val p = live.stats()["staleCachePrune"] as Map<*, *>
            if ((p["best-v1"] as? Map<*, *>)?.get("status") == "deleted") break
            Thread.sleep(10)
        }
        assertEquals(mapOf("status" to "deleted", "files" to 2L, "bytes" to 150L), (live.stats()["staleCachePrune"] as Map<*, *>)["best-v1"])
        assertTrue(LiveTiles.staleDemCaches(File(cache, "live")).isEmpty())
    }
}
