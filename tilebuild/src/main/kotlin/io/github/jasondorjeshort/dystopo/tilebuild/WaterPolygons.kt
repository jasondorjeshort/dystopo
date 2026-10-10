package io.github.jasondorjeshort.dystopo.tilebuild

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * The ocean polygons for a small build box (T50). Planetiler reads OSM's `water-polygons-split-3857`
 * shapefile (931 MB zipped, 1.27 GB of geometry, 14,509 records) through a zip file system, inflating all of it
 * on every build even when the box is far from the sea: that was 15 to 35 s of a 20 to 40 s server-side outdoors
 * build. Here instead:
 * - once per downloaded zip, one pass over the `.shp` records' bounding boxes writes a small index
 *   ([INDEX_NAME], 44 bytes per record) next to the zip;
 * - per build box the index says which records reach the box: none (an inland box, the usual case for
 *   mountain packs) and the source is [Skip]ped, the output being identical (Planetiler drops shapes outside
 *   its bounds anyway); otherwise those records are copied into a small shapefile ([Clipped]) cached per box
 *   under `planetiler/water-clip/`, whole records byte for byte (same geometry, same attributes), so the
 *   result is the same as reading the full file.
 * Without a zip yet (first build ever) or a bbox, Planetiler gets the full file as before ([Full]).
 */
object WaterPolygons {
    const val SHP = "water-polygons-split-3857/water_polygons.shp"
    const val SHX = "water-polygons-split-3857/water_polygons.shx"
    const val DBF = "water-polygons-split-3857/water_polygons.dbf"
    const val PRJ = "water-polygons-split-3857/water_polygons.prj"
    const val CPG = "water-polygons-split-3857/water_polygons.cpg"
    const val INDEX_NAME = "water-polygons-split-3857.index"
    private const val INDEX_MAGIC = 0x57504931 // "WPI1"

    /** Clipped copies kept; older ones are deleted when a new one is written. */
    const val KEEP_CLIPS = 24

    sealed interface Choice {
        val describe: String
    }

    /** No water polygon reaches the box: leave the source out. */
    data class Skip(val records: Int) : Choice {
        override val describe get() = "skipped: none of $records water polygons reaches the box"
    }

    /** A shapefile holding only the [used] records that reach the box. */
    data class Clipped(val shp: File, val used: Int, val records: Int, val cached: Boolean) : Choice {
        override val describe get() = "clipped: $used of $records water polygons (${if (cached) "cached" else "written"} ${shp.parentFile.name})"
    }

    data object Full : Choice {
        override val describe get() = "full shapefile"
    }

    /** One `.shp` record: byte offset of its 8-byte header, content length in bytes, bounding box in EPSG:3857 metres. */
    data class Record(val offset: Long, val contentBytes: Int, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    /**
     * The choice for a build over [bbox] (west, south, east, north in degrees) with the polygons in [zip];
     * the index and clipped copies live in [cacheDir].
     */
    fun forBox(zip: File, cacheDir: File, bbox: DoubleArray): Choice {
        if (!zip.isFile) return Full
        val records = index(zip, cacheDir)
        val (x0, y0) = mercator(bbox[0], bbox[1])
        val (x1, y1) = mercator(bbox[2], bbox[3])
        // A little margin, so rounding never drops a polygon Planetiler's own bounds test would keep.
        val pad = 0.01 * maxOf(x1 - x0, y1 - y0) + 50.0
        val used = records.withIndex().filter { (_, r) -> r.maxX >= x0 - pad && r.minX <= x1 + pad && r.maxY >= y0 - pad && r.minY <= y1 + pad }
        if (used.isEmpty()) return Skip(records.size)
        val key = "%.4f_%.4f_%.4f_%.4f".format(java.util.Locale.ROOT, bbox[0], bbox[1], bbox[2], bbox[3]) + "-" + zip.length().toString(36)
        val dir = File(cacheDir, "water-clip/$key")
        val shp = File(dir, "water_polygons.shp")
        if (shp.isFile) {
            dir.setLastModified(System.currentTimeMillis())
            return Clipped(shp, used.size, records.size, cached = true)
        }
        writeClip(zip, dir, used.map { it.index }, records)
        prune(File(cacheDir, "water-clip"), keep = KEEP_CLIPS)
        return Clipped(shp, used.size, records.size, cached = false)
    }

    /** Web Mercator metres for a longitude/latitude (latitude clamped to the projection's range). */
    fun mercator(lon: Double, lat: Double): Pair<Double, Double> {
        val r = 6378137.0
        val phi = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180
        return lon * PI / 180 * r to r * ln(tan(PI / 4 + phi / 2))
    }

    /** The record index of [zip], read from [cacheDir] or built with one pass over the `.shp` (and saved). */
    fun index(zip: File, cacheDir: File): List<Record> {
        val file = File(cacheDir, INDEX_NAME)
        readIndex(file, zip)?.let { return it }
        val records = ZipFile(zip).use { z ->
            val shx = z.getInputStream(z.getEntry(SHX) ?: error("$SHX missing in $zip")).use { it.readBytes() }
            z.getInputStream(z.getEntry(SHP) ?: error("$SHP missing in $zip")).use { scan(it, shx) }
        }
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "$INDEX_NAME.${ProcessHandle.current().pid()}.part")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(INDEX_MAGIC)
            out.writeLong(zip.length())
            out.writeLong(zip.lastModified())
            out.writeInt(records.size)
            for (r in records) {
                out.writeLong(r.offset); out.writeInt(r.contentBytes)
                out.writeDouble(r.minX); out.writeDouble(r.minY); out.writeDouble(r.maxX); out.writeDouble(r.maxY)
            }
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return records
    }

    private fun readIndex(file: File, zip: File): List<Record>? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != INDEX_MAGIC || inp.readLong() != zip.length() || inp.readLong() != zip.lastModified()) return null
                List(inp.readInt()) { Record(inp.readLong(), inp.readInt(), inp.readDouble(), inp.readDouble(), inp.readDouble(), inp.readDouble()) }
            }
        }.getOrNull()
    }

    /**
     * Reads every record's bounding box from a `.shp` stream, using the `.shx` ([shx]) for the record offsets
     * and lengths, so only 44 bytes of each record are looked at and the rest is skipped.
     */
    fun scan(shp: InputStream, shx: ByteArray): List<Record> {
        val n = (shx.size - 100) / 8
        val out = ArrayList<Record>(n)
        val head = ByteArray(44)
        var pos = 0L
        val stream = shp.buffered(1 shl 16)
        for (i in 0 until n) {
            val off = ByteBuffer.wrap(shx, 100 + 8 * i, 8).order(ByteOrder.BIG_ENDIAN)
            val offset = off.int.toLong() * 2
            val contentBytes = off.int * 2
            stream.skipNBytes(offset - pos)
            pos = offset
            val take = minOf(44, 8 + contentBytes)
            stream.readNBytes(head, 0, take).also { require(it == take) { "water polygons: .shp ends inside record $i" } }
            pos += take
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val type = b.getInt(8)
            out += if (type == 0 || contentBytes < 36) {
                Record(offset, contentBytes, Double.NaN, Double.NaN, Double.NaN, Double.NaN) // null shape: never selected
            } else {
                Record(offset, contentBytes, b.getDouble(12), b.getDouble(20), b.getDouble(28), b.getDouble(36))
            }
        }
        return out
    }

    /** Writes a shapefile of [keep] (record numbers, ascending) from [zip] into [dir], atomically. */
    fun writeClip(zip: File, dir: File, keep: List<Int>, records: List<Record>) {
        dir.parentFile.mkdirs()
        val tmp = File(dir.parentFile, dir.name + ".${ProcessHandle.current().pid()}-${Thread.currentThread().threadId()}.part")
        tmp.deleteRecursively()
        tmp.mkdirs()
        ZipFile(zip).use { z ->
            fun bytes(name: String) = z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
            val shpHeader: ByteArray
            val shpBody = java.io.ByteArrayOutputStream()
            val shx = java.io.ByteArrayOutputStream()
            z.getInputStream(z.getEntry(SHP)).use { raw ->
                val s = raw.buffered(1 shl 16)
                shpHeader = s.readNBytes(100)
                var pos = 100L
                for (i in keep) {
                    val r = records[i]
                    s.skipNBytes(r.offset - pos)
                    val rec = s.readNBytes(8 + r.contentBytes)
                    require(rec.size == 8 + r.contentBytes) { "water polygons: .shp ends inside record $i" }
                    pos = r.offset + rec.size
                    val newOffset = 100 + shpBody.size()
                    ByteBuffer.wrap(rec).order(ByteOrder.BIG_ENDIAN).putInt(0, shx.size() / 8 + 1) // renumber 1..n
                    shx.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(newOffset / 2).putInt(r.contentBytes / 2).array())
                    shpBody.write(rec)
                }
            }
            val box = keep.map { records[it] }
            fun header(totalBytes: Int): ByteArray = shpHeader.copyOf().also { h ->
                val b = ByteBuffer.wrap(h)
                b.order(ByteOrder.BIG_ENDIAN).putInt(24, totalBytes / 2)
                b.order(ByteOrder.LITTLE_ENDIAN)
                b.putDouble(36, box.minOf { it.minX }); b.putDouble(44, box.minOf { it.minY })
                b.putDouble(52, box.maxOf { it.maxX }); b.putDouble(60, box.maxOf { it.maxY })
            }
            File(tmp, "water_polygons.shp").outputStream().use { it.write(header(100 + shpBody.size())); shpBody.writeTo(it) }
            File(tmp, "water_polygons.shx").outputStream().use { it.write(header(100 + shx.size())); shx.writeTo(it) }
            File(tmp, "water_polygons.dbf").writeBytes(clipDbf(bytes(DBF) ?: error("$DBF missing in $zip"), keep))
            bytes(PRJ)?.let { File(tmp, "water_polygons.prj").writeBytes(it) }
            bytes(CPG)?.let { File(tmp, "water_polygons.cpg").writeBytes(it) }
        }
        try {
            Files.move(tmp.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.FileSystemException) {
            // Another build wrote the same box first: keep its copy.
            tmp.deleteRecursively()
            if (!File(dir, "water_polygons.shp").isFile) throw e
        }
    }

    /** The dBASE table [dbf] with only the rows in [keep] (0-based, ascending). */
    fun clipDbf(dbf: ByteArray, keep: List<Int>): ByteArray {
        val b = ByteBuffer.wrap(dbf).order(ByteOrder.LITTLE_ENDIAN)
        val headerLen = b.getShort(8).toInt() and 0xFFFF
        val recordLen = b.getShort(10).toInt() and 0xFFFF
        val out = java.io.ByteArrayOutputStream(headerLen + keep.size * recordLen + 1)
        val header = dbf.copyOfRange(0, headerLen)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, keep.size)
        out.write(header)
        for (i in keep) out.write(dbf, headerLen + i * recordLen, recordLen)
        out.write(0x1A)
        return out.toByteArray()
    }

    /** Deletes all but the [keep] most recently used clip directories in [dir]. */
    private fun prune(dir: File, keep: Int) {
        val dirs = dir.listFiles { f -> f.isDirectory && !f.name.endsWith(".part") }?.sortedByDescending { it.lastModified() } ?: return
        for (old in dirs.drop(keep)) old.deleteRecursively()
    }
}
