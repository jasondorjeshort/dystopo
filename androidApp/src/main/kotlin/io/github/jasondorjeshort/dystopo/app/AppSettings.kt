package io.github.jasondorjeshort.dystopo.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
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

    @Volatile private var prefs: SharedPreferences? = null

    /** Multiplier on every label and marker size (1.0 = default), within [MapLibreStyle.MIN_TEXT_SCALE]..[MapLibreStyle.MAX_TEXT_SCALE]. */
    @Volatile var textScale: Double = 1.0
        private set

    @Volatile var unitPreference: UnitPreference = UnitPreference()
        private set

    @Volatile var warmup: Boolean = true
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
        server = p.getString(KEY_SERVER, null) ?: defaultServer()
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
    )
}
