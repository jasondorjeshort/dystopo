package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.floor

/**
 * T78 debug routes for peer sources (debug builds only, loopback server; consulted from `ShareRoutes`). The sharing side
 * needs nothing beyond the Share screen being open (`POST /share/start`); `GET /share/status` has `serving` (who fetched
 * what from this phone) and `peerSources`.
 * - `GET /share/peers`: the phones in use as sources (address, name, catalog packs, areas, `discoveredAt`, `catalogAt`,
 *   `catalogAfterDiscoveryMs`; T90: `state` `connected`, `saved` or `unreachable`, `lastSeenAt`, `retries`, `nextRetryAt`),
 *   the known codes' count, auto tries, address overrides, `restoredPeers` and `saves` (T90: peers are kept in
 *   `files/share/peers.json` across restarts), and the fetcher's per-source counters.
 * - `POST /share/peers/refresh?peer=`: T90: a restored peer is added again (hello, catalog), any other refreshed.
 * - `POST /share/peers/add?addr=<host[:port]|share link>&code=<code>`: adds that phone as a source (hello, catalog, areas).
 * - `POST /share/peers/code?code=<code>`: remembers a code; every phone discovery resolves from then on is tried with it
 *   once (the trip's code). `DELETE /share/peers/code` forgets them all.
 * - `POST /share/peers/via?from=<host:port>&to=<host:port>`: connect to `to` whenever discovery reports `from` (emulators:
 *   a peer's 10.0.2.x address through an adb forward on the host); without `to` the override goes.
 * - `DELETE /share/peers?peer=`: stop using it (and forget it: the Share screen's Forget).
 * - `GET /share/peers/catalog?peer=`: the peer's catalog document as it sent it.
 * - `POST /share/peers/download?peer=&pack=<id>[&activate=1]`: downloads that pack from the peer (job as `GET /packs/downloads`).
 * - `POST /share/peers/area?peer=&id=<area id>`: downloads that area (a pack, or a corridor's tiles through the fetcher).
 * - `POST /share/peers/bench?peer=[&lat=&lon=][&z=13][&n=9]`: the derived-tile measurement: the same contour tiles fetched
 *   from the peer (cold: it makes them; warm: from its cache) and regenerated here (contour cache cleared, one warm-up tile
 *   first); needs the same pack on both phones (the full derived key must match).
 * - `POST /share/peers/piece?peer=&file=<archive>&from=<byte>&len=<bytes>`: T90, one piece of the peer's archive as a download
 *   asks for it (at most 4 MiB), with its SHA-256, milliseconds and bytes per second; a reply cut short is a 500 with the error.
 * - `GET /share/peers/testframe`: a sealed `GET /share/hello` frame under this phone's current share code (base64), for
 *   `tools/android-probe.py --share` (sent twice: 200, then 409 as a replay). Debug builds only, like every route here.
 * `peer` is the address, `peer://` URL, the phone's name or its NSD name; it may be left out when there is one peer.
 */
object PeerRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/share/peers" && !r.path.startsWith("/share/peers/")) return null
        fun peer(): PeerSources.Peer = r.query["peer"]?.let { PeerSources.find(it) ?: throw NoSuchElementException("no peer '$it'") }
            ?: PeerSources.all().singleOrNull() ?: throw IllegalArgumentException("pass peer= (${PeerSources.all().size} peers)")
        return when (r.method to r.path) {
            "GET" to "/share/peers" -> Response.json(PeerSources.json() + mapOf("files" to PeerFiles.json(), "fetcher" to OnlineService.get(app).fetcher?.let { f -> mapOf("sources" to f.json()["sources"]) }))
            "POST" to "/share/peers/add" -> {
                val addr = r.query["addr"] ?: return Response.json(mapOf("error" to "pass addr=<host[:port]> and code="), 400)
                Response.json(PeerSources.add(addr, r.query["code"]).json())
            }
            "POST" to "/share/peers/code" -> {
                val c = PeerSources.learnCode(r.query["code"] ?: return Response.json(mapOf("error" to "pass code="), 400))
                Response.json(mapOf("known" to PeerSources.knownCodes.size, "code" to c.take(4) + "..."))
            }
            "DELETE" to "/share/peers/code" -> { PeerSources.forgetCodes(); Response.json(mapOf("known" to 0)) }
            "POST" to "/share/peers/via" -> {
                val from = r.query["from"] ?: return Response.json(mapOf("error" to "pass from=<host:port>[&to=<host:port>]"), 400)
                PeerSources.setVia(from, r.query["to"])
                Response.json(PeerSources.json())
            }
            "POST" to "/share/peers/refresh" -> Response.json(PeerSources.reach(peer()).json())
            "DELETE" to "/share/peers" -> Response.json(mapOf("removed" to PeerSources.remove(r.query["peer"] ?: peer().addr)))
            "GET" to "/share/peers/catalog" -> Response.json(PeerSources.refresh(peer()).catalog?.json())
            "POST" to "/share/peers/download" -> {
                val pack = r.query["pack"] ?: return Response.json(mapOf("error" to "pass pack=<id>"), 400)
                Response.json(PeerSources.download(peer().addr, pack, activate = r.query["activate"] == "1").json())
            }
            "POST" to "/share/peers/area" -> Response.json(PeerSources.area(peer().addr, r.query["id"] ?: return Response.json(mapOf("error" to "pass id=<area id>"), 400)))
            "POST" to "/share/peers/bench" -> Response.json(bench(app, peer(), r.query))
            "GET" to "/share/peers/testframe" -> {
                val c = ShareSession.of(app).code ?: return Response.json(mapOf("error" to "not sharing: POST /share/start"), 409)
                val frame = io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink.sealRequest(c, io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink.Request("GET", "/share/hello", ByteArray(0)), ShareGate::randomBytes)
                Response.json(mapOf("frame" to java.util.Base64.getEncoder().encodeToString(frame), "port" to ShareSession.of(app).port))
            }
            "POST" to "/share/peers/piece" -> {
                val file = r.query["file"] ?: return Response.json(mapOf("error" to "pass file=<archive name on the peer>&from=&len="), 400)
                val len = (r.query["len"]?.toIntOrNull() ?: PeerFiles.CHUNK).coerceIn(1, PeerServe.MAX_CHUNK)
                Response.json(PeerFiles.fetchPiece(peer().addr, file, r.query["from"]?.toLongOrNull() ?: 0L, len))
            }
            "POST" to "/share/peers/sealbench" -> Response.json(sealBench(r.query["mb"]?.toIntOrNull() ?: 4))
            else -> Response.json(mapOf("error" to "GET /share/peers, POST /share/peers/add|code|via|refresh|download|area|bench|piece, GET /share/peers/catalog"), 404)
        }
    }

    /** The derived-tile measurement (see the class comment). */
    private fun bench(app: DystopoApp, p: PeerSources.Peer, q: Map<String, String>): Map<String, Any?> {
        val lat = q["lat"]?.toDouble() ?: 46.786
        val lon = q["lon"]?.toDouble() ?: -121.736
        val z = q["z"]?.toInt() ?: 13
        val n = (q["n"]?.toInt() ?: 9).coerceIn(1, 64)
        val side = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt()
        val cx = floor(WebMercator.lonToX(lon, z)).toInt()
        val cy = floor(WebMercator.latToY(lat, z)).toInt()
        val tiles = (0 until side * side).map { i -> Triple(z, cx - side / 2 + i % side, cy - side / 2 + i / side) }.take(n)
        val keys = tiles.map { (tz, tx, ty) -> PeerServe.derivedKey(app, tz, tx, ty) }
        if (keys.any { it == null }) return mapOf("error" to "no DEM pack under every tile here; download the pack first", "tiles" to tiles.size)

        fun transfer(): List<Map<String, Any?>> = tiles.mapIndexed { i, (tz, tx, ty) ->
            val t0 = System.nanoTime()
            val r = PeerSources.request(p.addr, p.code, "GET", "/peer/contours/$tz/$tx/$ty.pbf?key=${keys[i]}")
            val ms = (System.nanoTime() - t0) / 1e6
            val us = r.contentType.substringAfter("us=", "").toLongOrNull()
            mapOf("tile" to "$tz/$tx/$ty", "status" to r.status, "ms" to round1(ms), "peerMs" to us?.let { round1(it / 1000.0) }, "bytes" to r.body.size)
        }
        val cold = transfer()
        val warm = transfer()
        // Regeneration here: the cache cleared, one neighbouring tile made first (warms the DEM decode), then the same tiles.
        app.tiles.clearContourCache()
        val warmup = run { val t0 = System.nanoTime(); app.tiles.contourTile(z, cx + side, cy); (System.nanoTime() - t0) / 1e6 }
        val regen = tiles.map { (tz, tx, ty) ->
            val t0 = System.nanoTime()
            val t = app.tiles.contourTile(tz, tx, ty)
            mapOf("tile" to "$tz/$tx/$ty", "status" to t.status, "ms" to round1((System.nanoTime() - t0) / 1e6), "bytes" to t.body.size)
        }
        fun stats(l: List<Map<String, Any?>>, k: String = "ms"): Map<String, Any?> {
            val v = l.mapNotNull { (it[k] as? Number)?.toDouble() }.sorted()
            if (v.isEmpty()) return emptyMap()
            return mapOf("mean" to round1(v.average()), "median" to round1(v[v.size / 2]), "min" to v.first(), "max" to v.last())
        }
        return linkedMapOf(
            "peer" to p.name, "zoom" to z, "tiles" to tiles.size, "keyMatched" to (cold.all { it["status"] == 200 }), "key" to keys.first(),
            "transferCold" to stats(cold), "peerMakeCold" to stats(cold, "peerMs"),
            "transferWarm" to stats(warm), "peerWarm" to stats(warm, "peerMs"),
            "regenerate" to stats(regen), "regenerateWarmupMs" to round1(warmup),
            "bytesMean" to regen.mapNotNull { (it["bytes"] as? Number)?.toLong() }.average().toLong(),
            "detail" to mapOf("cold" to cold, "warm" to warm, "regenerate" to regen),
        )
    }

    /** How fast this phone seals and opens a reply of [mb] MB (the pure-Kotlin ChaCha20-Poly1305 of the T72 link). */
    private fun sealBench(mb: Int): Map<String, Any?> {
        val code = ShareGate.randomCode()
        val body = ShareGate.randomBytes(mb.coerceIn(1, 16) shl 20)
        val req = io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink.sealRequest(code, io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink.Request("GET", "/x", ByteArray(0)), ShareGate::randomBytes)
        val t0 = System.nanoTime()
        val sealed = io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink.sealResponse(code, req, io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink.Response(206, "application/octet-stream", body), ShareGate::randomBytes)
        val t1 = System.nanoTime()
        io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink.openResponse(code, req, sealed)
        val t2 = System.nanoTime()
        val sealMs = (t1 - t0) / 1e6
        val openMs = (t2 - t1) / 1e6
        return mapOf("bytes" to body.size, "sealMs" to round1(sealMs), "openMs" to round1(openMs), "sealMBps" to round1(body.size / 1e6 / (sealMs / 1000)), "openMBps" to round1(body.size / 1e6 / (openMs / 1000)))
    }

    private fun round1(d: Double) = Math.round(d * 10) / 10.0
}
