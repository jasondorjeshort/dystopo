package io.github.jasondorjeshort.dystopo.app.firstrun

import android.content.Context
import android.util.Log
import java.io.File

/**
 * T80: the label glyphs (Noto Sans, SIL OFL 1.1) and the marker sprite sheet travel in the APK (assets `glyphs/`
 * and `sprites/`, made by the `bundleMapAssets` Gradle task) and are copied into the files dir, where the style
 * finds them as `file://` URLs ([io.github.jasondorjeshort.dystopo.app.Glyphs],
 * [io.github.jasondorjeshort.dystopo.app.SpriteFiles]), so a fresh install never draws a map without labels or
 * icons, server or not. The copy runs on the first start of each installed build (a marker holds the APK's
 * update time), so an update brings its own glyphs and sprites; files pushed by tools/android-push-pack.ps1 in
 * between are left alone until then.
 */
object BundledAssets {
    private const val MARKER = "bundled-assets.version"
    val DIRS = listOf("glyphs", "sprites")

    /** What the last [install] did (`GET /firstrun` reports it). */
    @Volatile var last: Map<String, Any?> = linkedMapOf("state" to "not run")
        private set

    fun install(context: Context, filesDir: File = context.filesDir): Map<String, Any?> {
        val started = System.currentTimeMillis()
        val version = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("unknown")
        val marker = File(filesDir, MARKER)
        if (marker.isFile && marker.readText() == version && DIRS.all { File(filesDir, it).isDirectory }) {
            last = linkedMapOf("state" to "current", "version" to version)
            return last
        }
        var files = 0
        var bytes = 0L
        try {
            for (top in DIRS) {
                fun copy(path: String) {
                    val children = context.assets.list(path).orEmpty()
                    if (children.isEmpty()) {
                        val target = File(filesDir, path)
                        target.parentFile?.mkdirs()
                        val tmp = File(target.parentFile, target.name + ".tmp")
                        context.assets.open(path).use { input -> tmp.outputStream().use { bytes += input.copyTo(it) } }
                        if (!tmp.renameTo(target)) {
                            target.delete()
                            tmp.renameTo(target)
                        }
                        files++
                    } else {
                        for (c in children) copy("$path/$c")
                    }
                }
                copy(top)
            }
            marker.writeText(version)
            last = linkedMapOf("state" to "installed", "version" to version, "files" to files, "bytes" to bytes, "ms" to System.currentTimeMillis() - started)
        } catch (e: Exception) {
            Log.w("DystopoAssets", "bundled assets not installed", e)
            last = linkedMapOf("state" to "failed", "error" to (e.message ?: e.javaClass.simpleName), "files" to files)
        }
        Log.i("DystopoAssets", "bundled assets: $last")
        return last
    }
}
