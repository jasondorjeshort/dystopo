package io.github.jasondorjeshort.dystopo.app.online

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.packs.PackDownloader
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * "Download this region": asks the server to build a pack for a box (`POST /build`, T37), follows its
 * progress (`GET /build/{id}`), then hands the finished pair under `/out/` to T29's
 * [PackDownloader] (one call) with activation, so the region shows from the pack, offline, once it is
 * on the device. One job at a time; [json] is the progress the map overlay and `GET /online/download` show.
 *
 * Phases ([state]): `idle`, `building` (server), `downloading` (device), `done`, `failed`, `cancelled`.
 * A name the server already built is downloaded without building again; a pack already on the device is
 * just activated.
 */
class RegionDownload(private val service: OnlineService, private val app: DystopoApp) {
    @Volatile var state = "idle"
        private set
    @Volatile var name: String? = null
        private set
    @Volatile private var bounds: PackBounds? = null
    @Volatile private var zooms: IntRange? = null
    @Volatile private var buildId: String? = null
    @Volatile private var build: Map<String, Any?>? = null
    @Volatile private var download: PackDownloader.Job? = null
    @Volatile var error: String? = null
        private set
    @Volatile private var cancelled = false
    @Volatile private var startedAt = 0L
    @Volatile private var builtAt = 0L
    @Volatile private var finishedAt = 0L
    @Volatile private var note: String? = null

    val running: Boolean get() = state == "building" || state == "downloading"

    /**
     * Starts building and downloading [box] at [minZoom]..[maxZoom] (defaults: z8 to z14, lowered until the
     * box fits [TILE_LIMIT] tiles), named from the box unless [name] is given.
     */
    @Synchronized
    fun start(box: PackBounds, minZoom: Int?, maxZoom: Int?, name: String?): Map<String, Any?> {
        check(!running) { "a region download is already running (${this.name}); DELETE /online/download first" }
        val server = service.fetcher?.server ?: throw IllegalArgumentException("no server set: POST /settings?server=http://host:port")
        require(box.maxLon > box.minLon && box.maxLat > box.minLat) { "empty box" }
        require(box.maxLon - box.minLon <= MAX_SPAN_DEG && box.maxLat - box.minLat <= MAX_SPAN_DEG) {
            "the view is ${fmt(box.maxLon - box.minLon)} x ${fmt(box.maxLat - box.minLat)} degrees; zoom in to at most $MAX_SPAN_DEG degrees per side"
        }
        val lo = (minZoom ?: DEFAULT_MIN_ZOOM).coerceIn(0, OnlineTiles.DEM_MAX_ZOOM)
        var hi = (maxZoom ?: DEFAULT_MAX_ZOOM).coerceIn(maxOf(lo, OnlineTiles.DEM_MIN_ZOOM), OnlineTiles.DEM_MAX_ZOOM)
        while (hi > maxOf(lo, OnlineTiles.DEM_MIN_ZOOM) && tileCount(box, lo..hi) > TILE_LIMIT) hi--
        require(tileCount(box, lo..hi) <= TILE_LIMIT) { "the view needs too many tiles even at z$lo-$hi; zoom in" }
        val packName = name ?: nameFor(box, lo..hi)
        require(PackNames.isValidName(packName)) { "invalid pack name '$packName'" }
        this.name = packName
        bounds = box
        zooms = lo..hi
        buildId = null
        build = null
        download = null
        error = null
        note = null
        cancelled = false
        startedAt = System.currentTimeMillis()
        builtAt = 0L
        finishedAt = 0L
        state = "building"
        Thread({ run(server, packName, box, lo..hi) }, "online-region-download").apply { isDaemon = true }.start()
        return json()
    }

    /** Stops the job: cancels the server build or the device download. */
    fun cancel(): Map<String, Any?> {
        if (!running) return json()
        cancelled = true
        val server = service.fetcher?.server
        buildId?.let { id -> if (server != null) runCatching { http("DELETE", "$server/build/$id") } }
        name?.let { n -> if (download != null) runCatching { PackManager.get(app).downloads.cancel(n) } }
        state = "cancelled"
        finishedAt = System.currentTimeMillis()
        return json()
    }

    private fun run(server: String, packName: String, box: PackBounds, z: IntRange) {
        try {
            buildOnServer(server, packName, box, z)
            if (cancelled) return
            builtAt = System.currentTimeMillis()
            state = "downloading"
            val packs = PackManager.get(app)
            // T29's downloader does the rest (resume, verification); activation moves the camera to the pack.
            val job = try {
                packs.downloads.start("$server/out/$packName-dem.pmtiles", packName, activate = true)
            } catch (e: IllegalArgumentException) {
                if (packs.find(packName) == null) throw e
                note = "already on the device; activated"
                packs.activate(packName)
                null
            }
            download = job
            while (job != null && (job.state == "queued" || job.state == "running")) {
                if (cancelled) return
                Thread.sleep(POLL_MS)
            }
            if (job != null && job.state != "done") throw IOException("download ${job.state}: ${job.error}")
            // The downloader activates on its own thread just after it reports done.
            val deadline = System.currentTimeMillis() + 10_000
            while (packs.activeName() != packName && System.currentTimeMillis() < deadline) Thread.sleep(200)
            state = "done"
        } catch (e: Exception) {
            if (cancelled) return
            Log.w(OnlineService.TAG, "region download $packName failed", e)
            error = e.message ?: e.javaClass.simpleName
            state = "failed"
        } finally {
            if (finishedAt == 0L) finishedAt = System.currentTimeMillis()
        }
    }

    private fun buildOnServer(server: String, packName: String, box: PackBounds, z: IntRange) {
        val bbox = listOf(box.minLon, box.minLat, box.maxLon, box.maxLat).joinToString(",") { String.format(Locale.ROOT, "%.5f", it) }
        val query = "name=${enc(packName)}&bbox=${enc(bbox)}&minZoom=${z.first}&maxZoom=${z.last}"
        val (status, body) = http("POST", "$server/build?$query")
        var id: String? = null
        when (status) {
            202, 200 -> id = body["id"]?.toString()
            409 -> {
                val message = body["error"]?.toString().orEmpty()
                if ("already exist" in message) {
                    note = "already built on the server"
                    return
                }
                // Queued or running already (another device, or a retry): follow that job.
                @Suppress("UNCHECKED_CAST")
                val jobs = (http("GET", "$server/build").second["jobs"] as? List<Map<String, Any?>>).orEmpty()
                id = jobs.lastOrNull { it["name"] == packName && it["status"] in setOf("queued", "running") }?.get("id")?.toString()
                    ?: throw IOException("server: $message")
            }
            else -> throw IOException("server refused the build (HTTP $status): ${body["error"] ?: body}")
        }
        buildId = id ?: throw IOException("server returned no build id")
        while (true) {
            if (cancelled) return
            Thread.sleep(POLL_MS)
            val (s, job) = http("GET", "$server/build/$id")
            if (s != 200) throw IOException("build status HTTP $s")
            build = job
            when (job["status"]) {
                "done" -> return
                "failed" -> throw IOException("server build failed: ${job["error"]}")
                "cancelled" -> throw IOException("server build was cancelled")
            }
        }
    }

    /** One JSON request to the server: status and the parsed object (empty when the body is not JSON). */
    private fun http(method: String, url: String): Pair<Int, Map<String, Any?>> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        try {
            if (method == "POST") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                conn.outputStream.close()
            }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            val body = runCatching { MiniJson.parseObject(text) }.getOrDefault(emptyMap())
            return status to body
        } finally {
            conn.disconnect()
        }
    }

    fun json(): Map<String, Any?> {
        val b = build
        val job = download
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "state" to state, "name" to name, "error" to error, "note" to note,
            "bbox" to bounds?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
            "zooms" to zooms?.let { listOf(it.first, it.last) },
            "build" to (b?.let { mapOf("id" to it["id"], "status" to it["status"], "phase" to it["phase"], "percent" to it["percent"], "elapsedMs" to it["elapsedMs"], "dem" to it["dem"], "osm" to it["osm"], "files" to it["files"]) }
                ?: buildId?.let { mapOf("id" to it) }),
            "download" to job?.json(),
            "percent" to percent(),
            "elapsedMs" to if (startedAt == 0L) null else (if (finishedAt != 0L) finishedAt else now) - startedAt,
            "buildMs" to if (builtAt == 0L) null else builtAt - startedAt,
            "activePack" to runCatching { PackManager.get(app).activeName() }.getOrNull(),
        )
    }

    /** Overall progress for the overlay: the build is the first half, the download the second. */
    fun percent(): Double? = when (state) {
        "building" -> ((build?.get("percent") as? Number)?.toDouble() ?: 0.0) / 2
        "downloading" -> 50.0 + ((download?.json()?.get("percent") as? Number)?.toDouble() ?: 0.0) / 2
        "done" -> 100.0
        else -> null
    }

    companion object {
        const val DEFAULT_MIN_ZOOM = 8
        const val DEFAULT_MAX_ZOOM = 14
        /** The server's per-job default (`DYSTOPO_BUILD_MAX_TILES`), DEM and OSM tiles together. */
        const val TILE_LIMIT = 20_000
        /** The server's default box limit (`DYSTOPO_BUILD_MAX_SPAN_DEG`). */
        const val MAX_SPAN_DEG = 3.0
        const val POLL_MS = 1_000L

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun fmt(d: Double) = String.format(Locale.ROOT, "%.2f", d)

        /** Tiles a build of [box] at [zooms] writes: DEM tiles (from z6) plus OSM tiles (to z14), as the server counts them. */
        fun tileCount(box: PackBounds, zooms: IntRange): Int {
            fun count(z: Int): Int {
                val n = 1 shl z
                val x0 = floor(WebMercator.lonToX(box.minLon, z)).toInt().coerceIn(0, n - 1)
                val x1 = floor(WebMercator.lonToX(box.maxLon, z)).toInt().coerceIn(0, n - 1)
                val y0 = floor(WebMercator.latToY(box.maxLat, z)).toInt().coerceIn(0, n - 1)
                val y1 = floor(WebMercator.latToY(box.minLat, z)).toInt().coerceIn(0, n - 1)
                return (x1 - x0 + 1) * (y1 - y0 + 1)
            }
            val dem = zooms.filter { it in OnlineTiles.DEM_MIN_ZOOM..OnlineTiles.DEM_MAX_ZOOM }.sumOf(::count)
            val osm = zooms.filter { it <= OnlineTiles.OSM_MAX_ZOOM }.sumOf(::count)
            return dem + osm
        }

        /** A pack name from the view: its centre and zooms, e.g. `view-45.83n-6.86e-z8-14`. */
        fun nameFor(box: PackBounds, zooms: IntRange): String {
            val lat = (box.minLat + box.maxLat) / 2
            val lon = (box.minLon + box.maxLon) / 2
            val ns = if (lat >= 0) "n" else "s"
            val ew = if (lon >= 0) "e" else "w"
            return String.format(Locale.ROOT, "view-%.2f%s-%.2f%s-z%d-%d", abs(lat), ns, abs(lon), ew, zooms.first, zooms.last)
        }
    }
}
