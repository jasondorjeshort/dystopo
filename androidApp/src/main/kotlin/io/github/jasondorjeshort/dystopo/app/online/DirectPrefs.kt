package io.github.jasondorjeshort.dystopo.app.online

import android.content.Context

/**
 * T80: the phone's own source reading ([DirectSources]): on unless turned off, and its per-session download
 * budget, and the deepest DEM zoom it renders (deeper ones are synthesized). Kept in their own preferences file (not synced: what one phone may download is that phone's business).
 * Debug route: `POST /firstrun/direct?on=0|1&sessionMb=&maxZoom=` ([io.github.jasondorjeshort.dystopo.app.firstrun.FirstRunRoutes]).
 */
object DirectPrefs {
    private const val FILE = "direct-sources"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", true)

    fun sessionMb(c: Context): Long = prefs(c).getLong("sessionMb", DirectSources.DEFAULT_SESSION_MB)

    /** Whether the phone may read the sources over mobile data (default off: a fresh view is 75 to 100 MB). */
    fun onMobileData(c: Context): Boolean = prefs(c).getBoolean("onMobileData", false)

    fun setOnMobileData(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("onMobileData", on).apply()
    }

    /** Per-session download budget choices the Settings sheet offers (any 1..100000 through `POST /settings`). */
    val SESSION_MB_CHOICES = listOf(100L, 300L, 1000L)
    val SESSION_MB_RANGE = 1L..100_000L

    /** The two user settings, as `GET /settings` shows them. */
    fun json(c: Context): Map<String, Any?> = linkedMapOf("directSessionMb" to sessionMb(c), "directOnMobileData" to onMobileData(c))

    fun maxZoom(c: Context): Int = prefs(c).getInt("maxZoom", DirectSources.DEFAULT_MAX_ZOOM)

    fun set(c: Context, enabled: Boolean? = null, sessionMb: Long? = null, maxZoom: Int? = null) {
        prefs(c).edit().apply {
            maxZoom?.let { putInt("maxZoom", it) }
            enabled?.let { putBoolean("enabled", it) }
            sessionMb?.let { putLong("sessionMb", it) }
        }.apply()
    }
}
