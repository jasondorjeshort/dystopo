package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpServer
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.model.RouteDirections
import io.github.jasondorjeshort.dystopo.model.RouteGpx
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.MemoryTileStaging
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.DirectionsText
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.OverlayCellFile
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.Materializer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T68: route planning for the browser over a synthetic pack (one z14 tile with two paths crossing in a
 * T and a separate path, a tilted-plane DEM), without the network.
 */
class RouteRoutesTest {
    private val dir: File = Files.createTempDirectory("route-routes-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // The z14 tile holding Paradise (public: Mount Rainier National Park).
    private val z = 14
    private val tx = WebMercator.lonToX(-121.735, z).toInt()
    private val ty = WebMercator.latToY(46.79, z).toInt()

    /** Tile units (0..4096) to lat, lon. */
    private fun at(px: Int, py: Int): Pair<Double, Double> =
        WebMercator.yToLat(ty + py / 4096.0, z) to WebMercator.xToLon(tx + px / 4096.0, z)

    private fun line(name: String, vararg p: Pair<Int, Int>) = Feature(
        null, mapOf("class" to Value.StringValue("path"), "name" to Value.StringValue(name)),
        Geometry.Lines(listOf(p.map { TilePoint(it.first, it.second) })),
    )

    private fun bounds(): DoubleArray {
        val west = WebMercator.xToLon(tx.toDouble(), z); val east = WebMercator.xToLon(tx + 1.0, z)
        val north = WebMercator.yToLat(ty.toDouble(), z); val south = WebMercator.yToLat(ty + 1.0, z)
        return doubleArrayOf(west, south, east, north)
    }

    private fun write(file: File, type: TileType, tiles: Map<Triple<Int, Int, Int>, ByteArray>, minZoom: Int, metadata: String = "{}") {
        val w = PmtilesWriter(type, staging = MemoryTileStaging())
        for ((t, b) in tiles) w.addTile(t.first, t.second, t.third, b)
        val (west, south, east, north) = bounds().toList()
        file.outputStream().buffered().use { os ->
            w.finish(ByteSink { os.write(it) }, metadata, west, south, east, north, centerZoom = z, minZoom = minZoom, maxZoom = z,
                centerLon = (west + east) / 2, centerLat = (south + north) / 2)
        }
    }

    /** A plane rising 1 m per pixel eastwards from 1600 m. */
    private fun slopePng(): ByteArray {
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        for (j in 0 until 256) for (i in 0 until 256) img.setRGB(i, j, Terrarium.encode(1600f + i))
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun packs(): File {
        val tile = VectorTile(listOf(Layer("transportation", listOf(
            line("Main Trail", 400 to 2048, 2048 to 2048, 3600 to 2048),
            line("Side Trail", 2048 to 2048, 2048 to 600),
            line("Lonely Trail", 500 to 3700, 1200 to 3700),
        )))).encode()
        write(File(dir, "t-outdoors.pmtiles"), TileType.MVT, mapOf(Triple(z, tx, ty) to tile), z)
        val png = slopePng()
        write(File(dir, "t-dem.pmtiles"), TileType.PNG, mapOf(Triple(z, tx, ty) to png, Triple(z - 1, tx / 2, ty / 2) to png), z - 1)
        return dir
    }

    private val flat = ElevationSampler { _, _ -> 1700.0 }

    @Suppress("UNCHECKED_CAST")
    private fun summary(r: Map<String, Any?>) = r["summary"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun kinds(r: Map<String, Any?>) = (r["legs"] as List<Map<String, Any?>>).map { it["kind"] }

    @Test
    fun plansAlongTrailsAndBuildsTheGraphNextToThePack() {
        val p = RoutePlanner(packs(), flat)
        val r = p.plan(listOf(at(420, 2048), at(3580, 2048)), "hike")
        assertEquals("t", r["pack"])
        assertEquals(listOf("trail"), kinds(r))
        assertEquals(emptyList<String>(), r["notes"])
        // T102: the turn directions ride along: straight past Side Trail says nothing; to Side Trail's end, a left turn.
        @Suppress("UNCHECKED_CAST")
        fun texts(m: Map<String, Any?>) = (m["instructions"] as List<Map<String, Any?>>).map { it["text"] }
        assertEquals(listOf("Head east on Main Trail", "Arrive at the end of the route"), texts(r))
        assertEquals(listOf("Head east on Main Trail", "Turn left onto Side Trail", "Arrive at the end of the route"), texts(p.plan(listOf(at(420, 2048), at(2048, 620)), "hike")))
        val d = (summary(r)["distanceM"] as Number).toDouble()
        val expected = 3160.0 / 4096 * WebMercator.metersPerPixel(46.79, z, 256) * 256
        assertTrue(kotlin.math.abs(d - expected) < 0.02 * expected, "distance $d vs $expected")
        assertTrue((summary(r)["ascentM"] as Number).toDouble() > 0, "the DEM rises eastwards")
        assertTrue(File(dir, "t-outdoors-graph.idx").isFile)
        @Suppress("UNCHECKED_CAST")
        assertEquals("built", (r["graph"] as Map<String, Any?>)["action"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("cached", (p.plan(listOf(at(420, 2048), at(3580, 2048)), "bike")["graph"] as Map<String, Any?>)["action"])
        // A fresh planner (a server restart) loads the file instead of building again.
        @Suppress("UNCHECKED_CAST")
        assertEquals("loaded", (RoutePlanner(dir, flat).plan(listOf(at(420, 2048), at(3580, 2048)), "hike")["graph"] as Map<String, Any?>)["action"])
    }

    @Test
    fun driveModeNeedsAPackWithRoadData() {
        // T73: the test pack says nothing about its profile, like a pack built before the road data.
        val p = RoutePlanner(packs(), flat)
        val old = p.plan(listOf(at(420, 2048), at(3580, 2048)), "drive")
        assertEquals("drive", old["mode"])
        assertEquals(listOf("straight"), kinds(old))
        assertEquals(listOf("drive_unsupported"), old["notes"])
        @Suppress("UNCHECKED_CAST")
        val msg = (old["messages"] as Map<String, String>).getValue("drive_unsupported")
        assertTrue("t-outdoors.pmtiles was built before it" in msg, msg)
        assertEquals(io.github.jasondorjeshort.dystopo.routing.Router.DRIVE_NOTICE, old["notice"]) // ADR 0032 (T102)
        assertEquals(null, p.plan(listOf(at(420, 2048), at(3580, 2048)), "hike")["notice"])
        assertFailsWith<IllegalArgumentException> { p.plan(listOf(at(420, 2048), at(3580, 2048)), "car") }
        // An outdoors pack from profile 2 on: the road is driven, the trail beside it is not.
        val road = Feature(
            null, mapOf("class" to Value.StringValue("minor"), "name" to Value.StringValue("Park Road")),
            Geometry.Lines(listOf(listOf(TilePoint(400, 2400), TilePoint(3600, 2400)))),
        )
        val tile = VectorTile(listOf(Layer("transportation", listOf(line("Main Trail", 400 to 2048, 3600 to 2048), road)))).encode()
        File(dir, "t-outdoors-graph.idx").delete()
        write(File(dir, "t-outdoors.pmtiles"), TileType.MVT, mapOf(Triple(z, tx, ty) to tile), z, """{"dystopo:profile":"outdoors","dystopo:profile_version":"2"}""")
        val r = RoutePlanner(dir, flat).plan(listOf(at(420, 2400), at(3580, 2400)), "drive")
        assertEquals(listOf("trail"), kinds(r))
        assertEquals(emptyList<String>(), r["notes"])
        // A tap on the trail snaps onto the road for a car.
        val tap = RoutePlanner(dir, flat).plan(listOf(at(420, 2048), at(3580, 2048)), "drive")
        @Suppress("UNCHECKED_CAST")
        val via = tap["via"] as List<List<Double>>
        assertTrue(kotlin.math.abs(via[0][0] - at(420, 2400).first) < 1e-4, "start snapped to the road: ${via[0]}")
    }

    /**
     * T107: a pack replaced under a running server. Its graph reopens on the next route, and the overlay warm-up
     * must run again for the new open: when only the file's timestamp changed (the graph file is reused, same
     * source) the cell file is registered again; when its content changed the graph and the cells are built again.
     * Before the fix `warm()` returned early on the old entry, so `cellFiles` stayed empty until a restart.
     */
    @Test
    fun aPackReplacedUnderARunningServerKeepsItsOverlayCells() {
        // Two z14 tiles on either side of a z11 tile border, a tertiary road across it: one overlay cell each side.
        val x0 = tx or 7
        val x1 = x0 + 1
        val west = WebMercator.xToLon(x0.toDouble(), z); val east = WebMercator.xToLon(x1 + 1.0, z)
        val north = WebMercator.yToLat(ty.toDouble(), z); val south = WebMercator.yToLat(ty + 1.0, z)
        fun road(name: String, a: Int, b: Int, y: Int) = VectorTile(listOf(Layer("transportation", listOf(Feature(
            null, mapOf("class" to Value.StringValue("tertiary"), "name" to Value.StringValue(name)),
            Geometry.Lines(listOf(listOf(TilePoint(a, y), TilePoint(b, y)))),
        ))))).encode()
        val vector = File(dir, "w-outdoors.pmtiles")
        fun writePack(extra: Boolean) {
            val tiles = mutableMapOf(Triple(z, x0, ty) to road("Rim Road", 400, 4096, 2048), Triple(z, x1, ty) to road("Rim Road", 0, 3600, 2048))
            if (extra) tiles[Triple(z, x1, ty)] = VectorTile(listOf(Layer("transportation", listOf(
                Feature(null, mapOf("class" to Value.StringValue("tertiary"), "name" to Value.StringValue("Rim Road")), Geometry.Lines(listOf(listOf(TilePoint(0, 2048), TilePoint(3600, 2048))))),
                Feature(null, mapOf("class" to Value.StringValue("minor"), "name" to Value.StringValue("Spur")), Geometry.Lines(listOf(listOf(TilePoint(2000, 2048), TilePoint(2000, 3500))))),
            )))).encode()
            val w = PmtilesWriter(TileType.MVT, staging = MemoryTileStaging())
            for ((t, b) in tiles) w.addTile(t.first, t.second, t.third, b)
            vector.outputStream().buffered().use { os ->
                w.finish(ByteSink { os.write(it) }, """{"dystopo:profile":"outdoors","dystopo:profile_version":"2"}""", west, south, east, north,
                    centerZoom = z, minZoom = z, maxZoom = z, centerLon = (west + east) / 2, centerLat = (south + north) / 2)
            }
        }
        writePack(false)
        val lat = WebMercator.yToLat(ty + 0.5, z)
        val from = lat to WebMercator.xToLon(x0 + 0.2, z)
        val to = lat to WebMercator.xToLon(x1 + 0.8, z)
        val cellFile = File(dir, "w-outdoors-overlay.idx")

        @Suppress("UNCHECKED_CAST")
        fun overlay(p: RoutePlanner) = p.status()["driveOverlay"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        fun warm(p: RoutePlanner) = overlay(p)["warm"] as List<Map<String, Any?>>
        fun settled(p: RoutePlanner): Map<String, Any?> {
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                warm(p).singleOrNull()?.takeIf { it["state"] != "queued" }?.let { return it }
                Thread.sleep(20)
            }
            error("the warm-up did not finish: ${warm(p)}")
        }
        @Suppress("UNCHECKED_CAST")
        fun drive(p: RoutePlanner) = p.plan(listOf(from, to), "drive").also { assertEquals(listOf("trail"), kinds(it), it.toString()) }["graph"] as Map<String, Any?>

        // A first server builds the graph and, in the background, the cells and their file.
        val first = RoutePlanner(dir, flat)
        assertEquals("built", drive(first)["action"])
        val built = settled(first)
        assertEquals("done", built["state"], built.toString())
        assertEquals(2, built["cells"], built.toString())
        assertTrue(cellFile.isFile)

        // A restarted server reads the cell file.
        val p = RoutePlanner(dir, flat)
        assertEquals("loaded", drive(p)["action"])
        assertEquals("loaded", settled(p)["state"])
        assertEquals(listOf("w-outdoors.pmtiles"), overlay(p)["cellFiles"])

        // The pack's file is copied over with the same bytes (a new timestamp): the same graph file reopens, and
        // its cell file is registered again (the bug: `cellFiles` empty here while `warm` still said loaded).
        assertTrue(vector.setLastModified(vector.lastModified() + 10_000))
        assertEquals("loaded", drive(p)["action"])
        val again = settled(p)
        assertEquals("loaded", again["state"], again.toString())
        assertEquals(2, again["cells"], again.toString())
        assertEquals(listOf("w-outdoors.pmtiles"), overlay(p)["cellFiles"], overlay(p).toString())

        // The pack is rebuilt with other content: the graph is built again, the stale cell file is replaced by new
        // cells (one entry, this graph's), and the drive still follows the road.
        writePack(true)
        val before = cellFile.lastModified()
        assertEquals("built", drive(p)["action"])
        val rebuilt = settled(p)
        assertEquals("done", rebuilt["state"], rebuilt.toString())
        assertEquals(2, rebuilt["cells"], rebuilt.toString())
        val src = FileRangeSource(cellFile)
        assertEquals(2, OverlayCellFile.open { o, l -> src.read(o, l) }.cellCount, "the cell file holds the new graph's cells")
        assertTrue(cellFile.lastModified() >= before)
    }

    @Test
    fun editsRerouteAndFallBackToStraightLegs() {
        val p = RoutePlanner(packs(), flat)
        val r = p.plan(listOf(at(420, 2048), at(3580, 2048)), "hike")
        // A via point up the side trail: two trail legs through it.
        val added = p.edit(mapOf("path" to r["path"], "op" to "add", "lat" to at(2048, 700).first, "lon" to at(2048, 700).second))
        assertEquals(2, added["index"])
        assertEquals(listOf("trail", "trail"), kinds(added))
        val moved = p.edit(mapOf("path" to added["path"], "op" to "move", "index" to 1, "lat" to at(3000, 2048).first, "lon" to at(3000, 2048).second))
        assertNotEquals(added["path"], moved["path"])
        assertEquals(listOf("trail", "trail"), kinds(moved))
        // The lonely trail is another network: the leg to it is straight, with DEM elevation every 25 m.
        val far = p.edit(mapOf("path" to moved["path"], "op" to "add", "lat" to at(800, 3700).first, "lon" to at(800, 3700).second))
        assertEquals(listOf("trail", "trail", "straight"), kinds(far))
        assertTrue("not_connected" in (far["notes"] as List<*>), far["notes"].toString())
        assertTrue((summary(far)["straightM"] as Number).toDouble() > 100)
        @Suppress("UNCHECKED_CAST")
        val straight = (far["legs"] as List<Map<String, Any?>>).last()["coordinates"] as List<List<Double>>
        assertTrue(straight.size > 3 && straight.all { it.size == 3 && it[2] == 1700.0 })
        val removed = p.edit(mapOf("path" to far["path"], "op" to "remove", "index" to 3))
        assertEquals(moved["path"], removed["path"])
        val shown = p.edit(mapOf("path" to moved["path"], "op" to "show"))
        assertEquals(moved["path"], shown["path"])
        assertEquals("bike", p.edit(mapOf("path" to moved["path"], "op" to "mode", "mode" to "bike"))["mode"])
        assertFailsWith<IllegalArgumentException> { p.edit(mapOf("path" to moved["path"], "op" to "fly")) }
        assertFailsWith<IllegalArgumentException> { p.edit(mapOf("path" to "%%%", "op" to "show")) }
        assertFailsWith<IllegalArgumentException> { p.edit(mapOf("path" to r["path"], "op" to "remove", "index" to 0)) } // keeps two points
        assertFailsWith<IllegalArgumentException> { p.plan(listOf(at(420, 2048), at(3580, 2048)), "swim") }
    }

    @Test
    fun outsideEveryPackTheRouteIsStraight() {
        val r = RoutePlanner(packs(), null).plan(listOf(45.83 to 6.86, 45.84 to 6.87), "hike")
        assertEquals(null, r["pack"])
        assertEquals(listOf("straight"), kinds(r))
        assertEquals(listOf("no_graph"), r["notes"])
    }

    @Test
    fun gpxAndBundleCarryTheTripAsThePhoneStoresIt() {
        val p = RoutePlanner(packs(), flat)
        val r = p.plan(listOf(at(420, 2048), at(3580, 2048)), "hike")
        val path = r["path"] as String
        val id = "0b6f1c2e-1d2a-4c5b-9e8f-112233445566"
        val gpx = p.gpx(mapOf("path" to path, "name" to "Paradise", "id" to id))
        assertEquals(RouteGpx.gpx(RoutePlan(id, "Paradise", RoutePath.decode(path))), gpx)
        assertTrue("<rte>" in gpx && id in gpx)

        val device = "5f0e6a52-3a6b-4b8e-9a51-0c1d2e3f4a5b"
        fun h(c: Int) = "%012x.%06x.%s".format(1_790_000_000_000L, c, device)
        val ops = listOf(
            mapOf("hlc" to h(2), "type" to "set", "id" to id, "field" to "waypoints", "value" to r["waypointsValue"]),
            mapOf("hlc" to h(0), "type" to "put", "id" to id, "name" to "Paradise"),
            mapOf("hlc" to h(1), "type" to "set", "id" to id, "field" to "path", "value" to path),
        )
        val bundle = Bundle.decode(Gzip.decompress(p.bundle(mapOf("device" to device, "ops" to ops))))
        assertEquals(device, bundle.from)
        assertEquals(3, bundle.ops.size)
        assertEquals(bundle.ops.sortedBy { it.hlc }, bundle.ops)
        assertEquals(h(2), bundle.vector[device].text)
        val plan = Materializer.routePlan(id, bundle.ops)!!
        assertEquals("Paradise", plan.name)
        assertEquals(RoutePath.decode(path), plan.path)
        assertEquals(gpx, RouteGpx.gpx(plan)) // what the phone exports after a sync

        val deleted = Bundle.decode(Gzip.decompress(p.bundle(mapOf("device" to device, "ops" to ops + mapOf("hlc" to h(3), "type" to "delete", "id" to id)))))
        assertEquals(null, Materializer.routePlan(id, deleted.ops))

        // T116: the browser-side directions op: the reply's directionsValue is the trip's `directions` field for the
        // reply's path, so the phone reads the saved directions from a trip the browser saved (no graph needed).
        val value = r["directionsValue"] as String
        val saved = RouteDirections.decode(value)
        assertTrue(saved.isFor(RoutePath.decode(path)))
        @Suppress("UNCHECKED_CAST") val replyTexts = (r["instructions"] as List<Map<String, Any?>>).map { it["text"] }
        assertEquals(replyTexts, Directions.shown(saved.list).map { DirectionsText.of(it, TravelMode.HIKE) })
        val withDirections = ops + mapOf("hlc" to h(3), "type" to "set", "id" to id, "field" to "directions", "value" to value)
        val trip = Materializer.routePlan(id, Bundle.decode(Gzip.decompress(p.bundle(mapOf("device" to device, "ops" to withDirections)))).ops)!!
        assertEquals(saved.list, trip.savedDirections)
        // An edit answers the directions of its new path; a value that does not decode is refused.
        val edited = p.edit(mapOf("path" to path, "op" to "add", "lat" to at(2048, 700).first, "lon" to at(2048, 700).second))
        val again = RouteDirections.decode(edited["directionsValue"] as String)
        assertTrue(again.isFor(RoutePath.decode(edited["path"] as String)) && !again.isFor(RoutePath.decode(path)))
        assertFailsWith<IllegalArgumentException> { p.bundle(mapOf("device" to device, "ops" to listOf(mapOf("hlc" to h(4), "type" to "set", "id" to id, "field" to "directions", "value" to "AAAA")))) }
        // No graph open for a path with trail legs: only the frame could be said, so nothing is offered to save (a
        // phone with the pack makes them when it opens the trip); straight legs everywhere: the frame is all there is.
        val bare = RoutePlanner(Files.createTempDirectory("route-routes-bare").toFile().also { it.deleteOnExit() }, flat)
        assertEquals(null, bare.edit(mapOf("path" to path, "op" to "show"))["directionsValue"])
        val straight = bare.plan(listOf(45.83 to 6.86, 45.84 to 6.87), "hike")
        assertEquals(listOf("straight"), kinds(straight))
        assertTrue(RouteDirections.decode(straight["directionsValue"] as String).isFor(RoutePath.decode(straight["path"] as String)))

        val other = "%012x.%06x.%s".format(1_790_000_000_000L, 9, "someone-else")
        assertFailsWith<IllegalArgumentException> { p.bundle(mapOf("device" to device, "ops" to listOf(mapOf("hlc" to other, "type" to "put", "id" to id)))) }
        assertFailsWith<IllegalArgumentException> { p.bundle(mapOf("device" to device, "ops" to listOf(mapOf("hlc" to h(4), "type" to "set", "id" to id, "field" to "path", "value" to "AAAA")))) }
        assertFailsWith<IllegalArgumentException> { p.bundle(mapOf("device" to device, "ops" to listOf(mapOf("hlc" to h(4), "type" to "set", "id" to id, "field" to "points", "value" to "x")))) }
        assertFailsWith<IllegalArgumentException> { p.bundle(mapOf("device" to device, "ops" to listOf(mapOf("hlc" to "nope", "type" to "put", "id" to id)))) }
        assertFailsWith<IllegalArgumentException> { p.bundle(mapOf("device" to device, "ops" to listOf(ops[1], ops[1]))) }
    }

    @Test
    fun servedThroughLiveTiles() {
        packs()
        File(dir, "trips.js").writeText("export function install() {}\n")
        val page = File(dir, "index.html").apply { writeText("<html></html>") }
        val live = LiveTiles(emptyList(), emptyList(), cacheRoot = File(dir, "cache"), outDir = dir, threads = 1,
            generateDem = { LiveTiles.LiveDem(slopePng(), listOf("copernicus")) }, fetchOfm = { null })
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", live.handler(File(dir, "glyphs"), page) { "http://127.0.0.1" })
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val client = HttpClient.newHttpClient()
            fun get(path: String) = client.send(HttpRequest.newBuilder(URI(base + path)).build(), HttpResponse.BodyHandlers.ofString())
            fun post(path: String, body: String) = client.send(HttpRequest.newBuilder(URI(base + path)).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            val (a, b) = at(420, 2048) to at(3580, 2048)
            val r = get("/route?from=${a.first},${a.second}&to=${b.first},${b.second}&mode=hike")
            assertEquals(200, r.statusCode(), r.body())
            val json = MiniJson.parseObject(r.body())
            assertEquals("t", json["pack"])
            val e = post("/route/edit", MiniJson.encode(mapOf("path" to json["path"], "op" to "show")))
            assertEquals(200, e.statusCode(), e.body())
            assertEquals(400, get("/route?from=91,0&to=0,0").statusCode())
            assertEquals(400, post("/route/edit", "{not json").statusCode())
            assertEquals(405, get("/route/edit").statusCode())
            assertEquals(404, get("/route/nope").statusCode())
            assertTrue("\"t\"" in get("/route/packs").body())
            assertTrue("\"plan\"" in get("/route/status").body())
            val js = get("/trips.js")
            assertEquals(200, js.statusCode())
            assertTrue(js.body().startsWith("export function install"))
            assertTrue(js.headers().firstValue("Content-Type").get().startsWith("text/javascript"))
            assertEquals(200, post("/route/bundle", """{"device":"d1","ops":[]}""").statusCode())
        } finally {
            server.stop(0)
        }
    }
}
