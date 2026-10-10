package io.github.jasondorjeshort.dystopo.server

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
 * (`*.building`) are not listed. Every other `.pmtiles` file in [outDir] (a vector archive without its
 * DEM, an `-osm` archive shadowed by an `-outdoors` one, a tilebuild output without a role suffix) is a
 * **single-file pack** (T58): `id` and `name` are the file name without `.pmtiles`, `single` is true,
 * and the file's role comes from its tile type (`mvt` is `outdoors`, anything else `dem`). Clients that
 * need a DEM (the phone) skip vector-only entries.
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
 * `<file>.sha256` (`sha256sum` format, so `sha256sum -c` checks a download). The sidecar's
 * modification time is set to its file's, and a sidecar whose time differs from the file's is stale
 * and recomputed (T58: a rebuilt file always gets a new time; `/build` also deletes the sidecars of the
 * files it replaces). Bytes changed in place under an unchanged size and time are only found by
 * [refresh] (`POST /catalog/refresh`), which rehashes every file. [warm] fills missing sidecars in the
 * background at startup; [warmState] says how far it got (`/healthz`).
 */
class PackCatalog(private val outDir: File, private val urlPrefix: String = "/out/") {
    /** One archive's description, cached by (length, modification time). */
    private class Described(val length: Long, val modified: Long, val json: Map<String, Any?>, val registryIds: List<String>, val profile: String?, val bounds: List<Double>, val tileType: String)

    private val described = ConcurrentHashMap<String, Described>()
    private val hashLocks = ConcurrentHashMap<String, Any>()

    @Volatile var hashesComputed = 0L
        private set

    @Volatile private var warmStarted = 0L
    @Volatile private var warmEnded = 0L
    @Volatile private var warmTotal = 0
    private val warmDone = java.util.concurrent.atomic.AtomicInteger()

    /** One catalogued pack's files, by role. */
    private class PackFiles(val name: String, val single: Boolean, val files: List<Pair<PackRole, File>>)

    /** The catalog document: `version`, `generatedAt`, `packs`. */
    fun json(): Map<String, Any?> = linkedMapOf(
        "version" to VERSION,
        "generatedAt" to Instant.now().toString(),
        "packs" to packs(),
    )

    /**
     * The catalog routes of both `serve` modes and the server: `GET /catalog` (or `/catalog.json`) and
     * `POST /catalog/refresh` ([refresh]). Returns (status, body), or null for any other path.
     */
    fun route(path: String, method: String): Pair<Int, Map<String, Any?>>? = when (path) {
        "/catalog", "/catalog.json" -> 200 to json()
        "/catalog/refresh" -> if (method == "POST") 200 to refresh() else 405 to mapOf("error" to "use POST /catalog/refresh", "status" to 405)
        else -> null
    }

    fun packs(): List<Map<String, Any?>> =packFiles().mapNotNull { p -> runCatching { entry(p) }.getOrNull() }

    /** Pairs first (a DEM plus its vector archive), then every other `.pmtiles` file as a single-file pack. */
    private fun packFiles(): List<PackFiles> {
        val names = (outDir.list()?.toList() ?: emptyList()).filter { !it.startsWith(".") && File(outDir, it).isFile }
        val pairs = PackNames.group(names).filter { it.dem != null }
        val used = pairs.flatMap { listOfNotNull(it.dem, it.vector) }.toSet()
        val paired = pairs.map { s ->
            PackFiles(s.name, false, listOfNotNull(PackRole.DEM to File(outDir, s.dem!!), s.vector?.let { PackRole.VECTOR to File(outDir, it) }))
        }
        val singles = names.filter { it.endsWith(".pmtiles") && it !in used }.mapNotNull { f ->
            val stem = f.removeSuffix(".pmtiles")
            if (!PackNames.isValidName(stem)) return@mapNotNull null
            val role = runCatching { if (describe(File(outDir, f), null).tileType == "mvt") PackRole.VECTOR else PackRole.DEM }.getOrNull()
                ?: return@mapNotNull null
            PackFiles(stem, true, listOf(role to File(outDir, f)))
        }
        return (paired + singles).sortedBy { it.name }
    }

    private fun entry(p: PackFiles): Map<String, Any?> {
        val parts = p.files.map { (role, f) -> describe(f, role) }
        val d = parts.firstOrNull { it.json["role"] == PackRole.DEM.label } ?: parts.first()
        val v = parts.firstOrNull { it.json["role"] == PackRole.VECTOR.label }
        val ids = SourceRegistry.builtIn.ordered(parts.flatMap { it.registryIds }.distinct().filter { it in SourceRegistry.builtIn })
        val b = parts.map { it.bounds }
        val bounds = listOf(b.minOf { it[0] }, b.minOf { it[1] }, b.maxOf { it[2] }, b.maxOf { it[3] })
        val files = parts.map { it.json + ("sha256" to sha256(File(outDir, it.json["file"] as String))) }
        return linkedMapOf(
            "id" to p.name,
            "name" to p.name,
            "single" to p.single,
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

    /** [file]'s header and metadata, cached by (length, time). [role] null: only the tile type is wanted. */
    private fun describe(file: File, role: PackRole?): Described {
        val length = file.length()
        val modified = file.lastModified()
        val key = file.name + "|" + role?.label
        described[key]?.takeIf { it.length == length && it.modified == modified }?.let { return it }
        val reader = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors)
        val h = reader.header
        val meta = runCatching { reader.metadata() }.getOrDefault(emptyMap())
        val bounds = listOf(h.minLon, h.minLat, h.maxLon, h.maxLat)
        val json = linkedMapOf<String, Any?>(
            "role" to role?.label,
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
        return Described(length, modified, json, ids, profile, bounds, h.tileType.label).also { described[key] = it }
    }

    /**
     * SHA-256 of [file] (lowercase hex), from its `.sha256` sidecar when the sidecar's time equals the
     * file's; [force] ignores the sidecar and rehashes.
     */
    fun sha256(file: File, force: Boolean = false): String = synchronized(hashLocks.computeIfAbsent(file.canonicalPath) { Any() }) {
        val side = sidecar(file)
        val modified = file.lastModified()
        if (!force && side.isFile && side.lastModified() == modified) {
            side.readText().trim().substringBefore(' ').takeIf { HEX64.matches(it) }?.let { return it }
        }
        val hex = hashFile(file)
        hashesComputed++
        // Written via a temp file and a rename, so a reader never sees half a line; then stamped with the
        // file's time, which is what makes it current.
        runCatching {
            val tmp = Http.partFile(side).also { it.writeText("$hex  ${file.name}\n") }
            tmp.setLastModified(modified)
            side.delete()
            Http.publish(tmp, side)
            side.setLastModified(modified)
        }
        hex
    }

    /** Computes every missing or stale hash on a background thread (the first `/catalog` is then fast). */
    fun warm(): Thread {
        warmStarted = System.currentTimeMillis()
        warmEnded = 0L
        return Thread({
            try {
                val files = runCatching { packFiles().flatMap { p -> p.files.map { it.second } } }.getOrDefault(emptyList())
                warmDone.set(0)
                warmTotal = files.size
                for (f in files) {
                    runCatching { sha256(f) }
                    warmDone.incrementAndGet()
                }
            } finally {
                warmEnded = System.currentTimeMillis()
            }
        }, "catalog-hash").apply { isDaemon = true; start() }
    }

    /**
     * The startup hash pass, for `/healthz`: `state` is `cold` (not started), `warming` (files hashed so
     * far out of `files`) or `ready` (every sidecar current; `/catalog` answers without hashing).
     */
    fun warmState(): Map<String, Any?> = linkedMapOf(
        "state" to when {
            warmStarted == 0L -> "cold"
            warmEnded == 0L -> "warming"
            else -> "ready"
        },
        "files" to warmTotal,
        "hashed" to warmDone.get(),
        "hashesComputed" to hashesComputed,
        "ms" to if (warmStarted == 0L) null else (if (warmEnded == 0L) System.currentTimeMillis() else warmEnded) - warmStarted,
    )

    /**
     * `POST /catalog/refresh` (T58): forgets the cached headers and rehashes every catalogued file,
     * ignoring the sidecars, so bytes changed in place (same size and time) show up. Returns the
     * catalog plus `refresh`: files rehashed, those whose hash changed, and the time taken.
     */
    fun refresh(): Map<String, Any?> {
        val started = System.nanoTime()
        described.clear()
        val changed = ArrayList<String>()
        var count = 0
        for (p in packFiles()) for ((_, f) in p.files) {
            val side = sidecar(f)
            val old = side.takeIf { it.isFile }?.let { runCatching { it.readText().trim().substringBefore(' ') }.getOrNull() }
            val now = runCatching { sha256(f, force = true) }.getOrNull() ?: continue
            count++
            if (old != null && old != now) changed += f.name
        }
        return json() + ("refresh" to linkedMapOf("files" to count, "changed" to changed, "ms" to (System.nanoTime() - started) / 1_000_000))
    }

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
