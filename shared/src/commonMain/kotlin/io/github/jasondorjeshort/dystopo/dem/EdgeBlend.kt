package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * T357 (ADR 0066 decision 4): the one blend weight at a map's edge. A holding's cells count fully [BAND_M] metres and more
 * inside its box, not at all on or past the box's edge, and in between by a smoothstep of the distance to the nearest side,
 * so the step to the holding behind it (a coarser map, or the online source) is spread over the band with no kink at either
 * end. The weight is a pure function of the cell's position and the box: any two tiles or windows compute the same value at
 * the same place, so no seam falls on a tile boundary, and the map's tiles ([MergedTile]) and the sky views' terrain read
 * ([MergedTerrain]) agree at the edge because both call [weight].
 *
 * The band and the curve are the code's existing feather where a fine source meets a coarser one: 300 m
 * (`BestSource.GLOBAL_FEATHER_METERS`, the national and global elevation's join in `devtool tiles` and the server) and the
 * smoothstep of [Composite.over]. Pure, tested (`EdgeBlendTest`).
 */
object EdgeBlend {
    /** The band's width in metres (`BestSource.GLOBAL_FEATHER_METERS`). */
    const val BAND_M = 300.0

    /** Ground metres per degree of latitude (and of longitude at the equator), on the sphere Web Mercator uses. */
    const val M_PER_DEG = 111_319.49

    /** The cosine of latitude [lat] (degrees). */
    fun cosLat(lat: Double): Double = cos(lat * PI / 180.0)

    /** Metres from latitude [lat] to [b]'s nearer side north or south (negative outside). */
    fun latInsideM(b: PackBounds, lat: Double): Double = min(lat - b.minLat, b.maxLat - lat) * M_PER_DEG

    /** Degrees from longitude [lon] to [b]'s nearer side east or west (negative outside). */
    fun lonInsideDeg(b: PackBounds, lon: Double): Double = min(lon - b.minLon, b.maxLon - lon)

    /**
     * The weight from a cell's distances to the box's sides: [latM] metres north or south, [lonDeg] degrees east or west at a
     * latitude whose cosine is [cosLat]. [weight] and [MergedTerrain.read] (which keeps the row's and the column's parts) both
     * come here, so they give the same number at the same place.
     */
    fun weightOf(latM: Double, lonDeg: Double, cosLat: Double, bandM: Double): Double {
        if (bandM <= 0) return 1.0
        val d = min(latM, lonDeg * M_PER_DEG * cosLat)
        if (d >= bandM) return 1.0
        return smooth(d / bandM)
    }

    /** The distance in metres from ([lon], [lat]) inside [b] to its nearest side; negative outside. */
    fun insideM(b: PackBounds, lon: Double, lat: Double): Double = min(latInsideM(b, lat), lonInsideDeg(b, lon) * M_PER_DEG * cosLat(lat))

    /** The smoothstep of [t] clamped to 0..1. */
    fun smooth(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    /** The weight of [b]'s holding at ([lon], [lat]): 0 on or outside the box, 1 from [bandM] inside, a smoothstep between. */
    fun weight(b: PackBounds, lon: Double, lat: Double, bandM: Double = BAND_M): Double {
        if (lon <= b.minLon || lon >= b.maxLon || lat <= b.minLat || lat >= b.maxLat) return 0.0
        return weightOf(latInsideM(b, lat), lonInsideDeg(b, lon), cosLat(lat), bandM)
    }

    /**
     * [b] shrunk by [bandM] on every side: inside it every weight is 1 (null when the band closes it). The longitude margin is
     * taken at the box's latitude farthest from the equator, so the inner box never reaches into the band.
     */
    fun inner(b: PackBounds, bandM: Double = BAND_M): PackBounds? {
        val dLat = bandM / M_PER_DEG
        val c = cosLat(max(abs(b.minLat), abs(b.maxLat)).coerceAtMost(89.0))
        val dLon = bandM / (M_PER_DEG * c)
        val r = PackBounds(b.minLon + dLon, b.minLat + dLat, b.maxLon - dLon, b.maxLat - dLat)
        return r.takeIf { it.minLon < it.maxLon && it.minLat < it.maxLat }
    }

    /** [a] and [b] overlap (touching edges do not). */
    fun overlaps(a: PackBounds, b: PackBounds): Boolean = a.maxLon > b.minLon && a.minLon < b.maxLon && a.maxLat > b.minLat && a.minLat < b.maxLat

    /** [a] lies wholly inside [b]. */
    fun within(a: PackBounds, b: PackBounds): Boolean = a.minLon >= b.minLon && a.maxLon <= b.maxLon && a.minLat >= b.minLat && a.maxLat <= b.maxLat

    /** Whether [extent] reaches into [b]'s band: it overlaps the box and is not wholly inside the box's [inner] part. */
    fun reachesBand(b: PackBounds, extent: PackBounds, bandM: Double = BAND_M): Boolean {
        if (!overlaps(extent, b)) return false
        val i = inner(b, bandM) ?: return true
        return !within(extent, i)
    }
}
