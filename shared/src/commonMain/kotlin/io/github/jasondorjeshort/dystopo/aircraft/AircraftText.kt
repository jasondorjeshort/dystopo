package io.github.jasondorjeshort.dystopo.aircraft

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * T311: the layer's words (ADR 0031: built from the task text, the owner adjusts them). User-visible text says
 * *Flights*; code and data say aircraft (the wave 41 paragraph). No other product names.
 */
object AircraftText {
    const val LAYER = "Flights"
    const val SETTING = "Flight tracking"
    const val ROUTE_UNKNOWN = "Route unknown"
    const val ROUTE_HEDGE = "From schedule data; may be wrong"
    const val CREDIT = "ADSB.lol contributors (ODbL)"
    const val REPLAY_CREDIT = "Recorded flights (a replay on this phone)"
    const val ESTIMATED_PREFIX = "Dotted: " // T314
    const val NO_POSITION = "Distance from you: waiting for your position"

    /** Vertical rate (feet a minute) past which the card says climbing or descending rather than level. */
    const val LEVEL_FPM = 300.0

    /** The airports of [m] as [naming] puts them on a label: "SEA > DEN", or "Seattle > Denver". */
    fun routeLabel(m: RoutePlausibility.Match, naming: AirportNaming): String? {
        val a = m.origin ?: return null
        val b = m.destination ?: return null
        return if (naming == AirportNaming.CITIES) "${city(a)} > ${city(b)}" else "${a.code} > ${b.code}"
    }

    /** The card's route line: "SEA Seattle > DEN Denver" (both), "SEA > DEN" (codes), "Seattle > Denver" (cities). */
    fun routeCard(m: RoutePlausibility.Match?, naming: AirportNaming): String {
        val a = m?.origin ?: return ROUTE_UNKNOWN
        val b = m.destination ?: return ROUTE_UNKNOWN
        fun one(x: Airport) = when (naming) {
            AirportNaming.BOTH -> x.city?.let { "${x.code} $it" } ?: "${x.code} ${x.name}"
            AirportNaming.CODES -> x.code
            AirportNaming.CITIES -> city(x)
        }
        return "${one(a)} > ${one(b)}"
    }

    private fun city(a: Airport) = a.city ?: a.name

    /**
     * T314: the card's line for the dotted estimate before the app saw the aircraft: "Dotted: estimated path from SEA"
     * (the city with [AirportNaming.CITIES]).
     */
    fun estimatedLine(from: Airport, naming: AirportNaming): String =
        "${ESTIMATED_PREFIX}estimated path from ${if (naming == AirportNaming.CITIES) city(from) else from.code}"

    /** The map label: the call sign (else registration, else the hex code), the route when plausible, the age when stale. */
    fun label(r: AircraftReport, m: RoutePlausibility.Match?, naming: AirportNaming, staleAgeS: Double?): String {
        val name = r.callSign ?: r.registration ?: r.hex.uppercase()
        val route = m?.takeIf { it.plausible }?.let { routeLabel(it, naming) }
        val age = staleAgeS?.let { " · ${it.roundToLong()} s ago" } ?: ""
        return if (route != null) "$name $route$age" else "$name$age"
    }

    /** "Airliner · BOEING 737-800 · N12345": the kind, the description (else the type code) and the registration. */
    fun typeLine(r: AircraftReport): String =
        listOfNotNull(kindName(r.kind), r.description ?: r.type, r.registration).joinToString(" · ").ifEmpty { "Type unknown" }

    /** The kind's name from the emitter category ("Helicopter"); null when the feed says nothing (the Horizon view's words). */
    fun kindName(k: AircraftKind): String? = when (k) {
        AircraftKind.LIGHT -> "Light aircraft"
        AircraftKind.AIRLINER -> "Airliner"
        AircraftKind.HEAVY -> "Heavy jet"
        AircraftKind.ROTORCRAFT -> "Helicopter"
        AircraftKind.GLIDER -> "Glider"
        AircraftKind.BALLOON -> "Balloon"
        AircraftKind.UAV -> "Drone"
        AircraftKind.GENERIC -> null
    }

    /** The flight level from a pressure altitude in feet ("FL370"), above [transitionFt] only; else null. */
    fun flightLevel(baroFt: Double?, transitionFt: Double = AircraftRules.TRANSITION_FT): String? {
        baroFt ?: return null
        if (baroFt <= transitionFt) return null
        val fl = (baroFt / 100.0).roundToLong()
        return "FL" + fl.toString().padStart(3, '0')
    }

    /**
     * "11,300 m · FL370, climbing" or "37,000 ft · FL370, climbing": the altitude in the units setting, the flight level
     * (from [baroFt]) above the transition altitude.
     */
    fun altitudeLine(meters: Double?, kind: AltitudeKind, verticalRateFpm: Double?, units: ContourUnits, baroFt: Double?): String {
        val base = altitudeLine(meters, kind, verticalRateFpm, units)
        if (kind == AltitudeKind.GROUND || meters == null) return base
        val fl = flightLevel(baroFt) ?: return base
        val comma = base.indexOf(", ")
        return if (comma < 0) "$base · $fl" else base.substring(0, comma) + " · $fl" + base.substring(comma)
    }

    /** "35000 ft, climbing"; " (pressure altitude)" when barometric; "On the ground"; "Altitude unknown". */
    fun altitudeLine(meters: Double?, kind: AltitudeKind, verticalRateFpm: Double?, units: ContourUnits): String {
        if (kind == AltitudeKind.GROUND) return "On the ground"
        if (meters == null) return "Altitude unknown"
        val trend = when {
            verticalRateFpm == null -> null
            verticalRateFpm > LEVEL_FPM -> "climbing"
            verticalRateFpm < -LEVEL_FPM -> "descending"
            else -> "level"
        }
        val h = grouped(UnitFormat.elevationValue(meters, units)) + if (units == ContourUnits.FEET) " ft" else " m"
        val base = if (kind == AltitudeKind.BAROMETRIC) "$h (pressure altitude)" else h
        return trend?.let { "$base, $it" } ?: base
    }

    /**
     * T316: the card's line under the altitude: "4,210 m above the ground" from a geometric altitude (to 10 m or 10 ft),
     * "About 4,200 m above the ground" from pressure altitude (to 100 m or 100 ft), in the units setting (ADR 0022).
     */
    fun aboveGroundLine(h: AboveGround, units: ContourUnits): String {
        val v = UnitFormat.elevationValue(h.meters, units).toDouble()
        val step = if (h.approximate && v >= 100.0) 100.0 else 10.0
        val n = (kotlin.math.round(v / step) * step).roundToLong().coerceAtLeast(if (v >= 5.0) 10 else 0)
        val text = grouped(n) + (if (units == ContourUnits.FEET) " ft" else " m") + " above the ground"
        return if (h.approximate) "About $text" else text
    }

    /** Ground speed in the units setting's road unit: "520 mph" or "837 km/h". */
    fun speedLine(knots: Double?, units: ContourUnits): String? {
        knots ?: return null
        val mps = knots * AircraftRules.KNOT_TO_MPS
        return if (units == ContourUnits.FEET) "${(mps * 3600 / UnitFormat.METERS_PER_MILE).roundToLong()} mph" else "${(mps * 3.6).roundToLong()} km/h"
    }

    /** "52 mi away, to the southeast (135°), 11° above the horizon". */
    fun fromYou(distanceM: Double?, bearingDeg: Double?, elevationDeg: Double?, units: ContourUnits): String {
        if (distanceM == null || bearingDeg == null) return NO_POSITION
        val b = bearingDeg.roundToLong() % 360
        val word = io.github.jasondorjeshort.dystopo.routing.Directions.compass(bearingDeg)
        val angle = elevationDeg?.let { e ->
            val d = abs(e)
            val n = if (d < 10) UnitFormat.fixed(d, 1) else d.roundToLong().toString()
            if (e >= 0) ", $n° above the horizon" else ", $n° below the horizon"
        } ?: ""
        return "${UnitFormat.distance(distanceM, units)} away, to the $word ($b°)$angle"
    }

    /** "12 s ago", "2 min ago". */
    fun age(ageS: Double): String {
        val s = ageS.roundToLong()
        return if (s < 120) "Position $s s ago" else "Position ${s / 60} min ago"
    }

    /** T316: a refresh choice's label: "20 s", "60 s", "2 min", "3 min" (the owner's words for the two new choices). */
    fun refreshLabel(s: Int): String = if (s >= 120 && s % 60 == 0) "${s / 60} min" else "$s s"

    /** T316: a span of seconds as the help says it: "30 s", "90 s", "3 min", "4 min 30 s". */
    fun seconds(s: Double): String {
        val n = s.roundToLong()
        return when {
            n < 120 -> "$n s"
            n % 60 == 0L -> "${n / 60} min"
            else -> "${n / 60} min ${n % 60} s"
        }
    }

    /** T316: how long the day's requests last, as the refresh row's help says it: "1 h 23 min", "16 h 40 min", "a whole day". */
    fun lasts(hours: Double): String {
        if (hours >= 24.0) return "a whole day"
        val min = (hours * 60).roundToLong()
        val h = min / 60
        val m = min % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    /**
     * T316: how long a day's requests last at each choice, for the refresh row's help: "1 h 23 min at 10 s, ..., 16 h 40
     * min at 2 min and a whole day at 3 min".
     */
    fun lastsList(choices: List<Int> = AircraftRules.REFRESH_CHOICES_S, daily: Int = AircraftRules.DAILY_REQUESTS): String {
        val parts = choices.map { "${lasts(AircraftRules.hoursLeft(daily, it))} at ${refreshLabel(it)}" }
        return if (parts.size < 2) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    /**
     * T316: the refresh row's line: "about 480 requests left today (UTC), about 3 h at this rate", the hours those
     * requests last at [refreshS] ("about 40 min" under an hour; "enough for the rest of the day at this rate" when they
     * outlast the UTC day at [nowMs]); while a 429's pause holds ([limited]) the limit's words; none left, no rate.
     */
    fun quotaLine(left: Int, limited: Boolean, refreshS: Int, nowMs: Long): String {
        if (limited) return "${FeedState.LIMITED.message}; about 0 requests left today (UTC)"
        val base = "about $left requests left today (UTC)"
        if (left <= 0) return base
        val hours = AircraftRules.hoursLeft(left, refreshS)
        val untilMidnightH = (AircraftQuota.DAY_MS - nowMs.mod(AircraftQuota.DAY_MS)) / 3_600_000.0
        val rate = when {
            hours >= untilMidnightH -> "enough for the rest of the day"
            hours < 1.0 -> "about ${(hours * 60).roundToLong().coerceAtLeast(1)} min"
            else -> "about ${hours.roundToLong()} h"
        }
        return "$base, $rate at this rate"
    }

    /** T316: the card help's hollow and gone times at [refreshS]: "Hollow after 30 s without a position, gone after 60 s". */
    fun staleWords(refreshS: Int): String =
        "Hollow after ${seconds(AircraftRules.staleS(refreshS))} without a position, gone after ${seconds(AircraftRules.dropS(refreshS))}"

    /** "35000" as "35,000". */
    fun grouped(v: Long): String {
        val s = abs(v).toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(s[i])
        }
        return (if (v < 0) "-" else "") + sb
    }

    /**
     * The card's lines in the finding's order (call sign is the title), [credit] last; T314: [estimatedFrom], the origin
     * of the dotted estimate when the map draws one, adds [estimatedLine] after the route's hedge.
     */
    fun card(a: AircraftNow, units: ContourUnits, naming: AirportNaming, credit: String, estimatedFrom: Airport? = null): List<String> = listOfNotNull(
        a.report.emergency?.let { "${it.line} (squawk ${it.squawk})" },
        routeCard(a.route?.takeIf { it.plausible }, naming),
        if (a.route?.plausible == true) ROUTE_HEDGE else null,
        if (a.route?.plausible == true) estimatedFrom?.let { estimatedLine(it, naming) } else null,
        typeLine(a.report),
        altitudeLine(a.altitudeM, a.altitudeKind, a.report.verticalRateFpm, units, a.report.altBaroFt),
        a.aboveGround?.let { aboveGroundLine(it, units) }, // T316
        speedLine(a.report.groundSpeedKt, units),
        fromYou(a.distanceM, a.bearingDeg, a.elevationDeg, units),
        age(a.ageS),
        credit,
    )

    /**
     * The trail's one fixed colour scale by altitude (metres above EGM2008), low warm to high cool, as tar1090's (orange
     * low, green at 10,000 ft, magenta at 40,000 ft) and the finding's common practice 1; unknown altitude grey.
     */
    val ALTITUDE_COLOURS: List<Pair<Double, String>> = listOf(
        0.0 to "#e8590c",
        600.0 to "#f59f00",
        1500.0 to "#d9c400",
        3000.0 to "#5cb85c",
        6000.0 to "#1c9a8e",
        9000.0 to "#2c6fbb",
        12000.0 to "#b0278c",
    )
    const val UNKNOWN_COLOUR = "#8a8a8a"
}
