package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import io.github.jasondorjeshort.dystopo.geo.WebMercator

/** The schema of a vector pack, as its metadata states it; [rank] orders packs (lower wins). */
enum class VectorProfile(val label: String, val rank: Int) {
    /** tilebuild's outdoors profile: wilderness, cliff ticks, climbing routes, huts. */
    OUTDOORS("outdoors", 0),

    /** Plain OpenMapTiles schema (tilebuild default, or a server build from OpenFreeMap tiles). */
    OPENMAPTILES("openmaptiles", 1),

    /** Metadata says nothing we recognise (and DEM archives, which have no profile). */
    UNKNOWN("unknown", 2),
}

/** Which tiles an archive answers. */
enum class PackRoleZooms {
    /** Terrarium DEM: from its lowest zoom down, deeper tiles synthesized from ancestors. */
    DEM,

    /** Vector tiles: only the zooms the archive has (MapLibre overzooms past the source's max). */
    VECTOR,

    /** Contour tiles (512 px, so one level above the DEM tiles they sample): from min zoom - 1 down. */
    CONTOUR,
}

/** One opened archive as the tile service ranks it: coverage plus the facts precedence looks at. */
data class PackCoverage(
    val pack: String,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: PackBounds,
    val profile: VectorProfile = VectorProfile.UNKNOWN,
    /** The pack the user chose last (`POST /packs/activate`): first within a profile (T49). */
    val primary: Boolean = false,
    /** The archive stores whole upstream tiles even at its edges (built from OpenFreeMap), so an edge tile is complete. */
    val wholeTiles: Boolean = false,
)

/**
 * Several packs on the map at once (T41): every usable pack is consulted, tile by tile, in a fixed
 * order. Vector archives rank by profile first (outdoors over plain OpenMapTiles, T37), then the
 * user's primary pack (T49: the pack the user chose wins over a deeper one of the same profile), then
 * the deeper archive, then the smaller (more specific) box, then the name.
 * For one tile the first archive in that order that holds the whole tile wins; when none does (a tile
 * on a pack edge), the first that overlaps it, so an edge tile clipped to a small pack loses to a
 * larger pack that has all of it.
 */
object PackPrecedence {
    /** Bounds are stored in 1e-7 degrees; a tile edge this close counts as inside. */
    const val EDGE_EPS_DEG = 1e-6

    /** The vector profile a pack's metadata declares (`dystopo:profile`, else `dystopo:schema`, else provenance). */
    fun vectorProfile(metadata: Map<String, Any?>): VectorProfile {
        when ((metadata["dystopo:profile"] as? String)?.trim()?.lowercase()) {
            "outdoors" -> return VectorProfile.OUTDOORS
            "openmaptiles" -> return VectorProfile.OPENMAPTILES
        }
        val schema = (metadata["dystopo:schema"] as? String)?.trim()?.lowercase().orEmpty()
        if (schema.startsWith("dystopo outdoors")) return VectorProfile.OUTDOORS
        if (schema.startsWith("openmaptiles")) return VectorProfile.OPENMAPTILES
        val ids = PackAttribution.registryIds(metadata)
        if ("openfreemap" in ids || "openmaptiles" in ids) return VectorProfile.OPENMAPTILES
        return VectorProfile.UNKNOWN
    }

    /** Whether the archive keeps whole upstream tiles at its edges (an OpenFreeMap build). */
    fun wholeTiles(metadata: Map<String, Any?>): Boolean = "openfreemap" in PackAttribution.registryIds(metadata)

    private fun area(b: PackBounds) = (b.maxLon - b.minLon) * (b.maxLat - b.minLat)

    val ORDER: Comparator<PackCoverage> = compareBy<PackCoverage>(
        { it.profile.rank },
        { if (it.primary) 0 else 1 }, // T49: ahead of depth
        { -it.maxZoom },
        { area(it.bounds) },
        { it.pack },
    )

    /** [archives] in precedence order. */
    fun <T> sort(archives: List<T>, coverage: (T) -> PackCoverage): List<T> = archives.sortedWith(compareBy(ORDER, coverage))

    private fun zoomOk(c: PackCoverage, z: Int, role: PackRoleZooms) = when (role) {
        PackRoleZooms.DEM -> z >= c.minZoom
        PackRoleZooms.VECTOR -> z in c.minZoom..c.maxZoom
        PackRoleZooms.CONTOUR -> z >= c.minZoom - 1
    }

    /** West, south, east, north of tile z/x/y in degrees. */
    fun tileBounds(z: Int, x: Int, y: Int): PackBounds = PackBounds(
        WebMercator.xToLon(x.toDouble(), z), WebMercator.yToLat(y + 1.0, z),
        WebMercator.xToLon(x + 1.0, z), WebMercator.yToLat(y.toDouble(), z),
    )

    fun overlaps(c: PackCoverage, z: Int, x: Int, y: Int, role: PackRoleZooms): Boolean {
        if (!zoomOk(c, z, role)) return false
        val t = tileBounds(z, x, y)
        val b = c.bounds
        return t.maxLon > b.minLon && t.minLon < b.maxLon && t.maxLat > b.minLat && t.minLat < b.maxLat
    }

    /** The archive's copy of the tile is complete: the tile lies inside its box (or it keeps whole tiles and overlaps). */
    fun contains(c: PackCoverage, z: Int, x: Int, y: Int, role: PackRoleZooms): Boolean {
        if (!overlaps(c, z, x, y, role)) return false
        if (c.wholeTiles && role == PackRoleZooms.VECTOR) return true
        val t = tileBounds(z, x, y)
        val b = c.bounds
        val e = EDGE_EPS_DEG
        return t.minLon >= b.minLon - e && t.maxLon <= b.maxLon + e && t.minLat >= b.minLat - e && t.maxLat <= b.maxLat + e
    }

    /**
     * Index into [sorted] (already in precedence order) of the archive that answers tile z/x/y for
     * [role]: the first that contains it, else the first that overlaps it; -1 when none does.
     */
    fun choose(sorted: List<PackCoverage>, z: Int, x: Int, y: Int, role: PackRoleZooms): Int {
        var firstOverlap = -1
        for ((i, c) in sorted.withIndex()) {
            if (!overlaps(c, z, x, y, role)) continue
            if (contains(c, z, x, y, role)) return i
            if (firstOverlap < 0) firstOverlap = i
        }
        return firstOverlap
    }

    /** Every index into [sorted] overlapping tile z/x/y for [role], in precedence order (fallbacks when the first has no tile). */
    fun candidates(sorted: List<PackCoverage>, z: Int, x: Int, y: Int, role: PackRoleZooms): List<Int> {
        val first = choose(sorted, z, x, y, role)
        if (first < 0) return emptyList()
        return listOf(first) + sorted.indices.filter { it != first && overlaps(sorted[it], z, x, y, role) }
    }
}
