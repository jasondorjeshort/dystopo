package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup
import io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
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
 *   T47 map options ([MapOptions.KEYS]): `hillshade=off|single|multi`, `hillshadeIntensity=0..1`, `slope=off|avalanche|steepness` (T104; `on` is avalanche),
 *   `contourInterval=auto|<one of contourIntervalChoices, in the units>`, `contourLabels=sparse|normal|dense`,
 *   `trails`, `water`, `landcover`, `pois`, `boundaries` and `night` `=on|off`. A contour interval change
 *   reopens the tile service (contour tiles are cached per interval); every other option rebuilds the style
 *   (hillshade and slope switch to generated `/shade` tiles, cached per option set).
 *   T80 `directSessionMb` (1..100000, default 300: what the phone may download from the public sources per session
 *   when no server is set) and `directOnMobileData` (1/0, default 1 since T83: no Wi-Fi gates); also in Settings › Sources.
 *   `onlineCacheMb` and `contourCacheMb` (T41) cap the online tile cache and the contour cache (all packs),
 *   16 to 8192 MB; lowering one evicts the least recently used tiles at once.
 * - `POST /settings/ui?open=1|0`: opens or closes the settings sheet ([SettingsSheet]); opening keeps the page shown.
 * - T103 `GET /settings/layout`: the sheet's groups in list order ([io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup]:
 *   `id`, `title`, `summary`), each with its rows in page order (`id`, `label` as drawn, `drawnBy`: the composable
 *   that draws it, `since`: the task that added it); `serverGroup` (the group with the server field, which the
 *   first-run card's Settings link opens), and what is shown now: `open`, `group` (null: the list), `twoPane` (null
 *   until the sheet was drawn once).
 * - T103 `POST /settings/open?group=data|map|display|navigation|sync|about|list`: opens the sheet on that group's page
 *   (`list` or no `group`: the list of groups), for tours and screenshots; an unknown group is a 400 naming the ids.
 */
class SettingsRoutes(private val app: DystopoApp) {
    /** The response for `/settings`, or null for any other path. */
    fun handle(r: Request): Response? {
        if (r.path == "/settings/ui" && r.method == "POST") {
            // Opens (open=1, default) or closes (open=0) the settings sheet, for screenshots.
            app.map.onMain { SettingsUi.visible = r.query["open"] != "0" }
            return Response.json(mapOf("open" to (r.query["open"] != "0")))
        }
        if (r.path == "/settings/layout" && r.method == "GET") return Response.json(layout())
        if (r.path == "/settings/open" && r.method == "POST") {
            val id = r.query["group"]?.trim().orEmpty()
            val group = if (id.isEmpty() || id == "list") null else SettingsGroup.byId(id)
                ?: return Response.json(mapOf("error" to "group must be one of ${SettingsGroup.entries.map { it.id } + "list"} (got $id)"), 400)
            app.map.onMain { SettingsUi.open(group) }
            return Response.json(SettingsLayout.json(open = true, group = group, twoPane = SettingsUi.twoPane) + mapOf("rows" to group?.let { g -> SettingsLayout.rows(g).map { it.label } }))
        }
        if (r.path != "/settings") return null
        return when (r.method) {
            "GET" -> Response.json(AppSettings.json() + io.github.jasondorjeshort.dystopo.app.online.DirectPrefs.json(app)) // T80: directSessionMb, directOnMobileData
            "POST" -> post(r)
            else -> Response.json(mapOf("error" to "GET or POST"), 405)
        }
    }

    private fun post(r: Request): Response {
        val params = HashMap<String, Any?>(r.query)
        val body = r.bodyText.trim()
        if (body.startsWith("{")) params.putAll(MiniJson.parseObject(body))
        val parsed = when (val p = parse(params, AppSettings.units)) {
            is Parsed.Error -> return Response.json(mapOf("error" to p.message), 400)
            is Parsed.Ok -> p
        }
        params["server"]?.let { OnlineRoutes.setServer(app, it.toString())?.let { e -> return Response.json(mapOf("error" to e), 400) } } // T38
        params["decoder"]?.let { TileService.setDecoder(app, it.toString())?.let { e -> return Response.json(mapOf("error" to e), 400) } } // T42
        // T80: the phone's own source reading (no server): per-session download limit and mobile data.
        val directMb = params["directSessionMb"]?.let { it.toString().toDoubleOrNull()?.toLong() ?: return Response.json(mapOf("error" to "directSessionMb must be a number of MB (got $it)"), 400) }
        val directMobile = params["directOnMobileData"]?.let { raw -> when (raw.toString()) { "1", "true", "on" -> true; "0", "false", "off" -> false; else -> return Response.json(mapOf("error" to "directOnMobileData must be 1 or 0 (got $raw)"), 400) } }
        io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).setDirect(directMb, directMobile)?.let { return Response.json(mapOf("error" to it), 400) }
        params["tripCorridorM"]?.let { io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.setWidth(it)?.let { e -> return Response.json(mapOf("error" to e), 400) } } // T130
        apply(parsed)
        return Response.json(AppSettings.json() + io.github.jasondorjeshort.dystopo.app.online.DirectPrefs.json(app))
    }

    /** Sets, persists and applies validated settings (also used by the settings sheet; call off the main thread). */
    fun apply(parsed: Parsed.Ok) {
        val styleBefore = AppSettings.textScale
        val unitsBefore = AppSettings.units
        val warmupBefore = AppSettings.warmup
        val mapBefore = AppSettings.mapOptions
        parsed.textScale?.let { AppSettings.setTextScale(it) }
        if (parsed.unitsGiven) AppSettings.setUnits(parsed.units)
        parsed.warmup?.let { AppSettings.setWarmup(it) }
        parsed.onlineCacheMb?.let { AppSettings.setOnlineCacheMb(it); io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).cache.resize(it * ContourCache.MB) } // T41
        parsed.contourCacheMb?.let { AppSettings.setContourCacheMb(it); ContourCache.resize(it) }
        parsed.pinnedCacheMb?.let { AppSettings.setPinnedCacheMb(it) } // T91: checked when an area downloads
        parsed.routeSnapM?.let { AppSettings.setRouteSnapM(it) } // T57: the next plan uses it
        parsed.debugFixedPort?.let { AppSettings.setDebugFixedPort(it) } // T57: from the next launch
        if (parsed.navStaleS != null || parsed.navStartM != null || parsed.navSnoozeMin != null) {
            AppSettings.setNav(parsed.navStaleS, parsed.navStartM, parsed.navSnoozeMin) // T91: from the next navigation start
        }
        // T47: map options; a fixed interval the new units do not offer goes back to auto.
        var map = if (parsed.map.isEmpty()) mapBefore else MapOptions.parse(parsed.map, mapBefore, AppSettings.units)
        map.contourInterval?.let { if (it !in MapOptions.intervals(AppSettings.units)) map = map.copy(contourInterval = null) }
        if (map != mapBefore) AppSettings.setMapOptions(map)
        io.github.jasondorjeshort.dystopo.app.sync.SettingsSync.publish(app, parsed) // T51: user choices into the synced log
        if (AppSettings.units != unitsBefore || AppSettings.warmup != warmupBefore || map.contourInterval != mapBefore.contourInterval) {
            app.reload() // new tile service: contour units, warm-up; reloads the style too
        } else if (AppSettings.textScale != styleBefore || map != mapBefore) {
            val style = app.tiles.style()
            app.map.onMain { app.map.loadStyle(style) }
        }
    }

    sealed class Parsed {
        class Ok(
            val textScale: Double?,
            val unitsGiven: Boolean,
            val units: ContourUnits?,
            val warmup: Boolean?,
            /** T47: validated map option parameters ([MapOptions.KEYS]), applied over the current options. */
            val map: Map<String, Any?> = emptyMap(),
            val onlineCacheMb: Long? = null, val contourCacheMb: Long? = null, // T41
            val routeSnapM: Int? = null, val debugFixedPort: Boolean? = null, // T57
            val navStaleS: Int? = null, val navStartM: Int? = null, val navSnoozeMin: Int? = null, // T91
            val pinnedCacheMb: Long? = null,
        ) : Parsed()
        class Error(val message: String) : Parsed()
    }

    companion object {
        /** T103: `GET /settings/layout` ([SettingsLayout.json] with what the sheet shows now). */
        fun layout(): Map<String, Any?> = SettingsLayout.json(SettingsUi.visible, SettingsUi.group, SettingsUi.twoPane, AppSettings.cacheBudgetMb)

        /** T41: cache caps in MB, within [AppSettings.CACHE_MB_RANGE] (T115: `pinnedCacheMb` within [AppSettings.PINNED_CACHE_MB_RANGE]; declared before [KEYS], which uses it). */
        val CACHE_KEYS = setOf("onlineCacheMb", "contourCacheMb", "pinnedCacheMb") // T91: pinnedCacheMb

        /** `server` (T38) is validated and applied by [OnlineRoutes.setServer]. */
        val KEYS = setOf("textScale", "units", "warmup", "server", "decoder", "routeSnapM", "debugFixedPort", "directSessionMb", "directOnMobileData", "navStaleS", "navStartM", "navSnoozeMin", "tripCorridorM") + MapOptions.KEYS + CACHE_KEYS // T80: direct* // decoder: T42, TileService.setDecoder; T57: routeSnapM, debugFixedPort

        private fun cacheMb(params: Map<String, Any?>, key: String): Pair<Long?, String?> {
            val raw = params[key] ?: return null to null
            val v = (raw as? Number)?.toLong() ?: raw.toString().trim().toLongOrNull() ?: return null to "$key must be a whole number of MB"
            val r = if (key == "pinnedCacheMb") AppSettings.PINNED_CACHE_MB_RANGE else AppSettings.CACHE_MB_RANGE // T115: pinned up to 1 TB
            if (v !in r) return null to "$key must be between ${r.first} and ${r.last} MB (got $v)"
            return v to null
        }

        /**
         * Validates a settings request without applying it (pure, unit-tested). A contour interval is checked
         * against the units the request sets, else [currentUnits].
         */
        fun parse(params: Map<String, Any?>, currentUnits: ContourUnits = ContourUnits.FEET): Parsed {
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
            params["warmup"]?.let { raw -> warmup = flag(raw) ?: return Parsed.Error("warmup must be 1 or 0 (got $raw)") }
            var debugFixedPort: Boolean? = null
            params["debugFixedPort"]?.let { raw -> debugFixedPort = flag(raw) ?: return Parsed.Error("debugFixedPort must be 1 or 0 (got $raw)") }
            var routeSnapM: Int? = null
            params["routeSnapM"]?.let { raw ->
                val v = (raw as? Number)?.toDouble() ?: raw.toString().trim().toDoubleOrNull()
                val r = AppSettings.ROUTE_SNAP_RANGE
                if (v == null || !v.isFinite() || v < r.first || v > r.last) return Parsed.Error("routeSnapM must be between ${r.first} and ${r.last} m (got $raw)")
                routeSnapM = Math.round(v).toInt()
            }
            // T91: the navigation thresholds, whole numbers within their ranges.
            fun intIn(key: String, r: IntRange, unit: String): Pair<Int?, String?> {
                val raw = params[key] ?: return null to null
                val v = (raw as? Number)?.toDouble() ?: raw.toString().trim().toDoubleOrNull()
                if (v == null || !v.isFinite() || v < r.first || v > r.last) return null to "$key must be between ${r.first} and ${r.last} $unit (got $raw)"
                return Math.round(v).toInt() to null
            }
            val (navStaleS, e1) = intIn("navStaleS", AppSettings.NAV_STALE_RANGE, "s")
            e1?.let { return Parsed.Error(it) }
            val (navStartM, e2) = intIn("navStartM", AppSettings.NAV_START_RANGE, "m")
            e2?.let { return Parsed.Error(it) }
            val (navSnoozeMin, e3) = intIn("navSnoozeMin", AppSettings.NAV_SNOOZE_RANGE, "min")
            e3?.let { return Parsed.Error(it) }
            val map = params.filterKeys { it in MapOptions.KEYS }
            if (map.isNotEmpty()) {
                try {
                    MapOptions.parse(map, MapOptions(), units ?: currentUnits)
                } catch (e: IllegalArgumentException) {
                    return Parsed.Error(e.message ?: "bad map option")
                }
            }
            val (online, onlineError) = cacheMb(params, "onlineCacheMb")
            onlineError?.let { return Parsed.Error(it) }
            val (contour, contourError) = cacheMb(params, "contourCacheMb")
            contourError?.let { return Parsed.Error(it) }
            val (pinned, pinnedError) = cacheMb(params, "pinnedCacheMb")
            pinnedError?.let { return Parsed.Error(it) }
            return Parsed.Ok(textScale, unitsGiven, units, warmup, map, online, contour, routeSnapM, debugFixedPort, navStaleS, navStartM, navSnoozeMin, pinned)
        }

        private fun flag(raw: Any): Boolean? = when (raw.toString().trim().lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> null
        }
    }
}
