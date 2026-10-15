package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.options.SAFETY_LINE
import io.github.jasondorjeshort.dystopo.app.options.degrees
import io.github.jasondorjeshort.dystopo.app.options.legendCaption
import io.github.jasondorjeshort.dystopo.app.options.swatch
import io.github.jasondorjeshort.dystopo.render.SlopeShading

/**
 * T47 routes (registered from [Routes]):
 * - `GET /shade/{z}/{x}/{y}.png`: a hillshade/slope raster tile generated from the DEM with the current map
 *   options ([io.github.jasondorjeshort.dystopo.app.options.ShadeService]); a map request, always served.
 *   404 when the options need no shade tiles or nothing covers the tile.
 * - `GET /shade` (debug builds): the shade options in force, the style's URL version, the generation
 *   timings (also in `/stats` as `shadeGeneration`, with T104's `packFloorReads` and `blankAtPackFloor`) and
 *   (T104) `slopeLegend`: the slope shading in force (`off`, `avalanche` or `steepness`), its bands (label,
 *   degrees, colour as `#rrggbb`, and the legend swatch) and the caption and safety line the Map options show.
 */
object ShadeRoutes {
    private val tile = Regex("^/shade/(-?\\d+)/(-?\\d+)/(-?\\d+)\\.png$")

    fun handle(app: DystopoApp, r: Request): Response? {
        tile.matchEntire(r.path)?.let { m ->
            val (zs, xs, ys) = m.destructured
            val (zxy, error) = RequestChecks.tile(zs, xs, ys)
            val (z, x, y) = zxy ?: return Response.text("$error\n", 400)
            val res = app.tiles.shadeTile(z, x, y)
            return Response(res.status, res.contentType, res.body)
        }
        if (r.path != "/shade" || !BuildConfig.DEBUG_ENDPOINTS) return null
        if (r.method != "GET") return Response.json(mapOf("error" to "GET"), 405)
        val s = app.tiles.shade
        return Response.json(
            mapOf(
                "options" to AppSettings.mapOptions.json(AppSettings.units), "urlVersion" to s.urlVersion(), "generation" to s.json(),
                "slopeLegend" to slopeLegend(AppSettings.mapOptions.slope),
            ),
        )
    }

    /** T104: what the Map options' slope legend shows for [shading]. */
    fun slopeLegend(shading: SlopeShading): Map<String, Any?> = linkedMapOf(
        "shading" to shading.key,
        "caption" to legendCaption(shading).ifEmpty { null },
        "safetyLine" to SAFETY_LINE,
        "bands" to shading.bands.map { b ->
            linkedMapOf(
                "label" to b.label, "shown" to degrees(b.label), "minDeg" to b.minDeg, "maxDeg" to minOf(b.maxDeg, 90.0),
                "rgb" to hex(b.rgb), "swatch" to hex(swatch(b.rgb)),
            )
        },
    )

    private fun hex(rgb: Int) = String.format(java.util.Locale.US, "#%06x", rgb and 0xFFFFFF)
}
