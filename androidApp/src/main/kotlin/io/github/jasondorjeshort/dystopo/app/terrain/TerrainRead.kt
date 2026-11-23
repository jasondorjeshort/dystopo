package io.github.jasondorjeshort.dystopo.app.terrain

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.TileService
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.ElevationSource
import io.github.jasondorjeshort.dystopo.dem.MergedTerrain
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid

/**
 * T340: the one terrain read for a chosen point that the Horizon view (`Gnss.sweep`), the Panorama (`PanoramaData`) and the
 * viewshed (`ViewshedSession`) share: [MergedTerrain]'s rule (the open maps cell by cell, the deepest that holds data first, a
 * coarser one where it ends, then the online source) over the maps [PackSet][io.github.jasondorjeshort.dystopo.app.PackSet]
 * opens, each through a small [DemPyramid] of its own, and the online DEM ([OnlineTiles.pyramid]: the same fetcher, so the
 * same Mobile data and session limit rules as the map's online tiles and the Horizon view's read before T340).
 *
 * A read holds the tile service it was opened on ([DystopoApp.leaseTiles]) until [close], so a map opening or closing
 * meanwhile does not close the archives under it. [epoch] names the maps open and the online source's generation (it
 * changes when the network comes back), so a view can tell that an answer it keeps was made before either changed.
 */
class TerrainRead private constructor(private val lease: AutoCloseable, val tiles: TileService, withOnline: Boolean) : AutoCloseable {
    private val pyramids = HashMap<String, DemPyramid>()
    @Volatile var tilesRead = 0
        private set
    @Volatile var bytesRead = 0L
        private set
    val tally = MergedTerrain.Tally()

    /** The online DEM, when a source is set and the caller asks for it. */
    val online: OnlineTiles? = if (withOnline) tiles.online else null

    val sources: List<ElevationSource> = MergedTerrain.order(
        tiles.packs.demPacks.map { pk ->
            val h = pk.header
            val box = PackBounds(h.minLon, h.minLat, h.maxLon, h.maxLat)
            val name = tiles.packs.packOf(pk) ?: pk.name
            ElevationSource(name, h.maxZoom, box, MergedTerrain.wholeTileReach(box, h.minZoom)) { w ->
                val p = synchronized(pyramids) {
                    pyramids.getOrPut(name) {
                        DemPyramid(h.minZoom, h.maxZoom, TileService.demDecoder(), cacheTiles = 48) { z, x, y ->
                            pk.tile(z, x, y)?.also { b -> synchronized(this) { tilesRead++; bytesRead += b.size } }
                        }
                    }
                }
                p.window(w.zoom, w.x0, w.y0, w.width, w.height)
            }
        } + listOfNotNull(online?.let { o -> ElevationSource(MergedTerrain.ONLINE, OnlineTiles.DEM_MAX_ZOOM, null) { w -> o.pyramid().window(w.zoom, w.x0, w.y0, w.width, w.height) } }),
    )

    /** [w] by the rule, counted in [tally]. */
    fun read(w: MercatorWindow): ElevationGrid = MergedTerrain.read(w, sources, tally)

    /** The zoom to read ([lat], [lon]) at, at most [cap]; null when nothing answers there. */
    fun pointZoom(lat: Double, lon: Double, cap: Int): Int? = MergedTerrain.pointZoom(sources, lat, lon, cap)

    /** What answered so far, in the views' words and as JSON. */
    fun answered(): Answered = Answered(tally.maps, tally.online, tally.complete, tally.json() + linkedMapOf("tiles" to tilesRead, "bytes" to bytesRead, "onlineAsked" to (online != null)))

    override fun close() {
        synchronized(pyramids) { pyramids.clear() }
        lease.close()
    }

    /**
     * What a read took its cells from: [maps] the offline maps (by name, in the order they first gave cells), [online] the
     * online source, [complete] no cell left without data. [text] is the terrain line's "from the ..." part.
     */
    class Answered(val maps: List<String>, val online: Boolean, val complete: Boolean, val counts: Map<String, Any?>) {
        val text: String? get() = TerrainText.from(maps.size, online)
        fun json(): Map<String, Any?> = linkedMapOf<String, Any?>("text" to text, "maps" to maps, "online" to online, "complete" to complete) + counts
    }

    companion object {
        /** Opens a read on the tile service now published; [online] false reads the offline maps alone (the viewshed). */
        fun open(online: Boolean = true): TerrainRead {
            val app = DystopoApp.instance
            val lease = app.leaseTiles()
            return try {
                TerrainRead(lease, app.tiles, online)
            } catch (e: Throwable) {
                lease.close()
                throw e
            }
        }

        /**
         * The maps open and the online source's generation now: a kept answer made under another epoch may be stale (a map
         * opened or closed: any answer; the network came back: an answer with cells missing).
         */
        fun epoch(): Epoch {
            val t = DystopoApp.instance.tiles
            return Epoch(System.identityHashCode(t.packs), t.online?.fetcher?.generation ?: -1)
        }
    }

    /** [maps] the opened map set's identity, [network] the online source's generation. */
    data class Epoch(val maps: Int, val network: Int) {
        /** Whether an answer made under this epoch, [complete] or not, should be read again now ([now]). */
        fun stale(now: Epoch, complete: Boolean): Boolean = maps != now.maps || (!complete && network != now.network)
    }
}

/** T340: the words of the merged read (ADR 0031: built in alpha, the owner adjusts them). */
object TerrainText {
    /** The terrain line's source after "from the": "offline map", "2 offline maps", "online source", "offline map and the online source". */
    fun from(maps: Int, online: Boolean): String? {
        val m = when (maps) {
            0 -> null
            1 -> "offline map"
            else -> "$maps offline maps"
        }
        return when {
            m != null && online -> "$m and the online source"
            m != null -> m
            online -> "online source"
            else -> null
        }
    }
}
