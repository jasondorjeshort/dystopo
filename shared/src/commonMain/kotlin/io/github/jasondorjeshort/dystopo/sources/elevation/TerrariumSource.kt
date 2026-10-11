package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.png.TerrariumPngDecoder

/**
 * Mapzen/Tilezen terrain tiles in Terrarium encoding, hosted on AWS Open Data.
 * Global, z0-15, already in Web Mercator. A frozen ~2017 mosaic: mostly 10 m NED in the US,
 * 30-90 m SRTM and similar elsewhere. Tiles are cached on disk under [cacheDir].
 * Heights are in each underlying source's own datum and are not converted; use for comparison only.
 */
class TerrariumSource(private val cacheDir: String = SourceIo.dir("terrarium")) : ElevationSource {
    companion object {
        const val MAX_ZOOM = 15
        const val TILE_SIZE = 256
        private const val URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"

        private val decoder by lazy { TerrariumPngDecoder(zlibDecompressor, platformImageDecoder()) }
    }

    override val id = "terrarium"
    override val registryIds = listOf("terrarium")

    private fun cacheFile(tile: TileId) = "$cacheDir/${tile.z}/${tile.x}/${tile.y}.png"

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        require(window.zoom in 0..MAX_ZOOM) { "terrarium zoom must be 0..$MAX_ZOOM" }
        require(window.tileSize == TILE_SIZE) { "terrarium tiles are $TILE_SIZE px" }
        val tiles = window.tiles()
        val downloaded = Counter()
        parallelForEach(tiles) { tile ->
            val file = cacheFile(tile)
            if (!SourceFiles.exists(file)) {
                SourceFiles.write(file, SourceIo.download("$URL/${tile.z}/${tile.x}/${tile.y}.png"))
                downloaded.increment()
            }
        }

        val tx0 = tiles.minOf { it.x }
        val ty0 = tiles.minOf { it.y }
        val fullW = (tiles.maxOf { it.x } - tx0 + 1) * TILE_SIZE
        val fullH = (tiles.maxOf { it.y } - ty0 + 1) * TILE_SIZE
        val values = FloatArray(fullW * fullH)
        for (tile in tiles) {
            val bytes = SourceFiles.read(cacheFile(tile)) ?: error("could not read tile $tile")
            val tileValues = decoder.elevations(bytes, TILE_SIZE, TILE_SIZE)
            val ox = (tile.x - tx0) * TILE_SIZE
            val oy = (tile.y - ty0) * TILE_SIZE
            for (y in 0 until TILE_SIZE) {
                for (x in 0 until TILE_SIZE) {
                    values[(oy + y) * fullW + ox + x] = tileValues[y * TILE_SIZE + x]
                }
            }
        }
        val full = ElevationGrid(fullW, fullH, window.cellSizeMeters, values)
        val grid = full.crop(window.x0 - tx0 * TILE_SIZE, window.y0 - ty0 * TILE_SIZE, window.width, window.height)
        return ElevationSource.Result(
            grid, mapOf("tiles" to tiles.size.toLong(), "tilesDownloaded" to downloaded.get()),
            ElevationSource.provenanceIfData(grid, registryIds),
        )
    }
}
