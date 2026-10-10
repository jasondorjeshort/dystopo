package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.packs.PackArchiveInfo
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.PackCoverage
import io.github.jasondorjeshort.dystopo.packs.PackFileSet
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackPrecedence
import io.github.jasondorjeshort.dystopo.packs.PackRoleZooms
import io.github.jasondorjeshort.dystopo.packs.VectorProfile
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
 * The region packs the app shows: every usable pack on the device at once (T41), each a Terrarium DEM
 * archive and an optional OSM vector archive, plus glyph PBFs in `files/glyphs/<fontstack>/<range>.pbf`.
 * Packs live in `packs/` under the app's files dir or the app-specific external files dir, named
 * `<name>-dem.pmtiles` and `<name>-outdoors.pmtiles` ([PackNames]); the unnamed pair `dem.pmtiles` +
 * `osm.pmtiles` that tools/android-push-pack.ps1 pushes is the pack `default`.
 *
 * [TileService] asks [demFor] / [osmFor] per tile: the archives are ranked by [PackPrecedence] (vector:
 * outdoors profile over plain OpenMapTiles, then the deeper archive, then the primary pack, then the
 * smaller box), and a tile goes to the first archive that holds all of it, else the first that overlaps.
 * The primary pack ([name], stored in `files/packs/active`, set by
 * [io.github.jasondorjeshort.dystopo.app.packs.PackManager.activate]) is where the map starts, what the
 * contour warm-up prepares and a tie-breaker in the ranking; [dem] and [osm] are its archives.
 */
class PackSet(val regions: List<Region>, val glyphDir: File?, val name: String? = null) {
    /** One pack's opened archives. */
    class Region(val name: String, val dem: Pack?, val osm: Pack?, val primary: Boolean) {
        val demCoverage: PackCoverage? = dem?.let { coverage(name, it, primary, vector = false) }
        val osmCoverage: PackCoverage? = osm?.let { coverage(name, it, primary, vector = true) }
    }

    /** The primary pack (or the first one when none is stored). */
    val primary: Region? = regions.firstOrNull { it.name == name } ?: regions.firstOrNull()

    /** The primary pack's DEM and vector archives. */
    val dem: Pack? get() = primary?.dem
    val osm: Pack? get() = primary?.osm

    private val demRegions = PackPrecedence.sort(regions.filter { it.dem != null }) { it.demCoverage!! }
    private val osmRegions = PackPrecedence.sort(regions.filter { it.osm != null }) { it.osmCoverage!! }
    private val demCoverage = demRegions.map { it.demCoverage!! }
    private val osmCoverage = osmRegions.map { it.osmCoverage!! }

    /** DEM and vector archives of every pack, in precedence order. */
    val demPacks: List<Pack> = demRegions.map { it.dem!! }
    val osmPacks: List<Pack> = osmRegions.map { it.osm!! }

    /** The pack name an archive belongs to. */
    fun packOf(p: Pack): String? = regions.firstOrNull { it.dem === p || it.osm === p }?.name

    /** The DEM archive that answers tile z/x/y (role DEM, or CONTOUR for 512-px contour tiles); null outside every pack. */
    fun demFor(z: Int, x: Int, y: Int, role: PackRoleZooms = PackRoleZooms.DEM): Pack? =
        PackPrecedence.choose(demCoverage, z, x, y, role).takeIf { it >= 0 }?.let { demPacks[it] }

    /** Every DEM archive overlapping tile z/x/y, best first (the next ones are fallbacks when the first has nothing there). */
    fun demCandidates(z: Int, x: Int, y: Int, role: PackRoleZooms = PackRoleZooms.DEM): List<Pack> =
        PackPrecedence.candidates(demCoverage, z, x, y, role).map { demPacks[it] }

    /** True when contour tile z/x/y lies wholly inside one DEM archive. */
    fun demContains(z: Int, x: Int, y: Int, role: PackRoleZooms = PackRoleZooms.CONTOUR): Boolean =
        demCoverage.any { PackPrecedence.contains(it, z, x, y, role) }

    /** The vector archive that answers tile z/x/y, or null. */
    fun osmFor(z: Int, x: Int, y: Int): Pack? =
        PackPrecedence.choose(osmCoverage, z, x, y, PackRoleZooms.VECTOR).takeIf { it >= 0 }?.let { osmPacks[it] }

    fun osmCandidates(z: Int, x: Int, y: Int): List<Pack> =
        PackPrecedence.candidates(osmCoverage, z, x, y, PackRoleZooms.VECTOR).map { osmPacks[it] }

    /** Every opened archive. */
    val archives: List<Pack> get() = regions.flatMap { listOfNotNull(it.dem, it.osm) }

    fun close() = archives.forEach { runCatching { it.close() } }

    /** `/health`, `/stats`: the packs in use, in DEM precedence order, with their ranking facts. */
    fun json(): List<Map<String, Any?>> = regions.map { r ->
        linkedMapOf(
            "name" to r.name, "primary" to (r === primary),
            "demRank" to demRegions.indexOf(r).takeIf { it >= 0 }, "osmRank" to osmRegions.indexOf(r).takeIf { it >= 0 },
            "osmProfile" to r.osmCoverage?.profile?.label,
            "dem" to r.dem?.file?.name, "osm" to r.osm?.file?.name,
        )
    }

    companion object {
        const val PACKS_DIR = "packs"
        const val ACTIVE_FILE = "active"

        private fun coverage(pack: String, p: Pack, primary: Boolean, vector: Boolean): PackCoverage {
            val h = p.header
            return PackCoverage(
                pack = pack, minZoom = h.minZoom, maxZoom = h.maxZoom,
                bounds = PackBounds(h.minLon, h.minLat, h.maxLon, h.maxLat),
                profile = if (vector) PackPrecedence.vectorProfile(p.metadata) else VectorProfile.UNKNOWN,
                primary = primary,
                wholeTiles = vector && PackPrecedence.wholeTiles(p.metadata),
            )
        }

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

        /**
         * Opens every pack that has a DEM whose archives open and are complete (a truncated vector archive is
         * left out, the pack's DEM still shows). [skip] names packs to leave closed (being deleted).
         */
        fun open(roots: List<File>, skip: Set<String> = emptySet()): PackSet {
            val packs = catalog(roots).filter { it.name !in skip }
            val stored = PackNames.chooseActive(packs.map { PackFileSet(it.name, it.dem?.name, it.vector?.name) }, storedActive(roots))
            val glyphs = roots.map { File(it, "glyphs") }.firstOrNull { it.isDirectory }
            fun open(file: File?, label: String): Pack? {
                val f = file ?: return null
                val p = runCatching { Pack(label, f) }.getOrNull() ?: return null
                if (f.length() < PackArchiveInfo.expectedBytes(p.header)) {
                    p.close()
                    return null
                }
                return p
            }
            val regions = packs.mapNotNull { set ->
                val dem = open(set.dem, "dem") ?: return@mapNotNull null
                Region(set.name, dem, open(set.vector, "osm"), primary = set.name == stored)
            }
            val primary = stored?.takeIf { s -> regions.any { it.name == s } } ?: regions.firstOrNull()?.name
            return PackSet(regions, glyphs, primary)
        }
    }
}

/** A pack's files on disk (either may be missing). */
class PackFiles(val name: String, val dem: File?, val vector: File?) {
    val files: List<File> get() = listOfNotNull(dem, vector)
}
