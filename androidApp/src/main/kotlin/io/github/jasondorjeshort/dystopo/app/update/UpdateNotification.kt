package io.github.jasondorjeshort.dystopo.app.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.jasondorjeshort.dystopo.app.MainActivity

/**
 * T83: "Update to <version>" as a notification (once per version; tapping it opens the map, where the same offer
 * has Install and Not now). Without the notification permission (Android 13+, asked at the first recording) only
 * the map shows it.
 */
object UpdateNotification {
    private const val CHANNEL = "updates"
    private const val ID = 83
    private const val PREFS = "dystopo-updates"

    fun post(context: Context, tag: String, versionName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("notifiedTag", null) == tag) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update to $versionName")
            .setContentText("Downloaded and checked. Open Dystopo to install it.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(ID, n) }
        prefs.edit().putString("notifiedTag", tag).apply()
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
