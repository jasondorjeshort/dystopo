package io.github.jasondorjeshort.dystopo.app.sync

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.sync.SyncStore

/**
 * App settings through the synced log (T51). The user's choices of units, text scale and map options
 * ([MapOptions.KEYS]) are written as setting operations (`app.units`, `app.textScale`,
 * `app.map.<key>`), so every device converges on the newest choice per setting (last writer wins per
 * key, docs/PLANNING.md › Sync). Device-local settings (server, warm-up, cache caps, decoder, the sync
 * peer) stay local, and so do units seeded from a region: only a choice the user made is published.
 *
 * - [publish] runs after [SettingsRoutes.apply] (`POST /settings` and the settings sheet) and writes the
 *   keys that request set, with their values after the change.
 * - [pull] runs after a sync merged something: synced values that differ from the current settings are
 *   applied through the same [SettingsRoutes.apply] (validated by [SettingsRoutes.parse]; a value this
 *   build does not accept is skipped and reported), without publishing them again.
 */
object SettingsSync {
    const val PREFIX = "app."
    private const val MAP = "map."
    private const val TAG = "DystopoSync"

    @Volatile private var applyingRemote = false

    /** The last [pull]: applied keys, skipped values, when. For `/sync/status`. */
    @Volatile var lastPull: Map<String, Any?>? = null
        private set

    @Volatile var published = 0L
        private set

    private fun syncedKey(settingsKey: String) = if (settingsKey in MapOptions.KEYS) PREFIX + MAP + settingsKey else PREFIX + settingsKey

    private fun settingsKey(syncedKey: String) = syncedKey.removePrefix(PREFIX).removePrefix(MAP)

    /** The synced settings as they are now, by synced key; null for units the user has not chosen. */
    fun current(): Map<String, String?> {
        val m = LinkedHashMap<String, String?>()
        m[syncedKey("units")] = AppSettings.unitPreference.units?.takeIf { AppSettings.unitPreference.source == "user" }?.symbol
        m[syncedKey("textScale")] = AppSettings.textScale.toString()
        val map = AppSettings.mapOptions.json(AppSettings.units)
        for (k in MapOptions.KEYS) m[syncedKey(k)] = map[k]?.toString()
        return m
    }

    /** One line in [SettingsRoutes.apply]: writes the settings [parsed] set to the log. */
    fun publish(app: DystopoApp, parsed: SettingsRoutes.Parsed.Ok) {
        if (applyingRemote) return
        val keys = LinkedHashSet<String>()
        if (parsed.textScale != null) keys += syncedKey("textScale")
        if (parsed.unitsGiven) {
            keys += syncedKey("units")
            keys += syncedKey("contourInterval") // a units change can reset a fixed interval to auto
        }
        parsed.map.keys.forEach { keys += syncedKey(it) }
        if (keys.isEmpty()) return
        val values = current()
        val now = System.currentTimeMillis()
        val t = Tracking.of(app)
        try {
            t.call {
                val s = t.store as SyncStore
                val held = s.settings()
                for (k in keys) if (held[k] != values[k]) { s.setSetting(k, values[k], now); published++ }
            }
        } catch (e: Exception) {
            Log.w(TAG, "publishing settings failed", e)
        }
    }

    /** After a merge: applies synced settings that differ from the current ones. Returns what changed. */
    fun pull(app: DystopoApp): Map<String, Any?> {
        val t = Tracking.of(app)
        val synced = t.call { (t.store as SyncStore).settings() }.filterKeys { it.startsWith(PREFIX) }
        val current = current()
        val params = LinkedHashMap<String, Any?>()
        val skipped = ArrayList<String>()
        for ((k, v) in synced) {
            if (current[k] == v) continue
            val key = settingsKey(k)
            if (key !in SettingsRoutes.KEYS) { skipped += k; continue }
            params[key] = v
        }
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
        val result = linkedMapOf<String, Any?>("at" to System.currentTimeMillis(), "applied" to params, "skipped" to skipped)
        if (params.isNotEmpty() && parsed is SettingsRoutes.Parsed.Ok) {
            applyingRemote = true
            try {
                SettingsRoutes(app).apply(parsed)
            } finally {
                applyingRemote = false
            }
            app.map.onMain { SettingsUi.revision++ }
        }
        lastPull = result
        return result
    }

    fun json(): Map<String, Any?> = linkedMapOf("published" to published, "lastPull" to lastPull, "synced" to current())
}
