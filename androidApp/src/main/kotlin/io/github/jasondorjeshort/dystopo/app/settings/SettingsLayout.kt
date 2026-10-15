package io.github.jasondorjeshort.dystopo.app.settings

import io.github.jasondorjeshort.dystopo.app.firstrun.Screens

/**
 * T103: the settings sheet's groups, in the order its list shows them. The sheet opens on this list (two panes,
 * list and page side by side, on a wide window); a group opens its page. [DATA] comes first because a fresh
 * install needs the server field before anything else (T79): Settings, then Data and storage, and the field is
 * the first thing on the page; the first-run card's Settings link opens [DATA] at once.
 */
enum class SettingsGroup(val id: String, val title: String, val summary: String) {
    DATA("data", "Data and storage", "Sources (server, mobile data), offline areas, storage"),
    MAP("map", "Map options", "Hillshade, slope, contours, labels, layers, night, scale bar"),
    DISPLAY("display", "Units and display", "Feet or metres, text size"),
    NAVIGATION("navigation", "Navigation and tracking", "Route snap, GPS fix, off-route snooze"),
    SYNC("sync", "Sync and sharing", "Sync status, sharing with a nearby phone"),
    ABOUT("about", "About and advanced", "Version and updates, contour warm-up, credits"),
    ;

    companion object {
        fun byId(id: String): SettingsGroup? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * T103: every row of the settings sheet, in page order. [label] is the row's heading as it is drawn: for a row
 * the settings package draws ([drawnBy] `SettingsPages`) the page passes [label] to the row, so the two cannot
 * differ; a section drawn by another file's composable ([drawnBy] names it) draws its own heading, which
 * `SettingsLayoutTest` checks in that file. [since] is the task that added the row (T103's rows are links to
 * screens the sheet did not reach before).
 */
enum class SettingsRow(val id: String, val group: SettingsGroup, val label: String, val drawnBy: String, val since: String) {
    SOURCES("sources", SettingsGroup.DATA, Screens.SOURCES, "SettingsPages", "T79"),
    STORAGE("storage", SettingsGroup.DATA, Screens.STORAGE, "SettingsPages", "T74"),
    OFFLINE_AREAS("offlineAreas", SettingsGroup.DATA, "Offline areas", "SettingsPages", "T103"),
    MAP_OPTIONS("mapOptions", SettingsGroup.MAP, "Map", "MapOptionsSection", "T47"),
    UNITS("units", SettingsGroup.DISPLAY, "Units", "SettingsPages", "T33"),
    TEXT_SIZE("textSize", SettingsGroup.DISPLAY, "Text size", "SettingsPages", "T33"),
    ROUTE_SNAP("routeSnap", SettingsGroup.NAVIGATION, "Route snap", "SettingsPages", "T57"),
    NAV_STALE("navStale", SettingsGroup.NAVIGATION, "Navigation: GPS fix stale after", "SettingsPages", "T91"),
    NAV_SNOOZE("navSnooze", SettingsGroup.NAVIGATION, "Navigation: Snooze", "SettingsPages", "T91"),
    SYNC_STATUS("syncStatus", SettingsGroup.SYNC, "Sync status", "SettingsPages", "T103"),
    SHARE("share", SettingsGroup.SYNC, Screens.SHARE, "SettingsPages", "T103"),
    UPDATES("updates", SettingsGroup.ABOUT, "Updates", "UpdateRow", "T83"),
    WARMUP("warmup", SettingsGroup.ABOUT, "Contour warm-up", "SettingsPages", "T33"),
    CREDITS("credits", SettingsGroup.ABOUT, "Credits", "SettingsPages", "T103"),
}

/** T103: the layout as data, for the sheet and `GET /settings/layout` (pure, unit-tested). */
object SettingsLayout {
    /** The group the first-run card's Settings link and the "set your server first" paths open: where the server field is. */
    val SERVER_GROUP = SettingsGroup.DATA

    fun rows(group: SettingsGroup): List<SettingsRow> = SettingsRow.entries.filter { it.group == group }

    /** `GET /settings/layout`: the groups in list order, each with its rows in page order, and what is open now. */
    fun json(open: Boolean = false, group: SettingsGroup? = null, twoPane: Boolean? = null): Map<String, Any?> = linkedMapOf(
        "groups" to SettingsGroup.entries.map { g ->
            linkedMapOf(
                "id" to g.id, "title" to g.title, "summary" to g.summary,
                "rows" to rows(g).map { r -> linkedMapOf("id" to r.id, "label" to r.label, "drawnBy" to r.drawnBy, "since" to r.since) },
            )
        },
        "serverGroup" to SERVER_GROUP.id,
        "open" to open,
        "group" to group?.id,
        "twoPane" to twoPane,
    )
}
