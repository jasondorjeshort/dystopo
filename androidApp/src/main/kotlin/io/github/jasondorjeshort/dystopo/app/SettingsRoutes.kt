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
 *   `trails`, `water`, `landcover`, `pois`, `boundaries`, `drainage` (T174, off by default) and `night` `=on|off`. A contour interval change
 *   reopens the tile service (contour tiles are cached per interval); every other option rebuilds the style
 *   (hillshade and slope switch to generated `/shade` tiles, cached per option set).
 *   T80 `directSessionMb` (1..100000, default 300: what the phone may download from the public sources per session
 *   when no server is set) and `directOnMobileData` (1/0, default 1 since T83: no Wi-Fi gates); also in Settings › Sources.
 *   `onlineCacheMb` and `contourCacheMb` (T41) cap the online tile cache and the contour cache (all packs),
 *   16 to 8192 MB; lowering one evicts the least recently used tiles at once.
 *   T126 `navSpeech` (1/0, default 0): spoken turn directions while navigating ([io.github.jasondorjeshort.dystopo.app.nav.NavSpeech]),
 *   synced; also Settings › Navigation and tracking and `POST /nav/speech?enabled=`.
 *   T130 `tripCorridorM` (0 off, or 100 to 10000 m; default 1000): the corridor every trip keeps offline; T136: synced
 *   (`app.tripCorridorM`, the metres as a whole number). Raising `pinnedCacheMb` (T136) tries a refused trip corridor again.
 *   T164 `paceStandard` (1/0, default 0: "Use the standard estimate"), `paceFit` (the fitted route times as their synced
 *   value, `1;hike:<peak m/s>,<width>,<peak grade>,<tracks>,<metres>,<spread>;...`, "" for none; set by the phone's refit,
 *   [io.github.jasondorjeshort.dystopo.app.routing.PaceSettings]) and `paceSince` (the reset: only tracks started at or after
 *   this epoch ms count, 0 all), all three synced (`app.paceStandard` `on`/`off`, `app.paceFit`, `app.paceSince`); also
 *   Settings › Navigation and tracking › Route times and `GET /pace`.
 *   T163 `turnDeadline` (`sunset`, the default; `civil`; or a typed local time `HH:MM`; there is no off) and `turnMarginMin`
 *   (0 to 240, default 30): the turn-around clock's deadline and margin, synced (`app.turnDeadline`, `app.turnMarginMin`);
 *   also Settings › Navigation and tracking. A running navigation reads them at its next reading.
 *   T172, recording per activity (Settings › Navigation and tracking): `trackActivity=foot|bike|road|rail|boat|air`, the
 *   activity the picker marks and Record starts with (synced, `app.trackActivity`); `trackProfile.<activity>`, that
 *   activity's four choices as one value (`acc=50;autopause=0;net=1;interval=1000`, `acc=none` for no bound; synced as
 *   `app.trackProfile.<activity>`, [io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles]); and the rows' own
 *   keys, which edit the remembered activity's profile (the one `trackActivity` sets in the same request, else the
 *   remembered one) and are synced through its `trackProfile.<activity>`: `trackAccuracyM=off|1..10000`,
 *   `trackAutoPause=1|0`, `trackNetworkFallback=1|0`, `trackIntervalS=1..3600`. A recording of that activity takes the
 *   change from its next fix. `GET /settings` reports `trackActivity`, `trackProfile` (the remembered activity's choices)
 *   and `trackProfiles` (every activity's, with the `stored` value or null for the defaults).
 *   T161, the live readout (Settings › Navigation and tracking, `readout/ReadoutSettings`): `readout.<activity>.<entry>`
 *   (entry `speed`, `climb`, `course` or `elevation`), that activity's options for the entry as one value
 *   (`shown=1;pace=0;damping=low|medium|high;pm=0;knots=0`, keys left out keep their current value; synced as
 *   `app.readout.<activity>.<entry>`); the rows' own keys `readout.<entry>`, the same form, which edit the remembered
 *   activity's options (`trackActivity`'s, also when set in the same request) and sync through its
 *   `readout.<activity>.<entry>`; `readoutBarometer=1|0` (default on, synced `app.readoutBarometer`). Pace and knots
 *   are speed's only, knots honoured in boat and air only. `GET /settings` reports `readoutBarometer`, `readout` (the
 *   remembered activity's options per entry) and `readoutStored`. The readout takes a change at its next refresh.
 *   T176, the position button (Settings › Map, `controls/RecenterButton`): `mapControls=1|2|3` (default 1: one button;
 *   2 adds the orientation button, 3 the lock), `recenterZoom` (10 to 18, default 14: the zoom a tap brings the map to;
 *   a tap never zooms out) and `orientationLock=1|0` ("Keep north up", default off: no gesture turns the map; the
 *   orientation button and the navigation card still change the mode), all three synced (`app.mapControls`,
 *   `app.recenterZoom`, `app.orientationLock` `on`/`off`). The button and the lock take a change at once.
 *   T177, the readout bar (Settings › Navigation and tracking): `readoutBar=1|0` (default on: the bar beside the scale
 *   bar; off draws the scale bar alone) and `climbWindow=30|60|300|600` (seconds, also `30s`, `1min`, `5min`, `10min`;
 *   default 60: the climb rate's averaging window on the ground), synced as `app.readoutBar` `on`/`off` and
 *   `app.climbWindow` in seconds. `GET /settings` reports `readoutBar` and `climbWindow`.
 *   T178, the pressure-fall warning (Settings › Navigation and tracking): `pressureFallWarning=off|more|standard|fewer`
 *   (also `0`, `3`, `4`, `6`, the fall in hPa over 3 hours, sea-level equivalent; default `standard`, 4), synced as
 *   `app.pressureFallWarning`. `GET /settings` reports it; `GET /readout` › `trend` shows its effect.
 *   T187, water along the route (Settings › Navigation and tracking, `routing/RouteWater`): `waterAlongRoute=1|0`
 *   (default on: the route card's and the trip card's water line and list, the markers, the navigation card's next water)
 *   and `waterSpoken=1|0` (default off: navigating, the last water before a stretch of more than 5 km without any is
 *   said once), synced as `app.waterAlongRoute` and `app.waterSpoken` `on`/`off`. The markers and cards follow at once.
 *   T182, the viewshed's range (Settings › Map, `viewshed/ViewshedSession`): `viewRange=10|30|100` (km, default 30: how far
 *   "What can I see from here" looks), synced (`app.viewRange`); the next viewshed takes it.
 *   T184, the route card's time (Settings › Navigation and tracking, `routing/PaceSettings`): `timeEstimateForm=range|one`
 *   (default `range`: "about 4:30" with "4:10 to 4:50" from the recorded hikes under it; `one`: the single number),
 *   synced as `app.timeEstimateForm`. The card follows at once.
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
        parsed.pinnedCacheMb?.let { mb -> // T91: checked when an area downloads
            val raised = mb > AppSettings.pinnedCacheMb
            AppSettings.setPinnedCacheMb(mb)
            if (raised) io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.request("budget $mb MB") // T136: a corridor refused at the old budget is tried again
        }
        parsed.tripCorridorM?.let { io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.applyWidth(it) } // T130; T136: through the parser, so it syncs
        parsed.routeSnapM?.let { AppSettings.setRouteSnapM(it) } // T57: the next plan uses it
        parsed.debugFixedPort?.let { AppSettings.setDebugFixedPort(it) } // T57: from the next launch
        if (parsed.navStaleS != null || parsed.navStartM != null || parsed.navSnoozeMin != null) {
            AppSettings.setNav(parsed.navStaleS, parsed.navStartM, parsed.navSnoozeMin) // T91: from the next navigation start
        }
        parsed.navSpeech?.let { on -> // T126: from the next direction; off stops a line being said
            AppSettings.setNavSpeech(on)
            if (!on) io.github.jasondorjeshort.dystopo.app.nav.NavSpeech.silence()
        }
        // T164: the route-time fit, its switch and its reset; the router reads the result from the next plan.
        if (parsed.paceStandard != null || parsed.paceFit != null || parsed.paceSince != null) {
            AppSettings.setPace(parsed.paceStandard, parsed.paceFit, parsed.paceSince)
            io.github.jasondorjeshort.dystopo.app.routing.PaceSettings.changed()
        }
        // T163: the turn-around clock reads them at its next reading (a running navigation included)
        if (parsed.turnDeadline != null || parsed.turnMarginMin != null) AppSettings.setTurn(parsed.turnDeadline, parsed.turnMarginMin)
        // T172: the remembered activity first, then whole values (a sync), then a row's edit of the remembered one's profile;
        // a recording of a changed activity takes its profile from the next fix.
        parsed.trackActivity?.let { AppSettings.setTrackActivity(it) }
        val profilesChanged = LinkedHashSet<io.github.jasondorjeshort.dystopo.model.Activity>()
        for ((a, v) in parsed.trackProfiles) if (AppSettings.trackProfiles[a] != v) { AppSettings.setTrackProfile(a, v); profilesChanged += a }
        parsed.trackEdit?.let { e ->
            val a = AppSettings.trackActivity
            val v = io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles.encode(e.applyTo(AppSettings.trackProfile(a)))
            if (AppSettings.trackProfiles[a] != v) { AppSettings.setTrackProfile(a, v); profilesChanged += a }
        }
        if (profilesChanged.isNotEmpty()) {
            val t = io.github.jasondorjeshort.dystopo.app.tracking.Tracking.of(app)
            profilesChanged.forEach { t.applyProfile(it) }
        }
        // T161: whole readout values (a sync), then the rows' edits of the remembered activity's entries (after T172 set
        // the activity); the readout takes them at its next refresh.
        parsed.readoutBarometer?.let { AppSettings.setReadoutBarometer(it) }
        for ((k, v) in parsed.readoutValues) if (AppSettings.readoutEntries[k] != v) AppSettings.setReadoutEntry(k, v)
        for ((e, raw) in parsed.readoutEdits) {
            val rs = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings
            val a = AppSettings.trackActivity
            val v = rs.merge(e, raw, AppSettings.readoutOptions(a)[e])?.encode() ?: continue
            if (AppSettings.readoutEntries[rs.key(a, e)] != v) AppSettings.setReadoutEntry(rs.key(a, e), v)
        }
        if (parsed.readoutBarometer != null || parsed.readoutValues.isNotEmpty() || parsed.readoutEdits.isNotEmpty()) io.github.jasondorjeshort.dystopo.app.readout.LiveReadout.refresh()
        // T176: the position button redraws from the values; the lock turns the rotate gesture off or on at once.
        if (parsed.mapControls != null || parsed.recenterZoom != null || parsed.orientationLock != null) {
            AppSettings.setMapControls(parsed.mapControls, parsed.recenterZoom, parsed.orientationLock)
            io.github.jasondorjeshort.dystopo.app.controls.MapOrientation.onSettings()
        }
        // T177: the bar switch (the scale bar draws or leaves out the bar within half a second) and the climb window.
        if (parsed.readoutBar != null || parsed.climbWindow != null) {
            AppSettings.setReadoutBar(parsed.readoutBar, parsed.climbWindow)
            io.github.jasondorjeshort.dystopo.app.readout.LiveReadout.refresh()
        }
        // T178: the pressure-fall warning (the trend is evaluated again at once); the barometer switch also starts or stops its sampling.
        parsed.pressureFallWarning?.let { AppSettings.setPressureFallWarning(it) }
        if (parsed.pressureFallWarning != null || parsed.readoutBarometer != null) io.github.jasondorjeshort.dystopo.app.readout.PressureWatch.onSettings()
        // T187: water along the route; the route and trip markers redraw (or go) at once.
        if (parsed.waterAlongRoute != null || parsed.waterSpoken != null) {
            AppSettings.setWater(parsed.waterAlongRoute, parsed.waterSpoken)
            io.github.jasondorjeshort.dystopo.app.routing.RouteWater.onSettings()
        }
        parsed.viewRange?.let { AppSettings.setViewRange(it) } // T182: the next viewshed reads it
        parsed.timeEstimateForm?.let { AppSettings.setTimeEstimateForm(it) } // T184: the card reads it on its next frame
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
            val navSpeech: Boolean? = null, // T126
            val tripCorridorM: Int? = null, // T130, T136
            // T164: "Use the standard estimate", the fit's synced value ("" none), the reset's start (epoch ms).
            val paceStandard: Boolean? = null, val paceFit: String? = null, val paceSince: Long? = null,
            val turnDeadline: String? = null, val turnMarginMin: Int? = null, // T163
            // T172: the remembered activity, whole profile values per activity (`trackProfile.<a>`, also what a sync brings),
            // and a row's edit of the remembered activity's profile.
            val trackActivity: io.github.jasondorjeshort.dystopo.model.Activity? = null,
            val trackProfiles: Map<io.github.jasondorjeshort.dystopo.model.Activity, String> = emptyMap(),
            val trackEdit: io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles.Edit? = null,
            // T161: the readout's barometer switch, whole values per activity and entry (also what a sync brings), and
            // the rows' edits of the remembered activity's entries (checked here, merged over its options in apply).
            val readoutBarometer: Boolean? = null,
            val readoutValues: Map<String, String> = emptyMap(),
            val readoutEdits: Map<io.github.jasondorjeshort.dystopo.readout.ReadoutEntry, String> = emptyMap(),
            // T176: the position button's layout, its close zoom and "Keep north up".
            val mapControls: Int? = null, val recenterZoom: Int? = null, val orientationLock: Boolean? = null,
            // T177: the readout bar's switch and the climb window (seconds).
            val readoutBar: Boolean? = null, val climbWindow: Int? = null,
            // T178: the pressure-fall warning.
            val pressureFallWarning: io.github.jasondorjeshort.dystopo.readout.FallWarning? = null,
            // T187: water along the route and its spoken line.
            val waterAlongRoute: Boolean? = null, val waterSpoken: Boolean? = null,
            // T182: the viewshed's range, km.
            val viewRange: Int? = null,
            // T184: the route card's time as a range or one number.
            val timeEstimateForm: String? = null,
        ) : Parsed()
        class Error(val message: String) : Parsed()
    }

    companion object {
        /** T103: `GET /settings/layout` ([SettingsLayout.json] with what the sheet shows now). */
        fun layout(): Map<String, Any?> = SettingsLayout.json(SettingsUi.visible, SettingsUi.group, SettingsUi.twoPane, AppSettings.cacheBudgetMb)

        /** T41: cache caps in MB, within [AppSettings.CACHE_MB_RANGE] (T115: `pinnedCacheMb` within [AppSettings.PINNED_CACHE_MB_RANGE]; declared before [KEYS], which uses it). */
        val CACHE_KEYS = setOf("onlineCacheMb", "contourCacheMb", "pinnedCacheMb") // T91: pinnedCacheMb

        /** `server` (T38) is validated and applied by [OnlineRoutes.setServer]. */
        val KEYS = setOf("textScale", "units", "warmup", "server", "decoder", "routeSnapM", "debugFixedPort", "directSessionMb", "directOnMobileData", "navStaleS", "navStartM", "navSnoozeMin", "tripCorridorM", "navSpeech") + MapOptions.KEYS + CACHE_KEYS + // T80: direct* // decoder: T42, TileService.setDecoder; T57: routeSnapM, debugFixedPort
            setOf("paceStandard", "paceFit", "paceSince") + // T164
            setOf("turnDeadline", "turnMarginMin") + // T163
            // T172
            setOf("trackActivity", "trackAccuracyM", "trackAutoPause", "trackNetworkFallback", "trackIntervalS") + io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles.KEYS +
            // T161
            setOf(io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAROMETER) + io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.KEYS +
            io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.EDIT_KEYS +
            setOf("mapControls", "recenterZoom", "orientationLock") + // T176
            setOf(io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAR, io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.CLIMB_WINDOW) + // T177
            setOf(AppSettings.KEY_PRESSURE_FALL_WARNING) + // T178
            setOf("waterAlongRoute", "waterSpoken") + // T187
            setOf("viewRange") + // T182
            setOf(AppSettings.KEY_TIME_ESTIMATE_FORM) // T184

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
            var navSpeech: Boolean? = null // T126
            params["navSpeech"]?.let { raw -> navSpeech = flag(raw) ?: return Parsed.Error("navSpeech must be 1 or 0 (got $raw)") }
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
            var tripCorridorM: Int? = null // T130; T136: validated here so a synced value goes through the same check
            params["tripCorridorM"]?.let { raw ->
                tripCorridorM = io.github.jasondorjeshort.dystopo.app.areas.parseCorridorWidth(raw)
                    ?: return Parsed.Error(io.github.jasondorjeshort.dystopo.app.areas.corridorWidthError(raw))
            }
            // T164: the switch is a flag; the fit a value this build reads (PaceFits.decode); the reset an instant, epoch ms.
            var paceStandard: Boolean? = null
            params["paceStandard"]?.let { raw -> paceStandard = flag(raw) ?: return Parsed.Error("paceStandard must be 1 or 0 (got $raw)") }
            var paceFit: String? = null
            params["paceFit"]?.let { raw ->
                val v = raw.toString().trim()
                if (io.github.jasondorjeshort.dystopo.routing.PaceFits.decode(v) == null) return Parsed.Error("paceFit is not a fit this build reads (got $v)")
                paceFit = v
            }
            var paceSince: Long? = null
            params["paceSince"]?.let { raw ->
                val v = (raw as? Number)?.toLong() ?: raw.toString().trim().toLongOrNull()
                if (v == null || v < 0) return Parsed.Error("paceSince must be a time in epoch milliseconds, 0 for every track (got $raw)")
                paceSince = v
            }
            // T163: the turn-around clock's deadline (sunset, civil or a typed HH:MM; no off) and margin (whole minutes).
            var turnDeadline: String? = null
            params["turnDeadline"]?.let { raw ->
                turnDeadline = io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.parse(raw.toString())?.value
                    ?: return Parsed.Error("turnDeadline must be sunset, civil or a time HH:MM (got $raw)")
            }
            val (turnMarginMin, e4) = intIn("turnMarginMin", io.github.jasondorjeshort.dystopo.nav.TurnAround.MARGIN_RANGE, "min")
            e4?.let { return Parsed.Error(it) }
            // T172: the recording activity and the profiles per activity.
            val profiles = io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles
            var trackActivity: io.github.jasondorjeshort.dystopo.model.Activity? = null
            params["trackActivity"]?.let { raw ->
                trackActivity = io.github.jasondorjeshort.dystopo.model.Activity.of(raw.toString().trim().lowercase())
                    ?: return Parsed.Error("trackActivity must be one of ${io.github.jasondorjeshort.dystopo.model.Activity.entries.map { it.code }} (got $raw)")
            }
            val trackProfiles = LinkedHashMap<io.github.jasondorjeshort.dystopo.model.Activity, String>()
            for (k in params.keys.filter { it in profiles.KEYS }) {
                val a = profiles.activityOfKey(k)!!
                trackProfiles[a] = profiles.parseValue(a, params[k].toString())
                    ?: return Parsed.Error("$k must be acc=off|none or metres;autopause=0|1;net=0|1;interval=<ms> (got ${params[k]})")
            }
            val trackEdit = try {
                profiles.parseEdit(params)
            } catch (e: IllegalArgumentException) {
                return Parsed.Error(e.message ?: "bad recording setting")
            }
            // T161: the readout's options and barometer switch.
            val rs = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings
            val readoutBarometer = params[rs.BAROMETER]?.let { flag(it) ?: return Parsed.Error("${rs.BAROMETER} must be 1 or 0 (got $it)") }
            val readoutValues = LinkedHashMap<String, String>()
            val readoutEdits = LinkedHashMap<io.github.jasondorjeshort.dystopo.readout.ReadoutEntry, String>()
            val valueForm = "shown=0|1;pace=0|1;damping=low|medium|high;pm=0|1;knots=0|1 (any of them)"
            for (k in params.keys.sorted()) {
                val raw = params[k].toString()
                rs.ofKey(k)?.let { (a, e) -> readoutValues[k] = rs.parseValue(a, e, raw) ?: return Parsed.Error("$k must be $valueForm (got $raw)") }
                rs.entryOfEditKey(k)?.let { e ->
                    rs.merge(e, raw, io.github.jasondorjeshort.dystopo.readout.ReadoutOptions.defaults(io.github.jasondorjeshort.dystopo.model.Activity.FOOT)[e])
                        ?: return Parsed.Error("$k must be $valueForm (got $raw)")
                    readoutEdits[e] = raw
                }
            }
            // T176: the position button's layout (1, 2 or 3), its close zoom (whole levels) and the lock.
            val (mapControls, e5) = intIn("mapControls", io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.LAYOUTS, "buttons")
            e5?.let { return Parsed.Error(it) }
            val (recenterZoom, e6) = intIn("recenterZoom", io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.CLOSE_ZOOM_RANGE, "(zoom level)")
            e6?.let { return Parsed.Error(it) }
            val orientationLock = params["orientationLock"]?.let { flag(it) ?: return Parsed.Error("orientationLock must be 1 or 0 (got $it)") }
            // T177: the readout bar's switch and the climb window.
            val readoutBar = params[rs.BAR]?.let { flag(it) ?: return Parsed.Error("${rs.BAR} must be 1 or 0 (got $it)") }
            val climbWindow = params[rs.CLIMB_WINDOW]?.let {
                rs.parseClimbWindow(it.toString()) ?: return Parsed.Error("${rs.CLIMB_WINDOW} must be 30, 60, 300 or 600 seconds (got $it)")
            }
            // T182: the viewshed's range, one of its three choices in km.
            val ranges = io.github.jasondorjeshort.dystopo.dem.Viewshed.RANGES_KM
            val viewRange = params["viewRange"]?.let { raw ->
                ((raw as? Number)?.toDouble() ?: raw.toString().trim().removeSuffix("km").trim().toDoubleOrNull())?.takeIf { it.isFinite() && it.toInt().toDouble() == it && it.toInt() in ranges }?.toInt()
                    ?: return Parsed.Error("viewRange must be one of ${ranges.joinToString(", ")} km (got $raw)")
            }
            // T178: the pressure-fall warning.
            val pressureFallWarning = params[AppSettings.KEY_PRESSURE_FALL_WARNING]?.let {
                io.github.jasondorjeshort.dystopo.readout.FallWarning.parse(it.toString())
                    ?: return Parsed.Error("${AppSettings.KEY_PRESSURE_FALL_WARNING} must be off, more, standard or fewer (or 0, 3, 4, 6) (got $it)")
            }
            // T187: water along the route and its spoken line.
            val waterAlongRoute = params["waterAlongRoute"]?.let { flag(it) ?: return Parsed.Error("waterAlongRoute must be 1 or 0 (got $it)") }
            val waterSpoken = params["waterSpoken"]?.let { flag(it) ?: return Parsed.Error("waterSpoken must be 1 or 0 (got $it)") }
            // T184: the route card's time.
            val timeEstimateForm = params[AppSettings.KEY_TIME_ESTIMATE_FORM]?.let { v ->
                v.toString().trim().lowercase().takeIf { it in AppSettings.TIME_FORMS }
                    ?: return Parsed.Error("${AppSettings.KEY_TIME_ESTIMATE_FORM} must be range or one (got $v)")
            }
            return Parsed.Ok(textScale, unitsGiven, units, warmup, map, online, contour, routeSnapM, debugFixedPort, navStaleS, navStartM, navSnoozeMin, pinned, navSpeech, tripCorridorM, paceStandard, paceFit, paceSince, turnDeadline = turnDeadline, turnMarginMin = turnMarginMin,
                trackActivity = trackActivity, trackProfiles = trackProfiles, trackEdit = trackEdit,
                readoutBarometer = readoutBarometer, readoutValues = readoutValues, readoutEdits = readoutEdits,
                mapControls = mapControls, recenterZoom = recenterZoom, orientationLock = orientationLock,
                readoutBar = readoutBar, climbWindow = climbWindow,
                pressureFallWarning = pressureFallWarning,
                waterAlongRoute = waterAlongRoute, waterSpoken = waterSpoken,
                viewRange = viewRange,
                timeEstimateForm = timeEstimateForm)
        }

        private fun flag(raw: Any): Boolean? = when (raw.toString().trim().lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> null
        }
    }
}
