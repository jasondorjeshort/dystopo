package io.github.jasondorjeshort.dystopo.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * T83: hands a verified APK to the system installer through a `PackageInstaller` session (no file provider, no
 * world-readable copy). The system answers on [UpdateResultReceiver]: first with "pending user action" (we start
 * its confirmation screen: "Do you want to update this app?", and on the first update the "install unknown apps"
 * switch for Dystopo, which `REQUEST_INSTALL_PACKAGES` lets it offer), then with the outcome. A cancel is
 * [PackageInstaller.STATUS_FAILURE_ABORTED], which [Updater] remembers as declined for that version. On success
 * Android stops this process and replaces the app; tracks, trips, packs and settings stay (same package, same key).
 */
object UpdateInstaller {
    const val ACTION = "io.github.jasondorjeshort.dystopo.UPDATE_RESULT"

    /** The file no longer has the checksum it was checked with (changed between the check and the install). */
    class ApkChanged(message: String) : java.io.IOException(message)

    /**
     * Writes [apk] into a new session while hashing the bytes written; commits only when they hash to [expectedSha]
     * (the checked download), else abandons the session and throws [ApkChanged].
     */
    fun install(context: Context, apk: File, tag: String, expectedSha: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED) // honoured only when we installed ourselves
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val md = java.security.MessageDigest.getInstance("SHA-256")
                session.openWrite("dystopo.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input ->
                        val buf = ByteArray(256 shl 10)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                    session.fsync(out)
                }
                val written = Signing.hex(md.digest())
                if (written != expectedSha) throw ApkChanged("the APK changed after it was checked (${written.take(12)}..., checked ${expectedSha.take(12)}...)")
                val intent = Intent(context, UpdateResultReceiver::class.java).setAction(ACTION).putExtra("tag", tag)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}

/** The installer's answers for [UpdateInstaller] sessions (not exported: only the system's PendingIntent reaches it). */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(Updater.TAG, "installer status $status ${message ?: ""}")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(confirm)
            } catch (e: Exception) {
                Log.w(Updater.TAG, "could not open the installer", e)
                if (Updater.initialized()) Updater.installResult(PackageInstaller.STATUS_FAILURE, "could not open the system installer: ${e.message}")
            }
            return
        }
        if (Updater.initialized()) Updater.installResult(status, message)
    }
}
