package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.packs.PackFileSet
import io.github.jasondorjeshort.dystopo.packs.PackNames
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
 * The region pack the app shows: a Terrarium DEM archive and an optional OSM vector archive, plus glyph
 * PBFs in `files/glyphs/<fontstack>/<range>.pbf`. Packs live in `packs/` under the app's files dir or
 * the app-specific external files dir, named `<name>-dem.pmtiles` and `<name>-outdoors.pmtiles`
 * ([PackNames]); the unnamed pair `dem.pmtiles` + `osm.pmtiles` that tools/android-push-pack.ps1 pushes
 * is the pack `default`. The active pack's name is stored in `files/packs/active`
 * ([io.github.jasondorjeshort.dystopo.app.packs.PackManager] changes it); without one, `default` or the
 * first pack by name is shown.
 */
class PackSet(val dem: Pack?, val osm: Pack?, val glyphDir: File?, val name: String? = null) {
    companion object {
        const val PACKS_DIR = "packs"
        const val ACTIVE_FILE = "active"

        /** Every pack in [roots] (`<root>/packs/`), the first root winning when a file name repeats. */
        fun catalog(roots: List<File>): List<PackFiles> {
            val files = LinkedHashMap<String, File>()
            for (root in roots) {
                File(root, PACKS_DIR).listFiles()?.filter { it.isFile }?.forEach { f -> if (f.name !in files) files[f.name] = f }
            }
            return PackNames.group(files.keys).map { set -> PackFiles(set.name, set.dem?.let(files::get), set.vector?.let(files::get)) }
        }

        fun storedActive(roots: List<File>): String? =
            roots.firstOrNull()?.let { File(it, "$PACKS_DIR/$ACTIVE_FILE") }?.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

        fun storeActive(roots: List<File>, name: String) {
            val f = File(roots.first(), "$PACKS_DIR/$ACTIVE_FILE")
            f.parentFile?.mkdirs()
            f.writeText(name + "\n")
        }

        fun open(roots: List<File>): PackSet {
            val packs = catalog(roots)
            val active = PackNames.chooseActive(packs.map { PackFileSet(it.name, it.dem?.name, it.vector?.name) }, storedActive(roots))
            val glyphs = roots.map { File(it, "glyphs") }.firstOrNull { it.isDirectory }
            val set = packs.firstOrNull { it.name == active } ?: return PackSet(null, null, glyphs)
            fun open(file: File?, label: String) = file?.let { f -> runCatching { Pack(label, f) }.getOrNull() }
            return PackSet(dem = open(set.dem, "dem"), osm = open(set.vector, "osm"), glyphDir = glyphs, name = active)
        }
    }
}

/** A pack's files on disk (either may be missing). */
class PackFiles(val name: String, val dem: File?, val vector: File?) {
    val files: List<File> get() = listOfNotNull(dem, vector)
}
