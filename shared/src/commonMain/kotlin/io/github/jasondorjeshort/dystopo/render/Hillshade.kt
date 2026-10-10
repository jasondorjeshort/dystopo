package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Hillshade {
    /** Light directions blended by [multidirectional], clockwise from north. */
    val multiAzimuths = doubleArrayOf(225.0, 270.0, 315.0, 360.0)

    /** Fixed weight added to each light in [multiAzimuths], on top of its per-cell cross-light weight. */
    private val multiBias = doubleArrayOf(0.0, 0.25, 0.75, 0.25)

    /**
     * Lambertian hillshade using Horn's 3x3 gradient.
     *
     * @param azimuthDeg direction the light comes from, clockwise from north (315 = north-west)
     * @param altitudeDeg light angle above the horizon
     * @param zFactor vertical exaggeration
     * @return brightness per cell in 0..1, same layout as [grid]. Flat ground gets sin(altitude).
     *   NaN where the grid has no data.
     */
    fun compute(
        grid: ElevationGrid,
        azimuthDeg: Double = 315.0,
        altitudeDeg: Double = 45.0,
        zFactor: Double = 1.0,
    ): FloatArray {
        val light = Light(azimuthDeg, altitudeDeg)
        val out = FloatArray(grid.width * grid.height)
        hornGradient(grid, zFactor) { i, dzdx, dzdy ->
            out[i] = light.shade(dzdx, dzdy, 1.0 / sqrt(dzdx * dzdx + dzdy * dzdy + 1.0)).toFloat()
        }
        return out
    }

    /**
     * Multidirectional hillshade: a blend of lights from [multiAzimuths] (south-west through north),
     * weighted per cell by how much each light grazes across the slope rather than along it.
     *
     * The weight of a light at azimuth `a` on a cell facing `aspect` is sin²(aspect − a): a light
     * shining straight down or straight up the fall line gets no weight, one from the side gets the
     * most. Single-azimuth shading turns faces that point away from the light black and washes out
     * faces that point into it; cross-lighting keeps texture (gullies, ribs) readable on steep
     * faces of every aspect. Pure cross-lighting loses the sense of one light direction (every
     * steep face ends up the same mid grey), so each light also gets a fixed [multiBias] weight
     * that favors the north-west: the map still reads as lit from the upper left, but faces
     * turned away from that light keep their texture instead of clamping to black.
     * Flat ground still gets sin(altitude).
     */
    fun multidirectional(
        grid: ElevationGrid,
        altitudeDeg: Double = 45.0,
        zFactor: Double = 1.0,
    ): FloatArray {
        val lights = multiAzimuths.indices.map { Light(multiAzimuths[it], altitudeDeg, multiBias[it]) }
        val out = FloatArray(grid.width * grid.height)
        hornGradient(grid, zFactor) { i, dzdx, dzdy ->
            val invNorm = 1.0 / sqrt(dzdx * dzdx + dzdy * dzdy + 1.0)
            val g = sqrt(dzdx * dzdx + dzdy * dzdy)
            var sum = 0.0
            var weights = 0.0
            for (light in lights) {
                // sin² of the angle between the light's horizontal direction and the aspect.
                // The downhill unit vector is -(dzdx, dzdy)/g; its cross product with the light's
                // unit (east, north) direction is that sine. Flat ground: equal weights.
                val w = light.bias + if (g == 0.0) 1.0 else {
                    val s = (-dzdx * light.uy + dzdy * light.ux) / g
                    s * s
                }
                sum += w * light.shade(dzdx, dzdy, invNorm)
                weights += w
            }
            out[i] = if (weights > 0.0) (sum / weights).toFloat() else Float.NaN
        }
        return out
    }

    private class Light(azimuthDeg: Double, altitudeDeg: Double, val bias: Double = 0.0) {
        private val az = azimuthDeg * PI / 180.0
        private val alt = altitudeDeg * PI / 180.0

        // Horizontal unit direction toward the light, (east, north).
        val ux = sin(az)
        val uy = cos(az)

        // Light vector in (east, north, up).
        private val lx = ux * cos(alt)
        private val ly = uy * cos(alt)
        private val lz = sin(alt)

        /** Surface normal (-dzdx, -dzdy, 1) normalized by [invNorm], dotted with the light, clamped at 0. */
        fun shade(dzdx: Double, dzdy: Double, invNorm: Double): Double =
            ((-dzdx * lx - dzdy * ly + lz) * invNorm).coerceAtLeast(0.0)
    }
}
