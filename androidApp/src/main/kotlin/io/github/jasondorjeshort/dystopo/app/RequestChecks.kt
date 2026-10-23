package io.github.jasondorjeshort.dystopo.app

/** Input checks for the loopback server's routes; pure functions so JVM unit tests cover them. */
object RequestChecks {
    /** Deepest tile zoom any route accepts; MapLibre never asks beyond this. */
    const val MAX_TILE_ZOOM = 24

    /**
     * Parses tile coordinates from path segments (which may be negative or have many digits) and
     * checks them: 0 <= z <= [MAX_TILE_ZOOM] and 0 <= x, y < 2^z. Returns null and the reason when invalid.
     */
    fun tile(z: String, x: String, y: String): Pair<IntArray?, String?> {
        val zz = z.toIntOrNull()
        val xx = x.toLongOrNull()
        val yy = y.toLongOrNull()
        if (zz == null || xx == null || yy == null) return null to "tile coordinates out of range"
        if (zz !in 0..MAX_TILE_ZOOM) return null to "zoom $zz outside 0..$MAX_TILE_ZOOM"
        val n = 1L shl zz
        if (xx !in 0 until n || yy !in 0 until n) return null to "tile $zz/$xx/$yy outside 0..${n - 1}"
        return intArrayOf(zz, xx.toInt(), yy.toInt()) to null
    }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "ndash" to "–", "mdash" to "—",
        "middot" to "·", "hellip" to "…", "deg" to "°",
    )
    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val tag = Regex("<[^>]*>")

    /**
     * Plain text from an HTML attribution string (PMTiles `attribution` metadata is HTML, e.g.
     * `<a href=...>&copy; OpenStreetMap</a>`): tags removed, entities decoded, whitespace collapsed.
     * Unknown entities stay as written.
     */
    fun htmlToText(html: String): String = tag.replace(html, "").let { text ->
        entity.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::codePoint)
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint)
                else -> named[body]
            } ?: m.value
        }
    }.replace(Regex("\\s+"), " ").trim()

    /**
     * T123: debug routes that need the activity on screen (its window, the map's frames, Compose layout), which the
     * background guard in [Routes] answers 503 while it is stopped: `/screenshot`, `/features`, `/features/text`,
     * `/labels`, everything under `/help`, and `POST /attribution/open` (Android does not start an activity for an
     * app in the background, so it said `opened` and nothing opened). `GET /attribution` reads state and answers in
     * the background; `POST /attribution/close` must answer while the About screen itself has the map stopped.
     * T128: the five UI routes that open or drive a sheet (`POST /settings/ui`, `/settings/open`, `/search/ui`,
     * `/storage/ui`, `/areas/ui`) set the activity's Compose state, which nothing draws while it is stopped, so they
     * answered `open` and nothing showed (a script then screenshotted or tapped a sheet that was not there); their
     * `GET` siblings (`/settings/layout`, `/storage`, `/areas`, ...) read state and stay open.
     */
    fun needsActivity(method: String, path: String): Boolean = when {
        path == "/screenshot" || path == "/features" || path == "/features/text" || path == "/labels" -> true
        path == "/help" || path.startsWith("/help/") -> true
        method == "POST" && path == "/attribution/open" -> true
        method == "POST" && path in uiRoutes -> true
        else -> false
    }

    /** T128: the sheet-driving UI routes, `POST` only ([needsActivity]). */
    val uiRoutes = setOf("/settings/ui", "/settings/open", "/search/ui", "/storage/ui", "/areas/ui")

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null
}
