package io.github.jasondorjeshort.dystopo.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.MainActivity
import java.util.Locale

/**
 * The foreground service that keeps the process (and the GPS) alive while a track records, with
 * a notification showing the live distance. Recording itself lives in [Tracking]; this service only
 * runs the [GpsLocationSource] (when the recording mode uses it) and the notification.
 */
class TrackingService : Service() {
    private val tracking get() = Tracking.of(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val t = tracking
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW))
        try {
            val n = notification(t.ui)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, n)
            t.serviceState = "foreground"
        } catch (e: Exception) {
            // Android 14 refuses a location-type foreground service without the location permission.
            Log.w(Tracking.TAG, "startForeground failed", e)
            t.serviceState = "failed: ${e.javaClass.simpleName}: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        t.gpsState = if (!t.mode.gps) "off (mode ${t.mode.name.lowercase()})"
        else if (t.gps.start { t.onGpsFix(it) }) "on" else "unavailable (permission or provider)"
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tracking.gps.stop()
        tracking.gpsState = "off"
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun update(state: Tracking.UiState) {
        if (!state.recording) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
    }

    private fun notification(state: Tracking.UiState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = String.format(Locale.US, "%.2f km, %d points", state.distanceM / 1000, state.points)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording ${state.activeName ?: "track"}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL = "tracking"
        const val NOTIFICATION_ID = 31
        @Volatile var instance: TrackingService? = null
            private set
    }
}
