package io.github.jasondorjeshort.dystopo.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * T49: the map's visible box, updated whenever the camera comes to rest ([MapController] calls [update]). Null before
 * the first camera idle.
 *
 * T162: two copies of the one box. [bounds] is Compose state, so the credit line recomposes for the packs in view; it is
 * read in composition only (`AttributionLine`). [current] is a plain volatile copy for every reader off the main
 * thread (`DirectSources`, `CacheBudget`, `AppCredits.mapLine` for `GET /attribution`), which never touch the Compose
 * state. The Compose state is made by [prepare] on the main thread before the first composition (`MainActivity`), as
 * `OrientationUi.prepare()` and `Areas.prepare()` do. Before T162 the state was made by whoever touched this object
 * first: with the caches over their budget at launch that was `CacheBudget`'s thread (the eviction pass reads the view),
 * in the global snapshot; when that landed while the first composition ran, the composition's snapshot, taken earlier,
 * could not see the new state, and `AttributionLine` failed with "Reading a state that was created after the snapshot
 * was taken" (finding `2026-10-28-open-view-ignores-the-location-fix.md`).
 */
object MapViewport {
    /** The box for readers off the main thread (and anyone outside composition). */
    @Volatile var current: PackBounds? = null
        private set

    private var state: MutableState<PackBounds?>? = null // main thread only

    /** Makes the Compose state on the main thread, outside any composition. Idempotent. */
    fun prepare() {
        if (state == null) state = mutableStateOf(current)
    }

    /** The box as Compose state: read in composition only (main thread). */
    val bounds: PackBounds?
        get() = (state ?: mutableStateOf(current).also { state = it }).value // prepare() normally ran; this is the fallback on the main thread

    /** Main thread (the camera-idle listener). */
    fun update(m: MapLibreMap) {
        val b = m.projection.visibleRegion.latLngBounds
        set(PackBounds(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth))
    }

    /** Main thread: publishes [next] to both copies. */
    internal fun set(next: PackBounds) {
        if (next == current) return
        current = next
        prepare()
        state?.value = next
    }

    /** Whether boxes [a] and [b] overlap (degrees; touching edges do not count). */
    fun overlaps(a: PackBounds, b: PackBounds): Boolean =
        a.maxLon > b.minLon && a.minLon < b.maxLon && a.maxLat > b.minLat && a.minLat < b.maxLat
}

/** T162: a camera position the map can open at, and when it was set (epoch ms; 0 for never). */
data class ViewAt(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double, val at: Long)

/**
 * T162: the open view, the owner's rule of 2026-10-28: the map opens at the most recent of the last location fix
 * ([LastFix]), the last saved view ([SavedView], where the camera last came to rest after a move the app did not make
 * itself) and the primary pack's view (its box fitted to the screen, set when the pack was activated, `PackManager`);
 * a `dystopo://map` link still comes first, and an activity recreated with its saved state keeps the camera MapLibre
 * restores. Pure (no Android), so the rule is unit-tested.
 *
 * The fix's time is when it last moved ([LastFix.Fix.movedAt]: more than its accuracy, at least
 * [LastFix.MOVED_M], from where it was), not when it last arrived: a phone lying still keeps reporting the same place
 * every second, and that should not beat a view the user panned to after it.
 *
 * After open a fix the map receives within [FIX_WINDOW_MS] of open, before any gesture or other camera move, recentres
 * the camera once when it is newer than the open view (it moved since that view was set). When the open view is older
 * than [STALE_VIEW_MS] (or was never set: the pack view of a pack never activated), any fix counts, moved or not, and the
 * window is [STALE_FIX_WINDOW_MS]: after a week the phone is likely somewhere else, and a GPS cold start can take longer
 * than ten seconds.
 */
object OpenView {
    /** The per-device preferences file of the saved view, the last fix and the pack's activation; never synced. */
    const val PREFS = "device-view"

    const val FIX_WINDOW_MS = 10_000L
    const val STALE_FIX_WINDOW_MS = 60_000L
    const val STALE_VIEW_MS = 7L * 24 * 3600 * 1000

    /** The zoom when the fix wins and no saved view gives one. */
    const val FIX_ZOOM = 14.0

    enum class Source(val json: String) { DEEP_LINK("deepLink"), RESTORED("restored"), FIX("fix"), SAVED("saved"), PACK("pack"), WORLD("world") }

    /** What the map opened at: [view] is null for [Source.WORLD] (no pack, no fix, no saved view) and [Source.RESTORED]. */
    class Choice(val source: Source, val view: ViewAt?)

    /**
     * The newest of [fix] (at its [LastFix.Fix.movedAt]), [saved] and [pack]; ties go to the fix, then the saved view.
     * The fix opens at the saved view's zoom and bearing when there is one, else [FIX_ZOOM] north up.
     */
    fun choose(fix: LastFix.Fix?, saved: ViewAt?, pack: ViewAt?): Choice {
        val fixView = fix?.let { ViewAt(it.lat, it.lon, saved?.zoom ?: FIX_ZOOM, saved?.bearing ?: 0.0, it.movedAt) }
        val best = listOfNotNull(
            fixView?.let { Source.FIX to it },
            saved?.let { Source.SAVED to it },
            pack?.let { Source.PACK to it },
        ).maxByOrNull { it.second.at } // the first of equal maxima wins
        return if (best == null) Choice(Source.WORLD, null) else Choice(best.first, best.second)
    }

    /** Whether the open view [opened] (set at [ViewAt.at]) is stale at [now]. */
    fun stale(opened: ViewAt?, now: Long): Boolean = opened == null || now - opened.at > STALE_VIEW_MS

    /** How long after open a fix may still recentre the camera. */
    fun windowMs(opened: ViewAt?, now: Long): Long = if (stale(opened, now)) STALE_FIX_WINDOW_MS else FIX_WINDOW_MS

    /**
     * Whether a fix arriving [sinceOpenMs] after open, before any gesture, recentres the camera: within the window, and
     * newer than the open view (it moved after the view was set) unless that view is stale.
     */
    fun recentres(opened: ViewAt?, fix: LastFix.Fix, sinceOpenMs: Long, now: Long): Boolean =
        sinceOpenMs <= windowMs(opened, now) && (stale(opened, now) || fix.movedAt > opened!!.at)
}

/**
 * T162: the saved view, where the camera last came to rest (`MapController`'s camera-idle listener), with the time, in
 * the per-device [OpenView.PREFS] file. Written at most once per [MIN_INTERVAL_MS] (the last rest of a burst is written
 * when the interval is up). Main thread.
 */
object SavedView {
    const val MIN_INTERVAL_MS = 2_000L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastWriteAt = 0L
    private var pending: ViewAt? = null
    @Volatile var writes = 0L
        private set

    private fun prefs() = DystopoApp.instance.getSharedPreferences(OpenView.PREFS, android.content.Context.MODE_PRIVATE)

    fun get(): ViewAt? = runCatching {
        val p = prefs()
        val at = p.getLong("view.at", 0L).takeIf { it > 0 } ?: return null
        ViewAt(p.getString("view.lat", null)!!.toDouble(), p.getString("view.lon", null)!!.toDouble(),
            p.getString("view.zoom", null)!!.toDouble(), p.getString("view.bearing", "0")!!.toDouble(), at)
    }.getOrNull()

    fun save(v: ViewAt) {
        val now = android.os.SystemClock.elapsedRealtime()
        val wait = lastWriteAt + MIN_INTERVAL_MS - now
        if (wait <= 0) {
            write(v)
            lastWriteAt = now
            return
        }
        val scheduled = pending != null
        pending = v
        if (!scheduled) main.postDelayed({
            pending?.let { write(it) }
            pending = null
            lastWriteAt = android.os.SystemClock.elapsedRealtime()
        }, wait)
    }

    /** Writes a throttled view now (the activity stops). */
    fun flush() {
        pending?.let { write(it) }
        pending = null
    }

    private fun write(v: ViewAt) {
        prefs().edit()
            .putString("view.lat", v.lat.toString()).putString("view.lon", v.lon.toString())
            .putString("view.zoom", v.zoom.toString()).putString("view.bearing", v.bearing.toString())
            .putLong("view.at", v.at)
            .apply()
        writes++
    }
}

/** T162: when the primary pack was last activated with its view shown (`PackManager`), in [OpenView.PREFS]. */
object PackView {
    private fun prefs() = DystopoApp.instance.getSharedPreferences(OpenView.PREFS, android.content.Context.MODE_PRIVATE)

    fun activated(name: String, at: Long = System.currentTimeMillis()) {
        runCatching { prefs().edit().putString("pack.name", name).putLong("pack.at", at).apply() }
    }

    /** When [name] was activated, or 0 when it never was (a pack found on the device, or one that became primary unseen). */
    fun activatedAt(name: String?): Long = runCatching {
        val p = prefs()
        if (name != null && p.getString("pack.name", null) == name) p.getLong("pack.at", 0L) else 0L
    }.getOrDefault(0L)
}
