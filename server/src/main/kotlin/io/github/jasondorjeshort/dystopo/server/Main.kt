package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpServer
import io.github.jasondorjeshort.dystopo.server.BuildRoutes
import io.github.jasondorjeshort.dystopo.server.Caches
import io.github.jasondorjeshort.dystopo.server.LiveTiles
import io.github.jasondorjeshort.dystopo.server.OverpassSource
import io.github.jasondorjeshort.dystopo.server.SyncRoutes
import io.github.jasondorjeshort.dystopo.server.TilebuildProcess
import io.github.jasondorjeshort.dystopo.server.Extracts
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The standalone Dystopo server (T52): the live routes of `devtool serve --live` ([LiveTiles]: packs
 * first, else live DEM and OpenFreeMap; `/build`; `/out/`; the `/sync` peer; `/catalog`; attribution)
 * behind a config file ([ServerConfig]), with JSON logs, `/healthz`, a bearer token on `/build` and
 * `/sync`, a CORS allowlist ([Gate]) and a graceful shutdown on SIGTERM. Usage:
 *
 *     server [--config server.json] [--print-config]
 *
 * The config file is `--config`, else `DYSTOPO_SERVER_CONFIG`, else `config/server.json` in the
 * distribution when present, else the defaults. docs/SERVER.md has the details.
 */
fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
    val log = JsonLog()
    val (configFile, printConfig) = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println("usage: server [--config <file.json>] [--print-config]")
        exitProcess(2)
    }
    val config = try {
        configFile?.let { ServerConfig.load(it) } ?: ServerConfig()
    } catch (e: Exception) {
        val why = if (e is SerializationException || e is IllegalArgumentException) e.message?.lineSequence()?.first() else e.toString()
        log.error("config_invalid", mapOf("file" to configFile?.path, "error" to why))
        exitProcess(2)
    }
    if (printConfig) {
        println(ServerConfig.json.encodeToString(ServerConfig.serializer(), config.copy(token = config.token?.let { "<set>" })))
        return
    }
    config.validate().takeIf { it.isNotEmpty() }?.let {
        log.error("config_invalid", mapOf("file" to configFile?.path, "errors" to it))
        exitProcess(2)
    }
    val server = try {
        Server(config, configFile, log).also { it.start() }
    } catch (e: java.net.BindException) {
        log.error("bind_failed", mapOf("bind" to config.bind, "port" to config.port, "error" to e.message))
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread({ server.stop("signal") }, "shutdown"))
    server.awaitStopped()
}

/** `--config <file>` and `--print-config`; the config file falls back as described on [main]. */
fun parseArgs(args: Array<String>, env: (String) -> String? = System::getenv, home: File? = Server.appHome()): Pair<File?, Boolean> {
    var config: File? = null
    var print = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--config", "-c" -> config = File(args.getOrNull(++i) ?: throw IllegalArgumentException("--config needs a file"))
            "--print-config" -> print = true
            else -> if (a.startsWith("--config=")) config = File(a.removePrefix("--config=")) else throw IllegalArgumentException("unknown argument '$a'")
        }
        i++
    }
    if (config == null) config = env("DYSTOPO_SERVER_CONFIG")?.takeIf { it.isNotBlank() }?.let { File(it) }
    if (config == null) config = home?.let { File(it, "config/server.json") }?.takeIf { it.isFile }
    if (config != null && !config.isFile) throw IllegalArgumentException("config file ${config.path} not found")
    return config to print
}

/** One running server: the HTTP listener, its routes and its shutdown. */
class Server(private val config: ServerConfig, private val configFile: File?, private val log: JsonLog) {
    private val startedAt = System.currentTimeMillis()
    private val stopped = CountDownLatch(1)
    @Volatile private var stopping = false
    private lateinit var http: HttpServer
    private lateinit var executor: ExecutorService
    lateinit var live: LiveTiles
        private set

    val cacheDir = File(config.cacheDir)
    val outDir = File(config.outDir)
    val glyphDir = config.glyphsDir?.let { File(it) } ?: File(cacheDir, "glyphs")
    val webPage: File = config.webPage?.let { File(it) }
        ?: listOfNotNull(appHome()?.let { File(it, "web/index.html") }, File("tools/demo/index.html")).firstOrNull { it.isFile }
        ?: File("web/index.html")

    fun start() {
        // Before anything makes a source: they take their cache directories from Caches.root.
        Caches.root = cacheDir
        cacheDir.mkdirs()
        outDir.mkdirs()
        SyncRoutes.dbOverride = config.syncDb?.let { File(it) } ?: File(outDir, "sync/peer.db")
        val (dems, osms) = LiveTiles.findPacks(emptyList(), outDir)
        val tilebuild = config.tilebuildHome?.let { TilebuildProcess(home = File(it)) } ?: TilebuildProcess()
        live = LiveTiles(
            dems, osms, cacheRoot = cacheDir, outDir = outDir,
            threads = config.liveThreads ?: Runtime.getRuntime().availableProcessors().coerceIn(4, 16),
            makeBuilds = { lt ->
                BuildRoutes(
                    lt, outDir,
                    overpass = OverpassSource(endpoint = config.overpass.url, maxAreaSqDeg = config.overpass.maxAreaSqDeg, timeoutSeconds = config.overpass.timeoutSeconds),
                    tilebuild = tilebuild,
                    maxTiles = config.build.maxTiles, maxSpanDegrees = config.build.maxSpanDegrees,
                    concurrentBuilds = config.build.concurrent, maxQueued = config.build.queue,
                    extracts = Extracts(
                        config.extracts.map { e -> Extracts.Configured(File(e.path), e.bbox?.let { BoundingBox(it[0], it[1], it[2], it[3]) }, e.name) },
                        when (config.extractsDir) { null -> File(cacheDir, Extracts.DIR_NAME); "" -> null; else -> File(config.extractsDir) },
                    ),
                )
            },
        )
        val token = config.effectiveToken()
        val gate = Gate(token, config.corsOrigins, log, config.logRequests)
        val fallbackBase = config.publicUrl?.trimEnd('/') ?: "http://127.0.0.1:${config.port}"
        val base: (com.sun.net.httpserver.HttpExchange) -> String =
            if (config.publicUrl != null) { _ -> fallbackBase } else { ex -> LiveTiles.requestBase(ex, fallbackBase) }

        http = HttpServer.create(InetSocketAddress(config.bind, config.port), 256)
        executor = Executors.newFixedThreadPool(config.threads) { r -> Thread(r, "http").apply { isDaemon = true } }
        http.executor = executor
        http.createContext("/", live.handler(glyphDir, webPage, base = base)).filters += gate
        http.createContext("/healthz") { ex ->
            val body = (JsonWriter.write(health()) + "\n").toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.responseHeaders.add("Cache-Control", "no-store")
            ex.sendResponseHeaders(if (stopping) 503 else 200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }.filters += gate
        http.start()

        val stale = live.pruneStaleDemCaches()
        live.catalog.warm()
        if (config.fetchGlyphs) Thread({ fetchGlyphs() }, "glyph-fetch").apply { isDaemon = true }.start()
        log.info(
            "started",
            linkedMapOf(
                "bind" to config.bind, "port" to config.port, "publicUrl" to config.publicUrl,
                "config" to configFile?.path, "cacheDir" to cacheDir.absolutePath, "outDir" to outDir.absolutePath,
                "glyphsDir" to glyphDir.path, "webPage" to webPage.path.takeIf { webPage.isFile },
                "syncDb" to SyncRoutes.dbOverride?.path,
                "demPacks" to dems.map { it.name }, "osmPacks" to osms.map { it.name },
                "token" to (token != null), "corsOrigins" to config.corsOrigins,
                "tilebuild" to (tilebuild.unavailable() == null),
                "staleCachesDeleting" to stale.map { it.name },
            ),
        )
    }

    /** `GET /healthz`: 200 while serving, 503 once shutting down. */
    fun health(): Map<String, Any?> = linkedMapOf(
        "status" to if (stopping) "stopping" else "ok",
        "uptimeS" to (System.currentTimeMillis() - startedAt) / 1000,
        "packs" to (live.info()["demPacks"] as List<*>).size,
        "outDirWritable" to outDir.canWrite(),
        "cacheDirWritable" to cacheDir.canWrite(),
        "glyphs" to MapLibreStyle.FONTS.count { File(glyphDir, "$it/0-255.pbf").isFile },
        "errors" to live.stats()["errors"],
        // T58: the startup hash pass (cold, warming, ready); /catalog hashes on demand until it is ready.
        "catalog" to live.catalog.warmState(),
    )

    /** Stops accepting requests, lets those in flight finish (up to the grace period), cancels builds. */
    fun stop(reason: String) = synchronized(this) {
        if (stopping) return
        stopping = true
        val started = System.nanoTime()
        log.info("stopping", mapOf("reason" to reason, "graceSeconds" to config.shutdownGraceSeconds))
        val grace = config.shutdownGraceSeconds
        val cancelled = live.cancelBuilds(grace * 1000L / 2)
        http.stop(maxOf(0, grace / 2))
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)
        log.info("stopped", mapOf("reason" to reason, "buildsCancelled" to cancelled, "ms" to (System.nanoTime() - started) / 1_000_000))
        stopped.countDown()
    }

    fun awaitStopped() = stopped.await()

    /** The port actually bound (differs from the config's when that is 0). */
    val port: Int get() = http.address.port

    /** Downloads the glyph ranges the style uses (as tools/android-push-pack.ps1 does) when missing. */
    private fun fetchGlyphs() {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
        var fetched = 0
        val failed = ArrayList<String>()
        for (font in MapLibreStyle.FONTS) for (range in GLYPH_RANGES) {
            val file = File(glyphDir, "$font/$range.pbf")
            if (file.isFile) continue
            val url = config.glyphsUrl.trimEnd('/') + "/" + URLEncoder.encode(font, StandardCharsets.UTF_8).replace("+", "%20") + "/$range.pbf"
            try {
                val r = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofByteArray())
                if (r.statusCode() != 200) throw IllegalStateException("HTTP ${r.statusCode()}")
                file.parentFile.mkdirs()
                val tmp = File(file.path + ".part")
                tmp.writeBytes(r.body())
                if (!tmp.renameTo(file)) tmp.delete()
                fetched++
            } catch (e: Exception) {
                failed += "$font/$range: ${e.message}"
            }
        }
        if (fetched > 0 || failed.isNotEmpty()) {
            val fields = mapOf("dir" to glyphDir.path, "fetched" to fetched, "failed" to failed)
            if (failed.isEmpty()) log.info("glyphs_fetched", fields) else log.warn("glyphs_fetched", fields)
        }
    }

    companion object {
        /** The ranges the style's text needs (Basic Latin, Latin-1, Latin Extended-A/B, punctuation). */
        val GLYPH_RANGES = listOf("0-255", "256-511", "8192-8447")

        /** The installed distribution's directory (the parent of `lib/`), when running from one. */
        fun appHome(): File? = runCatching {
            val jar = File(Server::class.java.protectionDomain.codeSource.location.toURI())
            jar.parentFile?.takeIf { it.name == "lib" }?.parentFile
        }.getOrNull()
    }
}
