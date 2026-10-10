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
    val sources: List<String> = registry.ordered(SOURCE_IDS).map { registry.getValue(it) }
        .filter { it.id != "openmaptiles" } // the schema is recorded under dystopo:schema
        .map { s -> "${s.name} (${s.licenseName}, ${s.licenseUrl})" }

    const val SCHEMA = "OpenMapTiles 3.16 (code BSD-3-Clause, design CC-BY 4.0)"

    /**
     * Extra key/value metadata for the archive. Keys are prefixed `dystopo:` the way Planetiler
     * prefixes its own (`planetiler:version`), so readers can tell them apart from MBTiles-spec fields.
     */
    fun extraMetadata(extract: String, bbox: DoubleArray?): Map<String, String> = buildMap {
        put("dystopo:attribution", TEXT)
        put("dystopo:license", LICENSE)
        put("dystopo:license_url", ODBL_URL)
        put("dystopo:sources", sources.joinToString("; "))
        put("dystopo:provenance", registry.ordered(SOURCE_IDS).joinToString(","))
        put("dystopo:schema", SCHEMA)
        put("dystopo:extract", extract)
        if (bbox != null) put("dystopo:clip_bbox", bbox.joinToString(","))
    }
}
