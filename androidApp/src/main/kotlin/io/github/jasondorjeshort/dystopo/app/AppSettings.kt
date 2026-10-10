package io.github.jasondorjeshort.dystopo.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle

/**
 * User settings that shape the map style, persisted in app preferences. [load] runs once in
 * [DystopoApp.onCreate]; until then the defaults apply. Reads are lock-free; whoever changes a value
 * rebuilds the style ([SettingsRoutes] reloads it).
 */
object AppSettings {
    private const val PREFS = "dystopo-settings"
    private const val KEY_TEXT_SCALE = "textScale"
    private const val KEY_SERVER = "server"

    /** The server a debug build on the emulator uses until one is set: `devtool serve --live` on the host (10.0.2.2). */
    const val EMULATOR_DEBUG_SERVER = "http://10.0.2.2:8765"

    @Volatile private var prefs: SharedPreferences? = null

    /** Multiplier on every label and marker size (1.0 = default), clamped by [MapLibreStyle.clampTextScale]. */
    @Volatile var textScale: Double = 1.0
        private set

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // Stored as a Float; round so 1.6 reads back as 1.6, not 1.600000023841858.
        textScale = MapLibreStyle.clampTextScale(Math.round(p.getFloat(KEY_TEXT_SCALE, 1.0f) * 1000.0) / 1000.0)
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

    /** Sets and persists the text scale; returns the clamped value. */
    fun setTextScale(value: Double): Double {
        val v = MapLibreStyle.clampTextScale(value)
        textScale = v
        prefs?.edit()?.putFloat(KEY_TEXT_SCALE, v.toFloat())?.apply()
        return v
    }

    fun json(): Map<String, Any?> = mapOf(
        "textScale" to textScale,
        "textScaleRange" to listOf(MapLibreStyle.MIN_TEXT_SCALE, MapLibreStyle.MAX_TEXT_SCALE),
        "baseTextScale" to MapLibreStyle.BASE_TEXT_SCALE,
        "server" to server,
    )
}
