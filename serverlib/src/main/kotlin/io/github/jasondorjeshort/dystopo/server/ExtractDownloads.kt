package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * A region polygon in the Osmosis `.poly` format that Geofabrik publishes next to every extract (T66): a
 * name line, then sections of `lon lat` lines each closed by `END` (a section whose name starts with `!`
 * is a hole), then a final `END`. Coverage is "the whole box lies inside", so a box near a state border is
 * no longer counted as covered just because it is inside the region's bounding box.
 */
class OsmPoly(val name: String, val rings: List<DoubleArray>, val holes: List<Boolean>) {
    val bbox: BoundingBox = run {
        var w = Double.MAX_VALUE; var s = Double.MAX_VALUE; var e = -Double.MAX_VALUE; var n = -Double.MAX_VALUE
        for ((i, r) in rings.withIndex()) if (!holes[i]) for (k in r.indices step 2) {
            w = minOf(w, r[k]); e = maxOf(e, r[k]); s = minOf(s, r[k + 1]); n = maxOf(n, r[k + 1])
        }
        BoundingBox(w, s, e, n)
    }

    /** The point is inside: in an odd number of rings (outer rings and holes alike, even-odd). */
    fun contains(lon: Double, lat: Double): Boolean {
        var inside = false
        for (r in rings) {
            val n = r.size / 2
            var j = n - 1
            for (k in 0 until n) {
                val yk = r[2 * k + 1]; val yj = r[2 * j + 1]
                if ((yk > lat) != (yj > lat) && lon < r[2 * k] + (lat - yk) * (r[2 * j] - r[2 * k]) / (yj - yk)) inside = !inside
                j = k
            }
        }
        return inside
    }

    /**
     * The whole [box] lies inside: its corners are inside and no polygon edge crosses or enters the box (so
     * no hole, inlet or other region pokes into it).
     */
    fun covers(box: BoundingBox): Boolean {
        if (box.west < bbox.west || box.east > bbox.east || box.south < bbox.south || box.north > bbox.north) return false
        if (!contains(box.west, box.south) || !contains(box.east, box.south) || !contains(box.west, box.north) || !contains(box.east, box.north)) return false
        for (r in rings) {
            val n = r.size / 2
            var j = n - 1
            for (k in 0 until n) {
                if (segmentTouchesBox(r[2 * j], r[2 * j + 1], r[2 * k], r[2 * k + 1], box)) return false
                j = k
            }
        }
        return true
    }

    companion object {
        fun parse(text: String): OsmPoly {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.iterator()
            require(lines.hasNext()) { "empty .poly file" }
            val name = lines.next()
            val rings = ArrayList<DoubleArray>()
            val holes = ArrayList<Boolean>()
            while (lines.hasNext()) {
                val section = lines.next()
                if (section == "END") break
                val coords = ArrayList<Double>()
                while (true) {
                    require(lines.hasNext()) { "unterminated section '$section' in .poly $name" }
                    val line = lines.next()
                    if (line == "END") break
                    val parts = line.split(Regex("\\s+"))
                    require(parts.size >= 2) { "bad coordinate line '$line' in .poly $name" }
                    coords += parts[0].toDouble()
                    coords += parts[1].toDouble()
                }
                if (coords.size >= 6) {
                    rings += coords.toDoubleArray()
                    holes += section.startsWith("!")
                }
            }
            require(rings.any { true } && holes.any { !it }) { ".poly $name has no outer ring" }
            return OsmPoly(name, rings, holes)
        }

        /** Segment a-b intersects the closed box (an end inside, or it crosses an edge). */
        fun segmentTouchesBox(ax: Double, ay: Double, bx: Double, by: Double, box: BoundingBox): Boolean {
            fun inside(x: Double, y: Double) = x >= box.west && x <= box.east && y >= box.south && y <= box.north
            if (inside(ax, ay) || inside(bx, by)) return true
            if (maxOf(ax, bx) < box.west || minOf(ax, bx) > box.east || maxOf(ay, by) < box.south || minOf(ay, by) > box.north) return false
            // Liang-Barsky: the parametric segment against the four slabs.
            var t0 = 0.0
            var t1 = 1.0
            val dx = bx - ax
            val dy = by - ay
            for ((p, q) in listOf(-dx to ax - box.west, dx to box.east - ax, -dy to ay - box.south, dy to box.north - ay)) {
                if (p == 0.0) {
                    if (q < 0) return false
                } else {
                    val t = q / p
                    if (p < 0) { if (t > t1) return false; if (t > t0) t0 = t } else { if (t < t0) return false; if (t < t1) t1 = t }
                }
            }
            return t0 <= t1
        }
    }
}

/**
 * Extracts the server may download when a `profile=outdoors` build needs one (T66): a list of Geofabrik
 * region URLs from the config (`extractDownloads.urls`, e.g. `https://download.geofabrik.de/europe/alps`;
 * a `-latest.osm.pbf` or `.poly` URL works too). Each region's `.poly` is fetched on first use and kept in
 * [dir] (small; it decides coverage); the `-latest.osm.pbf` is downloaded only when a build asks for a box
 * that no extract on disk holds and the polygon covers, then lives in [dir] like any other extract (with
 * its `.poly` beside it, so [Extracts] keeps using the polygon). One download per region at a time;
 * files over [maxBytes] (by `Content-Length`) are refused before any byte is written.
 */
class ExtractDownloads(
    urls: List<String>,
    val dir: File?,
    val maxBytes: Long = DEFAULT_MAX_MB * 1024 * 1024,
    /** GET with a body stream (tests swap in a fake): status, content length (-1 unknown), stream. */
    private val open: (String) -> Triple<Int, Long, InputStream> = ::httpOpen,
    /** T92: region name -> the SHA-256 (hex) its download must have (`extractDownloads.sha256`); a mismatch is refused. */
    sha256: Map<String, String> = emptyMap(),
) {
    /**
     * One configured region: [base] without suffix; [name] its last path segment. T92: [pin] is a Geofabrik
     * snapshot date (`YYMMDD`, from a URL like `.../liechtenstein-260101.osm.pbf`): that file instead of
     * `-latest`, so a CI run reads the same bytes every time; [sha256] is the checksum the download must have.
     */
    class Region(val base: String, val pin: String? = null, val sha256: String? = null) {
        val name: String = base.trimEnd('/').substringAfterLast('/')
        val pbfUrl: String get() = "$base-${pin ?: "latest"}.osm.pbf"
        val polyUrl: String get() = "$base.poly"
    }

    private val checksums = sha256.mapValues { it.value.trim().lowercase() }.also { m ->
        m.forEach { (k, v) -> require(HEX64.matches(v)) { "extractDownloads.sha256.$k must be 64 hex digits" } }
    }

    val regions: List<Region> = urls.map { u ->
        var t = u.trim().removeSuffix("-latest.osm.pbf").removeSuffix(".poly").trimEnd('/')
        val pin = PINNED.find(t)?.groupValues?.get(1)
        if (pin != null) t = t.removeSuffix("-$pin.osm.pbf")
        t = t.removeSuffix(".osm.pbf")
        require(Regex("https?://[^\\s]+/[^/\\s]+").matches(t)) { "extract download URL must be an http(s) region URL like https://download.geofabrik.de/europe/alps (got '$u')" }
        val name = t.trimEnd('/').substringAfterLast('/')
        Region(t, pin, checksums[name])
    }

    private val polys = ConcurrentHashMap<String, Result<OsmPoly>>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** T92: the SHA-256 of each region's last download in this process (listed by [describe]). */
    val lastSha256 = ConcurrentHashMap<String, String>()

    /** Downloads in progress: region name -> bytes so far, total (-1 unknown). */
    val progress = ConcurrentHashMap<String, LongArray>()

    fun polyFile(r: Region): File? = dir?.let { File(it, "${r.name}.poly") }

    /** The region's polygon: from [dir], else fetched once (a failure is remembered for this process and listed). */
    fun poly(r: Region): OsmPoly? = polys.computeIfAbsent(r.name) {
        runCatching {
            val f = polyFile(r)
            val text = if (f != null && f.isFile) f.readText() else {
                val (status, _, body) = open(r.polyUrl)
                val bytes = body.use { it.readBytes() }
                if (status != 200) throw IOException("HTTP $status for ${r.polyUrl}")
                val t = bytes.decodeToString()
                OsmPoly.parse(t) // before keeping it
                if (f != null) {
                    f.parentFile.mkdirs()
                    Http.partFile(f).also { it.writeText(t) }.let { Http.publish(it, f) }
                }
                t
            }
            OsmPoly.parse(text)
        }
    }.getOrNull()

    fun problem(r: Region): String? = polys[r.name]?.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }

    /** The smallest configured region whose polygon holds all of [box], or null. */
    fun covering(box: BoundingBox): Region? =
        regions.mapNotNull { r -> poly(r)?.let { r to it } }.filter { (_, p) -> p.covers(box) }
            .minByOrNull { (_, p) -> (p.bbox.east - p.bbox.west) * (p.bbox.north - p.bbox.south) }?.first

    /** The extract file a download of [r] writes (also how [Extracts] finds it afterwards). */
    fun pbfFile(r: Region): File? = dir?.let { File(it, "${r.name}-${r.pin ?: "latest"}${Extracts.PBF_SUFFIX}") }

    /**
     * Downloads [r]'s `-latest.osm.pbf` into [dir] (unless a copy is already there) and returns the file.
     * Concurrent callers for one region share the download; [cancelled] stops it (the partial file goes).
     */
    fun download(r: Region, cancelled: () -> Boolean = { false }): File {
        val out = pbfFile(r) ?: throw IOException("no extracts directory to download into")
        synchronized(locks.computeIfAbsent(r.name) { Any() }) {
            if (out.isFile) return out
            poly(r) // writes the .poly beside the extract, so Extracts uses the polygon for coverage
            val (status, length, body) = open(r.pbfUrl)
            body.use { input ->
                if (status != 200) throw IOException("HTTP $status for ${r.pbfUrl}")
                if (length > maxBytes) throw IOException("${r.pbfUrl} is ${length / 1_048_576} MB, over the ${maxBytes / 1_048_576} MB limit (extractDownloads.maxMb)")
                out.parentFile.mkdirs()
                val part = Http.partFile(out)
                val counter = LongArray(2).also { it[1] = length }
                progress[r.name] = counter
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                try {
                    part.outputStream().buffered(1 shl 16).use { os ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (cancelled()) throw IOException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            counter[0] += n
                            if (counter[0] > maxBytes) throw IOException("${r.pbfUrl} passed the ${maxBytes / 1_048_576} MB limit")
                        }
                    }
                    if (length >= 0 && counter[0] != length) throw IOException("${r.pbfUrl}: got ${counter[0]} of $length bytes")
                    val got = digest.digest().joinToString("") { "%02x".format(it) }
                    lastSha256[r.name] = got
                    // T92: a pinned checksum (extractDownloads.sha256) must match before the file is published.
                    if (r.sha256 != null && got != r.sha256) throw IOException("${r.pbfUrl}: sha256 $got, expected ${r.sha256} (extractDownloads.sha256)")
                    Extracts.readHeader(part) // a real PBF before it is published
                    Http.publish(part, out)
                } finally {
                    part.delete()
                    progress.remove(r.name)
                }
            }
            return out
        }
    }

    fun describe(box: BoundingBox? = null): List<Map<String, Any?>> = regions.map { r ->
        val p = poly(r)
        linkedMapOf(
            "name" to r.name, "url" to r.pbfUrl, "poly" to r.polyUrl, "pin" to r.pin, "sha256" to r.sha256, "downloadedSha256" to lastSha256[r.name],
            "bbox" to p?.bbox?.let { listOf(it.west, it.south, it.east, it.north) },
            "downloaded" to (pbfFile(r)?.isFile == true),
            "downloading" to progress[r.name]?.let { mapOf("bytes" to it[0], "total" to it[1]) },
            "problem" to problem(r),
        ).also { m -> if (box != null) m["covers"] = p?.covers(box) == true }
    }

    companion object {
        const val DEFAULT_MAX_MB = 4096L

        /** A Geofabrik snapshot URL's date: `<region>-YYMMDD.osm.pbf`. */
        private val PINNED = Regex("-(\\d{6})\\.osm\\.pbf$")
        private val HEX64 = Regex("[0-9a-f]{64}")

        private fun httpOpen(url: String): Triple<Int, Long, InputStream> {
            Http.beforeNetwork?.invoke(url)
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(30)).build()
            val resp = Http.client.send(req, HttpResponse.BodyHandlers.ofInputStream())
            return Triple(resp.statusCode(), resp.headers().firstValueAsLong("Content-Length").orElse(-1L), resp.body())
        }
    }
}
