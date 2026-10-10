package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.app.packs.PacksUi

/**
 * Debug routes for region packs (registered with one line in [Routes]):
 * - `GET /packs`: every pack on the device (name, bounds, zooms, bytes, attribution, per-archive
 *   header facts) and which one is active.
 * - `POST /packs/activate?name=`: switch packs; the camera moves to the pack's bounds.
 * - `POST /packs/download?url=[&name=][&activate=true]`: start (or resume) downloading a pack pair from
 *   a static host; `url` names `<name>-dem.pmtiles`, `<name>-outdoors.pmtiles` or the `<name>` prefix.
 * - `GET /packs/download`: download progress (bytes, percent, rate, resume offsets, HTTP responses).
 * - `DELETE /packs/{name}` (or `POST /packs/delete?name=`): delete a pack, cancelling its download.
 * - `POST /packs/ui?show=true|false`: open or close the pack list screen (for screenshots).
 */
object PacksRoutes {
    private val packPath = Regex("^/packs/([^/]+)$")

    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/packs" && !r.path.startsWith("/packs/")) return null
        val m = PackManager.get(app)
        return try {
            when {
                r.method == "GET" && r.path == "/packs" -> Response.json(m.json())
                r.method == "POST" && r.path == "/packs/activate" -> Response.json(m.activate(r.query["name"] ?: return bad("name required")))
                r.method == "POST" && r.path == "/packs/download" -> {
                    val url = r.query["url"] ?: r.bodyText.trim().takeIf { it.startsWith("http") } ?: return bad("url required")
                    val job = m.downloads.start(url, r.query["name"], activate = r.query["activate"] == "true")
                    Response.json(job.json())
                }
                r.method == "GET" && r.path == "/packs/download" -> Response.json(m.downloads.json())
                r.method == "POST" && r.path == "/packs/delete" -> Response.json(m.delete(r.query["name"] ?: return bad("name required")))
                r.method == "POST" && r.path == "/packs/ui" -> {
                    val show = r.query["show"] != "false"
                    app.map.onMain { PacksUi.visible = show }
                    Response.json(mapOf("visible" to show))
                }
                r.method == "DELETE" -> packPath.matchEntire(r.path)?.let { Response.json(m.delete(it.groupValues[1])) } ?: Response.notFound()
                else -> Response.notFound()
            }
        } catch (e: NoSuchElementException) {
            Response.json(mapOf("error" to e.message), 404)
        } catch (e: IllegalArgumentException) {
            bad(e.message ?: "bad request")
        }
    }

    private fun bad(message: String) = Response.json(mapOf("error" to message), 400)
}
