package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Mapzen/Tilezen terrain tiles in Terrarium encoding, hosted on AWS Open Data.
 * Global, z0-15, already in Web Mercator. Tiles are cached on disk under [cacheDir].
 */
class TerrariumSource(private val cacheDir: File = File("cache/terrarium")) {
    companion object {
        const val MAX_ZOOM = 15
        const val TILE_SIZE = 256
        private const val URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png"
        const val ATTRIBUTION = "Elevation: Mapzen Terrain Tiles (AWS Open Data), from SRTM, USGS 3DEP/NED, " +
            "GMTED2010, ETOPO1 and national datasets. " +
            "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"
    }

    data class Mosaic(val grid: ElevationGrid, val tiles: Int, val downloaded: Int)

    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private fun cacheFile(tile: TileId) = File(cacheDir, "${tile.z}/${tile.x}/${tile.y}.png")

    /** Returns the cached tile file, downloading it first if needed (and counting the download). */
    private fun ensureCached(tile: TileId, downloaded: AtomicInteger): File {
        val file = cacheFile(tile)
        if (file.exists()) return file
        file.parentFile.mkdirs()
        val request = HttpRequest.newBuilder(URI(URL.format(tile.z, tile.x, tile.y)))
            .header("User-Agent", "dystopo-devtool/0.1")
            .timeout(Duration.ofSeconds(60))
            .build()
        val tmp = File(file.parentFile, "${file.name}.part")
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for tile $tile" }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        downloaded.incrementAndGet()
        return file
    }

    /** Elevation grid covering exactly [bbox] at [zoom], stitched from tiles. */
    fun mosaic(bbox: BoundingBox, zoom: Int): Mosaic {
        require(zoom in 0..MAX_ZOOM) { "zoom must be 0..$MAX_ZOOM" }
        val tiles = WebMercator.tilesCovering(bbox, zoom)
        val downloaded = AtomicInteger()
        tiles.parallelStream().forEach { ensureCached(it, downloaded) }

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

        // Crop the tile mosaic to the bbox, in pixels.
        val px0 = floor((WebMercator.lonToX(bbox.west, zoom) - tx0) * TILE_SIZE).toInt().coerceIn(0, fullW - 1)
        val px1 = ceil((WebMercator.lonToX(bbox.east, zoom) - tx0) * TILE_SIZE).toInt().coerceIn(px0 + 1, fullW)
        val py0 = floor((WebMercator.latToY(bbox.north, zoom) - ty0) * TILE_SIZE).toInt().coerceIn(0, fullH - 1)
        val py1 = ceil((WebMercator.latToY(bbox.south, zoom) - ty0) * TILE_SIZE).toInt().coerceIn(py0 + 1, fullH)
        val cellSize = WebMercator.metersPerPixel(bbox.centerLat, zoom, TILE_SIZE)
        val full = ElevationGrid(fullW, fullH, cellSize, values)
        return Mosaic(full.crop(px0, py0, px1 - px0, py1 - py0), tiles.size, downloaded.get())
    }
}
