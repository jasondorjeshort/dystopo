package io.github.jasondorjeshort.dystopo.app.reports

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup
import java.io.File

/**
 * T145, the problem reports' debug routes (debug builds only, on the debug port; [Reports], [ReportRow]):
 * - `GET /reports`: reads the exit records not seen yet first (`import`: `records`, `written`, `skipped`; `launchImport`:
 *   what the read at launch found), then the kept
 *   reports newest first (`reports`: `id`, `kind`, `label` as the row lists it, `at`, `atMs`, `headline`, `bytes`, `file`,
 *   `header` as written, unscrubbed), `keep`, `exitSupported`.
 * - `GET /reports/{id}`: one report as the row shows it: `text` (scrubbed, what Open GitHub issue copies), `title`,
 *   `link` (`url`, `urlLength`, `encodedBodyLength`, `frames`, `titleOnly`, `body`), and `header`.
 * - `DELETE /reports/{id}`: deletes it (`deleted`).
 * - `POST /reports/ui?id=`: opens Settings › About and advanced with that report shown (no `id`: none), for screenshots;
 *   503 while the app is in the background.
 * - `POST /reports/open?id=`: what Open GitHub issue does for that report (copies, then opens the link in the browser),
 *   then reads the clipboard back: `message`, `url`, `urlLength`, `copied`, `clipboard`, `equal`.
 * - `POST /debug/crash`: throws on the UI thread (`?thread=worker`: on a new thread) with a message carrying what a real
 *   crash could: the camera's position, a tile path, the packs in use and a file path under the app's storage, so the
 *   report's scrub can be checked; the process dies and the report is listed on the next launch. Answers at once.
 */
object ReportRoutes {
    private val ONE = Regex("^/reports/([A-Za-z0-9._-]+)$")

    fun handle(app: DystopoApp, r: Request): Response? {
        ONE.matchEntire(r.path)?.let { m ->
            val id = m.groupValues[1]
            if (id == "ui" || id == "open") return@let
            return when (r.method) {
                "GET" -> Reports.get(id)?.let { Response.json(one(it)) } ?: notFound(id)
                "DELETE" -> if (Reports.delete(id)) { ReportUi.changed(); Response.json(mapOf("deleted" to id)) } else notFound(id)
                else -> null
            }
        }
        return when (r.method to r.path) {
            "GET" to "/reports" -> Response.json(list(app))
            "POST" to "/reports/ui" -> ui(app, r.query["id"])
            "POST" to "/reports/open" -> open(app, r.query["id"])
            "POST" to "/debug/crash" -> crash(app, r.query["thread"] == "worker")
            else -> null
        }
    }

    private fun notFound(id: String) = Response.json(mapOf("error" to "no report $id"), 404)

    private fun list(app: DystopoApp): Map<String, Any?> {
        val import = Reports.importExits(app)
        val files = Reports.list()
        return linkedMapOf(
            "exitSupported" to (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R),
            "launchImport" to Reports.launchImport, "import" to import, "keep" to Reports.KEEP, "dir" to Reports.dir().path,
            "reports" to files.mapNotNull { f ->
                Reports.read(f)?.let { rep ->
                    linkedMapOf("id" to rep.id, "kind" to rep.kind, "label" to ReportUi.label(rep), "at" to rep.dump.header["at"], "atMs" to rep.atMs,
                        "headline" to rep.headline, "file" to f.name, "bytes" to f.length(), "header" to rep.dump.header)
                }
            },
        )
    }

    private fun one(rep: Report): Map<String, Any?> {
        val open = ReportUi.prepare(rep)
        return linkedMapOf("id" to rep.id, "kind" to rep.kind, "title" to open.link.title, "text" to open.text, "link" to linkJson(open.link), "header" to rep.dump.header)
    }

    private fun linkJson(l: IssueLink.Link) = linkedMapOf(
        "url" to l.url, "urlLength" to l.url.length, "encodedBodyLength" to l.encodedBodyLength, "frames" to l.frames, "titleOnly" to l.titleOnly, "body" to l.body,
    )

    private fun ui(app: DystopoApp, id: String?): Response {
        app.map.backgroundReason()?.let { return Response.json(mapOf("error" to "the app is in the background", "reason" to it), 503) }
        if (id != null && Reports.get(id) == null) return notFound(id)
        app.map.onMain {
            ReportUi.message = null
            ReportUi.selected = id
            SettingsUi.open(SettingsGroup.ABOUT)
        }
        return Response.json(mapOf("open" to true, "selected" to id))
    }

    private fun open(app: DystopoApp, id: String?): Response {
        app.map.backgroundReason()?.let { return Response.json(mapOf("error" to "the app is in the background", "reason" to it), 503) }
        val rep = id?.let { Reports.get(it) } ?: return Response.json(mapOf("error" to "id of a kept report required"), 400)
        val prepared = ReportUi.prepare(rep)
        val (message, clip) = app.map.onMain {
            ReportUi.selected = rep.id
            val msg = openIssue(app.map.activity ?: app, prepared)
            ReportUi.message = msg
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            msg to cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return Response.json(linkedMapOf("message" to message, "url" to prepared.link.url, "urlLength" to prepared.link.url.length,
            "titleOnly" to prepared.link.titleOnly, "copied" to prepared.text, "clipboard" to clip, "equal" to (prepared.text == clip)))
    }

    private fun crash(app: DystopoApp, worker: Boolean): Response {
        val cam = runCatching { app.map.camera() }.getOrNull().orEmpty()
        val packs = runCatching { app.tiles.packs.regions.map { it.name } }.getOrDefault(emptyList())
        val z = (cam["zoom"] as? Number)?.toInt() ?: 12
        val msg = "debug crash at ${cam["lat"]}, ${cam["lon"]} (lat=${cam["lat"]} lon=${cam["lon"]}) z$z, tile 12/654/1430.png, " +
            "packs $packs, file ${File(app.filesDir, "packs/${packs.firstOrNull() ?: "none"}-dem.pmtiles").path}, request /online/download?bbox=1.5,2.5,3.5,4.5"
        val boom = Runnable { throw DebugCrash(msg) }
        if (worker) Thread(boom, "debug-crash").start() else Handler(Looper.getMainLooper()).postDelayed(boom, 300)
        return Response.json(mapOf("posted" to true, "thread" to if (worker) "debug-crash" else "main"))
    }

    /** The exception `POST /debug/crash` throws. */
    class DebugCrash(message: String) : IllegalStateException(message)
}
