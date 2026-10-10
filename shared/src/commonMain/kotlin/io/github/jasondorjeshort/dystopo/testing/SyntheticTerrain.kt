package io.github.jasondorjeshort.dystopo.testing

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Deterministic, seeded synthetic terrain for offline tests (golden images, report regressions).
 *
 * A terrain is a continuous height function over a plane in meters, so the same landscape can be
 * sampled at any cell size (zoom). Coordinates: x grows east, y grows south (like grid rows), the
 * origin is the middle of the preset's nominal extent. Elevations are meters; there is no NaN.
 *
 * Features are built from smooth closed-form pieces plus seeded value noise for rolling ground.
 * Everything is pure Kotlin arithmetic, so a seed gives the same terrain on every platform (up to
 * the last ulp of `exp`/`cos`, which the golden tests tolerate).
 */
class SyntheticTerrain(
    val name: String,
    val seed: Int,
    /** Ground distance between samples of the nominal [grid]. */
    val cellSizeMeters: Double = 10.0,
    /** Nominal grid size in cells (square). */
    val size: Int = 512,
    private val features: List<Feature>,
) {
    /** One landform; [heightAt] returns meters added to the base. */
    sealed interface Feature

    /** Base level plus gentle seeded value noise: [amplitude] meters at about [wavelength] meters, and a regional tilt. */
    data class Rolling(val base: Double, val amplitude: Double, val wavelength: Double, val tiltPerKm: Double = 0.0) : Feature

    /** A volcano-like cone with a rounded summit. */
    data class Cone(val x: Double, val y: Double, val radius: Double, val height: Double) : Feature

    /**
     * A ridge from (x0, y0) to (x1, y1) with a summit at each end and a saddle in the middle.
     * The saddle is [saddleHeight]; the summits are [peakHeight]; [halfWidth] is the e-folding width.
     */
    data class Ridge(
        val x0: Double, val y0: Double, val x1: Double, val y1: Double,
        val peakHeight: Double, val saddleHeight: Double, val halfWidth: Double,
    ) : Feature

    /**
     * A bowl [depth] deep and [radius] wide, filled with flat water to [waterDepth] below the
     * surrounding ground: every sample under the water surface is set exactly to the lake level.
     */
    data class Lake(val x: Double, val y: Double, val radius: Double, val depth: Double, val waterDepth: Double) : Feature

    /**
     * A cliff band: ground on the east side of the line through (x, y) with direction ([dx], [dy])
     * is raised by [height] over a horizontal run of [run] meters (a run of 20 m for 150 m is ~80
     * degrees). The band fades out beyond [halfLength] meters from (x, y) along the line.
     */
    data class Cliff(
        val x: Double, val y: Double, val dx: Double, val dy: Double,
        val height: Double, val run: Double, val halfLength: Double,
    ) : Feature

    private val lakes = features.filterIsInstance<Lake>()

    /** Elevation in meters at ([x], [y]) meters from the origin. */
    fun elevationAt(x: Double, y: Double): Double {
        var h = 0.0
        for (f in features) {
            when (f) {
                is Rolling -> h += f.base + f.amplitude * fbm(x / f.wavelength, y / f.wavelength) - f.tiltPerKm * y / 1000.0
                is Cone -> {
                    val r = dist(x - f.x, y - f.y) / f.radius
                    // Linear flanks with a parabolic summit (r < 0.15) and foot (0.8..1.2), slopes matched.
                    h += f.height * when {
                        r >= 1.2 -> 0.0
                        r > 0.8 -> (1.2 - r) * (1.2 - r) / 0.8
                        r > 0.15 -> 1.0 - r
                        else -> 0.925 - r * r / 0.3
                    }
                }
                is Ridge -> {
                    val lx = f.x1 - f.x0
                    val ly = f.y1 - f.y0
                    val len = dist(lx, ly)
                    val ux = lx / len
                    val uy = ly / len
                    val px = x - f.x0
                    val py = y - f.y0
                    val t = (px * ux + py * uy) / len // 0 at one summit, 1 at the other
                    val d = -px * uy + py * ux // perpendicular distance
                    val crest = f.saddleHeight + (f.peakHeight - f.saddleHeight) * 0.5 * (1.0 + cos(2.0 * PI * t))
                    val ends = when {
                        t < 0.0 -> exp(-(t * len / f.halfWidth) * (t * len / f.halfWidth))
                        t > 1.0 -> exp(-((t - 1.0) * len / f.halfWidth) * ((t - 1.0) * len / f.halfWidth))
                        else -> 1.0
                    }
                    val crestAtEnds = if (t < 0.0 || t > 1.0) f.peakHeight else crest
                    h += crestAtEnds * ends * exp(-(d / f.halfWidth) * (d / f.halfWidth))
                }
                is Lake -> Unit // below
                is Cliff -> {
                    val len = dist(f.dx, f.dy)
                    val ux = f.dx / len
                    val uy = f.dy / len
                    val along = (x - f.x) * ux + (y - f.y) * uy
                    val across = (x - f.x) * uy - (y - f.y) * ux // positive to the east of a southward line
                    val fade = smoothstep(f.halfLength, f.halfLength * 0.7, kotlin.math.abs(along))
                    h += f.height * fade * smoothstep(-f.run / 2, f.run / 2, across)
                }
            }
        }
        // Lakes last: dig the bowl, then flood it to a flat surface.
        for ((k, lake) in lakes.withIndex()) {
            val r = dist(x - lake.x, y - lake.y) / lake.radius
            if (r >= 1.0) continue
            h -= lake.depth * (1.0 - r * r) * (1.0 - r * r)
            // One flat water surface; the shoreline is where the bowl rises above it.
            if (h < lakeLevels[k]) h = lakeLevels[k]
        }
        return h
    }

    /**
     * Water level of each [Lake] feature, in feature order: the ground at the lake's center
     * without any lakes, minus the bowl depth, plus the water depth.
     */
    val lakeLevels: List<Double> by lazy {
        val withoutLakes = SyntheticTerrain(name, seed, cellSizeMeters, size, features.filter { it !is Lake })
        lakes.map { withoutLakes.elevationAt(it.x, it.y) - it.depth + it.waterDepth }
    }

    /**
     * Samples the terrain into a [width] x [height] grid at [cellSizeMeters], centered on
     * ([centerX], [centerY]) meters. Sample (i, j) is at the cell center.
     */
    fun grid(
        width: Int = size,
        height: Int = size,
        cellSizeMeters: Double = this.cellSizeMeters,
        centerX: Double = 0.0,
        centerY: Double = 0.0,
    ): ElevationGrid {
        val values = FloatArray(width * height)
        for (j in 0 until height) {
            val y = centerY + (j + 0.5 - height / 2.0) * cellSizeMeters
            for (i in 0 until width) {
                val x = centerX + (i + 0.5 - width / 2.0) * cellSizeMeters
                values[j * width + i] = elevationAt(x, y).toFloat()
            }
        }
        return ElevationGrid(width, height, cellSizeMeters, values)
    }

    /** Same landscape with a different noise seed. */
    fun withSeed(seed: Int) = SyntheticTerrain(name, seed, cellSizeMeters, size, features)

    // --- seeded value noise -------------------------------------------------------------------

    /** Three octaves of value noise, roughly in -1..1. */
    private fun fbm(x: Double, y: Double): Double =
        (noise(x, y, 0) + 0.5 * noise(2 * x + 17.3, 2 * y - 4.1, 1) + 0.25 * noise(4 * x - 9.7, 4 * y + 31.9, 2)) / 1.75

    /** Smoothly interpolated lattice noise in -1..1. */
    private fun noise(x: Double, y: Double, octave: Int): Double {
        val xf = floor(x)
        val yf = floor(y)
        val xi = xf.toInt()
        val yi = yf.toInt()
        val sx = fade(x - xf)
        val sy = fade(y - yf)
        val a = lattice(xi, yi, octave)
        val b = lattice(xi + 1, yi, octave)
        val c = lattice(xi, yi + 1, octave)
        val d = lattice(xi + 1, yi + 1, octave)
        return lerp(lerp(a, b, sx), lerp(c, d, sx), sy)
    }

    /** A hash of (x, y, seed, octave) mapped to -1..1. Integer-only, so identical on every platform. */
    private fun lattice(x: Int, y: Int, octave: Int): Double {
        var h = seed * 0x27d4eb2d + octave * 0x165667b1
        h = h xor (x * 0x61c88647)
        h = (h xor (h ushr 15)) * 0x2c1b3c6d
        h = h xor (y * 0x5bd1e995.toInt())
        h = (h xor (h ushr 13)) * 0x297a2d39
        h = h xor (h ushr 16)
        return (h and 0xffffff) / 0xffffff.toDouble() * 2.0 - 1.0
    }

    companion object {
        private fun dist(dx: Double, dy: Double) = sqrt(dx * dx + dy * dy)
        private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
        private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

        /** 0 at [e0], 1 at [e1], smooth in between; [e0] may be greater than [e1]. */
        private fun smoothstep(e0: Double, e1: Double, v: Double): Double {
            val t = ((v - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        /**
         * Everything at once on a 512 x 512 grid of 10 m cells (5.12 km square): a cone in the
         * north-west, a ridge with two summits and a saddle in the north-east, a flat lake in the
         * south-west, a cliff band in the south-east, rolling ground everywhere.
         */
        val MIXED = SyntheticTerrain(
            name = "mixed", seed = 1,
            features = listOf(
                Rolling(base = 1000.0, amplitude = 18.0, wavelength = 700.0, tiltPerKm = 8.0),
                Cone(x = -1250.0, y = -1150.0, radius = 1150.0, height = 650.0),
                Ridge(x0 = 350.0, y0 = -1900.0, x1 = 1900.0, y1 = -450.0, peakHeight = 520.0, saddleHeight = 300.0, halfWidth = 520.0),
                Lake(x = -1200.0, y = 1250.0, radius = 950.0, depth = 90.0, waterDepth = 40.0),
                Cliff(x = 1250.0, y = 1350.0, dx = 0.35, dy = 1.0, height = 160.0, run = 25.0, halfLength = 1100.0),
            ),
        )

        /** A lone cone on rolling ground: concentric contours, labels all around, hillshade on every aspect. */
        val CONE = SyntheticTerrain(
            name = "cone", seed = 2,
            features = listOf(
                Rolling(base = 400.0, amplitude = 12.0, wavelength = 500.0),
                Cone(x = 0.0, y = 0.0, radius = 2000.0, height = 900.0),
            ),
        )

        /** A cliff band above a lake, close together: contour thinning next to a flat shoreline. */
        val LAKE_CLIFF = SyntheticTerrain(
            name = "lake-cliff", seed = 3,
            features = listOf(
                Rolling(base = 1600.0, amplitude = 10.0, wavelength = 400.0, tiltPerKm = 10.0),
                Lake(x = -700.0, y = 300.0, radius = 1300.0, depth = 80.0, waterDepth = 37.0),
                Cliff(x = 450.0, y = 0.0, dx = 0.2, dy = 1.0, height = 220.0, run = 20.0, halfLength = 1800.0),
            ),
        )

        val presets: List<SyntheticTerrain> = listOf(MIXED, CONE, LAKE_CLIFF)

        fun preset(name: String): SyntheticTerrain = presets.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("unknown terrain preset '$name'; one of ${presets.map { it.name }}")
    }
}
