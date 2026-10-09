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

    /** North American Vertical Datum of 1988; used by USGS 3DEP (via GEOID18 on NAD83(2011)). */
    NAVD88,
}

/** Geoid height N: meters from the reference ellipsoid up to the geoid at a location. */
fun interface GeoidModel {
    fun undulation(lat: Double, lon: Double): Double
}

object Datums {
    // ITRF2008 -> NAD83(2011) translation, meters (Pearson & Snay 2013). WGS84 (G1762+) matches
    // ITRF2008 to a few cm, far below DEM accuracy.
    private const val TX = 0.99343
    private const val TY = -1.90331
    private const val TZ = -0.52655

    /**
     * Ellipsoid height difference h(WGS84) - h(NAD83(2011)) at a location. NAD83's origin is
     * ~2.2 m from Earth's center, so the same point has different ellipsoid heights in the two
     * frames. Uses the translation only: the transformation's rotations move points sideways,
     * and its scale changes heights by ~1 cm.
     */
    fun wgs84MinusNad83Height(lat: Double, lon: Double): Double {
        val phi = lat * PI / 180
        val lambda = lon * PI / 180
        val ux = cos(phi) * cos(lambda)
        val uy = cos(phi) * sin(lambda)
        val uz = sin(phi)
        return -(TX * ux + TY * uy + TZ * uz)
    }

    /**
     * Meters to add to a NAVD88 height to get an EGM2008 height:
     * NAVD88 + GEOID18 = NAD83 ellipsoid height -> WGS84 ellipsoid height -> minus EGM2008 geoid.
     */
    fun navd88ToEgm2008(lat: Double, lon: Double, geoid18: GeoidModel, egm2008: GeoidModel): Double =
        geoid18.undulation(lat, lon) + wgs84MinusNad83Height(lat, lon) - egm2008.undulation(lat, lon)
}
