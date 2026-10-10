package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.share.QrCode
import io.github.jasondorjeshort.dystopo.app.share.ShareClient
import io.github.jasondorjeshort.dystopo.app.share.ShareSession
import io.github.jasondorjeshort.dystopo.app.share.ShareUi

/**
 * Debug routes for phone-to-phone sync (T54), consulted by [Routes] (debug builds only, loopback server):
 * - `POST /share/start`: opens the Share screen and starts the LAN listener and NSD; JSON as `/share/status`.
 * - `POST /share/stop`: closes the screen; the listener's port is closed when it answers.
 * - `GET /share/status`: listening, port, code, addresses, share link, refused codes, sync calls served,
 *   NSD state (announced name, discovering, resolves) and every service discovered (self included),
 *   the last incoming push and the last connect.
 * - `POST /share/connect?addr=<host[:port]|share link>&code=<6 digits>`: one sync with that phone
 *   (code check, then pull, merge, push). JSON as `POST /sync`; a wrong code is a 403 with `wrongCode`.
 * - `GET /share/qr`: the share link and the QR matrix (`rows`, `#` dark) the screen draws.
 */
object ShareRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/share" && !r.path.startsWith("/share/")) return null
        val session = ShareSession.of(app)
        return try {
            when (r.method to r.path) {
                "POST" to "/share/start" -> {
                    app.map.onMain { ShareUi.visible = true } // Compose state: created and written on the main thread
                    Response.json(session.start())
                }
                "POST" to "/share/stop" -> {
                    app.map.onMain { ShareUi.visible = false }
                    Response.json(session.stop())
                }
                "GET" to "/share/status", "GET" to "/share" -> Response.json(session.status())
                "POST" to "/share/connect" -> {
                    val addr = r.query["addr"] ?: return Response.json(mapOf("error" to "pass addr=<host[:port]> and code="), 400)
                    Response.json(session.connect(addr, r.query["code"]))
                }
                "GET" to "/share/qr" -> {
                    val uri = session.shareUri() ?: return Response.json(mapOf("error" to "not sharing: POST /share/start"), 409)
                    val qr = QrCode.encode(uri)
                    Response.json(mapOf("uri" to uri, "version" to qr.version, "mask" to qr.mask, "size" to qr.size, "rows" to qr.rows()))
                }
                else -> Response.json(mapOf("error" to "POST /share/start, POST /share/stop, GET /share/status, POST /share/connect?addr=&code=, GET /share/qr"), 404)
            }
        } catch (e: ShareClient.WrongCodeException) {
            Response.json(mapOf("ok" to false, "wrongCode" to true, "error" to e.message), 403)
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to e.message), 400)
        } catch (e: Exception) {
            Response.json(mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"), 502)
        }
    }
}
