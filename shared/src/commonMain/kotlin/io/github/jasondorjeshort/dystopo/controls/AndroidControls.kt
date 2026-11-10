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
        row("mapControls", "map", 15),
        row("recenterZoom", "map", 16),
        row("orientationLock", "map", 17),
        row("viewRange", "map", 18),
        row("showDivergence", "map", 20),
        row("routeSnap", "navigation", 1),
        row("navSpeech", "navigation", 2),
        row("navStale", "navigation", 3),
        row("navSnooze", "navigation", 4),
        row("pace", "navigation", 5),
        row("turnDeadline", "navigation", 6),
        row("turnMargin", "navigation", 7),
        row("trackActivity", "navigation", 8),
        row("trackAccuracy", "navigation", 9),
        row("trackAutoPause", "navigation", 10),
        row("trackFallback", "navigation", 11),
        row("trackInterval", "navigation", 12),
        row("trackBattery", "navigation", 13),
        row("readoutSpeed", "navigation", 14),
        row("readoutClimb", "navigation", 15),
        row("readoutCourse", "navigation", 16),
        row("readoutElevation", "navigation", 17),
        row("readoutBarometer", "navigation", 18),
        row("readoutBar", "navigation", 19),
        row("climbWindow", "navigation", 20),
        row("pressureFallWarning", "navigation", 21),
        row("waterAlongRoute", "navigation", 22),
        row("waterSpoken", "navigation", 23),
        row("timeEstimateForm", "navigation", 24),
        row("shadeSpoken", "navigation", 25),
        row("setHeight", "navigation", 26, ControlKind.ACTION),
        row("estimatePosition", "navigation", 27),
        row("readoutExtended", "navigation", 35), // T257: the page's last row, after the map elements' 28 to 34
        row("syncStatus", "sync", 1, ControlKind.LINK),
        row("share", "sync", 2, ControlKind.LINK),
        row("updates", "about", 1),
        row("diagnostics", "about", 2, ControlKind.DISPLAY),
        row("reports", "about", 3, ControlKind.LINK),
        row("warmup", "about", 4, ControlKind.ACTION),
        row("credits", "about", 5, ControlKind.LINK),
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
        element(OverlayIds.SEARCH, ControlKind.ACTION, "map", 21, "map-top"),
        element(OverlayIds.COMPASS, ControlKind.DISPLAY, "map", 22, "map-right"),
        element(OverlayIds.LOCK, ControlKind.ACTION, "map", 25, "map-lower-right"),
        element(OverlayIds.TRACK_BAR, ControlKind.ACTION, "navigation", 28, "map-right"),
        element(OverlayIds.TRIP_CARD, ControlKind.DISPLAY, "navigation", 30, "card"),
        element(OverlayIds.NAV_CARD, ControlKind.DISPLAY, "navigation", 32, "card"),
        element(OverlayIds.WARNING_CARD, ControlKind.DISPLAY, "navigation", 33, "card"),
        element(OverlayIds.STRIP, ControlKind.DISPLAY, "navigation", 34, "map-lower-left"),
        element(OverlayIds.UPDATE_CARD, ControlKind.DISPLAY, "about", 7, "card"),
    )

    val items: List<ControlItem> = ROWS + ELEMENTS
}
