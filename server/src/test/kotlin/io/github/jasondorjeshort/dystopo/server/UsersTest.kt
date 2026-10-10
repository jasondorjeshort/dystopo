package io.github.jasondorjeshort.dystopo.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jasondorjeshort.dystopo.model.RouteGpx
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.TrackImport
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport
import io.github.jasondorjeshort.dystopo.sync.Sync
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T67: users with their own replicas, the admin token, `/admin/users`, the user flags and share links. */
class UsersTest {
    private val dir: File = Files.createTempDirectory("users").toFile()
    private val log = JsonLog(PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
    private var server: Server? = null
    private val client = HttpClient.newHttpClient()

    private val alice = "alice-token-0123456789abcdef"
    private val bob = "bob-token-0123456789abcdefgh"
    private val admin = "admin-token-0123456789abcdef"

    @AfterTest
    fun cleanup() {
        server?.stop("test")
        // SyncRoutes is process-wide: put it back for the other tests.
        SyncRoutes.dbOverride = null
        SyncRoutes.userDir = null
        SyncRoutes.publicUrl = null
        SyncRoutes.reset()
        dir.deleteRecursively()
    }

    private fun call(method: String, path: String, token: String? = null, body: ByteArray? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:${server!!.port}$path"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
        token?.let { b.header("Authorization", "Bearer $it") }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(url: String, body: ByteArray, token: String): ByteArray {
        val r = client.send(
            HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $token").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(r.statusCode() == 200) { "HTTP ${r.statusCode()} ${r.body().decodeToString()}" }
        return r.body()
    }

    private fun store() = SqlTrackStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema), System::currentTimeMillis)

    private fun sync(s: SqlTrackStore, token: String) =
        Sync.exchange(s, HttpSyncTransport("http://127.0.0.1:${server!!.port}", { u, b -> post(u, b, token) }, Gzip.compress, Gzip.decompress), System::currentTimeMillis, { System.nanoTime() / 1_000_000 }, 0)

    private fun gpx(name: String, lat: Double) = """<?xml version="1.0"?><gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>$name</name><trkseg>""" +
        (0 until 5).joinToString("") { """<trkpt lat="${lat + it * 0.001}" lon="-121.73"><ele>${1600 + it}</ele><time>2026-01-01T00:00:0${it}Z</time></trkpt>""" } +
        "</trkseg></trk></gpx>"

    private fun json(r: HttpResponse<String>) = MiniJson.parseObject(r.body())

    private fun start(config: ServerConfig, configFile: File? = null): Server {
        val out = File(dir, "out").apply { mkdirs() }
        return Server(
            config.copy(bind = "127.0.0.1", port = 0, cacheDir = File(dir, "cache").path, outDir = out.path, fetchGlyphs = false, shutdownGraceSeconds = 1),
            configFile, log,
        ).also { it.start(); server = it }
    }

    @Test
    fun twoUsersTwoReplicasAdminAndShareLinks() {
        val configFile = File(dir, "server.json")
        configFile.writeText("""{"users": [{"id": "alice", "token": "$alice"}, {"id": "bob", "token": "$bob"}], "adminToken": "$admin"}""")
        start(ServerConfig.load(configFile), configFile)

        val a = store()
        val b = store()
        TrackImport.import(a, gpx("Alice walk", 46.78), System.currentTimeMillis(), "a")
        TrackImport.import(b, gpx("Bob walk", 46.80), System.currentTimeMillis(), "b")
        val route = RoutePlan("6f1c2a52-3d4e-4f60-8a7b-9c0d1e2f3a4b", "Paradise loop", RoutePath.fixed(listOf(RoutePoint(46.786, -121.736, 1647.0), RoutePoint(46.795, -121.733, 1800.0), RoutePoint(46.802, -121.729, 2100.0))).quantized())
        a.putRoutePlan(route, System.currentTimeMillis())
        assertTrue(sync(a, alice).pushed > 0)
        assertTrue(sync(b, bob).pushed > 0)

        // Each token sees its own replica and nothing of the other's.
        val aTracks = json(call("GET", "/sync/tracks", alice))
        val bTracks = json(call("GET", "/sync/tracks", bob))
        assertEquals("alice", aTracks["user"])
        assertEquals(listOf("Alice walk"), (aTracks["tracks"] as List<*>).map { (it as Map<*, *>)["name"] })
        assertEquals(listOf("Bob walk"), (bTracks["tracks"] as List<*>).map { (it as Map<*, *>)["name"] })
        val syncDir = File(dir, "out/sync")
        assertTrue(File(syncDir, "alice.db").isFile)
        assertTrue(File(syncDir, "bob.db").isFile)
        assertFalse(File(syncDir, "peer.db").isFile)
        // A fresh device with bob's token gets bob's track only.
        val c = store()
        sync(c, bob)
        assertEquals(listOf("Bob walk"), c.list().map { it.name })
        assertTrue(c.routeList().isEmpty())

        // The gate: no token, a wrong one, admin without ?user=, a user token on /admin.
        assertEquals(401, call("GET", "/sync/status").statusCode())
        assertEquals(401, call("GET", "/sync/status", "nope").statusCode())
        assertEquals(400, call("GET", "/sync/status", admin).statusCode())
        assertEquals(404, call("GET", "/sync/status?user=carol", admin).statusCode())
        assertEquals("alice", json(call("GET", "/sync/status?user=alice", admin))["user"])
        assertEquals(403, call("GET", "/admin/users", alice).statusCode())
        assertEquals(401, call("GET", "/admin/users").statusCode())
        // Users may build (past the gate: 400 for no name).
        assertEquals(400, call("POST", "/build", bob).statusCode())

        // The admin token lists users with their replicas.
        val listed = json(call("GET", "/admin/users", admin))
        val users = (listed["users"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("alice", "bob"), users.map { it["id"] })
        assertEquals(listOf(1, 1), users.map { (it["tracks"] as Number).toInt() })
        assertEquals(listOf(1, 0), users.map { (it["routes"] as Number).toInt() })
        assertNull(listed["defaultReplica"])

        // Add a user at run time: the token works at once and lands in the config file.
        val added = json(call("POST", "/admin/users?id=carol", admin))
        val carol = added["token"] as String
        assertEquals(true, added["persisted"])
        assertEquals(200, call("GET", "/sync/status", carol).statusCode())
        assertEquals(listOf("alice", "bob", "carol"), ServerConfig.load(configFile).users.map { it.id })
        assertEquals(400, call("POST", "/admin/users?id=carol", admin).statusCode())
        assertEquals(400, call("POST", "/admin/users?id=Peer!", admin).statusCode())

        // Share links: alice shares her route; the page, the GPX and the JSON are open.
        assertEquals(404, call("POST", "/sync/share?route=${route.id}", bob).statusCode()) // not in bob's replica
        val link = json(call("POST", "/sync/share?route=${route.id}", alice))
        val token = link["token"] as String
        assertEquals(false, link["existing"])
        assertTrue((link["url"] as String).endsWith("/s/$token"))
        assertEquals(token, json(call("POST", "/sync/share?route=${route.id}", alice))["token"]) // same link again
        val page = call("GET", "/s/$token")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("Paradise loop") && page.body().contains("maplibregl"))
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(null))
        val gpx = call("GET", "/s/$token.gpx")
        assertEquals(RouteGpx.gpx(a.routePlan(route.id)!!), gpx.body())
        assertTrue(gpx.headers().firstValue("Content-Disposition").orElse("").contains("Paradise loop.gpx"))
        val geo = json(call("GET", "/s/$token.json"))
        assertEquals("Paradise loop", geo["name"])
        assertEquals(1, (((geo["geojson"] as Map<*, *>)["features"] as List<*>).count { ((it as Map<*, *>)["geometry"] as Map<*, *>)["type"] == "LineString" }))
        assertEquals(1, (json(call("GET", "/sync/share", alice))["links"] as List<*>).size)
        assertEquals(0, (json(call("GET", "/sync/share", bob))["links"] as List<*>).size)
        assertEquals(1, (json(call("GET", "/healthz"))["shareLinks"] as Number).toInt())
        // Only the owner revokes; afterwards every form of the link is a 404.
        assertEquals(404, call("DELETE", "/sync/share/$token", bob).statusCode())
        assertEquals(200, call("DELETE", "/sync/share/$token", alice).statusCode())
        assertEquals(404, call("GET", "/s/$token").statusCode())
        assertEquals(404, call("GET", "/s/$token.gpx").statusCode())
        assertEquals(404, call("GET", "/s/nonexistent-token-1234").statusCode())

        // A deleted (and synced) route takes its link down too.
        val again = json(call("POST", "/sync/share?route=${route.id}", alice))["token"] as String
        assertEquals(200, call("GET", "/s/$again.gpx").statusCode())
        a.deleteRoute(route.id, System.currentTimeMillis())
        sync(a, alice)
        assertEquals(404, call("GET", "/s/$again").statusCode())

        // /healthz counts users and replicas.
        val health = json(call("GET", "/healthz"))
        assertEquals(3, (health["users"] as Number).toInt())
        assertEquals(3, (health["replicas"] as Number).toInt()) // alice, bob, carol (carol's opened by /sync/status)

        // Removing a user revokes their links and refuses their token; the replica stays.
        assertEquals(200, call("DELETE", "/admin/users/alice", admin).statusCode())
        assertEquals(401, call("GET", "/sync/status", alice).statusCode())
        assertEquals(404, call("GET", "/s/$again").statusCode())
        assertTrue(File(syncDir, "alice.db").isFile)
        assertEquals(listOf("bob", "carol"), ServerConfig.load(configFile).users.map { it.id })
    }

    @Test
    fun concurrentUsersNeverCross() {
        start(ServerConfig(users = listOf(ServerConfig.UserConfig("alice", alice), ServerConfig.UserConfig("bob", bob))))
        val pool = Executors.newFixedThreadPool(16)
        val wrong = java.util.concurrent.atomic.AtomicInteger()
        repeat(400) { i ->
            pool.execute {
                val (who, tok) = if (i % 2 == 0) "alice" to alice else "bob" to bob
                if (json(call("GET", "/sync/status", tok))["user"] != who) wrong.incrementAndGet()
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(0, wrong.get())
    }

    @Test
    fun legacyTokenKeepsTheDefaultReplica() {
        start(ServerConfig(token = "legacy", users = listOf(ServerConfig.UserConfig("alice", alice))))
        assertEquals("peer", json(call("GET", "/sync/status", "legacy"))["user"])
        assertEquals("alice", json(call("GET", "/sync/status", alice))["user"])
        assertEquals(401, call("GET", "/sync/status").statusCode())
        assertEquals(403, call("GET", "/admin/users", "legacy").statusCode())
    }

    @Test
    fun usersRegistryAndValidation() {
        val u = Users(listOf(Users.User("alice", alice)), admin, null)
        assertEquals(Users.Principal.Member("alice"), u.resolve("Bearer $alice"))
        assertEquals(Users.Principal.Member("alice"), u.resolve("bearer  $alice "))
        assertEquals(Users.Principal.Admin, u.resolve("Bearer $admin"))
        assertNull(u.resolve(null))
        assertNull(u.resolve("Bearer "))
        assertNull(u.resolve("Basic $alice"))
        assertEquals(Users.Principal.Anonymous, Users().resolve(null))
        assertFailsWith<IllegalArgumentException> { u.add("peer") }
        assertFailsWith<IllegalArgumentException> { u.add("Alice") }
        assertFailsWith<IllegalArgumentException> { u.add("alice") }
        val bobUser = u.add("bob")
        assertEquals(43, bobUser.token.length)
        assertEquals(Users.Principal.Member("bob"), u.resolve("Bearer ${bobUser.token}"))
        assertTrue(u.remove("bob"))
        assertNull(u.resolve("Bearer ${bobUser.token}"))

        assertTrue(ServerConfig(users = listOf(ServerConfig.UserConfig("alice", "short"))).validate { null }.isNotEmpty())
        assertTrue(ServerConfig(users = listOf(ServerConfig.UserConfig("alice", alice), ServerConfig.UserConfig("alice", bob))).validate { null }.isNotEmpty())
        assertTrue(ServerConfig(users = listOf(ServerConfig.UserConfig("alice", alice)), adminToken = alice).validate { null }.isNotEmpty())
        assertTrue(ServerConfig(users = listOf(ServerConfig.UserConfig("alice", alice)), adminToken = admin).validate { null }.isEmpty())
        assertEquals("env-admin-token-0123456789", ServerConfig(adminToken = admin).effectiveAdminToken { if (it == "DYSTOPO_ADMIN_TOKEN") "env-admin-token-0123456789" else null })
    }

    @Test
    fun userFlagsEditTheConfigFile() {
        val f = File(dir, "cfg/server.json")
        val (cmd, rest) = UserAdmin.parse(arrayOf("--config", f.path, "--add-user", "alice"))
        assertEquals(UserAdmin.Command.Add("alice"), cmd)
        assertEquals(listOf("--config", f.path), rest.toList())
        assertEquals(f.path, configArg(rest)?.path)
        assertFailsWith<IllegalArgumentException> { UserAdmin.parse(arrayOf("--add-user", "a", "--list-users")) }

        f.parentFile.mkdirs()
        f.writeText("""{"port": 9000}""")
        val added = UserAdmin.run(UserAdmin.Command.Add("alice"), f)
        val token = added["token"] as String
        val config = ServerConfig.load(f)
        assertEquals(9000, config.port) // other keys kept
        assertEquals(listOf(ServerConfig.UserConfig("alice", token)), config.users)
        assertFailsWith<IllegalArgumentException> { UserAdmin.run(UserAdmin.Command.Add("alice"), f) }
        assertFailsWith<IllegalArgumentException> { UserAdmin.run(UserAdmin.Command.Add("no way"), f) }
        val listed = UserAdmin.run(UserAdmin.Command.List, f)
        assertFalse(listed.toString().contains(token)) // tokens are never listed
        val adminTok = UserAdmin.run(UserAdmin.Command.NewAdminToken, f)["adminToken"] as String
        assertEquals(adminTok, ServerConfig.load(f).adminToken)
        assertEquals(true, UserAdmin.run(UserAdmin.Command.Remove("alice"), f)["found"])
        assertTrue(ServerConfig.load(f).users.isEmpty())
        assertNotNull(ServerConfig.load(f).adminToken)
    }
}
