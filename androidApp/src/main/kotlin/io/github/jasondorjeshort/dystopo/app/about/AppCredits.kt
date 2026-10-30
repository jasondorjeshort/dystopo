package io.github.jasondorjeshort.dystopo.app.about

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.MapViewport
import io.github.jasondorjeshort.dystopo.app.Pack
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.attribution.Credits
import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import io.github.jasondorjeshort.dystopo.attribution.PackMetadata
import io.github.jasondorjeshort.dystopo.boundaries.Countries

/** The app's credits for the packs it has open: what the map line, the About screen and `GET /attribution` show. */
object AppCredits {
    /**
     * T141: registry ids of the data the app itself ships, credited whenever it runs (as used by [BUNDLED_USE]):
     * `natural-earth` for the bundled country set ([Countries]) and the Norway border mask.
     */
    val BUNDLED_SOURCE_IDS: List<String> = listOf(Countries.REGISTRY_ID)

    /**
     * [view] (T49): only the packs whose box overlaps it are credited (the map line); null credits every
     * pack (the About screen). When no pack overlaps the view and nothing comes from the server, every
     * pack is credited, as before. [bundled]: registry ids of the bundled resources ([BUNDLED_SOURCE_IDS]).
     */
    fun compose(
        packs: PackSet,
        online: List<PackMetadata> = emptyList(),
        view: PackBounds? = null,
        bundled: List<String> = BUNDLED_SOURCE_IDS,
    ): Credits {
        fun inView(p: Pack): Boolean {
            val h = p.header
            return view == null || MapViewport.overlaps(PackBounds(h.minLon, h.minLat, h.maxLon, h.maxLat), view)
        }
        var osm = packs.osmPacks.filter(::inView)
        var dem = packs.demPacks.filter(::inView)
        if (osm.isEmpty() && dem.isEmpty() && online.isEmpty()) {
            osm = packs.osmPacks
            dem = packs.demPacks
        }
        val credits = PackAttribution.compose(
            // T41: every pack on the map, then what the map shows from the server (online mode, T38).
            osm.map { PackMetadata("osm", it.file.name, it.metadata) } +
                dem.map { PackMetadata("dem", it.file.name, it.metadata) } + online,
            // Labels are drawn with the Noto Sans glyphs only when they are installed next to the packs.
            appSourceIds = (if (packs.glyphDir != null) listOf(PackAttribution.GLYPH_FONT_ID) else emptyList()) + bundled,
        )
        // The composer credits every app source as used by `app` (the label font); a bundled resource is [BUNDLED_USE].
        return credits.copy(
            sources = credits.sources.map { c ->
                if (c.source.id in bundled && c.source.id != PackAttribution.GLYPH_FONT_ID) c.copy(usedBy = c.usedBy.map { if (it == "app") BUNDLED_USE else it }) else c
            },
        )
    }

    /** `usedBy` of a source credited for a resource the app ships ([BUNDLED_SOURCE_IDS]). */
    const val BUNDLED_USE = "bundled"

    fun compose(app: DystopoApp = DystopoApp.instance, view: PackBounds? = null): Credits =
        compose(app.tiles.packs, runCatching { io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).credits(all = view == null) }.getOrDefault(emptyList()), view) // T80: the About screen lists every source the phone reads

    /** T49: the map's credit line, for the packs in the current view ([MapViewport]). */
    fun mapLine(app: DystopoApp = DystopoApp.instance): Credits = compose(app, MapViewport.current) // T162: read off the main thread (`GET /attribution`)

    /** First `href` in a registry HTML credit: the source's home page, for a link on the About screen. */
    fun homePage(attributionHtml: String): String? = Regex("href=\"([^\"]+)\"").find(attributionHtml)?.groupValues?.get(1)
}
