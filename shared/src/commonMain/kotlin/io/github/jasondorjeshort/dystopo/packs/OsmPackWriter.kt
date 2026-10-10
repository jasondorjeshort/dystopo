package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.Compress
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.MemoryTileStaging
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileStaging
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.pmtiles.WriteResult
import kotlin.math.floor
import kotlin.math.round

/**
 * What goes into a vector pack's metadata besides the tiles. [vectorLayers] is the TileJSON
 * `vector_layers` list of the tiles' source (OpenFreeMap's planet TileJSON), so MapLibre and the
 * app see the same layer description as online. [bounds] is west, south, east, north.
 */
data class OsmPackMetadata(
    val name: String,
    val bounds: List<Double>,
    val minZoom: Int,
    val maxZoom: Int,
    val vectorLayers: List<Any?>,
    /** Plain-text and HTML credit, composed from the source registry for [provenance]. */
    val attribution: String,
    val attributionHtml: String,
    /** Registry ids (`sources.json`) of what the tiles are made of. */
    val provenance: List<String>,
    val license: String,
    /** The upstream build the tiles were taken from (OpenFreeMap's planet build id), when known. */
    val sourceBuild: String? = null,
    val profile: String = OsmPackWriter.PROFILE_OPENMAPTILES,
    val schema: String = OsmPackWriter.SCHEMA_OPENMAPTILES,
)

/**
 * Writes a vector region pack (`<name>-outdoors.pmtiles`, see [PackNames]) from vector tiles fetched
 * from an OpenMapTiles-schema server such as OpenFreeMap: MVT tiles, gzip-compressed as OpenMapTiles
 * archives are (raw tiles are compressed with [gzip]; already gzipped ones are stored as given),
 * empty tiles (open ocean) left out, identical tiles stored once by [PmtilesWriter].
 *
 * The metadata says what the pack is: `vector_layers` as the source describes them, the box, zooms,
 * attribution, `dystopo:provenance` (registry ids, comma-separated like `tilebuild` writes it) and
 * `dystopo:profile` = [PROFILE_OPENMAPTILES], i.e. **not** the outdoors profile: wilderness areas,
 * cliff ticks and climbing routes are absent, and `dystopo:outdoors_layers` says so.
 */
class OsmPackWriter(
    private val gzip: Compress,
    staging: TileStaging = MemoryTileStaging(),
) {
    private val writer = PmtilesWriter(TileType.MVT, tileCompression = Compression.GZIP, staging = staging)

    /** Compressed bytes of the tiles added (before deduplication). */
    var tileBytes = 0L
        private set

    /** Empty tiles offered to [add] and left out. */
    var emptyTiles = 0
        private set

    val tiles: Int get() = writer.tileCount

    /** Adds tile z/x/y; [mvt] is a raw or gzipped Mapbox Vector Tile. Returns false (and stores nothing) for an empty tile. */
    fun add(z: Int, x: Int, y: Int, mvt: ByteArray): Boolean {
        if (mvt.isEmpty()) {
            emptyTiles++
            return false
        }
        val stored = if (isGzip(mvt)) mvt else gzip(mvt)
        writer.addTile(z, x, y, stored)
        tileBytes += stored.size
        return true
    }

    /** Writes the archive to [sink]; header bounds, zooms and center from [meta]. */
    fun finish(sink: ByteSink, meta: OsmPackMetadata): WriteResult {
        require(tiles > 0) { "no non-empty vector tiles in the box; nothing to write" }
        val (w, s, e, n) = meta.bounds
        return writer.finish(
            sink, metadataJson(meta), w, s, e, n,
            centerZoom = (meta.minZoom + meta.maxZoom + 1) / 2,
            centerLon = (w + e) / 2,
            centerLat = (s + n) / 2,
            minZoom = meta.minZoom, maxZoom = meta.maxZoom,
        )
    }

    companion object {
        /** `dystopo:profile` of a pack made from OpenMapTiles-schema tiles (OpenFreeMap), as opposed to tilebuild's `outdoors`. */
        const val PROFILE_OPENMAPTILES = "openmaptiles"
        const val SCHEMA_OPENMAPTILES = "OpenMapTiles (OpenFreeMap planet build; code BSD-3-Clause, design CC-BY 4.0)"

        /** `dystopo:outdoors_layers` of such a pack: the layers only tilebuild's outdoors profile has are missing. */
        const val OUTDOORS_LAYERS_ABSENT =
            "absent: OpenMapTiles schema only (no wilderness areas, cliff ticks or climbing routes); tilebuild --profile outdoors makes those"

        fun isGzip(b: ByteArray) = b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

        /** The metadata JSON object written into the archive. */
        fun metadata(meta: OsmPackMetadata): Map<String, Any?> {
            val (w, s, e, n) = meta.bounds
            val box = listOf(w, s, e, n).joinToString(",") { fmt(it) }
            return linkedMapOf(
                "name" to meta.name,
                "description" to "OpenStreetMap base layers (OpenMapTiles schema) for $box, z${meta.minZoom}-${meta.maxZoom}; not the outdoors profile",
                "version" to "1",
                "type" to "baselayer",
                "format" to "pbf",
                "minzoom" to meta.minZoom,
                "maxzoom" to meta.maxZoom,
                "bounds" to box,
                "attribution" to meta.attributionHtml,
                "vector_layers" to meta.vectorLayers,
                "dystopo:attribution" to meta.attribution,
                "dystopo:license" to meta.license,
                "dystopo:provenance" to meta.provenance.joinToString(","),
                "dystopo:profile" to meta.profile,
                "dystopo:outdoors_layers" to OUTDOORS_LAYERS_ABSENT,
                "dystopo:schema" to meta.schema,
                "dystopo:source_build" to meta.sourceBuild,
            ).filterValues { it != null }
        }

        fun metadataJson(meta: OsmPackMetadata): String = MiniJson.encode(metadata(meta))

        /**
         * The `vector_layers` list of a TileJSON document (empty when it has none), trimmed to what a
         * viewer reads: each layer's `id`, `minzoom`, `maxzoom` and `fields`, without descriptions and
         * without the per-language name fields (`name:de`, `name_de`, ...) except [KEPT_NAME_FIELDS].
         * OpenFreeMap's planet list is 18.7 KB, almost all of it name languages; trimmed it is 5.3 KB.
         */
        fun vectorLayers(tileJson: Map<String, Any?>): List<Any?> = (tileJson["vector_layers"] as? List<*>)?.mapNotNull { layer ->
            val l = layer as? Map<*, *> ?: return@mapNotNull null
            val id = l["id"] as? String ?: return@mapNotNull null
            linkedMapOf<String, Any?>("id" to id).apply {
                l["minzoom"]?.let { put("minzoom", it) }
                l["maxzoom"]?.let { put("maxzoom", it) }
                put("fields", (l["fields"] as? Map<*, *>).orEmpty().entries.filter { (k, _) -> k is String && keepField(k) }.associate { (k, v) -> k as String to v })
            }
        } ?: emptyList()

        /** Name fields kept in [vectorLayers]: the ones our styles and search read. */
        val KEPT_NAME_FIELDS = setOf("name", "name:en", "name_en", "name:latin", "name:nonlatin", "name_int")

        private fun keepField(key: String) =
            key in KEPT_NAME_FIELDS || !(key.startsWith("name:") || key.startsWith("name_"))

        private fun fmt(d: Double): String {
            val r = round(d * 1e6) / 1e6
            return if (r == floor(r)) r.toLong().toString() else r.toString()
        }
    }
}
