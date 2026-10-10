package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Raw OpenStreetMap for a small box from the Overpass API (registry id `overpass`), for server-built
 * outdoors packs (`POST /build?profile=outdoors`, [BuildRoutes]).
 *
 * The public instances' usage policy (dev.overpass-api.de/overpass-doc/en/preface/commons.html and the
 * wiki's Overpass_API page: about 10,000 requests and 1 GB a day, no parallel scripts, no stitching
 * boxes into a scrape, back off after 429/504) is kept by construction:
 * - **one request per box**: the whole extract comes from a single `[out:xml]` query ([query]);
 * - **a small box**: at most [maxAreaSqDeg] square degrees (`DYSTOPO_OVERPASS_MAX_AREA`, default 0.05,
 *   e.g. 0.3 x 0.17 degrees, about 23 x 19 km at Rainier's latitude), checked before anything is sent ([checkArea]);
 * - **a timeout and a maxsize** in the query (`DYSTOPO_OVERPASS_TIMEOUT_S`, default 90 s; 128 MiB), and
 *   the HTTP timeout a little longer. Both are kept modest on purpose: the server admits a query only
 *   when it needs at most half of the remaining resources, and the first try with 512 MiB was refused
 *   with a 504 while slots were free;
 * - **a cool-down**: after a refusal no request is sent for [COOL_DOWN_MS] (the wiki asks for a pause after
 *   429 and 504); builds in that window fall back at once;
 * - **a descriptive User-Agent** naming the project and its URL;
 * - **cached by box**: the answer is stored under `<cache>/overpass/` and a rebuild of the same box
 *   never asks again ([fetches] vs [cacheHits]);
 * - **never retried**: a refusal (429 rate limit, 504 load, a timeout remark, a network error) is
 *   reported as [Refused] and the caller falls back; nothing loops.
 *
 * The endpoint is `DYSTOPO_OVERPASS_URL` (default the main instance, overpass-api.de).
 */
class OverpassSource(
    private val cacheDir: File = Caches.dir("overpass"),
    val endpoint: String = env("DYSTOPO_OVERPASS_URL") ?: DEFAULT_ENDPOINT,
    val maxAreaSqDeg: Double = env("DYSTOPO_OVERPASS_MAX_AREA")?.toDoubleOrNull() ?: 0.05,
    val timeoutSeconds: Int = env("DYSTOPO_OVERPASS_TIMEOUT_S")?.toIntOrNull()?.coerceIn(10, 900) ?: 90,
    /** POSTs a query body to the endpoint and streams the answer into a file; tests swap in a fake. */
    private val post: (url: String, body: String, timeout: Duration, into: File) -> Int = ::httpPost,
) {
    /** Overpass refused or failed; [status] is the HTTP status (0 for a network error or an error remark). */
    class Refused(val status: Int, message: String) : Exception(message)

    /** A box too big for the policy cap; nothing was sent. */
    class TooBig(val areaSqDeg: Double, val maxSqDeg: Double, message: String) : Exception(message)

    /** The cached extract for a box. */
    class Extract(val file: File, val cached: Boolean, val bytes: Long, val ms: Long, val query: String)

    private val fetchCount = AtomicLong()
    private val hitCount = AtomicLong()

    /** Requests sent to Overpass by this instance. */
    val fetches: Long get() = fetchCount.get()

    /** Builds answered from the cache. */
    val cacheHits: Long get() = hitCount.get()

    fun describe(): Map<String, Any?> = linkedMapOf(
        "endpoint" to endpoint, "maxAreaSqDeg" to maxAreaSqDeg, "timeoutSeconds" to timeoutSeconds,
        "fetches" to fetches, "cacheHits" to cacheHits, "cache" to cacheDir.path,
    )

    /** Throws [TooBig] when [box] exceeds the area cap. */
    fun checkArea(box: BoundingBox) {
        val area = areaSqDeg(box)
        if (area > maxAreaSqDeg) {
            throw TooBig(
                area, maxAreaSqDeg,
                "box is ${fmt(box.east - box.west)} x ${fmt(box.north - box.south)} = ${fmt(area)} square degrees; Overpass extracts are " +
                    "limited to $maxAreaSqDeg square degrees (DYSTOPO_OVERPASS_MAX_AREA, the Overpass usage policy asks for small requests)",
            )
        }
    }

    /** The cache file for [box]: corners rounded to 1e-5 degrees (about 1 m), so the same view maps to the same file. */
    fun cacheFile(box: BoundingBox): File = File(cacheDir, "${key(box)}.osm")

    /**
     * The OSM XML for [box]: from the cache, else one Overpass request. The answer is written to a temp
     * file, checked for Overpass' error remark (a timeout or out-of-memory answer is a 200 with partial
     * data) and only then moved into the cache.
     */
    fun extract(box: BoundingBox): Extract {
        checkArea(box)
        val file = cacheFile(box)
        val q = query(box, timeoutSeconds)
        if (file.isFile && file.length() > 0) {
            hitCount.incrementAndGet()
            return Extract(file, true, file.length(), 0, q)
        }
        val wait = coolUntil - System.currentTimeMillis()
        if (wait > 0) throw Refused(0, "Overpass refused a request less than ${COOL_DOWN_MS / 1000} s ago; not asking again for ${(wait + 999) / 1000} s")
        file.parentFile.mkdirs()
        val tmp = Http.partFile(file)
        val started = System.nanoTime()
        try {
            fetchCount.incrementAndGet()
            return fetch(q, file, tmp, started)
        } catch (e: Refused) {
            coolUntil = System.currentTimeMillis() + COOL_DOWN_MS
            throw e
        } finally {
            tmp.delete()
        }
    }

    @Volatile private var coolUntil = 0L

    private fun fetch(q: String, file: File, tmp: File, started: Long): Extract {
        run {
            val status = try {
                post(endpoint, "data=" + URLEncoder.encode(q, StandardCharsets.UTF_8), Duration.ofSeconds(timeoutSeconds + 30L), tmp)
            } catch (e: java.net.http.HttpTimeoutException) {
                throw Refused(0, "Overpass did not answer within ${timeoutSeconds + 30} s")
            } catch (e: java.io.IOException) {
                throw Refused(0, "Overpass request failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            when (status) {
                200 -> {}
                429 -> throw Refused(429, "Overpass rate limit (HTTP 429): too many requests from this address; try later (not retried automatically)")
                504 -> throw Refused(504, "Overpass is overloaded or the query needs more than the server will give (HTTP 504); try later or a smaller box: ${head(tmp).substringAfter("<body>").take(200)}")
                else -> throw Refused(status, "Overpass answered HTTP $status: ${tmp.takeIf { it.isFile }?.let { head(it) }.orEmpty()}")
            }
            errorRemark(tmp)?.let { throw Refused(0, "Overpass answered with an error: $it") }
            if (!head(tmp).contains("<osm")) throw Refused(0, "Overpass answer is not OSM XML: ${head(tmp)}")
            Http.publish(tmp, file)
            return Extract(file, false, file.length(), (System.nanoTime() - started) / 1_000_000, q)
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val USER_AGENT = "dystopo-devtool/0.1 (offline topo map pack builder; +https://github.com/jasondorjeshort/dystopo)"
        const val MAX_SIZE_BYTES = 128L * 1024 * 1024
        const val COOL_DOWN_MS = 30_000L

        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

        private fun fmt(d: Double) = String.format(Locale.ROOT, "%.4f", d)

        fun areaSqDeg(box: BoundingBox): Double = (box.east - box.west) * (box.north - box.south)

        fun key(box: BoundingBox): String {
            fun c(d: Double) = String.format(Locale.ROOT, "%.5f", d)
            return "bbox_${c(box.west)}_${c(box.south)}_${c(box.east)}_${c(box.north)}"
        }

        /**
         * One query for everything the outdoors profile can use in the box. With a global `[bbox:]`,
         * `node; way; rel;` select the nodes inside the box, the ways with a node inside and the relations
         * with a member inside. Ways come complete (all their nodes, also those outside the box) so lines
         * leaving the box are clipped by Planetiler rather than broken. Multipolygons and protected-area
         * or park boundaries come with every member way and node, so wilderness and glacier areas close;
         * other relations (hiking routes, administrative boundaries, which can span a continent) come
         * as tags plus member ids only, which is all the profile reads from them.
         */
        fun query(box: BoundingBox, timeoutSeconds: Int): String {
            fun c(d: Double) = String.format(Locale.ROOT, "%.6f", d)
            return "[out:xml][timeout:$timeoutSeconds][maxsize:$MAX_SIZE_BYTES][bbox:${c(box.south)},${c(box.west)},${c(box.north)},${c(box.east)}];\n" +
                "(node; way; rel;)->.a;\n" +
                "(way.a; rel.a[type=multipolygon]; rel.a[boundary~\"^(protected_area|national_park)$\"];)->.full;\n" +
                "(.a; .full >;);\n" +
                "out body;"
        }

        /** The first few hundred characters of a file, for error messages. */
        private fun head(file: File): String = if (!file.isFile) "" else file.inputStream().use { s ->
            val buf = ByteArray(400)
            val n = s.read(buf).coerceAtLeast(0)
            String(buf, 0, n, StandardCharsets.UTF_8).replace(Regex("\\s+"), " ").trim()
        }

        /** Overpass reports a timeout or memory exhaustion as `<remark> runtime error: ...</remark>` in a 200 answer, at the end. */
        fun errorRemark(file: File): String? {
            val len = file.length()
            val tail = java.io.RandomAccessFile(file, "r").use { raf ->
                val n = minOf(len, 4096L).toInt()
                raf.seek(len - n)
                ByteArray(n).also { raf.readFully(it) }.toString(StandardCharsets.UTF_8)
            }
            val m = Regex("<remark>(.*?)</remark>", RegexOption.DOT_MATCHES_ALL).find(tail) ?: return null
            val text = m.groupValues[1].trim()
            return text.takeIf { it.contains("error", ignoreCase = true) }
        }

        fun httpPost(url: String, body: String, timeout: Duration, into: File): Int {
            Http.beforeNetwork?.invoke(url)
            val req = HttpRequest.newBuilder(URI(url))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            // No retry: one request per build is the policy; the caller reports the failure.
            return Http.client.send(req, HttpResponse.BodyHandlers.ofFile(into.toPath())).statusCode()
        }
    }
}
