package io.github.jasondorjeshort.dystopo.packs

/**
 * A server's pack catalog (T53; the server side is `GET /catalog`, T52, with the same shape served by
 * `devtool serve` until then): every pack the server hosts, with what a phone needs to list it, draw its
 * outline, download it and notice when the server rebuilt it.
 *
 * JSON (version 1):
 * ```
 * {"version": 1, "generatedAt": "2026-10-10T12:00:00Z", "packs": [
 *   {"id": "rainier", "name": "rainier", "title": "...", "bounds": [w, s, e, n], "minZoom": 8, "maxZoom": 13,
 *    "profile": "outdoors", "bytes": 226811914, "builtAt": "2026-10-09T21:43:34Z", "builtAtMs": 1791...,
 *    "provenance": ["3dep", "copernicus", "osm"], "attribution": "...", "contentHash": "sha256:<64 hex>",
 *    "files": [{"role": "dem", "file": "rainier-dem.pmtiles", "url": "/out/rainier-dem.pmtiles", "bytes": 222344963,
 *               "sha256": "<64 hex>", "tileType": "png", "minZoom": 8, "maxZoom": 13, "bounds": [w, s, e, n]},
 *              {"role": "outdoors", ...}]}]}
 * ```
 * `id` is stable across rebuilds (the pack name); `contentHash` (`hash` accepted too) is the content hash ([PackHash.combine] of the
 * files' SHA-256), so a rebuilt pack keeps its id and gets a new hash. `url` may be absolute or relative
 * to the server. `profile` is the vector archive's `dystopo:profile` (`outdoors`, `openmaptiles`,
 * `unknown`), null for a terrain-only pack.
 */
data class PackCatalog(val version: Int, val generatedAt: String?, val packs: List<CatalogPack>) {
    fun find(id: String): CatalogPack? = packs.firstOrNull { it.id == id } ?: packs.firstOrNull { it.name == id }

    fun json(): Map<String, Any?> = linkedMapOf("version" to version, "generatedAt" to generatedAt, "packs" to packs.map { it.json() })

    companion object {
        const val VERSION = 1

        /**
         * Reads a catalog document. Entries that are unusable (no valid name, no files, a file without a
         * SHA-256, a hash that does not match) are skipped and reported in [skipped] instead of failing the
         * whole catalog. T65: vector-only entries (no DEM file, [CatalogPack.vectorOnly]) are kept.
         */
        fun parse(doc: Map<String, Any?>, skipped: MutableList<String>? = null): PackCatalog {
            val version = (doc["version"] as? Number)?.toInt() ?: VERSION
            val packs = (doc["packs"] as? List<*>).orEmpty().mapNotNull { e ->
                @Suppress("UNCHECKED_CAST")
                val m = e as? Map<String, Any?> ?: return@mapNotNull null
                try {
                    CatalogPack.parse(m)
                } catch (x: IllegalArgumentException) {
                    skipped?.add(x.message ?: "bad entry")
                    null
                }
            }
            return PackCatalog(version, doc["generatedAt"] as? String, packs)
        }

        /** [url] resolved against [server] (`http://host:port[/path]`): absolute URLs stay, `/out/x` gets the server's origin and path. */
        fun resolveUrl(server: String, url: String): String {
            if (url.startsWith("http://") || url.startsWith("https://")) return url
            val base = server.trimEnd('/')
            return if (url.startsWith("/")) base + url else "$base/$url"
        }
    }
}

/** One archive of a catalog pack. */
data class CatalogFile(
    val role: PackRole,
    val file: String,
    val url: String,
    val bytes: Long,
    /** 64 lowercase hex digits. */
    val sha256: String,
    val tileType: String? = null,
    val minZoom: Int? = null,
    val maxZoom: Int? = null,
    val bounds: PackBounds? = null,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "role" to role.label, "file" to file, "url" to url, "bytes" to bytes, "sha256" to sha256,
        "tileType" to tileType, "minZoom" to minZoom, "maxZoom" to maxZoom, "bounds" to bounds?.toList(),
    )

    companion object {
        fun parse(m: Map<String, Any?>): CatalogFile {
            val role = when (m["role"]) {
                "dem" -> PackRole.DEM
                "outdoors", "osm", "vector" -> PackRole.VECTOR
                else -> throw IllegalArgumentException("file role '${m["role"]}'")
            }
            val file = m["file"] as? String ?: throw IllegalArgumentException("file without a name")
            val sha = PackHash.normalize(m["sha256"] as? String) ?: throw IllegalArgumentException("$file: no sha256")
            return CatalogFile(
                role = role, file = file, url = m["url"] as? String ?: "/out/$file",
                bytes = (m["bytes"] as? Number)?.toLong() ?: throw IllegalArgumentException("$file: no bytes"),
                sha256 = sha, tileType = m["tileType"] as? String,
                minZoom = (m["minZoom"] as? Number)?.toInt(), maxZoom = (m["maxZoom"] as? Number)?.toInt(),
                bounds = parseBounds(m["bounds"]),
            )
        }
    }
}

/** One pack in a [PackCatalog]. */
data class CatalogPack(
    val id: String,
    val name: String,
    val title: String?,
    val bounds: PackBounds?,
    val minZoom: Int?,
    val maxZoom: Int?,
    val profile: String?,
    val bytes: Long,
    val builtAt: String?,
    val builtAtMs: Long?,
    val provenance: List<String>,
    val attribution: String,
    val hash: String,
    val files: List<CatalogFile>,
) {
    fun file(role: PackRole): CatalogFile? = files.firstOrNull { it.role == role }

    /** T65: map data without a DEM (a server's single-file vector pack). */
    val vectorOnly: Boolean get() = files.none { it.role == PackRole.DEM }

    /** `YYYY-MM-DD` of [builtAt], for lists. */
    val builtDate: String? get() = builtAt?.takeIf { it.length >= 10 }?.substring(0, 10)

    fun json(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "title" to title, "bounds" to bounds?.toList(), "minZoom" to minZoom, "maxZoom" to maxZoom,
        "profile" to profile, "bytes" to bytes, "builtAt" to builtAt, "builtAtMs" to builtAtMs, "provenance" to provenance,
        "attribution" to attribution, "contentHash" to hash, "files" to files.map { it.json() },
    )

    companion object {
        fun parse(m: Map<String, Any?>): CatalogPack {
            val name = m["name"] as? String ?: throw IllegalArgumentException("entry without a name")
            require(PackNames.isValidName(name)) { "invalid pack name '$name'" }
            val files = (m["files"] as? List<*>).orEmpty().map {
                @Suppress("UNCHECKED_CAST")
                CatalogFile.parse(it as? Map<String, Any?> ?: throw IllegalArgumentException("$name: bad file entry"))
            }
            // T65: a vector-only entry (a server's single-file pack, T58) is listed too; the phone overlays its map data where a DEM pack or the online DEM covers it.
            require(files.isNotEmpty()) { "$name: no files" }
            require(files.map { it.role }.distinct().size == files.size) { "$name: two files with one role" }
            val computed = PackHash.combine(files.associate { it.role to it.sha256 })
            val hash = ((m["contentHash"] ?: m["hash"]) as? String)?.trim()?.lowercase() ?: computed
            require(hash == computed) { "$name: hash $hash does not match its files ($computed)" }
            return CatalogPack(
                id = (m["id"] as? String)?.takeIf { it.isNotBlank() } ?: name,
                name = name,
                title = m["title"] as? String,
                bounds = parseBounds(m["bounds"]) ?: files.firstOrNull { it.role == PackRole.DEM }?.bounds ?: files.firstNotNullOfOrNull { it.bounds },
                minZoom = (m["minZoom"] as? Number)?.toInt(),
                maxZoom = (m["maxZoom"] as? Number)?.toInt(),
                profile = m["profile"] as? String,
                bytes = (m["bytes"] as? Number)?.toLong() ?: files.sumOf { it.bytes },
                builtAt = m["builtAt"] as? String,
                builtAtMs = (m["builtAtMs"] as? Number)?.toLong(),
                provenance = (m["provenance"] as? List<*>).orEmpty().filterIsInstance<String>(),
                attribution = m["attribution"] as? String ?: "",
                hash = hash,
                files = files,
            )
        }

        /** An entry from the facts a server has about the archives in its pack directory. */
        fun of(
            name: String,
            dem: PackArchiveInfo,
            demSha256: String,
            vector: PackArchiveInfo?,
            vectorSha256: String?,
            profile: String?,
            provenance: List<String>,
            builtAt: String?,
            builtAtMs: Long?,
            urlPrefix: String = "/out/",
        ): CatalogPack {
            fun file(role: PackRole, a: PackArchiveInfo, sha: String) = CatalogFile(
                role, a.fileName, urlPrefix + a.fileName, a.bytes, PackHash.normalize(sha) ?: throw IllegalArgumentException("bad sha256 '$sha'"),
                a.tileType, a.minZoom, a.maxZoom, a.bounds,
            )
            val files = listOfNotNull(file(PackRole.DEM, dem, demSha256), vector?.let { file(PackRole.VECTOR, it, vectorSha256 ?: throw IllegalArgumentException("no sha256 for ${it.fileName}")) })
            val archives = listOfNotNull(dem, vector)
            return CatalogPack(
                id = name, name = name,
                title = vector?.title ?: dem.title,
                bounds = dem.bounds,
                minZoom = archives.minOf { it.minZoom }, maxZoom = archives.maxOf { it.maxZoom },
                profile = profile,
                bytes = files.sumOf { it.bytes },
                builtAt = builtAt, builtAtMs = builtAtMs,
                provenance = provenance,
                attribution = archives.map { it.attribution }.filter { it.isNotEmpty() }.distinct().joinToString(" | "),
                hash = PackHash.combine(files.associate { it.role to it.sha256 }),
                files = files,
            )
        }
    }
}

/**
 * Content hashes of packs. A file's hash is its SHA-256 in hex; a pack's is `sha256:` plus the SHA-256 of
 * one line per archive, `<role>:<file sha256>\n`, DEM first, so it changes when any archive changes and
 * does not depend on file names (`-osm` and `-outdoors` hash alike).
 */
object PackHash {
    const val PREFIX = "sha256:"
    private val HEX64 = Regex("[0-9a-f]{64}")

    /** 64 lowercase hex digits (an optional `sha256:` prefix removed), or null when [s] is not a SHA-256. */
    fun normalize(s: String?): String? = s?.trim()?.lowercase()?.removePrefix(PREFIX)?.takeIf { HEX64.matches(it) }

    fun combine(files: Map<PackRole, String>): String {
        val lines = PackRole.entries.mapNotNull { r -> files[r]?.let { "${r.label}:${normalize(it) ?: throw IllegalArgumentException("bad sha256 '$it'")}\n" } }
        return PREFIX + Sha256.hex(lines.joinToString("").encodeToByteArray())
    }

    /** Whether a downloaded file's digest [actual] is the [expected] one (case and prefix ignored). */
    fun matches(expected: String?, actual: String?): Boolean {
        val e = normalize(expected) ?: return false
        return e == normalize(actual)
    }
}

/** What a phone has of a catalog pack: per-archive SHA-256 of the installed files (null while not known yet). */
data class InstalledPack(val name: String, val sha256: Map<PackRole, String?>) {
    val hash: String? get() = if (sha256.values.any { it == null }) null else PackHash.combine(sha256.mapValues { it.value!! })
}

/** How a catalog pack relates to the device. */
enum class CatalogStatus(val label: String) {
    /** Not on the device. */
    AVAILABLE("available"),

    /** On the device, its hash not computed yet. */
    CHECKING("checking"),

    /** On the device with the server's content. */
    INSTALLED("installed"),

    /** On the device, but the server's content differs (rebuilt): an update is available. */
    UPDATE("update"),

    /** A download or update is running. */
    DOWNLOADING("downloading"),
    ;

    companion object {
        fun of(entry: CatalogPack, installed: InstalledPack?, downloading: Boolean = false): CatalogStatus = when {
            downloading -> DOWNLOADING
            installed == null -> AVAILABLE
            else -> when (installed.hash) {
                null -> CHECKING
                entry.hash -> INSTALLED
                else -> UPDATE
            }
        }

        /** The archives an update must fetch: those whose installed SHA-256 differs from the catalog's (or are missing). */
        fun changedRoles(entry: CatalogPack, installed: InstalledPack?): Set<PackRole> =
            entry.files.filter { f -> installed?.sha256?.get(f.role)?.let { PackHash.matches(f.sha256, it) } != true }.map { it.role }.toSet()
    }
}

internal fun parseBounds(v: Any?): PackBounds? {
    val l = (v as? List<*>)?.map { (it as? Number)?.toDouble() ?: return null } ?: return null
    if (l.size != 4 || l.any { !it.isFinite() }) return null
    return PackBounds(l[0], l[1], l[2], l[3])
}
