package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode
import io.github.jasondorjeshort.dystopo.tiles.ShadeOptions
import io.github.jasondorjeshort.dystopo.tiles.ShadeTileStats
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO

/**
 * The phone's map options (T47, [MapOptions]) for `devtool serve` and `serve --live` (T50). The server keeps no
 * settings: the options travel in the query string, so a page asks for `/style.json?slope=on&night=on` and the
 * style it gets points its tile URLs at the matching routes:
 * - `/style.json` and `/phone` take the [MapOptions.KEYS] (`hillshade=off|single|multi`, `hillshadeIntensity=0..1`,
 *   `slope=off|avalanche|steepness` (T104; `on` is avalanche), `contourInterval=auto|<n>` in the contour units, `contourLabels`, `trails`, `water`, `landcover`, `pois`,
 *   `boundaries`, `drainage` (T174, off by default), `night`); a bad value is a 400 naming it.
 * - `/contours/{z}/{x}/{y}.pbf?interval=<n>` draws a fixed interval from [MapOptions.FIXED_INTERVAL_FROM_ZOOM] up,
 *   as on the phone.
 * - `/shade/{z}/{x}/{y}.png?hillshade=&hillshadeIntensity=&slope=&night=` is the phone's shade raster
 *   ([ShadeTiles], 256 px, transparent where flat or outside the DEM), generated from the server's DEM.
 */
object ServeMapOptions {
    val shadeRe = Regex("^/shade/(\\d+)/(\\d+)/(\\d+)\\.png$")

    /** The decoded query string of [rawQuery] (later keys win). */
    fun query(rawQuery: String?): Map<String, String> =
        (rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }

    /** The map options in [q] over the defaults; throws [IllegalArgumentException] for a bad value. */
    fun parse(q: Map<String, String>, units: ContourUnits): MapOptions =
        MapOptions.parse(q.filterKeys { it in MapOptions.KEYS }, MapOptions(), units)

    /** `&interval=<n>` for the contour tile URL when [o] fixes the interval, else empty. */
    fun contourQuery(o: MapOptions): String = o.contourInterval?.let { "&interval=" + MapOptions.fmt(it) } ?: ""

    /** [base] with the fixed interval of `interval=` in [q] (one of [MapOptions.intervals] for the units), if any. */
    fun contourOptions(base: ContourTileOptions, q: Map<String, String>): ContourTileOptions {
        val raw = q["interval"]?.trim()?.takeIf { it.isNotEmpty() && it != "auto" } ?: return base
        val v = raw.toDoubleOrNull()
        require(v != null && MapOptions.intervals(base.units).any { it == v }) {
            "interval must be auto or one of ${MapOptions.intervals(base.units).map { MapOptions.fmt(it) }} (${base.units.symbol}; got $raw)"
        }
        return base.copy(interval = v, intervalFromZoom = MapOptions.FIXED_INTERVAL_FROM_ZOOM)
    }

    /** The shade tile URL template under [base] for [o], or null when the style needs no shade tiles. */
    fun shadeTiles(base: String, o: MapOptions): String? = o.shade?.let { s ->
        val q = linkedMapOf(
            "hillshade" to s.hillshade.key, "hillshadeIntensity" to MapOptions.fmt(s.intensity),
            "slope" to s.slope.key, "night" to if (s.night) "on" else "off", // T104: off, avalanche or steepness
        )
        "$base/shade/{z}/{x}/{y}.png?" + q.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8) }
    }

    /** The shade options of a `/shade/` request; throws [IllegalArgumentException] for a bad value. */
    fun shadeOptions(q: Map<String, String>): ShadeOptions {
        val o = parse(q.filterKeys { it in setOf("hillshade", "hillshadeIntensity", "slope", "night") }, ContourUnits.METERS)
        // A shade request draws what it names; hillshade=single at full intensity is an overlay here too.
        val hs = o.hillshade
        return ShadeOptions(hs, if (hs == HillshadeMode.OFF) 0.0 else o.hillshadeIntensity, o.slope, o.night)
    }

    /** Shade tile z/x/y from [dem] as a PNG; [stats] records the timing. */
    fun shadePng(dem: DemPyramid, z: Int, x: Int, y: Int, options: ShadeOptions, stats: ShadeTileStats? = null): ByteArray {
        require(z in 0..22 && x >= 0 && y >= 0 && x < (1 shl z) && y < (1 shl z)) { "tile $z/$x/$y out of range" }
        val started = System.nanoTime()
        val plan = ShadeTiles.plan(z, x, y, dem.maxZoom)
        val grid = ShadeTiles.sample(dem, plan)
        val sampled = System.nanoTime()
        val tile = ShadeTiles.render(grid, plan, options)
        val encodeStart = System.nanoTime()
        val img = BufferedImage(tile.size, tile.size, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, tile.size, tile.size, tile.argb, 0, tile.size)
        val png = ByteArrayOutputStream(16 * 1024).also { check(ImageIO.write(img, "png", it)) { "no PNG writer" } }.toByteArray()
        val done = System.nanoTime()
        stats?.add(plan, tile, (done - started) / 1e6, (sampled - started) / 1e6, (done - encodeStart) / 1e6, png.size, options)
        return png
    }
}
