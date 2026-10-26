package io.github.jasondorjeshort.dystopo.app.online

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.areas.Areas
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.packs.PackCatalog
import io.github.jasondorjeshort.dystopo.packs.PackRole
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
import kotlin.math.floor

/**
 * "Download this region": asks the server to build a pack for a box (`POST /build`, T37), follows its
 * progress (`GET /build/{id}`), then hands the finished pair under `/out/` to T29's
 * [PackDownloader] (one call) with activation, so the region shows from the pack, offline, once it is
 * on the device. One job at a time; [json] is the progress the map overlay and `GET /online/download` show.
 *
 * The vector half asks for the outdoors profile (T64: `profile=outdoors&fallback=1`): the server builds it
 * from a regional extract that covers the box, else from Overpass for a small box, else falls back to
 * OpenFreeMap's OpenMapTiles tiles. [json] and the overlay say which profile and source the pack got
 * (`osmProfile`, `osmSource`, `osmExtract`). A server that cannot build outdoors packs at all (no tilebuild,
 * HTTP 503, or an old server that refuses the parameter) is asked again for the plain profile.
 *
 * Phases ([state]): `idle`, `building` (server), `downloading` (device), `done`, `failed`, `cancelled`.
 * A name the server already built is downloaded without building again; a pack already on the device is
 * just activated.
 *
 * T75: this is the box case of an area of interest. With a server that has `POST /area`, the build is asked for
 * through it (the same `/build` job underneath) and the finished pack comes with each archive's SHA-256 and size,
 * so the download is verified like a catalog download ([PackDownloader.startCatalog]); an older server gets the
 * plain `/build` path as before. Either way (and for a pack built on the phone) the result is recorded in
 * [Areas] as a pinned, pack-backed area, and the phone builds the search index and trail graph from it.
 */
class RegionDownload(private val service: OnlineService, private val app: DystopoApp) {
    init {
        Areas.attach(service) // T75: pinned areas' tiles are skipped by the tile cache's eviction
    }

    /** T75: the area this job downloads (the box and zooms) and the server's manifest once it is ready. */
    @Volatile private var area: AreaOfInterest? = null
    @Volatile private var manifest: AreaManifest? = null
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
    /** The vector profile asked for: [PROFILE_OUTDOORS] (default) or `openmaptiles`. */
    @Volatile var profile: String = PROFILE_OUTDOORS
        private set

    val running: Boolean get() = state == "building" || state == "downloading"

    /**
     * Starts building and downloading [box] at [minZoom]..[maxZoom] (defaults: z8 to z14, lowered until the
     * box fits [TILE_LIMIT] tiles), named from the box unless [name] is given.
     */
    @Synchronized
    fun start(box: PackBounds, minZoom: Int?, maxZoom: Int?, name: String?, profile: String? = null): Map<String, Any?> {
        val wanted = profile?.trim()?.lowercase()?.ifEmpty { null } ?: PROFILE_OUTDOORS
        require(wanted == PROFILE_OUTDOORS || wanted == PROFILE_OPENMAPTILES) { "profile must be $PROFILE_OUTDOORS or $PROFILE_OPENMAPTILES (got '$profile')" }
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
        val packName = name ?: nameFor(System.currentTimeMillis(), takenNames())
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
        this.profile = wanted
        area = AreaOfInterest(AreaShape.Box(box.minLon, box.minLat, box.maxLon, box.maxLat), lo, hi, packName)
        manifest = null
        startedAt = System.currentTimeMillis()
        builtAt = 0L
        finishedAt = 0L
        state = "building"
        Thread({ run(server, packName, box, lo..hi) }, "online-region-download").apply { isDaemon = true }.start()
        return json()
    }

    /** T145: the pack and offline-area names in use on this phone, which a generated name must not repeat. */
    private fun takenNames(): Set<String> {
        val packs = runCatching { PackManager.get(app).catalog().map { it.name } }.getOrDefault(emptyList())
        val areas = runCatching { io.github.jasondorjeshort.dystopo.app.areas.Areas.all().flatMap { listOfNotNull(it.name, it.pack) } }.getOrDefault(emptyList())
        return (packs + areas).toSet()
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
        if (server == DirectSources.SERVER) return runLocal(packName, box, z)
        try {
            // T75: through the server's area manifest when it has one (hashes for every archive), else plain /build.
            val m = buildWithArea(server, packName, box, z)
            if (m == null) buildOnServer(server, packName, box, z)
            if (cancelled) return
            builtAt = System.currentTimeMillis()
            state = "downloading"
            val packs = PackManager.get(app)
            // T29's downloader does the rest (resume, verification); activation moves the camera to the pack.
            val job = try {
                if (m != null) {
                    val files = m.packs.mapNotNull { f ->
                        val role = PackRole.entries.firstOrNull { it.label == f.role } ?: return@mapNotNull null
                        role to Triple(PackCatalog.resolveUrl(server, f.url), f.sha256, f.bytes)
                    }.toMap()
                    packs.downloads.startCatalog(packName, files, emptySet(), update = false, activate = true, catalog = linkedMapOf("server" to server, "id" to packName, "area" to m.id))
                } else packs.downloads.start("$server/out/$packName-dem.pmtiles", packName, activate = true)
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
            // The downloader opens the pack on its own thread just after it reports done (T41: next to the others).
            val deadline = System.currentTimeMillis() + 10_000
            while (packName !in packs.inUse() && System.currentTimeMillis() < deadline) Thread.sleep(200)
            recordArea(server, packName)
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

    /**
     * T80: no server: the phone builds the pack itself ([LocalPackBuild]) from the DEM tiles it renders and the
     * OpenFreeMap tiles it fetches (both through the online fetcher, so tiles already seen come from its cache),
     * then installs it like an imported pack: a pinned, immutable area that works with the network off.
     */
    private fun runLocal(packName: String, box: PackBounds, z: IntRange) {
        try {
            val f = service.fetcher ?: throw IOException("no online source")
            val local = LocalPackBuild(service.direct, f, app.cacheDir, packName, box, z) { cancelled }
            build = local.json()
            val result = local.run { build = it }
            if (cancelled) return
            builtAt = System.currentTimeMillis()
            state = "downloading" // installing: the archives are copied into the packs dir and opened
            build = result
            PackManager.get(app).importFiles(local.outputs().map { file ->
                PackManager.ImportSource(file.name, file.length()) { file.inputStream() }
            }, packName)
            local.cleanUp()
            note = "built on this phone from public sources"
            recordArea(DirectSources.SERVER, packName)
            state = "done"
        } catch (e: Exception) {
            if (cancelled) return
            Log.w(OnlineService.TAG, "local region build $packName failed", e)
            error = e.message ?: e.javaClass.simpleName
            state = "failed"
        } finally {
            if (finishedAt == 0L) finishedAt = System.currentTimeMillis()
        }
    }

    /** T75: records the finished pack as a pinned area and starts the search index and trail graph builds. */
    private fun recordArea(source: String, packName: String) {
        val a = area ?: return
        val bytes = runCatching { PackManager.get(app).find(packName)?.files?.files?.sumOf { it.length() } }.getOrNull() ?: 0L
        Areas.put(
            Areas.Record(
                id = a.id, name = packName, area = a, createdAt = startedAt, pinned = true, state = "done", source = source, pack = packName,
                bytes = bytes, estimateBytes = manifest?.bytes ?: bytes, downloadMs = System.currentTimeMillis() - startedAt,
            ),
        )
        Thread({
            runCatching { io.github.jasondorjeshort.dystopo.app.search.PlaceSearch.ensure(app) }
            runCatching { io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner.ensure(app) }
        }, "area-products").apply { isDaemon = true }.start()
    }

    /**
     * T75: `POST /area` for the box: follows the `/build` job it starts (or finds) and asks again once it is done,
     * returning the ready manifest with the archives' URLs, sizes and SHA-256. Null when the server has no `/area`.
     */
    private fun buildWithArea(server: String, packName: String, box: PackBounds, z: IntRange): AreaManifest? {
        val q = "name=${enc(packName)}&pack=1&fallback=1&profile=" + (if (profile == PROFILE_OUTDOORS) PROFILE_OUTDOORS else PROFILE_OPENMAPTILES)
        val body = MiniJson.encode(AreaOfInterest(AreaShape.Box(box.minLon, box.minLat, box.maxLon, box.maxLat), z.first, z.last, packName).json())
        var (status, doc) = http("POST", "$server/area?$q&retry=1", body)
        if (status == 404 || status == 405) return null
        var waited = false
        while (true) {
            if (cancelled) return null
            when (status) {
                200 -> {
                    val m = AreaManifest.parse(doc)
                    if (m.status == AreaManifest.STATUS_FAILED) throw IOException("server build failed: ${m.notes.joinToString("; ")}")
                    manifest = m
                    if (!waited) note = "already built on the server"
                    return m
                }
                202 -> {
                    @Suppress("UNCHECKED_CAST")
                    val job = doc["build"] as? Map<String, Any?> ?: throw IOException("server answered building without a job")
                    val id = job["id"]?.toString() ?: throw IOException("server returned no build id")
                    buildId = id
                    build = job
                    waited = true
                    followBuild(server, id)
                    if (cancelled) return null
                    val again = http("POST", "$server/area?$q", body)
                    status = again.first
                    doc = again.second
                }
                else -> throw IOException("server refused the area (HTTP $status): ${doc["error"] ?: doc}")
            }
        }
    }

    /** Polls `GET /build/{id}` until the job is done; throws when it failed or was cancelled. */
    private fun followBuild(server: String, id: String) {
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

    private fun buildOnServer(server: String, packName: String, box: PackBounds, z: IntRange) {
        val bbox = listOf(box.minLon, box.minLat, box.maxLon, box.maxLat).joinToString(",") { String.format(Locale.ROOT, "%.5f", it) }
        val base = "name=${enc(packName)}&bbox=${enc(bbox)}&minZoom=${z.first}&maxZoom=${z.last}"
        var (status, body) = http("POST", "$server/build?$base" + if (profile == PROFILE_OUTDOORS) "&profile=$PROFILE_OUTDOORS&fallback=1" else "")
        if (profile == PROFILE_OUTDOORS && (status == 503 || (status == 400 && "profile" in body["error"]?.toString().orEmpty()))) {
            // The server cannot build outdoors packs (no tilebuild, or too old to know the profile): ask for the plain one.
            note = "server cannot build the outdoors profile (${body["error"]}); built OpenMapTiles"
            val retry = http("POST", "$server/build?$base")
            status = retry.first
            body = retry.second
        }
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
    private fun http(method: String, url: String, body: String? = null): Pair<Int, Map<String, Any?>> {
        val conn = io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy.open(url) // T79: plain http only to home-network hosts
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        try {
            if (method == "POST") {
                conn.doOutput = true
                val bytes = body?.toByteArray() ?: ByteArray(0)
                if (body != null) conn.setRequestProperty("Content-Type", "application/json") // T75: the area
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
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
            "profile" to profile,
            "osmProfile" to b?.get("osmProfile"),
            "osmSource" to b?.get("osmSource"),
            "osmExtract" to (b?.get("provenance") as? Map<*, *>)?.get("osmExtract"),
            "packLabel" to packLabel(b),
            "warnings" to b?.get("warnings"),
            "build" to (b?.let { mapOf("id" to it["id"], "status" to it["status"], "phase" to it["phase"], "percent" to it["percent"], "elapsedMs" to it["elapsedMs"], "dem" to it["dem"], "osm" to it["osm"], "files" to it["files"]) }
                ?: buildId?.let { mapOf("id" to it) }),
            "download" to job?.json(),
            "percent" to percent(),
            "elapsedMs" to if (startedAt == 0L) null else (if (finishedAt != 0L) finishedAt else now) - startedAt,
            "buildMs" to if (builtAt == 0L) null else builtAt - startedAt,
            "activePack" to runCatching { PackManager.get(app).activeName() }.getOrNull(),
            "packsInUse" to runCatching { PackManager.get(app).inUse() }.getOrNull(), // T41
            "area" to area?.let { a -> linkedMapOf("id" to a.id, "manifest" to manifest?.summary()) }, // T75
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
        const val PROFILE_OUTDOORS = "outdoors"
        const val PROFILE_OPENMAPTILES = "openmaptiles"
        const val DEFAULT_MIN_ZOOM = 8

        /**
         * What the overlay says about the vector half of a server build job ([build] is its JSON): `outdoors,
         * washington extract 2026-10-08`, `outdoors, Overpass`, or `OpenMapTiles (fallback)`; null before the
         * server has said.
         */
        fun packLabel(build: Map<String, Any?>?): String? {
            val p = build?.get("osmProfile")?.toString() ?: return null
            val extract = (build["provenance"] as? Map<*, *>)?.get("osmExtract") as? Map<*, *>
            return when {
                p == PROFILE_OUTDOORS && build["osmSource"] == "extract" && extract != null ->
                    "outdoors, ${extract["name"]} extract" + (extract["date"]?.toString()?.take(10)?.let { " $it" } ?: "")
                p == PROFILE_OUTDOORS && build["osmSource"] == "overpass" -> "outdoors, Overpass"
                p == PROFILE_OUTDOORS -> "outdoors"
                build["profile"] == PROFILE_OUTDOORS -> "OpenMapTiles (outdoors not available here)"
                else -> "OpenMapTiles"
            }
        }
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

        /**
         * T145: a generated pack name, `view-<local date>-<n>` (`view-2026-10-25-1`), the lowest n not in [taken]. It no
         * longer carries the view's centre and zooms (`view-45.83n-6.86e-z8-14` before T145; such packs keep their names
         * and still read, since nothing parses a pack's name), so a name that reaches a problem report or a shared folder
         * names no place (ADR 0033: a data format change in alpha, listed in T145's report).
         */
        fun nameFor(nowMs: Long, taken: Set<String>): String {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(nowMs))
            return generateSequence(1) { it + 1 }.map { "view-$day-$it" }.first { it !in taken }
        }
    }
}
