package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AssetItem
import io.github.jasondorjeshort.dystopo.areas.FileItem
import io.github.jasondorjeshort.dystopo.areas.LocalItem
import io.github.jasondorjeshort.dystopo.areas.ManifestItem
import io.github.jasondorjeshort.dystopo.areas.TileSet
import io.github.jasondorjeshort.dystopo.areas.TilesItem

/**
 * T189 test fixture: the pack catalog and area manifest readers of v0.1.14-alpha, kept to show what a phone on that
 * release does with a catalog entry carrying `derived` and a manifest `graph` item carrying a shipped graph (ADR 0049:
 * it reads both unchanged and builds its own graph). Copied from the tag `v0.1.14-alpha`: `PackCatalog.parse`,
 * `CatalogPack.parse` and `CatalogFile.parse` (`packs/PackCatalog.kt`, unchanged since the tag until T189), and
 * `ManifestItem.parse` and `AreaManifest.parse` (`areas/AreaManifest.kt`, with that release's manifest `VERSION` 2
 * written out). They build today's types through the constructors the tag had (no `derived`, no `shipped`), which is
 * what that release held in memory. Never update this file to follow the readers: it is the old reader.
 */
object V014Readers {
    const val MANIFEST_VERSION = 2

    fun parseCatalog(doc: Map<String, Any?>, skipped: MutableList<String>? = null): PackCatalog {
        val version = (doc["version"] as? Number)?.toInt() ?: PackCatalog.VERSION
        val packs = (doc["packs"] as? List<*>).orEmpty().mapNotNull { e ->
            @Suppress("UNCHECKED_CAST")
            val m = e as? Map<String, Any?> ?: return@mapNotNull null
            try {
                parsePack(m)
            } catch (x: IllegalArgumentException) {
                skipped?.add(x.message ?: "bad entry")
                null
            }
        }
        @Suppress("UNCHECKED_CAST")
        return PackCatalog(version, doc["generatedAt"] as? String, packs, doc["publisher"] as? Map<String, Any?>)
    }

    private fun parseFile(m: Map<String, Any?>): CatalogFile {
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

    private fun parsePack(m: Map<String, Any?>): CatalogPack {
        val name = m["name"] as? String ?: throw IllegalArgumentException("entry without a name")
        require(PackNames.isValidName(name)) { "invalid pack name '$name'" }
        val files = (m["files"] as? List<*>).orEmpty().map {
            @Suppress("UNCHECKED_CAST")
            parseFile(it as? Map<String, Any?> ?: throw IllegalArgumentException("$name: bad file entry"))
        }
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

    @Suppress("UNCHECKED_CAST")
    fun parseItem(m: Map<String, Any?>): ManifestItem {
        fun str(k: String) = m[k] as? String
        fun long(k: String) = (m[k] as? Number)?.toLong()
        fun int(k: String) = (m[k] as? Number)?.toInt()
        return when (val kind = str("kind")) {
            ManifestItem.KIND_PACK -> FileItem(
                role = str("role") ?: throw IllegalArgumentException("pack item without role"),
                pack = str("pack") ?: throw IllegalArgumentException("pack item without pack"),
                file = str("file") ?: throw IllegalArgumentException("pack item without file"),
                url = str("url") ?: throw IllegalArgumentException("pack item without url"),
                bytes = long("bytes") ?: throw IllegalArgumentException("pack item without bytes"),
                sha256 = str("sha256")?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                    ?: throw IllegalArgumentException("pack item needs a 64-digit sha256"),
                tileType = str("tileType"), minZoom = int("minZoom"), maxZoom = int("maxZoom"),
                bounds = (m["bounds"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 },
            )
            ManifestItem.KIND_TILES -> {
                val tiles = (m["tiles"] as? Map<String, Any?>).orEmpty().map { (z, rows) ->
                    val zi = z.toIntOrNull() ?: throw IllegalArgumentException("tile zoom '$z'")
                    zi to TileSet.fromRows(zi, (rows as? List<*>).orEmpty().map { r -> (r as List<*>).map { (it as Number).toInt() } })
                }.toMap()
                TilesItem(
                    route = str("route") ?: throw IllegalArgumentException("tiles item without route"),
                    ext = str("ext") ?: "png", url = str("url") ?: "", tiles = tiles,
                    bytesEstimate = long("bytes") ?: 0L, fetchedBytes = long("fetchedBytes"),
                )
            }
            ManifestItem.KIND_GLYPHS, ManifestItem.KIND_SPRITE -> AssetItem(kind, str("url") ?: "", int("files") ?: 0, long("fileBytes") ?: long("bytes") ?: 0L, m["bundled"] == true, (m["names"] as? List<*>).orEmpty().map { it.toString() })
            ManifestItem.KIND_GRAPH, ManifestItem.KIND_SEARCH, ManifestItem.KIND_CONTOURS -> LocalItem(kind, str("from"), m["available"] != false, str("note"))
            else -> throw IllegalArgumentException("unknown manifest item kind '$kind'")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseManifest(m: Map<String, Any?>): AreaManifest {
        val version = (m["version"] as? Number)?.toInt() ?: 1
        if (version > MANIFEST_VERSION) throw IllegalArgumentException("manifest version $version is newer than this app reads ($MANIFEST_VERSION)")
        val area = AreaOfInterest.parse(m["area"] as? Map<String, Any?> ?: throw IllegalArgumentException("manifest without area"))
        return AreaManifest(
            id = m["id"] as? String ?: area.id,
            name = m["name"] as? String ?: area.name ?: area.id,
            area = area,
            status = m["status"] as? String ?: AreaManifest.STATUS_READY,
            items = (m["items"] as? List<*>).orEmpty().map { parseItem(it as Map<String, Any?>) },
            source = m["source"] as? String,
            generatedAt = m["generatedAt"] as? String,
            build = m["build"] as? Map<String, Any?>,
            notes = (m["notes"] as? List<*>).orEmpty().map { it.toString() },
            version = version,
            versions = (m["versions"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }.toMap(),
        )
    }
}
