package io.github.jasondorjeshort.dystopo.app.firstrun

import android.content.Context
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.PackSet
import java.io.File
import java.io.InputStream

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

    @Volatile private var lastCopy: Map<String, Any?> = linkedMapOf("state" to "not run")

    /** What the last [install] did (`GET /firstrun` reports it); T264: with the world overview's copy under `world`. */
    val last: Map<String, Any?> get() = lastCopy + ("world" to WorldOverview.last)

    fun install(context: Context, filesDir: File = context.filesDir): Map<String, Any?> {
        val started = System.currentTimeMillis()
        val version = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("unknown")
        WorldOverview.start(context, filesDir, version) // T264: on a thread of its own, never this one
        val marker = File(filesDir, MARKER)
        if (marker.isFile && marker.readText() == version && DIRS.all { File(filesDir, it).isDirectory }) {
            lastCopy = linkedMapOf("state" to "current", "version" to version)
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
            lastCopy = linkedMapOf("state" to "installed", "version" to version, "files" to files, "bytes" to bytes, "ms" to System.currentTimeMillis() - started)
        } catch (e: Exception) {
            Log.w("DystopoAssets", "bundled assets not installed", e)
            lastCopy = linkedMapOf("state" to "failed", "error" to (e.message ?: e.javaClass.simpleName), "files" to files)
        }
        Log.i("DystopoAssets", "bundled assets: $last")
        return last
    }
}

/**
 * T264: the world overview (`tilebuild --natural-earth-only`: Natural Earth's water, country borders and country and city
 * labels, z0 to z5) travels in the APK as the asset [ASSET] (`bundleMapAssets`) and is copied to the files dir as
 * [PackSet.WORLD_FILE], outside `packs/`, where [PackSet.open] finds it as the built-in pack drawn where nothing else is.
 * The copy runs on a thread of its own ([THREAD]), never the main thread, on the first start of each installed build (a
 * marker holds the APK's update time, as [BundledAssets]); the map gains the overview when the copy lands (a reload).
 */
object WorldOverview {
    const val ASSET = "world/world-outdoors.pmtiles"
    const val MARKER = "world-outdoors.version"
    const val THREAD = "world-overview-copy"

    /** How long the copy waits for the app's first tile service before reloading it (the copy can beat it). */
    private const val WAIT_FOR_TILES_MS = 60_000L

    /** What the last copy did (`GET /firstrun`'s `assets.world`): its state, thread, bytes and time. */
    @Volatile var last: Map<String, Any?> = linkedMapOf("state" to "not run")
        private set

    fun file(filesDir: File) = File(filesDir, PackSet.WORLD_FILE)

    /** Starts the copy on [THREAD]; returns at once. */
    fun start(context: Context, filesDir: File, version: String) {
        Thread({
            val r = install(filesDir, version) { context.assets.open(ASSET) }
            last = r
            Log.i("DystopoAssets", "world overview: $r")
            if (r["state"] == "installed") reloadWhenUp()
        }, THREAD).apply { isDaemon = true }.start()
    }

    /**
     * Copies the bundled file into [filesDir] unless the file and its marker already hold [version]: written beside it and
     * renamed over it, so [PackSet.open] never sees half a file. A failure leaves the previous copy (if any) in place. Pure on
     * files, tested.
     */
    fun install(filesDir: File, version: String, open: () -> InputStream): Map<String, Any?> {
        val started = System.nanoTime()
        val thread = Thread.currentThread().name
        val target = file(filesDir)
        val marker = File(filesDir, MARKER)
        if (target.isFile && marker.isFile && marker.readText() == version) {
            return linkedMapOf("state" to "current", "version" to version, "thread" to thread, "bytes" to target.length())
        }
        val tmp = File(filesDir, PackSet.WORLD_FILE + ".tmp")
        return try {
            filesDir.mkdirs()
            val bytes = open().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "could not rename ${tmp.name}" }
            }
            marker.writeText(version)
            linkedMapOf(
                "state" to "installed", "version" to version, "thread" to thread, "bytes" to bytes,
                "ms" to (System.nanoTime() - started) / 1_000_000,
            )
        } catch (e: Exception) {
            tmp.delete()
            linkedMapOf("state" to "failed", "error" to (e.message ?: e.javaClass.simpleName), "thread" to thread)
        }
    }

    /** Reopens the packs once the app's tile service exists, so the overview appears without a restart. */
    private fun reloadWhenUp() {
        val app = runCatching { DystopoApp.instance }.getOrNull() ?: return
        val until = System.currentTimeMillis() + WAIT_FOR_TILES_MS
        while (runCatching { app.tiles }.isFailure) {
            if (System.currentTimeMillis() > until) return
            Thread.sleep(50)
        }
        app.reload()
    }
}
