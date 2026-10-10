package io.github.jasondorjeshort.dystopo.tilebuild

import com.onthegomap.planetiler.Planetiler
import com.onthegomap.planetiler.config.Arguments
import com.onthegomap.planetiler.config.PlanetilerConfig
import com.onthegomap.planetiler.stats.Timer
import com.onthegomap.planetiler.util.Geofabrik
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.openmaptiles.OpenMapTilesProfile
import org.openmaptiles.generated.OpenMapTilesSchema
import java.io.File
import java.util.Locale

data class BuildRequest(
    val input: OsmInput,
    val out: File,
    val clipArea: String?,
    /** west, south, east, north; null = the extract's own bounds. */
    val bbox: DoubleArray?,
    val minZoom: Int,
    val maxZoom: Int,
    val threads: Int?,
    val cacheRoot: File,
)

@Serializable
data class InputReport(
    val extract: String,
    val kind: String,
    val path: String,
    val url: String?,
    val bytes: Long,
    val downloaded: Boolean,
)

@Serializable
data class OutputReport(val path: String, val bytes: Long)

@Serializable
data class BuildReport(
    val profile: String,
    val input: InputReport,
    val clipArea: String?,
    val bbox: List<Double>?,
    val minZoom: Int,
    val maxZoom: Int,
    val threads: Int,
    val output: OutputReport,
    val pmtiles: PmtilesInfo,
    /** The attribution read back from the finished archive's metadata. */
    val attribution: String?,
    val attributionText: String,
    val license: String,
    val extraMetadata: Map<String, String>,
    val auxiliaryData: List<String>,
    val stageSeconds: Map<String, Double>,
    val totalSeconds: Double,
)

/**
 * The OpenMapTiles profile with Dystopo's attribution, description and license metadata. Everything
 * else (layers, zoom rules, name translations) is the upstream schema.
 */
class DystopoOpenMapTilesProfile(runner: Planetiler, private val extract: String, private val extra: Map<String, String>) :
    OpenMapTilesProfile(runner) {

    override fun attribution(): String = Attribution.HTML

    override fun description(): String =
        "OpenMapTiles-schema vector tiles of OpenStreetMap extract '$extract', built by Dystopo tilebuild with Planetiler. " +
            "Data © OpenStreetMap contributors, ODbL 1.0."

    override fun extraArchiveMetadata(): Map<String, String> = super.extraArchiveMetadata() + extra
}

object TileBuild {
    private const val LAKE_CENTERLINE_URL = "https://dev.maptiler.download/geodata/omt/lake_centerline.shp.zip"
    private const val WATER_POLYGONS_URL = "https://osmdata.openstreetmap.de/download/water-polygons-split-3857.zip"
    private const val NATURAL_EARTH_URL = "https://dev.maptiler.download/geodata/omt/natural_earth_vector.sqlite.zip"

    // addNaturalEarthSource is deprecated in favour of the GeoPackage reader, but OpenMapTiles 3.16 still
    // expects the Natural Earth sqlite tables, so this follows the profile's own main class.
    @Suppress("DEPRECATION")
    fun run(req: BuildRequest): BuildReport {
        val started = System.nanoTime()
        val planetilerCache = File(req.cacheRoot, "planetiler").apply { mkdirs() }
        req.input.path.absoluteFile.parentFile?.mkdirs()
        req.out.absoluteFile.parentFile?.mkdirs()
        val tmpDir = File(planetilerCache, "tmp/${req.out.nameWithoutExtension}")

        val args = buildMap {
            put("download", "true")
            put("force", "true")
            put("tmpdir", tmpDir.path)
            put("minzoom", req.minZoom.toString())
            put("maxzoom", req.maxZoom.toString())
            // Only used for weighted tile statistics; skipping it avoids one more download.
            put("download_osm_tile_weights", "false")
            req.bbox?.let { put("bounds", it.joinToString(",") { d -> String.format(Locale.ROOT, "%.6f", d) }) }
            req.threads?.let { put("threads", it.toString()) }
        }
        val arguments = Arguments.of(args)
        val config = PlanetilerConfig.from(arguments)

        val existedBefore = req.input.path.isFile
        val osmUrl = when {
            req.input.url == null -> null
            existedBefore -> req.input.url
            req.input.kind == ExtractKind.GEOFABRIK_NAME ->
                Geofabrik.getDownloadUrl(req.input.url.removePrefix("geofabrik:"), config)
            else -> req.input.url
        }
        if (osmUrl == null && !existedBefore) {
            throw IllegalArgumentException("OSM extract ${req.input.path} does not exist")
        }

        val extra = Attribution.extraMetadata(req.input.label, req.bbox)
        val planetiler = Planetiler.create(arguments)
            .setDefaultLanguages(OpenMapTilesSchema.LANGUAGES)
            .setProfile { runner -> DystopoOpenMapTilesProfile(runner, req.input.label, extra) }
            .addShapefileSource(
                "EPSG:3857", OpenMapTilesProfile.LAKE_CENTERLINE_SOURCE,
                File(planetilerCache, "lake_centerline.shp.zip").toPath(), LAKE_CENTERLINE_URL,
            )
            .addShapefileSource(
                OpenMapTilesProfile.WATER_POLYGON_SOURCE,
                File(planetilerCache, "water-polygons-split-3857.zip").toPath(), WATER_POLYGONS_URL,
            )
            .addNaturalEarthSource(
                OpenMapTilesProfile.NATURAL_EARTH_SOURCE,
                File(planetilerCache, "natural_earth_vector.sqlite.zip").toPath(), NATURAL_EARTH_URL,
            )
        if (osmUrl != null) {
            planetiler.addOsmSource(OpenMapTilesProfile.OSM_SOURCE, req.input.path.toPath(), osmUrl)
        } else {
            planetiler.addOsmSource(OpenMapTilesProfile.OSM_SOURCE, req.input.path.toPath())
        }
        planetiler.overwriteOutput(req.out.toPath())
        planetiler.run()

        val stages = stageSeconds(planetiler)
        // Planetiler removes its temp files but leaves the (now empty) directory tree behind.
        runCatching { tmpDir.deleteRecursively() }

        val info = PmtilesInfo.read(req.out)
        return BuildReport(
            profile = "${OpenMapTilesSchema.NAME} ${OpenMapTilesSchema.VERSION}",
            input = InputReport(
                extract = req.input.label,
                kind = req.input.kind.name.lowercase(),
                path = req.input.path.path,
                url = osmUrl,
                bytes = req.input.path.length(),
                downloaded = !existedBefore,
            ),
            clipArea = req.clipArea,
            bbox = req.bbox?.toList(),
            minZoom = req.minZoom,
            maxZoom = req.maxZoom,
            threads = config.threads(),
            output = OutputReport(req.out.path, req.out.length()),
            pmtiles = info,
            attribution = info.attribution,
            attributionText = Attribution.TEXT,
            license = Attribution.LICENSE,
            extraMetadata = extra,
            auxiliaryData = listOf(LAKE_CENTERLINE_URL, WATER_POLYGONS_URL, NATURAL_EARTH_URL),
            stageSeconds = stages,
            totalSeconds = (System.nanoTime() - started) / 1e9,
        )
    }

    /**
     * Wall time per Planetiler stage. `Timers.all()` returns a package-private record type, so the
     * timer is pulled out reflectively; failures just leave the map empty.
     */
    private fun stageSeconds(planetiler: Planetiler): Map<String, Double> = runCatching {
        val all: Map<String, Any> = planetiler.stats().timers().all()
        all.mapValues { (_, stage) ->
            val method = stage.javaClass.getDeclaredMethod("timer").apply { isAccessible = true }
            val timer = method.invoke(stage) as Timer
            timer.elapsed().wall().toMillis() / 1000.0
        }
    }.getOrDefault(emptyMap())
}
