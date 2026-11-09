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
 *   T163 `turnDeadline` (`sunset`, the default; `civil`; T193 `terrain`, the sun behind the ridge at the route's end when
 *   earlier, which builds before T193 skip; or a typed local time `HH:MM`; there is no off) and `turnMarginMin`
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
 *   T194, the terrain shade line (Settings › Navigation and tracking, `nav/NavShade`): `shadeSpoken=off|hot|always` (also
 *   `On hot days`; default `hot`: navigating, the line before a sunny stretch of more than 20 minutes after terrain shade
 *   is said only when the air at the walker's height is 25 C or more, the weather station's temperature lowered at 6.5 C
 *   a km; `always` says it whatever the temperature), synced as `app.shadeSpoken`. The card's line shows in every case.
 *   T203, the terrain estimate (Settings › Navigation and tracking, `tracking/TerrainSource`): `estimatePosition=1|0`
 *   (default on: "Estimate position when GPS is poor"), synced as `app.estimatePosition` `on`/`off`; the source starts or
 *   stops watching at once. `GET /settings` reports it; `GET /location/terrain` shows its effect.
 *   T213, where the user's tracks leave the mapped trails (Settings › Map options, `divergence/DivergenceLayer`):
 *   `showDivergence=1|0` (default on: "Show trail disagreements"), synced as `app.showDivergence` `on`/`off`; the
 *   markers go or come at once, and off stops the run after a recording (`POST /divergence/run` still runs).
 *   T248: the keys with one stored value each are the registry's ([AppSettings.PLAIN], one line per setting: parsed,
 *   refused, stored, reported and synced from that line); the others are [COMPOSITE_KEYS], each with its code here.
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
        // T248: every plain setting the request set, stored at once (the remembered activity before the profile and
        // readout edits below read it); what must act on a change follows, by setting.
        val corridorBefore = AppSettings.tripCorridorM
        for ((s, v) in parsed.plain) parsed.store(s, v)
        fun given(vararg s: Setting<*>) = s.any { it in parsed.plain }
        parsed.pinnedCacheMb?.let { mb -> // T91: checked when an area downloads
            val raised = mb > AppSettings.pinnedCacheMb
            AppSettings.setPinnedCacheMb(mb)
            if (raised) io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.request("budget $mb MB") // T136: a corridor refused at the old budget is tried again
        }
        // T130, T136: a new corridor width is kept for every trip (through the parser, so it syncs)
        parsed[AppSettings.TRIP_CORRIDOR_M]?.let { if (it != corridorBefore) io.github.jasondorjeshort.dystopo.app.areas.TripCorridors.request("width $it") }
        // T126: spoken directions from the next direction; off stops a line being said
        if (parsed[AppSettings.NAV_SPEECH] == false) io.github.jasondorjeshort.dystopo.app.nav.NavSpeech.silence()
        // T164: the route-time fit, its switch and its reset; the router reads the result from the next plan.
        if (given(AppSettings.PACE_STANDARD, AppSettings.PACE_FIT, AppSettings.PACE_SINCE)) io.github.jasondorjeshort.dystopo.app.routing.PaceSettings.changed()
        // T163: the turn-around clock reads the deadline and the margin at its next reading (a running navigation included)
        parsed.turnDeadline?.let { AppSettings.setTurnDeadline(it) }
        // T172: whole values (a sync), then a row's edit of the remembered activity's profile (the activity is set above);
        // a recording of a changed activity takes its profile from the next fix.
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
        for ((k, v) in parsed.readoutValues) if (AppSettings.readoutEntries[k] != v) AppSettings.setReadoutEntry(k, v)
        for ((e, raw) in parsed.readoutEdits) {
            val rs = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings
            val a = AppSettings.trackActivity
            val v = rs.merge(e, raw, AppSettings.readoutOptions(a)[e])?.encode() ?: continue
            if (AppSettings.readoutEntries[rs.key(a, e)] != v) AppSettings.setReadoutEntry(rs.key(a, e), v)
        }
        // T161, T177: the readout takes the barometer switch, its entries, the bar switch and the climb window at its next
        // refresh (the scale bar draws or leaves out the bar within half a second).
        if (given(AppSettings.READOUT_BAROMETER, AppSettings.READOUT_BAR, AppSettings.CLIMB_WINDOW) || parsed.readoutValues.isNotEmpty() || parsed.readoutEdits.isNotEmpty()) {
            io.github.jasondorjeshort.dystopo.app.readout.LiveReadout.refresh()
        }
        // T176: the position button redraws from the values; the lock turns the rotate gesture off or on at once.
        if (given(AppSettings.MAP_CONTROLS, AppSettings.RECENTER_ZOOM, AppSettings.ORIENTATION_LOCK)) io.github.jasondorjeshort.dystopo.app.controls.MapOrientation.onSettings()
        // T178: the pressure-fall warning (the trend is evaluated again at once); the barometer switch also starts or stops its sampling.
        if (given(AppSettings.PRESSURE_FALL_WARNING, AppSettings.READOUT_BAROMETER)) io.github.jasondorjeshort.dystopo.app.readout.PressureWatch.onSettings()
        // T187: water along the route; the route and trip markers redraw (or go) at once.
        if (given(AppSettings.WATER_ALONG_ROUTE, AppSettings.WATER_SPOKEN)) io.github.jasondorjeshort.dystopo.app.routing.RouteWater.onSettings()
        // T184: the route card's time; the card recomposes at once (also after POST /settings, which bumps no revision itself).
        if (given(AppSettings.TIME_ESTIMATE_FORM)) runCatching { app.map.onMain { SettingsUi.revision++ } }
        // T203: the terrain estimate's switch; the source starts or stops watching at once.
        if (given(AppSettings.ESTIMATE_POSITION)) io.github.jasondorjeshort.dystopo.app.tracking.TerrainSource.update()
        // T213: the trail disagreements' layer switch; the markers follow at once.
        if (given(AppSettings.SHOW_DIVERGENCE)) io.github.jasondorjeshort.dystopo.app.divergence.DivergenceLayer.refresh()
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
        /**
         * A validated request. The composites keep a field each; the registry's plain settings ([AppSettings.PLAIN]) are
         * [plain], read with `parsed[AppSettings.X]` and made with [of] (T248).
         */
        class Ok(
            val textScale: Double?,
            val unitsGiven: Boolean,
            val units: ContourUnits?,
            val warmup: Boolean?,
            /** T47: validated map option parameters ([MapOptions.KEYS]), applied over the current options. */
            val map: Map<String, Any?> = emptyMap(),
            val onlineCacheMb: Long? = null, val contourCacheMb: Long? = null, val pinnedCacheMb: Long? = null, // T41, T91
            val turnDeadline: String? = null, // T163: two stored keys (the typed time kept apart)
            // T172: whole profile values per activity (`trackProfile.<a>`, also what a sync brings), and a row's edit of the
            // remembered activity's profile.
            val trackProfiles: Map<io.github.jasondorjeshort.dystopo.model.Activity, String> = emptyMap(),
            val trackEdit: io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles.Edit? = null,
            // T161: whole values per activity and entry (also what a sync brings), and the rows' edits of the remembered
            // activity's entries (checked here, merged over its options in apply).
            val readoutValues: Map<String, String> = emptyMap(),
            val readoutEdits: Map<io.github.jasondorjeshort.dystopo.readout.ReadoutEntry, String> = emptyMap(),
            /** T248: the plain settings the request sets, with their parsed values. */
            val plain: Map<Setting<*>, Any> = emptyMap(),
        ) : Parsed() {
            /** [s]'s value in this request, null when it does not set it. */
            @Suppress("UNCHECKED_CAST")
            operator fun <T : Any> get(s: Setting<T>): T? = plain[s] as T?

            /** This request and [s] set to [v]. */
            fun <T : Any> and(s: Setting<T>, v: T) = Ok(textScale, unitsGiven, units, warmup, map, onlineCacheMb, contourCacheMb, pinnedCacheMb,
                turnDeadline, trackProfiles, trackEdit, readoutValues, readoutEdits, plain + (s to v))

            @Suppress("UNCHECKED_CAST")
            internal fun store(s: Setting<*>, v: Any) = AppSettings.set(s as Setting<Any>, v)

            companion object {
                /** A request that sets [s] to [v] alone (a settings row, a button). */
                fun <T : Any> of(s: Setting<T>, v: T) = Ok(null, false, null, null, plain = mapOf<Setting<*>, Any>(s to v))
            }
        }
        class Error(val message: String) : Parsed()
    }

    companion object {
        /** T103: `GET /settings/layout` ([SettingsLayout.json] with what the sheet shows now). */
        fun layout(): Map<String, Any?> = SettingsLayout.json(SettingsUi.visible, SettingsUi.group, SettingsUi.twoPane, AppSettings.cacheBudgetMb)

        /** T41: cache caps in MB, within [AppSettings.CACHE_MB_RANGE] (T115: `pinnedCacheMb` within [AppSettings.PINNED_CACHE_MB_RANGE]; declared before [KEYS], which uses it). */
        val CACHE_KEYS = setOf("onlineCacheMb", "contourCacheMb", "pinnedCacheMb") // T91: pinnedCacheMb

        /**
         * T248: the keys with their own code here, by key: the text scale, units and warm-up, `server` (T38, validated and
         * applied by [OnlineRoutes.setServer]), `decoder` (T42, [TileService.setDecoder]), the direct-source limits (T80),
         * the map options (T47), the cache caps (T41, T91), the turn-around deadline (T163: two stored keys), the recording
         * profiles and their rows' keys (T172) and the readout's entries and their rows' keys (T161).
         */
        val COMPOSITE_KEYS: Set<String> = setOf("textScale", "units", "warmup", "server", "decoder", "directSessionMb", "directOnMobileData") + MapOptions.KEYS + CACHE_KEYS +
            setOf("turnDeadline") +
            setOf("trackAccuracyM", "trackAutoPause", "trackNetworkFallback", "trackIntervalS") + io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles.KEYS +
            io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.KEYS + io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.EDIT_KEYS

        /** Every key `POST /settings` takes: the composites and the registry's plain settings ([AppSettings.PLAIN]). */
        val KEYS: Set<String> = COMPOSITE_KEYS + AppSettings.PLAIN.map { it.key }

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
            params["warmup"]?.let { raw -> warmup = Setting.parseFlag(raw) ?: return Parsed.Error("warmup must be 1 or 0 (got $raw)") }
            // T248: every plain setting by its registry line.
            val plain = LinkedHashMap<Setting<*>, Any>()
            for (s in AppSettings.PLAIN) {
                val raw = params[s.key] ?: continue
                plain[s] = s.parse(raw) ?: return Parsed.Error(s.error(raw))
            }
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
            // T163: the turn-around clock's deadline (sunset, civil, terrain or a typed HH:MM; no off).
            var turnDeadline: String? = null
            params["turnDeadline"]?.let { raw ->
                turnDeadline = io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.parse(raw.toString())?.value
                    ?: return Parsed.Error("turnDeadline must be sunset, civil, terrain or a time HH:MM (got $raw)")
            }
            // T172: the profiles per activity.
            val profiles = io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles
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
            // T161: the readout's options.
            val rs = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings
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
            return Parsed.Ok(textScale, unitsGiven, units, warmup, map, online, contour, pinned, turnDeadline, trackProfiles, trackEdit, readoutValues, readoutEdits, plain)
        }

    }
}
