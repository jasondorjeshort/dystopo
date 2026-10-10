package io.github.jasondorjeshort.dystopo.app.firstrun

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * T79: where the app may talk plain HTTP. HTTPS is always allowed and preferred; plain `http://` only to
 * hosts on the device itself or on a home network, so a server at home works without certificates while
 * nothing travels unencrypted across the internet:
 * - `localhost` and loopback addresses (127/8, `::1`);
 * - private IPv4 ranges: 10/8, 172.16/12, 192.168/16, and link-local 169.254/16;
 * - IPv6 unique-local (fc00::/7) and link-local (fe80::/10) literals;
 * - mDNS names ending in `.local` (a home server announced by name).
 *
 * Android's network security config cannot express address ranges (its `<domain>` entries are literal
 * names), so `res/xml/network_security_config.xml` permits cleartext in general and this policy is the
 * gate: [open] checks every HTTP connection the app opens through `HttpURLConnection` (server tiles, catalog,
 * region builds, pack downloads, sync, phone-to-phone sync links), and the server setting is refused up front
 * ([problem]) with a message the Settings sheet shows. MapLibre only ever talks to the app's own loopback
 * server. Not covered: the Share screen's LAN client (`share/LanHttp.kt`) speaks HTTP over a raw socket to the
 * other phone; its payload is ChaCha20-Poly1305-encrypted end to end under the share code (T72) on any network.
 *
 * Redirects: `HttpURLConnection` follows a redirect by itself unless the protocol changes, so a private http
 * server answering 302 with a public http address would be followed past the check. [open] turns automatic
 * redirects off; a caller that wants them follows them through [redirectTarget], which checks each
 * `Location` with [problem] (the pack downloader does; the Dystopo server's routes never redirect).
 */
object CleartextPolicy {
    /** Why [url] may not be used, or null when it may (https anywhere, http only to a private host). */
    fun problem(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "not a URL: $url"
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host ?: return "no host in $url"
        return when (scheme) {
            "https" -> null
            "http" -> if (isPrivateHost(host)) null else
                "plain http is only allowed to a home-network address (10.x, 172.16-31.x, 192.168.x, 169.254.x, a .local name or this device); use https://$host${portPart(uri)}"
            else -> "only http and https are supported (got ${uri.scheme})"
        }
    }

    /** Opens [url] after checking [problem]; throws [IOException] with the reason otherwise. */
    fun open(url: String): HttpURLConnection {
        problem(url)?.let { throw IOException(it) }
        return (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false }
    }

    /**
     * Where a redirect response on [conn] points, after checking it with [problem] (throws [IOException] when
     * it is refused or a 3xx has no `Location`); null when the response is not a redirect. Reads the status.
     */
    fun redirectTarget(conn: HttpURLConnection): String? {
        val status = conn.responseCode
        if (status !in REDIRECTS) return null
        val location = conn.getHeaderField("Location") ?: throw IOException("HTTP $status without a Location")
        val target = try {
            conn.url.toURI().resolve(location.trim()).toString()
        } catch (e: Exception) {
            throw IOException("bad redirect Location '$location'")
        }
        problem(target)?.let { throw IOException("redirect to $target refused: $it") }
        return target
    }

    /** Redirects a caller follows per request at most. */
    const val MAX_REDIRECTS = 5
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** True for loopback, private and link-local address literals and `.local` / `localhost` names. */
    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim().trimStart('[').trimEnd(']').trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local") && host.length > ".local".length) return true
        ipv4(host)?.let { (a, b) ->
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in host) {
            val h = host.substringBefore('%') // zone id
            if (h == "::1") return true
            val first = h.substringBefore(':').ifEmpty { return false }.toIntOrNull(16) ?: return false
            if (h.substringBefore(':').length > 4) return false
            return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
        }
        return false
    }

    /** The first two octets of a dotted IPv4 literal, or null when [host] is not one. */
    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        // Leading zeros are refused: "010.0.0.1" is octal (8.0.0.1) to some resolvers and decimal to others.
        val n = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit) || (p.length > 1 && p[0] == '0')) return null else p.toInt() }
        if (n.any { it > 255 }) return null
        return n[0] to n[1]
    }

    private fun portPart(uri: URI) = if (uri.port > 0) ":${uri.port}" else ""
}
