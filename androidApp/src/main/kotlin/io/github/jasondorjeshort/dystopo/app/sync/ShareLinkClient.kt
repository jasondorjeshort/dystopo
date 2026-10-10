package io.github.jasondorjeshort.dystopo.app.sync

import android.content.Context
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * T67: trip share links from the phone. A link lives on the sync peer (the `server` module, or
 * `devtool serve`): `POST <peer>/sync/share?route=<id>` with this device's sync token makes it (the
 * server serves the route from this user's replica there, so the route is synced first), `DELETE
 * <peer>/sync/share/<token>` revokes it. The last link made for each route is remembered (prefs
 * `dystopo-share-links`) so the trip card can revoke it; the server returns the same link when asked
 * again for the same route.
 */
class ShareLinkClient private constructor(private val app: DystopoApp) {
    private val sync = AppSync.of(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The peer links are made on: the default sync peer, which must be an HTTP(S) server. */
    private fun peer(): String {
        val p = sync.defaultPeer.trimEnd('/')
        require(p.isNotBlank()) { "Share link needs a sync server: set one in Sync status (POST /sync/settings?peer=)" }
        require(p.startsWith("http://") || p.startsWith("https://")) { "Share link needs a server as the sync peer, not the file $p" }
        return p
    }

    /**
     * Syncs with the peer (so the server holds the route as it is now), then asks it for the link. Returns
     * the server's answer (`url`, `gpx`, `token`, `existing`, `name`) plus `peer` and `sync` (the sync's result).
     */
    fun create(routeId: String, syncFirst: Boolean = true): Map<String, Any?> {
        val peer = peer()
        val synced = if (syncFirst) sync.syncNow(peer) else null
        val (code, body) = request("POST", "$peer/sync/share?route=" + URLEncoder.encode(routeId, "UTF-8"))
        val o = runCatching { MiniJson.parseObject(body) }.getOrElse { mapOf("error" to body.take(300)) }
        check(code == 200) { "the server refused the share link (HTTP $code): ${o["error"] ?: body.take(200)}" }
        val token = o["token"] as? String ?: error("no token in the server's answer")
        prefs.edit().putString(routeId, MiniJson.encode(mapOf("token" to token, "url" to o["url"], "peer" to peer, "at" to System.currentTimeMillis()))).apply()
        return linkedMapOf<String, Any?>("peer" to peer) + o + mapOf("sync" to synced?.let { linkedMapOf("pulled" to it["pulled"], "pushed" to it["pushed"]) })
    }

    /** The last link made for [routeId] on this device (`token`, `url`, `peer`, `at`), or null. */
    fun last(routeId: String): Map<String, Any?>? = prefs.getString(routeId, null)?.let { runCatching { MiniJson.parseObject(it) }.getOrNull() }

    /** Revokes the last link made for [routeId]; the server's answer (404 when it was already gone). */
    fun revoke(routeId: String): Map<String, Any?> {
        val l = last(routeId) ?: throw NoSuchElementException("no share link made for route $routeId on this device")
        val peer = (l["peer"] as? String) ?: peer()
        val (code, body) = request("DELETE", "$peer/sync/share/${l["token"]}", peer)
        if (code == 200 || code == 404) prefs.edit().remove(routeId).apply()
        return linkedMapOf("status" to code, "revoked" to (code == 200), "token" to l["token"], "body" to runCatching { MiniJson.parseObject(body) }.getOrElse { body.take(300) })
    }

    /** This user's links on the peer (`GET /sync/share`). */
    fun list(): Map<String, Any?> {
        val (code, body) = request("GET", peer() + "/sync/share")
        check(code == 200) { "HTTP $code: ${body.take(200)}" }
        return MiniJson.parseObject(body)
    }

    private fun request(method: String, url: String, peer: String = sync.defaultPeer): Pair<Int, String> {
        val c = io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy.open(url) // T79
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            // The sync token belongs to the default peer; a link remembered for another peer is revoked without it.
            if (sync.syncToken.isNotEmpty() && peer.trimEnd('/') == sync.defaultPeer.trimEnd('/')) c.setRequestProperty("Authorization", "Bearer ${sync.syncToken}")
            if (method == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val code = c.responseCode
            val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            return code to bytes.decodeToString()
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val PREFS = "dystopo-share-links"
        @Volatile private var instance: ShareLinkClient? = null

        fun of(context: Context): ShareLinkClient = instance ?: synchronized(this) {
            instance ?: ShareLinkClient(context.applicationContext as DystopoApp).also { instance = it }
        }
    }
}
