package io.github.jasondorjeshort.dystopo.app

/**
 * T47 routes (registered from [Routes]):
 * - `GET /shade/{z}/{x}/{y}.png`: a hillshade/slope raster tile generated from the DEM with the current map
 *   options ([io.github.jasondorjeshort.dystopo.app.options.ShadeService]); a map request, always served.
 *   404 when the options need no shade tiles or nothing covers the tile.
 * - `GET /shade` (debug builds): the shade options in force, the style's URL version and the generation
 *   timings (also in `/stats` as `shadeGeneration`).
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
        return Response.json(mapOf("options" to AppSettings.mapOptions.json(AppSettings.units), "urlVersion" to s.urlVersion(), "generation" to s.json()))
    }
}
