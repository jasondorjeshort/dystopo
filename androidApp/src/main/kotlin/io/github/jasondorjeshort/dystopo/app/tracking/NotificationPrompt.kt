package io.github.jasondorjeshort.dystopo.app.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * T81: Android 13+ shows the recording notification only with POST_NOTIFICATIONS, a runtime permission. It is
 * asked for once, when the user first starts a recording (the moment the notification matters); recording works
 * either way, and a refusal is not asked again (the user can allow it in the system settings later).
 */
object NotificationPrompt {
    private const val PREFS = "dystopo-notifications"
    private const val ASKED = "askedAtRecording"

    /** True when the system dialog should be shown now; marks it as asked, so this is true at most once. */
    fun shouldAsk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return false
        prefs.edit().putBoolean(ASKED, true).apply()
        return true
    }

    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"
}
