package io.github.jasondorjeshort.dystopo.app.firstrun

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import java.io.File
import java.io.FileInputStream

/**
 * T79: "Import pack files": the system file picker (several files at once: the `-dem.pmtiles` and the
 * optional `-outdoors.pmtiles`, from Downloads, a USB stick or a cloud drive), then
 * [PackManager.importFiles] on a background thread. The picker is launched by [ImportPickerHost], drawn once
 * in the map screen; anything may ask for it through [request] (the first-run card, the pack list,
 * `POST /packs/import/pick` in debug builds).
 */
object ImportPicker {
    /** Picker requests; the host launches once per new value. */
    var requests by mutableIntStateOf(0)
    private var handled = 0
    @Volatile var launches = 0
        private set

    /** PMTiles has no registered MIME type on phones; files come as octet-stream or anything. */
    val MIME = arrayOf("application/octet-stream", "application/vnd.pmtiles", "*/*")

    fun request() {
        requests++
    }

    internal fun take(req: Int): Boolean = (req > handled).also { if (it) handled = req }
    internal fun launched() {
        launches++
    }

    /** Imports the picked [uris] (background thread). */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = DystopoApp.instance
        val sources = uris.map { uri ->
            var name: String? = null
            var size: Long? = null
            runCatching {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        size = if (c.isNull(1)) null else c.getLong(1)
                    }
                }
            }
            PackManager.ImportSource(name ?: uri.lastPathSegment ?: "file.pmtiles", size) {
                app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open $name")
            }
        }
        start(sources)
    }

    /** Imports files by path (debug builds: `POST /packs/import?dem=&osm=`); relative paths are under the app's external files dir. */
    fun importPaths(dem: String, osm: String?, name: String?): Map<String, Any?> {
        val app = DystopoApp.instance
        fun file(p: String): File {
            val f = File(p).let { if (it.isAbsolute) it else File(app.getExternalFilesDir(null), p) }
            require(f.isFile) { "no file $f" }
            return f
        }
        val files = listOfNotNull(file(dem), osm?.let(::file))
        return PackManager.get(app).importFiles(files.map { f -> PackManager.ImportSource(f.name, f.length()) { FileInputStream(f) } }, name)
    }

    private fun start(sources: List<PackManager.ImportSource>) {
        FirstRun.message = "Importing ${sources.joinToString { it.displayName }}..."
        Thread({
            FirstRun.message = try {
                val r = PackManager.get(DystopoApp.instance).importFiles(sources)
                "Imported pack ${r["name"]}" + if (r["labels"] == false) " (no label fonts on this phone yet: set a server in ${Screens.SETTINGS} › ${Screens.SOURCES} to fetch them)" else ""
            } catch (e: Exception) {
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }, "dystopo-pack-import").apply { isDaemon = true }.start()
    }
}

/** Hosts the file picker launcher; drawn once in the map screen. */
@Composable
fun ImportPickerHost() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        ImportPicker.importUris(uris)
    }
    val req = ImportPicker.requests
    LaunchedEffect(req) {
        if (ImportPicker.take(req)) {
            ImportPicker.launched()
            launcher.launch(ImportPicker.MIME)
        }
    }
}
