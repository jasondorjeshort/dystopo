package io.github.jasondorjeshort.dystopo.app.controls

import android.Manifest
import android.content.pm.PackageManager
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.nav.NavSession

/**
 * T93, T100: the recentre button's logic, shared by the button ([RecenterButton]), the navigation card's Recenter
 * and `POST /camera/recenter` ([ControlRoutes]). The tap rule is the owner's ([OrientationRules.tap]): off centre a
 * tap recentres on the fix and keeps the orientation; on a centred map it advances the orientation (north-up to
 * facing when there is a compass, facing and free rotate to north-up). Either way the camera then follows the fix
 * ([MapOrientation.following]), which is navigation's follow-me too, so the two never fight. Without the location
 * permission a tap asks for it; without a fix it does nothing (the button looks disabled).
 *
 * T93's seam `Recenter.Orientation` became the app's one orientation state, [MapOrientation.orientation].
 */
object Recenter {
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
     * One tap; [done] runs on the main thread once the camera is there. Returns what happened: `result`
     * `recentered` (with `action` `recenter` or `advance`, the orientation before and after, whether the map was
     * centred) or `noFix` / `noPermission`; `navFollowOn` when navigation's follow came back on. The caller asks
     * for the permission on `noPermission` (the button through its launcher; the route only reports it).
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
        val t = MapOrientation.tap(animateMs, done)
        val result = linkedMapOf<String, Any?>(
            "source" to source,
            "result" to "recentered",
            "fix" to mapOf("lat" to fix.latitude, "lon" to fix.longitude, "accuracy" to fix.accuracy),
            // Navigation's follow-me (stopped by a pan) is the same following: it came back on with this tap.
            "navFollowOn" to (NavSession.active && t["followingBefore"] == false),
        ) + t
        lastTap = result
        return result
    }

    fun json(app: DystopoApp = DystopoApp.instance): Map<String, Any?> = linkedMapOf(
        "state" to state(app),
        "enabled" to (app.map.lastLocation != null),
        "orientation" to MapOrientation.orientation.key,
        "taps" to taps,
        "recenters" to MapOrientation.recenters,
        "lastTap" to lastTap,
        "button" to RecenterLayout.json(),
    )
}
