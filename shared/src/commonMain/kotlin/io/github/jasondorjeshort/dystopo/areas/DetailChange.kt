package io.github.jasondorjeshort.dystopo.areas

/**
 * T386: a saved area's detail changed with its shape kept: which zooms of each route go and which come, the tiles a raise
 * fetches, and the manifests the area keeps. Pure.
 *
 * The zooms differ by route, not as one range ([AreaDetail]): Less to Medium adds the vector z14 only, Medium to Full the
 * elevation z14 and z15, Less to Full both; a lower drops the same. An area's id includes its zooms
 * ([AreaOfInterest.id]), so the app re-keys the record and writes [rekeyed] under the new id; a raise downloads [raiseManifest]
 * (the added tiles alone), so the public vector source's cap ([AreaLimit.DIRECT_MAX_TILES]) counts only those.
 *
 * [Change.held] is what the area holds whole now; [Change.current] the area as recorded, which is above [Change.held] while a raise
 * waits or runs. A change to [Change.target] fetches what [Change.target] has and [Change.held] lacks, and frees what
 * [Change.current] or [Change.held] has and [Change.target] lacks (choosing the held detail again while a raise waits frees what
 * the raise fetched so far and fetches nothing).
 */
object DetailChange {
    /** The deepest elevation zoom a source serves (the phone's tile routes, `OnlineTiles.DEM_MAX_ZOOM`). */
    const val DEM_MAX_ZOOM = 15

    val ROUTES = listOf(AreaManifest.ROUTE_DEM, AreaManifest.ROUTE_OSM)

    /**
     * The zooms of each route of [area]'s download from a source whose elevation starts at [demLo] (the source's lowest; the
     * same for both ends of a change, so it never adds or drops a zoom by itself): the elevation to the area's cap, the vector
     * to z14.
     */
    fun routeZooms(area: AreaOfInterest, demLo: Int, demMax: Int = DEM_MAX_ZOOM, osmMax: Int = AreaManifest.OSM_MAX_ZOOM): Map<String, Set<Int>> =
        mapOf(AreaManifest.ROUTE_DEM to area.demZooms(demLo, demMax).toSet(), AreaManifest.ROUTE_OSM to (area.osmZooms(osmMax)?.toSet() ?: emptySet()))

    /** A change of detail: per route the zooms [added] (fetched) and [dropped] (freed); only routes with some are listed. */
    data class Change(
        val held: AreaOfInterest, val current: AreaOfInterest, val target: AreaOfInterest,
        val added: Map<String, Set<Int>>, val dropped: Map<String, Set<Int>>,
    ) {
        val raise: Boolean get() = added.isNotEmpty()
        val lower: Boolean get() = dropped.isNotEmpty()
        val none: Boolean get() = !raise && !lower

        /** The record changes id: the target's zooms are not the current ones. */
        val rekey: Boolean get() = target.id != current.id

        fun json(): Map<String, Any?> = linkedMapOf(
            "from" to AreaDetail.of(held)?.key, "to" to AreaDetail.of(target)?.key, "raise" to raise, "lower" to lower,
            "added" to added.toSortedMap().mapValues { it.value.sorted() }, "dropped" to dropped.toSortedMap().mapValues { it.value.sorted() },
            "fromId" to current.id, "toId" to target.id,
        )
    }

    /** The change from [held] (and [current], the area as recorded) to [target] (see the object comment). */
    fun of(held: AreaOfInterest, target: AreaOfInterest, demLo: Int, current: AreaOfInterest = held): Change {
        val h = routeZooms(held, demLo)
        val c = routeZooms(current, demLo)
        val t = routeZooms(target, demLo)
        val added = ROUTES.associateWith { r -> t.getValue(r) - h.getValue(r) }.filterValues { it.isNotEmpty() }
        val dropped = ROUTES.associateWith { r -> (c.getValue(r) + h.getValue(r)) - t.getValue(r) }.filterValues { it.isNotEmpty() }
        return Change(held, current, target, added, dropped)
    }

    /** [of] between two details of [area] (its shape and name kept), from z8 as every detail is. */
    fun of(area: AreaOfInterest, from: AreaDetail, to: AreaDetail, demLo: Int): Change = of(area.at(from), area.at(to), demLo)

    /** The tiles [change] fetches over its target's shape, each zoom's cover grown by [margin] tiles as a download's are. */
    fun addedItems(change: Change, margin: Int = 1): List<TilesItem> = items(change.target, change.added, margin)

    /** The manifest a raise downloads: [change]'s added tiles alone, under the target's id ([AreaManifest.vectorTileCount] is what the cap counts). */
    fun raiseManifest(change: Change, source: String? = null, generatedAt: String? = null, margin: Int = 1): AreaManifest =
        AreaManifest(
            change.target.id, change.target.name ?: change.target.id, change.target, AreaManifest.STATUS_READY, addedItems(change, margin),
            source = source, generatedAt = generatedAt, notes = listOf("the tiles a change of detail adds"),
        )

    /**
     * [m], the area's whole manifest, for [change]'s target under its id: each tile item without the dropped zooms of its route
     * and with the added ones (a route the area had no item for gets one), its size an estimate again; the other items as they
     * were. What the area kept stays as it was (a manifest of fewer rows than its shape keeps them).
     */
    fun rekeyed(m: AreaManifest, change: Change, margin: Int = 1): AreaManifest {
        val added = addedItems(change, margin).associateBy { it.route }
        val out = ArrayList<ManifestItem>()
        val seen = HashSet<String>()
        for (item in m.items) {
            if (item !is TilesItem) { out += item; continue }
            seen += item.route
            val drop = change.dropped[item.route].orEmpty()
            val tiles = item.tiles.filterKeys { it !in drop } + added[item.route]?.tiles.orEmpty()
            out += TilesItem(item.route, item.ext, item.url, tiles.toSortedMap(), estimateOf(item.route, tiles))
        }
        for ((route, item) in added) if (route !in seen) out += item
        val t = change.target
        return m.copy(id = t.id, name = t.name ?: m.name, area = t, items = out, version = t.manifestVersion)
    }

    /** The tiles of [m] at [change]'s dropped zooms, per route, for the clear (the shared tiles are kept by the caller's pins). */
    fun droppedItems(m: AreaManifest, change: Change): List<TilesItem> = m.items.filterIsInstance<TilesItem>().mapNotNull { item ->
        val drop = change.dropped[item.route] ?: return@mapNotNull null
        val tiles = item.tiles.filterKeys { it in drop }
        if (tiles.isEmpty()) null else TilesItem(item.route, item.ext, item.url, tiles, estimateOf(item.route, tiles))
    }

    /** The estimated bytes of [tiles] of [route] ([AreaManifest.estimate] per zoom). */
    fun estimateOf(route: String, tiles: Map<Int, TileSet>): Long = tiles.entries.sumOf { (z, s) -> s.size * AreaManifest.estimate(route, z) }

    private fun items(area: AreaOfInterest, zooms: Map<String, Set<Int>>, margin: Int): List<TilesItem> {
        val covers = HashMap<Int, TileSet>() // a zoom both routes need is filled once, as AreaManifest.tileItems does
        return ROUTES.mapNotNull { route ->
            val zs = zooms[route]?.sorted()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val tiles = zs.associateWith { z -> covers.getOrPut(z) { TileCover.of(area, z, margin) } }
            val (ext, url) = if (route == AreaManifest.ROUTE_DEM) "png" to "/dem/{z}/{x}/{y}.png" else "pbf" to "/osm/{z}/{x}/{y}.pbf"
            TilesItem(route, ext, url, tiles, estimateOf(route, tiles))
        }
    }
}
