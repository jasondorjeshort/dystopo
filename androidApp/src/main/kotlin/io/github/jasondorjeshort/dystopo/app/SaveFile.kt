package io.github.jasondorjeshort.dystopo.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import io.github.jasondorjeshort.dystopo.app.routing.RouteOrigin
import io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner
import io.github.jasondorjeshort.dystopo.app.routing.RouteSession
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import io.github.jasondorjeshort.dystopo.model.RouteGpx
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.routing.PlannedRoute
import io.github.jasondorjeshort.dystopo.store.TrackExport

/**
 * T219: Export GPX saves where the user picks: the system's save dialog (one launcher, [MainActivity.saveFile]) for a
 * track ([track]), a kept route or the planning card's route ([route]), then one line where the export was asked,
 * "Saved <name> as a GPX file". Cancelling the dialog says nothing. The file's bytes are made before the dialog opens, so
 * a route cleared meanwhile still saves as it was. A saved track also gets the app's own copy in `files/tracks/`, which the
 * Import list shows ([Tracking.export]); the debug routes (`POST /route/export`, `POST /tracks/{id}/export`,
 * `POST /routes/{id}/export`) write the app's own files and never open the dialog. `GET /tracks/ui` `saveFile` has the
 * launches and the last result.
 */
object SaveFile {
    /** The launcher's contract; the name [ask] passes it carries the extension. */
    fun contract() = ActivityResultContracts.CreateDocument(MIME)

    private class Pending(val name: String, val fileName: String, val bytes: ByteArray, val say: (String) -> Unit, val after: () -> Unit, val line: String = saved(name))

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending: Pending? = null
    @Volatile var launches = 0
        private set
    @Volatile private var last: Map<String, Any?>? = null

    /** Exports track [id] (off the main thread: it reads the store). */
    fun track(id: String) = Thread({
        val tracking = Tracking.of(DystopoApp.instance)
        val made = tracking.call { tracking.store.summary(id)?.let { s -> TrackExport.gpx(tracking.store, id)?.let { s.name to it } } }
        if (made == null) tracking.refreshUi(saveFailed("this track", "it is gone"))
        else ask(made.first, made.second, { tracking.refreshUi(it) }) { tracking.export(id) }
    }, "dystopo-save-file").start()

    /** Exports a kept route; [say] gets the line after the save (T262: the Tracks and routes screen's message; the trip card's by default). */
    fun route(plan: RoutePlan, say: (String) -> Unit = { Trips.say(it) }) = ask(plan.name.ifBlank { Trips.UNNAMED }, RouteGpx.gpx(plan), say)

    /** Exports the planning card's route under the name [Trips.keep] would give it (off the main thread: the name reads the packs). */
    fun route(plan: PlannedRoute, from: RouteOrigin.End?, to: RouteOrigin.End?) = Thread({
        val name = Trips.nameFor(plan, from, to)
        ask(name, RoutePlanner.gpx(plan.route.copy(name = name)), { RouteSession.say(it) })
    }, "dystopo-save-file").start()

    /** T258: saves [bytes] as [fileName] through the same dialog (the diagnostic dump); [line] is said after the save. */
    fun bytes(fileName: String, bytes: ByteArray, line: String, say: (String) -> Unit) = launch(Pending(fileName, fileName, bytes, say, {}, line))

    private fun ask(name: String, gpx: String, say: (String) -> Unit, after: () -> Unit = {}) =
        launch(Pending(name, gpxFileName(name), gpx.toByteArray(Charsets.UTF_8), say, after))

    private fun launch(p: Pending) {
        val name = p.name
        val file = p.fileName
        val say = p.say
        pending = p
        main.post {
            val activity = DystopoApp.instance.map.activity as? MainActivity
            if (activity == null) {
                pending = null
                say(saveFailed(name, "the app is not on screen"))
            } else {
                launches++
                activity.saveFile.launch(file)
            }
        }
    }

    /** The dialog's answer, on the main thread: null when cancelled, else where to write. */
    fun finished(uri: Uri?) {
        val p = pending
        pending = null
        if (uri == null) {
            last = linkedMapOf("cancelled" to true, "name" to p?.name)
            return
        }
        Thread({ write(p, uri) }, "dystopo-save-file").start()
    }

    private fun write(p: Pending?, uri: Uri) {
        val resolver = DystopoApp.instance.contentResolver
        if (p == null) { // the process died behind the dialog, and the bytes with it: no empty file left behind
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            last = linkedMapOf("error" to "nothing to save")
            return
        }
        try {
            checkNotNull(resolver.openOutputStream(uri, "wt")) { "the folder did not open" }.use { it.write(p.bytes) }
            val line = p.line
            last = linkedMapOf("name" to p.name, "fileName" to p.fileName, "bytes" to p.bytes.size, "message" to line)
            p.say(line)
            runCatching(p.after).onFailure { Log.w(TAG, "after saving ${p.fileName}", it) }
        } catch (e: Exception) {
            Log.w(TAG, "saving ${p.fileName}", e)
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            val line = saveFailed(p.name, e.message ?: e.javaClass.simpleName)
            last = linkedMapOf("name" to p.name, "fileName" to p.fileName, "error" to line)
            p.say(line)
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf("launches" to launches, "waitingForDialog" to (pending != null), "last" to last)

    /**
     * What the dialog is told the file is. The phone knows no extension for GPX's own type, and a documents provider then
     * takes the whole name for the stem, so a second save beside the first was "walk.gpx (1)"; with this one the name's
     * extension stands ("walk (1).gpx", seen on the emulator).
     */
    const val MIME = "application/octet-stream"
    private const val TAG = "DystopoSaveFile"
}

/** T219: the line after a save (ADR 0031). Pure, tested. */
internal fun saved(name: String): String = "Saved $name as a GPX file"

/** T219: the line when the save failed. Pure, tested. */
internal fun saveFailed(name: String, why: String): String = "Could not save $name: $why"

/** T219: what the save dialog offers as the file's name: [name] without the characters file systems refuse, ".gpx" after it. Pure, tested. */
internal fun gpxFileName(name: String): String {
    val safe = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "-").trim(' ', '.', '-').take(80).trimEnd(' ', '.', '-')
    return safe.ifEmpty { "route" } + ".gpx"
}
