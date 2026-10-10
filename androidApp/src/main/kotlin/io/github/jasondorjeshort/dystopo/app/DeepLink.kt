package io.github.jasondorjeshort.dystopo.app

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * T55: opens the map at a place from a link, in every build type (release has no debug `/camera`):
 *
 *     adb shell am start -W -a android.intent.action.VIEW -d 'dystopo://map?lat=46.853&lon=-121.76&z=12'
 *
 * `lat` and `lon` are required (degrees), `z` (or `zoom`, default 12) and `bearing` are optional. The same
 * keys work as intent extras (doubles or strings) on any intent that reaches [MainActivity]. A link on the
 * launch intent becomes the start camera ([camera]); one that arrives while the map is open (`am start`
 * with `--activity-single-top`) moves it ([install]). Anything malformed or out of range is ignored.
 */
object DeepLink {
    const val SCHEME = "dystopo"
    const val HOST = "map"
    const val DEFAULT_ZOOM = 12.0

    /** The camera a link or the intent's extras ask for, or null. */
    fun camera(intent: Intent?): CameraPosition? {
        intent ?: return null
        val uri = intent.data?.takeIf { it.scheme == SCHEME && it.host == HOST }
        fun value(key: String): Double? {
            uri?.let { u -> query(u, key)?.toDoubleOrNull()?.let { return it } }
            val extras = intent.extras ?: return null
            @Suppress("DEPRECATION") // the typed getters need the caller's type; am sends strings or doubles
            return when (val v = extras.get(key)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
        }
        val t = target(value("lat"), value("lon"), value("z") ?: value("zoom"), value("bearing")) ?: return null
        return CameraPosition.Builder().target(LatLng(t.lat, t.lon)).zoom(t.zoom).bearing(t.bearing).build()
    }

    /** Where a link points (plain values, so the checks are unit-tested on the JVM). */
    data class Target(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double)

    /** Validates a link's values; null when lat/lon are missing or anything is out of range. */
    fun target(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?): Target? {
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -85.06..85.06 || lon !in -180.0..180.0) return null
        val z = zoom ?: DEFAULT_ZOOM
        if (!z.isFinite() || z !in 0.0..22.0) return null
        val b = bearing?.takeIf { it.isFinite() }?.mod(360.0) ?: 0.0
        return Target(lat, lon, z, b)
    }

    /** Moves [mapView]'s camera for links delivered to the running activity (onNewIntent). */
    fun install(activity: ComponentActivity, mapView: MapView) {
        activity.addOnNewIntentListener { intent ->
            val c = camera(intent) ?: return@addOnNewIntentListener
            activity.intent = intent
            mapView.getMapAsync { it.moveCamera(CameraUpdateFactory.newCameraPosition(c)) }
        }
    }

    private fun query(uri: Uri, key: String): String? = runCatching { uri.getQueryParameter(key) }.getOrNull()
}
