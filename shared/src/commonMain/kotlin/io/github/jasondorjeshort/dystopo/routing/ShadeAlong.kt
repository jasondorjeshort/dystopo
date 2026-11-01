package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.RayMarch
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.dem.TerrainLayer
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.Sun
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import io.github.jasondorjeshort.dystopo.render.CastShadow
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan
import kotlin.time.TimeSource

/**
 * T194: the light at one sample of a route, at the time the walker reaches it. [key] is the JSON value.
 */
enum class ShadeLight(val key: String) {
    /** The sun is up and no terrain stands in front of it. */
    SUN("sun"),

    /** The sun is up but the terrain hides it (a ridge in front, or the slope itself facing away): terrain shade. */
    SHADE("shade"),

    /** The sun is at or below the flat horizon (before sunrise or after sunset). */
    DOWN("down"),

    /** No elevation data under the sample. */
    NO_DATA("noData"),
}

/** T194: one sample of [ShadeAlong.march]: where, when the walker gets there, the light then and the sun's position. */
class ShadeSample(
    val alongM: Double,
    val lat: Double,
    val lon: Double,
    val eleM: Double?,
    val atMs: Long,
    val light: ShadeLight,
    val sunAltitudeDeg: Double,
    val sunAzimuthDeg: Double,
)

/**
 * T194: a stretch of the route in one light ([ShadeAlong.stretches]): from [fromM] to [toM] along the line, reached at
 * [startMs] and left at [endMs] at the route's pace. [open] for the first stretch, which starts where the walker was when
 * it was worked out rather than where the light changed. [samples] it holds, [absorbed] shorter stretches merged into it.
 */
class ShadeStretch(
    val fromM: Double,
    val toM: Double,
    val light: ShadeLight,
    val startMs: Long,
    val endMs: Long,
    val open: Boolean,
    val samples: Int,
    val absorbed: Int,
) {
    val lengthM: Double get() = toM - fromM
    val minutes: Double get() = (endMs - startMs) / 60_000.0

    fun json(clock: ((Long) -> String)? = null): Map<String, Any?> = linkedMapOf(
        "fromM" to r1(fromM), "toM" to r1(toM), "lengthM" to r1(lengthM), "light" to light.key,
        "startMs" to startMs, "endMs" to endMs, "minutes" to r1(minutes),
        "start" to clock?.invoke(startMs), "end" to clock?.invoke(endMs),
        "open" to open, "samples" to samples, "absorbed" to absorbed,
    )
}

/**
 * T194: where the light changes along the route: [alongM] and [atMs] (when the walker gets there), the point, and the
 * light that follows ([light]: the terrain's shade, the sun going down, or the sun).
 */
class ShadeEvent(val alongM: Double, val atMs: Long, val lat: Double, val lon: Double, val light: ShadeLight) {
    fun json(clock: ((Long) -> String)? = null): Map<String, Any?> = linkedMapOf(
        "alongM" to r1(alongM), "atMs" to atMs, "at" to clock?.invoke(atMs), "lat" to r6(lat), "lon" to r6(lon),
        "then" to light.key, "cause" to when (light) { ShadeLight.SHADE -> "terrain"; ShadeLight.DOWN -> "sunset"; else -> null },
    )
}

/** T194: what one [ShadeAlong.compute] took: rays, ray samples, the terrain's zooms and cells, the time. */
class ShadeAlongStats(
    val rays: Int,
    val steps: Long,
    val zoom: Int,
    val coarseZoom: Int?,
    val cellsRead: Long,
    val rangeM: Double,
    val readMs: Double,
    val marchMs: Double,
    val terrainReused: Boolean,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "rays" to rays, "steps" to steps, "zoom" to zoom, "coarseZoom" to coarseZoom, "cellsRead" to cellsRead,
        "rangeM" to rangeM.roundToLong(), "readMs" to r1(readMs), "marchMs" to r1(marchMs), "terrainReused" to terrainReused,
    )
}

/**
 * T194: the light along a route from [fromM] on, for a walker there at [startMs]: the [samples], the [stretches] they
 * merge into, the first place the walker loses the sun ([firstSunLoss], the terrain's shade or the sunset; T193's idea's
 * "you lose the sun at the pass") and the first place the sun comes back ([firstSunGain]). [terrain] is what the rays
 * marched over (a later [ShadeAlong.compute] may reuse it); [frozen] when every sample was taken at [startMs].
 */
class ShadeAlongResult(
    val fromM: Double,
    val toM: Double,
    val startMs: Long,
    val samples: List<ShadeSample>,
    val stretches: List<ShadeStretch>,
    val firstSunLoss: ShadeEvent?,
    val firstSunGain: ShadeEvent?,
    val stats: ShadeAlongStats,
    val frozen: Boolean = false,
    val terrain: Terrain? = null,
) {
    /** When the walker reaches [alongM] (linear between samples; clamped to the samples' span). */
    fun timeAt(alongM: Double): Long = ShadeAlong.timeAt(samples, alongM)

    /** The index of the stretch holding [alongM], -1 past the end or before the start. */
    fun stretchAt(alongM: Double): Int = stretches.indexOfFirst { alongM >= it.fromM && alongM < it.toM }
        .let { if (it < 0 && stretches.isNotEmpty() && alongM >= stretches.last().fromM && alongM <= stretches.last().toM) stretches.size - 1 else it }

    /** Metres and minutes in each light. */
    fun totals(): Map<String, Any?> = ShadeLight.entries.associate { l ->
        val s = stretches.filter { it.light == l }
        l.key to linkedMapOf("m" to r1(s.sumOf { it.lengthM }), "minutes" to r1(s.sumOf { it.minutes }))
    }

    fun json(clock: ((Long) -> String)? = null): Map<String, Any?> = linkedMapOf(
        "fromM" to r1(fromM), "toM" to r1(toM), "startMs" to startMs, "start" to clock?.invoke(startMs), "frozen" to frozen,
        "sampleM" to ShadeAlong.SAMPLE_M, "absorbM" to ShadeAlong.ABSORB_M, "samples" to samples.size,
        "stretches" to stretches.map { it.json(clock) },
        "firstSunLoss" to firstSunLoss?.json(clock), "firstSunGain" to firstSunGain?.json(clock),
        "totals" to totals(), "stats" to stats.json(),
    )
}

/**
 * T194: terrain shade along a route at the time the walker reaches each point (the idea of 2026-10-31, "next shade on a
 * hot climb"). Terrain shade only, as T188's overlay draws it: trees, buildings and clouds are not counted, and every
 * word the user sees says "terrain shade".
 *
 * - **Samples** every [SAMPLE_M] along the line from the walker's place to the end, the end included ([plan]); each is
 *   given its arrival time from the start instant and the route's pace ([TurnAround]'s prefix sums: the clock's
 *   [io.github.jasondorjeshort.dystopo.nav.TurnPace], `CostModel.timeS` on each segment's climb and descent, T164's fitted
 *   curve when the user has one).
 * - **One ray per sample** toward the sun at its own instant ([Sun.position]; [RayMarch.maxTangent] from the ground, as
 *   [CastShadow] marches each cell of the overlay, with the same range rule and T194's low-sun cap,
 *   [CastShadow.rangeFor]): in terrain shade when any ground along it stands above the sun's line, the slope itself
 *   included. The sun at or below the flat horizon is [ShadeLight.DOWN].
 * - **The terrain** is read once for the route's box ([windows]: a fine layer [Horizon.NEAR_CELLS] cells around the
 *   samples, as [Horizon.windowsAround] gives a point, and a layer [Horizon.COARSE_ZOOMS] coarser reaching from every
 *   sample toward its sun as far as the ray looks), at the deepest zoom up to [Horizon]'s fine zoom whose fine window
 *   stays under [MAX_CELLS] cells.
 * - **Stretches** ([stretches]): the samples' runs of one light, each running to the midpoints between samples, with
 *   any run shorter than [ABSORB_M] absorbed (the shortest first, into a neighbour that matches on both sides, else the
 *   longer neighbour), plus the first sun-loss and sun-gain.
 *
 * The words are [ShadeText]'s; the spoken line's setting is [ShadeSpoken] with [HotDay]'s rule.
 */
object ShadeAlong {
    /**
     * Sample spacing, metres. 25 m is about 20 seconds of walking, so a stretch's minutes are good to well under one,
     * and a 10 km route is 400 rays (a fraction of a second); it is four samples to the shortest stretch kept
     * ([ABSORB_M]) and about the size of a route line's own error on a trail (the graph's points, the GPS fix).
     */
    const val SAMPLE_M = 25.0

    /**
     * Stretches shorter than this are absorbed into their neighbours, metres. Under 100 m is under two minutes at a
     * walking pace, too short to plan a rest in, and a shadow's edge on the ground moves by tens of metres with the
     * model's errors (the elevation model's cells and heights, the pace estimate, the sun moving between the phone's
     * recomputes); without it a ridge-crest trail flickers between sun and shade.
     */
    const val ABSORB_M = 100.0

    /** Cells a terrain window may hold before the next coarser zoom is used (4M floats, 16 MB). */
    const val MAX_CELLS = 4_000_000L

    /** The finest zoom read: z14 (6.5 m cells at 47 N), as the point answer ([io.github.jasondorjeshort.dystopo.tiles.ShadeTiles.POINT_ZOOM]). */
    const val MAX_ZOOM = 14

    /** One sample to march: where, the height on the line, when the walker gets there. */
    class Planned(val alongM: Double, val lat: Double, val lon: Double, val eleM: Double?, val atMs: Long)

    /**
     * The samples from [fromM] (clamped to the line) to the end every [sampleM], the end included, each timed from
     * [startMs] by [timing]'s onward times (the time from [fromM] to the sample at the route's pace); with [frozen] every
     * sample at [startMs] (the overlay's single instant, for a side-by-side check).
     */
    fun plan(path: NavPath, timing: TurnAround, fromM: Double, startMs: Long, sampleM: Double = SAMPLE_M, frozen: Boolean = false): List<Planned> {
        require(sampleM > 0) { "sampleM must be positive" }
        val total = path.totalM
        val from = fromM.coerceIn(0.0, total)
        val base = timing.onwardS(from)
        val out = ArrayList<Planned>()
        fun add(d: Double) {
            val p = path.pointAt(d)
            val dt = if (frozen) 0L else ((base - timing.onwardS(d)) * 1000).roundToLong().coerceAtLeast(0L)
            out += Planned(d, p.lat, p.lon, p.ele, startMs + dt)
        }
        var k = 0
        while (true) {
            val d = from + k * sampleM
            if (d >= total - 1e-6) break
            add(d)
            k++
        }
        add(total)
        return out
    }

    /**
     * The terrain windows the rays of [planned] need at reference [zoom], each with its band: the samples' box grown by
     * [Horizon.NEAR_CELLS] (plus two) cells, and (when the sun is up at any sample) [coarseZooms] coarser, the box of the
     * samples and of each sample's point [CastShadow.rangeFor] its sun's altitude away toward it, grown by two cells.
     * [coarseZooms] is raised while the coarse window would hold more than [MAX_CELLS].
     */
    fun windows(
        planned: List<Planned>, zoom: Int, tileSize: Int = 256, coarseZooms: Int = Horizon.COARSE_ZOOMS,
        capM: Double = Horizon.DEFAULT_RANGE_M, lowSunM: Double = Horizon.LOW_SUN_RANGE_M,
    ): List<Pair<MercatorWindow, Double>> {
        require(planned.isNotEmpty()) { "no samples" }
        var x0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY
        var y1 = Double.NEGATIVE_INFINITY
        for (p in planned) {
            val gx = WebMercator.lonToX(p.lon, zoom) * tileSize
            val gy = WebMercator.latToY(p.lat, zoom) * tileSize
            x0 = min(x0, gx); x1 = max(x1, gx); y0 = min(y0, gy); y1 = max(y1, gy)
        }
        val margin = Horizon.NEAR_CELLS + 2
        fun box(z: Int, ax: Double, ay: Double, bx: Double, by: Double, grow: Int): MercatorWindow {
            val s = 1.0 / (1L shl (zoom - z))
            val l = floor(ax * s).toInt() - grow
            val t = floor(ay * s).toInt() - grow
            val r = floor(bx * s).toInt() + grow
            val b = floor(by * s).toInt() + grow
            return MercatorWindow(z, l, t, r - l + 1, b - t + 1, tileSize)
        }
        // Each sunlit sample's farthest ray point, in reference pixels.
        var cx0 = x0; var cx1 = x1; var cy0 = y0; var cy1 = y1
        var anyUp = false
        for (p in planned) {
            val sun = Sun.position(p.lat, p.lon, p.atMs)
            if (sun.apparentAltitudeDeg <= 0) continue
            anyUp = true
            val r = CastShadow.rangeFor(sun.apparentAltitudeDeg, capM, lowSunM)
            val px = r / WebMercator.metersPerPixel(p.lat, zoom, tileSize)
            val gx = WebMercator.lonToX(p.lon, zoom) * tileSize + sin(sun.azimuthDeg * DEG) * px
            val gy = WebMercator.latToY(p.lat, zoom) * tileSize - cos(sun.azimuthDeg * DEG) * px
            cx0 = min(cx0, gx); cx1 = max(cx1, gx); cy0 = min(cy0, gy); cy1 = max(cy1, gy)
        }
        var coarse = zoom - coarseZooms
        if (!anyUp || coarseZooms <= 0 || coarse < 0) return listOf(box(zoom, x0, y0, x1, y1, margin) to Double.POSITIVE_INFINITY)
        var cw = box(coarse, cx0, cy0, cx1, cy1, 2)
        while (cw.width.toLong() * cw.height > MAX_CELLS && coarse > 0) {
            coarse--
            cw = box(coarse, cx0, cy0, cx1, cy1, 2)
        }
        return listOf(box(zoom, x0, y0, x1, y1, margin) to Horizon.NEAR_CELLS.toDouble(), cw to Double.POSITIVE_INFINITY)
    }

    /** The deepest zoom up to [maxZoom] (at least 1) whose fine window around [planned] holds at most [MAX_CELLS] cells. */
    fun fineZoom(planned: List<Planned>, maxZoom: Int, tileSize: Int = 256): Int {
        var z = maxZoom
        while (z > 1) {
            val w = windows(planned, z, tileSize, coarseZooms = 0).first().first
            if (w.width.toLong() * w.height <= MAX_CELLS) return z
            z--
        }
        return 1
    }

    /**
     * The light along [path] from [fromM] for a walker there at [startMs] at [timing]'s pace: [plan], [windows] at
     * [fineZoom] (up to [maxZoom], the pack's deepest capped at [MAX_ZOOM]), the terrain read by [read] (or [reuse]'s when
     * its windows already hold every one needed), [march] and [stretches]. [frozen]: every sample at [startMs].
     */
    fun compute(
        path: NavPath, timing: TurnAround, fromM: Double, startMs: Long, maxZoom: Int,
        read: (MercatorWindow) -> ElevationGrid, tileSize: Int = 256, frozen: Boolean = false, reuse: Terrain? = null,
        lowSunM: Double = Horizon.LOW_SUN_RANGE_M,
    ): ShadeAlongResult {
        val planned = plan(path, timing, fromM, startMs, frozen = frozen)
        val zoom = fineZoom(planned, min(maxZoom, MAX_ZOOM), tileSize)
        val need = windows(planned, zoom, tileSize, lowSunM = lowSunM)
        val clock = TimeSource.Monotonic
        val t0 = clock.markNow()
        val reused = reuse != null && covers(reuse, need)
        val terrain = if (reused) reuse!! else Terrain.read(need, read)
        val readMs = t0.elapsedNow().inWholeMicroseconds / 1000.0
        val t1 = clock.markNow()
        val (samples, steps) = march(planned, terrain, lowSunM = lowSunM)
        val marchMs = t1.elapsedNow().inWholeMicroseconds / 1000.0
        val st = stretches(samples, fromM.coerceIn(0.0, path.totalM), path.totalM)
        val (loss, gain) = events(st, samples)
        val stats = ShadeAlongStats(
            samples.size, steps, terrain.zoom, terrain.layers.getOrNull(1)?.window?.zoom,
            if (reused) 0L else need.sumOf { it.first.width.toLong() * it.first.height },
            samples.maxOf { if (it.sunAltitudeDeg > 0) CastShadow.rangeFor(it.sunAltitudeDeg, lowSunM = lowSunM) else 0.0 },
            readMs, marchMs, reused,
        )
        return ShadeAlongResult(fromM.coerceIn(0.0, path.totalM), path.totalM, startMs, samples, st, loss, gain, stats, frozen, terrain)
    }

    /** True when every window of [need] lies inside [terrain]'s layer of the same index, zoom and band. */
    fun covers(terrain: Terrain, need: List<Pair<MercatorWindow, Double>>): Boolean {
        if (terrain.layers.size != need.size) return false
        return need.indices.all { i ->
            val have = terrain.layers[i]
            val (w, until) = need[i]
            have.window.zoom == w.zoom && have.untilPx == until && w.x0 >= have.window.x0 && w.y0 >= have.window.y0 &&
                w.x0 + w.width <= have.window.x0 + have.window.width && w.y0 + w.height <= have.window.y0 + have.window.height
        }
    }

    /**
     * One ray per sample toward its sun over [terrain] from the ground ([eyeM] above it; 0, as the overlay's cells), its
     * range [CastShadow.rangeFor] the sun's altitude; returns the samples and the ray samples taken.
     */
    fun march(
        planned: List<Planned>, terrain: Terrain, eyeM: Double = 0.0, growth: Double = Horizon.STEP_GROWTH,
        capM: Double = Horizon.DEFAULT_RANGE_M, lowSunM: Double = Horizon.LOW_SUN_RANGE_M,
    ): Pair<List<ShadeSample>, Long> {
        val ray = RayMarch(terrain, capM, growth)
        val out = ArrayList<ShadeSample>(planned.size)
        for (p in planned) {
            val sun = Sun.position(p.lat, p.lon, p.atMs)
            val alt = sun.apparentAltitudeDeg
            val gx = terrain.gx(p.lon)
            val gy = terrain.gy(p.lat)
            val ground = terrain.sample(gx, gy)
            val light = when {
                alt <= 0 -> ShadeLight.DOWN
                ground.isNaN() || ground == Terrain.OUTSIDE -> ShadeLight.NO_DATA
                else -> {
                    val tanSun = tan(alt * DEG)
                    val mpp = WebMercator.metersPerPixel(p.lat, terrain.zoom, terrain.tileSize)
                    val east = sin(sun.azimuthDeg * DEG)
                    val north = cos(sun.azimuthDeg * DEG)
                    val t = ray.maxTangent(gx, gy, ground + eyeM, east, north, 1 / mpp, mpp, stopTan = tanSun, floorTan = tanSun, rangeM = CastShadow.rangeFor(alt, capM, lowSunM))
                    if (t >= tanSun) ShadeLight.SHADE else ShadeLight.SUN
                }
            }
            out += ShadeSample(p.alongM, p.lat, p.lon, p.eleM, p.atMs, light, alt, sun.azimuthDeg)
        }
        return out to ray.steps
    }

    /** When the walker reaches [alongM] by [samples]' times (linear between two; the first or last outside them). */
    fun timeAt(samples: List<ShadeSample>, alongM: Double): Long {
        if (samples.isEmpty()) return 0L
        if (alongM <= samples.first().alongM) return samples.first().atMs
        if (alongM >= samples.last().alongM) return samples.last().atMs
        var lo = 0
        var hi = samples.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].alongM <= alongM) lo = mid else hi = mid
        }
        val a = samples[lo]
        val b = samples[hi]
        val f = if (b.alongM > a.alongM) (alongM - a.alongM) / (b.alongM - a.alongM) else 0.0
        return a.atMs + ((b.atMs - a.atMs) * f).roundToLong()
    }

    private class Run(var light: ShadeLight, var fromM: Double, var toM: Double, var samples: Int, var absorbed: Int) {
        val length: Double get() = toM - fromM
    }

    /**
     * The stretches of [samples] (a run of one light reaching to the midpoints between samples, the first from [fromM],
     * the last to [toM]) with every run shorter than [absorbM] absorbed: the shortest first, into the neighbours when
     * both have one light, else into the longer neighbour (the earlier on a tie). One stretch at least when there are
     * samples; the first is [ShadeStretch.open].
     */
    fun stretches(samples: List<ShadeSample>, fromM: Double, toM: Double, absorbM: Double = ABSORB_M): List<ShadeStretch> {
        if (samples.isEmpty()) return emptyList()
        val runs = ArrayList<Run>()
        for (i in samples.indices) {
            val lo = if (i == 0) fromM else (samples[i - 1].alongM + samples[i].alongM) / 2
            val hi = if (i == samples.size - 1) toM else (samples[i].alongM + samples[i + 1].alongM) / 2
            val last = runs.lastOrNull()
            if (last != null && last.light == samples[i].light) {
                last.toM = hi
                last.samples++
            } else {
                runs += Run(samples[i].light, lo, hi, 1, 0)
            }
        }
        while (runs.size > 1) {
            var k = -1
            for (i in runs.indices) if (runs[i].length < absorbM && (k < 0 || runs[i].length < runs[k].length)) k = i
            if (k < 0) break
            val prev = runs.getOrNull(k - 1)
            val next = runs.getOrNull(k + 1)
            val r = runs[k]
            when {
                prev != null && next != null && prev.light == next.light -> {
                    prev.toM = next.toM
                    prev.samples += r.samples + next.samples
                    prev.absorbed += 1 + next.absorbed + r.absorbed
                    runs.removeAt(k + 1)
                    runs.removeAt(k)
                }
                next == null || (prev != null && prev.length >= next.length) -> {
                    prev!!.toM = r.toM
                    prev.samples += r.samples
                    prev.absorbed += 1 + r.absorbed
                    runs.removeAt(k)
                }
                else -> {
                    next.fromM = r.fromM
                    next.samples += r.samples
                    next.absorbed += 1 + r.absorbed
                    runs.removeAt(k)
                }
            }
        }
        return runs.mapIndexed { i, r -> ShadeStretch(r.fromM, r.toM, r.light, timeAt(samples, r.fromM), timeAt(samples, r.toM), i == 0, r.samples, r.absorbed) }
    }

    /**
     * The first place the walker loses the sun (a stretch in sun followed by terrain shade or the sun going down) and the
     * first place the sun comes back (shade or night followed by sun), with the point from [samples].
     */
    fun events(stretches: List<ShadeStretch>, samples: List<ShadeSample>): Pair<ShadeEvent?, ShadeEvent?> {
        var loss: ShadeEvent? = null
        var gain: ShadeEvent? = null
        fun dark(l: ShadeLight) = l == ShadeLight.SHADE || l == ShadeLight.DOWN
        for (i in 1 until stretches.size) {
            val a = stretches[i - 1]
            val b = stretches[i]
            if (loss == null && a.light == ShadeLight.SUN && dark(b.light)) loss = event(b, samples)
            if (gain == null && dark(a.light) && b.light == ShadeLight.SUN) gain = event(b, samples)
        }
        return loss to gain
    }

    private fun event(s: ShadeStretch, samples: List<ShadeSample>): ShadeEvent {
        val i = samples.indexOfFirst { it.alongM >= s.fromM }.coerceAtLeast(0)
        val p = samples[i]
        return ShadeEvent(s.fromM, s.startMs, p.lat, p.lon, s.light)
    }

    private const val DEG = PI / 180
}

/**
 * T194: the spoken shade line's setting (`shadeSpoken`, ADR 0044): never, on hot days only (the default: [HotDay]), or
 * before every long sunny stretch. The survey (2026-10-31) found no outdoor app with such a setting, so the three choices
 * rest on behaviour, not a published split: the line is noise on a cool day, and some walkers want it on every sunny
 * stretch (sun exposure, not only heat). [key] is the stored and synced value, [label] the row's option.
 */
enum class ShadeSpoken(val key: String, val label: String) {
    OFF("off", "Off"),
    HOT("hot", "On hot days"),
    ALWAYS("always", "Always");

    companion object {
        val DEFAULT = HOT

        /** By key or label, any case ("on hot days" too); null for anything else. */
        fun parse(raw: String?): ShadeSpoken? {
            val s = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key == s || it.label.lowercase() == s }
        }
    }
}

/**
 * T194: the hot-day rule of [ShadeSpoken.HOT]: the air temperature at the walker's height, the weather station's
 * temperature lowered at [LAPSE_C_PER_KM] (the standard atmosphere's lapse rate) for the height between them, at or above
 * [THRESHOLD_C]. The survey (2026-10-31): published heat guidance starts in the high 20s C (the UK's heatwave lines 25 to
 * 28, the US heat index's caution at 26.7, a watch maker's heat adaptation from 22), no source lowers a line for altitude
 * beyond the cooler air up high, which the lapse models, and humidity (a heat index) is not available offline and moves
 * the figure little there; so 25 C is a project choice inside the 22 to 27 band, not a citation. A station report older
 * than [MAX_AGE_MS] counts as no temperature.
 */
object HotDay {
    const val THRESHOLD_C = 25.0
    const val LAPSE_C_PER_KM = 6.5

    /** Station reports are hourly; three hours covers a missed fetch or two while the readout strip is hidden. */
    const val MAX_AGE_MS = 3 * 3_600_000L

    /** The station's [stationTempC] at [stationEleM] carried to [walkerEleM] by the lapse rate. */
    fun atHeight(stationTempC: Double, stationEleM: Double, walkerEleM: Double): Double =
        stationTempC - LAPSE_C_PER_KM * (walkerEleM - stationEleM) / 1000.0

    /** True when [tempAtWalkerC] (null: no temperature known) is a hot day. */
    fun hot(tempAtWalkerC: Double?): Boolean = tempAtWalkerC != null && tempAtWalkerC >= THRESHOLD_C

    /** Whether [setting] lets the spoken line be said with [tempAtWalkerC] at the walker (null: unknown, not hot). */
    fun allows(setting: ShadeSpoken, tempAtWalkerC: Double?): Boolean = when (setting) {
        ShadeSpoken.OFF -> false
        ShadeSpoken.ALWAYS -> true
        ShadeSpoken.HOT -> hot(tempAtWalkerC)
    }
}

/**
 * T194: the words of terrain shade along a route (pure; `ShadeAlongTest`; ADR 0031, the owner adjusts them). Every line
 * says "terrain shade": the model knows the ground only, not trees or clouds.
 */
object ShadeText {
    /** A stretch this close ahead is "just ahead"; a sunny stretch that began this far behind still counts as next, metres. */
    const val PASS_M = 30.0

    /** The spoken line is said before a sunny stretch longer than this, minutes (see the report: about one drink break). */
    const val LONG_SUN_MIN = 20.0

    /** How far ahead the spoken line is said, metres (the water line's walking lead, `WaterText.leadM`). */
    const val LEAD_M = 400.0

    /** "10 min", "1 h 5 min": a stretch's minutes on the card, at least one. */
    fun minutes(m: Double): String {
        val total = max(1, m.roundToInt())
        return if (total < 60) "$total min" else "${total / 60} h" + (if (total % 60 != 0) " ${total % 60} min" else "")
    }

    /** The spoken form: "25 minutes", "1 hour 5 minutes". */
    fun minutesSpoken(m: Double): String {
        val total = max(1, m.roundToInt())
        val h = total / 60
        val mm = total % 60
        val hs = if (h == 0) "" else if (h == 1) "1 hour" else "$h hours"
        val ms = if (mm == 0) "" else if (mm == 1) "1 minute" else "$mm minutes"
        return listOf(hs, ms).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** A distance ahead for the card: to 50 m below a kilometre (the samples are 25 m apart). */
    private fun ahead(m: Double, units: ContourUnits): String =
        UnitFormat.distance(if (m < 1000) (m / 50).roundToInt() * 50.0 else m, units)

    /**
     * The navigation card's line at [progressM]: in terrain shade "In terrain shade for 12 min more"; in sun the next
     * terrain shade "Terrain shade in 400 m for 10 min" ("Terrain shade just ahead for 10 min" within 50 m), "No terrain
     * shade ahead" or "No terrain shade before sunset"; null with the sun down at the walker, without data there, or past
     * the end.
     */
    fun cardLine(r: ShadeAlongResult, progressM: Double, units: ContourUnits): String? {
        val k = r.stretchAt(progressM)
        if (k < 0) return null
        val cur = r.stretches[k]
        return when (cur.light) {
            ShadeLight.DOWN, ShadeLight.NO_DATA -> null
            ShadeLight.SHADE -> "In terrain shade for ${minutes((cur.endMs - r.timeAt(progressM)) / 60_000.0)} more"
            ShadeLight.SUN -> {
                for (i in k + 1 until r.stretches.size) {
                    val s = r.stretches[i]
                    when (s.light) {
                        ShadeLight.SHADE -> {
                            val a = s.fromM - progressM
                            return if (a < 50) "Terrain shade just ahead for ${minutes(s.minutes)}"
                            else "Terrain shade in ${ahead(a, units)} for ${minutes(s.minutes)}"
                        }
                        ShadeLight.DOWN -> return "No terrain shade before sunset"
                        else -> continue
                    }
                }
                "No terrain shade ahead"
            }
        }
    }

    /**
     * Navigating: the index of the sunny stretch to announce now, or -1: the next stretch in sun that follows terrain
     * shade (not the open first one), lasts more than [longMin] minutes, starts no more than [PASS_M] behind
     * [progressM] and not before [saidUntilM] (the end of the stretch last announced, so a recompute never announces the
     * same sun twice); due once its start is within [leadM]. Only the next such stretch is considered, so a passed one is
     * never said late.
     */
    fun dueSpoken(r: ShadeAlongResult, progressM: Double, leadM: Double = LEAD_M, saidUntilM: Double = Double.NEGATIVE_INFINITY, longMin: Double = LONG_SUN_MIN): Int {
        val st = r.stretches
        val k = (1 until st.size).firstOrNull { i ->
            val s = st[i]
            s.light == ShadeLight.SUN && st[i - 1].light == ShadeLight.SHADE && s.minutes > longMin &&
                s.fromM >= progressM - PASS_M && s.fromM >= saidUntilM - 1e-6
        } ?: return -1
        return if (st[k].fromM - progressM <= leadM) k else -1
    }

    /**
     * The spoken line before sunny stretch [index]: "In 400 m, the terrain shade ends. Then sun for 25 minutes." (at it,
     * "The terrain shade ends here."; a stretch that runs to the end, "Then sun to the end, 25 minutes.").
     */
    fun spoken(r: ShadeAlongResult, index: Int, aheadM: Double, units: ContourUnits): String {
        val s = r.stretches[index]
        val head = if (aheadM < PASS_M) "The terrain shade ends here." else "In ${ahead(aheadM, units)}, the terrain shade ends."
        val toEnd = index == r.stretches.size - 1
        val m = minutesSpoken(s.minutes)
        return "$head " + if (toEnd) "Then sun to the end, $m." else "Then sun for $m."
    }
}

private fun r1(v: Double) = kotlin.math.round(v * 10) / 10
private fun r6(v: Double) = kotlin.math.round(v * 1e6) / 1e6
