package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.dem.MergedTerrain
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.floor

/**
 * ADR 0066's one lookup for what the phone holds here (decision 5), in two parts.
 *
 * **The lookup** (T354, build task 1): [tile] and [point] answer, for one vector or elevation tile ([Role]), what each
 * holding stores there ([Member]: offline maps, downloaded areas' held cells, the tile cache, the world overview), which one
 * serves, which are off (T342), and what the network would add ([Network]). The order is [ORDER]: depth first (decision 1),
 * the map chosen last only the tie between two of equal depth, the outdoors profile ahead for vector tiles, the world overview
 * last. `PackPrecedence.ORDER` and `MergedTerrain.ORDER` are built from the same rule ([depthFirst]), so the tile paths, the
 * terrain read of the sky views and `GET /holdings` rank the maps alike; [pointZoom] is the zoom `MergedTerrain.pointZoom`
 * reads a point at. The network member is reported here, not yet used by any tile path (ADR 0066 build task 2, T356 and
 * T368), and the terrain read keeps it last behind `MergedTerrain`'s `onlineByDepth`, false until T368.
 *
 * **T341's vector rule** ([vectorTile]): which source answers a vector tile at the world overview's zooms, between the
 * offline maps, the overview, the online tiles kept on the phone and the network; the tile path's (`app/TileService.kt`
 * `osmTile`) until T356 moves it onto the lookup.
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
     * The online member: [storedMaxZoom] the deepest zoom its vector source stores, [demMaxZoom] the deepest its elevation
     * source stores (T354; null when unknown: the URL range is not stored detail), [allowed] whether a fetch may run now (a
     * source set, not offline, not paused by Mobile data or the session limit), [detail] the setting `onlineDetail`, [reason]
     * why it is not allowed (null when it is).
     */
    class Online(val storedMaxZoom: Int, val allowed: Boolean, val detail: Boolean, val reason: String? = null, val demMaxZoom: Int? = null) {
        /** The deepest zoom the source stores for [role]. */
        fun storedMax(role: Role): Int? = when (role) {
            Role.VECTOR -> storedMaxZoom
            Role.DEM -> demMaxZoom
        }

        fun json(): Map<String, Any?> = linkedMapOf(
            "vectorMaxZoom" to storedMaxZoom, "demMaxZoom" to demMaxZoom, "allowed" to allowed, "onlineDetail" to detail, "notAllowedBecause" to reason,
        )
    }

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

    // ---- T354: the lookup (ADR 0066 build task 1) ----

    /** What a tile is for: the map's vector tiles or the elevation (DEM) tiles; [zooms] the zoom rule a map answers by. */
    enum class Role(val word: String, val zooms: PackRoleZooms) {
        VECTOR("vector", PackRoleZooms.VECTOR),
        DEM("dem", PackRoleZooms.DEM),
    }

    /** What kind of thing on the phone a [Holding] is; [rank] orders two of equal depth (after the map chosen last). */
    enum class Kind(val word: String, val rank: Int) {
        /** An offline map (a pack): its box, at its archives' zooms. */
        MAP("map", 0),
        /** A downloaded area: its own held cells (not its box), at its zooms. */
        AREA("area", 1),
        /** The online tile cache: the tiles fetched earlier, each where it is. */
        CACHE("cache", 2),
        /** The built-in world overview: always last (T264). */
        OVERVIEW("overview", 3),
    }

    /**
     * What a holding stores for one role: zooms [minZoom] to [maxZoom] (the stored maximum, its depth), inside [bounds] (null:
     * wherever its cells say, the cache); [wholeTiles] it keeps whole upstream tiles at its edges (an OpenFreeMap build).
     */
    class Stored(val minZoom: Int, val maxZoom: Int, val bounds: PackBounds? = null, val wholeTiles: Boolean = false) {
        fun json(): Map<String, Any?> = linkedMapOf("zooms" to listOf(minZoom, maxZoom), "bounds" to bounds?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) })
    }

    /**
     * One thing on the phone the lookup ranks: [name] (a map's or an area's, `cache`, `world`), [kind], what it stores for each
     * role ([vector], [dem]; null: none of that role), its vector [profile], [chosen] the map chosen last (`POST
     * /packs/activate`: the tie between two of equal depth), [off] switched off (T342: listed, holds nothing), and [cells]
     * whether it holds tile z/x/y of a role exactly (an area's held cells, the cache's entries; null: by [Stored.bounds]).
     */
    class Holding(
        val name: String,
        val kind: Kind,
        val vector: Stored?,
        val dem: Stored?,
        val profile: VectorProfile = VectorProfile.UNKNOWN,
        val chosen: Boolean = false,
        val off: Boolean = false,
        val cells: ((role: Role, z: Int, x: Int, y: Int) -> Boolean)? = null,
    ) {
        fun stored(role: Role): Stored? = when (role) {
            Role.VECTOR -> vector
            Role.DEM -> dem
        }

        /** The holding as `PackPrecedence` sees one archive (null without a box for [role]). */
        fun coverage(role: Role): PackCoverage? {
            val s = stored(role) ?: return null
            val b = s.bounds ?: return null
            return PackCoverage(name, s.minZoom, s.maxZoom, b, if (role == Role.VECTOR) profile else VectorProfile.UNKNOWN, chosen, s.wholeTiles)
        }

        fun json(): Map<String, Any?> = linkedMapOf(
            "name" to name, "kind" to kind.word, "vector" to vector?.json(), "dem" to dem?.json(), "profile" to profile.label, "chosen" to chosen, "off" to off,
        )
    }

    /** How a member holds the tile. */
    enum class How(val word: String) {
        /** It stores the tile, all of it. */
        STORES("stores"),
        /** A map's elevation past its stored maximum: made from its deepest tile above (as deep as it is, no deeper). */
        SYNTHESISES("synthesises"),
        /** A map's box covers part of the tile only: it answers when nothing ahead of it holds all of it. */
        EDGE("edge"),
    }

    /**
     * One holding that answers the tile: [depth] its stored maximum for the role (the cache's: the online source's stored
     * maximum, the source its tiles came from), [how] it holds it, [serves] it is the one that answers now.
     */
    class Member(val holding: Holding, val role: Role, val depth: Int, val how: How, val serves: Boolean = false) {
        internal fun serving() = Member(holding, role, depth, how, true)

        fun json(): Map<String, Any?> = linkedMapOf(
            "name" to holding.name, "kind" to holding.kind.word, "depth" to depth, "holds" to how.word, "serves" to serves, "chosen" to holding.chosen,
        ) + (if (role == Role.VECTOR) mapOf("profile" to holding.profile.label) else emptyMap())
    }

    /**
     * What the network would add at the tile: [storedMaxZoom] the online source's stored maximum for the role (null: unknown
     * or no source), [adds] that zoom where it is deeper than what serves (or nothing on the phone holds the tile), else
     * null; [ahead] it would go ahead of what serves under `onlineDetail` (ADR 0066 decision 2; no tile path takes it yet:
     * T356, T368); [allowed], [detail] and [reason] as [Online].
     */
    class Network(val storedMaxZoom: Int?, val adds: Int?, val ahead: Boolean, val allowed: Boolean, val detail: Boolean, val reason: String?) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "storedMaxZoom" to storedMaxZoom, "adds" to adds, "ahead" to ahead, "allowed" to allowed, "onlineDetail" to detail, "notAllowedBecause" to reason,
        )
    }

    /**
     * The lookup's answer for tile [z]/[x]/[y] of [role]: [members], every holding that answers it in [ORDER] (the one that
     * serves flagged), [off] the holdings switched off that would, [network] what the network adds (null with no source set).
     */
    class Here(val role: Role, val z: Int, val x: Int, val y: Int, val members: List<Member>, val off: List<Holding>, val network: Network?) {
        /** The member that answers now: the first that holds all of the tile, else the first that holds part of it; null: none. */
        val serving: Member? get() = members.firstOrNull { it.serves }

        /** The members in the order a reader asks them: the serving one, then the others in [ORDER] (fallbacks where it has no tile). */
        val candidates: List<Member> get() = listOfNotNull(serving) + members.filter { !it.serves }

        fun json(): Map<String, Any?> = linkedMapOf(
            "role" to role.word, "tile" to "$z/$x/$y", "serves" to serving?.holding?.name, "servesKind" to serving?.holding?.kind?.word, "depth" to serving?.depth,
            "members" to members.map { it.json() }, "off" to off.map { it.name }, "network" to network?.json(),
        )
    }

    private fun area(b: PackBounds?) = if (b == null) Double.MAX_VALUE else (b.maxLon - b.minLon) * (b.maxLat - b.minLat)

    /**
     * The one ranking rule (ADR 0066 decision 1), for any list of things that hold data: the deeper stored maximum first;
     * at equal depth the one [chosen] last (the map the user picked), then the lower [kind] rank ([Kind.rank]: a map before an
     * area before the cache), then the smaller box ([area]), then the [name]. `PackPrecedence.ORDER` (after the vector
     * profile) and `MergedTerrain.ORDER` (after its online input) are this rule.
     */
    fun <T> depthFirst(depth: (T) -> Int, chosen: (T) -> Boolean, kind: (T) -> Int, area: (T) -> Double, name: (T) -> String): Comparator<T> =
        compareBy({ -depth(it) }, { if (chosen(it)) 0 else 1 }, { kind(it) }, { area(it) }, { name(it) })

    /** The lookup's order of the members of one tile: the world overview last, the outdoors profile ahead (vector), then [depthFirst]. */
    val ORDER: Comparator<Member> = compareBy<Member>(
        { if (it.holding.kind == Kind.OVERVIEW) 1 else 0 },
        { if (it.role == Role.VECTOR) it.holding.profile.rank else 0 },
    ).then(depthFirst({ it.depth }, { it.holding.chosen }, { it.holding.kind.rank }, { area(it.holding.stored(it.role)?.bounds) }, { it.holding.name }))

    /** [h]'s answer for the tile, as if it were on; null where it holds none of it. */
    private fun member(h: Holding, role: Role, z: Int, x: Int, y: Int): Member? {
        val s = h.stored(role) ?: return null
        val cells = h.cells
        if (cells != null) {
            // An area holds its cells at its own zooms; the cache holds what it holds, at any zoom.
            if (h.kind != Kind.CACHE && z !in s.minZoom..s.maxZoom) return null
            return if (cells(role, z, x, y)) Member(h, role, s.maxZoom, How.STORES) else null
        }
        val c = h.coverage(role) ?: return null
        if (!PackPrecedence.overlaps(c, z, x, y, role.zooms)) return null
        val how = when {
            !PackPrecedence.contains(c, z, x, y, role.zooms) -> How.EDGE
            z > s.maxZoom -> How.SYNTHESISES
            else -> How.STORES
        }
        return Member(h, role, s.maxZoom, how)
    }

    /** What the phone holds at tile [z]/[x]/[y] of [role], from [holdings] (off ones included: listed apart), and what [online] adds. */
    fun tile(role: Role, z: Int, x: Int, y: Int, holdings: List<Holding>, online: Online?): Here {
        val ranked = holdings.filter { !it.off }.mapNotNull { member(it, role, z, x, y) }.sortedWith(ORDER)
        val first = ranked.indexOfFirst { it.how != How.EDGE }.takeIf { it >= 0 } ?: if (ranked.isEmpty()) -1 else 0
        val members = ranked.mapIndexed { i, m -> if (i == first) m.serving() else m }
        val off = holdings.filter { it.off && member(it, role, z, x, y) != null }
        val net = online?.let { o ->
            val stored = o.storedMax(role)
            val depth = members.getOrNull(first)?.depth
            val adds = stored?.takeIf { depth == null || it > depth }
            Network(stored, adds, adds != null && depth != null && o.detail, o.allowed, o.detail, o.reason)
        }
        return Here(role, z, x, y, members, off, net)
    }

    /** [tile] for the tile of zoom [z] holding ([lat], [lon]). */
    fun point(role: Role, lat: Double, lon: Double, z: Int, holdings: List<Holding>, online: Online?): Here {
        val n = 1 shl z
        val x = floor(WebMercator.lonToX(lon, z)).toInt().coerceIn(0, n - 1)
        val y = floor(WebMercator.latToY(lat, z)).toInt().coerceIn(0, n - 1)
        return tile(role, z, x, y, holdings, online)
    }

    /**
     * The zoom a point's elevation is read at, at most [cap] (`MergedTerrain.pointZoom`): the deepest of [inBox] (the maps
     * whose box holds the point), else of [inReach] (whose whole tiles reach it), else [online] (the online source's stored
     * maximum); with [onlineByDepth] the online source counts beside the phone's and the deeper wins (T368 turns it on).
     * Null when nothing answers there.
     */
    fun pointZoom(inBox: List<Int>, inReach: List<Int>, online: Int?, onlineByDepth: Boolean, cap: Int): Int? {
        val phone = inBox.maxOrNull() ?: inReach.maxOrNull()
        val z = if (onlineByDepth) listOfNotNull(phone, online).maxOrNull() else phone ?: online
        return z?.let { minOf(it, cap) }
    }

    /**
     * [pointZoom] over [holdings]: the maps' elevation (the terrain read's sources; areas and the cache are read through the
     * online source) and [online]'s stored elevation maximum.
     */
    fun readZoom(holdings: List<Holding>, online: Online?, lat: Double, lon: Double, cap: Int, onlineByDepth: Boolean = false): Int? {
        val maps = holdings.filter { it.kind == Kind.MAP && !it.off }.mapNotNull { h -> h.dem?.takeIf { it.bounds != null } }
        fun holds(b: PackBounds) = lon >= b.minLon && lon <= b.maxLon && lat >= b.minLat && lat <= b.maxLat
        return pointZoom(
            maps.filter { holds(it.bounds!!) }.map { it.maxZoom },
            maps.filter { holds(MergedTerrain.wholeTileReach(it.bounds!!, it.minZoom)) }.map { it.maxZoom },
            online?.demMaxZoom, onlineByDepth, cap,
        )
    }
}
