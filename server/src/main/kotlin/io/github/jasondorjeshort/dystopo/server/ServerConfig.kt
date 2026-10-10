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
) {
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

    fun validate(): List<String> = buildList {
        if (port !in 0..65535) add("port must be 0..65535 (0: any free port; got $port)")
        if (threads < 1) add("threads must be at least 1")
        if (liveThreads != null && liveThreads < 1) add("liveThreads must be at least 1")
        if (shutdownGraceSeconds < 0) add("shutdownGraceSeconds must be >= 0")
        if (build.maxTiles < 1 || build.maxSpanDegrees <= 0 || build.concurrent < 1 || build.queue < 0) add("build limits must be positive (queue may be 0)")
        publicUrl?.let { if (!Regex("https?://[^/\\s]+(/[^\\s]*)?").matches(it)) add("publicUrl must be an absolute http(s) URL (got $it)") }
        if (corsOrigins.any { it != "*" && !Regex("https?://[^/\\s]+").matches(it) }) add("corsOrigins entries must be \"*\" or an origin like https://maps.example.org (no path)")
    }

    companion object {
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; prettyPrint = true }

        fun parse(text: String): ServerConfig = json.decodeFromString(serializer(), text)

        fun load(file: File): ServerConfig = parse(file.readText())
    }
}
