package io.github.jasondorjeshort.dystopo.controls

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.ui.OverlayIds

/**
 * ADR 0053 build step 2 (T238): the rest of the shared surface, the items both clients have beyond the map options
 * ([MapControls]), as ADR 0053 §1 and the inventory's section 1 list them.
 *
 * Values: [UNITS] takes its choices from [ContourUnits] (the phone has no fixed default: the region seeds it, ADR 0022);
 * the text scale's ranges and defaults differ between the clients (the phone stores 0.5 to 3, the page's slider runs
 * 0.8 to 2 from 1.3 and stores nothing), so the registry records only its id, kind, sync key and placement (ADR 0053 §1,
 * the rule for values the registry cannot express); the scale bar's both-units choice is stored and synced as `on` or
 * `off` by the phone alone (the page's bar shows one unit), likewise.
 *
 * Ids (ADR 0053 §3): a phone settings key where there is one (`units`, `textScale`, `scaleBar.both`), else the phone
 * row's id (`mapOptions`, `printSheet`) or the element's name in `GET /layout` ([OverlayIds.NAMES]: `positionButton`,
 * `routesChip`, ...). The web's element ids map to them in its placement.
 *
 * Correspondences, from the inventory: the page's NavigationControl (zoom and the compass) is the orientation button;
 * its GeolocateControl the position button; its AttributionControl the credit line; its Trips box the Routes chip and
 * its list (its Saved trips rows, Send to server and Get from server are that flow's actions); its "Plan a route" the
 * route card (its trip card, the mode and the waypoints are that flow's actions).
 */
object SharedControls {
    private fun layout(id: String): String = OverlayIds.NAMES.getValue(id)

    private fun inventory(id: String): List<String> = OverlayIds.CONTROLS[id].orEmpty()

    val UNITS = ControlItem(
        "units", ControlKind.SETTING, ValueType.CHOICE, choices = ContourUnits.entries.map { it.symbol }, syncKey = "app.units",
        placements = mapOf(onAndroid("display", 1, "settings", row = "units"), onWeb("display", 1, "panel", "unitsel")),
    )
    val TEXT_SCALE = ControlItem(
        "textScale", ControlKind.SETTING, syncKey = "app.textScale",
        placements = mapOf(onAndroid("display", 2, "settings", row = "textSize"), onWeb("display", 2, "panel", "text")),
    )

    /** The section the map options sit in: its row on the phone, its `<summary>` on the page; order 0, ahead of its options. */
    val MAP_OPTIONS = ControlItem(
        "mapOptions", ControlKind.LINK,
        placements = mapOf(onAndroid("map", 0, "settings", row = "mapOptions"), onWeb("map", 0, "panel")),
    )
    val SCALE_BAR_BOTH = ControlItem(
        "scaleBar.both", ControlKind.SETTING, syncKey = "app.scaleBar.both",
        placements = mapOf(onAndroid("map", 14, "settings", row = "mapOptions")),
    )
    val PRINT = ControlItem(
        "printSheet", ControlKind.ACTION,
        placements = mapOf(onAndroid("map", 19, "settings", row = "printSheet"), onWeb("map", 14, "panel")),
    )

    /** The scale bar takes no input: the page's is its own control (`ScaleBarControl`), so it names no element. */
    val SCALE_BAR = ControlItem(
        layout(OverlayIds.SCALE_BAR), ControlKind.DISPLAY,
        placements = mapOf(onAndroid("map", 26, "map-lower-left", inventory = inventory(OverlayIds.SCALE_BAR)), onWeb("map", 17, "map-corner")),
    )
    val POSITION = ControlItem(
        layout(OverlayIds.POSITION), ControlKind.ACTION,
        placements = mapOf(
            onAndroid("map", 23, "map-lower-right", inventory = inventory(OverlayIds.POSITION)),
            onWeb("map", 16, "map-corner", "maplibregl.GeolocateControl"),
        ),
    )
    val ORIENTATION = ControlItem(
        layout(OverlayIds.ORIENTATION), ControlKind.ACTION,
        placements = mapOf(
            onAndroid("map", 24, "map-lower-right", inventory = inventory(OverlayIds.ORIENTATION)),
            onWeb("map", 15, "map-corner", "maplibregl.NavigationControl"),
        ),
    )
    val CREDIT = ControlItem(
        layout(OverlayIds.CREDIT), ControlKind.LINK,
        placements = mapOf(
            onAndroid("about", 6, "map-lower-left", inventory = inventory(OverlayIds.CREDIT)),
            onWeb("about", 2, "map-corner", "maplibregl.AttributionControl"),
        ),
    )
    val ROUTES = ControlItem(
        layout(OverlayIds.ROUTES), ControlKind.LINK,
        placements = mapOf(onAndroid("navigation", 29, "map-right", inventory = inventory(OverlayIds.ROUTES)), onWeb("navigation", 1, "trips")),
    )
    val PLANNING = ControlItem(
        layout(OverlayIds.PLANNING_CARD), ControlKind.ACTION,
        placements = mapOf(
            onAndroid("navigation", 31, "card", inventory = inventory(OverlayIds.PLANNING_CARD)),
            onWeb("navigation", 2, "trips", "trip-plan"),
        ),
    )

    val items: List<ControlItem> = listOf(UNITS, TEXT_SCALE, MAP_OPTIONS, SCALE_BAR_BOTH, PRINT, SCALE_BAR, POSITION, ORIENTATION, CREDIT, ROUTES, PLANNING)
}
