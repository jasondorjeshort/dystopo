package io.github.jasondorjeshort.dystopo.dem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Height reference systems of elevation sources. Everything is converted to [EGM2008], the
 * system-wide internal datum, before compositing, so sources agree at their seams.
 */
enum class VerticalDatum {
    /** Global geoid model; used by Copernicus DEM. The internal datum. */
    EGM2008,

    /**
     * North American Vertical Datum of 1988 and its US siblings; used by USGS 3DEP. Realized
     * through an NGS hybrid geoid chosen by location (see [Datums.HYBRID_GEOIDS]): GEOID18 in
     * CONUS (NAVD88) and Puerto Rico / USVI (PRVD02, VIVD09), GEOID12B in Alaska (NAVD88) and
     * Hawaii (the USGG2012-based local datum).
     */
    NAVD88,

    /**
     * Swiss national heights: LN02 (the official levelling network of 1902, used by swissALTI3D,
     * vertical EPSG:5728) and its successor LHN95 (EPSG:5729). Realized through swisstopo's
     * CHGeo2004 geoid, one PROJ grid per system ([Datums.SWISS_GEOIDS]); LN02 differs from LHN95 by
     * -0.2 to +0.5 m, growing with height, so the matching grid is used rather than a constant.
     */
    LN02,
}

/**
 * A swisstopo CHGeo2004 grid on the PROJ CDN: Swiss height + undulation = ETRS89 ellipsoidal
 * height. ETRS89 is taken as WGS84 (they differ by centimetres vertically).
 */
data class SwissGeoid(
    /** Vertical CRS the heights are in (5728 LN02, 5729 LHN95). */
    val verticalEpsg: Int,
    /** Registry id in sources.json. */
    val registryId: String,
    /** File name on the PROJ CDN. */
    val file: String,
)

/** Geoid height N: meters from the reference ellipsoid up to the geoid at a location. */
fun interface GeoidModel {
    fun undulation(lat: Double, lon: Double): Double
}

/** NAD83 realizations a hybrid geoid can be referenced to. Their origins differ by ~10 cm. */
enum class Nad83Frame {
    /** NAD83(2011): CONUS, Alaska, Puerto Rico and the US Virgin Islands. */
    NAD83_2011,

    /** NAD83(PA11): Pacific plate (Hawaii, American Samoa). */
    NAD83_PA11,
}

/**
 * An NGS hybrid geoid grid: US vertical datum height + undulation = NAD83 ellipsoid height.
 * Bounds are the grid's extent in degrees; [east] may exceed 180 for grids that cross the
 * antimeridian (Alaska), and longitudes are matched modulo 360.
 */
data class HybridGeoid(
    /** Registry id in sources.json; credited when the grid converts any cell. */
    val registryId: String,
    /** File name on the PROJ CDN (https://cdn.proj.org/). */
    val file: String,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val frame: Nad83Frame,
) {
    private fun lonInside(lon: Double): Boolean =
        doubleArrayOf(lon, lon + 360, lon - 360).any { it in west..east }

    fun covers(lat: Double, lon: Double): Boolean = lat in south..north && lonInside(lon)

    /** True if the box overlaps the grid. [boxEast] < [boxWest] means the box crosses the antimeridian. */
    fun intersects(boxWest: Double, boxSouth: Double, boxEast: Double, boxNorth: Double): Boolean {
        if (boxNorth < south || boxSouth > north) return false
        val e = if (boxEast < boxWest) boxEast + 360 else boxEast
        for (shift in doubleArrayOf(0.0, 360.0, -360.0)) {
            if (e + shift >= west && boxWest + shift <= east) return true
        }
        return false
    }
}

object Datums {
    // ITRF2008 -> NAD83(2011) translation, meters (Pearson & Snay 2013; EPSG:7807). WGS84 (G1762+)
    // matches ITRF2008 to a few cm, far below DEM accuracy.
    private val T_2011 = doubleArrayOf(0.99343, -1.90331, -0.52655)

    // ITRF2008 -> NAD83(PA11) translation, meters (Pearson & Snay 2013; EPSG:7808).
    private val T_PA11 = doubleArrayOf(0.9080, -2.0161, -0.5653)

    /**
     * NGS hybrid geoids by location, first match wins. GEOID18 supersedes GEOID12B in CONUS and
     * Puerto Rico / USVI; NGS says to keep using GEOID12B in Alaska and Hawaii. Extents are the
     * PROJ-data grids' (https://cdn.proj.org/files.geojson).
     */
    val HYBRID_GEOIDS: List<HybridGeoid> = listOf(
        HybridGeoid("geoid18", "us_noaa_g2018u0.tif", -130.0, 24.0, -60.0, 58.0, Nad83Frame.NAD83_2011),
        HybridGeoid("geoid18-prvi", "us_noaa_g2018p0.tif", -69.0, 15.0, -64.0, 21.0, Nad83Frame.NAD83_2011),
        HybridGeoid("geoid12b-alaska", "us_noaa_g2012ba0.tif", 172.0, 49.0, 234.0, 72.0, Nad83Frame.NAD83_2011),
        HybridGeoid("geoid12b-hawaii", "us_noaa_g2012bh0.tif", -161.0, 18.0, -154.0, 24.0, Nad83Frame.NAD83_PA11),
    )

    /** The hybrid geoid to use at a location, or null where no US grid covers it. */
    fun hybridGeoidFor(lat: Double, lon: Double): HybridGeoid? = HYBRID_GEOIDS.firstOrNull { it.covers(lat, lon) }

    /** Hybrid geoids overlapping a box (see [HybridGeoid.intersects]); empty means US heights there can't be converted. */
    fun hybridGeoidsIntersecting(west: Double, south: Double, east: Double, north: Double): List<HybridGeoid> =
        HYBRID_GEOIDS.filter { it.intersects(west, south, east, north) }

    /**
     * Ellipsoid height difference h(WGS84) - h(NAD83) at a location. NAD83's origin is ~2.2 m
     * from Earth's center, so the same point has different ellipsoid heights in the two frames.
     * Uses the translation only: the transformation's rotations move points sideways, and its
     * scale changes heights by ~1 cm.
     */
    fun wgs84MinusNad83Height(lat: Double, lon: Double, frame: Nad83Frame = Nad83Frame.NAD83_2011): Double {
        val t = when (frame) {
            Nad83Frame.NAD83_2011 -> T_2011
            Nad83Frame.NAD83_PA11 -> T_PA11
        }
        val phi = lat * PI / 180
        val lambda = lon * PI / 180
        val ux = cos(phi) * cos(lambda)
        val uy = cos(phi) * sin(lambda)
        val uz = sin(phi)
        return -(t[0] * ux + t[1] * uy + t[2] * uz)
    }

    /**
     * Meters to add to a US vertical datum height to get an EGM2008 height:
     * height + hybrid geoid = NAD83 ellipsoid height -> WGS84 ellipsoid height -> minus EGM2008 geoid.
     * [hybrid] is the location's hybrid geoid model, referenced to [frame].
     */
    fun navd88ToEgm2008(
        lat: Double,
        lon: Double,
        hybrid: GeoidModel,
        egm2008: GeoidModel,
        frame: Nad83Frame = Nad83Frame.NAD83_2011,
    ): Double = hybrid.undulation(lat, lon) + wgs84MinusNad83Height(lat, lon, frame) - egm2008.undulation(lat, lon)

    /**
     * CHGeo2004 grids by vertical CRS. Both are CC0 (swisstopo via PROJ-data) and cover
     * 5.85-10.5 E, 45.75-47.85 N, a little more than Switzerland and Liechtenstein.
     */
    val SWISS_GEOIDS: List<SwissGeoid> = listOf(
        SwissGeoid(5728, "chgeo2004", "ch_swisstopo_chgeo2004_ETRS89_LN02.tif"),
        SwissGeoid(5729, "chgeo2004", "ch_swisstopo_chgeo2004_ETRS89_LHN95.tif"),
    )

    /** Extent of the CHGeo2004 grids: west, south, east, north. */
    val SWISS_GEOID_BOUNDS = doubleArrayOf(5.85, 45.75, 10.5, 47.85)

    fun swissGeoidFor(verticalEpsg: Int): SwissGeoid? = SWISS_GEOIDS.firstOrNull { it.verticalEpsg == verticalEpsg }

    /**
     * Meters to add to a Swiss (LN02 or LHN95) height to get an EGM2008 height: height + CHGeo2004
     * undulation = ETRS89 ellipsoidal height (= WGS84 to a few cm), minus the EGM2008 undulation.
     */
    fun swissToEgm2008(lat: Double, lon: Double, chgeo: GeoidModel, egm2008: GeoidModel): Double =
        chgeo.undulation(lat, lon) - egm2008.undulation(lat, lon)
}
