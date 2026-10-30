package io.github.jasondorjeshort.dystopo.app.controls

import android.Manifest
import android.content.pm.PackageManager
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.nav.NavSession

/**
 * T93, T100, T176: the position button's logic, shared by the buttons ([RecenterButton]), the navigation card's
 * Recenter and `POST /camera/recenter` ([ControlRoutes]). The tap rule is [OrientationRules.tap]: off centre a tap
 * recentres on the fix and keeps the orientation, zooming in to the close zoom ([AppSettings.recenterZoom]) when the map
 * is farther out; centred but farther out it zooms in; centred and close it advances the orientation on the one-button
 * layout (north-up to facing when there is a compass, facing and free rotate to north-up) unless the lock is on, and
 * does nothing on two or three buttons. A tap never zooms out, and navigation keeps its own zoom (a tap from the
 * navigation card, or any tap while navigating). Either way the camera then follows the fix
 * ([MapOrientation.following]), which is navigation's follow-me too, so the two never fight. Without the location
 * permission a tap asks for it; without a fix it does nothing (the button looks disabled).
 *
 * T176: [orientationButton] (two and three buttons) advances the orientation; [toggleLock] ("Keep north up", the third
 * button, or a long press on one or two buttons, [longPress]) flips [AppSettings.orientationLock] through the settings,
 * so it syncs.
 *
 * T93's seam `Recenter.Orientation` became the app's one orientation state, [MapOrientation.orientation].
 */
object Recenter {
    /** T176: the toasts of a long press (and of the lock button). */
    const val LOCKED_TOAST = "Orientation locked"
    const val UNLOCKED_TOAST = "Orientation unlocked"

    /** What a tap would do now: `recenter`, `noFix` (disabled look) or `noPermission` (a tap asks for it). */
    fun state(app: DystopoApp = DystopoApp.instance): String = when {
        app.map.lastLocation != null -> "recenter"
        !permitted(app) -> "noPermission"
        else -> "noFix"
    }

    fun permitted(app: DystopoApp): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Volatile var taps = 0L
        private set
    @Volatile var lastTap: Map<String, Any?>? = null
        private set
    @Volatile var longPresses = 0L
        private set
    @Volatile var lastButton: Map<String, Any?>? = null
        private set

    /** T176: the buttons the rule reads: those drawn (the room may drop some, [RecenterLayout.shownButtons]), else the setting. */
    fun layout(): Int = RecenterLayout.shownButtons.takeIf { RecenterLayout.shown && it > 0 } ?: AppSettings.mapControls

    /** T176: the close zoom of a tap from [source], or null where navigation keeps its own zoom. */
    fun closeZoom(source: String): Double? =
        if (source == "navCard" || NavSession.active) null else AppSettings.recenterZoom.toDouble()

    /**
     * One tap; [done] runs on the main thread once the camera is there. Returns what happened: `result`
     * `recentered` (with `action` `recenter`, `zoom`, `advance` or `none`, the orientation before and after, whether the
     * map was centred, the zoom before, its target and, once there, `zoomAfter`) or `noFix` / `noPermission`;
     * `navFollowOn` when navigation's follow came back on. The caller asks for the permission on `noPermission` (the
     * button through its launcher; the route only reports it).
     */
    fun tap(app: DystopoApp = DystopoApp.instance, animateMs: Int = 300, source: String, done: () -> Unit = {}): Map<String, Any?> {
        taps++
        val fix = app.map.lastLocation
        if (fix == null) {
            val r = linkedMapOf<String, Any?>("source" to source, "result" to state(app), "orientation" to MapOrientation.orientation.key)
            lastTap = r
            done()
            return r
        }
        val result = java.util.Collections.synchronizedMap(LinkedHashMap<String, Any?>())
        result["source"] = source
        result["result"] = "recentered"
        result["fix"] = mapOf("lat" to fix.latitude, "lon" to fix.longitude, "accuracy" to fix.accuracy)
        val t = MapOrientation.tap(animateMs, closeZoom(source), layout()) {
            // The camera's zoom once there (main thread), so `lastTap` holds the zoom before and after.
            result["zoomAfter"] = app.map.map?.cameraPosition?.zoom?.let { Math.round(it * 100) / 100.0 }
            done()
        }
        // Navigation's follow-me (stopped by a pan) is the same following: it came back on with this tap.
        result["navFollowOn"] = NavSession.active && t["followingBefore"] == false
        for ((k, v) in t) result.putIfAbsent(k, v)
        lastTap = result
        return result
    }

    /** T176: the orientation button (two and three buttons). */
    fun orientationButton(source: String): Map<String, Any?> {
        val r = linkedMapOf<String, Any?>("button" to "orientation", "source" to source) + MapOrientation.advance("orientationButton")
        lastButton = r
        return r
    }

    /**
     * T176: flips "Keep north up" through the settings (validated, synced, applied; call off the main thread). Returns
     * the lock after it and the toast's text.
     */
    fun toggleLock(app: DystopoApp, source: String): Map<String, Any?> {
        val on = !AppSettings.orientationLock
        SettingsRoutes(app).apply(SettingsRoutes.Parsed.Ok(null, false, null, null, orientationLock = on))
        app.map.onMain { SettingsUi.revision++ }
        val r = linkedMapOf<String, Any?>("button" to "lock", "source" to source, "lock" to AppSettings.orientationLock, "toast" to if (on) LOCKED_TOAST else UNLOCKED_TOAST)
        lastButton = r
        return r
    }

    /**
     * T176: a long press on the position button (and on the orientation button of two): toggles the lock on one or two
     * buttons ([longPressLocks]); on three, where the lock has its own button, it does nothing.
     */
    fun longPress(app: DystopoApp, source: String): Map<String, Any?> {
        longPresses++
        val n = layout()
        if (!longPressLocks(n)) {
            val r = linkedMapOf<String, Any?>("button" to "longPress", "source" to source, "result" to "ignored", "layout" to n, "lock" to AppSettings.orientationLock)
            lastButton = r
            return r
        }
        val r = toggleLock(app, source) + mapOf("button" to "longPress", "layout" to n)
        lastButton = r
        return r
    }

    /** T176: a long press toggles the lock on the one- and two-button layouts only. */
    fun longPressLocks(layout: Int): Boolean = layout in 1..2

    fun json(app: DystopoApp = DystopoApp.instance): Map<String, Any?> = linkedMapOf(
        "state" to state(app),
        "enabled" to (app.map.lastLocation != null),
        "orientation" to MapOrientation.orientation.key,
        // T176: the layout as set and as drawn, the lock, the close zoom.
        "layout" to AppSettings.mapControls,
        "shownButtons" to if (RecenterLayout.shown) RecenterLayout.shownButtons else 0,
        "lock" to AppSettings.orientationLock,
        "closeZoom" to AppSettings.recenterZoom,
        "taps" to taps,
        "recenters" to MapOrientation.recenters,
        "lastTap" to lastTap?.let { synchronized(it) { LinkedHashMap(it) } },
        "longPresses" to longPresses,
        "lastButton" to lastButton,
        "button" to RecenterLayout.json(),
    )
}
