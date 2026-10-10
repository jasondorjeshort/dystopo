package io.github.jasondorjeshort.dystopo.app.routing

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.trips.Trips

/**
 * T65: importing a route from a GPX file anywhere on the phone (Downloads, a cloud drive) through the system
 * file picker, from the Routes list ("Import"; one line in the trips UI). The file goes through the trips'
 * own import ([Trips.import]: our exports come back with their ids, a route from another app becomes via
 * points joined by fixed legs) and the imported route opens. `POST /route/import/pick` (debug) opens the
 * Routes list and the picker the same way; `GET /route/import` has the last result.
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
        last = try {
            val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("cannot open the file")
            val r = Trips.import(text)
            // Trips.import opens the route through a main-thread post, and a list refresh computed in between
            // can close it again (Trips.refresh reads the open route before that post ran): once the posts have
            // run, open it on the main thread if it is not open.
            r.imported.lastOrNull()?.let { p -> app.map.onMain { if (Trips.editingId != p.id) Trips.open(p.id, fit = false) } }
            val msg = when {
                r.imported.isNotEmpty() -> "Imported ${r.imported.size} route(s) from ${name ?: "the file"}"
                r.skipped.isNotEmpty() -> "Already imported: ${name ?: "the file"}"
                else -> "No route in ${name ?: "the file"}"
            }
            toast(msg)
            linkedMapOf("file" to name, "imported" to r.imported.map { it.id }, "names" to r.imported.map { it.name }, "skipped" to r.skipped, "message" to msg)
        } catch (e: Exception) {
            Log.w(RoutePlanner.TAG, "route import failed", e)
            val msg = "Import failed: ${e.message ?: e.javaClass.simpleName}"
            toast(msg)
            linkedMapOf("file" to name, "error" to msg)
        }
    }

    private fun toast(text: String) = main.post { Toast.makeText(DystopoApp.instance, text, Toast.LENGTH_LONG).show() }

    fun json(): Map<String, Any?> = linkedMapOf("requests" to requests, "launches" to launches, "last" to last)

    internal fun launched() {
        launches++
    }

    internal fun take(req: Int): Boolean = (req > handled).also { if (it) handled = req }
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
