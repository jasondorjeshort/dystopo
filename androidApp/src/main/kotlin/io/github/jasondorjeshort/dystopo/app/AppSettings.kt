package io.github.jasondorjeshort.dystopo.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitPreference
import java.util.Locale

/**
 * User settings, persisted in app preferences. [load] runs once in [DystopoApp.onCreate]; until then the
 * defaults apply. Reads are lock-free; whoever changes a value rebuilds what depends on it
 * ([SettingsRoutes] reloads the style or the tile service).
 *
 * - [textScale]: multiplier on label and marker sizes.
 * - [unitPreference]: feet or metres (docs/PLANNING.md › Units). Unset on a fresh install; seeded once from
 *   the home area (the first pack opened, else the first location fix) through [seedUnits], and after that
 *   changed only by the user. Contour tiles, the style, track stats, the profile and search results all
 *   follow [units].
 * - [warmup]: background contour warm-up after install, pack switch or reload (T35), on by default.
 * - [mapOptions] (T47): hillshade, slope, contour interval and labels, layer toggles, night; one
 *   preference per [MapOptions.KEYS] entry (`map.<key>`), in the spelling `GET /settings` reports.
 */
object AppSettings {
    private const val PREFS = "dystopo-settings"
    private const val KEY_TEXT_SCALE = "textScale"
    private const val KEY_SERVER = "server"

    /** The server a debug build on the emulator uses until one is set: `devtool serve --live` on the host (10.0.2.2). */
    const val EMULATOR_DEBUG_SERVER = "http://10.0.2.2:8765"
    private const val KEY_UNITS = "units"
    private const val KEY_UNITS_SOURCE = "unitsSource"
    private const val KEY_WARMUP = "warmup"
    private const val MAP_PREFIX = "map." // T47

    @Volatile private var prefs: SharedPreferences? = null

    /** Multiplier on every label and marker size (1.0 = default), within [MapLibreStyle.MIN_TEXT_SCALE]..[MapLibreStyle.MAX_TEXT_SCALE]. */
    @Volatile var textScale: Double = 1.0
        private set

    @Volatile var unitPreference: UnitPreference = UnitPreference()
        private set

    @Volatile var warmup: Boolean = true
        private set

    /** T47: the map options; [MapOptions] defaults until the user changes them. */
    @Volatile var mapOptions: MapOptions = MapOptions()
        private set

    /** The units everything shows: the preference, or (unset, nothing to seed from yet) the device locale's habit. */
    val units: ContourUnits get() = unitPreference.effective(localeUnits())

    private fun localeUnits(): ContourUnits =
        if (Locale.getDefault().country in setOf("US", "LR", "MM")) ContourUnits.FEET else ContourUnits.METERS

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // Stored as a Float; round so 1.6 reads back as 1.6, not 1.600000023841858.
        textScale = MapLibreStyle.clampTextScale(Math.round(p.getFloat(KEY_TEXT_SCALE, 1.0f) * 1000.0) / 1000.0)
        unitPreference = UnitPreference(
            p.getString(KEY_UNITS, null)?.let { runCatching { ContourUnits.parse(it) }.getOrNull() },
            p.getString(KEY_UNITS_SOURCE, null),
        )
        warmup = p.getBoolean(KEY_WARMUP, true)
        mapOptions = loadMapOptions(p)
        server = p.getString(KEY_SERVER, null) ?: defaultServer()
        loadCaches(p) // T41
        routeSnapM = p.getInt(KEY_ROUTE_SNAP_M, DEFAULT_ROUTE_SNAP_M).coerceIn(ROUTE_SNAP_RANGE) // T57
        debugFixedPort = p.getBoolean(KEY_DEBUG_FIXED_PORT, false)
        navStaleS = p.getInt(KEY_NAV_STALE_S, DEFAULT_NAV_STALE_S).coerceIn(NAV_STALE_RANGE) // T91
        navStartM = p.getInt(KEY_NAV_START_M, DEFAULT_NAV_START_M).coerceIn(NAV_START_RANGE)
        navSnoozeMin = p.getInt(KEY_NAV_SNOOZE_MIN, DEFAULT_NAV_SNOOZE_MIN).coerceIn(NAV_SNOOZE_RANGE)
        mapOrientation = p.getString(KEY_MAP_ORIENTATION, null) ?: "north-up" // T100
        navSpeech = p.getBoolean(KEY_NAV_SPEECH, false) // T126
        // T164: the route-time fit and its switch, then the fits the router reads.
        paceStandard = p.getBoolean(KEY_PACE_STANDARD, false)
        paceFit = p.getString(KEY_PACE_FIT, "") ?: ""
        paceSince = p.getLong(KEY_PACE_SINCE, 0L)
        io.github.jasondorjeshort.dystopo.app.routing.PaceSettings.activate()
        // T163
        turnDeadline = p.getString(KEY_TURN_DEADLINE, null)?.let { io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.parse(it)?.value } ?: io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.DEFAULT
        turnMarginMin = p.getInt(KEY_TURN_MARGIN_MIN, io.github.jasondorjeshort.dystopo.nav.TurnAround.DEFAULT_MARGIN_MIN).coerceIn(io.github.jasondorjeshort.dystopo.nav.TurnAround.MARGIN_RANGE)
        // T172
        trackActivity = Activity.of(p.getString(KEY_TRACK_ACTIVITY, null)) ?: Activity.FOOT
        trackProfiles = Activity.entries.mapNotNull { a -> p.getString(RecordingProfiles.key(a), null)?.let { v -> RecordingProfiles.parseValue(a, v)?.let { a to it } } }.toMap()
        // T161
        readoutBarometer = p.getBoolean(io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAROMETER, true)
        readoutEntries = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.KEYS.mapNotNull { k ->
            val (a, e) = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.ofKey(k)!!
            p.getString(k, null)?.let { v -> io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.parseValue(a, e, v)?.let { k to it } }
        }.toMap()
        // T176
        mapControls = p.getInt(KEY_MAP_CONTROLS, 1).coerceIn(io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.LAYOUTS)
        recenterZoom = p.getInt(KEY_RECENTER_ZOOM, io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.DEFAULT_CLOSE_ZOOM).coerceIn(io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.CLOSE_ZOOM_RANGE)
        orientationLock = p.getBoolean(KEY_ORIENTATION_LOCK, false)
    }

    // ---- T164: route times fitted to the user's tracks (routing/PaceSettings); all three synced (sync/SettingsSync) ----

    private const val KEY_PACE_STANDARD = "paceStandard"
    private const val KEY_PACE_FIT = "paceFit"
    private const val KEY_PACE_SINCE = "paceSince"

    /** "Use the standard estimate": routes keep the standard model even with a fit (off by default: the fit is used once ready). */
    @Volatile var paceStandard: Boolean = false
        private set

    /** The fit as its synced value ([io.github.jasondorjeshort.dystopo.routing.PaceFits.encode]); "" for none yet. */
    @Volatile var paceFit: String = ""
        private set

    /** Reset: only tracks started at or after this count (epoch ms; 0 all tracks). */
    @Volatile var paceSince: Long = 0L
        private set

    fun setPace(standard: Boolean?, fit: String?, since: Long?) {
        val e = prefs?.edit()
        standard?.let { paceStandard = it; e?.putBoolean(KEY_PACE_STANDARD, it) }
        fit?.let { paceFit = it; e?.putString(KEY_PACE_FIT, it) }
        since?.let { paceSince = it; e?.putLong(KEY_PACE_SINCE, it) }
        e?.apply()
    }

    // ---- T163: the turn-around clock (nav/TurnAround): what it counts to and the margin; synced (sync/SettingsSync) ----

    private const val KEY_TURN_DEADLINE = "turnDeadline"
    private const val KEY_TURN_MARGIN_MIN = "turnMarginMin"
    private const val KEY_TURN_TYPED = "turnTypedTime"

    /** `sunset` (the default), `civil` or a typed `HH:MM` ([io.github.jasondorjeshort.dystopo.nav.DeadlineSetting]); never off. */
    @Volatile var turnDeadline: String = io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.DEFAULT
        private set

    /** Minutes to be out before the deadline (default 30, 0 to 240). */
    @Volatile var turnMarginMin: Int = io.github.jasondorjeshort.dystopo.nav.TurnAround.DEFAULT_MARGIN_MIN
        private set

    /** The last time typed for the deadline (this phone only), so A time I type offers it again after sunset was chosen. */
    val turnTypedTime: String get() = prefs?.getString(KEY_TURN_TYPED, null) ?: "18:00"

    fun setTurn(deadline: String?, marginMin: Int?) {
        val e = prefs?.edit()
        deadline?.let {
            turnDeadline = it
            e?.putString(KEY_TURN_DEADLINE, it)
            if (':' in it) e?.putString(KEY_TURN_TYPED, it)
        }
        marginMin?.let { turnMarginMin = it; e?.putInt(KEY_TURN_MARGIN_MIN, it) }
        e?.apply()
    }

    // ---- T172: the recording activity remembered for the next Record, and one profile value per activity (tracking/ActivityPicker); synced ----

    private const val KEY_TRACK_ACTIVITY = "trackActivity"

    /** The activity the picker marks and a recording with none given starts with (T161's readout reads it too). */
    @Volatile var trackActivity: Activity = Activity.FOOT
        private set

    /** The stored choices per activity ([RecordingProfiles.encode]'s form); an activity missing has its defaults. */
    @Volatile var trackProfiles: Map<Activity, String> = emptyMap()
        private set

    fun setTrackActivity(a: Activity) {
        trackActivity = a
        prefs?.edit()?.putString(KEY_TRACK_ACTIVITY, a.code)?.apply()
    }

    /** Stores [a]'s choices ([value] already checked by [RecordingProfiles.parseValue]; null: back to the defaults). */
    fun setTrackProfile(a: Activity, value: String?) {
        trackProfiles = if (value == null) trackProfiles - a else trackProfiles + (a to value)
        prefs?.edit()?.apply { if (value == null) remove(RecordingProfiles.key(a)) else putString(RecordingProfiles.key(a), value) }?.apply()
    }

    /** [a]'s recording profile: its defaults with the stored choices. */
    fun trackProfile(a: Activity = trackActivity): ActivityProfile = RecordingProfiles.profile(a, trackProfiles[a])

    // ---- T161: the live readout's options per activity and entry, and the barometer switch (readout/ReadoutSettings); synced ----

    /** The barometer switch (default on): the readout's elevation and climb use the phone's barometer on foot, bike and boat. */
    @Volatile var readoutBarometer: Boolean = true
        private set

    /** The stored options by [io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.key] (`readout.<activity>.<entry>`); missing ones are the defaults. */
    @Volatile var readoutEntries: Map<String, String> = emptyMap()
        private set

    fun setReadoutBarometer(on: Boolean) {
        readoutBarometer = on
        prefs?.edit()?.putBoolean(io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAROMETER, on)?.apply()
    }

    /** Stores one activity's entry options ([value] already checked by `ReadoutSettings.parseValue`; null: back to the defaults). */
    fun setReadoutEntry(key: String, value: String?) {
        readoutEntries = if (value == null) readoutEntries - key else readoutEntries + (key to value)
        prefs?.edit()?.apply { if (value == null) remove(key) else putString(key, value) }?.apply()
    }

    /** [a]'s readout options (the remembered activity's by default): its defaults with the stored values and the barometer switch. */
    fun readoutOptions(a: Activity = trackActivity): io.github.jasondorjeshort.dystopo.readout.ReadoutOptions =
        io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.options(a, readoutEntries, readoutBarometer)

    // ---- T176: the position button's layout, its close zoom and the orientation lock (controls/RecenterButton); synced ----

    private const val KEY_MAP_CONTROLS = "mapControls"
    private const val KEY_RECENTER_ZOOM = "recenterZoom"
    private const val KEY_ORIENTATION_LOCK = "orientationLock"

    /** The position button's layout: 1 (the default, one button), 2 (plus the orientation button), 3 (plus the lock). */
    @Volatile var mapControls: Int = 1
        private set

    /** The zoom a tap on the position button brings the map to (default 14); a tap never zooms out. */
    @Volatile var recenterZoom: Int = io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.DEFAULT_CLOSE_ZOOM
        private set

    /** "Keep north up" (default off): no gesture changes the orientation; only the orientation button and the navigation card do. */
    @Volatile var orientationLock: Boolean = false
        private set

    fun setMapControls(layout: Int?, closeZoom: Int?, lock: Boolean?) {
        val e = prefs?.edit()
        layout?.let { mapControls = it; e?.putInt(KEY_MAP_CONTROLS, it) }
        closeZoom?.let { recenterZoom = it; e?.putInt(KEY_RECENTER_ZOOM, it) }
        lock?.let { orientationLock = it; e?.putBoolean(KEY_ORIENTATION_LOCK, it) }
        e?.apply()
    }

    // ---- T126: spoken turn directions while navigating (nav/NavSpeech), off by default; synced (sync/SettingsSync) ----

    private const val KEY_NAV_SPEECH = "navSpeech"

    @Volatile var navSpeech: Boolean = false
        private set

    fun setNavSpeech(on: Boolean) {
        navSpeech = on
        prefs?.edit()?.putBoolean(KEY_NAV_SPEECH, on)?.apply()
    }

    // ---- T100: the map orientation kept across restarts (controls/MapOrientation): north-up or facing ----

    private const val KEY_MAP_ORIENTATION = "mapOrientation"

    @Volatile var mapOrientation: String = "north-up"
        private set

    fun setMapOrientation(key: String) {
        mapOrientation = key
        prefs?.edit()?.putString(KEY_MAP_ORIENTATION, key)?.apply()
    }

    // ---- T91: navigation thresholds (the T65 constants) ----

    private const val KEY_NAV_STALE_S = "navStaleS"
    private const val KEY_NAV_START_M = "navStartM"
    private const val KEY_NAV_SNOOZE_MIN = "navSnoozeMin"
    const val DEFAULT_NAV_STALE_S = 30
    const val DEFAULT_NAV_START_M = 2000
    const val DEFAULT_NAV_SNOOZE_MIN = 10
    val NAV_STALE_RANGE = 5..600
    val NAV_START_RANGE = 100..50_000
    val NAV_SNOOZE_RANGE = 1..240

    /** A GPS fix older than this is not used, and with no usable fix for this long the bar says so (seconds). */
    @Volatile var navStaleS: Int = DEFAULT_NAV_STALE_S
        private set
    /** The last known location starts navigation only within this of the line (metres). */
    @Volatile var navStartM: Int = DEFAULT_NAV_START_M
        private set
    /** The banner's Snooze button holds the off-route alert this long (minutes). */
    @Volatile var navSnoozeMin: Int = DEFAULT_NAV_SNOOZE_MIN
        private set

    fun setNav(staleS: Int?, startM: Int?, snoozeMin: Int?) {
        val e = prefs?.edit()
        staleS?.let { navStaleS = it; e?.putInt(KEY_NAV_STALE_S, it) }
        startM?.let { navStartM = it; e?.putInt(KEY_NAV_START_M, it) }
        snoozeMin?.let { navSnoozeMin = it; e?.putInt(KEY_NAV_SNOOZE_MIN, it) }
        e?.apply()
    }

    // ---- T57: routing snap distance, debug server port ----

    private const val KEY_ROUTE_SNAP_M = "routeSnapM"
    private const val KEY_DEBUG_FIXED_PORT = "debugFixedPort"
    const val DEFAULT_ROUTE_SNAP_M = 300
    /** How far a tapped start or end may be from the nearest trail or road, metres. */
    val ROUTE_SNAP_RANGE = 50..1000

    /** T57: the route planner's snap distance (default 300 m, up to 1 km). */
    @Volatile var routeSnapM: Int = DEFAULT_ROUTE_SNAP_M
        private set

    fun setRouteSnapM(m: Int) {
        routeSnapM = m
        prefs?.edit()?.putInt(KEY_ROUTE_SNAP_M, m)?.apply()
    }

    /**
     * T57: debug builds serve the debug routes on a random loopback port announced only in logcat
     * ([DystopoApp.debugPort]); with this set (`POST /settings?debugFixedPort=1`, from the next launch) they
     * stay on the tile server's port 8686 with the `/health` token bootstrap, as before T57.
     */
    @Volatile var debugFixedPort: Boolean = false
        private set

    fun setDebugFixedPort(on: Boolean) {
        debugFixedPort = on
        prefs?.edit()?.putBoolean(KEY_DEBUG_FIXED_PORT, on)?.commit() // commit: usually followed by a restart
    }

    // ---- T41: cache caps ----

    private const val KEY_ONLINE_CACHE_MB = "onlineCacheMb"
    private const val KEY_CONTOUR_CACHE_MB = "contourCacheMb"
    private const val KEY_CACHE_BUDGET_MB = "cacheBudgetMb" // T112

    /** T112: the caps default to the default total ([BudgetPlan.DEFAULT_MB]): each is a ceiling under the one budget. */
    const val DEFAULT_ONLINE_CACHE_MB = 1024L
    const val DEFAULT_CONTOUR_CACHE_MB = 1024L

    /** The caps before T112 (what an install that never set one had), for [BudgetPlan.migrate]. */
    private const val PRE_BUDGET_CAP_MB = 256L

    /** Allowed range of either cap set on its own (`POST /settings`), in MB. */
    val CACHE_MB_RANGE = 16L..8192L

    /** T112: the range a stored cap may have: the cache budget sets both caps to its total, up to 1 TB. */
    val CACHE_CAP_MB_RANGE = CACHE_MB_RANGE.first..io.github.jasondorjeshort.dystopo.app.cachebudget.BudgetPlan.RANGE_MB.last

    /**
     * T115 (T112's follow-up): the range of [pinnedCacheMb], up to the cache budget's 1 TB. It shared [CACHE_MB_RANGE]
     * and stopped at 8 GB, below the Large preset (128 GB), so a phone with room for a long trip's areas could not pin them.
     */
    val PINNED_CACHE_MB_RANGE = CACHE_MB_RANGE.first..io.github.jasondorjeshort.dystopo.app.cachebudget.BudgetPlan.RANGE_MB.last

    /** Cap of the online tile cache (fetched DEM, OSM, glyphs and online contours), MB; T112: a ceiling under [cacheBudgetMb]. */
    @Volatile var onlineCacheMb: Long = DEFAULT_ONLINE_CACHE_MB
        private set

    /** Cap of the contour tile cache over every pack's directory, MB; T112: a ceiling under [cacheBudgetMb]. */
    @Volatile var contourCacheMb: Long = DEFAULT_CONTOUR_CACHE_MB
        private set

    /**
     * T112: the one cache budget over online tiles, contours and shading, and source data (MB;
     * [io.github.jasondorjeshort.dystopo.app.cachebudget.CacheBudget]). An install from before T112 gets the small preset, or
     * its two caps together when they were raised past it ([BudgetPlan.migrate]), stored at first load.
     */
    @Volatile var cacheBudgetMb: Long = io.github.jasondorjeshort.dystopo.app.cachebudget.BudgetPlan.DEFAULT_MB
        private set

    private fun loadCaches(p: SharedPreferences) {
        onlineCacheMb = p.getLong(KEY_ONLINE_CACHE_MB, DEFAULT_ONLINE_CACHE_MB).coerceIn(CACHE_CAP_MB_RANGE)
        contourCacheMb = p.getLong(KEY_CONTOUR_CACHE_MB, DEFAULT_CONTOUR_CACHE_MB).coerceIn(CACHE_CAP_MB_RANGE)
        pinnedCacheMb = p.getLong(KEY_PINNED_CACHE_MB, DEFAULT_PINNED_CACHE_MB).coerceIn(PINNED_CACHE_MB_RANGE) // T115
        tripCorridorM = p.getInt(KEY_TRIP_CORRIDOR_M, io.github.jasondorjeshort.dystopo.areas.TripCorridor.DEFAULT_WIDTH_M).takeIf { io.github.jasondorjeshort.dystopo.areas.TripCorridor.validWidth(it) } ?: io.github.jasondorjeshort.dystopo.areas.TripCorridor.DEFAULT_WIDTH_M // T130
        val plan = io.github.jasondorjeshort.dystopo.app.cachebudget.BudgetPlan
        cacheBudgetMb = if (p.contains(KEY_CACHE_BUDGET_MB)) {
            p.getLong(KEY_CACHE_BUDGET_MB, plan.DEFAULT_MB).coerceIn(plan.RANGE_MB)
        } else {
            plan.migrate(p.getLong(KEY_ONLINE_CACHE_MB, PRE_BUDGET_CAP_MB), p.getLong(KEY_CONTOUR_CACHE_MB, PRE_BUDGET_CAP_MB))
                .also { p.edit().putLong(KEY_CACHE_BUDGET_MB, it).apply() }
        }
    }

    /** T112: sets and persists the total (the caller checked [BudgetPlan.RANGE_MB] and runs the budget). */
    fun setCacheBudgetMb(mb: Long) {
        cacheBudgetMb = mb
        prefs?.edit()?.putLong(KEY_CACHE_BUDGET_MB, mb)?.apply()
    }

    /** T112: both caps to the total [mb] (the budget's ceilings; the caller resizes the caches). */
    fun setCapsFromBudget(mb: Long) {
        onlineCacheMb = mb
        contourCacheMb = mb
        prefs?.edit()?.putLong(KEY_ONLINE_CACHE_MB, mb)?.putLong(KEY_CONTOUR_CACHE_MB, mb)?.apply()
    }

    private const val KEY_PINNED_CACHE_MB = "pinnedCacheMb"
    const val DEFAULT_PINNED_CACHE_MB = 2048L

    /** T91: budget of the pinned tiles of offline areas (no longer counted in [onlineCacheMb]); a download that would pass it is refused, MB ([PINNED_CACHE_MB_RANGE]). */
    @Volatile var pinnedCacheMb: Long = DEFAULT_PINNED_CACHE_MB
        private set

    fun setPinnedCacheMb(mb: Long) {
        pinnedCacheMb = mb
        prefs?.edit()?.putLong(KEY_PINNED_CACHE_MB, mb)?.apply()
    }

    // T130: the corridor every trip keeps offline, metres each side (0: off); validated and applied by areas/TripCorridors.
    private const val KEY_TRIP_CORRIDOR_M = "tripCorridorM"
    @Volatile var tripCorridorM: Int = io.github.jasondorjeshort.dystopo.areas.TripCorridor.DEFAULT_WIDTH_M
        private set
    fun setTripCorridorM(m: Int) { tripCorridorM = m; prefs?.edit()?.putInt(KEY_TRIP_CORRIDOR_M, m)?.apply() }

    /** Sets and persists a cap (the caller checked the range and resizes the cache). */
    fun setOnlineCacheMb(mb: Long) {
        onlineCacheMb = mb
        prefs?.edit()?.putLong(KEY_ONLINE_CACHE_MB, mb)?.apply()
    }

    fun setContourCacheMb(mb: Long) {
        contourCacheMb = mb
        prefs?.edit()?.putLong(KEY_CONTOUR_CACHE_MB, mb)?.apply()
    }

    /**
     * T38: the Dystopo server for online mode (tiles where no pack covers the view, "Download this
     * region"), e.g. `http://10.0.2.2:8765`; empty for offline only. Empty by default, except in a debug
     * build on the emulator ([EMULATOR_DEBUG_SERVER]).
     */
    @Volatile var server: String = ""
        private set

    private fun defaultServer(): String {
        val emulator = Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.contains("sdk")
        return if (BuildConfig.DEBUG && emulator) EMULATOR_DEBUG_SERVER else ""
    }

    /** Normalizes a server setting: "" (offline only) or `http(s)://host[:port][/path]` without a trailing slash; null if invalid. */
    fun normalizeServer(value: String): String? {
        val v = value.trim().trimEnd('/')
        if (v.isEmpty()) return ""
        val ok = Regex("""^https?://[A-Za-z0-9.\-\[\]:]+(/[A-Za-z0-9._~\-/]*)?$""").matches(v)
        return v.takeIf { ok }
    }

    /** Sets and persists the server (already normalized by [normalizeServer]). */
    fun setServer(value: String) {
        server = value
        prefs?.edit()?.putString(KEY_SERVER, value)?.apply()
    }

    /** Sets and persists the text scale; the caller has checked the range (see [SettingsRoutes]). */
    fun setTextScale(value: Double): Double {
        val v = MapLibreStyle.clampTextScale(value)
        textScale = v
        prefs?.edit()?.putFloat(KEY_TEXT_SCALE, v.toFloat())?.apply()
        return v
    }

    /** The user's choice of units (null clears it, so the next pack or fix seeds it again). */
    fun setUnits(units: ContourUnits?) {
        store(if (units == null) UnitPreference() else UnitPreference(units, "user"))
    }

    /**
     * Seeds the units from the home area once: a no-op when they are already set. Returns true when this
     * call set them and the effective units changed (the caller then rebuilds the style and contour tiles).
     */
    @Synchronized
    fun seedUnits(lat: Double, lon: Double, source: String): Boolean {
        val before = units
        val next = unitPreference.seeded(lat, lon, source)
        if (next == unitPreference) return false
        store(next)
        return units != before
    }

    private fun store(p: UnitPreference) {
        unitPreference = p
        prefs?.edit()?.apply {
            val u = p.units
            if (u == null) remove(KEY_UNITS) else putString(KEY_UNITS, u.symbol)
            if (p.source == null) remove(KEY_UNITS_SOURCE) else putString(KEY_UNITS_SOURCE, p.source)
        }?.apply()
    }

    /** Stored map options; a value that no longer parses (say an interval of the other unit system) falls back to its default. */
    private fun loadMapOptions(p: SharedPreferences): MapOptions {
        var o = MapOptions()
        for (key in MapOptions.KEYS) {
            val raw = p.getString(MAP_PREFIX + key, null) ?: continue
            o = runCatching { MapOptions.parse(mapOf(key to raw), o, units) }.getOrDefault(o)
        }
        return o
    }

    /** Sets and persists the map options (validated by the caller, see [SettingsRoutes]). */
    fun setMapOptions(o: MapOptions) {
        mapOptions = o
        prefs?.edit()?.apply {
            val json = o.json(units)
            for (key in MapOptions.KEYS) putString(MAP_PREFIX + key, json[key].toString())
        }?.apply()
    }

    fun setWarmup(on: Boolean) {
        warmup = on
        prefs?.edit()?.putBoolean(KEY_WARMUP, on)?.apply()
    }

    fun json(): Map<String, Any?> = mapOf(
        "textScale" to textScale,
        "textScaleRange" to listOf(MapLibreStyle.MIN_TEXT_SCALE, MapLibreStyle.MAX_TEXT_SCALE),
        "baseTextScale" to MapLibreStyle.BASE_TEXT_SCALE,
        "server" to server,
        "units" to units.symbol,
        "unitsSet" to (unitPreference.units != null),
        "unitsSource" to (unitPreference.source ?: "locale default (not set yet)"),
        "warmup" to warmup,
        "tripCorridorM" to tripCorridorM, // T130
        "onlineCacheMb" to onlineCacheMb, // T41
        "pinnedCacheMb" to pinnedCacheMb, // T91
        "contourCacheMb" to contourCacheMb,
        "cacheBudgetMb" to cacheBudgetMb, // T112
        "routeSnapM" to routeSnapM, // T57
        "routeSnapRange" to listOf(ROUTE_SNAP_RANGE.first, ROUTE_SNAP_RANGE.last),
        "debugFixedPort" to debugFixedPort,
        "navStaleS" to navStaleS, "navStartM" to navStartM, "navSnoozeMin" to navSnoozeMin, // T91
        "navSpeech" to navSpeech, // T126
        "paceStandard" to paceStandard, "paceFit" to paceFit, "paceSince" to paceSince, // T164
        "turnDeadline" to turnDeadline, "turnMarginMin" to turnMarginMin, // T163
        // T172: the remembered activity, its profile's four choices, and every activity's (stored value or null for the defaults)
        "trackActivity" to trackActivity.code,
        "trackProfile" to RecordingProfiles.json(trackProfile()),
        "trackProfiles" to Activity.entries.associate { a -> a.code to (RecordingProfiles.json(trackProfile(a)) + mapOf("stored" to trackProfiles[a])) },
        // T161: the barometer switch, the remembered activity's readout options per entry, and the stored values
        "readoutBarometer" to readoutBarometer,
        "readout" to readoutOptions().let { o -> io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.entries.associate { e -> e.code to o[e].encode() } },
        "readoutStored" to readoutEntries,
        "mapControls" to mapControls, "recenterZoom" to recenterZoom, "orientationLock" to orientationLock, // T176
    ) + mapOptions.json(units) // T47
}
