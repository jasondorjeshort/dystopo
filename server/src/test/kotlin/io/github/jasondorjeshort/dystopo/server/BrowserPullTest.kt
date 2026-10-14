package io.github.jasondorjeshort.dystopo.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport
import io.github.jasondorjeshort.dystopo.sync.RouteOps
import io.github.jasondorjeshort.dystopo.sync.Sync
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T92: the pull side of the browser's trip replica (`GET /sync/routes?full=1`) and its waypoint edits
 * (`POST /sync/waypoints/encode`, then `wpt:` operations through `/route/bundle` and `/sync/push`), against a
 * phone-like store syncing with the same server.
 */
class BrowserPullTest {
    private val dir: File = Files.createTempDirectory("pull").toFile()
    private var server: Server? = null
    private val client = HttpClient.newHttpClient()
    private val alice = "alice-token-0123456789abcdef"

    @AfterTest
    fun cleanup() {
        server?.stop("test")
        SyncRoutes.dbOverride = null
        SyncRoutes.userDir = null
        SyncRoutes.publicUrl = null
        SyncRoutes.reset()
        dir.deleteRecursively()
    }

    private fun call(method: String, path: String, body: String? = null, type: String = "application/json"): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:${server!!.port}$path"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .header("Authorization", "Bearer $alice").header("Content-Type", type)
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun obj(r: HttpResponse<ByteArray>) = MiniJson.parseObject(r.body().decodeToString())

    @Test
    fun theBrowserPullsRoutesAndEditsWaypoints() {
        server = Server(
            ServerConfig(users = listOf(ServerConfig.UserConfig("alice", alice)), bind = "127.0.0.1", port = 0, cacheDir = File(dir, "cache").path,
                outDir = File(dir, "out").path, fetchGlyphs = false, shutdownGraceSeconds = 1),
            null, JsonLog(java.io.PrintStream(java.io.ByteArrayOutputStream(), true, Charsets.UTF_8)),
        ).also { it.start() }
        val phone = SqlTrackStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema), System::currentTimeMillis)
        val summit = Waypoint("9d0e8f7a-1b2c-4d3e-8f4a-5b6c7d8e9f0a", "Panorama Point", 46.8024, -121.7289, 2070.0)
        val route = RoutePlan(
            "3c5a7e9b-2d4f-4a6b-8c0d-1e3f5a7b9c1d", "Paradise to Panorama",
            RoutePath.fixed(listOf(RoutePoint(46.786, -121.736, 1647.0), RoutePoint(46.795, -121.733, 1800.0), RoutePoint(46.8024, -121.7289, 2070.0))).quantized(),
            listOf(summit),
        )
        phone.putRoutePlan(route, System.currentTimeMillis())
        val post = { url: String, b: ByteArray ->
            client.send(HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $alice").POST(HttpRequest.BodyPublishers.ofByteArray(b)).build(), HttpResponse.BodyHandlers.ofByteArray()).body()
        }
        val sync = { Sync.exchange(phone, HttpSyncTransport("http://127.0.0.1:${server!!.port}", post, Gzip.compress, Gzip.decompress), System::currentTimeMillis, { System.nanoTime() / 1_000_000 }, 0) }
        sync()

        // Pull: the full list carries the path value and the waypoints; the short list does not.
        val short = (obj(call("GET", "/sync/routes"))["routes"] as List<*>).single() as Map<*, *>
        assertEquals(null, short["path"])
        val full = (obj(call("GET", "/sync/routes?full=1"))["routes"] as List<*>).single() as Map<*, *>
        assertEquals(route.id, full["id"])
        assertEquals(route.path.quantized().encode(), full["path"])
        assertEquals(RoutePath.decode(full["path"] as String).points.size, 3)
        val listed = (full["waypointList"] as List<*>).single() as Map<*, *>
        assertEquals(listOf(summit.id, "Panorama Point"), listOf(listed["id"], listed["name"]))

        // The browser adds a waypoint and renames the summit: encode, then wpt: operations from its own device.
        val camp = "4e6f8a0b-3c5d-4e7f-9a1b-2c3d4e5f6a7b"
        val enc = obj(call("POST", "/sync/waypoints/encode", """{"waypoints": [
            {"id": "$camp", "name": "Glacier Vista", "lat": 46.7985, "lon": -121.7325},
            {"id": "${summit.id}", "name": "Panorama Point (top)", "lat": 46.8024, "lon": -121.7289, "ele": 2070}]}"""))
        @Suppress("UNCHECKED_CAST") val values = enc["values"] as Map<String, String>
        assertEquals("Glacier Vista", RouteOps.decodeWaypoint(values[camp])!!.name)
        val device = "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"
        val wall = System.currentTimeMillis()
        fun hlc(i: Int) = "%012x.%06x.%s".format(wall + 1000, i, device)
        val ops = listOf(
            mapOf("hlc" to hlc(0), "type" to "set", "id" to route.id, "field" to RouteOps.waypointField(camp), "value" to values[camp]),
            mapOf("hlc" to hlc(1), "type" to "set", "id" to route.id, "field" to RouteOps.waypointField(summit.id), "value" to values[summit.id]),
        )
        val bundle = call("POST", "/route/bundle", JsonWriter.write(mapOf("device" to device, "ops" to ops)))
        assertEquals(200, bundle.statusCode(), bundle.body().decodeToString())
        val pushed = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${server!!.port}/sync/push")).header("Authorization", "Bearer $alice")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bundle.body())).build(), HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, pushed.statusCode(), pushed.body())

        // The phone gets both edits; the browser's next pull lists them in the phone's order.
        sync()
        assertEquals(listOf("Panorama Point (top)", "Glacier Vista"), phone.routePlan(route.id)!!.waypoints.map { it.name })
        val again = (obj(call("GET", "/sync/routes?full=1"))["routes"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("Panorama Point (top)", "Glacier Vista"), (again["waypointList"] as List<*>).map { (it as Map<*, *>)["name"] })

        // A removal is a null wpt: value.
        val removal = call("POST", "/route/bundle", JsonWriter.write(mapOf("device" to device, "ops" to listOf(
            mapOf("hlc" to hlc(2), "type" to "set", "id" to route.id, "field" to RouteOps.waypointField(camp), "value" to null)))))
        client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server!!.port}/sync/push")).header("Authorization", "Bearer $alice")
            .POST(HttpRequest.BodyPublishers.ofByteArray(removal.body())).build(), HttpResponse.BodyHandlers.ofString())
        sync()
        assertEquals(listOf("Panorama Point (top)"), phone.routePlan(route.id)!!.waypoints.map { it.name })

        // Bad input is a 400, never stored.
        assertEquals(400, call("POST", "/sync/waypoints/encode", """{"waypoints": [{"id": "not-a-uuid", "name": "x", "lat": 1, "lon": 2}]}""").statusCode())
        assertEquals(400, call("POST", "/sync/waypoints/encode", """{"waypoints": [{"id": "$camp", "name": " ", "lat": 1, "lon": 2}]}""").statusCode())
        assertEquals(400, call("POST", "/sync/waypoints/encode", """{"waypoints": [{"id": "$camp", "name": "x", "lat": 91, "lon": 2}]}""").statusCode())
        assertEquals(405, call("GET", "/sync/waypoints/encode").statusCode())
    }
}
