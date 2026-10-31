package io.github.jasondorjeshort.dystopo.app.sync

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings
import io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.sync.SyncStore

/**
 * App settings through the synced log (T51). The user's choices of units, text scale and map options
 * ([MapOptions.KEYS]) are written as setting operations (`app.units`, `app.textScale`,
 * `app.map.<key>`), so every device converges on the newest choice per setting (last writer wins per
 * key, docs/PLANNING.md › Sync). Device-local settings (server, warm-up, cache caps, decoder, the sync
 * peer) stay local, and so do units seeded from a region: only a choice the user made is published.
 * T110: the scale bar's both-units choice (`app.scaleBar.both`, `on` or `off`; [ScaleBarSettings], not a
 * [SettingsRoutes] key, so it is applied here and not through `POST /settings`) syncs too ([LOCAL_KEYS]).
 * T126: spoken directions (`app.navSpeech`, `on` or `off`) are a [SettingsRoutes] key and sync through it.
 * T136: so is the trip corridor's width (`app.tripCorridorM`, whole metres, `0` off); the per-trip "not kept" choice
 * stays on the phone (T130).
 * T164: the route-time fit (`app.paceFit`, [io.github.jasondorjeshort.dystopo.routing.PaceFits.encode]'s value, so every
 * device shows the same numbers), its switch (`app.paceStandard`, `on` or `off`) and its reset (`app.paceSince`, epoch ms).
 * T163: the turn-around clock's deadline and margin (`app.turnDeadline`, `sunset`, `civil` or `HH:MM`; `app.turnMarginMin`,
 * whole minutes) are [SettingsRoutes] keys too.
 * T172: the recording activity the picker remembers (`app.trackActivity`, an activity code) and one value per activity for its
 * four recording choices (`app.trackProfile.<activity>`, `acc=50;autopause=0;net=1;interval=1000`); both [SettingsRoutes] keys.
 * T161: the live readout's options per activity and entry (`app.readout.<activity>.<entry>`,
 * `shown=1;pace=0;damping=medium;pm=0;knots=0`) and its barometer switch (`app.readoutBarometer`, `on` or `off`); both
 * [SettingsRoutes] keys.
 * T176: the position button's layout (`app.mapControls`, `1`, `2` or `3`), its close zoom (`app.recenterZoom`, a whole
 * zoom level) and "Keep north up" (`app.orientationLock`, `on` or `off`); [SettingsRoutes] keys. The orientation itself
 * stays on each phone.
 * T177: the readout bar's switch (`app.readoutBar`, `on` or `off`) and the climb window (`app.climbWindow`, seconds);
 * [SettingsRoutes] keys.
 * T178: the pressure-fall warning (`app.pressureFallWarning`: `off`, `more`, `standard` or `fewer`); a [SettingsRoutes] key.
 * T187: water along the route (`app.waterAlongRoute`) and its spoken line (`app.waterSpoken`), `on` or `off`;
 * [SettingsRoutes] keys.
 *
 * - [publish] runs after [SettingsRoutes.apply] (`POST /settings` and the settings sheet) and writes the
 *   keys that request set, with their values after the change; [publishLocal] after a [LOCAL_KEYS] setting changed
 *   (the Map options row, `POST /scalebar?both=`).
 * - [pull] runs after a sync merged something: synced values that differ from the current settings are
 *   applied through the same [SettingsRoutes.apply] (validated by [SettingsRoutes.parse]; a value this
 *   build does not accept is skipped and reported), [LOCAL_KEYS] through their own setters, without publishing
 *   them again.
 */
object SettingsSync {
    const val PREFIX = "app."
    private const val MAP = "map."
    private const val TAG = "DystopoSync"

    /** T110: the scale bar's both-units choice, under its settings-file key. */
    const val SCALE_BAR_BOTH = "scaleBar.both"

    /** T126: spoken turn directions, a [SettingsRoutes] key whose synced value is `on` or `off`. */
    const val NAV_SPEECH = "navSpeech"

    /** T136: the trip corridor's width each side, a [SettingsRoutes] key whose synced value is whole metres (`0` off). */
    const val TRIP_CORRIDOR_M = "tripCorridorM"

    /** T164: the route-time fit, its "Use the standard estimate" switch and its reset, [SettingsRoutes] keys. */
    const val PACE_FIT = "paceFit"
    const val PACE_STANDARD = "paceStandard"
    const val PACE_SINCE = "paceSince"
    /** T163: the turn-around clock's deadline (`sunset`, `civil` or `HH:MM`) and margin (whole minutes), [SettingsRoutes] keys. */
    const val TURN_DEADLINE = "turnDeadline"
    const val TURN_MARGIN_MIN = "turnMarginMin"
    /** T172: the remembered recording activity, a [SettingsRoutes] key whose synced value is an activity code. */
    const val TRACK_ACTIVITY = "trackActivity"
    /** T161: the readout's barometer switch, a [SettingsRoutes] key whose synced value is `on` or `off`. */
    const val READOUT_BAROMETER = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAROMETER
    /** T176: the position button's layout, its close zoom and the orientation lock, [SettingsRoutes] keys. */
    const val MAP_CONTROLS = "mapControls"
    const val RECENTER_ZOOM = "recenterZoom"
    const val ORIENTATION_LOCK = "orientationLock"
    /** T177: the readout bar's switch (`on`/`off`) and the climb window (seconds), [SettingsRoutes] keys. */
    const val READOUT_BAR = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAR
    const val CLIMB_WINDOW = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.CLIMB_WINDOW
    /** T178: the pressure-fall warning (`off`, `more`, `standard`, `fewer`), a [SettingsRoutes] key. */
    const val PRESSURE_FALL_WARNING = AppSettings.KEY_PRESSURE_FALL_WARNING
    /** T187: water along the route and its spoken line (`on`/`off`), [SettingsRoutes] keys. */
    const val WATER_ALONG_ROUTE = "waterAlongRoute"
    const val WATER_SPOKEN = "waterSpoken"

    /** T110: synced settings kept outside [SettingsRoutes] (applied by [applyLocal]). */
    val LOCAL_KEYS = setOf(SCALE_BAR_BOTH)

    @Volatile private var applyingRemote = false

    /** The last [pull]: applied keys, skipped values, when. For `/sync/status`. */
    @Volatile var lastPull: Map<String, Any?>? = null
        private set

    @Volatile var published = 0L
        private set

    fun syncedKey(settingsKey: String) = if (settingsKey in MapOptions.KEYS) PREFIX + MAP + settingsKey else PREFIX + settingsKey

    private fun settingsKey(syncedKey: String) = syncedKey.removePrefix(PREFIX).removePrefix(MAP)

    /** T110: a boolean setting's synced value. */
    fun onOff(on: Boolean) = if (on) "on" else "off"

    /** T110: a synced `on`/`off` value, null for anything else (skipped, as a value this build does not accept). */
    fun parseOnOff(v: String?): Boolean? = when (v) {
        "on" -> true
        "off" -> false
        else -> null
    }

    /** T110: the [LOCAL_KEYS] settings as they are now, by synced key. */
    fun localValues(bothUnits: Boolean): Map<String, String> = mapOf(syncedKey(SCALE_BAR_BOTH) to onOff(bothUnits))

    /** The synced settings as they are now, by synced key; null for units the user has not chosen. */
    fun current(): Map<String, String?> {
        val m = LinkedHashMap<String, String?>()
        m[syncedKey("units")] = AppSettings.unitPreference.units?.takeIf { AppSettings.unitPreference.source == "user" }?.symbol
        m[syncedKey("textScale")] = AppSettings.textScale.toString()
        val map = AppSettings.mapOptions.json(AppSettings.units)
        for (k in MapOptions.KEYS) m[syncedKey(k)] = map[k]?.toString()
        m += localValues(ScaleBarSettings.bothUnits) // T110
        m[syncedKey(NAV_SPEECH)] = onOff(AppSettings.navSpeech) // T126
        m[syncedKey(TRIP_CORRIDOR_M)] = AppSettings.tripCorridorM.toString() // T136
        m[syncedKey(PACE_STANDARD)] = onOff(AppSettings.paceStandard) // T164
        m[syncedKey(PACE_FIT)] = AppSettings.paceFit
        m[syncedKey(PACE_SINCE)] = AppSettings.paceSince.toString()
        m[syncedKey(TURN_DEADLINE)] = AppSettings.turnDeadline // T163
        m[syncedKey(TURN_MARGIN_MIN)] = AppSettings.turnMarginMin.toString()
        m[syncedKey(TRACK_ACTIVITY)] = AppSettings.trackActivity.code // T172
        for (a in io.github.jasondorjeshort.dystopo.model.Activity.entries) m[syncedKey(RecordingProfiles.key(a))] = AppSettings.trackProfiles[a]
        m[syncedKey(READOUT_BAROMETER)] = onOff(AppSettings.readoutBarometer) // T161
        for (k in io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.KEYS) m[syncedKey(k)] = AppSettings.readoutEntries[k]
        m[syncedKey(MAP_CONTROLS)] = AppSettings.mapControls.toString() // T176
        m[syncedKey(RECENTER_ZOOM)] = AppSettings.recenterZoom.toString()
        m[syncedKey(ORIENTATION_LOCK)] = onOff(AppSettings.orientationLock)
        m[syncedKey(READOUT_BAR)] = onOff(AppSettings.readoutBar) // T177
        m[syncedKey(CLIMB_WINDOW)] = AppSettings.climbWindowS.toString()
        m[syncedKey(PRESSURE_FALL_WARNING)] = AppSettings.pressureFallWarning.code // T178
        m[syncedKey(WATER_ALONG_ROUTE)] = onOff(AppSettings.waterAlongRoute) // T187
        m[syncedKey(WATER_SPOKEN)] = onOff(AppSettings.waterSpoken)
        return m
    }

    /**
     * Writes [keys] with their [values] to [store] where the log holds something else; returns how many were written.
     * Pure (no Android), so tests run it on an in-memory store.
     */
    fun write(store: SyncStore, keys: Collection<String>, values: Map<String, String?>, now: Long): Int {
        val held = store.settings()
        var n = 0
        for (k in keys) if (held[k] != values[k]) { store.setSetting(k, values[k], now); n++ }
        return n
    }

    /** What [pull] does with the synced settings against the current ones (pure; tests run it). */
    data class Plan(
        /** Values for [SettingsRoutes.parse], by settings key. */
        val params: Map<String, Any?>,
        /** T110: [LOCAL_KEYS] values to set, by settings key. */
        val local: Map<String, Boolean>,
        /** Synced keys whose values are not taken (unknown keys, values this build does not accept). */
        val skipped: List<String>,
    )

    fun plan(synced: Map<String, String>, current: Map<String, String?>, routeKeys: Set<String>): Plan {
        val params = LinkedHashMap<String, Any?>()
        val local = LinkedHashMap<String, Boolean>()
        val skipped = ArrayList<String>()
        for ((k, v) in synced) {
            if (!k.startsWith(PREFIX) || current[k] == v) continue
            val key = settingsKey(k)
            when (key) {
                in LOCAL_KEYS -> parseOnOff(v)?.let { local[key] = it } ?: run { skipped += k }
                in routeKeys -> params[key] = v
                else -> skipped += k
            }
        }
        return Plan(params, local, skipped)
    }

    /** One line in [SettingsRoutes.apply]: writes the settings [parsed] set to the log. */
    fun publish(app: DystopoApp, parsed: SettingsRoutes.Parsed.Ok) {
        val keys = LinkedHashSet<String>()
        if (parsed.textScale != null) keys += syncedKey("textScale")
        if (parsed.unitsGiven) {
            keys += syncedKey("units")
            keys += syncedKey("contourInterval") // a units change can reset a fixed interval to auto
        }
        parsed.map.keys.forEach { keys += syncedKey(it) }
        if (parsed.navSpeech != null) keys += syncedKey(NAV_SPEECH) // T126
        if (parsed.tripCorridorM != null) keys += syncedKey(TRIP_CORRIDOR_M) // T136
        if (parsed.paceStandard != null) keys += syncedKey(PACE_STANDARD) // T164
        if (parsed.paceFit != null) keys += syncedKey(PACE_FIT)
        if (parsed.paceSince != null) keys += syncedKey(PACE_SINCE)
        if (parsed.turnDeadline != null) keys += syncedKey(TURN_DEADLINE) // T163
        if (parsed.turnMarginMin != null) keys += syncedKey(TURN_MARGIN_MIN)
        if (parsed.trackActivity != null) keys += syncedKey(TRACK_ACTIVITY) // T172
        parsed.trackProfiles.keys.forEach { keys += syncedKey(RecordingProfiles.key(it)) }
        if (parsed.trackEdit != null) keys += syncedKey(RecordingProfiles.key(AppSettings.trackActivity)) // the remembered one's, as apply edited it
        if (parsed.readoutBarometer != null) keys += syncedKey(READOUT_BAROMETER) // T161
        parsed.readoutValues.keys.forEach { keys += syncedKey(it) }
        parsed.readoutEdits.keys.forEach { keys += syncedKey(io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.key(AppSettings.trackActivity, it)) }
        if (parsed.mapControls != null) keys += syncedKey(MAP_CONTROLS) // T176
        if (parsed.recenterZoom != null) keys += syncedKey(RECENTER_ZOOM)
        if (parsed.orientationLock != null) keys += syncedKey(ORIENTATION_LOCK)
        if (parsed.readoutBar != null) keys += syncedKey(READOUT_BAR) // T177
        if (parsed.climbWindow != null) keys += syncedKey(CLIMB_WINDOW)
        if (parsed.pressureFallWarning != null) keys += syncedKey(PRESSURE_FALL_WARNING) // T178
        if (parsed.waterAlongRoute != null) keys += syncedKey(WATER_ALONG_ROUTE) // T187
        if (parsed.waterSpoken != null) keys += syncedKey(WATER_SPOKEN)
        publishKeys(app, keys)
    }

    /** T110: after a [LOCAL_KEYS] setting changed on this device ([ScaleBarSettings.setBothUnits]); a pulled value is not sent back. */
    fun publishLocal(app: DystopoApp, settingsKey: String) {
        require(settingsKey in LOCAL_KEYS) { "$settingsKey is not a local synced setting" }
        publishKeys(app, listOf(syncedKey(settingsKey)))
    }

    private fun publishKeys(app: DystopoApp, keys: Collection<String>) {
        if (applyingRemote || keys.isEmpty()) return
        val values = current()
        val now = System.currentTimeMillis()
        val t = Tracking.of(app)
        try {
            t.call { published += write(t.store as SyncStore, keys, values, now) }
        } catch (e: Exception) {
            Log.w(TAG, "publishing settings failed", e)
        }
    }

    /** After a merge: applies synced settings that differ from the current ones. Returns what changed. */
    fun pull(app: DystopoApp): Map<String, Any?> {
        val t = Tracking.of(app)
        val synced = t.call { (t.store as SyncStore).settings() }
        val p = plan(synced, current(), SettingsRoutes.KEYS)
        val params = LinkedHashMap(p.params)
        val skipped = ArrayList(p.skipped)
        // Units first decide which contour intervals are valid.
        val units = (SettingsRoutes.parse(params.filterKeys { it == "units" }, AppSettings.units) as? SettingsRoutes.Parsed.Ok)?.units ?: AppSettings.units
        var parsed = SettingsRoutes.parse(params, units)
        if (parsed is SettingsRoutes.Parsed.Error) {
            // Drop values this build does not accept one by one, keep the rest.
            for (k in params.keys.toList()) {
                if (SettingsRoutes.parse(mapOf(k to params[k]), units) is SettingsRoutes.Parsed.Error) { params.remove(k); skipped += PREFIX + k }
            }
            parsed = SettingsRoutes.parse(params, units)
        }
        val applied = LinkedHashMap<String, Any?>(params)
        p.local.forEach { (k, v) -> applied[k] = onOff(v) }
        val result = linkedMapOf<String, Any?>("at" to System.currentTimeMillis(), "applied" to applied, "skipped" to skipped)
        if ((params.isNotEmpty() && parsed is SettingsRoutes.Parsed.Ok) || p.local.isNotEmpty()) {
            applyingRemote = true
            try {
                if (params.isNotEmpty() && parsed is SettingsRoutes.Parsed.Ok) SettingsRoutes(app).apply(parsed)
                if (p.local.isNotEmpty()) applyLocal(app, p.local)
            } finally {
                applyingRemote = false
            }
            app.map.onMain { SettingsUi.revision++ }
        }
        lastPull = result
        return result
    }

    /** T110: sets [LOCAL_KEYS] values a sync brought (on the main thread, where the scale bar reads them). */
    private fun applyLocal(app: DystopoApp, values: Map<String, Boolean>) {
        values[SCALE_BAR_BOTH]?.let { on -> app.map.onMain { ScaleBarSettings.setBothUnits(on, publish = false) } }
    }

    /** For `/sync/status` › `settingsSync`: `synced` is this device's values, `held` (T110) the synced log's `app.` settings. */
    fun json(held: Map<String, String>? = null): Map<String, Any?> = linkedMapOf(
        "published" to published, "lastPull" to lastPull, "synced" to current(),
        "held" to held?.filterKeys { it.startsWith(PREFIX) },
    )
}
