package io.github.jasondorjeshort.dystopo.gnss

import kotlin.math.abs
import kotlin.math.roundToInt

/** A satellite system, as the phone's GNSS status names it. [code] is the JSON and dump word, [label] what the screen shows. */
enum class Constellation(val code: String, val label: String) {
    GPS("gps", "GPS"),
    GLONASS("glonass", "GLONASS"),
    GALILEO("galileo", "Galileo"),
    BEIDOU("beidou", "BeiDou"),
    QZSS("qzss", "QZSS"),
    IRNSS("irnss", "NavIC"),
    SBAS("sbas", "SBAS"),
    UNKNOWN("unknown", "Other"),
    ;

    companion object {
        fun of(code: String): Constellation? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/**
 * A GNSS frequency band, from a signal's carrier frequency ([of]). L1 holds every system's first signal (GPS L1,
 * Galileo E1, BeiDou B1, GLONASS G1, 1559 to 1610 MHz); the others are the second signals a dual-band phone adds.
 */
enum class Band(val label: String, private val loMhz: Double, private val hiMhz: Double) {
    L1("L1", 1559.0, 1610.0),
    L2("L2", 1215.0, 1256.0),
    L5("L5", 1164.0, 1189.0),
    E5B("E5b", 1189.0, 1215.0),
    E6("E6", 1256.0, 1300.0),
    ;

    companion object {
        fun of(carrierHz: Double?): Band? {
            val mhz = (carrierHz ?: return null) / 1e6
            return entries.firstOrNull { mhz >= it.loMhz && mhz < it.hiMhz }
        }
    }
}

/**
 * One signal in the phone's GNSS status: a satellite on one band ([carrierHz], null where the phone does not report
 * it). [cn0] is the carrier-to-noise density in dB-Hz, 0 for a satellite the phone knows of but does not track;
 * [elevationDeg] and [azimuthDeg] (clockwise from true north) where it stands in the sky.
 */
data class Satellite(
    val constellation: Constellation,
    val svid: Int,
    val cn0: Double,
    val elevationDeg: Double,
    val azimuthDeg: Double,
    val used: Boolean,
    val ephemeris: Boolean = false,
    val almanac: Boolean = false,
    val carrierHz: Double? = null,
) {
    val band: Band? get() = Band.of(carrierHz)

    /** The phone hears it (a C/N0 above zero). */
    val tracked: Boolean get() = cn0 > 0

    /** The satellite itself, whatever the band: a dual-band satellite is two signals with one id. */
    val id: String get() = "${constellation.code}-$svid"
}

/**
 * T307: what the GPS view's summary and "Check my GPS" read from the phone's GNSS status: the counts, the four best
 * signals, the systems and bands heard ([summarize]), and the check's verdict over a run of samples ([judge]). Pure.
 *
 * The verdict (the GPS view's idea of 2026-11-19 and its two surveys, deleted with T307, in git; no app the survey found gives
 * one): each satellite's median C/N0 over the run; T4 the mean of the four strongest of the best constellation, over the
 * satellites above the terrain horizon (all of them where fewer than four are). Healthy: T4 at least [HEALTHY_T4] and at
 * least [MIN_USED] used. A hardware fault is suspected when T4 is under [FAULT_T4], no satellite above the terrain
 * reaches [STRONG_ANY], and at least [MANY_IN_VIEW] are heard above it (indoors hears few). Anything else is a weak signal.
 * A satellite below the terrain's horizon (plus [HORIZON_MARGIN_DEG]) is expected to be weak and never counts against
 * the phone. The 35 rests on the two published figures (a test tool's 38 dB-Hz pass mark for the four strongest GPS
 * satellites in open sky, and a 2021 study's phone mean of about 36); the 25 is unsourced and to be calibrated on real
 * phones. Time to first fix is reported, never judged.
 */
object GnssHealth {
    const val HEALTHY_T4 = 35.0
    const val FAULT_T4 = 25.0
    const val STRONG_ANY = 30.0
    const val MIN_USED = 4
    const val MANY_IN_VIEW = 8

    /** A satellite less than this above the terrain's horizon counts as behind it (diffraction at the ridge's edge). */
    const val HORIZON_MARGIN_DEG = 2.0

    /** The check stops when the phone moves farther than this from where it started, metres. */
    const val MAX_MOVE_M = 20.0

    /** The check's length, seconds (the idea: two minutes standing still). */
    const val CHECK_SECONDS = 120

    /** The view's summary of one status. */
    data class Summary(
        val inView: Int,
        val used: Int,
        /** The four strongest C/N0 of the satellites heard, strongest first (fewer when fewer are heard). */
        val best4: List<Double>,
        val constellations: List<Constellation>,
        val bands: List<Band>,
        /** "L1 + L5", "L1 only", or null when the phone reports no carrier frequencies. */
        val bandLine: String?,
    ) {
        val best4Mean: Double? get() = best4.takeIf { it.isNotEmpty() }?.average()
    }

    fun summarize(satellites: List<Satellite>): Summary {
        val heard = satellites.filter { it.tracked }
        val bySat = heard.groupBy { it.id }
        val strongest = bySat.values.map { s -> s.maxOf { it.cn0 } }.sortedDescending()
        val bands = heard.mapNotNull { it.band }.distinct().sortedBy { it.ordinal }
        return Summary(
            inView = bySat.size,
            used = bySat.values.count { s -> s.any { it.used } },
            best4 = strongest.take(4),
            constellations = heard.map { it.constellation }.distinct().sortedBy { it.ordinal },
            bands = bands,
            bandLine = bandLine(bands),
        )
    }

    /** The band line: the bands heard joined with "+", "L1 only" for a single-band phone, null when none is reported. */
    fun bandLine(bands: List<Band>): String? = when {
        bands.isEmpty() -> null
        bands == listOf(Band.L1) -> "L1 only"
        else -> bands.joinToString(" + ") { it.label }
    }

    /** Whether a satellite at ([elevationDeg], [azimuthDeg]) stands behind the terrain ([horizon]: angle by azimuth, degrees). */
    fun behind(elevationDeg: Double, azimuthDeg: Double, horizon: ((Double) -> Double)?): Boolean =
        elevationDeg < (horizon?.invoke(azimuthDeg) ?: 0.0).coerceAtLeast(0.0) + HORIZON_MARGIN_DEG

    /** One reading of the status during the check, at [atMs]. */
    class Sample(val atMs: Long, val satellites: List<Satellite>)

    enum class Kind(val code: String) { HEALTHY("healthy"), WEAK("weak"), FAULT("fault") }

    /** Why a weak verdict is weak, or a remark beside any verdict. */
    enum class Note(val code: String) {
        /** No satellite was heard at all. */
        NONE_HEARD("noneHeard"),
        /** Few satellites heard above the terrain: indoors or under cover. */
        FEW_IN_VIEW("fewInView"),
        /** The signals are weaker than open sky gives. */
        LOW_SIGNAL("lowSignal"),
        /** Fewer than [MIN_USED] satellites in the fix. */
        FEW_USED("fewUsed"),
        /** Some of the satellites heard stood behind the terrain. */
        BEHIND_TERRAIN("behindTerrain"),
        /** Other systems were heard but no GPS satellite. */
        NO_GPS("noGps"),
        /** No terrain horizon was known: only the flat horizon was used. */
        NO_HORIZON("noHorizon"),
    }

    /** One satellite's figures over the run. */
    data class SatelliteRun(val id: String, val constellation: Constellation, val medianCn0: Double, val elevationDeg: Double, val azimuthDeg: Double, val behind: Boolean)

    data class Verdict(
        val kind: Kind,
        /** The mean of the four strongest of the best constellation (above the terrain), dB-Hz; null with nothing heard. */
        val t4: Double?,
        val t4Constellation: Constellation?,
        val usedMedian: Int,
        val inViewMedian: Int,
        /** Satellites heard above the terrain's horizon, and behind it. */
        val aboveHorizon: Int,
        val behindHorizon: Int,
        /** The strongest median C/N0 above the terrain, dB-Hz. */
        val strongestAbove: Double?,
        val samples: Int,
        val seconds: Double,
        val ttffMs: Long?,
        val bands: List<Band>,
        val constellations: List<Constellation>,
        val notes: List<Note>,
        val satellites: List<SatelliteRun>,
    )

    /**
     * The verdict over [samples] (the check's readings, one a second), with the terrain [horizon] where the phone stands
     * (null: none known, the flat horizon) and the session's time to first fix [ttffMs] (reported only).
     */
    fun judge(samples: List<Sample>, horizon: ((Double) -> Double)?, ttffMs: Long? = null): Verdict {
        val heard = samples.flatMap { it.satellites }.filter { it.tracked }
        // Per satellite: its signals' C/N0 over the run (the strongest band of each sample), the median of those.
        val perSample = samples.map { s -> s.satellites.filter { it.tracked }.groupBy { it.id }.mapValues { (_, v) -> v.maxBy { it.cn0 } } }
        val runs = heard.map { it.id }.distinct().map { id ->
            val seen = perSample.mapNotNull { it[id] }
            val last = seen.last()
            SatelliteRun(id, last.constellation, median(seen.map { it.cn0 }), last.elevationDeg, last.azimuthDeg, behind(last.elevationDeg, last.azimuthDeg, horizon))
        }.sortedByDescending { it.medianCn0 }
        val above = runs.filter { !it.behind }
        val pool = if (above.size >= 4) above else runs
        val byConstellation = pool.groupBy { it.constellation }.filterValues { it.size >= 4 }
        val (t4, t4c) = if (byConstellation.isNotEmpty()) {
            byConstellation.map { (c, rs) -> rs.take(4).map { it.medianCn0 }.average() to c }.maxBy { it.first }
        } else if (pool.isNotEmpty()) {
            pool.take(4).map { it.medianCn0 }.average() to pool.take(4).map { it.constellation }.distinct().singleOrNull()
        } else null to null
        val used = medianInt(samples.map { s -> s.satellites.filter { it.used }.map { it.id }.distinct().size })
        val inView = medianInt(samples.map { s -> s.satellites.filter { it.tracked }.map { it.id }.distinct().size })
        val strongest = above.maxOfOrNull { it.medianCn0 }
        val notes = ArrayList<Note>()
        val kind = when {
            t4 == null -> Kind.WEAK.also { notes += Note.NONE_HEARD }
            t4 >= HEALTHY_T4 && used >= MIN_USED -> Kind.HEALTHY
            t4 < FAULT_T4 && (strongest ?: 0.0) < STRONG_ANY && above.size >= MANY_IN_VIEW -> Kind.FAULT
            else -> Kind.WEAK.also {
                if (above.size < MANY_IN_VIEW && t4 < FAULT_T4) notes += Note.FEW_IN_VIEW
                if (t4 < HEALTHY_T4) notes += Note.LOW_SIGNAL
                if (used < MIN_USED) notes += Note.FEW_USED
            }
        }
        if (runs.any { it.behind } && kind != Kind.HEALTHY) notes += Note.BEHIND_TERRAIN
        val constellations = runs.map { it.constellation }.distinct().sortedBy { it.ordinal }
        if (constellations.isNotEmpty() && Constellation.GPS !in constellations) notes += Note.NO_GPS
        if (horizon == null) notes += Note.NO_HORIZON
        val seconds = if (samples.size < 2) 0.0 else (samples.last().atMs - samples.first().atMs) / 1000.0
        return Verdict(
            kind, t4?.let(::round1), t4c, used, inView, above.size, runs.size - above.size, strongest?.let(::round1),
            samples.size, seconds, ttffMs, heard.mapNotNull { it.band }.distinct().sortedBy { it.ordinal }, constellations, notes, runs,
        )
    }

    /** The verdict as JSON (the debug route, the dump): figures, never a position. */
    fun json(v: Verdict): Map<String, Any?> = linkedMapOf(
        "kind" to v.kind.code, "t4" to v.t4, "t4Constellation" to v.t4Constellation?.code, "usedMedian" to v.usedMedian,
        "inViewMedian" to v.inViewMedian, "aboveHorizon" to v.aboveHorizon, "behindHorizon" to v.behindHorizon,
        "strongestAbove" to v.strongestAbove, "samples" to v.samples, "seconds" to v.seconds, "ttffMs" to v.ttffMs,
        "bands" to v.bands.map { it.label }, "bandLine" to bandLine(v.bands), "constellations" to v.constellations.map { it.code },
        "notes" to v.notes.map { it.code },
    )

    /** One satellite as JSON. */
    fun json(s: Satellite, horizon: ((Double) -> Double)?): Map<String, Any?> = linkedMapOf(
        "constellation" to s.constellation.code, "svid" to s.svid, "cn0" to round1(s.cn0), "elevationDeg" to round1(s.elevationDeg),
        "azimuthDeg" to round1(s.azimuthDeg), "used" to s.used, "ephemeris" to s.ephemeris, "almanac" to s.almanac,
        "band" to s.band?.label, "carrierMhz" to s.carrierHz?.let { (it / 1e4).roundToInt() / 100.0 },
        "behindTerrain" to behind(s.elevationDeg, s.azimuthDeg, horizon),
    )

    /** The view's list order: used first, then by C/N0, strongest first, then by system and number. */
    fun listOrder(satellites: List<Satellite>): List<Satellite> =
        satellites.sortedWith(compareByDescending<Satellite> { it.used }.thenByDescending { it.cn0 }.thenBy { it.constellation.ordinal }.thenBy { it.svid })

    internal fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun medianInt(v: List<Int>): Int = if (v.isEmpty()) 0 else median(v.map { it.toDouble() }).let { (it + 1e-9).toInt() }

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

    /** Degrees apart on the compass, 0..180. */
    internal fun angleApart(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)
}

/** The sky plot's orientation (ADR 0044: the apps split; the setting "Sky plot", default [PHONE]). */
enum class SkyPlotOrientation(val key: String) {
    /** The plot turns with the phone's compass, so the satellite ahead is at the top. */
    PHONE("phone"),
    /** North at the top, as paper sky charts are drawn. */
    NORTH("north"),
    ;

    companion object {
        val DEFAULT = PHONE
        fun of(s: String): SkyPlotOrientation? = entries.firstOrNull { it.key == s.trim().lowercase() }
    }
}
