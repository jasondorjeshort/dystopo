package io.github.jasondorjeshort.dystopo.app

import android.content.Context
import android.content.SharedPreferences
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle

/**
 * User settings that shape the map style, persisted in app preferences. [load] runs once in
 * [DystopoApp.onCreate]; until then the defaults apply. Reads are lock-free; whoever changes a value
 * rebuilds the style ([SettingsRoutes] reloads it).
 */
object AppSettings {
    private const val PREFS = "dystopo-settings"
    private const val KEY_TEXT_SCALE = "textScale"

    @Volatile private var prefs: SharedPreferences? = null

    /** Multiplier on every label and marker size (1.0 = default), clamped by [MapLibreStyle.clampTextScale]. */
    @Volatile var textScale: Double = 1.0
        private set

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // Stored as a Float; round so 1.6 reads back as 1.6, not 1.600000023841858.
        textScale = MapLibreStyle.clampTextScale(Math.round(p.getFloat(KEY_TEXT_SCALE, 1.0f) * 1000.0) / 1000.0)
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
    )
}
