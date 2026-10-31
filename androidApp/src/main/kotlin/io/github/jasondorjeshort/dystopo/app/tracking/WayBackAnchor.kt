package io.github.jasondorjeshort.dystopo.app.tracking

import android.content.Context
import io.github.jasondorjeshort.dystopo.app.DystopoApp

/**
 * T180: "The way back starts here" for a recording, kept per device in its own preferences file ([PREFS], like
 * `LastFix`), keyed by the track id; never a synced setting, never in the track's schema or its sync ops (ADR 0033's
 * cost for a per-trip choice the other phone has no use for). Without one, the way back starts where the speed rule or
 * the recording's first good fix puts it (`nav/TurnAround.kt` › `WayBack`). The file holds the recording track's
 * override only: setting one for a track forgets the others.
 *
 * The override is stored as the fix time of the point it was set at (the recorder's last point then), so the way back
 * re-finds it in the track after a restart: the first point at or after that time.
 */
object WayBackAnchor {
    const val PREFS = "dystopo-wayback"

    /** An override: the point's fix time and place, and when it was set (the phone's clock). */
    data class Here(val trackId: String, val pointTimeMs: Long, val lat: Double, val lon: Double, val setAtMs: Long)

    private fun prefs() = DystopoApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun get(trackId: String): Here? = runCatching {
        val p = prefs()
        val t = p.getLong("$trackId.time", Long.MIN_VALUE)
        if (t == Long.MIN_VALUE) null else Here(
            trackId, t, p.getString("$trackId.lat", null)!!.toDouble(), p.getString("$trackId.lon", null)!!.toDouble(), p.getLong("$trackId.setAt", 0L),
        )
    }.getOrNull()

    @Synchronized
    fun set(h: Here) {
        prefs().edit().clear()
            .putLong("${h.trackId}.time", h.pointTimeMs)
            .putString("${h.trackId}.lat", h.lat.toString())
            .putString("${h.trackId}.lon", h.lon.toString())
            .putLong("${h.trackId}.setAt", h.setAtMs)
            .apply()
    }

    /** Back to the rule (`POST /tracks/wayback?reset=1`). */
    @Synchronized
    fun clear(trackId: String) {
        val p = prefs()
        if (p.contains("$trackId.time")) p.edit().remove("$trackId.time").remove("$trackId.lat").remove("$trackId.lon").remove("$trackId.setAt").apply()
    }
}
