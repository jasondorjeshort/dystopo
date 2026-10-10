package io.github.jasondorjeshort.dystopo.tilebuild

import com.onthegomap.planetiler.Planetiler
import com.onthegomap.planetiler.config.Arguments
import org.openmaptiles.OpenMapTilesProfile
import org.openmaptiles.generated.OpenMapTilesSchema
import java.io.File

/** The vector tile schemas tilebuild can write (`--profile`). */
enum class ProfileKind(val cliName: String) {
    /** The upstream OpenMapTiles 3.16 schema (default). */
    OPENMAPTILES("openmaptiles"),

    /** Dystopo's backcountry schema, [OutdoorsProfile]; layers in `tilebuild/PROFILE.md`. */
    OUTDOORS("outdoors"),
    ;

    companion object {
        fun parse(name: String): ProfileKind = entries.firstOrNull { it.cliName == name }
            ?: throw IllegalArgumentException("unknown profile '$name'; known: ${entries.joinToString { it.cliName }}")
    }
}

/**
 * Sets up a [Planetiler] run for one profile: the profile itself, its auxiliary sources (downloaded once
 * into the cache) and the archive metadata. Kept apart from [TileBuild] so the build driver does not
 * need to know which profile reads what.
 */
object Profiles {
    const val LAKE_CENTERLINE_URL = "https://dev.maptiler.download/geodata/omt/lake_centerline.shp.zip"
    const val WATER_POLYGONS_URL = "https://osmdata.openstreetmap.de/download/water-polygons-split-3857.zip"
    const val NATURAL_EARTH_URL = "https://dev.maptiler.download/geodata/omt/natural_earth_vector.sqlite.zip"

    class Setup(
        val planetiler: Planetiler,
        /** Human-readable schema name and version for the report. */
        val label: String,
        val auxiliaryData: List<String>,
        val extraMetadata: Map<String, String>,
    )

    // addNaturalEarthSource is deprecated in favour of the GeoPackage reader, but the cached aux data is the
    // Natural Earth sqlite file that OpenMapTiles 3.16 expects, and the outdoors profile reuses it.
    @Suppress("DEPRECATION")
    fun create(kind: ProfileKind, arguments: Arguments, req: BuildRequest, planetilerCache: File, osmUrl: String?): Setup {
        val waterPolygons = File(planetilerCache, "water-polygons-split-3857.zip").toPath()
        val naturalEarth = File(planetilerCache, "natural_earth_vector.sqlite.zip").toPath()
        val setup = when (kind) {
            ProfileKind.OPENMAPTILES -> {
                val extra = Attribution.extraMetadata(req.input.label, req.bbox)
                val planetiler = Planetiler.create(arguments)
                    .setDefaultLanguages(OpenMapTilesSchema.LANGUAGES)
                    .setProfile { runner -> DystopoOpenMapTilesProfile(runner, req.input.label, extra) }
                    .addShapefileSource(
                        "EPSG:3857", OpenMapTilesProfile.LAKE_CENTERLINE_SOURCE,
                        File(planetilerCache, "lake_centerline.shp.zip").toPath(), LAKE_CENTERLINE_URL,
                    )
                    .addShapefileSource(OpenMapTilesProfile.WATER_POLYGON_SOURCE, waterPolygons, WATER_POLYGONS_URL)
                    .addNaturalEarthSource(OpenMapTilesProfile.NATURAL_EARTH_SOURCE, naturalEarth, NATURAL_EARTH_URL)
                Setup(
                    planetiler,
                    "${OpenMapTilesSchema.NAME} ${OpenMapTilesSchema.VERSION}",
                    listOf(LAKE_CENTERLINE_URL, WATER_POLYGONS_URL, NATURAL_EARTH_URL),
                    extra,
                )
            }
            ProfileKind.OUTDOORS -> {
                val extra = Attribution.extraMetadata(
                    req.input.label, req.bbox, Attribution.OUTDOORS_SOURCE_IDS, Attribution.OUTDOORS_SCHEMA,
                )
                val planetiler = Planetiler.create(arguments)
                    .setProfile(OutdoorsProfile(req.input.label, extra, req.maxZoom))
                    .addShapefileSource(OutdoorsProfile.WATER_POLYGON_SOURCE, waterPolygons, WATER_POLYGONS_URL)
                    .addNaturalEarthSource(OutdoorsProfile.NATURAL_EARTH_SOURCE, naturalEarth, NATURAL_EARTH_URL)
                Setup(
                    planetiler,
                    "${OutdoorsSchema.NAME} ${OutdoorsSchema.VERSION}",
                    listOf(WATER_POLYGONS_URL, NATURAL_EARTH_URL),
                    extra,
                )
            }
        }
        val osmSource = if (kind == ProfileKind.OPENMAPTILES) OpenMapTilesProfile.OSM_SOURCE else OutdoorsProfile.OSM_SOURCE
        if (osmUrl != null) {
            setup.planetiler.addOsmSource(osmSource, req.input.path.toPath(), osmUrl)
        } else {
            setup.planetiler.addOsmSource(osmSource, req.input.path.toPath())
        }
        return setup
    }
}
