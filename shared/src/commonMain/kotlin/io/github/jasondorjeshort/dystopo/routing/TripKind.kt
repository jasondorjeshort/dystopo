package io.github.jasondorjeshort.dystopo.routing

/**
 * T276: what a route is for, held beside its [TravelMode] (never a new mode: a mode's label is the server's `mode=`, every
 * stored `path.mode` and what older peers read). An [EVERYDAY] route (a walk across town, a drive) shows no water, terrain,
 * best-start or past-effort lines on the route card and, under navigation, no turn-back clock, no next water and no next
 * terrain shade; a [BACKCOUNTRY] route keeps them all. [key] is the value in `GET /route`'s `tripKind`, `POST /route`'s
 * `kind=` and the un-kept route's file.
 */
enum class TripKind(val key: String) {
    EVERYDAY("everyday"), BACKCOUNTRY("backcountry");

    companion object {
        fun of(key: String?): TripKind? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** T276: why a route has its [TripKind]: its mode alone, the share of it in town, a tapped chip, or no share known. */
enum class KindReason(val key: String) {
    MODE("mode"), SHARE("share"), TAP("tap"), UNKNOWN("unknown");

    companion object {
        fun of(key: String?): KindReason? = entries.firstOrNull { it.key == key }
    }
}

/** T276: a route's kind, why, and on foot the share of its metres in town ([TripKinds.townShare]; null when not known). */
data class KindChoice(val kind: TripKind, val reason: KindReason, val townShare: Double? = null)

/**
 * T276: the route card's five chips. Walk and Hike are one pedestrian routing ([TravelMode.HIKE]); the chip a person taps
 * says which kind they mean. [label] is the chip's word. [kind] is the chip's own kind on foot; T278: a Bike route's kind
 * comes from its share ([TripKinds.guess]), [BIKE]'s [kind] being only what a bike reads as when nothing else is known.
 */
enum class RouteChip(val key: String, val label: String, val mode: TravelMode, val kind: TripKind) {
    WALK("walk", "Walk", TravelMode.HIKE, TripKind.EVERYDAY),
    HIKE("hike", "Hike", TravelMode.HIKE, TripKind.BACKCOUNTRY),
    BIKE("bike", "Bike", TravelMode.BIKE, TripKind.BACKCOUNTRY),
    DRIVE("drive", "Drive", TravelMode.DRIVE, TripKind.EVERYDAY),
    LINE("line", "Line", TravelMode.CROW, TripKind.BACKCOUNTRY);

    /** Whether this chip is the lit one for [mode] and [kind] (a foot chip only once the kind is known). */
    fun lit(mode: TravelMode, kind: TripKind?): Boolean = this.mode == mode && (mode != TravelMode.HIKE || kind == this.kind)

    companion object {
        /** The chip for [mode] and [kind]; null on foot while the kind is not known (no foot chip is lit). */
        fun of(mode: TravelMode, kind: TripKind?): RouteChip? = entries.firstOrNull { it.lit(mode, kind) }
    }
}

/**
 * T276: the kind of a route (pure). By mode: Drive is everyday and Line backcountry. On foot the planner guesses from where
 * the route runs ([guess]); a tapped Walk or Hike chip wins. T278: a Bike route is guessed the same way from its share in
 * town, at [BIKE_TOWN_SHARE] (no chip overrides it: an everyday Bike card keeps the folded Trip details row instead).
 *
 * The town classes and the threshold come from the table of `devtool route` over public areas in the T276 report (the
 * Chamonix streets and a valley trail at Mont Blanc, Greenwich's streets and park, a street at Sea-Tac, a trail and the
 * park road at Rainier): roads from [WayClass.SERVICE] up, footways, steps and cycleways count as town; paths, tracks and
 * bridleways do not, and neither does any way carrying a `sac_scale` grade, whatever its class (the paved trails at
 * Paradise are footways with a grade). A route with at least [TOWN_SHARE] of its metres in town is a walk.
 */
object TripKinds {
    /** The way classes counted as roads and streets. */
    val TOWN_CLASSES: Set<WayClass> = setOf(
        WayClass.FOOTWAY, WayClass.STEPS, WayClass.CYCLEWAY,
        WayClass.SERVICE, WayClass.MINOR, WayClass.TERTIARY, WayClass.SECONDARY, WayClass.PRIMARY, WayClass.TRUNK, WayClass.MOTORWAY,
    )

    /** The share of a route's metres in town from which a route on foot is a walk (see the class comment). */
    const val TOWN_SHARE = 0.75

    /**
     * T278: the share of a bike route's metres in town from which it is everyday. From the table of `devtool route --mode
     * bike` over public areas in the T278 report (Chamonix's streets and valley cycleway, Greenwich's streets and park,
     * Sea-Tac's streets, Rainier's park road and its trails): the same classes as on foot.
     */
    const val BIKE_TOWN_SHARE = 0.75

    /** T278: the share from which a route in [mode] is everyday ([TOWN_SHARE] on foot, [BIKE_TOWN_SHARE] on a bike). */
    fun threshold(mode: TravelMode): Double = if (mode == TravelMode.BIKE) BIKE_TOWN_SHARE else TOWN_SHARE

    /** The kind [mode] fixes by itself; null on foot and (T278) on a bike, where the route decides. */
    fun byMode(mode: TravelMode): TripKind? = when (mode) {
        TravelMode.HIKE, TravelMode.BIKE -> null
        TravelMode.DRIVE -> TripKind.EVERYDAY
        TravelMode.CROW -> TripKind.BACKCOUNTRY
    }

    /**
     * T278: the kind of a kept trip in [mode] (its stored `path.mode`, null when this build does not know it) with the kind
     * [stored] with it: the stored kind, else as by the mode before T278 (a Drive everyday; on foot, on a bike, a Line or a
     * mode this build does not know, backcountry), so every trip kept before T278 reads as it did.
     */
    fun kept(mode: TravelMode?, stored: TripKind?): TripKind =
        stored ?: if (mode == TravelMode.DRIVE) TripKind.EVERYDAY else TripKind.BACKCOUNTRY

    /**
     * T278: the kind a kept trip is navigated with: [kept], except that a Bike is navigated as backcountry whatever its
     * kind (the planner navigates the route on the map by its own gate; a kept ride keeps the clock, as before T278). An
     * everyday trip (a kept Walk, a Drive) starts with no turn-back clock, no next water and no next shade.
     */
    fun navKind(mode: TravelMode?, stored: TripKind?): TripKind =
        if (mode == TravelMode.BIKE) TripKind.BACKCOUNTRY else kept(mode, stored)

    /**
     * T278: whether a card offers a route's water, terrain, best-start and past-effort lines: a backcountry route (folded
     * under the planner's Trip details row; the trip card's water line), and an everyday Bike route as its override (a bike's
     * kind has no chip to change it; the survey's override row). On foot the Walk and Hike chips override; a Drive has none.
     */
    fun hasDetails(mode: TravelMode?, kind: TripKind?): Boolean = kind != TripKind.EVERYDAY || mode == TravelMode.BIKE

    /** T278: the chip a kept trip lights ([kept]): Walk or Hike on foot by its kind, else its mode's; null for an unknown mode. */
    fun chipOf(mode: TravelMode?, stored: TripKind?): RouteChip? = mode?.let { RouteChip.of(it, kept(it, stored)) }

    /**
     * The share of the route's metres in town: [metersByClass] in [TOWN_CLASSES] less the graded metres among them
     * ([gradedMetersByClass]), over all metres. Null when [metersByClass] is empty (a server's route, a restored one).
     */
    fun townShare(metersByClass: Map<WayClass, Double>, gradedMetersByClass: Map<WayClass, Double> = emptyMap()): Double? {
        val total = metersByClass.values.sum()
        if (metersByClass.isEmpty() || total <= 0.0) return null
        val town = metersByClass.entries.sumOf { (c, m) -> if (c in TOWN_CLASSES) (m - (gradedMetersByClass[c] ?: 0.0)).coerceAtLeast(0.0) else 0.0 }
        return town / total
    }

    /**
     * The planner's guess for a route on foot: Walk from [TOWN_SHARE] in town, else Hike; an unknown share is Hike, never
     * Walk. T278: on a bike ([mode]) everyday from [BIKE_TOWN_SHARE]; an unknown share is backcountry.
     */
    fun guess(metersByClass: Map<WayClass, Double>, gradedMetersByClass: Map<WayClass, Double> = emptyMap(), mode: TravelMode = TravelMode.HIKE): KindChoice {
        val share = townShare(metersByClass, gradedMetersByClass) ?: return KindChoice(TripKind.BACKCOUNTRY, KindReason.UNKNOWN)
        return KindChoice(if (share >= threshold(mode)) TripKind.EVERYDAY else TripKind.BACKCOUNTRY, KindReason.SHARE, share)
    }

    /** The kind of a route in [mode]: the mode's own, else a [tapped] chip's, else [guess]. */
    fun choose(mode: TravelMode, metersByClass: Map<WayClass, Double>, gradedMetersByClass: Map<WayClass, Double> = emptyMap(), tapped: TripKind? = null): KindChoice {
        byMode(mode)?.let { return KindChoice(it, KindReason.MODE) }
        if (tapped != null) return KindChoice(tapped, KindReason.TAP, townShare(metersByClass, gradedMetersByClass))
        return guess(metersByClass, gradedMetersByClass, mode)
    }

    /** [choose] for a planned route. */
    fun of(plan: PlannedRoute, tapped: TripKind? = null): KindChoice = choose(plan.mode, plan.metersByClass, plan.gradedMetersByClass, tapped)
}
