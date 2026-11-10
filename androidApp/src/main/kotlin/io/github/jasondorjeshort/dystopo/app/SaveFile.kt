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

/** T272: one GPX file to share or export: the item's name (the file is named after it) and the file's text. */
class GpxItem(val name: String, val gpx: String)

/**
 * T272: Share GPX (T258's follow-up, the survey's point 4; no folder sync): the GPX of one track or route, or of a
 * selection, through the system's share sheet. The files are written to `cacheDir/share/` (files there older than a day
 * go first) and offered by the app's own FileProvider (the manifest's `${applicationId}.gpxshare`, `res/xml/share_paths.xml`)
 * as `application/gpx+xml`, read permission granted to the app the user picks: one file with `ACTION_SEND`, several with
 * `ACTION_SEND_MULTIPLE`, inside the system's chooser. The select bar's Export GPX writes the same files into a folder the
 * user picks ([toFolder]). `GET /tracks/ui` `share` has the launches and the last result.
 */
object ShareGpx {
    const val MIME = "application/gpx+xml"
    const val DIR = "share"
    private const val TAG = "DystopoShare"
    private const val KEEP_MS = 24 * 3600_000L

    @Volatile var launches = 0
        private set
    @Volatile private var last: Map<String, Any?>? = null

    fun authority(context: android.content.Context): String = context.packageName + ".gpxshare"

    /**
     * The GPX of each row ([kind] "track" or "route", [id]), in order (off the main thread: it reads the stores); a row
     * that is gone is left out.
     */
    fun items(rows: List<Pair<String, String>>): List<GpxItem> {
        val tracking = Tracking.of(DystopoApp.instance)
        return rows.mapNotNull { (kind, id) ->
            runCatching {
                if (kind == "route") Trips.plan(id)?.let { GpxItem(it.name.ifBlank { Trips.UNNAMED }, RouteGpx.gpx(it)) }
                else tracking.call { tracking.store.summary(id)?.let { s -> TrackExport.gpx(tracking.store, id)?.let { GpxItem(s.name, it) } } }
            }.onFailure { Log.w(TAG, "GPX of $kind $id", it) }.getOrNull()
        }
    }

    /** Writes [items] and opens the share sheet on them (any thread); [say] gets a line only when nothing could be shared. */
    fun share(items: List<GpxItem>, say: (String) -> Unit) {
        if (items.isEmpty()) { say(SHARE_NOTHING); last = linkedMapOf("error" to SHARE_NOTHING); return }
        val app = DystopoApp.instance
        val files = try {
            val dir = java.io.File(app.cacheDir, DIR).apply { mkdirs() }
            val old = System.currentTimeMillis() - KEEP_MS
            dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
            val sub = java.io.File(dir, System.currentTimeMillis().toString(36)).apply { mkdirs() } // a share's own folder: names stay as they are
            val names = uniqueNames(items.map { gpxFileName(it.name) })
            items.zip(names).map { (it, n) -> java.io.File(sub, n).apply { writeText(it.gpx, Charsets.UTF_8) } }
        } catch (e: Exception) {
            Log.w(TAG, "writing the files to share", e)
            val line = shareFailed(e.message ?: e.javaClass.simpleName)
            last = linkedMapOf("error" to line)
            say(line)
            return
        }
        val uris = files.map { androidx.core.content.FileProvider.getUriForFile(app, authority(app), it) }
        val send = if (uris.size == 1) android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
        else android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = MIME
        send.putExtra(android.content.Intent.EXTRA_SUBJECT, shareSubject(items.map { it.name }))
        send.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply { for (u in uris.drop(1)) addItem(android.content.ClipData.Item(u)) }
        send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = android.content.Intent.createChooser(send, shareTitle(items.size))
        Handler(Looper.getMainLooper()).post {
            val activity = app.map.activity as? MainActivity
            try {
                if (activity != null) activity.startActivity(chooser)
                else app.startActivity(chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                launches++
                last = linkedMapOf("files" to files.map { it.name }, "bytes" to files.sumOf { it.length() }, "uris" to uris.map { it.toString() }, "mime" to MIME, "action" to send.action)
            } catch (e: Exception) {
                Log.w(TAG, "the share sheet", e)
                val line = shareFailed(e.message ?: e.javaClass.simpleName)
                last = linkedMapOf("error" to line)
                say(line)
            }
        }
    }

    /**
     * The select bar's Export GPX: [items] written into the folder [tree] the user picked (the system's folder picker),
     * one file each; then one line ("Saved 3 GPX files"). Off the main thread.
     */
    fun toFolder(tree: Uri, items: List<GpxItem>, say: (String) -> Unit) {
        val resolver = DystopoApp.instance.contentResolver
        var saved = 0
        val errors = ArrayList<String>()
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        for (it in items) {
            try {
                val doc = checkNotNull(DocumentsContract.createDocument(resolver, parent, SaveFile.MIME, gpxFileName(it.name))) { "the folder refused the file" }
                checkNotNull(resolver.openOutputStream(doc, "wt")) { "the file did not open" }.use { o -> o.write(it.gpx.toByteArray(Charsets.UTF_8)) }
                saved++
            } catch (e: Exception) {
                Log.w(TAG, "exporting ${it.name}", e)
                errors += it.name
            }
        }
        val line = if (errors.isEmpty()) savedFiles(saved) else savedSome(saved, items.size)
        last = linkedMapOf("folder" to tree.toString(), "saved" to saved, "failed" to errors, "message" to line)
        say(line)
    }

    fun json(): Map<String, Any?> = linkedMapOf("launches" to launches, "last" to last)
}

/** T272: the share sheet's title (ADR 0031). Pure, tested. */
internal fun shareTitle(n: Int): String = if (n == 1) "Share GPX" else "Share $n GPX files"

/** T272: the subject a mail app takes: the one name, or "3 tracks and routes". Pure, tested. */
internal fun shareSubject(names: List<String>): String = if (names.size == 1) names[0] else "${names.size} tracks and routes"

/** T272: the lines after a share or a folder export. Pure, tested. */
internal const val SHARE_NOTHING = "Nothing to share"
internal fun shareFailed(why: String): String = "Could not share: $why"
internal fun savedFiles(n: Int): String = if (n == 1) "Saved 1 GPX file" else "Saved $n GPX files"
internal fun savedSome(saved: Int, of: Int): String = "Saved $saved of $of GPX files"

/** T272: [names] made unique in order ("walk.gpx", "walk (2).gpx"), so a share of two items with one name keeps both. Pure, tested. */
internal fun uniqueNames(names: List<String>): List<String> {
    val seen = HashSet<String>()
    return names.map { n ->
        var c = n
        var k = 2
        while (!seen.add(c.lowercase())) c = n.removeSuffix(".gpx") + " (${k++}).gpx"
        c
    }
}
