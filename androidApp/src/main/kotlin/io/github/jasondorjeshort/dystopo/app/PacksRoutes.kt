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
 * - `POST /packs/ui?show=true|false[&catalog=1|0]`: open or close the pack list screen (for screenshots);
 *   `catalog=1` opens the "From server" section too (list in the lower part, catalog outlines on the map,
 *   camera fitted to them).
 *
 * T53, the server's pack catalog (`GET /catalog` on the server set in `/settings?server=`):
 * - `GET /packs/catalog[?refresh=1][&server=]`: the catalog with each pack's status on this device
 *   (`available`, `checking` while the installed files are hashed, `installed`, `update`, `downloading`),
 *   installed hashes, download progress, and the map overlay state (`overlay.outlines`).
 * - `POST /packs/catalog/download?id=[&activate=true]`: download a catalog pack; every archive's SHA-256 is
 *   checked against the catalog before the pack is used (a mismatch fails the job with `hash mismatch`).
 * - `POST /packs/catalog/refresh[?server=]` (T57): fetch the catalog again now (the open "From server" section
 *   reloads too); JSON as `GET /packs/catalog`.
 * - `GET /packs/update`: installed packs whose catalog content hash differs (fresh catalog).
 * - `POST /packs/update?name=`: update one: only the changed archives are downloaded and verified, then
 *   swapped in while the pack is closed.
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
                    val catalog = r.query["catalog"]?.let { it == "1" || it == "true" }
                    app.map.onMain {
                        PacksUi.visible = show
                        if (catalog != null) PacksUi.catalogOpen = catalog
                        if (catalog == true) PacksUi.fitRequest++
                    }
                    Response.json(mapOf("visible" to show, "catalogOpen" to app.map.onMain { PacksUi.catalogOpen }, "firstVisibleItem" to PacksUi.firstVisibleItem, "localRows" to PacksUi.localRows)) // T57
                }
                // T53: the server's catalog
                r.method == "GET" && r.path == "/packs/catalog" -> {
                    val c = m.catalogClient
                    val server = r.query["server"]
                    val error = runCatching {
                        if (r.query["refresh"] == "1" || server != null || c.catalog == null) c.refresh(server) else c.current()
                    }.exceptionOrNull()
                    val body = c.json()
                    Response.json(if (error != null) body + mapOf("error" to (error.message ?: error.javaClass.simpleName)) else body, if (error != null && c.catalog == null) 502 else 200)
                }
                r.method == "POST" && r.path == "/packs/catalog/refresh" -> { // T57
                    val c = m.catalogClient
                    val error = runCatching { c.refresh(r.query["server"]) }.exceptionOrNull()
                    app.map.onMain { PacksUi.catalogRefresh++ }
                    val body = c.json() + mapOf("refreshed" to (error == null), "fetchedAt" to c.fetchedAt)
                    Response.json(if (error != null) body + mapOf("error" to (error.message ?: error.javaClass.simpleName)) else body, if (error != null) 502 else 200)
                }
                r.method == "POST" && r.path == "/packs/catalog/download" -> {
                    val id = r.query["id"] ?: r.query["name"] ?: return bad("id required")
                    Response.json(m.catalogClient.download(id, activate = r.query["activate"] == "true").json())
                }
                r.method == "GET" && r.path == "/packs/update" -> {
                    val rows = m.catalogClient.updates()
                    Response.json(mapOf("count" to rows.size, "updates" to rows.map { it.json() }))
                }
                r.method == "POST" && r.path == "/packs/update" -> Response.json(m.catalogClient.update(r.query["name"] ?: return bad("name required")).json())
                r.method == "DELETE" -> packPath.matchEntire(r.path)?.let { Response.json(m.delete(it.groupValues[1])) } ?: Response.notFound()
                else -> Response.notFound()
            }
        } catch (e: NoSuchElementException) {
            Response.json(mapOf("error" to e.message), 404)
        } catch (e: IllegalArgumentException) {
            bad(e.message ?: "bad request")
        } catch (e: java.io.IOException) {
            Response.json(mapOf("error" to (e.message ?: e.javaClass.simpleName)), 502) // T53: the catalog server
        }
    }

    private fun bad(message: String) = Response.json(mapOf("error" to message), 400)
}
