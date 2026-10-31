package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.options.SAFETY_LINE
import io.github.jasondorjeshort.dystopo.app.options.SunShade
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
 *
 * T188, sun and shade ([SunShade], `android-app.md` › Sun and shade):
 * - `GET /shade/sun/{z}/{x}/{y}.png?m=<epoch minute>`: T181's sun-and-shade tile for that minute (the cool tint where the
 *   terrain hides the sun, all of it at night); a map request, always served; 400 without `m`, 404 where nothing covers it.
 * - `GET /shade` gains `sun`: [SunShade.json] (`on`, `date`, `today`, `dateLabel`, `instantMs`, `instantUtc`,
 *   `instantLocal`, `zone`, `zoneName`, `source`: `now`, `beforeSunset`, `noon`, `slider`, `step` or `route`; `centre`,
 *   `sun` (azimuth and altitude at the centre), `flatDay`, `slider` (`startMs`, `endMs`, `valueMs`, `fraction`, the end
 *   labels, the steps), `tileUrl` without the token, `layer`) plus `generation`: the sun tiles' timings by zoom,
 *   `served`, `diskCacheHits` and `recentDetail` (per tile the sun's altitude, `cellsRead`, `marchSteps`, the parts' ms).
 *   `GET /shade/sun` is that `sun` alone.
 * - `POST /shade/sun?on=1|0&date=YYYY-MM-DD|today&time=HH:mm&at=<epoch ms or ISO instant>&fraction=0..1&step=<quarter
 *   hours>`: what the Map options' controls do, in that order (a date turns the shade on and opens the day by the rule;
 *   `time` is a clock time on the day shown in the phone's zone); answers `GET /shade/sun`.
 * - `GET /shade/sun/bench?lat=&lon=&z=&at=<epoch ms or ISO>&repeat=3`: makes that tile `repeat` times past every cache
 *   and reports each run's times, the sun's altitude, the cells read and the ray samples (the phone's tile time).
 */
object ShadeRoutes {
    private val tile = Regex("^/shade/(-?\\d+)/(-?\\d+)/(-?\\d+)\\.png$")
    private val sunTile = Regex("^/shade/sun/(-?\\d+)/(-?\\d+)/(-?\\d+)\\.png$")

    fun handle(app: DystopoApp, r: Request): Response? {
        tile.matchEntire(r.path)?.let { m ->
            val (zs, xs, ys) = m.destructured
            val (zxy, error) = RequestChecks.tile(zs, xs, ys)
            val (z, x, y) = zxy ?: return Response.text("$error\n", 400)
            val res = app.tiles.shadeTile(z, x, y)
            return Response(res.status, res.contentType, res.body)
        }
        sunTile.matchEntire(r.path)?.let { m ->
            val (zs, xs, ys) = m.destructured
            val (zxy, error) = RequestChecks.tile(zs, xs, ys)
            val (z, x, y) = zxy ?: return Response.text("$error\n", 400)
            val minute = r.query["m"]?.toLongOrNull() ?: return Response.text("m (epoch minute) required\n", 400)
            val t = app.tiles
            val res = t.shade.sunTile(z, x, y, minute, packCovers = t.online?.packCoversDem(z, x, y) ?: true)
            return Response(res.status, res.contentType, res.body)
        }
        if (!BuildConfig.DEBUG_ENDPOINTS) return null
        when (r.method to r.path) {
            "GET" to "/shade/sun" -> return Response.json(sunJson(app))
            "POST" to "/shade/sun" -> return sunPost(app, r)
            "GET" to "/shade/sun/bench" -> return sunBench(app, r)
        }
        if (r.path != "/shade") return null
        if (r.method != "GET") return Response.json(mapOf("error" to "GET"), 405)
        val s = app.tiles.shade
        return Response.json(
            mapOf(
                "options" to AppSettings.mapOptions.json(AppSettings.units), "urlVersion" to s.urlVersion(), "generation" to s.json(),
                "slopeLegend" to slopeLegend(AppSettings.mapOptions.slope),
                "sun" to sunJson(app), // T188
            ),
        )
    }

    private fun sunJson(app: DystopoApp): Map<String, Any?> = app.map.onMain { SunShade.json() } + mapOf("generation" to app.tiles.shade.sunJson())

    /** T188: `POST /shade/sun`: the Map options' sun controls, on the main thread. */
    private fun sunPost(app: DystopoApp, r: Request): Response {
        val q = r.query
        val date = q["date"]?.let { d ->
            if (d == "today") null else runCatching { java.time.LocalDate.parse(d) }.getOrElse { return Response.json(mapOf("error" to "date must be YYYY-MM-DD or today"), 400) }
        }
        val at = q["at"]?.let { instant(it) ?: return Response.json(mapOf("error" to "at must be epoch ms or an ISO instant"), 400) }
        val time = q["time"]?.let { s ->
            runCatching { java.time.LocalTime.parse(s) }.getOrElse { return Response.json(mapOf("error" to "time must be HH:mm"), 400) }
        }
        val fraction = q["fraction"]?.let { it.toDoubleOrNull() ?: return Response.json(mapOf("error" to "fraction must be 0..1"), 400) }
        val step = q["step"]?.let { it.toIntOrNull() ?: return Response.json(mapOf("error" to "step must be a whole number"), 400) }
        app.map.onMain {
            when (q["on"]) {
                "1", "on", "true" -> SunShade.turn(true)
                "0", "off", "false" -> SunShade.turn(false)
            }
            if (q.containsKey("date")) SunShade.pickDate(date ?: SunShade.today())
            if (time != null) SunShade.setInstant(SunShade.date.atTime(time).atZone(SunShade.zone().toZoneId()).toInstant().toEpochMilli(), "route")
            if (at != null) SunShade.setInstant(at, "route")
            if (fraction != null) SunShade.setInstant(SunShade.range.at(fraction), "route")
            if (step != null) SunShade.step(step)
        }
        return Response.json(sunJson(app))
    }

    /** T188: `GET /shade/sun/bench`. */
    private fun sunBench(app: DystopoApp, r: Request): Response {
        val q = r.query
        val lat = q["lat"]?.toDoubleOrNull()
        val lon = q["lon"]?.toDoubleOrNull()
        val z = q["z"]?.toIntOrNull()
        if (lat == null || lon == null || z == null || z !in 0..20) return Response.json(mapOf("error" to "lat, lon and z (0..20) required"), 400)
        val at = q["at"]?.let { instant(it) ?: return Response.json(mapOf("error" to "at must be epoch ms or an ISO instant"), 400) } ?: System.currentTimeMillis()
        val res = app.tiles.shade.sunBench(lat, lon, z, at, q["repeat"]?.toIntOrNull() ?: 3)
            ?: return Response.json(mapOf("error" to "no elevation data covers that tile"), 404)
        return Response.json(res + mapOf("atUtc" to java.time.Instant.ofEpochMilli(at).toString()))
    }

    private fun instant(s: String): Long? = s.toLongOrNull() ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()

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
