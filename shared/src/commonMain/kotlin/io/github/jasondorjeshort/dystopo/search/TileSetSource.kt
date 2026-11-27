package io.github.jasondorjeshort.dystopo.search

import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import io.github.jasondorjeshort.dystopo.pmtiles.TileType

/**
 * T377: the vector tiles a [SearchIndexBuilder] reads: the zooms they span, the tiles present at a zoom, a tile's bytes
 * (MVT, unpacked) and a fingerprint that changes when the content does (the index is rebuilt then). Two kinds: a pack's
 * vector archive ([PmtilesTileSource]) and a set of tiles held elsewhere, such as an area kept as tiles in the online
 * cache ([TileListSource]), whose tiles may be fewer than its shape asks for (a download skips a tile an open map
 * answers), so its fingerprint counts the tiles present.
 */
interface TileSetSource {
    /** The shallowest and deepest zoom the set holds (the builder scans the deepest, capped at 14, and two and four lower). */
    val minZoom: Int
    val maxZoom: Int

    /** The latitude in the middle of the set's box, for the merge distances (a tile's width in km). */
    val midLat: Double

    /** Identifies the content: an index built from a different fingerprint is stale. */
    val fingerprint: String

    /** The tile ids ([TileIds]) present at zoom [z], ascending. */
    fun tileIds(z: Int): List<Long>

    /** The MVT bytes of tile [z]/[x]/[y], unpacked, or null when it is not there. */
    fun tile(z: Int, x: Int, y: Int): ByteArray?
}

/** T377: a pack's vector archive as a [TileSetSource] (what the builder read before the seam). */
class PmtilesTileSource(private val reader: PmtilesReader) : TileSetSource {
    private val h = reader.header

    init {
        require(h.tileType == TileType.MVT) { "not a vector archive (tile type ${h.tileType.label})" }
    }

    override val minZoom: Int get() = h.minZoom
    override val maxZoom: Int get() = h.maxZoom
    override val midLat: Double get() = (h.minLat + h.maxLat) / 2
    override val fingerprint: String get() = SearchIndexBuilder.fingerprint(h)

    /** Tile ids present at zoom [z] (run-length entries expanded), in id order. */
    override fun tileIds(z: Int): List<Long> {
        val start = TileIds.zoomStart(z)
        val end = TileIds.zoomStart(z + 1)
        val out = ArrayList<Long>()
        for (e in reader.tileEntries()) {
            if (e.tileId >= end) break
            if (e.tileId + e.runLength <= start) continue
            for (k in 0 until e.runLength) {
                val id = e.tileId + k
                if (id in start until end) out += id
            }
        }
        return out
    }

    override fun tile(z: Int, x: Int, y: Int): ByteArray? = reader.tile(z, x, y)
}

/**
 * T377: tiles held elsewhere (an area's tiles in the online cache): [present] lists, per zoom, the tile ids that are there
 * now (only the zooms the builder scans need be listed), and [read] gives a tile's unpacked bytes. [key] names the set and
 * what it was fetched at (an area's id and versions); the fingerprint adds, per listed zoom, the count of tiles present and
 * a sum of their ids, so a tile that arrives (or goes) makes the index stale.
 */
class TileListSource(
    private val key: String,
    override val minZoom: Int,
    override val maxZoom: Int,
    override val midLat: Double,
    private val present: Map<Int, List<Long>>,
    private val read: (Int, Int, Int) -> ByteArray?,
) : TileSetSource {
    override val fingerprint: String = fingerprintOf(key, present)

    override fun tileIds(z: Int): List<Long> = present[z]?.sorted().orEmpty()

    override fun tile(z: Int, x: Int, y: Int): ByteArray? = read(z, x, y)

    /** Tiles present at the listed zooms. */
    val tileCount: Int get() = present.values.sumOf { it.size }

    companion object {
        /** Pure: `tiles-v1:<key>:<z>=<count>.<id sum in hex>,...` over the listed zooms in order. */
        fun fingerprintOf(key: String, present: Map<Int, Collection<Long>>): String =
            "tiles-v1:$key:" + present.keys.sorted().joinToString(",") { z ->
                val ids = present.getValue(z)
                "$z=${ids.size}.${ids.fold(0L) { a, b -> a + b }.toULong().toString(16)}"
            }
    }
}
