package io.github.jasondorjeshort.dystopo.areas

/**
 * T75: which cached tiles belong to a pinned area, so the tile cache's eviction skips them. A cover is the
 * area's tiles at every zoom from 0 to its deepest ([TileCover], with a margin); a tile deeper than that counts
 * when its ancestor at the deepest zoom does (contour tiles one zoom past the DEM, overzoomed views).
 *
 * [isPinned] reads cache keys of the form `<source>/<route>[@<version>]/<z>/<x>/<y>.<ext>` (the phone's online tile cache:
 * `dem`, `osm`, `contours-...`), so every route a pinned area touched stays, whichever source filled it; glyph
 * ranges (`<source>/glyphs/...`) are pinned whenever any area is (a few hundred kB, needed for every label).
 *
 * T95: each cover may carry the content versions its tiles were fetched at ([versions], one map per cover: source
 * key to content name (`dem`, `osm`) to version, "" for a source that has none). A source whose content version
 * changes keeps the tiles a cover [holds] at the old version, and lookups that miss the current version try the
 * [versions] pins hold, so a pinned area is served at the version it was fetched at until it is refreshed.
 *
 * T152: a cover is [TileSet]s, so rows: every pinned area is held for the app's life at a cost of its tile rows (a few
 * thousand for a country at z15), not its tiles; [contains] is a hash lookup of the row and a binary search of its spans.
 */
class AreaPins(covers: List<Map<Int, TileSet>>, versions: List<Map<String, Map<String, String>>> = emptyList()) {
    private class Cover(val sets: Map<Int, TileSet>, val versions: Map<String, Map<String, String>>) {
        val maxZoom = sets.keys.maxOrNull() ?: -1
        val minZoom = sets.keys.minOrNull() ?: 0

        fun contains(z: Int, x: Int, y: Int): Boolean {
            if (maxZoom < 0) return false
            if (z > maxZoom) {
                val s = z - maxZoom
                return sets.getValue(maxZoom).contains(x shr s, y shr s)
            }
            if (z < minZoom) return sets.getValue(minZoom).ancestors(minZoom - z).contains(x, y)
            return sets[z]?.contains(x, y) ?: false
        }
    }

    private val covers = covers.mapIndexed { i, c -> Cover(c, versions.getOrNull(i).orEmpty()) }.filter { it.maxZoom >= 0 }

    /** Source key to content name to the versions some cover holds. */
    private val held: Map<String, Map<String, Set<String>>> = buildMap<String, MutableMap<String, MutableSet<String>>> {
        for (c in this@AreaPins.covers) for ((source, names) in c.versions) for ((name, v) in names) {
            getOrPut(source) { HashMap() }.getOrPut(name) { HashSet() }.add(v)
        }
    }

    val isEmpty: Boolean get() = covers.isEmpty()

    fun contains(z: Int, x: Int, y: Int): Boolean = covers.any { it.contains(z, x, y) }

    /** True when the cache entry [key] is a tile (or a glyph range) of a pinned area. */
    fun isPinned(key: String): Boolean {
        if (covers.isEmpty()) return false
        val parts = key.split('/')
        if (parts.size >= 2 && parts[1].substringBefore('@') == "glyphs") return true // T74: routes may carry a content version
        val (z, x, y) = tileOf(key) ?: return false
        return contains(z, x, y)
    }

    /** T95: the versions of content [name] (`dem`, `osm`) of [source] (a cache key) that pinned covers were fetched at. */
    fun versions(source: String, name: String): Set<String> = held[source]?.get(name).orEmpty()

    /** T95: true when a cover fetched at [version] of content [name] from [source] holds tile z/x/y. */
    fun holds(source: String, name: String, version: String, z: Int, x: Int, y: Int): Boolean =
        covers.any { it.versions[source]?.get(name) == version && it.contains(z, x, y) }

    companion object {
        val NONE = AreaPins(emptyList())

        /** The cover pinned for [area]: its tiles at zooms 0 to its deepest, grown by [margin]. */
        fun cover(area: AreaOfInterest, margin: Int = 1): Map<Int, TileSet> = TileCover.byZoom(area, 0..area.maxZoom, margin)

        /** The tile z/x/y of a cache key ending in `<z>/<x>/<y>.<ext>` under `<source>/<route>[@<version>]/`, or null. */
        fun tileOf(key: String): Triple<Int, Int, Int>? {
            val parts = key.split('/')
            if (parts.size < 5) return null
            val z = parts[parts.size - 3].toIntOrNull() ?: return null
            val x = parts[parts.size - 2].toIntOrNull() ?: return null
            val y = parts[parts.size - 1].substringBefore('.').toIntOrNull() ?: return null
            if (z !in 0..30 || x < 0 || y < 0) return null
            return Triple(z, x, y)
        }

        /**
         * T95: the content names (`dem`, `osm`) whose version an area recorded ([recorded]) differs from what its
         * source serves now ([current]): the area is older than the source. A name either side does not know is
         * not compared (a source without versions, or one not asked yet).
         */
        fun older(recorded: Map<String, String>, current: Map<String, String>): List<String> =
            recorded.entries.filter { (name, v) -> v.isNotEmpty() && current[name].let { c -> !c.isNullOrEmpty() && c != v } }.map { it.key }.sorted()
    }
}
