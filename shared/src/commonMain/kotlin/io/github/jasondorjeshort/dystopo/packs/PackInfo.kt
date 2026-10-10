package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesHeader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** A lon/lat box in degrees. */
data class PackBounds(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    val centerLon: Double get() = (minLon + maxLon) / 2
    val centerLat: Double get() = (minLat + maxLat) / 2

    fun union(o: PackBounds) = PackBounds(min(minLon, o.minLon), min(minLat, o.minLat), max(maxLon, o.maxLon), max(maxLat, o.maxLat))

    fun toList() = listOf(minLon, minLat, maxLon, maxLat)

    /**
     * The largest zoom at which the box fits a [widthPx] x [heightPx] viewport less [paddingPx] on
     * every side, for a renderer whose world is [tileSize] pixels wide at zoom 0 (MapLibre: 512).
     */
    fun fitZoom(widthPx: Double, heightPx: Double, tileSize: Double = 512.0, paddingPx: Double = 0.0): Double {
        val w = (widthPx - 2 * paddingPx).coerceAtLeast(1.0)
        val h = (heightPx - 2 * paddingPx).coerceAtLeast(1.0)
        val dx = ((maxLon - minLon) / 360.0).coerceAtLeast(1e-12) // world fractions
        val dy = (mercatorY(minLat) - mercatorY(maxLat)).coerceAtLeast(1e-12)
        return min(log2(w / (tileSize * dx)), log2(h / (tileSize * dy)))
    }

    companion object {
        fun of(h: PmtilesHeader) = PackBounds(h.minLon, h.minLat, h.maxLon, h.maxLat)

        /** Web Mercator y as a world fraction (0 at the north edge, 1 at the south). */
        fun mercatorY(lat: Double): Double {
            val l = lat.coerceIn(-85.051129, 85.051129) * PI / 180
            return (1 - ln(tan(l) + 1 / kotlin.math.cos(l)) / PI) / 2
        }
    }
}

/** One archive of a pack, described from its PMTiles header and metadata. */
data class PackArchiveInfo(
    val fileName: String,
    val bytes: Long,
    val tileType: String,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: PackBounds,
    val centerZoom: Int,
    val addressedTiles: Long,
    /** Plain-text attribution: HTML tags removed, entities decoded. */
    val attribution: String,
    /** Metadata `name` / `description`, when present. */
    val title: String?,
    val description: String?,
    /** Bytes the header says the archive spans; a shorter file is truncated. */
    val expectedBytes: Long,
) {
    val complete: Boolean get() = bytes >= expectedBytes

    fun json(): Map<String, Any?> = linkedMapOf(
        "file" to fileName, "bytes" to bytes, "tileType" to tileType, "minZoom" to minZoom, "maxZoom" to maxZoom,
        "bounds" to bounds.toList(), "centerZoom" to centerZoom, "tiles" to addressedTiles, "attribution" to attribution,
        "title" to title, "description" to description, "complete" to complete,
    )

    companion object {
        /** Describes the archive [reader] reads, which is the file [fileName] of [bytes] bytes. */
        fun read(fileName: String, bytes: Long, reader: PmtilesReader): PackArchiveInfo {
            val h = reader.header
            val meta = runCatching { reader.metadata() }.getOrDefault(emptyMap())
            return PackArchiveInfo(
                fileName = fileName,
                bytes = bytes,
                tileType = h.tileType.label,
                minZoom = h.minZoom,
                maxZoom = h.maxZoom,
                bounds = PackBounds.of(h),
                centerZoom = h.centerZoom,
                addressedTiles = h.addressedTiles,
                attribution = plainText(meta["attribution"] as? String),
                title = (meta["name"] as? String)?.let(::plainText)?.ifEmpty { null },
                description = (meta["description"] as? String)?.let(::plainText)?.ifEmpty { null },
                expectedBytes = expectedBytes(h),
            )
        }

        /** The end of the last section the header points at: the archive's minimum length. */
        fun expectedBytes(h: PmtilesHeader): Long = maxOf(
            PmtilesHeader.SIZE.toLong(),
            h.rootDirectoryOffset + h.rootDirectoryLength,
            h.metadataOffset + h.metadataLength,
            h.leafDirectoriesOffset + h.leafDirectoriesLength,
            h.tileDataOffset + h.tileDataLength,
        )

        private val TAG = Regex("<[^>]*>")
        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "copy" to "©", "reg" to "®", "mdash" to "—", "ndash" to "–",
        )

        /** Metadata text for display: tags removed, HTML entities decoded, whitespace collapsed. */
        fun plainText(html: String?): String {
            if (html.isNullOrEmpty()) return ""
            val noTags = html.replace(TAG, "")
            val decoded = ENTITY.replace(noTags) { m ->
                val e = m.groupValues[1]
                when {
                    e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
                    e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
                    else -> NAMED[e]
                } ?: m.value
            }
            return decoded.replace(Regex("\\s+"), " ").trim()
        }

        private fun codePoint(cp: Int): String? = when {
            cp < 0 || cp > 0x10FFFF -> null
            cp < 0x10000 -> cp.toChar().toString()
            else -> {
                val v = cp - 0x10000
                charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
            }
        }
    }
}

/** A region pack: a DEM archive and an optional vector archive sharing [name]. */
data class PackInfo(val name: String, val dem: PackArchiveInfo?, val vector: PackArchiveInfo?) {
    private val archives get() = listOfNotNull(dem, vector)

    /** Usable: has a complete DEM, and the vector archive (if any) is complete too. */
    val usable: Boolean get() = dem != null && archives.all { it.complete }

    /** The DEM's bounds (what the map shows); the vector archive's when there is no DEM. */
    val bounds: PackBounds? get() = dem?.bounds ?: vector?.bounds
    val minZoom: Int? get() = archives.minOfOrNull { it.minZoom }
    val maxZoom: Int? get() = archives.maxOfOrNull { it.maxZoom }
    val bytes: Long get() = archives.sumOf { it.bytes }
    val attribution: String get() = archives.map { it.attribution }.filter { it.isNotEmpty() }.distinct().joinToString(" | ")

    fun json(active: Boolean = false): Map<String, Any?> = linkedMapOf(
        "name" to name, "active" to active, "usable" to usable, "bytes" to bytes,
        "bounds" to bounds?.toList(), "minZoom" to minZoom, "maxZoom" to maxZoom, "attribution" to attribution,
        "dem" to dem?.json(), "vector" to vector?.json(),
    )
}
