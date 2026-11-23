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
 * [TileService] asks [demFor] / [osmFor] per tile: the archives are ranked by [PackPrecedence.ORDER] (vector:
 * outdoors profile over plain OpenMapTiles, then the primary pack, then the deeper archive, then the
 * smaller box, then the name), and a tile goes to the first archive that holds all of it, else the first that overlaps.
 * The primary pack ([name], stored in `files/packs/active`, set by
 * [io.github.jasondorjeshort.dystopo.app.packs.PackManager.activate]) is where the map starts, what the
 * contour warm-up prepares and a tie-breaker in the ranking; [dem] and [osm] are its archives.
 *
 * T342: a map switched off ("Use on this phone" off) is not opened, so nothing that reads maps through this set (the tile
 * server, the terrain reads, search, routing, peers' tiles) sees it; [off] names those on the phone. The state is a field
 * of the map's area records ([io.github.jasondorjeshort.dystopo.app.areas.Areas.Record.off]) and, for a map no record
 * names, a list beside the stored primary ([OFF_FILE]); a primary that is off leaves the stored choice alone.
 */
class PackSet(val regions: List<Region>, val glyphDir: File?, val name: String? = null, val world: Pack? = null, val notOpened: List<NotOpened> = emptyList(), val off: List<String> = emptyList()) {
    /**
     * T352: an archive [open] left closed: [pack] its pack, [archive] `dem` or `osm`, [file] its name, [reason] why (the
     * exception, or `short: <bytes> of <expected> bytes`), [dropped] whether the whole pack is left out (`GET /packs` `notOpened`).
     */
    class NotOpened(val pack: String, val archive: String, val file: String, val reason: String, val dropped: Boolean) {
        fun json(): Map<String, Any?> = linkedMapOf("pack" to pack, "archive" to archive, "file" to file, "reason" to reason, "dropped" to dropped)
        val line: String get() = "map $pack not opened: $archive $file: $reason" + if (dropped) " (the map is left out)" else " (the map opens without it)"
    }

    /** One pack's opened archives. */
    class Region(val name: String, val dem: Pack?, val osm: Pack?, val primary: Boolean) {
        val demCoverage: PackCoverage? = dem?.let { coverage(name, it, primary, vector = false) }
        val osmCoverage: PackCoverage? = osm?.let { coverage(name, it, primary, vector = true) }
    }

    /** The primary pack (or the first one when none is stored); T65: always one with a DEM (vector-only packs are never primary). */
    val primary: Region? = regions.firstOrNull { it.name == name && it.dem != null } ?: regions.firstOrNull { it.dem != null }

    /** The primary pack's DEM and vector archives. */
    val dem: Pack? get() = primary?.dem
    val osm: Pack? get() = primary?.osm

    private val demRegions = PackPrecedence.sort(regions.filter { it.dem != null }) { it.demCoverage!! }
    private val osmRegions = PackPrecedence.sort(regions.filter { it.osm != null }) { it.osmCoverage!! }
    private val demCoverage = demRegions.map { it.demCoverage!! }

    /** T264: the world overview's coverage, last: it answers a tile only where no pack ahead of it holds the whole tile. */
    private val worldCoverage: PackCoverage? = world?.let { coverage(WORLD_NAME, it, primary = false, vector = true) }
    private val osmCoverage = osmRegions.map { it.osmCoverage!! } + listOfNotNull(worldCoverage)

    /** DEM and vector archives of every pack, in precedence order; T264: the built-in world overview last among the vector ones. */
    val demPacks: List<Pack> = demRegions.map { it.dem!! }
    val osmPacks: List<Pack> = osmRegions.map { it.osm!! } + listOfNotNull(world)

    /** The pack name an archive belongs to (T264: [WORLD_NAME] for the world overview). */
    fun packOf(p: Pack): String? = if (p === world) WORLD_NAME else regions.firstOrNull { it.dem === p || it.osm === p }?.name

    /** T264: the archive answering vector tile z/x/y is the built-in world overview (the chip's "from the world overview"). */
    fun worldAnswers(z: Int, x: Int, y: Int): Boolean = world != null && osmFor(z, x, y) === world

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

    /** Every opened archive of the downloaded packs (T264: the world overview is not one; [close] closes it too). */
    val archives: List<Pack> get() = regions.flatMap { listOfNotNull(it.dem, it.osm) }

    fun close() = (archives + listOfNotNull(world)).forEach { runCatching { it.close() } }

    /**
     * `/health`, `/stats`, `GET /packs`'s `inUse`: the packs in use, in DEM precedence order, with their ranking facts. T264: the
     * built-in world overview last, `builtIn` true (no area row, Storage row or delete reaches it).
     */
    fun json(): List<Map<String, Any?>> = regions.map { r ->
        linkedMapOf(
            "name" to r.name, "primary" to (r === primary),
            "demRank" to demRegions.indexOf(r).takeIf { it >= 0 }, "osmRank" to osmRegions.indexOf(r).takeIf { it >= 0 },
            "osmProfile" to r.osmCoverage?.profile?.label,
            "dem" to r.dem?.file?.name, "osm" to r.osm?.file?.name,
        )
    } + listOfNotNull(
        world?.let { w ->
            linkedMapOf(
                "name" to WORLD_NAME, "primary" to false, "demRank" to null, "osmRank" to osmRegions.size,
                "osmProfile" to worldCoverage?.profile?.label, "dem" to null, "osm" to w.file.name, "builtIn" to true,
                "minZoom" to w.header.minZoom, "maxZoom" to w.header.maxZoom, "bytes" to w.file.length(),
            )
        },
    )

    companion object {
        const val PACKS_DIR = "packs"
        const val ACTIVE_FILE = "active"

        /**
         * T264: the built-in world overview (Natural Earth, z0 to z5, `tilebuild --natural-earth-only`), copied from the APK into
         * the files dir by [io.github.jasondorjeshort.dystopo.app.firstrun.WorldOverview], outside [PACKS_DIR] so no pack list,
         * Storage row or delete sees it.
         */
        const val WORLD_FILE = "world-outdoors.pmtiles"
        const val WORLD_NAME = "world"

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

        /** T342: the list beside [ACTIVE_FILE] of the maps that are off and named by no area record, one name a line. */
        const val OFF_FILE = "off"

        /** T342: the names in [OFF_FILE]; empty when there is none. */
        fun storedOff(roots: List<File>): Set<String> =
            roots.firstOrNull()?.let { File(it, "$PACKS_DIR/$OFF_FILE") }?.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

        /** T342: [name] added to or taken from [OFF_FILE] (the file goes when it would be empty). */
        fun storeOff(roots: List<File>, name: String, off: Boolean) {
            val now = storedOff(roots)
            val next = if (off) now + name else now - name
            if (next == now) return
            val f = File(roots.first(), "$PACKS_DIR/$OFF_FILE")
            if (next.isEmpty()) { f.delete(); return }
            f.parentFile?.mkdirs()
            f.writeText(next.sorted().joinToString("\n", postfix = "\n"))
        }

        /** T342: every map that is off: those in [OFF_FILE] and those an area record marks off. */
        fun offNames(roots: List<File>): Set<String> =
            storedOff(roots) + roots.firstOrNull()?.let { io.github.jasondorjeshort.dystopo.app.areas.offPacksIn(File(it, io.github.jasondorjeshort.dystopo.app.areas.Areas.DIR)) }.orEmpty()

        /**
         * Opens every pack that has a DEM whose archives open and are complete (a truncated vector archive is
         * left out, the pack's DEM still shows), and (T65) every vector-only pack whose archive opens and is
         * complete. [skip] names packs to leave closed (being deleted); T342: [off] the maps that are off, left closed too.
         */
        fun open(roots: List<File>, skip: Set<String> = emptySet(), off: Set<String> = offNames(roots)): PackSet {
            val onPhone = catalog(roots).filter { it.name !in skip }
            val packs = onPhone.filter { it.name !in off } // T342
            val stored = PackNames.chooseActive(packs.map { PackFileSet(it.name, it.dem?.name, it.vector?.name) }, storedActive(roots))
            val glyphs = roots.map { File(it, "glyphs") }.firstOrNull { it.isDirectory }
            val failed = HashMap<File, String>() // T352: why an archive stayed closed
            fun open(file: File?, label: String): Pack? {
                val f = file ?: return null
                val p = try {
                    Pack(label, f)
                } catch (e: Exception) {
                    failed[f] = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                    return null
                }
                val expected = PackArchiveInfo.expectedBytes(p.header)
                if (f.length() < expected) {
                    p.close()
                    failed[f] = "short: ${f.length()} of $expected bytes"
                    return null
                }
                return p
            }
            val notOpened = ArrayList<NotOpened>()
            // T65: a vector-only pack (a server's single-file pack) is opened too: its map data is drawn wherever it is
            // the best vector archive, over another pack's DEM or the online one; it is never primary.
            val regions = packs.mapNotNull { set ->
                val dem = open(set.dem, "dem")
                val osm = open(set.vector, "osm")
                val dropped = dem == null && (set.dem != null || osm == null)
                for ((f, label) in listOf(set.dem to "dem", set.vector to "osm")) if (f != null) failed[f]?.let { notOpened += NotOpened(set.name, label, f.name, it, dropped) }
                if (dropped) {
                    osm?.close()
                    return@mapNotNull null
                }
                Region(set.name, dem, osm, primary = dem != null && set.name == stored)
            }
            val primary = stored?.takeIf { s -> regions.any { it.name == s && it.dem != null } } ?: regions.firstOrNull { it.dem != null }?.name
            // T264: the world overview, from the first root only (where the copy puts it); absent until the first start's copy lands.
            val world = roots.firstOrNull()?.let { File(it, WORLD_FILE) }?.takeIf { it.isFile }?.let { open(it, WORLD_NAME) }
            roots.firstOrNull()?.let { File(it, WORLD_FILE) }?.let { f -> failed[f]?.let { notOpened += NotOpened(WORLD_NAME, "osm", f.name, it, dropped = true) } }
            if (notOpened.isNotEmpty()) runCatching { android.util.Log.w("Dystopo", "PackSet.open: " + notOpened.joinToString("; ") { it.line }) } // T352: one logcat line (none in JVM tests)
            return PackSet(regions, glyphs, primary, world, notOpened, onPhone.map { it.name }.filter { it in off }) // T342: off maps are filtered before opening, so never in notOpened
        }
    }
}

/** A pack's files on disk (either may be missing). */
class PackFiles(val name: String, val dem: File?, val vector: File?) {
    val files: List<File> get() = listOfNotNull(dem, vector)
}
