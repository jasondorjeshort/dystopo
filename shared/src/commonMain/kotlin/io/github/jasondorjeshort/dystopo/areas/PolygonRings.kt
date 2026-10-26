package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * T140 (ADR 0041 §1): helpers for the rings of an [AreaShape.Polygon]: rings are open (first point not repeated), the
 * inside is decided by the even-odd rule and rings never overlap, so a ring is an outer ring (a part) when an even
 * number of other rings hold it and a hole when an odd number do, whatever its orientation.
 */
object PolygonRings {
    private const val R = 6371008.8

    /** Even-odd: is ([lat], [lon]) inside [ring] (degrees, the ring taken as straight in longitude and latitude)? */
    fun contains(ring: List<AreaPoint>, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > lat) != (b.lat > lat) && lon < a.lon + (lat - a.lat) / (b.lat - a.lat) * (b.lon - a.lon)) inside = !inside
            j = i
        }
        return inside
    }

    /** How many other rings hold each ring (by its first point): even for an outer ring, odd for a hole. */
    fun depths(rings: List<List<AreaPoint>>): List<Int> = rings.mapIndexed { i, ring ->
        val p = ring.first()
        rings.indices.count { k -> k != i && contains(rings[k], p.lat, p.lon) }
    }

    /** The outer rings (the parts), without their holes. */
    fun outer(rings: List<List<AreaPoint>>): List<List<AreaPoint>> = if (rings.size == 1) rings else depths(rings).let { d -> rings.filterIndexed { i, _ -> d[i] % 2 == 0 } }

    /** The rings' box, grown by [bufferM] metres (as a corridor's is). */
    fun bounds(rings: List<List<AreaPoint>>, bufferM: Double): PackBounds {
        val all = rings.flatten()
        val minLat = all.minOf { it.lat }
        val maxLat = all.maxOf { it.lat }
        val dLat = bufferM / AreaOfInterest.M_PER_DEG
        val maxAbsLat = minOf(AreaOfInterest.MAX_LAT, max(abs(minLat), abs(maxLat)) + dLat)
        val dLon = bufferM / (AreaOfInterest.M_PER_DEG * cos(maxAbsLat * PI / 180))
        return PackBounds(
            (all.minOf { it.lon } - dLon).coerceAtLeast(-180.0), (minLat - dLat).coerceAtLeast(-AreaOfInterest.MAX_LAT),
            (all.maxOf { it.lon } + dLon).coerceAtMost(180.0), (maxLat + dLat).coerceAtMost(AreaOfInterest.MAX_LAT),
        )
    }

    /** The area of one ring on the sphere in square metres (unsigned). */
    fun ringAreaM2(ring: List<AreaPoint>): Double {
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += (b.lon - a.lon) * PI / 180 * (2 + sin(a.lat * PI / 180) + sin(b.lat * PI / 180))
        }
        return abs(sum * R * R / 2)
    }

    /** The area inside [rings] by even-odd, in square metres: the outer rings less their holes (rings do not overlap). */
    fun areaM2(rings: List<List<AreaPoint>>): Double {
        val d = depths(rings)
        return rings.indices.sumOf { i -> if (d[i] % 2 == 0) ringAreaM2(rings[i]) else -ringAreaM2(rings[i]) }.coerceAtLeast(0.0)
    }

    /**
     * A ring of [n] vertices around [center] whose edges lie [radiusM] or more from it (the vertices at radius / cos(pi / n)),
     * so the polygon holds the whole disc; counter-clockwise from north, as RFC 7946 wants an outer ring.
     */
    fun circle(center: AreaPoint, radiusM: Double, n: Int): List<AreaPoint> {
        val d = radiusM / cos(PI / n) / R
        val p1 = center.lat * PI / 180
        val l1 = center.lon * PI / 180
        return (0 until n).map { i ->
            val brg = -2 * PI * i / n // counter-clockwise: north, then west
            val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(brg))
            val l2 = l1 + atan2(sin(brg) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
            AreaPoint(p2 * 180 / PI, ((l2 * 180 / PI + 540) % 360) - 180)
        }
    }
}
