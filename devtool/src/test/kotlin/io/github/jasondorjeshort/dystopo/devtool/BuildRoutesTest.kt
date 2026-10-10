package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/build` without the network: fake live DEM renderer and OpenFreeMap fetcher, real archives written to a temp out/. */
class BuildRoutesTest {
    private val dir: File = Files.createTempDirectory("build-routes-test").toFile()
    private val out = File(dir, "out")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val png: ByteArray = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).let { img ->
        for (j in 0 until 256) for (i in 0 until 256) img.setRGB(i, j, Terrarium.encode(1000f + i))
        ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private val mvt = "fake vector tile".toByteArray()
    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private val tileJson = """{"tiles":["https://example.invalid/planet/b7/{z}/{x}/{y}.pbf"],
        "vector_layers":[{"id":"water","fields":{"class":"String"},"minzoom":0,"maxzoom":14},{"id":"mountain_peak","fields":{"name":"String"},"minzoom":7,"maxzoom":14}]}"""

    /** A Mont Blanc sized box. */
    private val bbox = "6.78,45.80,6.95,45.93"
    private val box = BoundingBox(6.78, 45.80, 6.95, 45.93)

    private fun live(fetch: (String) -> ByteArray? = { gz(mvt) }, renders: AtomicInteger = AtomicInteger()) = LiveTiles(
        emptyList(), emptyList(), cacheRoot = File(dir, "cache"), outDir = out, threads = 2,
        generateDem = { t ->
            renders.incrementAndGet()
            // Tiles west of x=... are "ocean": no source contributed.
            LiveTiles.LiveDem(png, if (t.z == 8) emptyList() else listOf("copernicus", "egm2008"))
        },
        fetchOfm = fetch,
        ofmTileJson = { tileJson },
    )

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
    fun buildsBothPacksWithMetadataAndServesThem() {
        val renders = AtomicInteger()
        val live = live(renders = renders)
        val builds = BuildRoutes(live, out)
        val job = builds.submit(mapOf("name" to "mb", "bbox" to bbox, "minZoom" to "8", "maxZoom" to "10"))
        val done = await(builds, job.id)
        assertEquals(BuildRoutes.Status.DONE, done.status, done.json().toString())
        val demTotal = (8..10).sumOf { WebMercator.tilesCovering(box, it).size }
        assertEquals(demTotal, done.dem.total)
        assertEquals(demTotal, done.dem.done.get())
        assertEquals(WebMercator.tilesCovering(box, 8).size, done.dem.empty.get()) // the no-data z8 tile is left out
        assertEquals(demTotal, renders.get())

        val dem = PmtilesReader.open(FileRangeSource(File(out, "mb-dem.pmtiles")), Gzip.decompressors)
        assertEquals(TileType.PNG, dem.header.tileType)
        assertEquals(9, dem.header.minZoom) // z8 had no data
        assertEquals(10, dem.header.maxZoom)
        val demMeta = dem.metadata()
        assertEquals(listOf("copernicus", "egm2008"), demMeta["provenance"])
        assertEquals("best", demMeta["source"])
        // Licence of the data actually in the tiles, from the registry (T37 follow-up).
        assertEquals("LicenseRef-Copernicus-DEM AND LicenseRef-US-Government-Public-Domain", demMeta["license"])

        val osm = PmtilesReader.open(FileRangeSource(File(out, "mb-outdoors.pmtiles")), Gzip.decompressors)
        assertEquals(TileType.MVT, osm.header.tileType)
        assertEquals(Compression.GZIP, osm.header.tileCompression)
        assertEquals(8, osm.header.minZoom)
        val t = WebMercator.tilesCovering(box, 10).first()
        assertContentEquals(mvt, osm.tile(10, t.x, t.y))
        val m = osm.metadata()
        assertEquals("osm,openfreemap,openmaptiles", m["dystopo:provenance"]) // registry order
        assertEquals("openmaptiles", m["dystopo:profile"])
        assertEquals("b7", m["dystopo:source_build"])
        assertEquals(listOf("water", "mountain_peak"), (m["vector_layers"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals(6.78, osm.header.minLon, 1e-7)

        assertEquals(listOf("/out/mb-dem.pmtiles", "/out/mb-outdoors.pmtiles"), done.files.map { it["url"] })
        assertFalse(out.listFiles()!!.any { it.name.endsWith(".building") || it.name.endsWith(".staging") })
        // The finished pack now answers the viewer's tiles.
        val inPack = WebMercator.tilesCovering(box, 10).first()
        assertEquals("pack", live.dem(10, inPack.x, inPack.y)!!.source)
        assertEquals("pack", live.osm(10, inPack.x, inPack.y)!!.source)

        // A second build of the same name needs overwrite=1; then it reuses the cache (no new renders).
        val e = assertFailsWith<BuildRoutes.Refused> { builds.submit(mapOf("name" to "mb", "bbox" to bbox, "minZoom" to "8", "maxZoom" to "10")) }
        assertEquals(409, e.status)
        val again = await(builds, builds.submit(mapOf("name" to "mb", "bbox" to bbox, "minZoom" to "8", "maxZoom" to "10", "overwrite" to "1")).id)
        assertEquals(BuildRoutes.Status.DONE, again.status)
        assertEquals(demTotal, renders.get())
        assertEquals(demTotal, again.dem.cached.get())
        assertEquals(again.osm.total, again.osm.cached.get())
    }

    @Test
    fun refusesBadAndOversizedRequests() {
        val builds = BuildRoutes(live(), out, maxTiles = 500, maxSpanDegrees = 1.0)
        fun refused(vararg p: Pair<String, String>) = assertFailsWith<BuildRoutes.Refused> { builds.submit(mapOf(*p)) }
        assertEquals(400, refused("name" to "../x", "bbox" to bbox).status)
        assertEquals(400, refused("name" to "a", "bbox" to "1,2,3").status)
        assertEquals(400, refused("name" to "a", "bbox" to "7,46,7,47").status)
        assertEquals(400, refused("name" to "a", "bbox" to "-181,46,7,47").status)
        // West > east crosses the antimeridian; this one would be 359 degrees wide.
        assertEquals(413, refused("name" to "a", "bbox" to "7,46,6,47").status)
        val deep = refused("name" to "a", "bbox" to bbox, "maxZoom" to "16")
        assertEquals(413, deep.status)
        assertTrue(deep.message!!.contains("use maxZoom=15"), deep.message)
        assertEquals(400, refused("name" to "a", "bbox" to bbox, "minZoom" to "9", "maxZoom" to "8").status)
        val span = refused("name" to "a", "bbox" to "6,45,7.5,46")
        assertEquals(413, span.status)
        assertTrue(span.message!!.contains("degrees per side"))
        val big = refused("name" to "a", "bbox" to bbox, "minZoom" to "8", "maxZoom" to "15")
        assertEquals(413, big.status)
        assertTrue(big.message!!.contains("limit is 500"), big.message)
        assertTrue(big.message!!.contains("maxZoom=14 fits"), big.message)
    }

    @Test
    fun countsTilesLikeTilesCovering() {
        for (z in 0..15) assertEquals(WebMercator.tilesCovering(box, z).size, BuildRoutes.countTiles(box, z))
        for ((_, half) in BuildRoutes.antimeridianParts("fj", 179.2, -17.2, -179.8, -16.4)) {
            for (z in 0..15) assertEquals(WebMercator.tilesCovering(half, z).size, BuildRoutes.countTiles(half, z))
        }
    }

    @Test
    fun antimeridianBoxIsSplitIntoTwoPacks() {
        val builds = BuildRoutes(live(), out)
        // Taveuni and Vanua Levu (Fiji) straddle 180 degrees.
        val first = builds.submit(mapOf("name" to "fj", "bbox" to "179.2,-17.2,-179.8,-16.4", "minZoom" to "8", "maxZoom" to "10"))
        val otherId = first.antimeridian!!["other"]!!
        assertEquals("east", first.antimeridian!!["part"])
        val east = await(builds, first.id)
        val west = await(builds, otherId)
        assertEquals(BuildRoutes.Status.DONE, east.status, east.json().toString())
        assertEquals(BuildRoutes.Status.DONE, west.status, west.json().toString())
        assertEquals("fj-e", east.name)
        assertEquals("fj-w", west.name)
        assertEquals(mapOf("part" to "west", "other" to east.id), west.json()["antimeridian"])
        for ((name, w, e) in listOf(Triple("fj-e", 179.2, 180.0), Triple("fj-w", -180.0, -179.8))) {
            for (role in listOf("dem", "outdoors")) {
                val h = PmtilesReader.open(FileRangeSource(File(out, "$name-$role.pmtiles")), Gzip.decompressors).header
                assertEquals(w, h.minLon, 1e-6, "$name-$role")
                assertEquals(e, h.maxLon, 1e-6, "$name-$role")
                assertTrue(h.minLon < h.maxLon)
            }
        }
        val halves = BuildRoutes.antimeridianParts("fj", 179.2, -17.2, -179.8, -16.4).map { it.second }
        assertEquals((8..10).sumOf { z -> halves.sumOf { WebMercator.tilesCovering(it, z).size } }, east.osm.total + west.osm.total)
        // The span limit counts the box across 180, not 359 degrees.
        val narrow = BuildRoutes(live(), out, maxSpanDegrees = 0.5)
        assertEquals(413, assertFailsWith<BuildRoutes.Refused> { narrow.submit(mapOf("name" to "fk", "bbox" to "179.8,-17,-179.6,-16.9")) }.status)
    }

    @Test
    fun cancelStopsFetching() {
        val fetches = AtomicInteger()
        val gate = CountDownLatch(1)
        val live = live(fetch = { _ ->
            fetches.incrementAndGet()
            gate.await(10, TimeUnit.SECONDS)
            gz(mvt)
        })
        val builds = BuildRoutes(live, out)
        val job = builds.submit(mapOf("name" to "cx", "bbox" to bbox, "minZoom" to "13", "maxZoom" to "14"))
        val deadline = System.currentTimeMillis() + 20_000
        while (fetches.get() < 4 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(4, fetches.get()) // OpenFreeMap politeness: 4 requests in flight, the rest wait
        assertTrue(builds.cancel(job))
        gate.countDown()
        val done = await(builds, job.id)
        assertEquals(BuildRoutes.Status.CANCELLED, done.status)
        Thread.sleep(200)
        val after = fetches.get()
        assertTrue(after <= 8, "fetches after cancel: $after") // at most the window already requested
        Thread.sleep(300)
        assertEquals(after, fetches.get())
        assertTrue(done.osm.done.get() < done.osm.total)
        assertFalse(File(out, "cx-dem.pmtiles").exists())
        assertFalse(out.listFiles()!!.any { it.name.startsWith("cx-") })
        // A queued job that is cancelled never starts.
        assertFalse(builds.cancel(done))
    }

    @Test
    fun liveDemForBuildsCarriesProvenance() {
        val live = live()
        val t = TileId(10, 531, 362)
        val first = live.liveDemAsync(t.z, t.x, t.y).join()
        assertFalse(first.cached)
        assertEquals(listOf("copernicus", "egm2008"), first.provenance)
        val second = live.liveDemAsync(t.z, t.x, t.y).join()
        assertTrue(second.cached)
        assertEquals(listOf("copernicus", "egm2008"), second.provenance)
    }
}
