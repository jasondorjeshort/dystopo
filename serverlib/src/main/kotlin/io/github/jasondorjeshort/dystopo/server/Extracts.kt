package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

/**
 * One regional OpenStreetMap extract on the server's disk (a Geofabrik `.osm.pbf`, ODbL), used for
 * `POST /build?profile=outdoors` boxes too big for Overpass ([BuildRoutes], T64).
 *
 * @param bbox  what the extract covers (west, south, east, north). For a Geofabrik file this is the
 *              bounding box of the region's polygon, so a box near a state border can be "covered" while
 *              the data beyond the border is missing; pick extracts whose interior holds the boxes you build.
 * @param date  the OSM data's date (ISO-8601 instant): the PBF header's replication timestamp, a sidecar's
 *              `date`, or the file's modification time, as [dateSource] says.
 */
class OsmExtract(
    val name: String,
    val file: File,
    val bbox: BoundingBox,
    val date: String?,
    val dateSource: String,
    val bboxSource: String,
) {
    val bytes: Long get() = file.length()

    /** The extract holds all of [box]. */
    fun covers(box: BoundingBox): Boolean =
        box.west >= bbox.west && box.east <= bbox.east && box.south >= bbox.south && box.north <= bbox.north

    val areaSqDeg: Double get() = (bbox.east - bbox.west) * (bbox.north - bbox.south)

    /** `washington 2026-10-08`: what the job and the phone show. */
    val label: String get() = name + (date?.let { " " + it.take(10) } ?: "")

    fun json(): Map<String, Any?> = linkedMapOf(
        "name" to name, "file" to file.name, "bytes" to bytes, "date" to date, "dateSource" to dateSource,
        "bbox" to listOf(bbox.west, bbox.south, bbox.east, bbox.north), "bboxSource" to bboxSource,
    )
}

/**
 * The OSM extracts the server can build outdoors packs from (T64): the ones listed in the config
 * (`extracts`: path, optional bbox and name) plus every `*.osm.pbf` in [dir] (default `<cache>/extracts/`),
 * rescanned on each call so a file dropped in is used without a restart (parsed headers are cached by
 * path, size and modification time).
 *
 * For each file the bbox and date come from, in order: a sidecar JSON next to it (`<base>.json` or
 * `<file>.json`, keys `name`, `bbox` `[w,s,e,n]`, `date`, all optional), the PBF header (Geofabrik writes
 * the region's bbox and the replication timestamp of the data), and for the date the file's mtime.
 * A file with no bbox anywhere is listed under [problems] and never used.
 */
class Extracts(
    private val configured: List<Configured> = emptyList(),
    val dir: File? = Caches.dir(DIR_NAME),
) {
    /** A config entry: [path] to an `.osm.pbf`; [bbox] and [name] override what the file says. */
    class Configured(val path: File, val bbox: BoundingBox? = null, val name: String? = null)

    private class Cached(val length: Long, val modified: Long, val extract: OsmExtract?, val problem: String?)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Why a file is not usable, by path, from the last [all]. */
    @Volatile var problems: Map<String, String> = emptyMap()
        private set

    /** Every usable extract: configured ones first, then the discovered ones (by name). */
    fun all(): List<OsmExtract> {
        val found = LinkedHashMap<String, OsmExtract>()
        val issues = LinkedHashMap<String, String>()
        for (c in configured) {
            val (e, p) = load(c.path, c.bbox, c.name)
            if (e != null) found[c.path.absoluteFile.normalize().path] = e
            if (p != null) issues[c.path.path] = p
        }
        val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(PBF_SUFFIX, ignoreCase = true) }?.sortedBy { it.name }.orEmpty()
        for (f in files) {
            val key = f.absoluteFile.normalize().path
            if (key in found) continue
            val (e, p) = load(f, null, null)
            if (e != null) found[key] = e
            if (p != null) issues[f.path] = p
        }
        problems = issues
        return found.values.toList()
    }

    /** The smallest extract holding all of [box], or null. */
    fun covering(box: BoundingBox): OsmExtract? = all().filter { it.covers(box) }.minByOrNull { it.areaSqDeg }

    fun describe(): Map<String, Any?> = linkedMapOf(
        "dir" to dir?.path,
        "configured" to configured.size,
        "extracts" to all().map { it.json() },
        "problems" to problems,
    )

    private fun load(file: File, bboxOverride: BoundingBox?, nameOverride: String?): Pair<OsmExtract?, String?> {
        if (!file.isFile) return null to "no such file"
        val key = file.absoluteFile.normalize().path + "|" + (bboxOverride?.toString() ?: "") + "|" + (nameOverride ?: "")
        val sidecar = sidecarFor(file)
        val stamp = file.lastModified() xor (sidecar?.lastModified() ?: 0L)
        cache[key]?.let { if (it.length == file.length() && it.modified == stamp) return it.extract to it.problem }
        val result = try {
            val side = sidecar?.let { MiniJson.parseObject(it.readText()) }.orEmpty()
            val header = runCatching { readHeader(file) }
            val sideBox = (side["bbox"] as? List<*>)?.let { l ->
                val v = l.map { (it as? Number)?.toDouble() }
                require(v.size == 4 && v.all { it != null }) { "sidecar ${sidecar?.name}: bbox must be [west, south, east, north]" }
                BoundingBox(v[0]!!, v[1]!!, v[2]!!, v[3]!!)
            }
            val headerBox = header.getOrNull()?.bbox
            val (bbox, bboxSource) = when {
                bboxOverride != null -> bboxOverride to "config"
                sideBox != null -> sideBox to "sidecar"
                headerBox != null -> headerBox to "pbf-header"
                else -> null to null
            }
            if (bbox == null) {
                val why = header.exceptionOrNull()?.let { ": header unreadable (${it.message})" } ?: ""
                null to "no bbox: give one in the config or a sidecar ${baseName(file)}.json {\"bbox\": [w, s, e, n]}$why"
            } else {
                val sideDate = side["date"]?.toString()?.takeIf { it.isNotBlank() }
                val headerDate = header.getOrNull()?.replicationTimestamp?.let { Instant.ofEpochSecond(it).toString() }
                val (date, dateSource) = when {
                    sideDate != null -> sideDate to "sidecar"
                    headerDate != null -> headerDate to "pbf-header"
                    else -> Instant.ofEpochMilli(file.lastModified()).toString() to "file-mtime"
                }
                val name = nameOverride ?: side["name"]?.toString()?.takeIf { it.isNotBlank() } ?: regionName(file)
                OsmExtract(name, file, bbox, date, dateSource, bboxSource!!) to null
            }
        } catch (e: Exception) {
            null to "${e.javaClass.simpleName}: ${e.message}"
        }
        cache[key] = Cached(file.length(), stamp, result.first, result.second)
        return result
    }

    /** What the PBF header says: the bbox (`HeaderBBox`) and the osmosis replication timestamp (seconds). */
    class PbfHeader(val bbox: BoundingBox?, val replicationTimestamp: Long?, val writingProgram: String?, val source: String?)

    companion object {
        const val DIR_NAME = "extracts"
        const val PBF_SUFFIX = ".osm.pbf"

        /** `washington-261008.osm.pbf` or `washington-latest.osm.pbf` -> `washington`. */
        fun regionName(file: File): String =
            baseName(file).replace(Regex("-(latest|\\d{6}|\\d{4}-\\d{2}-\\d{2})$"), "")

        fun baseName(file: File): String = file.name.let {
            if (it.endsWith(PBF_SUFFIX, ignoreCase = true)) it.dropLast(PBF_SUFFIX.length) else it.substringBeforeLast('.')
        }

        private fun sidecarFor(file: File): File? =
            listOf(File(file.parentFile, baseName(file) + ".json"), File(file.parentFile, file.name + ".json")).firstOrNull { it.isFile }

        /** Parses `w,s,e,n` (degrees). */
        fun parseBbox(text: String): BoundingBox {
            val v = text.split(',').map { it.trim().toDoubleOrNull() ?: throw IllegalArgumentException("bbox must be west,south,east,north (got '$text')") }
            require(v.size == 4) { "bbox must be west,south,east,north (got '$text')" }
            return BoundingBox(v[0], v[1], v[2], v[3])
        }

        /**
         * Reads the first blob of an `.osm.pbf` (the `OSMHeader`) with a minimal protobuf decoder: the
         * format is a 4-byte big-endian `BlobHeader` length, the `BlobHeader`, then a `Blob` (raw or zlib)
         * holding the `HeaderBlock`. Only the fields used here are decoded.
         */
        fun readHeader(file: File): PbfHeader = DataInputStream(file.inputStream().buffered()).use { input ->
            val headerLength = input.readInt()
            if (headerLength !in 1..64 * 1024) throw IOException("not an OSM PBF file (BlobHeader length $headerLength)")
            val blobHeader = ByteArray(headerLength).also { input.readFully(it) }
            var type: String? = null
            var dataSize = -1
            Proto(blobHeader).forEach { field, wire, p ->
                when (field) {
                    1 -> type = p.string()
                    3 -> dataSize = p.varint().toInt()
                    else -> p.skip(wire)
                }
            }
            if (type != "OSMHeader") throw IOException("first blob is '$type', not OSMHeader")
            if (dataSize !in 1..32 * 1024 * 1024) throw IOException("bad header blob size $dataSize")
            val blob = ByteArray(dataSize).also { input.readFully(it) }
            var raw: ByteArray? = null
            var rawSize = 0
            var zlib: ByteArray? = null
            Proto(blob).forEach { field, wire, p ->
                when (field) {
                    1 -> raw = p.bytes()
                    2 -> rawSize = p.varint().toInt()
                    3 -> zlib = p.bytes()
                    else -> p.skip(wire)
                }
            }
            val block = raw ?: zlib?.let { z ->
                val inflater = Inflater()
                try {
                    inflater.setInput(z)
                    val out = ByteArray(rawSize)
                    var n = 0
                    while (n < rawSize && !inflater.finished()) {
                        val k = inflater.inflate(out, n, rawSize - n)
                        if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                        n += k
                    }
                    out.copyOf(n)
                } finally {
                    inflater.end()
                }
            } ?: throw IOException("header blob has no data (lzma and other codecs are not read)")
            var bbox: BoundingBox? = null
            var ts: Long? = null
            var program: String? = null
            var source: String? = null
            Proto(block).forEach { field, wire, p ->
                when (field) {
                    1 -> {
                        val b = LongArray(4)
                        Proto(p.bytes()).forEach { f, w, q -> if (f in 1..4) b[f - 1] = q.sint() else q.skip(w) }
                        // HeaderBBox: left, right, top, bottom in nanodegrees.
                        val (left, right, top, bottom) = b.map { it / 1e9 }
                        if (left < right && bottom < top) bbox = BoundingBox(left, bottom, right, top)
                    }
                    16 -> program = p.string()
                    17 -> source = p.string()
                    32 -> ts = p.varint()
                    else -> p.skip(wire)
                }
            }
            PbfHeader(bbox, ts, program, source)
        }

        internal fun fmt(d: Double) = String.format(Locale.ROOT, "%.4f", d)
    }

    /** A protobuf message reader over [buf]: just varints, zigzag, length-delimited and skipping. */
    internal class Proto(private val buf: ByteArray) {
        private var pos = 0

        fun forEach(each: (field: Int, wire: Int, p: Proto) -> Unit) {
            while (pos < buf.size) {
                val tag = varint()
                each((tag ushr 3).toInt(), (tag and 7).toInt(), this)
            }
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= buf.size) throw IOException("truncated varint")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("varint too long")
            }
        }

        fun sint(): Long = varint().let { (it ushr 1) xor -(it and 1) }

        fun bytes(): ByteArray {
            val n = varint().toInt()
            if (n < 0 || pos + n > buf.size) throw IOException("truncated field")
            return buf.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun string(): String = bytes().toString(Charsets.UTF_8)

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> throw IOException("unsupported wire type $wire")
            }
        }
    }
}
