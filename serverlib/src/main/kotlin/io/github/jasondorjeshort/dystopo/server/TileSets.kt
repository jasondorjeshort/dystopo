package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Pretty JSON for reports printed to stdout. Reports are meant to be read by agents and scripts. */
val reportJson = Json { prettyPrint = true; encodeDefaults = true }

/** Written next to the tiles as metadata.json; a TileJSON-like description for consumers (MapLibre, PMTiles packaging). */
@Serializable
data class TileSetMetadata(
    val name: String,
    val format: String = "png",
    val encoding: String = "terrarium",
    val scheme: String = "xyz",
    val tileSize: Int,
    val bounds: List<Double>,
    val minzoom: Int,
    val maxzoom: Int,
    val verticalDatum: String = "EGM2008",
    val noDataMeters: Double = 0.0,
    val source: String,
    /** Composed from the data source registry for [provenance]. */
    val attribution: String,
    val attributionHtml: String,
    val provenance: List<String>,
    /** SPDX-style licence expression of the data in the tiles, from the registry for [provenance] ([licenseExpression]). */
    val license: String? = null,
)

/** Tile set helpers shared by `tiles`, pack builds and the catalog. */
object TileSets {
    /**
     * [box] as TileJSON/GeoJSON-style bounds [west, south, east, north] with longitudes in
     * -180..180. Boxes are unwrapped internally (an antimeridian box has east past 180), so here
     * the east edge is wrapped back, and west > east marks the crossing, as TileJSON and MapLibre
     * expect. A box ending exactly at 180 keeps 180.
     */
    fun geoJsonBounds(box: BoundingBox): List<Double> = listOf(wrapLon(box.west), box.south, wrapLon(box.east), box.north)

    /**
     * The licences of the sources [ids] as one expression: each distinct registry licence once, in
     * registry order, joined with AND (all of them apply to the combined tiles). Null for no ids.
     */
    fun licenseExpression(ids: Collection<String>, registry: SourceRegistry = SourceRegistry.builtIn): String? =
        registry.ordered(ids).map { registry.getValue(it).license }.distinct().joinToString(" AND ").ifEmpty { null }

    /** Center longitude of [box] in -180..180 (the middle of an unwrapped box can be past 180). */
    fun centerLon(box: BoundingBox): Double = wrapLon(box.centerLon)

    private fun wrapLon(lon: Double) = when {
        lon > 180.0 -> lon - 360.0
        lon < -180.0 -> lon + 360.0
        else -> lon
    }
}
