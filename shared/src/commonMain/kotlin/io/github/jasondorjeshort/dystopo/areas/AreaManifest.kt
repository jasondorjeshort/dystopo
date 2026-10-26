package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * One thing an area needs offline. Kinds:
 * - `pack`: a whole archive ([FileItem]): a region pack's DEM or vector half, with SHA-256 and size, downloaded
 *   resumably and verified like a catalog pack.
 * - `tiles`: XYZ tiles of one route of a tile server ([TilesItem]: `dem`, `osm`), fetched one by one into the
 *   phone's tile cache and pinned there; the size is an estimate until fetched.
 * - `glyphs`, `sprite`: style assets ([AssetItem]); `bundled` when the app ships them.
 * - `graph`, `search`, `contours`: products the phone makes itself from the data above ([LocalItem]).
 */
sealed class ManifestItem {
    abstract val kind: String
    /** Bytes to transfer (exact for files and assets, estimated for tiles not fetched yet). */
    abstract val bytes: Long
    abstract val exact: Boolean
    abstract fun json(): Map<String, Any?>

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(m: Map<String, Any?>): ManifestItem {
            fun str(k: String) = m[k] as? String
            fun long(k: String) = (m[k] as? Number)?.toLong()
            fun int(k: String) = (m[k] as? Number)?.toInt()
            return when (val kind = str("kind")) {
                KIND_PACK -> FileItem(
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
                KIND_TILES -> {
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
                KIND_GLYPHS, KIND_SPRITE -> AssetItem(kind, str("url") ?: "", int("files") ?: 0, long("fileBytes") ?: long("bytes") ?: 0L, m["bundled"] == true, (m["names"] as? List<*>).orEmpty().map { it.toString() })
                KIND_GRAPH, KIND_SEARCH, KIND_CONTOURS -> LocalItem(kind, str("from"), m["available"] != false, str("note"))
                else -> throw IllegalArgumentException("unknown manifest item kind '$kind'")
            }
        }

        const val KIND_PACK = "pack"
        const val KIND_TILES = "tiles"
        const val KIND_GLYPHS = "glyphs"
        const val KIND_SPRITE = "sprite"
        const val KIND_GRAPH = "graph"
        const val KIND_SEARCH = "search"
        const val KIND_CONTOURS = "contours"
    }
}

/** A whole archive (`kind: pack`); [role] is `dem` or `outdoors` (the vector half), [url] absolute or relative to the source. */
data class FileItem(
    val role: String,
    val pack: String,
    val file: String,
    val url: String,
    override val bytes: Long,
    val sha256: String,
    val tileType: String? = null,
    val minZoom: Int? = null,
    val maxZoom: Int? = null,
    val bounds: List<Double>? = null,
) : ManifestItem() {
    override val kind get() = KIND_PACK
    override val exact get() = true
    override fun json(): Map<String, Any?> = linkedMapOf(
        "kind" to kind, "role" to role, "pack" to pack, "file" to file, "url" to url, "bytes" to bytes, "sha256" to sha256,
        "tileType" to tileType, "minZoom" to minZoom, "maxZoom" to maxZoom, "bounds" to bounds,
    )
}

/**
 * Tiles of one route (`kind: tiles`): [url] is the template relative to the source (`/dem/{z}/{x}/{y}.png`),
 * [tiles] the set per zoom. [bytesEstimate] is [AreaManifest.estimate] of the count until [fetchedBytes] is known.
 */
data class TilesItem(
    val route: String,
    val ext: String,
    val url: String,
    val tiles: Map<Int, TileSet>,
    val bytesEstimate: Long,
    val fetchedBytes: Long? = null,
) : ManifestItem() {
    override val kind get() = KIND_TILES
    val count: Int get() = tiles.values.sumOf { it.size }
    val zooms: IntRange? get() = tiles.keys.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() }
    override val bytes get() = fetchedBytes ?: bytesEstimate
    override val exact get() = fetchedBytes != null
    override fun json(): Map<String, Any?> = linkedMapOf(
        "kind" to kind, "route" to route, "ext" to ext, "url" to url, "zooms" to zooms?.let { listOf(it.first, it.last) },
        "count" to count, "perZoom" to tiles.toSortedMap().mapKeys { it.key.toString() }.mapValues { it.value.size },
        "bytes" to bytes, "estimated" to !exact, "fetchedBytes" to fetchedBytes,
        "tiles" to tiles.toSortedMap().mapKeys { it.key.toString() }.mapValues { it.value.rows() },
    )
}

/**
 * Glyphs or sprite files (`kind: glyphs|sprite`): [files] files of [fileBytes] in all on the source; [bundled] when the
 * app ships them, and then nothing to transfer ([bytes] 0).
 */
data class AssetItem(
    override val kind: String,
    val url: String,
    val files: Int,
    val fileBytes: Long,
    val bundled: Boolean,
    val names: List<String> = emptyList(),
) : ManifestItem() {
    override val bytes get() = if (bundled) 0L else fileBytes
    override val exact get() = true
    override fun json(): Map<String, Any?> = linkedMapOf("kind" to kind, "url" to url, "files" to files, "bytes" to bytes, "fileBytes" to fileBytes, "bundled" to bundled, "names" to names)
}

/** A product the phone builds from the area's data (`kind: graph|search|contours`): [from] names its input; nothing to transfer. */
data class LocalItem(override val kind: String, val from: String?, val available: Boolean, val note: String?) : ManifestItem() {
    override val bytes get() = 0L
    override val exact get() = true
    override fun json(): Map<String, Any?> = linkedMapOf("kind" to kind, "onDevice" to true, "from" to from, "available" to available, "note" to note)
}

/**
 * T75: what an [AreaOfInterest] needs offline, as a list of [ManifestItem]s with sizes. The server answers one
 * for `POST /area` (packs built through `/build` for a box; tile lists for a corridor); the phone makes the same
 * for a corridor when it reads the sources itself, and T78 publishes them from one phone to another.
 * Field by field in docs/SERVER.md › Areas of interest.
 *
 * [status]: `ready` (every item can be fetched now), `building` (a pack is being built: [build] is the
 * `/build` job; ask again) or `failed` ([notes] says why).
 *
 * T95: [versions] are the source's content versions (`dem`, `osm`, as its `GET /version` names them) that the
 * tiles were fetched at, written by the phone when a download completes (empty when unknown). A pinned area's
 * tiles stay keyed by them until the area is refreshed, whatever the source serves meanwhile.
 */
data class AreaManifest(
    val id: String,
    val name: String,
    val area: AreaOfInterest,
    val status: String,
    val items: List<ManifestItem>,
    val source: String? = null,
    val generatedAt: String? = null,
    val build: Map<String, Any?>? = null,
    val notes: List<String> = emptyList(),
    /** T140 (ADR 0041 §2): the lowest version that holds the content, 2 for a polygon, else 1 ([AreaOfInterest.manifestVersion]). */
    val version: Int = area.manifestVersion,
    val versions: Map<String, String> = emptyMap(),
) {
    val bytes: Long get() = items.sumOf { it.bytes }
    val bytesExact: Boolean get() = items.all { it.exact }
    val tileCount: Int get() = items.filterIsInstance<TilesItem>().sumOf { it.count }
    val packs: List<FileItem> get() = items.filterIsInstance<FileItem>()
    fun tiles(route: String): TilesItem? = items.filterIsInstance<TilesItem>().firstOrNull { it.route == route }

    fun json(): Map<String, Any?> = linkedMapOf(
        "version" to maxOf(version, area.manifestVersion), "id" to id, "name" to name, "status" to status, "source" to source, "generatedAt" to generatedAt,
        "area" to area.json(), "bounds" to area.bounds.toList(), "lengthM" to area.lengthM.takeIf { it > 0 },
        "bytes" to bytes, "bytesExact" to bytesExact, "tiles" to tileCount,
        "items" to items.map { it.json() }, "build" to build, "notes" to notes,
    ).also { if (versions.isNotEmpty()) it["versions"] = versions.toSortedMap() } // T95

    /** The manifest without the tile lists (for listings; [json] has them). */
    fun summary(): Map<String, Any?> = json() + ("items" to items.map { i -> i.json().filterKeys { it != "tiles" } })

    companion object {
        /** T140: the newest manifest version this build reads (2: the polygon kind, ADR 0041). */
        const val VERSION = 2
        const val STATUS_READY = "ready"
        const val STATUS_BUILDING = "building"
        const val STATUS_FAILED = "failed"

        /** Vector tiles stop here (OpenFreeMap and our packs); the map overzooms. */
        const val OSM_MAX_ZOOM = 14

        /**
         * T140 (ADR 0041 §2): checks `version`, then the area's `kind`, then the fields; the first two refuse with a
         * [NeedsNewerApp] (every build before this one parsed the area first, so a new kind hid a newer version).
         */
        @Suppress("UNCHECKED_CAST")
        fun parse(m: Map<String, Any?>): AreaManifest {
            val version = (m["version"] as? Number)?.toInt() ?: 1
            checkVersion(version)
            val area = AreaOfInterest.parse(m["area"] as? Map<String, Any?> ?: throw IllegalArgumentException("manifest without area"))
            return AreaManifest(
                id = m["id"] as? String ?: area.id,
                name = m["name"] as? String ?: area.name ?: area.id,
                area = area,
                status = m["status"] as? String ?: STATUS_READY,
                items = (m["items"] as? List<*>).orEmpty().map { ManifestItem.parse(it as Map<String, Any?>) },
                source = m["source"] as? String,
                generatedAt = m["generatedAt"] as? String,
                build = m["build"] as? Map<String, Any?>,
                notes = (m["notes"] as? List<*>).orEmpty().map { it.toString() },
                version = version,
                versions = (m["versions"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }.toMap(),
            )
        }

        fun parse(json: String): AreaManifest = parse(MiniJson.parseObject(json))

        /** T140: a [NeedsNewerApp] for a manifest or record [version] newer than [VERSION]. */
        fun checkVersion(version: Int) {
            if (version > VERSION) throw NeedsNewerApp("manifest version $version is newer than this app reads ($VERSION)")
        }

        /**
         * The tile items of [area] for a tile server: DEM tiles at [demZooms] and vector tiles at [osmZooms] (null:
         * none), each grown by [margin] tiles (contour tiles sample past their edges).
         */
        fun tileItems(area: AreaOfInterest, demZooms: IntRange?, osmZooms: IntRange?, margin: Int = 1): List<TilesItem> {
            val out = ArrayList<TilesItem>()
            if (demZooms != null && !demZooms.isEmpty()) {
                val t = TileCover.byZoom(area, demZooms, margin)
                out += TilesItem(ROUTE_DEM, "png", "/dem/{z}/{x}/{y}.png", t, t.entries.sumOf { (z, s) -> s.size * estimate(ROUTE_DEM, z) })
            }
            if (osmZooms != null && !osmZooms.isEmpty()) {
                val t = TileCover.byZoom(area, osmZooms, margin)
                out += TilesItem(ROUTE_OSM, "pbf", "/osm/{z}/{x}/{y}.pbf", t, t.entries.sumOf { (z, s) -> s.size * estimate(ROUTE_OSM, z) })
            }
            return out
        }

        const val ROUTE_DEM = "dem"
        const val ROUTE_OSM = "osm"

        /**
         * Typical bytes of one tile of [route] at zoom [z], for estimates before anything is fetched. DEM: the Rainier
         * pack (2026-10-12), 1404 Terrarium PNGs at z8 to 14 averaging 158 kB (mountain terrain: an upper end;
         * flat ground and ocean compress far better). Vector: OpenFreeMap tiles as the phone stores them
         * (gunzipped), large at low zooms (about 0.8 MB at z5 to 7, T80) and a few kB at z13 and z14 in the hills.
         */
        fun estimate(route: String, z: Int): Long = when (route) {
            ROUTE_DEM -> 160_000L
            ROUTE_OSM -> when {
                z <= 7 -> 800_000L
                z <= 9 -> 250_000L
                z <= 11 -> 60_000L
                z <= 12 -> 25_000L
                else -> 10_000L
            }
            else -> 50_000L
        }
    }
}
