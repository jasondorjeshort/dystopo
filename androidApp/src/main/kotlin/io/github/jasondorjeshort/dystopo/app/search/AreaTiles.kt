package io.github.jasondorjeshort.dystopo.app.search

import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.areas.AreaDownload
import io.github.jasondorjeshort.dystopo.app.areas.Areas
import io.github.jasondorjeshort.dystopo.app.online.OnlineFetcher
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.TileSet
import io.github.jasondorjeshort.dystopo.areas.TilesItem
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import io.github.jasondorjeshort.dystopo.search.SearchIndexBuilder
import io.github.jasondorjeshort.dystopo.search.TileListSource

/**
 * T377: the read of an area kept as tiles: its vector tiles in the online cache, for search's index ([PlaceSearch]) and
 * for the sky views' peak names (T378, `HorizonData`). An area is "kept as tiles" when it is finished, has no pack and is
 * readable: the set [Areas.lookupHoldings] gives as `Kind.AREA` holdings (the vector profile OpenMapTiles, the online
 * source's), here with their records (id, versions, source), which the holdings do not carry. Its vector tiles are the
 * `osm` item of its manifest (the rows its download walked, margin included), under the cache keys a download writes
 * ([Areas.keyPrefixes]: the current version of each source, then an older one a pin holds), gzipped or not
 * ([OnlineFetcher.gunzipIfNeeded]). A download skips a vector tile that an open map answers, so an area drawn over an open
 * pack holds fewer tiles than its shape: [present] and [source] say which are there. Any thread but the UI one (the
 * cache's files and the manifest are read).
 */
object AreaTiles {
    /** The areas kept as tiles (finished, no pack, readable): [Areas.lookupHoldings]' `Kind.AREA` set, as records. */
    fun areas(): List<Areas.Record> = Areas.all().filter { keptAsTiles(it) }

    /** Pure: [r] is an area kept as tiles (the filter [Areas.lookupHoldings] applies). */
    fun keptAsTiles(r: Areas.Record): Boolean = r.pack == null && r.opaque == null && r.state == "done"

    /** [r]'s vector tile item: its saved manifest's, else the one the phone makes for its shape; null when it has none. */
    fun vectorItem(r: Areas.Record): TilesItem? {
        val m = Areas.manifest(r.id) ?: runCatching { AreaDownload.localManifest(r.area, r.source) }.getOrNull() ?: return null
        return m.items.filterIsInstance<TilesItem>().firstOrNull { it.route == AreaManifest.ROUTE_OSM }
    }

    /** [r]'s vector tile rows per zoom (empty when it has no vector item). */
    fun vectorCover(r: Areas.Record): Map<Int, TileSet> = vectorItem(r)?.tiles.orEmpty()

    /** The cache key prefixes [r]'s vector tiles may sit under, in the order a lookup tries them. */
    fun prefixes(r: Areas.Record): List<String> = Areas.keyPrefixes(AreaManifest.ROUTE_OSM, r.source)

    /**
     * The shared read (T378 calls it): the MVT bytes of [r]'s vector tile [z]/[x]/[y] from the online cache, unpacked, or
     * null when the cache does not hold it. [prefixes] may be passed when many tiles are read ([prefixes] of [r]).
     */
    fun vectorTile(r: Areas.Record, z: Int, x: Int, y: Int, prefixes: List<String> = prefixes(r), ext: String = EXT): ByteArray? {
        val cache = OnlineService.get(io.github.jasondorjeshort.dystopo.app.DystopoApp.instance).cache
        for (p in prefixes) cache.get("$p/$z/$x/$y.$ext")?.let { return OnlineFetcher.gunzipIfNeeded(it) }
        return null
    }

    /** Whether the cache holds [r]'s vector tile [z]/[x]/[y] under one of [prefixes]. */
    fun present(z: Int, x: Int, y: Int, prefixes: List<String>, ext: String = EXT): Boolean {
        val cache = OnlineService.get(io.github.jasondorjeshort.dystopo.app.DystopoApp.instance).cache
        return prefixes.any { cache.contains("$it/$z/$x/$y.$ext") }
    }

    /** [r]'s versions as one string (per source, per name, sorted): part of its index's key. */
    fun versionsKey(r: Areas.Record): String =
        r.versions.toSortedMap().entries.joinToString(";") { (s, v) -> s + "=" + v.toSortedMap().entries.joinToString(",") { (k, x) -> "$k@$x" } }

    /** What [source] found: the tile source and, per scanned zoom, the tiles the area's shape asks for and those missing. */
    class Scan(val source: TileListSource, val planned: Map<Int, Int>, val missing: Map<Int, Int>, val zooms: List<Int>, val ms: Long) {
        val tilesMissing: Int get() = missing.values.sum()
        val tilesPlanned: Int get() = planned.values.sum()
        val deepestZoom: Int? get() = zooms.lastOrNull()
    }

    /**
     * [r]'s vector tiles as a [TileListSource] for the index, at the zooms the builder scans
     * ([SearchIndexBuilder.zoomsFor] of the item's zooms): each tile of the item's rows at those zooms that the cache holds
     * now. The source's key is the area's id and versions, so its fingerprint changes with either and with the tiles
     * present. The reads wait while the app is off the screen (ADR 0071: the work pauses at Home and goes on at the return).
     */
    fun source(r: Areas.Record): Scan? {
        val t0 = System.currentTimeMillis()
        val item = vectorItem(r) ?: return null
        val zooms = item.tiles.keys.takeIf { it.isNotEmpty() }?.let { SearchIndexBuilder.zoomsFor(it.min(), it.max()) }.orEmpty()
        val pre = prefixes(r)
        val present = LinkedHashMap<Int, List<Long>>()
        val planned = LinkedHashMap<Int, Int>()
        val missing = LinkedHashMap<Int, Int>()
        for (z in zooms) {
            val set = item.tiles[z] ?: continue
            val ids = ArrayList<Long>()
            var miss = 0
            set.forEachRow { y, x0, x1 -> for (x in x0..x1) if (present(z, x, y, pre, item.ext)) ids += TileIds.fromZxy(z, x, y) else miss++ }
            present[z] = ids
            planned[z] = set.size
            missing[z] = miss
        }
        val b = r.area.bounds
        val src = TileListSource(
            "${r.id}:${versionsKey(r)}", item.tiles.keys.min(), item.tiles.keys.max(), (b.minLat + b.maxLat) / 2, present,
        ) { z, x, y ->
            Foreground.block() // ADR 0071: off the screen the build waits
            vectorTile(r, z, x, y, pre, item.ext)
        }
        return Scan(src, planned, missing, zooms, System.currentTimeMillis() - t0)
    }

    /** The vector tiles' extension a download writes (`AreaManifest.tileItems`' `osm` item). */
    const val EXT = "pbf"
}
