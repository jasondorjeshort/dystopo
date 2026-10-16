package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T82: the log cap, `/metrics` and the request body cap. */
class OperationsTest {
    private val dir: File = Files.createTempDirectory("ops").toFile()
    private var server: Server? = null

    @AfterTest
    fun cleanup() {
        server?.stop("test")
        dir.deleteRecursively()
    }

    @Test
    fun logRotatesAndKeepsAtMostKeepFiles() {
        val f = File(dir, "logs/server.log")
        val log = JsonLog(file = f, maxBytes = 2_000, keep = 2)
        repeat(200) { log.info("request", mapOf("path" to "/dem/12/655/$it.png", "status" to 200)) }
        log.close()
        val files = f.parentFile.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("server.log", "server.log.1", "server.log.2"), files)
        assertTrue(files.all { File(f.parentFile, it).length() <= 2_000 }, files.toString())
        assertTrue(log.rotations.get() >= 5)
        assertEquals(200, log.lines.get())
        // Every kept line is whole JSON.
        f.readLines().forEach { MiniJson.parseObject(it) }
        // keep = 0: one file, truncated at each rotation.
        val g = File(dir, "solo.log")
        val solo = JsonLog(file = g, maxBytes = 500, keep = 0)
        repeat(50) { solo.info("x", mapOf("i" to it)) }
        solo.close()
        assertEquals(listOf("solo.log"), dir.listFiles()!!.filter { it.name.startsWith("solo") }.map { it.name })
    }

    @Test
    fun longFieldsAreCut() {
        val bytes = ByteArrayOutputStream()
        val log = JsonLog(PrintStream(bytes, true, Charsets.UTF_8))
        log.warn("request", mapOf("path" to "/" + "a".repeat(20_000), "list" to List(500) { it }))
        val line = bytes.toString(Charsets.UTF_8).trim()
        assertTrue(line.length < 2_000, "line is ${line.length} chars")
        val m = MiniJson.parseObject(line)
        assertTrue((m["path"] as String).endsWith("...(20001 chars)"))
        assertEquals(2L, log.truncated.get())
    }

    @Test
    fun metricsHistogramAndGroups() {
        assertEquals("dem", Metrics.group("/dem/12/1/2.png"))
        assertEquals("relay", Metrics.group("/sync/relay"))
        assertEquals("sync", Metrics.group("/sync/pull"))
        assertEquals("sprite", Metrics.group("/sprite@2x.png"))
        assertEquals("other", Metrics.group("/wp-admin/x.php")) // a scan of random paths does not grow the map
        assertEquals("page", Metrics.group("/"))
        val r = Metrics.Route()
        repeat(90) { r.add(200, 3.0, 100) }
        repeat(10) { r.add(500, 700.0, 10) }
        assertEquals(5.0, r.quantile(0.5))
        assertEquals(1000.0, r.quantile(0.95))
        val j = r.json()
        assertEquals(100L, j["count"])
        assertEquals(10L, (j["status"] as Map<*, *>)["5xx"])
        assertEquals(700.0, j["maxMs"])

        File(dir, "cache/live/12/1").mkdirs()
        File(dir, "cache/live/12/1/2.png").writeBytes(ByteArray(1000))
        File(dir, "cache/openfreemap/a.pbf").apply { parentFile.mkdirs(); writeBytes(ByteArray(500)) }
        File(dir, "out").mkdirs()
        val m = Metrics(File(dir, "cache"), File(dir, "out"), diskScanMinutes = 0)
        m.request("/dem/1/0/0.png", 200, 4.0, 10)
        m.scanDisk()
        val json = m.json()
        val cache = (json["disk"] as Map<*, *>)["cacheDir"] as Map<*, *>
        assertEquals(1500L, cache["bytes"])
        assertEquals(2L, cache["files"])
        assertEquals(setOf("live/", "openfreemap/"), (cache["children"] as Map<*, *>).keys)
        assertNotNull((json["jvm"] as Map<*, *>)["heapUsedBytes"])
        val prom = m.prometheus(mapOf("log_lines_total" to 3))
        assertTrue(prom.contains("dystopo_requests_total{route=\"dem\",status=\"2xx\"} 1"), prom)
        assertTrue(prom.contains("dystopo_request_duration_ms_bucket{route=\"dem\",le=\"+Inf\"} 1"), prom)
        assertTrue(prom.contains("dystopo_disk_cache_bytes 1500"), prom)
        assertTrue(prom.contains("dystopo_log_lines_total 3"), prom)
    }

    @Test
    fun metricsRouteAndBodyCap() {
        val out = File(dir, "out").apply { mkdirs() }
        val logFile = File(dir, "logs/server.log")
        val config = ServerConfig(
            bind = "127.0.0.1", port = 0, cacheDir = File(dir, "cache").path, outDir = out.path, fetchGlyphs = false,
            shutdownGraceSeconds = 1, log = ServerConfig.LogConfig(file = logFile.path),
        )
        val log = JsonLog(file = logFile, maxBytes = 1 shl 20, keep = 1)
        server = Server(config, null, log).also { it.start() }
        val port = server!!.port
        val client = HttpClient.newHttpClient()
        fun get(path: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, get("/healthz").statusCode())
        assertEquals(404, get("/nope").statusCode())
        val m = MiniJson.parseObject(get("/metrics").body())
        val req = m["requests"] as Map<*, *>
        assertEquals(1L, ((req["healthz"] as Map<*, *>)["count"] as Number).toLong())
        assertEquals(1L, (((req["other"] as Map<*, *>)["status"] as Map<*, *>)["4xx"] as Number).toLong())
        assertTrue(((m["log"] as Map<*, *>)["lines"] as Number).toLong() >= 3)
        val prom = get("/metrics?format=prometheus")
        assertTrue(prom.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))
        assertTrue(prom.body().contains("dystopo_jvm_heap_used_bytes"))

        // A declared body over the cap: 413 before the route reads anything.
        val declared = rawStatus(port, "POST /route/edit HTTP/1.1\r\nHost: x\r\nContent-Length: ${Gate.MAX_BODY + 1}\r\n\r\n")
        assertEquals(413, declared)
        // A chunked body that never ends: cut at the cap while the route reads it, answered 413.
        val chunked = Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 20_000
            val o = s.getOutputStream()
            o.write("POST /route/edit HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            val block = ByteArray(1 shl 16)
            val head = "10000\r\n".toByteArray()
            try {
                repeat(((Gate.MAX_BODY shr 16) + 8).toInt()) { o.write(head); o.write(block); o.write("\r\n".toByteArray()) }
                o.write("0\r\n\r\n".toByteArray())
            } catch (e: java.io.IOException) {
                // cut off: read the answer
            }
            // The server answers 413 and closes; on Windows the close can reset the connection before the
            // client reads the answer (it was still sending). Either way the log says tooLarge (below).
            try { statusOf(s) } catch (e: java.net.SocketException) { -2 }
        }
        assertTrue(chunked == 413 || chunked == -2, "chunked: $chunked")
        assertEquals(200, get("/healthz").statusCode())
        // Share-link tokens never reach the log (the link is the secret).
        assertEquals(404, get("/s/secretsharetoken0123456789.gpx").statusCode())
        val del = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/sync/share/secretsharetoken0123456789")).DELETE().build(), HttpResponse.BodyHandlers.ofString())
        assertTrue(del.statusCode() in 400..499, "delete: ${del.statusCode()}")
        assertEquals("/s/…", Gate.logPath("/s/abc.json"))
        assertEquals("/sync/share", Gate.logPath("/sync/share"))
        server!!.stop("test")
        val logged = logFile.readText()
        assertTrue("secretsharetoken" !in logged, "a share token reached the log")
        assertTrue(logged.contains("\"path\":\"/s/…\"") && logged.contains("\"path\":\"/sync/share/…\""))
        val lines = logFile.readLines()
        assertEquals(2, lines.count { it.contains("/route/edit") && it.contains("\"status\":413") }, lines.filter { it.contains("route/edit") }.joinToString(" | "))
    }

    /**
     * T111: a request body still arriving at `http.maxRequestSeconds` is answered 408 and counted as a 4xx, not left
     * unanswered (status `none` in /metrics). The soak's hourly probe sends exactly that (a short body to
     * `/route/edit`); before, the JDK listener's timer closed the connection without an answer.
     */
    @Test
    fun aBodyStillArrivingAtTheLimitIsAnswered408() {
        // The listener's own timer is JVM-wide and fixed by the first listener: keep it at the default config's value
        // (60 + grace) so this test's 2-second limit does not leak into other tests; the gate's limit is per server.
        if (System.getProperty("sun.net.httpserver.maxReqTime") == null) System.setProperty("sun.net.httpserver.maxReqTime", (60 + Gate.TIMEOUT_GRACE_S).toString())
        val logFile = File(dir, "logs/server.log")
        val config = ServerConfig(
            bind = "127.0.0.1", port = 0, cacheDir = File(dir, "cache").path, outDir = File(dir, "out").apply { mkdirs() }.path, fetchGlyphs = false,
            shutdownGraceSeconds = 1, log = ServerConfig.LogConfig(file = logFile.path), http = ServerConfig.HttpConfig(maxRequestSeconds = 2),
        )
        val log = JsonLog(file = logFile, maxBytes = 1 shl 20, keep = 1)
        server = Server(config, null, log).also { it.start() }
        val port = server!!.port
        fun timed(block: () -> Int): Pair<Int, Long> { val t0 = System.nanoTime(); val s = block(); return s to (System.nanoTime() - t0) / 1_000_000 }

        // The probe's case: 50 bytes declared, 6 sent, then nothing.
        val (short, shortMs) = timed { rawStatus(port, "POST /route/edit HTTP/1.1\r\nHost: x\r\nContent-Length: 50\r\n\r\n{\"op\":") }
        assertEquals(408, short)
        assertTrue(shortMs in 1_500..6_000, "408 after $shortMs ms")
        // A chunked body that stops after its first chunk.
        val (chunk, _) = timed { rawStatus(port, "POST /route/edit HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n6\r\n{\"op\":\r\n") }
        assertEquals(408, chunk)
        // A slow body that arrives within the limit is answered by the route (a 400: no path), not cut.
        val slow = Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val body = "{\"op\":\"show\"}"
            s.getOutputStream().write("POST /route/edit HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.length}\r\n\r\n${body.take(5)}".toByteArray())
            Thread.sleep(1_000)
            s.getOutputStream().write(body.drop(5).toByteArray())
            statusOf(s)
        }
        assertEquals(400, slow)
        // A request without a body is never timed: /healthz answers at once.
        val client = HttpClient.newHttpClient()
        assertEquals(200, client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/healthz")).build(), HttpResponse.BodyHandlers.ofString()).statusCode())

        // Counted as 4xx answers of the route group, none unanswered (the gate records the two timed-out requests
        // once the route's thread gives up on the body, which is when the client closes).
        var route: Map<*, *>? = null
        var m: Map<String, Any?> = emptyMap()
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            m = MiniJson.parseObject(client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/metrics")).build(), HttpResponse.BodyHandlers.ofString()).body())
            route = (m["requests"] as Map<*, *>)["route"] as Map<*, *>?
            if (route != null && (route["count"] as Number).toLong() >= 3) break
            Thread.sleep(200)
        }
        val status = route!!["status"] as Map<*, *>
        assertEquals(3L, (status["4xx"] as Number).toLong(), route.toString())
        assertEquals(0L, (status["none"] as Number).toLong(), route.toString())
        assertEquals(2L, (m["requestTimeouts"] as Number).toLong())
        server!!.stop("test")
        val timedOut = logFile.readLines().filter { it.contains("\"outcome\":\"requestTimeout\"") }
        assertEquals(2, timedOut.size, timedOut.joinToString(" | "))
        assertTrue(timedOut.all { it.contains("\"status\":408") && it.contains("\"level\":\"warn\"") }, timedOut.joinToString(" | "))
    }

    private fun rawStatus(port: Int, request: String): Int = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().write(request.toByteArray())
        statusOf(s)
    }

    private fun statusOf(s: Socket): Int {
        val line = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: return -1
        return line.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
    }
}
