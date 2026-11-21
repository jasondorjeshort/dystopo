package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.aircraft.AircraftGeo
import io.github.jasondorjeshort.dystopo.aircraft.AircraftNow
import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.HorizonProfile
import kotlin.math.PI

/**
 * T312: the Horizon view's "Sky range" setting (ADR 0044: the apps split, the finding
 * `docs/findings/2026-11-20-aircraft-layer-sources-and-display.md` recommends it): which aircraft the Flights layer draws.
 * [OVERHEAD]: above [HorizonFlights.OVERHEAD_DEG] (the finding's 45°, one app's "Overhead" preset); [NEAR]: within
 * [HorizonFlights.NEAR_M] along the ground (50 km, a first guess); [ALL] (the default, the finding): every aircraft the feed
 * reports around the phone.
 */
enum class SkyRange(val key: String, val label: String) {
    OVERHEAD("overhead", "Overhead"),
    NEAR("near", "Near"),
    ALL("all", "All");

    /** Whether an aircraft at [elevationDeg] above the horizontal and [distanceM] along the ground is in this range. */
    fun admits(elevationDeg: Double, distanceM: Double): Boolean = when (this) {
        OVERHEAD -> elevationDeg >= HorizonFlights.OVERHEAD_DEG
        NEAR -> distanceM <= HorizonFlights.NEAR_M
        ALL -> true
    }

    companion object {
        val DEFAULT = ALL
        fun of(s: String): SkyRange? = entries.firstOrNull { it.key == s.trim().lowercase() }
    }
}

/** T312: a point on the sky plot: a true bearing and an elevation angle (degrees). */
data class SkyPoint(val bearingDeg: Double, val elevationDeg: Double)

/**
 * T312: one aircraft on the Horizon view's plot: [aircraft] as T311's tracker draws it now, seen from the plot's observer
 * at [bearingDeg] and [elevationDeg] (T311's [AircraftGeo.elevationAngleDeg], the earth's curvature in), [distanceM]
 * along the ground; [hidden] behind the terrain ([HorizonFlights.hiddenByTerrain]), [stale] its position older than T311's
 * `AircraftRules.staleS(refreshS)` (T316: the tracker's rule, scaled with the refresh interval); drawn [hollow] when either; [label] "call sign > destination code"; [tail] its positions of the
 * last [HorizonFlights.TAIL_S], oldest first, ending at the dot; [horizonDeg] the terrain's angle at its bearing (null: no profile).
 */
data class SkyFlight(
    val aircraft: AircraftNow,
    val bearingDeg: Double,
    val elevationDeg: Double,
    val distanceM: Double,
    val horizonDeg: Double?,
    val hidden: Boolean,
    val stale: Boolean,
    val label: String,
    val tail: List<SkyPoint>,
    /** The tail's span in seconds (its oldest point to now). */
    val tailS: Double,
) {
    val hollow: Boolean get() = hidden || stale
    val hex: String get() = aircraft.hex
}

/**
 * T312: the Horizon view's Flights layer, pure: T311's aircraft ([AircraftNow], memory only) placed on the sky plot from the
 * plot's own observer, the terrain's hiding tested on the plot's own [HorizonProfile], the "Sky range" filter, the tails, the
 * labels and the list's order (closest first, as the Peaks list).
 *
 * **Observer:** with a profile, its point and `groundM + observerHeightM` (the height the profile's angles were swept from,
 * so the hiding test compares like with like); without one, the phone's fix.
 *
 * **Hidden** ([hiddenByTerrain]): below the terrain's angle at the aircraft's bearing **and** farther than the ridge that
 * makes that angle (`HorizonProfile.distanceAt`): a helicopter below the ridge line but nearer than the ridge is in sight.
 * The profile's angles carry terrestrial refraction (`Horizon.REFRACTION_K`), so the aircraft's angle is raised by the same
 * bending ([refractionDeg], k θ / 2) for the test only; the dot stands at T311's geometric angle (0.02° apart at 30 km).
 */
object HorizonFlights {
    /** The "Overhead" sky range: above this elevation angle (degrees; the finding's 45°). */
    const val OVERHEAD_DEG = 45.0

    /** The "Near" sky range: within this distance along the ground (metres; a first guess). */
    const val NEAR_M = 50_000.0

    /** The tail behind each dot (seconds; the finding's 60 to 90 s, tar1090's 90 s, T311's trail). */
    const val TAIL_S = 90.0

    /** Where the plot looks from: a point and its eye height above EGM2008 (metres). */
    data class Eye(val lat: Double, val lon: Double, val heightM: Double) {
        companion object {
            /** The profile's own point and height. */
            fun of(p: HorizonProfile) = Eye(p.lat, p.lon, p.groundM + p.observerHeightM)
        }
    }

    /** The refraction's lift of a target [groundM] away (degrees): k θ / 2, the profile's coefficient. */
    fun refractionDeg(groundM: Double, k: Double = Horizon.REFRACTION_K): Double = k * (groundM / AircraftGeo.R) / 2 * 180 / PI

    /**
     * Whether a target at [elevationDeg] (geometric), [distanceM] along the ground toward [bearingDeg], is hidden by [profile]'s
     * terrain: its apparent angle below the terrain's and farther than the ridge's distance.
     */
    fun hiddenByTerrain(profile: HorizonProfile, bearingDeg: Double, elevationDeg: Double, distanceM: Double): Boolean =
        elevationDeg + refractionDeg(distanceM) < profile.angleAt(bearingDeg) && distanceM > profile.distanceAt(bearingDeg)

    /** The plot's label: "UAL123 > DEN" with a plausible route, the call sign alone without one. */
    fun label(a: AircraftNow): String {
        val dest = a.route?.takeIf { it.plausible }?.destination?.code
        return if (dest != null) "${a.title} > $dest" else a.title
    }

    /**
     * [aircraft] on the plot seen from [eye] (null: none drawn), hidden by [profile] (null: none hidden), within [range], at
     * [nowMs]: closest first. An aircraft on the ground is left out ([onGround]); one with no altitude has no angle and is left
     * out too ([unplaced] counts them).
     */
    fun of(aircraft: List<AircraftNow>, eye: Eye?, profile: HorizonProfile?, range: SkyRange, nowMs: Long): List<SkyFlight> {
        eye ?: return emptyList()
        val from = nowMs - (TAIL_S * 1000).toLong()
        return aircraft.mapNotNull { a ->
            if (onGround(a)) return@mapNotNull null
            val h = a.altitudeM ?: return@mapNotNull null
            val d = AircraftGeo.distanceM(eye.lat, eye.lon, a.lat, a.lon)
            val b = AircraftGeo.bearingDeg(eye.lat, eye.lon, a.lat, a.lon)
            val e = AircraftGeo.elevationAngleDeg(d, eye.heightM, h)
            if (!range.admits(e, d)) return@mapNotNull null
            val pts = a.trail.filter { it.atMs >= from }
            val tail = pts.mapNotNull { p ->
                val ph = p.altitudeM ?: h
                val pd = AircraftGeo.distanceM(eye.lat, eye.lon, p.lat, p.lon)
                SkyPoint(AircraftGeo.bearingDeg(eye.lat, eye.lon, p.lat, p.lon), AircraftGeo.elevationAngleDeg(pd, eye.heightM, ph))
            }
            SkyFlight(
                a, b, e, d, profile?.angleAt(b), profile?.let { hiddenByTerrain(it, b, e, d) } ?: false, a.stale, label(a), tail,
                pts.firstOrNull()?.let { (nowMs - it.atMs) / 1000.0 } ?: 0.0,
            )
        }.sortedWith(compareBy<SkyFlight> { it.distanceM }.thenBy { it.hex })
    }

    /** On the ground (the feed's word or the altitude's kind): left out of the layer and the list (the owner, 2026-11-20). */
    fun onGround(a: AircraftNow): Boolean = a.report.onGround || a.altitudeKind == io.github.jasondorjeshort.dystopo.aircraft.AltitudeKind.GROUND

    /** How many of [aircraft] are in the air with no altitude, so no angle (the route's count). */
    fun unplaced(aircraft: List<AircraftNow>): Int = aircraft.count { !onGround(it) && it.altitudeM == null }

    /**
     * The flights as labels for [HorizonLabels]' second pass ([LabelKind.AIRCRAFT], [SkyFlight] as each one's `ref`): the
     * clearance its angle over the terrain's at its bearing (+90 with no profile; negative when hidden), its altitude as the
     * elevation, no prominence.
     */
    fun labels(flights: List<SkyFlight>): List<HorizonLabel> = flights.map { f ->
        val clearance = when {
            f.horizonDeg == null -> 90.0
            f.hidden -> minOf(f.elevationDeg - f.horizonDeg, -0.01)
            else -> maxOf(f.elevationDeg - f.horizonDeg, 0.0)
        }
        HorizonLabel(f.label, LabelKind.AIRCRAFT, f.bearingDeg, f.elevationDeg, f.distanceM, clearance, f.aircraft.altitudeM ?: 0.0, 0.0, ref = f)
    }
}
