package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * `GET /catalog` (T52): every region pack in [outDir] that a client can download, so a phone can
 * browse a server's packs instead of building per view. A pack is `<name>-dem.pmtiles` plus an
 * optional `<name>-outdoors.pmtiles` (or `-osm`), grouped by [PackNames]; files still being written
 * (`*.building`) are not listed, nor a vector archive without its DEM.
 *
 * Per pack: `id` (the pack name: stable across rebuilds, unique on one server), `name`, `bounds`
 * (`[west, south, east, north]`, the union of its archives), `center`, `minZoom`/`maxZoom` (the DEM's),
 * `profile` (the vector archive's `dystopo:profile`: `outdoors` or `openmaptiles`; null without one),
 * `bytes` (all files), `builtAt` (ISO-8601 UTC, the newest file's modification time: when the pack
 * was published in `out/`), `provenance` (registry ids from the archives' metadata), `attribution`,
 * `license`, `contentHash` (`sha256:` over the files' hashes, see [packHash]: changes whenever any
 * file changes) and `files` (role, file name, `/out/` URL, bytes, `sha256`, tile type, zooms, bounds).
 *
 * File hashes are SHA-256 of the whole file, computed once and cached next to it as
 * `<file>.sha256` (`sha256sum` format, so `sha256sum -c` checks a download); a sidecar older than its
 * file is recomputed. [warm] fills missing sidecars in the background at startup.
 */
class PackCatalog(private val outDir: File, private val urlPrefix: String = "/out/") {
    /** One archive's description, cached by (length, modification time). */
    private class Described(val length: Long, val modified: Long, val json: Map<String, Any?>, val registryIds: List<String>, val profile: String?, val bounds: List<Double>)

    private val described = ConcurrentHashMap<String, Described>()
    private val hashLocks = ConcurrentHashMap<String, Any>()

    @Volatile var hashesComputed = 0L
        private set

    /** The catalog document: `version`, `generatedAt`, `packs`. */
    fun json(): Map<String, Any?> = linkedMapOf(
        "version" to VERSION,
        "generatedAt" to Instant.now().toString(),
        "packs" to packs(),
    )

    fun packs(): List<Map<String, Any?>> {
        val names = outDir.list()?.toList() ?: emptyList()
        return PackNames.group(names).filter { it.dem != null && !it.name.startsWith(".") }.mapNotNull { set ->
            runCatching { entry(set.name, File(outDir, set.dem!!), set.vector?.let { File(outDir, it) }) }.getOrNull()
        }
    }

    private fun entry(name: String, dem: File, vector: File?): Map<String, Any?> {
        val d = describe(dem, PackRole.DEM)
        val v = vector?.takeIf { it.isFile }?.let { describe(it, PackRole.VECTOR) }
        val parts = listOfNotNull(d, v)
        val ids = SourceRegistry.builtIn.ordered(parts.flatMap { it.registryIds }.distinct().filter { it in SourceRegistry.builtIn })
        val b = parts.map { it.bounds }
        val bounds = listOf(b.minOf { it[0] }, b.minOf { it[1] }, b.maxOf { it[2] }, b.maxOf { it[3] })
        val files = parts.map { it.json + ("sha256" to sha256(File(outDir, it.json["file"] as String))) }
        return linkedMapOf(
            "id" to name,
            "name" to name,
            "bounds" to bounds,
            "center" to listOf((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2),
            "minZoom" to d.json["minZoom"],
            "maxZoom" to d.json["maxZoom"],
            "profile" to v?.profile,
            "bytes" to parts.sumOf { it.length },
            "builtAt" to Instant.ofEpochMilli(parts.maxOf { it.modified }).toString(),
            "provenance" to ids,
            "attribution" to SourceRegistry.builtIn.attribution(ids),
            "license" to TileSets.licenseExpression(ids),
            "contentHash" to packHash(files.map { it["role"] as String to it["sha256"] as String }),
            "files" to files,
        )
    }

    private fun describe(file: File, role: PackRole): Described {
        val length = file.length()
        val modified = file.lastModified()
        described[file.name]?.takeIf { it.length == length && it.modified == modified }?.let { return it }
        val reader = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors)
        val h = reader.header
        val meta = runCatching { reader.metadata() }.getOrDefault(emptyMap())
        val bounds = listOf(h.minLon, h.minLat, h.maxLon, h.maxLat)
        val json = linkedMapOf<String, Any?>(
            "role" to role.label,
            "file" to file.name,
            "url" to urlPrefix + file.name,
            "bytes" to length,
            "tileType" to h.tileType.label,
            "minZoom" to h.minZoom,
            "maxZoom" to h.maxZoom,
            "bounds" to bounds,
            "modified" to Instant.ofEpochMilli(modified).toString(),
        )
        val ids = runCatching { PackAttribution.registryIds(meta) }.getOrDefault(emptyList()).ifEmpty {
            if (role == PackRole.DEM) emptyList() else LiveTiles.OSM_PACK_REGISTRY_IDS
        }
        val profile = if (role == PackRole.VECTOR) profileOf(meta) else null
        return Described(length, modified, json, ids, profile, bounds).also { described[file.name] = it }
    }

    /** SHA-256 of [file] (lowercase hex), from its `.sha256` sidecar when that is at least as new as the file. */
    fun sha256(file: File): String = synchronized(hashLocks.computeIfAbsent(file.canonicalPath) { Any() }) {
        val side = sidecar(file)
        if (side.isFile && side.lastModified() >= file.lastModified()) {
            side.readText().trim().substringBefore(' ').takeIf { HEX64.matches(it) }?.let { return it }
        }
        val hex = hashFile(file)
        hashesComputed++
        // Written via a temp file and a rename, so a reader never sees half a line.
        runCatching { Http.partFile(side).also { it.writeText("$hex  ${file.name}\n") }.let { tmp -> side.delete(); Http.publish(tmp, side) } }
        hex
    }

    /** Computes every missing or stale hash on a background thread (the first `/catalog` is then fast). */
    fun warm(): Thread = Thread({
        for (set in PackNames.group(outDir.list()?.toList() ?: emptyList())) {
            for (f in listOfNotNull(set.dem, set.vector)) runCatching { sha256(File(outDir, f)) }
        }
    }, "catalog-hash").apply { isDaemon = true; start() }

    companion object {
        const val VERSION = 1
        private val HEX64 = Regex("[0-9a-f]{64}")

        fun sidecar(file: File) = File(file.path + ".sha256")

        /**
         * A vector archive's profile: its `dystopo:profile`; for archives from before that key, `outdoors`
         * when it has the outdoors-only `outdoor_poi` layer, else `openmaptiles`.
         */
        fun profileOf(meta: Map<String, Any?>): String {
            (meta["dystopo:profile"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            val layers = (meta["vector_layers"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String } ?: emptyList()
            return if ("outdoor_poi" in layers) BuildRoutes.PROFILE_OUTDOORS else BuildRoutes.PROFILE_OPENMAPTILES
        }

        fun hashFile(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * The pack's content hash: `sha256:` + SHA-256 of the lines `<role>:<file sha256>\n` in role
         * order (dem, then outdoors). Two servers holding byte-identical files give the same hash.
         */
        fun packHash(files: List<Pair<String, String>>): String {
            val text = files.sortedBy { if (it.first == PackRole.DEM.label) 0 else 1 }.joinToString("") { "${it.first}:${it.second}\n" }
            val md = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return "sha256:" + md.joinToString("") { "%02x".format(it) }
        }
    }
}
