package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/** Positional reads from a local file; thread-safe (FileChannel positional reads don't move a shared cursor). */
class FileRangeSource(val file: File) : ByteRangeSource, AutoCloseable {
    private val channel: FileChannel = RandomAccessFile(file, "r").channel
    val size: Long = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        val n = minOf(length.toLong(), (size - offset).coerceAtLeast(0)).toInt()
        val buf = ByteBuffer.allocate(n)
        var pos = offset
        while (buf.hasRemaining()) {
            val r = channel.read(buf, pos)
            if (r < 0) break
            pos += r
        }
        return buf.array().copyOf(buf.position())
    }

    override fun close() = channel.close()
}

val gunzip: (ByteArray) -> ByteArray = { bytes -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } }

/**
 * One opened PMTiles archive. [PmtilesReader] caches leaf directories in a plain HashMap, so every
 * read goes through [lock].
 */
class Pack(val name: String, val file: File) : AutoCloseable {
    private val source = FileRangeSource(file)
    private val reader = PmtilesReader.open(source, mapOf(Compression.GZIP to gunzip))
    val header get() = reader.header
    val lock = Any()

    /** Tile bytes with the archive's tile compression undone, or null when absent. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? = synchronized(lock) { reader.tile(z, x, y) }

    val metadata: Map<String, Any?> by lazy { synchronized(lock) { runCatching { reader.metadata() }.getOrDefault(emptyMap()) } }

    /** The `attribution` metadata field with any HTML tags removed. */
    val attribution: String
        get() = (metadata["attribution"] as? String).orEmpty().replace(Regex("<[^>]*>"), "").trim()

    override fun close() = source.close()
}

/**
 * The region pack the app shows: a Terrarium DEM archive and an optional OSM vector archive, found in
 * the app's files dir (`files/packs/dem.pmtiles`, `files/packs/osm.pmtiles`; tools/android-push-pack.ps1
 * puts them there) or the app-specific external files dir (same layout), plus glyph PBFs in
 * `files/glyphs/<fontstack>/<range>.pbf`.
 */
class PackSet(val dem: Pack?, val osm: Pack?, val glyphDir: File?) {
    companion object {
        fun open(roots: List<File>): PackSet {
            fun find(name: String): File? = roots.map { File(it, "packs/$name") }.firstOrNull { it.isFile }
            val glyphs = roots.map { File(it, "glyphs") }.firstOrNull { it.isDirectory }
            return PackSet(
                dem = find("dem.pmtiles")?.let { Pack("dem", it) },
                osm = find("osm.pmtiles")?.let { Pack("osm", it) },
                glyphDir = glyphs,
            )
        }
    }
}
