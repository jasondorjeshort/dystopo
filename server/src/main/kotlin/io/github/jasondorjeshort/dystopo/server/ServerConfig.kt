package io.github.jasondorjeshort.dystopo.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The server's config file (JSON; every key optional, unknown keys are an error so a typo does not pass
 * silently). docs/SERVER.md documents each key; `server/server.example.json` lists them with their
 * defaults. Relative paths resolve against the working directory.
 */
@Serializable
data class ServerConfig(
    /** Address to listen on: `0.0.0.0` for every interface (a container), `127.0.0.1` for local only. */
    val bind: String = "0.0.0.0",
    val port: Int = 8080,
    /**
     * The URL clients reach the server at (`https://maps.example.org`), used for the absolute tile URLs in
     * `/style.json`. Null: taken from each request (`X-Forwarded-Proto`/`X-Forwarded-Host`, else `Host`).
     */
    val publicUrl: String? = null,
    /** Downloaded and generated data (live DEM tiles, OpenFreeMap tiles, Copernicus/3DEP files, Overpass extracts, glyphs). */
    val cacheDir: String = "cache",
    /** Region packs: served as packs and under `/out/`, listed by `/catalog`, written by `/build`. */
    val outDir: String = "out",
    /** Glyph PBFs (`<font>/<range>.pbf`); null: `<cacheDir>/glyphs`. */
    val glyphsDir: String? = null,
    /** Download the style's glyph ranges into [glyphsDir] at startup when missing (from [glyphsUrl]). */
    val fetchGlyphs: Boolean = true,
    val glyphsUrl: String = "https://protomaps.github.io/basemaps-assets/fonts/",
    /** The browser page served at `/`; null: `web/index.html` in the distribution, else `tools/demo/index.html`. */
    val webPage: String? = null,
    /** The sync peer's replica (SQLite); null: `<outDir>/sync/peer.db`. */
    val syncDb: String? = null,
    /**
     * Origins allowed to call the server from a browser (`Access-Control-Allow-Origin`). `["*"]` (the
     * default) allows any; an empty list allows none (same-origin pages and non-browser clients still work).
     */
    val corsOrigins: List<String> = listOf("*"),
    /**
     * Bearer token required on `/build` and `/sync` (`Authorization: Bearer <token>`). Null: open. The
     * environment variable `DYSTOPO_SERVER_TOKEN` overrides it (keeps the secret out of the file).
     */
    val token: String? = null,
    /**
     * T67: the people this server syncs for: `[{"id": "alice", "token": "<43 random characters>"}]`. Each
     * user's `/sync*` runs on their own replica (`<syncDb's directory>/<id>.db`); `server --add-user <id>`
     * (or `POST /admin/users?id=`) makes the token. Users may also call `/build`.
     */
    val users: List<UserConfig> = emptyList(),
    /**
     * T67: the admin token: `/admin/users` (list, add, remove) and any user's `/sync*` with `?user=<id>`.
     * The environment variable `DYSTOPO_ADMIN_TOKEN` overrides it.
     */
    val adminToken: String? = null,
    /** HTTP worker threads. */
    val threads: Int = 32,
    /** Threads rendering live DEM tiles; null: the CPU count, clamped to 4..16. */
    val liveThreads: Int? = null,
    /** Seconds a shutdown (SIGTERM) waits for requests in flight and cancelled builds. */
    val shutdownGraceSeconds: Int = 10,
    /** One JSON log line per request (startup, shutdown and errors are always logged). */
    val logRequests: Boolean = true,
    val build: BuildConfig = BuildConfig(),
    val overpass: OverpassConfig = OverpassConfig(),
    /** tilebuild install for `profile=outdoors` builds (`./gradlew :tilebuild:installDist`); null: `DYSTOPO_TILEBUILD_HOME` or `tilebuild/build/install/tilebuild`. */
    val tilebuildHome: String? = null,
    /**
     * Regional OSM extracts (`.osm.pbf`, e.g. from Geofabrik) for `profile=outdoors` builds larger than
     * Overpass serves (T64). Each: `path`, optional `bbox` `[w, s, e, n]` and `name` (else read from the
     * PBF header or a sidecar JSON and the file name).
     */
    val extracts: List<ExtractConfig> = emptyList(),
    /** Directory scanned for more `*.osm.pbf` extracts (rescanned per build); null: `<cacheDir>/extracts`; "" turns scanning off. */
    val extractsDir: String? = null,
    /** T72: the encrypted sync relay (`/sync/relay`, docs/SERVER.md, The relay). */
    val relay: RelayConfig = RelayConfig(),
    /** Geofabrik regions downloaded into the extracts directory when a build needs one (T66). */
    val extractDownloads: ExtractDownloadConfig = ExtractDownloadConfig(),
    /** T82: where the JSON log goes and how much of it is kept. */
    val log: LogConfig = LogConfig(),
    /** T82: `GET /metrics` and its background disk scan. */
    val metrics: MetricsConfig = MetricsConfig(),
    /** T82: connection limits of the HTTP listener (slow clients, idle connections). */
    val http: HttpConfig = HttpConfig(),
    /** T74: byte caps (LRU) of the tile stores and where they live. */
    val cache: CacheConfig = CacheConfig(),
) {
    /** T74: [tileDir] holds `live/`, `openfreemap/` and `derived/` (null: [cacheDir]); each store is capped in MB. */
    @Serializable
    data class CacheConfig(
        val tileDir: String? = null,
        val liveMb: Long = 4096,
        val openfreemapMb: Long = 2048,
        val derivedMb: Long = 1024,
    )

    /** T82: null [file] logs to stdout (cap it in the collector); a file rotates at [maxMb], keeping [keep] old files. */
    @Serializable
    data class LogConfig(
        val file: String? = null,
        val maxMb: Int = 64,
        val keep: Int = 5,
    )

    /** T82: [diskScanMinutes] 0 turns the disk scan off (the cache can hold millions of tiles). */
    @Serializable
    data class MetricsConfig(
        val enabled: Boolean = true,
        val diskScanMinutes: Int = 10,
    )

    /**
     * T82: [maxRequestSeconds] is how long a client may take to send a whole request, headers and body (a
     * slow-header drip holds a worker thread until then; a sync push slower than this is cut off, so behind
     * a reverse proxy, which buffers requests, it only bounds the proxy's hop); [idleSeconds] closes keep-alive connections nobody uses;
     * [maxConnections] caps open connections (beyond it new ones are closed at once). Each sets the matching
     * `sun.net.httpserver` / `jdk.httpserver` system property unless `JAVA_OPTS` already set it.
     */
    @Serializable
    data class HttpConfig(
        val maxRequestSeconds: Int = 60,
        val idleSeconds: Int = 30,
        val maxConnections: Int = 1000,
    )

    /** T72: limits of the sync relay; it holds only ciphertext, in memory, for [ttlSeconds] after each write. */
    @Serializable
    data class RelayConfig(
        val enabled: Boolean = true,
        val ttlSeconds: Int = 900,
        val maxBlobBytes: Int = 16 shl 20,
        val maxTotalBytes: Long = 256L shl 20,
        val maxSlots: Int = 10_000,
        val maxSides: Int = 4,
        val requestsPerMinute: Int = 120,
    ) {
        fun toRelay() = Relay.Config(enabled, ttlSeconds, maxBlobBytes, maxTotalBytes, maxSlots, maxSides, requestsPerMinute)
    }

    @Serializable
    data class ExtractDownloadConfig(
        /** Region URLs, e.g. `https://download.geofabrik.de/europe/alps`; each one's `.poly` decides what it covers. */
        val urls: List<String> = emptyList(),
        /** Largest extract downloaded, MB (by Content-Length). */
        val maxMb: Int = 4096,
    )

    @Serializable
    data class ExtractConfig(
        val path: String,
        /** west, south, east, north; null: from a sidecar JSON or the PBF header. */
        val bbox: List<Double>? = null,
        val name: String? = null,
    )

    /** T67: one sync user: an id (`[a-z0-9][a-z0-9_-]{0,31}`, names the replica file) and their bearer token. */
    @Serializable
    data class UserConfig(val id: String, val token: String)

    @Serializable
    data class BuildConfig(
        /** DEM and OSM tiles per job. */
        val maxTiles: Int = 20_000,
        /** Degrees per box side. */
        val maxSpanDegrees: Double = 3.0,
        /** Builds running at once. */
        val concurrent: Int = 1,
        /** Builds waiting beyond those. */
        val queue: Int = 4,
    )

    @Serializable
    data class OverpassConfig(
        /** Overpass API interpreter URL for `profile=outdoors` builds. */
        val url: String = "https://overpass-api.de/api/interpreter",
        /** Largest box sent to Overpass, in square degrees. */
        @SerialName("maxAreaSqDeg") val maxAreaSqDeg: Double = 0.05,
        val timeoutSeconds: Int = 90,
    )

    /** The token in force: `DYSTOPO_SERVER_TOKEN`, else [token]; blank means none. */
    fun effectiveToken(env: (String) -> String? = System::getenv): String? =
        (env("DYSTOPO_SERVER_TOKEN") ?: token)?.trim()?.takeIf { it.isNotEmpty() }

    /** T67: the admin token in force: `DYSTOPO_ADMIN_TOKEN`, else [adminToken]; blank means none. */
    fun effectiveAdminToken(env: (String) -> String? = System::getenv): String? =
        (env("DYSTOPO_ADMIN_TOKEN") ?: adminToken)?.trim()?.takeIf { it.isNotEmpty() }

    /** T67: the users, the admin token and the legacy token as the gate's registry. */
    fun usersRegistry(env: (String) -> String? = System::getenv): Users =
        Users(users.map { Users.User(it.id, it.token.trim()) }, effectiveAdminToken(env), effectiveToken(env))

    fun validate(env: (String) -> String? = System::getenv): List<String> = buildList {
        addAll(Users.problems(users.map { Users.User(it.id, it.token.trim()) }, effectiveAdminToken(env), effectiveToken(env)))
        if (port !in 0..65535) add("port must be 0..65535 (0: any free port; got $port)")
        if (threads < 1) add("threads must be at least 1")
        if (liveThreads != null && liveThreads < 1) add("liveThreads must be at least 1")
        if (shutdownGraceSeconds < 0) add("shutdownGraceSeconds must be >= 0")
        if (build.maxTiles < 1 || build.maxSpanDegrees <= 0 || build.concurrent < 1 || build.queue < 0) add("build limits must be positive (queue may be 0)")
        publicUrl?.let { if (!Regex("https?://[^/\\s]+(/[^\\s]*)?").matches(it)) add("publicUrl must be an absolute http(s) URL (got $it)") }
        extracts.forEachIndexed { i, e ->
            val b = e.bbox
            if (e.path.isBlank()) add("extracts[$i].path must not be empty")
            if (b != null && (b.size != 4 || b[0] >= b[2] || b[1] >= b[3] || b.any { it.isNaN() })) add("extracts[$i].bbox must be [west, south, east, north] with west < east and south < north")
        }
        if (relay.ttlSeconds < 1 || relay.maxBlobBytes < 1024 || relay.maxTotalBytes < relay.maxBlobBytes || relay.maxSlots < 1 || relay.maxSides < 2 || relay.requestsPerMinute < 1) add("relay limits: ttlSeconds >= 1, maxBlobBytes >= 1024, maxTotalBytes >= maxBlobBytes, maxSlots >= 1, maxSides >= 2, requestsPerMinute >= 1")
        extractDownloads.urls.forEachIndexed { i, u ->
            if (!Regex("https?://[^\\s]+/[^/\\s]+").matches(u.trim())) add("extractDownloads.urls[$i] must be an http(s) region URL like https://download.geofabrik.de/europe/alps (got $u)")
        }
        if (extractDownloads.maxMb < 1) add("extractDownloads.maxMb must be at least 1")
        if (extractDownloads.urls.isNotEmpty() && extractsDir == "") add("extractDownloads needs an extracts directory (extractsDir is \"\")")
        if (log.maxMb < 1 || log.keep < 0) add("log.maxMb must be at least 1 and log.keep at least 0")
        if (metrics.diskScanMinutes < 0) add("metrics.diskScanMinutes must be >= 0 (0: off)")
        if (http.maxRequestSeconds < 1 || http.idleSeconds < 1 || http.maxConnections < 1) add("http limits must be at least 1")
        if (corsOrigins.any { it != "*" && !Regex("https?://[^/\\s]+").matches(it) }) add("corsOrigins entries must be \"*\" or an origin like https://maps.example.org (no path)")
    }

    companion object {
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; prettyPrint = true }

        fun parse(text: String): ServerConfig = json.decodeFromString(serializer(), text)

        fun load(file: File): ServerConfig = parse(file.readText())
    }
}
