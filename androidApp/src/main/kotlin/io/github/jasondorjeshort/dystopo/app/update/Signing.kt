package io.github.jasondorjeshort.dystopo.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * T83: the signing certificates of the running app and of a downloaded APK, as SHA-256 fingerprints of the
 * certificates (what `apksigner verify --print-certs` prints; the release key's starts 47313810a4313ca3). An
 * update is offered only when the APK's signers are exactly the running app's: Android would refuse a
 * different key at install time anyway, but this way the user is told why before the installer opens.
 */
object Signing {
    /** The running app's signer fingerprints (lower-case hex), sorted. */
    fun own(context: Context): List<String> {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        return fingerprints(info)
    }

    /** What the system reads from an APK file: package, version code and signer fingerprints; null when it is not a readable APK. */
    fun archive(context: Context, apk: File): UpdateFeed.ApkFacts? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        info ?: return null
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return UpdateFeed.ApkFacts(info.packageName, code, info.versionName, fingerprints(info))
    }

    private fun fingerprints(info: PackageInfo): List<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
        else @Suppress("DEPRECATION") info.signatures
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.sorted()
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(digits[(b.toInt() shr 4) and 15]).append(digits[b.toInt() and 15])
        return sb.toString()
    }

    /** First 16 hex digits, as the docs quote the release key (`47313810a4313ca3`). */
    fun short(fingerprint: String?): String = UpdateFeed.short(fingerprint)
}
