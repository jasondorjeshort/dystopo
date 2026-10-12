package io.github.jasondorjeshort.dystopo.areas

/**
 * T75: which cached tiles belong to a pinned area, so the tile cache's eviction skips them. A cover is the
 * area's tiles at every zoom from 0 to its deepest ([TileCover], with a margin); a tile deeper than that counts
 * when its ancestor at the deepest zoom does (contour tiles one zoom past the DEM, overzoomed views).
 *
 * [isPinned] reads cache keys of the form `<source>/<route>[@<version>]/<z>/<x>/<y>.<ext>` (the phone's online tile cache:
 * `dem`, `osm`, `contours-...`), so every route a pinned area touched stays, whichever source filled it; glyph
 * ranges (`<source>/glyphs/...`) are pinned whenever any area is (a few hundred kB, needed for every label).
 */
class AreaPins(covers: List<Map<Int, TileSet>>) {
    private class Cover(val sets: Map<Int, TileSet>) {
        val maxZoom = sets.keys.maxOrNull() ?: -1
        val minZoom = sets.keys.minOrNull() ?: 0

        fun contains(z: Int, x: Int, y: Int): Boolean {
            if (maxZoom < 0) return false
            if (z > maxZoom) {
                val s = z - maxZoom
                return sets.getValue(maxZoom).contains(x shr s, y shr s)
            }
            if (z < minZoom) return sets.getValue(minZoom).tiles().any { (tx, ty) -> (tx shr (minZoom - z)) == x && (ty shr (minZoom - z)) == y }
            return sets[z]?.contains(x, y) ?: false
        }
    }

    private val covers = covers.map { Cover(it) }.filter { it.maxZoom >= 0 }

    val isEmpty: Boolean get() = covers.isEmpty()

    fun contains(z: Int, x: Int, y: Int): Boolean = covers.any { it.contains(z, x, y) }

    /** True when the cache entry [key] is a tile (or a glyph range) of a pinned area. */
    fun isPinned(key: String): Boolean {
        if (covers.isEmpty()) return false
        val parts = key.split('/')
        if (parts.size >= 2 && parts[1].substringBefore('@') == "glyphs") return true // T74: routes may carry a content version
        if (parts.size < 5) return false
        val z = parts[parts.size - 3].toIntOrNull() ?: return false
        val x = parts[parts.size - 2].toIntOrNull() ?: return false
        val y = parts[parts.size - 1].substringBefore('.').toIntOrNull() ?: return false
        if (z !in 0..30) return false
        return contains(z, x, y)
    }

    companion object {
        val NONE = AreaPins(emptyList())

        /** The cover pinned for [area]: its tiles at zooms 0 to its deepest, grown by [margin]. */
        fun cover(area: AreaOfInterest, margin: Int = 1): Map<Int, TileSet> = TileCover.byZoom(area, 0..area.maxZoom, margin)
    }
}
