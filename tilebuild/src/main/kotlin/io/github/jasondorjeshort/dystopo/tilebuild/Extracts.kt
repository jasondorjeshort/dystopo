package io.github.jasondorjeshort.dystopo.tilebuild

import com.github.ajalt.clikt.core.UsageError
import java.io.File

/** How `--extract` was interpreted. */
enum class ExtractKind {
    /** A local `.osm.pbf` file; never downloaded. */
    LOCAL,

    /**
     * A local OSM XML file (`.osm`, `.osm.xml`; e.g. an Overpass API answer). Planetiler reads only PBF,
     * so it is converted to the `.osm.pbf` named by [OsmInput.path] first ([OsmXmlToPbf]).
     */
    LOCAL_XML,

    /** A full Geofabrik path such as `north-america/us/washington`; the URL is built directly. */
    GEOFABRIK_PATH,

    /** A bare region name such as `liechtenstein`; resolved through Geofabrik's index at download time. */
    GEOFABRIK_NAME,
}

/**
 * The OSM input for a build.
 *
 * @param label short name used for metadata and temp directories
 * @param path  where the extract is (or will be downloaded to)
 * @param url   download URL for a missing file; a full URL, Planetiler's `geofabrik:<name>` shorthand,
 *              or null for local files
 * @param xml   for [ExtractKind.LOCAL_XML], the XML file [path] is converted from
 */
data class OsmInput(val label: String, val path: File, val url: String?, val kind: ExtractKind, val xml: File? = null)

object Extracts {
    const val GEOFABRIK = "https://download.geofabrik.de"
    private val pathPattern = Regex("[a-z0-9][a-z0-9/_-]*")

    /**
     * Resolves the `--extract` option. Anything ending in `.pbf` is a local file, `.osm` or `.osm.xml` a
     * local OSM XML file converted to `<name>.osm.pbf` beside it. Otherwise the value
     * is a Geofabrik region: a path (contains `/`) maps directly to `<GEOFABRIK>/<path>-latest.osm.pbf`,
     * a bare name is looked up in Geofabrik's index when the file needs downloading. Downloads land in
     * [osmCache] under a name derived from the region, so repeated builds reuse them.
     */
    fun resolve(spec: String, osmCache: File): OsmInput {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) throw UsageError("--extract must not be empty")
        if (trimmed.endsWith(".pbf", ignoreCase = true)) {
            val file = File(trimmed)
            val label = file.name.removeSuffix(".pbf").removeSuffix(".PBF").removeSuffix(".osm")
            return OsmInput(label, file, null, ExtractKind.LOCAL)
        }
        val lower = trimmed.lowercase()
        if (lower.endsWith(".osm") || lower.endsWith(".osm.xml")) {
            val xml = File(trimmed)
            val base = xml.name.substring(0, xml.name.length - if (lower.endsWith(".xml")) 8 else 4)
            return OsmInput(base, File(xml.absoluteFile.parentFile, "$base.osm.pbf"), null, ExtractKind.LOCAL_XML, xml)
        }
        val region = trimmed.trim('/').lowercase()
        if (!region.matches(pathPattern)) {
            throw UsageError("--extract '$spec' is neither a .pbf or .osm file nor a Geofabrik region (letters, digits, '-', '_', '/')")
        }
        val fileName = region.replace('/', '-') + "-latest.osm.pbf"
        return if ('/' in region) {
            OsmInput(region.substringAfterLast('/'), File(osmCache, fileName), "$GEOFABRIK/$region-latest.osm.pbf", ExtractKind.GEOFABRIK_PATH)
        } else {
            OsmInput(region, File(osmCache, fileName), "geofabrik:$region", ExtractKind.GEOFABRIK_NAME)
        }
    }
}
