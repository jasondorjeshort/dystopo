package io.github.jasondorjeshort.dystopo.app.panorama

/** T327: what a Panorama target is: [key] as `GET /panorama` and `POST /panorama?target=<kind>:<name>` name it. */
enum class TargetKind(val key: String) {
    PEAK("peak"), SUN("sun"), FLIGHT("flight"), SATELLITE("satellite");

    companion object {
        fun of(key: String?): TargetKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * T327: the one thing the Panorama turns to and points at (wave 45, T327 (2)): [kind], [id] (a peak's key `name@bearing`,
 * `sun`, a flight's hex, a satellite's id such as `gps-12`) and [name] as the edge arrow's line says it.
 */
data class PanoramaTarget(val kind: TargetKind, val id: String, val name: String)

/**
 * T327: the sky views' two picks (a peak's key, `HorizonUi.selected`; a flight's hex, `HorizonUi.selectedFlight`) and the
 * Panorama's [target]. The picks stay `HorizonUi`'s (one open state, choice (i)); the target is the Panorama's.
 */
data class TargetState(val peak: String? = null, val flight: String? = null, val target: PanoramaTarget? = null)

/**
 * T327: the target's rule, pure (the task's Done when): one target at a time, the newest choice.
 *
 * - A peak's pick and a flight's card are the target as they are picked (a row, a name on the plot, the route); letting the
 *   pick go (the pick's ×, a second tap on its row, the card's Close) lets the target go when it was that pick.
 * - The sun's row makes the sun the target while the sun is up; while it is down the row does nothing. A satellite's row
 *   or mark makes it the target. A second tap on the target's own row lets it go.
 * - At an opening, a pick kept across the switch (choice b) is the target: the peak, else the flight.
 * - Back lets go of the target first when it is the sun or a satellite (those have no pick of their own), then of a picked
 *   flight, then of a picked peak (the sky views' own ladder, `SkyViewEffects`); with nothing to let go Back is not used
 *   here, so the ladder goes on (a chosen point, then the view closes).
 *
 * [next] answers the new state and whether the view turns to the target ([Step.turn]): a new target turns it.
 */
object PanoramaTargets {
    sealed interface Event

    /** A peak picked ([key], its [name]) or let go (null). */
    data class PeakPicked(val key: String?, val name: String? = null) : Event

    /** A flight picked ([hex], its [label]) or let go (null). */
    data class FlightPicked(val hex: String?, val label: String? = null) : Event

    /** The sun's row (or its mark): [up] whether the sun stands above the horizon now. */
    data class SunTapped(val up: Boolean) : Event

    /** A satellite's row or mark. */
    data class SatelliteTapped(val id: String, val name: String) : Event

    /** Back. */
    data object Back : Event

    /** `POST /panorama?target=none`: the target and its pick let go. */
    data object LetGo : Event

    /** An opening with the picks kept across the switch. */
    data class Opened(val peak: String?, val peakName: String?, val flight: String?, val flightLabel: String?) : Event

    /** The state after an event; [turn] when the view turns to the (new) target; [used] false when Back found nothing to let go. */
    data class Step(val state: TargetState, val turn: Boolean, val used: Boolean = true)

    /** The sun's target id. */
    const val SUN_ID = "sun"

    fun next(s: TargetState, e: Event): Step = when (e) {
        is PeakPicked -> when {
            e.key == null -> Step(s.copy(peak = null, target = s.target.takeUnless { it?.kind == TargetKind.PEAK }), false)
            e.key == s.peak && s.target?.id == e.key -> Step(s, false)
            else -> Step(s.copy(peak = e.key, target = PanoramaTarget(TargetKind.PEAK, e.key, e.name ?: nameOfKey(e.key))), true)
        }
        is FlightPicked -> when {
            e.hex == null -> Step(s.copy(flight = null, target = s.target.takeUnless { it?.kind == TargetKind.FLIGHT }), false)
            e.hex == s.flight && s.target?.id == e.hex -> Step(s, false)
            else -> Step(s.copy(flight = e.hex, target = PanoramaTarget(TargetKind.FLIGHT, e.hex, e.label ?: e.hex)), true)
        }
        is SunTapped -> when {
            s.target?.kind == TargetKind.SUN -> Step(s.copy(target = null), false)
            !e.up -> Step(s, false)
            else -> Step(s.copy(target = PanoramaTarget(TargetKind.SUN, SUN_ID, SUN_NAME)), true)
        }
        is SatelliteTapped ->
            if (s.target?.kind == TargetKind.SATELLITE && s.target.id == e.id) Step(s.copy(target = null), false)
            else Step(s.copy(target = PanoramaTarget(TargetKind.SATELLITE, e.id, e.name)), true)
        Back -> when {
            s.target?.kind == TargetKind.SUN || s.target?.kind == TargetKind.SATELLITE -> Step(s.copy(target = null), false)
            s.flight != null -> Step(s.copy(flight = null, target = s.target.takeUnless { it?.kind == TargetKind.FLIGHT }), false)
            s.peak != null -> Step(s.copy(peak = null, target = s.target.takeUnless { it?.kind == TargetKind.PEAK }), false)
            else -> Step(s, false, used = false)
        }
        LetGo -> when (s.target?.kind) {
            null -> Step(s, false)
            TargetKind.PEAK -> Step(s.copy(peak = null, target = null), false)
            TargetKind.FLIGHT -> Step(s.copy(flight = null, target = null), false)
            else -> Step(s.copy(target = null), false)
        }
        is Opened -> {
            val t = when {
                e.peak != null -> PanoramaTarget(TargetKind.PEAK, e.peak, e.peakName ?: nameOfKey(e.peak))
                e.flight != null -> PanoramaTarget(TargetKind.FLIGHT, e.flight, e.flightLabel ?: e.flight)
                else -> null
            }
            Step(TargetState(e.peak, e.flight, t), t != null)
        }
    }

    /** The sun's name on its arrow ("Sun, 40° right"): the Sun layer's own word. */
    const val SUN_NAME = "Sun"

    /** A peak's name from its key (`name@bearing in tenths`, `HorizonUi.keyOf`). */
    fun nameOfKey(key: String): String = key.substringBeforeLast('@')
}
