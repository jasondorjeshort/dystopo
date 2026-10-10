package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.server.DemPmtilesWriter
import io.github.jasondorjeshort.dystopo.server.PackCatalog
import io.github.jasondorjeshort.dystopo.server.TileSetMetadata
import io.github.jasondorjeshort.dystopo.server.TileSets
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {
    private val dir: File = Files.createTempDirectory("server").toFile()
    private val logBytes = ByteArrayOutputStream()
    private val log = JsonLog(PrintStream(logBytes, true, Charsets.UTF_8))
    private var server: Server? = null

    @AfterTest
    fun cleanup() {
        server?.stop("test")
        dir.deleteRecursively()
    }

    @Test
    fun configDefaultsAndUnknownKeys() {
        val c = ServerConfig.parse("{}")
        assertEquals(8080, c.port)
        assertEquals(listOf("*"), c.corsOrigins)
        assertNull(c.effectiveToken { null })
        assertEquals("s3cret", c.copy(token = "s3cret").effectiveToken { null })
        assertEquals("env", c.copy(token = "file").effectiveToken { if (it == "DYSTOPO_SERVER_TOKEN") "env" else null })
        assertEquals(2, ServerConfig.parse("""{"build": {"concurrent": 2}}""").build.concurrent)
        assertFailsWith<SerializationException> { ServerConfig.parse("""{"prot": 1}""") }
        assertTrue(ServerConfig(port = 70000).validate().isNotEmpty())
        assertTrue(ServerConfig(corsOrigins = listOf("https://a.example/path")).validate().isNotEmpty())
        assertTrue(ServerConfig(publicUrl = "https://maps.example.org").validate().isEmpty())
        // The example file in the distribution is the defaults.
        val example = File("server.example.json").takeIf { it.isFile } ?: File("server/server.example.json")
        assertEquals(ServerConfig(), ServerConfig.load(example))
    }

    @Test
    fun gateRules() {
        val open = Gate(null, listOf("*"), log, false)
        assertTrue(open.authorized(null))
        assertEquals("*", open.allowOrigin("https://x.example"))
        val closed = Gate("tok", listOf("https://maps.example.org"), log, false)
        assertFalse(closed.authorized(null))
        assertFalse(closed.authorized("Bearer nope"))
        assertTrue(closed.authorized("Bearer tok"))
        assertTrue(closed.authorized("bearer tok"))
        assertEquals("https://maps.example.org", closed.allowOrigin("https://maps.example.org"))
        assertNull(closed.allowOrigin("https://evil.example"))
        assertTrue(Gate.isProtected("/build"))
        assertTrue(Gate.isProtected("/build/b1"))
        assertTrue(Gate.isProtected("/sync/pull"))
        assertFalse(Gate.isProtected("/buildings"))
        assertFalse(Gate.isProtected("/catalog"))
    }

    @Test
    fun argsAndConfigFile() {
        val f = File(dir, "s.json").apply { writeText("{}") }
        assertEquals(f.path to false, parseArgs(arrayOf("--config", f.path), { null }, null).let { it.first?.path to it.second })
        assertEquals(f.path, parseArgs(emptyArray(), { if (it == "DYSTOPO_SERVER_CONFIG") f.path else null }, null).first?.path)
        assertNull(parseArgs(emptyArray(), { null }, null).first)
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--nope"), { null }, null) }
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--config", File(dir, "missing.json").path), { null }, null) }
    }

    private fun demPack(out: File, name: String) {
        val box = BoundingBox(-121.9, 46.7, -121.6, 46.95)
        DemPmtilesWriter(File(out, "$name-dem.pmtiles")).use { w ->
            w.add(TileId(8, 42, 90), ByteArray(64) { it.toByte() })
            w.finish(
                TileSetMetadata(
                    name = "$name-dem", tileSize = 256, bounds = TileSets.geoJsonBounds(box), minzoom = 8, maxzoom = 8,
                    source = "best", attribution = "test", attributionHtml = "test", provenance = listOf("copernicus"),
                ),
                box, 8, 8,
            )
        }
    }

    private val client = HttpClient.newHttpClient()

    private fun call(method: String, path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:${server!!.port}$path")).method(method, HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun servesHealthCatalogAndGuardsBuildAndSync() {
        val out = File(dir, "out").apply { mkdirs() }
        demPack(out, "testpack")
        val config = ServerConfig(
            bind = "127.0.0.1", port = 0, cacheDir = File(dir, "cache").path, outDir = out.path, fetchGlyphs = false,
            token = "s3cret", corsOrigins = listOf("https://maps.example.org"), shutdownGraceSeconds = 1,
        )
        server = Server(config, null, log).also { it.start() }

        val health = call("GET", "/healthz")
        assertEquals(200, health.statusCode())
        assertEquals("ok", MiniJson.parseObject(health.body())["status"])

        val catalog = MiniJson.parseObject(call("GET", "/catalog").body())
        val pack = (catalog["packs"] as List<*>).single() as Map<*, *>
        assertEquals("testpack", pack["id"])
        val file = ((pack["files"] as List<*>).single() as Map<*, *>)
        assertEquals(PackCatalog.hashFile(File(out, "testpack-dem.pmtiles")), file["sha256"])

        // The token guards /build and /sync, nothing else.
        assertEquals(401, call("POST", "/build?name=x&bbox=1,1,1.01,1.01").statusCode())
        assertEquals(401, call("GET", "/sync/status").statusCode())
        assertEquals(401, call("POST", "/build", mapOf("Authorization" to "Bearer wrong")).statusCode())
        // Past the gate: the build route itself answers (400: no name).
        assertEquals(400, call("POST", "/build", mapOf("Authorization" to "Bearer s3cret")).statusCode())
        assertEquals(200, call("GET", "/build", mapOf("Authorization" to "Bearer s3cret")).statusCode())

        // CORS by allowlist: an allowed origin is echoed, another gets no header.
        val allowed = call("GET", "/catalog", mapOf("Origin" to "https://maps.example.org"))
        assertEquals("https://maps.example.org", allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        val other = call("GET", "/catalog", mapOf("Origin" to "https://evil.example"))
        assertFalse(other.headers().firstValue("Access-Control-Allow-Origin").isPresent)
        // Preflight for a token-carrying POST is answered without the token.
        val pre = call("OPTIONS", "/build", mapOf("Origin" to "https://maps.example.org", "Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "authorization"))
        assertEquals(204, pre.statusCode())
        assertTrue(pre.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("Authorization"))

        // The style's absolute URLs follow the request (a reverse proxy's forwarded headers).
        val style = call("GET", "/style.json?units=m", mapOf("X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "maps.example.org")).body()
        assertTrue(style.contains("https://maps.example.org/dem/{z}/{x}/{y}.png"), style.take(400))

        server!!.stop("test")
        val lines = logBytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { MiniJson.parseObject(it) }
        assertTrue(lines.all { it["ts"] != null && it["level"] != null && it["event"] != null })
        assertEquals(listOf("started"), lines.filter { it["event"] == "started" }.map { it["event"] })
        assertTrue(lines.any { it["event"] == "request" && it["path"] == "/build" && (it["status"] as Number).toInt() == 401 && it["level"] == "warn" })
        assertTrue(lines.any { it["event"] == "stopped" })
    }
}
