package io.github.jasondorjeshort.dystopo.app.controls

import android.Manifest
import android.content.pm.PackageManager
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import io.github.jasondorjeshort.dystopo.app.nav.NavSession

/**
 * T93: the recentre button's logic, shared by the button ([RecenterButton]) and `POST /camera/recenter`
 * ([ControlRoutes]). A tap centres the map on the current fix at the current zoom and leaves the orientation
 * alone; while navigating it also turns navigation's follow-me back on (the stats bar's Recenter does the
 * same), so the two never fight. Without the location permission a tap asks for it; without a fix it does
 * nothing (the button looks disabled).
 *
 * The seam for the later three-state button (docs/ideas/2026-10-13-recenter-button.md): [orientation] is
 * what the button shows and what a second tap on a centred map will cycle. It is fixed to north-up here.
 */
object Recenter {
    /** The map orientation the button shows. Only [NORTH_UP] exists yet; free rotate and facing come later. */
    enum class Orientation { NORTH_UP }

    @Volatile var orientation = Orientation.NORTH_UP
        private set

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

    /**
     * Recentres on the fix; [done] runs on the main thread once the camera is there. Returns what happened
     * (`recentered`, `noFix`), the fix, and whether navigation's follow was turned back on. The caller asks
     * for the permission on `noPermission` (the button through its launcher; the route only reports it).
     */
    fun tap(app: DystopoApp = DystopoApp.instance, animateMs: Int = 300, source: String, done: () -> Unit = {}): Map<String, Any?> {
        taps++
        val fix = app.map.recenter(animateMs, done)
        // Navigation's follow-me (stopped by a pan) comes back on: the camera then stays on each new fix.
        val followOn = fix != null && NavSession.active && app.map.cameraMode == CameraMode.FREE
        if (followOn) NavSession.setCamera(CameraMode.FOLLOW)
        val result = linkedMapOf<String, Any?>(
            "source" to source,
            "result" to if (fix != null) "recentered" else state(app),
            "fix" to fix?.let { mapOf("lat" to it.latitude, "lon" to it.longitude, "accuracy" to it.accuracy) },
            "navFollowOn" to followOn,
            "orientation" to orientation.name,
        )
        lastTap = result
        if (fix == null) done()
        return result
    }

    fun json(app: DystopoApp = DystopoApp.instance): Map<String, Any?> = linkedMapOf(
        "state" to state(app),
        "enabled" to (app.map.lastLocation != null),
        "orientation" to orientation.name,
        "taps" to taps,
        "recenters" to app.map.recenters,
        "lastTap" to lastTap,
        "button" to RecenterLayout.json(),
    )
}
