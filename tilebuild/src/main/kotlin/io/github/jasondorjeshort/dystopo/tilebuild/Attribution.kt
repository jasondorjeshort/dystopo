package io.github.jasondorjeshort.dystopo.tilebuild

/**
 * Attribution and license text written into every archive. OSM data is ODbL 1.0, so the tiles are a
 * derived database and must stay ODbL with OSM attribution; the OpenMapTiles schema's CC-BY 4.0 design
 * license requires a visible "© OpenMapTiles" credit. See docs/PLANNING.md › Licensing.
 */
object Attribution {
    const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    const val ODBL_URL = "https://opendatacommons.org/licenses/odbl/1-0/"
    const val OPENMAPTILES_URL = "https://www.openmaptiles.org/"

    /** Plain-text credit, for reports and non-HTML UIs. */
    const val TEXT = "© OpenStreetMap contributors (ODbL 1.0) © OpenMapTiles"

    /** HTML credit written to the archive's `attribution` field; this is what MapLibre displays. */
    const val HTML =
        "<a href=\"$OSM_COPYRIGHT_URL\" target=\"_blank\">&copy; OpenStreetMap contributors</a> " +
            "(<a href=\"$ODBL_URL\" target=\"_blank\">ODbL 1.0</a>) " +
            "<a href=\"$OPENMAPTILES_URL\" target=\"_blank\">&copy; OpenMapTiles</a>"

    const val LICENSE = "ODbL-1.0"

    /** Where the archive's contents come from, one entry per Planetiler source, with each source's license. */
    val sources: List<String> = listOf(
        "OpenStreetMap (ODbL 1.0, https://www.openstreetmap.org/copyright)",
        "OSM water polygons by osmdata.openstreetmap.de (ODbL 1.0, OSM-derived)",
        "OSM lake centerlines (ODbL 1.0, OSM-derived)",
        "Natural Earth (public domain)",
    )

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
        put("dystopo:schema", SCHEMA)
        put("dystopo:extract", extract)
        if (bbox != null) put("dystopo:clip_bbox", bbox.joinToString(","))
    }
}
