package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ElevationImageDecoder
import kotlin.jvm.Synchronized

/**
 * T357 (ADR 0066 decision 4, proposal 0066 C2): the elevation, shade and contour tiles across a map's edge, composed cell by
 * cell from the maps that meet there. Each cell is [MergedTerrain.read]'s answer over [sources] (the deepest first by the
 * lookup's rule), with the one blend weight [EdgeBlend.weight] near each map's box, so a finer map fades into the coarser
 * one over [EdgeBlend.BAND_M] inside its box and every tile, of any size or zoom, gives the same value at the same place:
 * no seam on a tile boundary, none along the box. The sky views' terrain read is the same function, so the map and the
 * Horizon view, the Panorama and the viewshed agree at the edge.
 *
 * [pyramid] is a [DemPyramid] over the composed tiles (its own small cache), for the contour, shade and cliff paths that
 * sample a pyramid; [window] reads any window directly. Tiles away from every band stay one map's own ([joins] says which
 * tiles are edge tiles); vector tiles are never composed. [key] names the maps a tile is made from, for the caches: a tile
 * made from two maps is kept under both identities, so switching one off or removing it leaves those tiles unread.
 */
class MergedTile(val sources: List<Source>, val bandM: Double = EdgeBlend.BAND_M, cacheTiles: Int = 64) {
    /**
     * One map's elevation: [identity] its cache identity, [bounds] its box, [minZoom]..[maxZoom] the zooms it stores, [chosen]
     * the map chosen last (the tie between two of equal depth), [pyramid] its own tiles.
     */
    class Source(val name: String, val identity: String, val bounds: PackBounds, val minZoom: Int, val maxZoom: Int, val pyramid: DemPyramid, val chosen: Boolean = false)

    init {
        require(sources.isNotEmpty()) { "a merged tile needs a map" }
    }

    private val byName = sources.associateBy { it.name }

    /** Hands [pyramid] the composed values as they are: no encoding, no copy ([Handoff]). */
    private val handoff = Handoff()

    /** The maps as the terrain read's sources (a map's whole tiles reach past its box, as `TerrainRead` gives them). */
    private val elevation: List<ElevationSource> = MergedTerrain.order(
        sources.map { s ->
            ElevationSource(s.name, s.maxZoom, s.bounds, MergedTerrain.wholeTileReach(s.bounds, s.minZoom), s.chosen) { w -> s.pyramid.window(w.zoom, w.x0, w.y0, w.width, w.height) }
        },
    )

    /** The maps' identities in the read's order joined by [KEY_JOIN]: the cache identity of the tiles made here. */
    val key: String = elevation.joinToString(KEY_JOIN) { byName.getValue(it.name).identity }

    /** The maps' names in the read's order. */
    val names: List<String> = elevation.map { it.name }

    /** The elevations of the global pixel window ([x0], [y0], [width] x [height]) at zoom [z] (NaN where no map has any). */
    fun window(z: Int, x0: Int, y0: Int, width: Int, height: Int, tally: MergedTerrain.Tally? = null): ElevationGrid =
        MergedTerrain.read(MercatorWindow(z, x0, y0, width, height), elevation, tally, bandM = bandM)

    /** Elevation tile z/x/y (256 px), or null when no map has a value in it. */
    fun tile(z: Int, x: Int, y: Int): ElevationGrid? = window(z, x * TILE, y * TILE, TILE, TILE).takeUnless { g -> g.values.all { it.isNaN() } }

    /** A pyramid over the composed tiles, from the shallowest map's first zoom to the deepest map's last (deeper overzooms). */
    val pyramid: DemPyramid = DemPyramid(sources.minOf { it.minZoom }, sources.maxOf { it.maxZoom }, handoff, cacheTiles) { z, x, y ->
        tile(z, x, y)?.let { handoff.put(it.values) }
    }

    /**
     * Each map's share of the [size] x [size] pixels of tile z/x/y (by the pixel's centre), in [names]' order: the cascade of
     * [EdgeBlend.weight]s by position alone (the last map takes what is left). A raster whose look depends on the map's depth
     * (a shade tile's sample zoom follows the deepest zoom stored) is drawn once per depth and mixed by these shares
     * ([mix]), so an edge tile matches its one-map neighbours on both sides: the coarse map's own drawing outside the fine
     * box, the fine map's past the band.
     */
    fun shares(z: Int, x: Int, y: Int, size: Int): List<FloatArray> {
        val ordered = names.map { byName.getValue(it) }
        val out = ordered.map { FloatArray(size * size) }
        // [EdgeBlend.weight] from each column's and each row's parts, worked out once (the same numbers).
        val lons = DoubleArray(size) { WebMercator.xToLon(x + (it + 0.5) / size, z) }
        val lonM = ordered.map { s -> DoubleArray(size) { EdgeBlend.lonInsideDeg(s.bounds, lons[it]) * EdgeBlend.M_PER_DEG } }
        for (j in 0 until size) {
            val lat = WebMercator.yToLat(y + (j + 0.5) / size, z)
            val cosLat = EdgeBlend.cosLat(lat)
            for (i in 0 until size) {
                val lon = lons[i]
                var rem = 1.0
                for ((k, s) in ordered.withIndex()) {
                    val b = s.bounds
                    val w = when {
                        k == ordered.lastIndex -> 1.0
                        lon <= b.minLon || lon >= b.maxLon || lat <= b.minLat || lat >= b.maxLat -> 0.0
                        else -> EdgeBlend.weightOfM(EdgeBlend.latInsideM(b, lat), lonM[k][i], cosLat, bandM)
                    }
                    val part = rem * w
                    out[k][j * size + i] = part.toFloat()
                    rem -= part
                    if (rem <= 0.0) break
                }
            }
        }
        return out
    }

    /** The deepest zoom each map stores, in [names]' order (the depth a raster of that map's own is drawn for). */
    val depths: List<Int> = names.map { byName.getValue(it).maxZoom }

    companion object {
        const val TILE = 256

        /** ARGB pixels mixed channel by channel: [layers] (one drawing per map, in [names]' order) weighted by [shares] ([MergedTile.shares]). */
        fun mix(layers: List<IntArray>, shares: List<FloatArray>): IntArray {
            require(layers.size == shares.size && layers.isNotEmpty())
            return IntArray(layers[0].size) { p ->
                var a = 0.0
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (k in layers.indices) {
                    val s = shares[k][p].toDouble()
                    if (s <= 0.0) continue
                    val c = layers[k][p]
                    a += s * (c ushr 24 and 0xff)
                    r += s * (c ushr 16 and 0xff)
                    g += s * (c ushr 8 and 0xff)
                    b += s * (c and 0xff)
                }
                fun q(v: Double) = (v + 0.5).toInt().coerceIn(0, 255)
                (q(a) shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
            }
        }

        /** Between the identities in [key] (a pack identity is hex: `ContourCache.keeps` splits on it). */
        const val KEY_JOIN = "+"

        /** How far past a tile its readers sample (a contour tile's halo, a shade tile's, a cliff's margin), as a fraction of the tile. */
        const val MARGIN = 0.25

        /** Tile z/x/y's box widened by [margin] of a tile on every side: what its readers may sample. */
        fun extent(z: Int, x: Int, y: Int, margin: Double = MARGIN): PackBounds = PackBounds(
            WebMercator.xToLon(x - margin, z), WebMercator.yToLat((y + 1 + margin).coerceAtMost((1 shl z).toDouble()), z),
            WebMercator.xToLon(x + 1 + margin, z), WebMercator.yToLat((y - margin).coerceAtLeast(0.0), z),
        )

        /**
         * Whether a tile whose readers sample [extent] is an edge tile: of the maps' [boxes] (in the read's order) at least two
         * overlap it, and it reaches into the band of one that has another after it (the last one's own edge has nothing to
         * blend into). Elsewhere the first map holding the tile answers alone, as before: inside a fine map past the band
         * every weight is 1, and outside it the fine map gives nothing.
         */
        fun joins(boxes: List<PackBounds>, extent: PackBounds, bandM: Double = EdgeBlend.BAND_M): Boolean {
            val over = boxes.filter { EdgeBlend.overlaps(it, extent) }
            if (over.size < 2) return false
            return over.dropLast(1).any { EdgeBlend.reachesBand(it, extent, bandM) }
        }
    }
}

/**
 * T357: the composed values of [MergedTile.pyramid]'s tiles passed to it as they are. The pyramid's fetch puts a tile's
 * values here and hands over an 8-byte ticket; its decode, on the same load, takes them back by the ticket. No encoding, no
 * copy: the values the pyramid keeps are the ones [MergedTerrain.read] made.
 */
internal class Handoff : ElevationImageDecoder {
    private val waiting = HashMap<Long, FloatArray>()
    private var next = 0L

    @Synchronized
    fun put(values: FloatArray): ByteArray {
        val t = next++
        waiting[t] = values
        return ByteArray(8) { (t ushr (8 * it)).toByte() }
    }

    @Synchronized
    override fun elevations(bytes: ByteArray, width: Int, height: Int): FloatArray {
        var t = 0L
        for (i in 0 until 8) t = t or ((bytes[i].toLong() and 0xff) shl (8 * i))
        val v = waiting.remove(t) ?: error("no composed tile for ticket $t")
        require(v.size == width * height) { "composed tile of ${v.size} cells for ${width}x$height" }
        return v
    }

    override fun decode(bytes: ByteArray): ArgbImage = throw UnsupportedOperationException("composed elevations have no image")
}

/**
 * T357: elevations carried as raw little-endian floats, the "encoding" of [MergedTile.pyramid]'s tiles: the composed
 * values reach the pyramid's cache as they are, with no Terrarium rounding and no PNG to make or read.
 */
object RawElevations : ElevationImageDecoder {
    fun encode(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) {
            val b = v.toRawBits()
            out[4 * i] = b.toByte()
            out[4 * i + 1] = (b ushr 8).toByte()
            out[4 * i + 2] = (b ushr 16).toByte()
            out[4 * i + 3] = (b ushr 24).toByte()
        }
        return out
    }

    override fun elevations(bytes: ByteArray, width: Int, height: Int): FloatArray {
        require(bytes.size == width * height * 4) { "raw elevations: ${bytes.size} bytes for ${width}x$height" }
        return FloatArray(width * height) { i ->
            Float.fromBits(
                (bytes[4 * i].toInt() and 0xff) or ((bytes[4 * i + 1].toInt() and 0xff) shl 8) or
                    ((bytes[4 * i + 2].toInt() and 0xff) shl 16) or ((bytes[4 * i + 3].toInt() and 0xff) shl 24),
            )
        }
    }

    override fun decode(bytes: ByteArray): ArgbImage = throw UnsupportedOperationException("raw elevations have no image")
}
