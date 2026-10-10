package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits

/**
 * Debug routes for [AppSettings] (registered from [Routes]):
 * - `GET /settings`: the current settings as JSON.
 * - `POST /settings?textScale=1.5&units=m&warmup=0` (or a JSON body): validates every value first, then
 *   sets, persists and applies them. `textScale` must lie in the advertised range; `units` is `ft` or `m`
 *   (also `feet`, `metres`/`meters`), or `unset` to clear the choice so the next pack or fix seeds it again;
 *   `warmup` is `1`/`0` (`true`/`false`). Unknown keys and bad values are a 400 and change nothing.
 *   A text scale change reloads the style; a units or warm-up change reopens the tile service (contour
 *   tiles are generated per unit, cached per unit). `server` (T38) sets the online-mode server.
 * - `POST /settings/ui?open=1|0`: opens or closes the settings sheet ([SettingsSheet]).
 */
class SettingsRoutes(private val app: DystopoApp) {
    /** The response for `/settings`, or null for any other path. */
    fun handle(r: Request): Response? {
        if (r.path == "/settings/ui" && r.method == "POST") {
            // Opens (open=1, default) or closes (open=0) the settings sheet, for screenshots.
            app.map.onMain { SettingsUi.visible = r.query["open"] != "0" }
            return Response.json(mapOf("open" to (r.query["open"] != "0")))
        }
        if (r.path != "/settings") return null
        return when (r.method) {
            "GET" -> Response.json(AppSettings.json())
            "POST" -> post(r)
            else -> Response.json(mapOf("error" to "GET or POST"), 405)
        }
    }

    private fun post(r: Request): Response {
        val params = HashMap<String, Any?>(r.query)
        val body = r.bodyText.trim()
        if (body.startsWith("{")) params.putAll(MiniJson.parseObject(body))
        val parsed = when (val p = parse(params)) {
            is Parsed.Error -> return Response.json(mapOf("error" to p.message), 400)
            is Parsed.Ok -> p
        }
        params["server"]?.let { OnlineRoutes.setServer(app, it.toString())?.let { e -> return Response.json(mapOf("error" to e), 400) } } // T38
        apply(parsed)
        return Response.json(AppSettings.json())
    }

    /** Sets, persists and applies validated settings (also used by the settings sheet; call off the main thread). */
    fun apply(parsed: Parsed.Ok) {
        val styleBefore = AppSettings.textScale
        val unitsBefore = AppSettings.units
        val warmupBefore = AppSettings.warmup
        parsed.textScale?.let { AppSettings.setTextScale(it) }
        if (parsed.unitsGiven) AppSettings.setUnits(parsed.units)
        parsed.warmup?.let { AppSettings.setWarmup(it) }
        if (AppSettings.units != unitsBefore || AppSettings.warmup != warmupBefore) {
            app.reload() // new tile service: contour units, warm-up; reloads the style too
        } else if (AppSettings.textScale != styleBefore) {
            val style = app.tiles.style()
            app.map.onMain { app.map.loadStyle(style) }
        }
    }

    sealed class Parsed {
        class Ok(val textScale: Double?, val unitsGiven: Boolean, val units: ContourUnits?, val warmup: Boolean?) : Parsed()
        class Error(val message: String) : Parsed()
    }

    companion object {
        /** `server` (T38) is validated and applied by [OnlineRoutes.setServer]. */
        val KEYS = setOf("textScale", "units", "warmup", "server")

        /** Validates a settings request without applying it (pure, unit-tested). */
        fun parse(params: Map<String, Any?>): Parsed {
            val unknown = params.keys - KEYS
            if (unknown.isNotEmpty()) return Parsed.Error("unknown settings: ${unknown.sorted()}")
            var textScale: Double? = null
            params["textScale"]?.let { raw ->
                val v = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
                if (v == null || !v.isFinite()) return Parsed.Error("textScale must be a number")
                if (v < MapLibreStyle.MIN_TEXT_SCALE || v > MapLibreStyle.MAX_TEXT_SCALE) {
                    return Parsed.Error("textScale must be between ${MapLibreStyle.MIN_TEXT_SCALE} and ${MapLibreStyle.MAX_TEXT_SCALE} (got $v)")
                }
                textScale = v
            }
            var units: ContourUnits? = null
            val unitsGiven = params.containsKey("units")
            if (unitsGiven) {
                units = when (params["units"]?.toString()?.trim()?.lowercase()) {
                    "ft", "feet", "imperial" -> ContourUnits.FEET
                    "m", "metres", "meters", "metric" -> ContourUnits.METERS
                    "unset", "", null -> null
                    else -> return Parsed.Error("units must be ft, m or unset (got ${params["units"]})")
                }
            }
            var warmup: Boolean? = null
            params["warmup"]?.let { raw ->
                warmup = when (raw.toString().trim().lowercase()) {
                    "1", "true", "on", "yes" -> true
                    "0", "false", "off", "no" -> false
                    else -> return Parsed.Error("warmup must be 1 or 0 (got $raw)")
                }
            }
            return Parsed.Ok(textScale, unitsGiven, units, warmup)
        }
    }
}
