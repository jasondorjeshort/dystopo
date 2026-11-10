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

    fun load(context: Context) = load(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** T248: [load] from a store (a test passes an in-memory one, `SettingsRegistryTest`). */
    internal fun load(p: SharedPreferences) {
        prefs = p
        for (s in PLAIN) s.load(p) // T248: every plain setting, before what reads them (the pace fit below)
        migrateBarometer(p) // T250: T161's on/off switch to the three-valued mode
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
        mapOrientation = p.getString(KEY_MAP_ORIENTATION, null) ?: "north-up" // T100
        // T164: the fits the router reads (the fit and its switch are plain settings, loaded above).
        io.github.jasondorjeshort.dystopo.app.routing.PaceSettings.activate()
        // T163
        turnDeadline = p.getString(KEY_TURN_DEADLINE, null)?.let { io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.parse(it)?.value } ?: io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.DEFAULT
        // T172
        trackProfiles = Activity.entries.mapNotNull { a -> p.getString(RecordingProfiles.key(a), null)?.let { v -> RecordingProfiles.parseValue(a, v)?.let { a to it } } }.toMap()
        // T161
        readoutEntries = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.KEYS.mapNotNull { k ->
            val (a, e) = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.ofKey(k)!!
            p.getString(k, null)?.let { v -> io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.parseValue(a, e, v)?.let { k to it } }
        }.toMap()
    }

    // ---- T164: route times fitted to the user's tracks (routing/PaceSettings); all three synced (sync/SettingsSync) ----

    /** "Use the standard estimate": routes keep the standard model even with a fit (off by default: the fit is used once ready). */
    val paceStandard: Boolean get() = PACE_STANDARD.value

    /** The fit as its synced value ([io.github.jasondorjeshort.dystopo.routing.PaceFits.encode]); "" for none yet. */
    val paceFit: String get() = PACE_FIT.value

    /** Reset: only tracks started at or after this count (epoch ms; 0 all tracks). */
    val paceSince: Long get() = PACE_SINCE.value

    // ---- T163: the turn-around clock (nav/TurnAround): what it counts to and the margin; synced (sync/SettingsSync) ----

    private const val KEY_TURN_DEADLINE = "turnDeadline"
    private const val KEY_TURN_TYPED = "turnTypedTime"

    /** `sunset` (the default), `civil` or a typed `HH:MM` ([io.github.jasondorjeshort.dystopo.nav.DeadlineSetting]); never off. */
    @Volatile var turnDeadline: String = io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.DEFAULT
        private set

    /** Minutes to be out before the deadline (default 30, 0 to 240). */
    val turnMarginMin: Int get() = TURN_MARGIN_MIN.value

    /** The last time typed for the deadline (this phone only), so A time I type offers it again after sunset was chosen. */
    val turnTypedTime: String get() = prefs?.getString(KEY_TURN_TYPED, null) ?: "18:00"

    /** The deadline, and a typed time also as [turnTypedTime] (a composite: two stored keys, one synced). */
    fun setTurnDeadline(deadline: String) {
        turnDeadline = deadline
        prefs?.edit()?.apply {
            putString(KEY_TURN_DEADLINE, deadline)
            if (':' in deadline) putString(KEY_TURN_TYPED, deadline)
        }?.apply()
    }

    // ---- T172: the recording activity remembered for the next Record, and one profile value per activity (tracking/ActivityPicker); synced ----

    /** The activity the picker marks and a recording with none given starts with (T161's readout reads it too). */
    val trackActivity: Activity get() = TRACK_ACTIVITY.value

    /** The stored choices per activity ([RecordingProfiles.encode]'s form); an activity missing has its defaults. */
    @Volatile var trackProfiles: Map<Activity, String> = emptyMap()
        private set

    /** Stores [a]'s choices ([value] already checked by [RecordingProfiles.parseValue]; null: back to the defaults). */
    fun setTrackProfile(a: Activity, value: String?) {
        trackProfiles = if (value == null) trackProfiles - a else trackProfiles + (a to value)
        prefs?.edit()?.apply { if (value == null) remove(RecordingProfiles.key(a)) else putString(RecordingProfiles.key(a), value) }?.apply()
    }

    /** [a]'s recording profile: its defaults with the stored choices. */
    fun trackProfile(a: Activity = trackActivity): ActivityProfile = RecordingProfiles.profile(a, trackProfiles[a])

    // ---- T161: the live readout's options per activity and entry, and the barometer switch (readout/ReadoutSettings); synced ----

    /**
     * T250: the barometer's mode (Automatic by default; [io.github.jasondorjeshort.dystopo.readout.BarometerMode]): the
     * readout's elevation and climb use the phone's barometer on foot, bike and boat while it agrees with the GNSS,
     * always, or never.
     */
    val barometerMode: io.github.jasondorjeshort.dystopo.readout.BarometerMode get() = READOUT_BAROMETER.value

    /** The barometer is read at all (any mode but Never): T178's trend and its arrow follow this, as they followed T161's switch. */
    val readoutBarometer: Boolean get() = READOUT_BAROMETER.value != io.github.jasondorjeshort.dystopo.readout.BarometerMode.NEVER

    /**
     * T250 (ADR 0033): a store from before T250 holds T161's switch as a boolean under `readoutBarometer`; on reads as
     * Automatic and off as Never, written under the mode's own key and the old one removed. A store that already has the
     * mode keeps it.
     */
    internal fun migrateBarometer(p: SharedPreferences) {
        val old = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAROMETER
        if (p.contains(READOUT_BAROMETER.storeKey) || !p.contains(old)) return
        val on = runCatching { p.getBoolean(old, true) }.getOrDefault(true)
        val mode = if (on) io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO else io.github.jasondorjeshort.dystopo.readout.BarometerMode.NEVER
        READOUT_BAROMETER.value = mode
        val e = p.edit()
        READOUT_BAROMETER.write(e, mode)
        e.remove(old).apply()
    }

    /** The stored options by [io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.key] (`readout.<activity>.<entry>`); missing ones are the defaults. */
    @Volatile var readoutEntries: Map<String, String> = emptyMap()
        private set

    /** Stores one activity's entry options ([value] already checked by `ReadoutSettings.parseValue`; null: back to the defaults). */
    fun setReadoutEntry(key: String, value: String?) {
        readoutEntries = if (value == null) readoutEntries - key else readoutEntries + (key to value)
        prefs?.edit()?.apply { if (value == null) remove(key) else putString(key, value) }?.apply()
    }

    /** [a]'s readout options (the remembered activity's by default): its defaults with the stored values and the barometer switch. */
    fun readoutOptions(a: Activity = trackActivity): io.github.jasondorjeshort.dystopo.readout.ReadoutOptions =
        io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.options(a, readoutEntries, barometerMode, climbWindowS)

    // ---- T177: the readout bar's switch and the climb window (readout/ReadoutSettings); synced ----

    /** The bar beside the scale bar (default on); off draws the scale bar alone and stops the barometer. */
    val readoutBar: Boolean get() = READOUT_BAR.value

    /** The climb rate's window in seconds: 30, 60 (default), 300 or 600. */
    val climbWindowS: Int get() = CLIMB_WINDOW.value

    // ---- T178: the pressure-fall warning (readout/PressureWatch); synced ----

    const val KEY_PRESSURE_FALL_WARNING = "pressureFallWarning"

    /** The fall in 3 hours that warns: Off, More warnings (3 hPa), Standard (4, default) or Fewer (6). */
    val pressureFallWarning: io.github.jasondorjeshort.dystopo.readout.FallWarning get() = PRESSURE_FALL_WARNING.value

    // ---- T176: the position button's layout, its close zoom and the orientation lock (controls/RecenterButton); synced ----

    /** The position button's layout: 1 (the default, one button), 2 (plus the orientation button), 3 (plus the lock). */
    val mapControls: Int get() = MAP_CONTROLS.value

    /** The zoom a tap on the position button brings the map to (default 14); a tap never zooms out. */
    val recenterZoom: Int get() = RECENTER_ZOOM.value

    /** "Keep north up" (default off): no gesture changes the orientation; only the orientation button and the navigation card do. */
    val orientationLock: Boolean get() = ORIENTATION_LOCK.value

    // ---- T187: water along the route (routing/RouteWater) and its spoken line (nav/NavSession); synced ----

    /** Water along a planned or navigated route on the cards, the map and the navigation card (default on). */
    val waterAlongRoute: Boolean get() = WATER_ALONG_ROUTE.value

    /** While navigating, the last water before a stretch of more than 5 km without any is said once (default off). */
    val waterSpoken: Boolean get() = WATER_SPOKEN.value

    // ---- T182: how far "What can I see from here" looks (viewshed/ViewshedSession); synced ----

    /** The viewshed's range in km: 10, 30 (the default) or 100 (the survey: the apps split, and the phone's cost differs). */
    val viewRangeKm: Int get() = VIEW_RANGE.value

    // ---- T184: the route card's time as a range or one number (routing/PaceSettings); synced ----

    const val KEY_TIME_ESTIMATE_FORM = "timeEstimateForm"
    const val TIME_RANGE = "range"
    const val TIME_ONE = "one"
    val TIME_FORMS = listOf(TIME_RANGE, TIME_ONE)

    /** The route card's time: `range` ("about 4:30" over "4:10 to 4:50", the default) or `one` (the single number). */
    val timeEstimateForm: String get() = TIME_ESTIMATE_FORM.value

    // ---- T194: the spoken line before a long sunny stretch while navigating (nav/NavShade); synced ----

    const val KEY_SHADE_SPOKEN = "shadeSpoken"

    /** `off`, `hot` (on hot days, the default: 25 C at the walker's height, routing/HotDay) or `always`. */
    val shadeSpoken: io.github.jasondorjeshort.dystopo.routing.ShadeSpoken get() = SHADE_SPOKEN.value
    // end T194

    // ---- T203: a position from the terrain while the GPS is poor or gone (tracking/TerrainSource); synced ----

    const val KEY_ESTIMATE_POSITION = "estimatePosition"

    /** "Estimate position when GPS is poor", on by default (ADR 0044: no practice to follow, a cost in battery, a guess that can mislead). */
    val estimatePosition: Boolean get() = ESTIMATE_POSITION.value
    // end T203

    // ---- T213: where the user's tracks leave the mapped trails, on the map (divergence/DivergenceLayer); synced ----

    const val KEY_SHOW_DIVERGENCE = "showDivergence"

    /** "Show trail disagreements", on by default: a layer switch as every overlay has (the survey of 2026-11-02). */
    val showDivergence: Boolean get() = SHOW_DIVERGENCE.value
    // end T213

    // ---- T126: spoken turn directions while navigating (nav/NavSpeech), off by default; synced (sync/SettingsSync) ----

    val navSpeech: Boolean get() = NAV_SPEECH.value

    // ---- T100: the map orientation kept across restarts (controls/MapOrientation): north-up or facing ----

    private const val KEY_MAP_ORIENTATION = "mapOrientation"

    @Volatile var mapOrientation: String = "north-up"
        private set

    fun setMapOrientation(key: String) {
        mapOrientation = key
        prefs?.edit()?.putString(KEY_MAP_ORIENTATION, key)?.apply()
    }

    // ---- T91: navigation thresholds (the T65 constants) ----

    const val DEFAULT_NAV_STALE_S = 30
    const val DEFAULT_NAV_START_M = 2000
    const val DEFAULT_NAV_SNOOZE_MIN = 10
    val NAV_STALE_RANGE = 5..600
    val NAV_START_RANGE = 100..50_000
    val NAV_SNOOZE_RANGE = 1..240

    /** A GPS fix older than this is not used, and with no usable fix for this long the bar says so (seconds). */
    val navStaleS: Int get() = NAV_STALE_S.value
    /** The last known location starts navigation only within this of the line (metres). */
    val navStartM: Int get() = NAV_START_M.value
    /** The banner's Snooze button holds the off-route alert this long (minutes). */
    val navSnoozeMin: Int get() = NAV_SNOOZE_MIN.value

    // ---- T57: routing snap distance, debug server port ----

    const val DEFAULT_ROUTE_SNAP_M = 300
    /** How far a tapped start or end may be from the nearest trail or road, metres. */
    val ROUTE_SNAP_RANGE = 50..1000

    /** T57: the route planner's snap distance (default 300 m, up to 1 km). */
    val routeSnapM: Int get() = ROUTE_SNAP_M.value

    /**
     * T57: debug builds serve the debug routes on a random loopback port announced only in logcat
     * ([DystopoApp.debugPort]); with this set (`POST /settings?debugFixedPort=1`, from the next launch) they
     * stay on the tile server's port 8686 with the `/health` token bootstrap, as before T57.
     */
    val debugFixedPort: Boolean get() = DEBUG_FIXED_PORT.value

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
    val tripCorridorM: Int get() = TRIP_CORRIDOR_M.value
    fun setTripCorridorM(m: Int) = set(TRIP_CORRIDOR_M, m)

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
        "onlineCacheMb" to onlineCacheMb, // T41
        "pinnedCacheMb" to pinnedCacheMb, // T91
        "contourCacheMb" to contourCacheMb,
        "cacheBudgetMb" to cacheBudgetMb, // T112
        "routeSnapRange" to listOf(ROUTE_SNAP_RANGE.first, ROUTE_SNAP_RANGE.last), // T57
        "turnDeadline" to turnDeadline, // T163
        // T172: the remembered activity's profile's four choices, and every activity's (stored value or null for the defaults)
        "trackProfile" to RecordingProfiles.json(trackProfile()),
        "trackProfiles" to Activity.entries.associate { a -> a.code to (RecordingProfiles.json(trackProfile(a)) + mapOf("stored" to trackProfiles[a])) },
        // T161: the remembered activity's readout options per entry, and the stored values
        "readout" to readoutOptions().let { o -> io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.entries.associate { e -> e.code to o[e].encode() } },
        "readoutStored" to readoutEntries,
    ) + PLAIN.associate { it.key to it.jsonValue() } + mapOptions.json(units) // T248: every plain setting; T47

    // ---- T248: the registry of plain settings ----
    //
    // One line per setting whose value is one stored key: its key (the `POST /settings` key, the stored key and, when
    // synced, `app.<key>` in the sync log), its default and its kind. The line alone puts it in `GET /settings`, in
    // `POST /settings` (parsed, refused with a 400 naming the key, stored, published), in [load] and in the sync replica
    // (`SettingsSync.current()`, `publish()` and `pull()`); the app reads `X.value`. What still takes code: a row in
    // `settings/SettingsLayout.kt` and `SettingsPages.kt` (`change(SettingsRoutes.Parsed.Ok.of(X, v))`), a help text,
    // and a line in `SettingsRoutes.apply` only when a change must act at once. Composites keep their own code and are
    // listed by key in `SettingsRoutes.COMPOSITE_KEYS`. A key's stored type never changes (ADR 0033); renaming one is a
    // migration. Declared last: the lines read the ranges above.

    private val registry = ArrayList<Setting<*>>()

    /** Every plain setting, in the order of the lines below. */
    val PLAIN: List<Setting<*>> get() = registry

    /** Sets and stores a plain setting (the caller parsed it; `SettingsRoutes.apply` acts on the change). */
    fun <T : Any> set(s: Setting<T>, v: T) {
        s.value = v
        prefs?.edit()?.also { s.write(it, v) }?.let { if (s.commitNow) it.commit() else it.apply() }
    }

    private fun <T : Any> add(s: Setting<T>): Setting<T> {
        require(registry.none { it.key == s.key }) { "two plain settings named ${s.key}" }
        registry += s
        return s
    }

    /** A switch: `1`/`0` (also `true`, `on`, `yes`) to `POST /settings`, stored as a boolean, synced `on`/`off`. */
    private fun flag(key: String, default: Boolean, synced: Boolean = false, commitNow: Boolean = false) =
        add(Setting(key, default, synced, Setting.Type.FLAG, { Setting.parseFlag(it) }, { "$key must be 1 or 0 (got $it)" }, commitNow = commitNow))

    /** A whole number in [range] (a decimal is rounded), stored as an int and synced as its digits; a stored value outside is brought into it. */
    private fun whole(key: String, default: Int, range: IntRange, unit: String, synced: Boolean = false) =
        add(Setting(key, default, synced, Setting.Type.INT, { Setting.parseWhole(it, range) }, { "$key must be between ${range.first} and ${range.last} $unit (got $it)" }, { it.coerceIn(range) }))

    val ROUTE_SNAP_M = whole("routeSnapM", DEFAULT_ROUTE_SNAP_M, ROUTE_SNAP_RANGE, "m") // T57: the next plan uses it
    val DEBUG_FIXED_PORT = flag("debugFixedPort", false, commitNow = true) // T57: from the next launch (committed: a restart follows)
    val NAV_STALE_S = whole("navStaleS", DEFAULT_NAV_STALE_S, NAV_STALE_RANGE, "s") // T91: from the next navigation start
    val NAV_START_M = whole("navStartM", DEFAULT_NAV_START_M, NAV_START_RANGE, "m")
    val NAV_SNOOZE_MIN = whole("navSnoozeMin", DEFAULT_NAV_SNOOZE_MIN, NAV_SNOOZE_RANGE, "min")
    val NAV_SPEECH = flag("navSpeech", false, synced = true) // T126
    val TRIP_CORRIDOR_M = add(Setting("tripCorridorM", io.github.jasondorjeshort.dystopo.areas.TripCorridor.DEFAULT_WIDTH_M, true, Setting.Type.INT, // T130, T136: whole metres, 0 off
        { io.github.jasondorjeshort.dystopo.app.areas.parseCorridorWidth(it) }, { io.github.jasondorjeshort.dystopo.app.areas.corridorWidthError(it) },
        { it.takeIf { m -> io.github.jasondorjeshort.dystopo.areas.TripCorridor.validWidth(m) } }))
    val PACE_STANDARD = flag("paceStandard", false, synced = true) // T164
    val PACE_FIT = add(Setting("paceFit", "", true, Setting.Type.STRING, // T164: PaceFits.encode's value, "" for none
        { r -> r.toString().trim().takeIf { io.github.jasondorjeshort.dystopo.routing.PaceFits.decode(it) != null } }, { "paceFit is not a fit this build reads (got ${it.toString().trim()})" }))
    val PACE_SINCE = add(Setting("paceSince", 0L, true, Setting.Type.LONG, // T164: epoch ms, 0 every track
        { r -> ((r as? Number)?.toLong() ?: r.toString().trim().toLongOrNull())?.takeIf { it >= 0 } }, { "paceSince must be a time in epoch milliseconds, 0 for every track (got $it)" }))
    val TURN_MARGIN_MIN = whole("turnMarginMin", io.github.jasondorjeshort.dystopo.nav.TurnAround.DEFAULT_MARGIN_MIN, io.github.jasondorjeshort.dystopo.nav.TurnAround.MARGIN_RANGE, "min", synced = true) // T163
    val TRACK_ACTIVITY = add(Setting("trackActivity", Activity.FOOT, true, Setting.Type.code({ it.code }, { Activity.of(it) }), // T172: an activity code
        { Activity.of(it.toString().trim().lowercase()) }, { "trackActivity must be one of ${Activity.entries.map { a -> a.code }} (got $it)" }))
    val READOUT_BAROMETER = add(Setting(io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAROMETER, io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO, true, // T161, T250: the mode
        Setting.Type({ p, k, d -> p.getString(k, null)?.let { io.github.jasondorjeshort.dystopo.readout.BarometerMode.of(it) } ?: d }, { e, k, v -> e.putString(k, v.code) },
            { io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.syncValue(it) }, { it.code }),
        { io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.parseBarometer(it.toString()) },
        { "readoutBarometer must be auto, always or never (1 and 0 read as auto and never) (got $it)" },
        storeKey = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAROMETER_STORED))
    val MAP_CONTROLS = whole("mapControls", 1, io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.LAYOUTS, "buttons", synced = true) // T176
    val RECENTER_ZOOM = whole("recenterZoom", io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.DEFAULT_CLOSE_ZOOM, io.github.jasondorjeshort.dystopo.app.controls.OrientationRules.CLOSE_ZOOM_RANGE, "(zoom level)", synced = true)
    val ORIENTATION_LOCK = flag("orientationLock", false, synced = true)
    val READOUT_BAR = flag(io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.BAR, true, synced = true) // T177
    val CLIMB_WINDOW = add(Setting(io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.CLIMB_WINDOW, io.github.jasondorjeshort.dystopo.readout.ReadoutOptions.DEFAULT_CLIMB_WINDOW_S, true, Setting.Type.INT, // T177: seconds
        { io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.parseClimbWindow(it.toString()) }, { "climbWindow must be 30, 60, 300 or 600 seconds (got $it)" },
        { it.takeIf { s -> s in io.github.jasondorjeshort.dystopo.readout.ReadoutOptions.CLIMB_WINDOWS_S } }))
    val PRESSURE_FALL_WARNING = add(Setting(KEY_PRESSURE_FALL_WARNING, io.github.jasondorjeshort.dystopo.readout.FallWarning.DEFAULT, true, // T178
        Setting.Type.code({ it.code }, { io.github.jasondorjeshort.dystopo.readout.FallWarning.parse(it) }),
        { io.github.jasondorjeshort.dystopo.readout.FallWarning.parse(it.toString()) }, { "$KEY_PRESSURE_FALL_WARNING must be off, more, standard or fewer (or 0, 3, 4, 6) (got $it)" }))
    val WATER_ALONG_ROUTE = flag("waterAlongRoute", true, synced = true) // T187
    val WATER_SPOKEN = flag("waterSpoken", false, synced = true)
    val VIEW_RANGE = add(Setting("viewRange", io.github.jasondorjeshort.dystopo.dem.Viewshed.DEFAULT_RANGE_KM, true, Setting.Type.INT, // T182: km
        { raw -> ((raw as? Number)?.toDouble() ?: raw.toString().trim().removeSuffix("km").trim().toDoubleOrNull())?.takeIf { it.isFinite() && it.toInt().toDouble() == it && it.toInt() in io.github.jasondorjeshort.dystopo.dem.Viewshed.RANGES_KM }?.toInt() },
        { "viewRange must be one of ${io.github.jasondorjeshort.dystopo.dem.Viewshed.RANGES_KM.joinToString(", ")} km (got $it)" },
        { it.takeIf { km -> km in io.github.jasondorjeshort.dystopo.dem.Viewshed.RANGES_KM } }))
    val TIME_ESTIMATE_FORM = add(Setting(KEY_TIME_ESTIMATE_FORM, TIME_RANGE, true, Setting.Type.STRING, // T184
        { v -> v.toString().trim().lowercase().takeIf { it in TIME_FORMS } }, { "$KEY_TIME_ESTIMATE_FORM must be range or one (got $it)" }, { it.takeIf { f -> f in TIME_FORMS } }))
    val SHADE_SPOKEN = add(Setting(KEY_SHADE_SPOKEN, io.github.jasondorjeshort.dystopo.routing.ShadeSpoken.DEFAULT, true, // T194
        Setting.Type.code({ it.key }, { io.github.jasondorjeshort.dystopo.routing.ShadeSpoken.parse(it) }),
        { io.github.jasondorjeshort.dystopo.routing.ShadeSpoken.parse(it.toString()) }, { "$KEY_SHADE_SPOKEN must be off, hot or always (got $it)" }))
    val ESTIMATE_POSITION = flag(KEY_ESTIMATE_POSITION, true, synced = true) // T203
    val SHOW_DIVERGENCE = flag(KEY_SHOW_DIVERGENCE, true, synced = true) // T213
}

/**
 * T248: one plain phone setting in [AppSettings.PLAIN] (see the registry's comment there): its [key], [default], whether
 * it is [synced] as `app.<key>`, its stored [type], how `POST /settings` reads it ([parse], refused with [error]) and the
 * check a stored value passes (else the default). [value] is what the app uses; [AppSettings.set] changes and stores it.
 */
class Setting<T : Any> internal constructor(
    val key: String,
    val default: T,
    val synced: Boolean,
    internal val type: Type<T>,
    private val parser: (Any) -> T?,
    private val refusal: (Any) -> String,
    private val check: (T) -> T? = { it },
    internal val commitNow: Boolean = false,
    /** T250: the preferences key it is stored under, when not [key] (a key whose stored type changed is a new stored key, ADR 0033). */
    internal val storeKey: String = key,
) {
    @Volatile var value: T = default
        internal set

    /** `POST /settings`' reading of [raw] (a query string or a JSON value): the value, or null for one refused with [error]. */
    fun parse(raw: Any): T? = parser(raw)

    /** The 400's sentence for [raw]; it names the key. */
    fun error(raw: Any): String = refusal(raw)

    /** The value in the sync log (`on`/`off` for a switch, a code for a choice). */
    fun syncValue(v: T = value): String = type.sync(v)

    /** The value in `GET /settings` (a boolean, a number, a string or a choice's code). */
    fun jsonValue(v: T = value): Any = type.json(v)

    internal fun load(p: SharedPreferences) { value = check(type.read(p, storeKey, default)) ?: default }

    internal fun write(e: SharedPreferences.Editor, v: T) = type.write(e, storeKey, v)

    override fun toString() = key

    /** How a value is stored (its preferences type, which never changes for a key), synced and reported. */
    class Type<T : Any>(
        internal val read: (SharedPreferences, String, T) -> T,
        internal val write: (SharedPreferences.Editor, String, T) -> Unit,
        internal val sync: (T) -> String = { it.toString() },
        internal val json: (T) -> Any = { it },
    ) {
        companion object {
            val FLAG = Type<Boolean>({ p, k, d -> p.getBoolean(k, d) }, { e, k, v -> e.putBoolean(k, v) }, { if (it) "on" else "off" })
            val INT = Type<Int>({ p, k, d -> p.getInt(k, d) }, { e, k, v -> e.putInt(k, v) })
            val LONG = Type<Long>({ p, k, d -> p.getLong(k, d) }, { e, k, v -> e.putLong(k, v) })
            val STRING = Type<String>({ p, k, d -> p.getString(k, d) ?: d }, { e, k, v -> e.putString(k, v) })

            /** A choice stored, synced and reported as its code; a stored code this build does not read is the default. */
            fun <T : Any> code(code: (T) -> String, of: (String) -> T?) =
                Type<T>({ p, k, d -> p.getString(k, null)?.let(of) ?: d }, { e, k, v -> e.putString(k, code(v)) }, code, code)
        }
    }

    companion object {
        /** `1`, `true`, `on`, `yes` or `0`, `false`, `off`, `no` (any case); null for anything else. */
        fun parseFlag(raw: Any): Boolean? = when (raw.toString().trim().lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> null
        }

        /** A number within [range], rounded to a whole one; null outside it or not a number. */
        fun parseWhole(raw: Any, range: IntRange): Int? {
            val v = (raw as? Number)?.toDouble() ?: raw.toString().trim().toDoubleOrNull()
            if (v == null || !v.isFinite() || v < range.first || v > range.last) return null
            return Math.round(v).toInt()
        }
    }
}
