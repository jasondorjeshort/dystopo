package io.github.jasondorjeshort.dystopo.app.about

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.attribution.Credits
import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import io.github.jasondorjeshort.dystopo.attribution.PackMetadata

/** The app's credits for the packs it has open: what the map line, the About screen and `GET /attribution` show. */
object AppCredits {
    fun compose(packs: PackSet, online: List<PackMetadata> = emptyList()): Credits = PackAttribution.compose(
        // T41: every pack on the map, then what the map shows from the server (online mode, T38).
        packs.osmPacks.map { PackMetadata("osm", it.file.name, it.metadata) } +
            packs.demPacks.map { PackMetadata("dem", it.file.name, it.metadata) } + online,
        // Labels are drawn with the Noto Sans glyphs only when they are installed next to the packs.
        appSourceIds = if (packs.glyphDir != null) listOf(PackAttribution.GLYPH_FONT_ID) else emptyList(),
    )

    fun compose(app: DystopoApp = DystopoApp.instance): Credits =
        compose(app.tiles.packs, runCatching { io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).credits() }.getOrDefault(emptyList()))

    /** First `href` in a registry HTML credit: the source's home page, for a link on the About screen. */
    fun homePage(attributionHtml: String): String? = Regex("href=\"([^\"]+)\"").find(attributionHtml)?.groupValues?.get(1)
}
