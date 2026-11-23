package io.github.jasondorjeshort.dystopo.packs

/**
 * T341, ADR 0066's first slice of the lookup: which source answers a vector tile, between the offline maps, the built-in world
 * overview, the online tiles kept on the phone and the network. ADR 0066 build task 1 extends this file to every holding and
 * role (the areas' held cells, the DEM, the order `PackPrecedence` and `MergedTerrain` take); here it has the overview and the
 * online members only, which is all the tile path asks of it today (`app/TileService.kt` `osmTile`, `GET /why`).
 *
 * The ranking rule (ADR 0066, decision 2): what the phone holds answers first, by stored depth; the network goes ahead of a
 * holding only where its **stored** maximum zoom is deeper than the holding's, and only under the per-device setting
 * `onlineDetail` ("More detail from online", default on). A tile the phone holds answers at once and the network's fetch runs
 * behind it ([Answer.fetchBehind]); the map's vector source is refreshed once it lands. A map answers a vector tile only at its
 * own zooms (`PackRoleZooms.VECTOR`), so a map that answers is never passed over here: its reach over maps is ADR 0066's build
 * task 2. The world overview stores z0 to z5 (Natural Earth); the online vector sources store to z14 (OpenFreeMap) or a
 * server's declared `osmMaxZoom`; `OnlineTiles.OSM_MAX_ZOOM` is the URL range, which for vector tiles is the same 14.
 *
 * Pure, tested (`HoldingsTest`).
 */
object Holdings {
    /** Who answers the tile now. */
    enum class Source(val word: String) {
        /** An offline map on the phone holds the tile at its own zooms. */
        MAP("map"),
        /** The online tile the phone fetched earlier, kept in its cache. */
        CACHE("cache"),
        /** The network (the online fallback: fetched, else cut from a wider zoom the phone holds, else nothing). */
        NETWORK("network"),
        /** The built-in world overview. */
        OVERVIEW("overview"),
        /** Nothing: no map, no overview at this zoom, no online source. */
        NONE("none"),
    }

    /**
     * The online member: [storedMaxZoom] the deepest zoom its vector source stores, [allowed] whether a fetch may run now (a
     * source set, not offline, not paused by Mobile data or the session limit), [detail] the setting `onlineDetail`, [reason]
     * why it is not allowed (null when it is).
     */
    class Online(val storedMaxZoom: Int, val allowed: Boolean, val detail: Boolean, val reason: String? = null)

    /** The overview member: it stores [minZoom] to [maxZoom] and holds the tile whenever no map answers it there. */
    class Overview(val minZoom: Int, val maxZoom: Int)

    /**
     * The answer: [serves] answers now; [fetchBehind], the network fetches the tile behind it (a deeper online tile, drawn when
     * it lands); [cause] one word for `GET /why` (`map`, `overview`, `overview-online-off`, `overview-not-allowed`,
     * `online-kept`, `online-fetching`, `online`, `no-source`).
     */
    class Answer(val serves: Source, val fetchBehind: Boolean, val cause: String) {
        fun json(): Map<String, Any?> = linkedMapOf("serves" to serves.word, "fetchBehind" to fetchBehind, "cause" to cause)
    }

    /**
     * Who answers vector tile at zoom [z]: [mapAnswers] an offline map holds it at its own zooms; [overview] the world overview
     * (null when the phone has none); [online] the online member (null with no source set: no server and direct reading off);
     * [cached] the online tile is in the phone's cache.
     */
    fun vectorTile(z: Int, mapAnswers: Boolean, overview: Overview?, online: Online?, cached: Boolean): Answer {
        if (mapAnswers) return Answer(Source.MAP, false, "map")
        val overviewHolds = overview != null && z in overview.minZoom..overview.maxZoom
        if (!overviewHolds) {
            return if (online != null) Answer(Source.NETWORK, false, "online") else Answer(Source.NONE, false, "no-source")
        }
        // The overview holds the tile. The online member goes ahead only where it stores deeper, under the setting.
        if (online == null) return Answer(Source.OVERVIEW, false, "overview")
        if (!online.detail) return Answer(Source.OVERVIEW, false, "overview-online-off")
        if (online.storedMaxZoom <= overview!!.maxZoom) return Answer(Source.OVERVIEW, false, "overview")
        if (cached) return Answer(Source.CACHE, false, "online-kept")
        if (!online.allowed) return Answer(Source.OVERVIEW, false, "overview-not-allowed")
        return Answer(Source.OVERVIEW, true, "online-fetching")
    }
}
