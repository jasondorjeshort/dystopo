package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.TileId
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/**
 * Mapzen/Tilezen terrain tiles in Terrarium encoding, hosted on AWS Open Data.
 * Global, z0-15, already in Web Mercator. A frozen ~2017 mosaic: mostly 10 m NED in the US,
 * 30-90 m SRTM and similar elsewhere. Tiles are cached on disk under [cacheDir].
 * Heights are in each underlying source's own datum and are not converted; use for comparison only.
 */
class TerrariumSource(private val cacheDir: File = File("cache/terrarium")) : ElevationSource {
    companion object {
        const val MAX_ZOOM = 15
        const val TILE_SIZE = 256
        private const val URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png"
    }

    override val id = "terrarium"
    override val attribution = "Elevation: Mapzen Terrain Tiles (AWS Open Data), from SRTM, USGS 3DEP/NED, " +
        "GMTED2010, ETOPO1 and national datasets. " +
        "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"

    private fun cacheFile(tile: TileId) = File(cacheDir, "${tile.z}/${tile.x}/${tile.y}.png")

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        require(window.zoom in 0..MAX_ZOOM) { "terrarium zoom must be 0..$MAX_ZOOM" }
        require(window.tileSize == TILE_SIZE) { "terrarium tiles are $TILE_SIZE px" }
        val tiles = window.tiles()
        val downloaded = AtomicLong()
        tiles.parallelStream().forEach { tile ->
            val file = cacheFile(tile)
            if (!file.exists()) {
                Http.download(URL.format(tile.z, tile.x, tile.y), file)
                downloaded.incrementAndGet()
            }
        }

        val tx0 = tiles.minOf { it.x }
        val ty0 = tiles.minOf { it.y }
        val fullW = (tiles.maxOf { it.x } - tx0 + 1) * TILE_SIZE
        val fullH = (tiles.maxOf { it.y } - ty0 + 1) * TILE_SIZE
        val values = FloatArray(fullW * fullH)
        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        for (tile in tiles) {
            val image = ImageIO.read(cacheFile(tile)) ?: error("could not decode tile $tile")
            image.getRGB(0, 0, TILE_SIZE, TILE_SIZE, pixels, 0, TILE_SIZE)
            val ox = (tile.x - tx0) * TILE_SIZE
            val oy = (tile.y - ty0) * TILE_SIZE
            for (y in 0 until TILE_SIZE) {
                for (x in 0 until TILE_SIZE) {
                    values[(oy + y) * fullW + ox + x] = Terrarium.elevation(pixels[y * TILE_SIZE + x])
                }
            }
        }
        val full = ElevationGrid(fullW, fullH, window.cellSizeMeters, values)
        val grid = full.crop(window.x0 - tx0 * TILE_SIZE, window.y0 - ty0 * TILE_SIZE, window.width, window.height)
        return ElevationSource.Result(grid, mapOf("tiles" to tiles.size.toLong(), "tilesDownloaded" to downloaded.get()))
    }
}
