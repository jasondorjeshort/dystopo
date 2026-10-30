package io.github.jasondorjeshort.dystopo.app

import android.content.Context
import android.location.Location
import io.github.jasondorjeshort.dystopo.store.LocationFix

/**
 * T162: the last location fix the app received, kept across launches in the per-device [OpenView.PREFS] file (never a
 * synced setting), so the map can open at it ([OpenView]).
 *
 * Every place that receives fixes calls [record] with each one, from any thread: the map's own listener and injected
 * fixes (`MapController.onFix`), the track recorder (`tracking/Tracking.onFix`) and navigation's own listener
 * (`nav/NavSession`). One line: `LastFix.record(fix)`.
 *
 * Times are the phone's clock when the fix arrived, not the fix's own time (a replayed GPX keeps its file's times).
 * [Fix.movedAt] advances only when a fix lands more than its accuracy, at least [MOVED_M], from the place the last
 * move was recorded at; the open view compares that time with the saved view's. A move is written at once; a fix at the
 * same place at most every [WRITE_INTERVAL_MS].
 */
object LastFix {
    /** A fix closer than this (or than its own accuracy) to the last moved-to place is the same place. */
    const val MOVED_M = 50.0
    const val WRITE_INTERVAL_MS = 30_000L

    /** [at]: when the last fix arrived; [movedAt]: when the place last changed (both epoch ms, the phone's clock). */
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Double?, val at: Long, val movedAt: Long, val source: String? = null)

    private var last: Fix? = null
    private var loaded = false
    private var writtenAt = 0L
    @Volatile var records = 0L
        private set

    /** Records a fix from the recorder or navigation (a shared-core [LocationFix]). */
    fun record(fix: LocationFix): Fix = record(fix.lat, fix.lon, fix.accuracyM, fix.source)

    /** Records a fix from a platform listener or an injected one. */
    fun record(fix: Location): Fix =
        record(fix.latitude, fix.longitude, if (fix.hasAccuracy()) fix.accuracy.toDouble() else null, fix.provider)

    /** The last fix, from memory or the preferences file; null when the app never had one. */
    @Synchronized
    fun get(): Fix? {
        load()
        return last
    }

    @Synchronized
    fun record(lat: Double, lon: Double, accuracyM: Double?, source: String?, now: Long = System.currentTimeMillis()): Fix {
        load()
        val prev = last
        val moved = prev == null || moved(prev, lat, lon, accuracyM)
        val next = Fix(lat, lon, accuracyM, now, if (moved) now else prev!!.movedAt, source)
        last = next
        records++
        if (moved || now - writtenAt >= WRITE_INTERVAL_MS) {
            writtenAt = now
            runCatching { write(next) }
        }
        return next
    }

    /** Whether a fix at [lat], [lon] with [accuracyM] is a new place relative to [prev]. */
    fun moved(prev: Fix, lat: Double, lon: Double, accuracyM: Double?): Boolean =
        distanceM(prev.lat, prev.lon, lat, lon) > maxOf(MOVED_M, accuracyM ?: 0.0)

    fun json(): Map<String, Any?>? = get()?.let {
        linkedMapOf("lat" to it.lat, "lon" to it.lon, "accuracy" to it.accuracyM, "source" to it.source, "at" to it.at, "movedAt" to it.movedAt)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        last = runCatching {
            val p = prefs()
            val at = p.getLong("fix.at", 0L)
            if (at <= 0) null else Fix(
                p.getString("fix.lat", null)!!.toDouble(), p.getString("fix.lon", null)!!.toDouble(),
                p.getString("fix.accuracy", null)?.toDoubleOrNull(), at, p.getLong("fix.movedAt", at), p.getString("fix.source", null),
            )
        }.getOrNull()
    }

    private fun write(f: Fix) {
        prefs().edit()
            .putString("fix.lat", f.lat.toString()).putString("fix.lon", f.lon.toString())
            .putString("fix.accuracy", f.accuracyM?.toString()).putString("fix.source", f.source)
            .putLong("fix.at", f.at).putLong("fix.movedAt", f.movedAt)
            .apply()
    }

    private fun prefs() = DystopoApp.instance.getSharedPreferences(OpenView.PREFS, Context.MODE_PRIVATE)

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_008.8
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}
