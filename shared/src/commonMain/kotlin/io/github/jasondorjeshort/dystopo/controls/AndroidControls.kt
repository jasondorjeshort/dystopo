package io.github.jasondorjeshort.dystopo.controls

import io.github.jasondorjeshort.dystopo.ui.OverlayIds

/**
 * ADR 0053 build step 2 (T238): what only the phone places, by id and placement alone (no values: phone-only settings
 * keep `AppSettings`, `SettingsRoutes` and `SettingsSync`, ADR 0053 §1).
 *
 * - Zone `settings`: every settings sheet row the shared items do not already name, by its `SettingsRow.id` (the id is
 *   the row's, which for most rows is also its settings key), in its group (the placeholder categories are the groups),
 *   in sheet order. `ControlIdsTest` checks both ways against `SettingsLayout.kt`.
 * - The map screen's elements: every element `GET /layout` lists, by its name there ([OverlayIds.NAMES]) with the
 *   inventory rows it holds, plus the first-run card and the Online chip's card (T236), which it does not yet list.
 *   Each is tagged in the phone's code by `Modifier.control("<id>")` (`app/MapLayout.kt`); `ControlIdsTest` checks
 *   both ways, and `GET /controls` lists them with their zone. The compass is MapLibre's own view and has no tag.
 *
 * Not placed yet: the long-press menu's entries (zone `menu`) and the screens' entry points inside a flow (zone `screen`):
 * their files were outside build step 2's task; a later task adds each with its tag.
 */
object AndroidControls {
    private fun row(id: String, category: String, order: Int, kind: ControlKind = ControlKind.SETTING) =
        ControlItem(id, kind, placements = mapOf(onAndroid(category, order, "settings", row = id)))

    private fun element(layoutId: String, kind: ControlKind, category: String, order: Int, zone: String) =
        ControlItem(OverlayIds.NAMES.getValue(layoutId), kind, placements = mapOf(onAndroid(category, order, zone, inventory = OverlayIds.CONTROLS[layoutId].orEmpty())))

    private fun element(id: String, kind: ControlKind, category: String, order: Int, zone: String, inventory: List<String>) =
        ControlItem(id, kind, placements = mapOf(onAndroid(category, order, zone, inventory = inventory)))

    /** The settings rows, in sheet order within each group (orders the shared items leave free). */
    val ROWS: List<ControlItem> = listOf(
        row("sources", "data", 1),
        row("storage", "data", 2, ControlKind.LINK),
        row("offlineAreas", "data", 3, ControlKind.LINK),
        row("mapControls", "map", 17), // T370: the Map page renumbered once, 15 and 16 are Map options' last two items (MAP_OPTION_ROWS)
        row("recenterZoom", "map", 18),
        row("orientationLock", "map", 19),
        row("viewRange", "map", 20),
        row("showDivergence", "map", 22),
        row("routeSnap", "navigation", 1),
        row("navSpeech", "navigation", 2),
        row("navStale", "navigation", 3),
        row("navSnooze", "navigation", 4),
        row("pace", "navigation", 5),
        row("turnDeadline", "navigation", 6),
        row("turnMargin", "navigation", 7),
        row("trackActivity", "navigation", 8),
        row("recordAskActivity", "navigation", 9), // T263: right under the activity; the rows after it moved down one
        row("trackAccuracy", "navigation", 10),
        row("trackAutoPause", "navigation", 11),
        row("trackFallback", "navigation", 12),
        row("trackInterval", "navigation", 13),
        row("trackBattery", "navigation", 14),
        row("readoutSpeed", "navigation", 15),
        row("readoutClimb", "navigation", 16),
        row("readoutCourse", "navigation", 17),
        row("readoutElevation", "navigation", 18),
        row("readoutBarometer", "navigation", 19),
        row("readoutBar", "navigation", 20),
        row("climbWindow", "navigation", 21),
        row("pressureFallWarning", "navigation", 22),
        row("waterAlongRoute", "navigation", 23),
        row("waterSpoken", "navigation", 24),
        row("timeEstimateForm", "navigation", 25),
        row("shadeSpoken", "navigation", 26),
        row("setHeight", "navigation", 27, ControlKind.ACTION),
        row("estimatePosition", "navigation", 28),
        row("readoutExtended", "navigation", 35), // T257: the page's last row, after the map elements' 29 to 34 (T263: 28 is a row now)
        row("drivePreference", "navigation", 38), // T282: Plan drives for, after the elements' 36 and 37
        row("gnssStatus", "navigation", 39, ControlKind.LINK), // T307: the GPS view (T308: the Horizon view), then its sky plot's orientation
        row("skyPlot", "navigation", 40),
        // T312
        row("skyRange", "navigation", 41), // the Horizon view's flights: beside its other rows
        // end T312
        // T330
        row("panoramaSteering", "navigation", 42), // how the Panorama view turns: under Sky range
        // end T330
        // T337
        row("pressureUnit", "display", 2), // the pressure's own unit, under Units (Text size moved to 3)
        // end T337
        row("syncStatus", "sync", 1, ControlKind.LINK),
        row("share", "sync", 2, ControlKind.LINK),
        row("updates", "about", 1),
        row("diagnostics", "about", 2, ControlKind.DISPLAY),
        row("reports", "about", 3, ControlKind.LINK),
        row("warmup", "about", 4, ControlKind.ACTION),
        row("credits", "about", 5, ControlKind.LINK),
        // T311: Flight tracking, its refresh and airport names (T370: the Data page's last rows, its next free orders)
        row("aircraft", "data", 8),
        row("aircraftRefreshS", "data", 9),
        row("aircraftAirports", "data", 10),
        // end T311
        // T314: Flight tracking: Route lines (after the Flights rows; T370: on the Data page with them)
        row("aircraftRouteLines", "data", 11),
        // end T314
        // T388: Coordinate format, the Units and display page's last row after Text size (3): its next free order, and last in
        // this list, so no existing order or entry moves (T346's warning: an insert renumbers the map orders in three files)
        row("coordinateFormat", "display", 4),
        // end T388
    )

    /**
     * The map screen's elements only the phone has (the position and orientation buttons, the scale bar, the credit
     * line, the Routes chip and the route card are shared: [SharedControls]). A card is kind `display`: the app shows
     * it, and its buttons are its flow's actions, which the manifest does not place.
     */
    val ELEMENTS: List<ControlItem> = listOf(
        element(OverlayIds.SETTINGS_ROW, ControlKind.LINK, "data", 4, "map-top"),
        element(OverlayIds.ONLINE, ControlKind.ACTION, "data", 5, "map-top"),
        element("onlineCard", ControlKind.DISPLAY, "data", 6, "card", emptyList()), // T236: under the Online chip
        element("firstRunCard", ControlKind.DISPLAY, "data", 7, "card", (102..107).map { "M$it" }),
        element(OverlayIds.SEARCH, ControlKind.ACTION, "map", 23, "map-top"), // T370: the Map page's elements renumbered after its rows
        element(OverlayIds.COMPASS, ControlKind.DISPLAY, "map", 24, "map-right"),
        element(OverlayIds.LOCK, ControlKind.ACTION, "map", 27, "map-lower-right"),
        element(OverlayIds.TRACK_BAR, ControlKind.ACTION, "navigation", 36, "map-right"), // T263: 28 before; that order is a row now
        element(OverlayIds.ACTIVITY, ControlKind.ACTION, "navigation", 37, "map-right"), // T263: the activity chip, under the Routes chip
        element(OverlayIds.TRIP_CARD, ControlKind.DISPLAY, "navigation", 30, "card"),
        element(OverlayIds.NAV_CARD, ControlKind.DISPLAY, "navigation", 32, "card"),
        element(OverlayIds.WARNING_CARD, ControlKind.DISPLAY, "navigation", 33, "card"),
        element(OverlayIds.STRIP, ControlKind.DISPLAY, "navigation", 34, "map-lower-left"),
        element(OverlayIds.HORIZON_SWITCH, ControlKind.ACTION, "map", 29, "map-lower-left"), // T309: "Map | Horizon", over the scale bar's line
        element(OverlayIds.UPDATE_CARD, ControlKind.DISPLAY, "about", 7, "card"),
        // T311: a tapped aircraft's card, and the Flights layer's status line under Search
        element(OverlayIds.AIRCRAFT_CARD, ControlKind.DISPLAY, "map", 30, "card"),
        element("flightsStatus", ControlKind.DISPLAY, "map", 31, "map-top", emptyList()),
        // end T311
    )

    // T370
    /**
     * Phone-only rows drawn inside Map options (`MapOptionsSection`, settings row `mapOptions`), after the shared
     * `scaleBar.both` (14), in the section's order: T346's "Show zoom level" and T364's "Show flights". Not in [ROWS],
     * whose items are placed by their own row id; like them, an item of one client records only its id, kind and placement
     * (both keys are synced by `SettingsSync`). `SettingsLayoutTest` holds their order to the section's.
     */
    val MAP_OPTION_ROWS: List<ControlItem> = listOf(
        ControlItem("scaleBar.zoom", ControlKind.SETTING, placements = mapOf(onAndroid("map", 15, "settings", row = "mapOptions"))),
        ControlItem("mapFlights", ControlKind.SETTING, placements = mapOf(onAndroid("map", 16, "settings", row = "mapOptions"))),
    )
    // end T370

    val items: List<ControlItem> = ROWS + ELEMENTS + MAP_OPTION_ROWS
}
