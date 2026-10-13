package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.share.QrCode
import io.github.jasondorjeshort.dystopo.app.share.QrDecoder
import io.github.jasondorjeshort.dystopo.app.share.ScanUi
import io.github.jasondorjeshort.dystopo.app.share.ShareLink
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
 * - `POST /share/connect?addr=<host[:port]|share link>&code=<share code>`: one sync with that phone (T72: 16 characters, the link encrypted)
 *   (code check, then pull, merge, push). JSON as `POST /sync`; a wrong code is a 403 with `wrongCode`.
 * - `GET /share/qr`: the share link and the QR matrix (`rows`, `#` dark) the screen draws.
 * T57:
 * - `POST /share/connect` takes `&page=N` (operations per page each way, default 2000; 0: one bundle) and
 *   answers with the clock check against the other phone (`clock`).
 * - `POST /share/scan` with an image body (PNG or JPEG, e.g. a screenshot of the other phone's Share screen):
 *   decodes it with the scanner's decoder; a share link fills the Share screen (source `scan`) and opens it.
 *   JSON: `text`, `version`, `level`, `corrected`, `ms`, `prefill`; 422 with the decoder's progress when no code.
 * - `GET /share/scan`: the camera scanner's state (frames, reads, last text); `POST /share/scan/ui?open=1|0`.
 * - `GET /share/status` adds `prefill` (the fields a link or scan filled).
 */
object ShareRoutes {
    /** T57: decode a posted image as the camera scanner would. */
    private fun scan(app: DystopoApp, r: Request): Response {
        if (r.body.isEmpty()) return Response.json(mapOf("error" to "POST an image (PNG or JPEG)"), 400)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(r.body, 0, r.body.size) ?: return Response.json(mapOf("error" to "not an image"), 400)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val res = QrDecoder.decode(QrDecoder.luminance(px), bmp.width, bmp.height)
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        val size = listOf(bmp.width, bmp.height)
        bmp.recycle()
        res ?: return Response.json(mapOf("found" to false, "ms" to ms, "size" to size, "miss" to QrDecoder.lastMiss?.toString()), 422)
        val opened = app.map.onMain { ShareLink.open(res.text, "scan") }
        return Response.json(linkedMapOf(
            "found" to true, "text" to res.text, "version" to res.version, "level" to res.level.toString(), "corrected" to res.corrected,
            "finders" to res.finders, "threshold" to res.threshold, "ms" to ms, "size" to size, "shareLink" to opened,
            "prefill" to app.map.onMain { ShareUi.json() },
        ))
    }

    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/share" && !r.path.startsWith("/share/")) return null
        val session = ShareSession.of(app)
        return try {
            io.github.jasondorjeshort.dystopo.app.share.ShareRelayRoutes.handle(app, r)?.let { return it } // T72: /share/relay/* (sync via the server relay)
            io.github.jasondorjeshort.dystopo.app.share.PeerRoutes.handle(app, r)?.let { return it } // T78: /share/peers/* (peer phones as map sources)
            when (r.method to r.path) {
                "POST" to "/share/start" -> {
                    app.map.onMain { ShareUi.visible = true } // Compose state: created and written on the main thread
                    Response.json(session.start())
                }
                "POST" to "/share/stop" -> {
                    app.map.onMain { ShareUi.visible = false }
                    Response.json(session.stop())
                }
                "GET" to "/share/status", "GET" to "/share" -> Response.json(session.status() + mapOf("prefill" to app.map.onMain { ShareUi.json() }))
                "POST" to "/share/scan" -> scan(app, r)
                "GET" to "/share/scan" -> Response.json(ScanUi.json())
                "POST" to "/share/scan/ui" -> {
                    val open = r.query["open"] != "0"
                    app.map.onMain { ScanUi.visible = open }
                    Response.json(ScanUi.json() + mapOf("visible" to open))
                }
                "POST" to "/share/connect" -> {
                    val addr = r.query["addr"] ?: return Response.json(mapOf("error" to "pass addr=<host[:port]> and code="), 400)
                    val page = r.query["page"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return Response.json(mapOf("error" to "page must be a whole number >= 0"), 400) }
                    Response.json(session.connect(addr, r.query["code"], page ?: io.github.jasondorjeshort.dystopo.sync.Sync.DEFAULT_PAGE_OPS))
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
        } catch (e: java.net.SocketTimeoutException) {
            Response.json(mapOf("error" to "timeout: ${e.message}"), 504)
        } catch (e: java.io.IOException) {
            Response.json(mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"), 502)
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to e.message), 400)
        } catch (e: Exception) {
            Response.json(mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"), 502)
        }
    }
}
