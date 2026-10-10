package io.github.jasondorjeshort.dystopo.app.about

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.attribution.Credits
import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import io.github.jasondorjeshort.dystopo.attribution.PackMetadata

/** The app's credits for the packs it has open: what the map line, the About screen and `GET /attribution` show. */
object AppCredits {
    fun compose(packs: PackSet): Credits = PackAttribution.compose(
        listOfNotNull(
            packs.osm?.let { PackMetadata("osm", it.file.name, it.metadata) },
            packs.dem?.let { PackMetadata("dem", it.file.name, it.metadata) },
        ),
        // Labels are drawn with the Noto Sans glyphs only when they are installed next to the packs.
        appSourceIds = if (packs.glyphDir != null) listOf(PackAttribution.GLYPH_FONT_ID) else emptyList(),
    )

    fun compose(app: DystopoApp = DystopoApp.instance): Credits = compose(app.tiles.packs)

    /** First `href` in a registry HTML credit: the source's home page, for a link on the About screen. */
    fun homePage(attributionHtml: String): String? = Regex("href=\"([^\"]+)\"").find(attributionHtml)?.groupValues?.get(1)
}
