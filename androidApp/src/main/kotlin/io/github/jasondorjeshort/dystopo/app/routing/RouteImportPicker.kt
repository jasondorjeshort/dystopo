package io.github.jasondorjeshort.dystopo.app.routing

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.app.trips.Trips

/**
 * T65: importing a route from a GPX file anywhere on the phone (Downloads, a cloud drive) through the system
 * file picker, from the Routes list ("Import"; one line in the trips UI). The file goes through the trips'
 * own import ([Trips.import]: our exports come back with their ids, a route from another app becomes via
 * points joined by fixed legs) and the imported route opens. `POST /route/import/pick` (debug) opens the
 * Routes list and the picker the same way; `GET /route/import` has the last result. T219: the sentences of [ImportText]:
 * [line] in the Routes list from the start, the end on the opened route's card.
 */
object RouteImportPicker {
    /** Picker requests from the debug route; the Import action launches once per new value. */
    var requests by mutableIntStateOf(0)
    private var handled = 0

    @Volatile var last: Map<String, Any?>? = null
        private set
    @Volatile var launches = 0
        private set

    /** MIME types offered: GPX has no registered type on most phones, so everything is allowed after the GPX ones. */
    val MIME = arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream", "*/*")

    private val main = Handler(Looper.getMainLooper())

    /** Reads [uri] and imports it as routes (background thread). */
    fun importUri(uri: Uri) {
        val app = DystopoApp.instance
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val file = name ?: ImportText.THE_FILE
        say(ImportText.start(file))
        ImportText.hold()
        last = try {
            val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("cannot open the file")
            try {
                routes(text, name, file)
            } catch (e: io.github.jasondorjeshort.dystopo.gpx.GpxException) {
                tracksInstead(text, name, file)
            }
        } catch (e: io.github.jasondorjeshort.dystopo.gpx.GpxException) { // neither routes nor tracks, or not GPX at all
            say(ImportText.NOTHING)
            linkedMapOf("file" to name, "error" to ImportText.NOTHING, "detail" to e.message)
        } catch (e: Exception) {
            Log.w(RoutePlanner.TAG, "route import failed", e)
            val msg = ImportText.failed(file)
            say(msg)
            linkedMapOf("file" to name, "error" to msg, "detail" to (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun routes(text: String, name: String?, file: String): Map<String, Any?> {
        val r = Trips.import(text)
        val msg = ImportText.routes(r.imported.size, r.skipped.size, file)
        // Trips.import opens the route through a main-thread post, and a list refresh computed in between
        // can close it again (Trips.refresh reads the open route before that post ran): once the posts have
        // run, open it on the main thread if it is not open. T219: then its card carries the sentence.
        val opened = r.imported.lastOrNull()
        if (opened != null) {
            DystopoApp.instance.map.onMain {
                if (Trips.editingId != opened.id) Trips.open(opened.id, fit = false)
                line = null
            }
            Trips.say(msg)
        } else say(msg)
        return linkedMapOf("file" to name, "imported" to r.imported.map { it.id }, "names" to r.imported.map { it.name }, "skipped" to r.skipped, "message" to msg)
    }

    /** T219: a file with no route but a track goes to the tracks, so "That file has no route or track" is true when it is said. */
    private fun tracksInstead(text: String, name: String?, file: String): Map<String, Any?> {
        val r = Tracking.of(DystopoApp.instance).importGpx(text, file.substringBeforeLast('.'), file)
        val msg = ImportText.tracks(r.imported.size, r.skippedTracks.size, r.waypoints, file)
        say(msg)
        return linkedMapOf("file" to name, "tracks" to r.imported.map { it.id }, "skipped" to r.skippedTracks, "message" to msg)
    }

    /** T219: the import's line in the Routes list, from its start to its end (a route that opens has the end on its card). */
    var line by mutableStateOf<String?>(null)
        private set

    private fun say(text: String) = main.post { line = text }

    fun json(): Map<String, Any?> = linkedMapOf("requests" to requests, "launches" to launches, "line" to line, "last" to last)

    internal fun launched() {
        launches++
    }

    internal fun take(req: Int): Boolean = (req > handled).also { if (it) handled = req }
}

/**
 * T219: what an import says, from its start to its end, in the Routes list and on the Tracks sheet alike (ADR 0031; the
 * survey `route-card-and-lists.md`). Pure, tested, but for [hold].
 */
internal object ImportText {
    const val THE_FILE = "the file"
    const val NOTHING = "That file has no route or track"
    const val ALREADY = "Already added"

    fun start(file: String) = "Importing $file"

    /** The end of a route import: [skipped] routes were here already. */
    fun routes(added: Int, skipped: Int, file: String): String = when {
        added > 0 -> "Added ${count(added, "route")} from $file"
        skipped > 0 -> ALREADY
        else -> NOTHING
    }

    /** The end of a track import, its waypoints named when it brought any; nothing new is what is left, the file holding something ([TrackImport] throws otherwise). */
    fun tracks(added: Int, skipped: Int, waypoints: Int, file: String): String = when {
        added > 0 && waypoints > 0 -> "Added ${count(added, "track")} and ${count(waypoints, "waypoint")} from $file"
        added > 0 -> "Added ${count(added, "track")} from $file"
        waypoints > 0 -> "Added ${count(waypoints, "waypoint")} from $file"
        else -> ALREADY
    }

    fun failed(file: String) = "Could not read $file"

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** Debug (`POST /tracks/ui?importHoldMs=`): the next import waits this long after its start line, for a screenshot of it. */
    @Volatile var holdMs = 0L

    fun hold() {
        val ms = holdMs
        holdMs = 0
        if (ms > 0) Thread.sleep(ms)
    }
}

/** The Routes list's "Import": opens the system file picker for a GPX file (T65). */
@Composable
fun RouteImportAction() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        // On the trips worker, after any list refresh already queued there: a refresh computed before the import
        // would otherwise close the route the import just opened (its state is posted to the main thread later).
        if (uri != null) Trips.background { RouteImportPicker.importUri(uri) }
    }
    val req = RouteImportPicker.requests
    LaunchedEffect(req) {
        if (RouteImportPicker.take(req)) {
            RouteImportPicker.launched()
            launcher.launch(RouteImportPicker.MIME)
        }
    }
    BasicText(
        "Import",
        style = TextStyle(color = Color(0xFF2C7BB6), fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable { RouteImportPicker.launched(); launcher.launch(RouteImportPicker.MIME) }.padding(vertical = 4.dp),
    )
}
