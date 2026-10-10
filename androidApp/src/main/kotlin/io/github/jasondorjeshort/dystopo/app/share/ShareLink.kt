package io.github.jasondorjeshort.dystopo.app.share

import android.content.Intent

/**
 * T57: a tapped `dystopo://share?addr=<host:port>&code=<6 digits>` link (the text of the Share screen's QR
 * code; also what the in-app scanner reads) opens the Share screen with the address and code filled in.
 * Nothing syncs until the user taps Sync there: a link from someone else must not push this phone's tracks
 * to an address it names without the user seeing where they go.
 *
 *     adb shell am start -a android.intent.action.VIEW -d "'dystopo://share?addr=10.0.2.2:8841&code=123456'"
 */
object ShareLink {
    const val SCHEME = "dystopo"
    const val HOST = "share"

    /** Handles [intent] when it carries a share link; true when it did. Main thread. */
    fun handle(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != SCHEME || uri.host != HOST) return false
        return open(uri.toString(), "link")
    }

    /** Fills the Share screen from [link] and opens it; false (and nothing changes) when [link] is not a valid share link. */
    fun open(link: String, source: String): Boolean {
        val (addr, code) = parse(link) ?: return false
        ShareUi.prefill(addr, code, source)
        ShareUi.visible = true
        return true
    }

    /** `host:port` and the code of a share link, or null when it is not one (bad address, code not 6 digits). Pure, unit-tested. */
    fun parse(link: String): Pair<String, String>? {
        if (!link.trim().startsWith("$SCHEME://$HOST")) return null
        val t = runCatching { ShareClient.parse(link) }.getOrNull() ?: return null
        val code = t.code?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
        return t.baseUrl.removePrefix("http://") to code
    }
}
