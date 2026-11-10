package io.github.jasondorjeshort.dystopo.tilebuild

import io.github.jasondorjeshort.dystopo.sources.SourceRegistry

/**
 * Attribution and license text written into every archive. OSM data is ODbL 1.0, so the tiles are a
 * derived database and must stay ODbL with OSM attribution; the OpenMapTiles schema's CC-BY 4.0 design
 * license requires a visible "© OpenMapTiles" credit. See docs/PLANNING.md › Licensing.
 * Credit text and licenses come from the data source registry (shared sources.json).
 */
object Attribution {
    const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    const val ODBL_URL = "https://opendatacommons.org/licenses/odbl/1-0/"
    const val OPENMAPTILES_URL = "https://www.openmaptiles.org/"

    private val registry = SourceRegistry.builtIn

    /** Registry ids credited on the map: OSM data (ODbL) and the OpenMapTiles schema (CC-BY 4.0 design). */
    val CREDIT_IDS = listOf("osm", "openmaptiles")

    /** Registry ids of everything Planetiler reads to build the archive. */
    val SOURCE_IDS = listOf("osm", "osm-water-polygons", "osm-lake-centerlines", "natural-earth", "openmaptiles")

    /** Plain-text credit, for reports and non-HTML UIs. From the data source registry (shared `sources.json`). */
    val TEXT: String = registry.attribution(CREDIT_IDS)

    /** HTML credit written to the archive's `attribution` field; this is what MapLibre displays. */
    val HTML: String = registry.attributionHtml(CREDIT_IDS)

    /** The archive's license: OSM's, since the tiles are an ODbL derived database. */
    val LICENSE: String = registry.getValue("osm").license

    /** Where the archive's contents come from, one entry per Planetiler source, with each source's license. */
    val sources: List<String> = sourcesFor(SOURCE_IDS)

    /** Registry ids of what the outdoors profile reads (no lake centerlines). */
    val OUTDOORS_SOURCE_IDS = listOf("osm", "osm-water-polygons", "natural-earth", "openmaptiles")

    /**
     * T264: what a Natural-Earth-only build (`--natural-earth-only`, the bundled world overview) credits and reads: no
     * OpenStreetMap data is in it, so its credit is Natural Earth's plus the schema's, and its licence is Natural Earth's.
     */
    val WORLD_CREDIT_IDS = listOf("natural-earth", "openmaptiles")
    val WORLD_TEXT: String = registry.attribution(WORLD_CREDIT_IDS)
    val WORLD_HTML: String = registry.attributionHtml(WORLD_CREDIT_IDS)

    /** The outdoors schema reuses OpenMapTiles layer names and class values, so the OpenMapTiles credit stays. */
    const val OUTDOORS_SCHEMA = "Dystopo outdoors 1 (tilebuild/PROFILE.md; OpenMapTiles 3.16 layer names and classes, design CC-BY 4.0)"

    fun sourcesFor(ids: List<String>): List<String> = registry.ordered(ids).map { registry.getValue(it) }
        .filter { it.id != "openmaptiles" } // the schema is recorded under dystopo:schema
        .map { s -> "${s.name} (${s.licenseName}, ${s.licenseUrl})" }

    const val SCHEMA = "OpenMapTiles 3.16 (code BSD-3-Clause, design CC-BY 4.0)"

    /**
     * Extra key/value metadata for the archive. Keys are prefixed `dystopo:` the way Planetiler
     * prefixes its own (`planetiler:version`), so readers can tell them apart from MBTiles-spec fields.
     */
    fun extraMetadata(
        extract: String,
        bbox: DoubleArray?,
        sourceIds: List<String> = SOURCE_IDS,
        schema: String = SCHEMA,
        profile: String = ProfileKind.OPENMAPTILES.cliName,
        /** T264: a Natural-Earth-only archive credits Natural Earth and carries its licence, not OSM's. */
        naturalEarthOnly: Boolean = false,
    ): Map<String, String> = buildMap {
        val ne = registry.getValue("natural-earth")
        put("dystopo:attribution", if (naturalEarthOnly) WORLD_TEXT else TEXT)
        put("dystopo:license", if (naturalEarthOnly) ne.license else LICENSE)
        put("dystopo:license_url", if (naturalEarthOnly) ne.licenseUrl else ODBL_URL)
        put("dystopo:sources", sourcesFor(sourceIds).joinToString("; "))
        put("dystopo:provenance", registry.ordered(sourceIds).joinToString(","))
        put("dystopo:schema", schema)
        // Which schema the tiles follow, so pack precedence (T41) can rank outdoors over plain OpenMapTiles packs.
        put("dystopo:profile", profile)
        put("dystopo:extract", extract)
        if (bbox != null) put("dystopo:clip_bbox", bbox.joinToString(","))
    }
}
