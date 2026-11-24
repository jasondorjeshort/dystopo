package io.github.jasondorjeshort.dystopo.aircraft

/**
 * T364: who asks the flights' poller, as one pure rule (the idea of 2026-11-22, the architect's model A; the owner's
 * answers of 2026-11-23). An overlay is one data switch and one show key per view, and a view that does not show it
 * stops asking:
 *
 * - the data switch is Flight tracking ([Inputs.tracking], the synced `aircraft`): off, nobody asks, the poller forgets
 *   what it held and its ticker stops;
 * - the map asks while its own show key is on ([Inputs.mapShown], the synced `mapFlights`, "Show flights" in Map options),
 *   the app is in the foreground, no sky view covers it and it has a view;
 * - the sky views, Horizon, Panorama and List, ask as one owner ([SKY]) while one of them is open and their shared
 *   toggle is on ([Inputs.skyShown], `horizonFlights`) and they have a fix to ask around.
 *
 * With nobody asking the poller makes no request and builds and publishes nothing ([Plan.rebuild]); a view that stops
 * asking because the user hid it clears its marks once ([mapClears]).
 */
object FlightAsks {
    /** The owner names the poller keeps its requests under: the map's, and the one the three sky views share. */
    const val MAP = "map"
    const val SKY = "horizon"

    /** What the rule reads: the data switch, each view's show key, and whether each view could draw now. */
    data class Inputs(
        val tracking: Boolean,
        val mapShown: Boolean = true,
        val foreground: Boolean = true,
        val skyOpen: Boolean = false,
        val skyShown: Boolean = true,
        val mapHasView: Boolean = true,
        val skyHasFix: Boolean = true,
    )

    /**
     * Why the map does not ask, or null when it does, first reason first: `off` (tracking off), `hidden` (its show key
     * off), `background` (the app is not in the foreground), `horizonViewOpen` (a sky view covers it; the name the map's
     * `GET /aircraft` `mapAsksBecause` had before), `noView`.
     */
    fun mapReason(i: Inputs): String? = when {
        !i.tracking -> "off"
        !i.mapShown -> "hidden"
        !i.foreground -> "background"
        i.skyOpen -> "horizonViewOpen"
        !i.mapHasView -> "noView"
        else -> null
    }

    /** Why the sky views do not ask, or null when they do: `off`, `closed`, `hidden` (their toggle off), `background`, `noFix`. */
    fun skyReason(i: Inputs): String? = when {
        !i.tracking -> "off"
        !i.skyOpen -> "closed"
        !i.skyShown -> "hidden"
        !i.foreground -> "background"
        !i.skyHasFix -> "noFix"
        else -> null
    }

    /** The owners that ask now ([MAP], [SKY]); empty when nothing shows flights. */
    fun askers(i: Inputs): Set<String> = buildSet {
        if (mapReason(i) == null) add(MAP)
        if (skyReason(i) == null) add(SKY)
    }

    /**
     * Whether the map clears its marks: when it stops drawing because the data is off or the user hid it on the map. A
     * map covered by a sky view or in the background keeps them, unchanged and not pushed, so it shows them again at
     * once when it is back.
     */
    fun mapClears(reason: String?): Boolean = reason == "off" || reason == "hidden"

    /** What the poller does this second: [ticker] runs, [rebuild] makes and publishes the view, [poll] may request. */
    data class Plan(val ticker: Boolean, val rebuild: Boolean, val poll: Boolean)

    /**
     * The poller's plan for [owners] asking (the owners it holds requests from) with tracking [tracking]: the ticker runs
     * only while tracking is on; the view is built and published, and a request made when one is due, only while someone
     * asks.
     */
    fun plan(tracking: Boolean, owners: Set<String>): Plan = Plan(
        ticker = tracking,
        rebuild = tracking && owners.isNotEmpty(),
        poll = tracking && owners.isNotEmpty(),
    )
}
