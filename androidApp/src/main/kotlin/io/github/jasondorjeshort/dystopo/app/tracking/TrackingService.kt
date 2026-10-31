package io.github.jasondorjeshort.dystopo.app.tracking

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * The foreground service that keeps the process (and the GPS) alive while a track records, with the recording's
 * notification ([TrackNotification]). Recording itself lives in [Tracking], which starts the GPS once this service is
 * in the foreground ([Tracking.onServiceForeground]).
 *
 * T159: `START_STICKY`. When the process dies while recording, the system restarts this service with no intent; the new
 * process's [Tracking] finds the track still marked as recording in the store (T158 keeps it open with a pending
 * "stopped by the system" gap) and goes on into it. [onTaskRemoved] (the app swiped away from the recent apps) keeps
 * the recording. Foreground-service type `location` (the manifest's and [startForeground]'s).
 *
 * T178: the barometer's trend samples while this service runs a recording (`readout/PressureWatch.setRecording`), so
 * the pressure-fall warning works with the screen off.
 */
class TrackingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        creating = true
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val t = Tracking.of(this)
        creating = false
        if (intent == null) t.onServiceRestarted()
        try {
            val n = t.foregroundNotification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(TrackNotification.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(TrackNotification.ID, n)
        } catch (e: Exception) {
            // Android 12+ refuses a foreground service started from the background, and Android 14 a location-type one
            // without the location permission: the recording waits for the app (Tracking.onServiceFailed).
            Log.w(Tracking.TAG, "startForeground failed", e)
            t.onServiceFailed(e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!t.call { t.recorder.recording }) {
            // A restart after the recording was stopped (or a start that lost a race with Stop): nothing to keep alive.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        t.onServiceForeground()
        io.github.jasondorjeshort.dystopo.app.readout.PressureWatch.setRecording(true) // T178: the trend's sampling
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Tracking.of(this).onTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        creating = false
        Tracking.of(this).onServiceDestroyed()
        io.github.jasondorjeshort.dystopo.app.readout.PressureWatch.setRecording(false) // T178
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: TrackingService? = null
            private set
        /** T159: true from `onCreate` until `onStartCommand` has the [Tracking] (which then does not start the service again). */
        @Volatile var creating = false
            private set
    }
}
