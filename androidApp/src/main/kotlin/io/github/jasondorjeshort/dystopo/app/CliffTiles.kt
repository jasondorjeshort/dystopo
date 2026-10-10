package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.style.LineTicks
import io.github.jasondorjeshort.dystopo.tiles.CliffSides
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.util.concurrent.atomic.AtomicLong

/**
 * Cliff ticks on the downhill side on the phone (T41), like the devtool renderer: vector tiles holding
 * cliff lines are rewritten by [CliffSides] with the elevation of the DEM pack under the tile, so the
 * style's ticks (always on the line's right) sit on the low side. Tiles without cliffs pass through
 * untouched after a byte scan; rewritten tiles are kept in a small memory cache and (T49) on disk in
 * [disk] (the contour cache's LRU, a directory per vector and DEM archive, [ContourCache.cliffDir]), so a
 * restart does not pay the 100 to 400 ms rewrite again; a tile whose cliffs were already right is stored
 * as an empty file, meaning serve as stored. [pyramidFor] gives the DEM pyramid answering DEM tile
 * z/x/y, or null (no DEM there: the tile is served as stored).
 */
class CliffTiles(private val pyramidFor: (Int, Int, Int) -> DemPyramid?, private val disk: () -> TileCache? = { null }) {
    val tilesScanned = AtomicLong()
    val tilesWithCliffs = AtomicLong()
    val tilesChanged = AtomicLong()
    val lines = AtomicLong()
    val piecesReversed = AtomicLong()
    val segmentsUndecided = AtomicLong()
    val noDem = AtomicLong()
    val diskHits = AtomicLong()
    val diskWrites = AtomicLong()
    val timing = Timing(keepRecent = 8)

    private val memo = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 64
    }

    /**
     * [bytes] (an uncompressed vector tile z/x/y) with its cliff lines turned downhill-right. [diskDir]: the
     * tile's directory in [disk] ([ContourCache.cliffDir] of the vector and DEM archives), null for no disk cache.
     */
    fun orient(z: Int, x: Int, y: Int, bytes: ByteArray, diskDir: String? = null): ByteArray {
        if (z < MIN_ZOOM) return bytes
        tilesScanned.incrementAndGet()
        if (!CliffSides.mayHaveCliffs(bytes)) return bytes
        val key = "$z/$x/$y/${bytes.size}"
        synchronized(memo) { memo[key] }?.let { return it }
        val diskKey = diskDir?.let { ContourCache.key(it, z, x, y) }
        val cache = if (diskKey != null) disk() else null
        if (cache != null) {
            cache.get(diskKey!!)?.let { stored ->
                diskHits.incrementAndGet()
                val out = if (stored.isEmpty()) bytes else stored
                synchronized(memo) { memo[key] = out }
                return out
            }
        }
        val started = System.nanoTime()
        var failed = false
        val out = try {
            rewrite(z, x, y, bytes)
        } catch (e: Exception) {
            failed = true
            runCatching { android.util.Log.w(LocalServer.TAG, "cliff sides for $z/$x/$y failed", e) }
            bytes
        }
        timing.add((System.nanoTime() - started) / 1e6, mapOf("tile" to "$z/$x/$y", "changed" to (out !== bytes)))
        synchronized(memo) { memo[key] = out }
        if (cache != null && !failed) {
            cache.put(diskKey!!, if (out === bytes) ByteArray(0) else out)
            diskWrites.incrementAndGet()
        }
        return out
    }

    private fun rewrite(z: Int, x: Int, y: Int, bytes: ByteArray): ByteArray {
        val tile = VectorTile.decode(bytes)
        val layer = tile.layer(CliffSides.LAYER) ?: return bytes
        if (layer.features.none(CliffSides::isCliff)) return bytes
        tilesWithCliffs.incrementAndGet()
        val p = pyramidFor(z, x, y) ?: return bytes.also { noDem.incrementAndGet() }
        // Sample one DEM zoom deeper than the tile (about 10 m cells at z13), within the pack's zooms.
        val zd = (z + 1).coerceIn(p.minZoom, p.maxZoom)
        val scale = if (zd >= z) (1 shl (zd - z)).toDouble() else 1.0 / (1 shl (z - zd))
        val size = (p.tileSize * scale).toInt().coerceAtLeast(16)
        val margin = MARGIN_PX
        val w = size + 2 * margin
        val grid = p.window(zd, (x * size) - margin, (y * size) - margin, w, w)
        if (grid.values.all { it.isNaN() }) return bytes.also { noDem.incrementAndGet() }
        val extent = layer.extent.toDouble()
        val perUnit = size / extent // DEM pixels per tile unit
        val dem = LineTicks.bilinear(w, w) { i, j -> grid.values[j * w + i] }
        val r = CliffSides.orient(
            tile,
            { u, v -> dem(u * perUnit + margin, v * perUnit + margin) },
            offset = OFFSET_DEM_PX / perUnit,
            step = 1.0 / perUnit,
        )
        lines.addAndGet(r.lines.toLong())
        piecesReversed.addAndGet(r.reversedPieces.toLong())
        segmentsUndecided.addAndGet(r.segmentsUndecided.toLong())
        if (!r.changed) return bytes
        tilesChanged.incrementAndGet()
        return r.tile.encode()
    }

    fun json(): Map<String, Any?> = mapOf(
        "tilesScanned" to tilesScanned.get(), "tilesWithCliffs" to tilesWithCliffs.get(), "tilesChanged" to tilesChanged.get(),
        "lines" to lines.get(), "piecesReversed" to piecesReversed.get(), "segmentsUndecided" to segmentsUndecided.get(),
        "noDem" to noDem.get(), "diskHits" to diskHits.get(), "diskWrites" to diskWrites.get(), "timing" to timing.json(),
    )

    companion object {
        /** The style draws cliffs from z12; tiles above that are passed through. */
        const val MIN_ZOOM = 11
        const val MARGIN_PX = 8
        /** Sample distance to each side of a cliff, in DEM pixels (about 25 m at z14). */
        const val OFFSET_DEM_PX = 2.5
    }
}
