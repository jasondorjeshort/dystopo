package io.github.jasondorjeshort.dystopo.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport
import io.github.jasondorjeshort.dystopo.sync.Sync
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T92: tokens never reach the request log (bearer headers, share-link tokens, `?token=` on the page, tokens
 * handed out by `/admin/users`), `/route*` is behind the gate once users are configured, and the config
 * file's permissions are checked.
 */
class TokenHygieneTest {
    private val dir: File = Files.createTempDirectory("tokens").toFile()
    private val logFile = File(dir, "server.log")
    private var server: Server? = null
    private val client = HttpClient.newHttpClient()

    private val alice = "alice-token-0123456789abcdef"
    private val admin = "admin-token-0123456789abcdef"
    private val legacy = "legacy-token-0123456789abcd"
    private val wrong = "wrong-token-0123456789abcdef"

    @AfterTest
    fun cleanup() {
        server?.stop("test")
        SyncRoutes.dbOverride = null
        SyncRoutes.userDir = null
        SyncRoutes.publicUrl = null
        SyncRoutes.reset()
        dir.deleteRecursively()
    }

    private fun start(config: ServerConfig, configFile: File? = null): Server {
        val out = File(dir, "out").apply { mkdirs() }
        val log = JsonLog(file = logFile, maxBytes = 8L shl 20, keep = 1)
        return Server(
            config.copy(bind = "127.0.0.1", port = 0, cacheDir = File(dir, "cache").path, outDir = out.path, fetchGlyphs = false, shutdownGraceSeconds = 1, logRequests = true),
            configFile, log,
        ).also { it.start(); server = it }
    }

    private fun call(method: String, path: String, token: String? = null, body: ByteArray? = null, json: Boolean = false): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:${server!!.port}$path"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
        token?.let { b.header("Authorization", "Bearer $it") }
        if (json) b.header("Content-Type", "application/json")
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun obj(r: HttpResponse<String>) = MiniJson.parseObject(r.body())

    @Test
    fun noTokenReachesTheLog() {
        val configFile = File(dir, "server.json")
        configFile.writeText("""{"users": [{"id": "alice", "token": "$alice"}], "adminToken": "$admin", "token": "$legacy"}""")
        start(ServerConfig.load(configFile), configFile)

        // A route in alice's replica, pushed through the real sync client, then shared.
        val store = SqlTrackStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrackDb.Schema), System::currentTimeMillis)
        val route = RoutePlan(
            "0b6f3c7e-4a1d-4e2b-9c8d-7f6e5d4c3b2a", "Paradise loop",
            RoutePath.fixed(listOf(RoutePoint(46.786, -121.736, 1647.0), RoutePoint(46.802, -121.729, 2100.0))).quantized(),
        )
        store.putRoutePlan(route, System.currentTimeMillis())
        val post = { url: String, b: ByteArray ->
            client.send(
                HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $alice").POST(HttpRequest.BodyPublishers.ofByteArray(b)).build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            ).body()
        }
        Sync.exchange(store, HttpSyncTransport("http://127.0.0.1:${server!!.port}", post, Gzip.compress, Gzip.decompress), System::currentTimeMillis, { System.nanoTime() / 1_000_000 }, 0)
        val share = obj(call("POST", "/sync/share?route=${route.id}", alice))["token"] as String
        assertEquals(200, call("GET", "/s/$share").statusCode())
        assertEquals(200, call("GET", "/s/$share.gpx").statusCode())
        assertEquals(200, call("GET", "/sync/share", alice).statusCode())
        // Every kind of token on the wire: user, admin (with ?user=), legacy, a wrong one, a page opened with ?token=.
        assertEquals(200, call("GET", "/sync/status", alice).statusCode())
        assertEquals(200, call("GET", "/sync/status?user=alice", admin).statusCode())
        assertEquals(200, call("GET", "/sync/status", legacy).statusCode())
        assertEquals(401, call("GET", "/sync/status", wrong).statusCode())
        call("GET", "/?token=$alice")
        call("GET", "/tools/demo/?token=$admin&units=m")
        val added = obj(call("POST", "/admin/users?id=carol", admin))["token"] as String
        assertEquals(200, call("GET", "/sync/status", added).statusCode())
        assertEquals(200, call("DELETE", "/sync/share/$share", alice).statusCode())
        assertEquals(404, call("GET", "/s/$share.json").statusCode())
        server!!.stop("test")
        server = null

        val text = logFile.readText()
        assertTrue(text.contains("\"path\":\"/sync/status\""), "requests are logged at all")
        assertTrue(text.contains("/s/…") && text.contains("/sync/share/…"), "share paths are logged redacted")
        for ((what, secret) in listOf("user" to alice, "admin" to admin, "legacy" to legacy, "share link" to share, "added user" to added, "wrong" to wrong)) {
            assertFalse(text.contains(secret), "the $what token appears in the request log")
        }
        // The page's ?token= is not logged either (the log has the path, never the query).
        assertFalse(text.contains("token="), "a query string reached the log")
    }

    @Test
    fun routePlanningNeedsATokenOnceUsersExist() {
        start(ServerConfig(users = listOf(ServerConfig.UserConfig("alice", alice)), adminToken = admin))
        for (p in listOf("/route?from=46.786,-121.736&to=46.802,-121.729", "/route/packs", "/route/status")) {
            assertEquals(401, call("GET", p).statusCode(), p)
            assertEquals(401, call("GET", p, wrong).statusCode(), p)
            assertNotEquals(401, call("GET", p, alice).statusCode(), p)
            assertNotEquals(401, call("GET", p, admin).statusCode(), p)
        }
        for (p in listOf("/route/edit", "/route/bundle", "/route/gpx")) {
            assertEquals(401, call("POST", p, body = "{}".toByteArray(), json = true).statusCode(), p)
        }
        // Not the page, the tiles or the trip editor's script.
        assertNotEquals(401, call("GET", "/trips.js").statusCode())
        assertNotEquals(401, call("GET", "/routes").statusCode())
        assertNotEquals(401, call("GET", "/healthz").statusCode())
    }

    @Test
    fun routePlanningStaysOpenWithoutUsers() {
        start(ServerConfig(token = legacy))
        assertNotEquals(401, call("GET", "/route/status").statusCode())
        assertEquals(401, call("GET", "/sync/status").statusCode()) // the T52 token still guards sync
        server!!.stop("test")
        server = null
        start(ServerConfig())
        assertNotEquals(401, call("GET", "/route/packs").statusCode())
    }

    @Test
    fun configReadableByOthersIsReported() {
        val f = File(dir, "server.json").apply { writeText("""{"adminToken": "$admin"}""") }
        val posix = Files.getFileAttributeView(f.toPath(), PosixFileAttributeView::class.java)
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-r--r--"))
            val p = ConfigPermissions.problems(f)
            assertEquals(1, p.size, p.toString())
            assertTrue("every user" in p[0] && "chmod 600" in p[0], p[0])
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"))
            assertEquals(emptyList(), ConfigPermissions.problems(f))
            // UserAdmin writes the file owner-only.
            posix.setPermissions(PosixFilePermissions.fromString("rw-rw-rw-"))
            UserAdmin.edit(f) { UserAdmin.addUser(it, "dave", "dave-token-0123456789abcdef") }
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
            return
        }
        val acl = Files.getFileAttributeView(f.toPath(), AclFileAttributeView::class.java) ?: return
        // Windows: granting Everyone read is the problem case, whatever the temp directory's inherited entries say.
        val everyone = f.toPath().fileSystem.userPrincipalLookupService.lookupPrincipalByName("Everyone")
        acl.acl = acl.acl + AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
            .setPermissions(AclEntryPermission.READ_DATA).build()
        val after = ConfigPermissions.problems(f)
        assertTrue(after.any { "Everyone" in it && "icacls" in it }, after.toString())
    }

    @Test
    fun aConfigWithoutTokensNeedsNoCheck() {
        assertFalse(ConfigPermissions.holdsTokens(ServerConfig()))
        assertTrue(ConfigPermissions.holdsTokens(ServerConfig(token = legacy)))
        assertTrue(ConfigPermissions.holdsTokens(ServerConfig(users = listOf(ServerConfig.UserConfig("alice", alice)))))
    }
}
