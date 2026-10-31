package io.github.jasondorjeshort.dystopo.app.settings

import io.github.jasondorjeshort.dystopo.app.firstrun.Screens

/**
 * T103: the settings sheet's groups, in the order its list shows them. The sheet opens on this list (two panes,
 * list and page side by side, on a wide window); a group opens its page. [DATA] comes first because a fresh
 * install needs the server field before anything else (T79): Settings, then Data and storage, and the field is
 * the first thing on the page; the first-run card's Settings link opens [DATA] at once.
 */
enum class SettingsGroup(val id: String, val title: String, val summary: String) {
    DATA("data", Screens.DATA_AND_STORAGE, "Sources (server, mobile data), offline areas, storage"), // T115: titles from Screens
    MAP("map", Screens.MAP_OPTIONS, "Hillshade, slope, contours, labels, layers, night, scale bar, position button"), // T176: position button
    DISPLAY("display", Screens.UNITS_AND_DISPLAY, "Feet or metres, text size"),
    NAVIGATION("navigation", Screens.NAVIGATION_AND_TRACKING, "Route snap, spoken directions, GPS fix, off-route snooze, route times, turn-back time, recording, readout"), // T164: route times; T163: turn-back time; T172: recording; T161: readout
    SYNC("sync", Screens.SYNC_AND_SHARING, "Sync status, sharing with a nearby phone"),
    ABOUT("about", Screens.ABOUT_AND_ADVANCED, "Version and updates, diagnostics, problem reports, contour warm-up, credits"),
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
    NAV_SPEECH("navSpeech", SettingsGroup.NAVIGATION, "Navigation: Spoken directions", "SettingsPages", "T126"),
    NAV_STALE("navStale", SettingsGroup.NAVIGATION, "Navigation: GPS fix stale after", "SettingsPages", "T91"),
    NAV_SNOOZE("navSnooze", SettingsGroup.NAVIGATION, "Navigation: Snooze", "SettingsPages", "T91"),
    // T164: route times fitted to the user's tracks (last of its group).
    PACE("pace", SettingsGroup.NAVIGATION, "Route times", "SettingsPages", "T164"),
    // T163
    TURN_DEADLINE("turnDeadline", SettingsGroup.NAVIGATION, "Navigation: Turn back by", "SettingsPages", "T163"),
    TURN_MARGIN("turnMargin", SettingsGroup.NAVIGATION, "Navigation: Turn-back margin", "SettingsPages", "T163"),
    SYNC_STATUS("syncStatus", SettingsGroup.SYNC, "Sync status", "SettingsPages", "T103"),
    SHARE("share", SettingsGroup.SYNC, Screens.SHARE, "SettingsPages", "T103"),
    UPDATES("updates", SettingsGroup.ABOUT, "Updates", "UpdateRow", "T83"),
    DIAGNOSTICS("diagnostics", SettingsGroup.ABOUT, "Diagnostics", "DiagnosticsRow", "T143"),
    REPORTS("reports", SettingsGroup.ABOUT, "Report a problem", "ReportRow", "T145"),
    WARMUP("warmup", SettingsGroup.ABOUT, "Contour warm-up", "SettingsPages", "T33"),
    CREDITS("credits", SettingsGroup.ABOUT, "Credits", "SettingsPages", "T103"),
    // T172: recording per activity (the remembered one's profile) and the battery exemption
    TRACK_ACTIVITY("trackActivity", SettingsGroup.NAVIGATION, "Recording: Activity", "SettingsPages", "T172"),
    TRACK_ACCURACY("trackAccuracy", SettingsGroup.NAVIGATION, "Recording: Accuracy limit", "SettingsPages", "T172"),
    TRACK_AUTO_PAUSE("trackAutoPause", SettingsGroup.NAVIGATION, "Recording: Auto-pause", "SettingsPages", "T172"),
    TRACK_FALLBACK("trackFallback", SettingsGroup.NAVIGATION, "Recording: Network location", "SettingsPages", "T172"),
    TRACK_INTERVAL("trackInterval", SettingsGroup.NAVIGATION, "Recording: Interval", "SettingsPages", "T172"),
    TRACK_BATTERY("trackBattery", SettingsGroup.NAVIGATION, "Recording: Battery", "SettingsPages", "T172"),
    // T161: the live readout beside the scale bar, per entry for the remembered activity, and the barometer switch
    READOUT_SPEED("readoutSpeed", SettingsGroup.NAVIGATION, "Readout: Speed", "SettingsPages", "T161"),
    READOUT_CLIMB("readoutClimb", SettingsGroup.NAVIGATION, "Readout: Climb", "SettingsPages", "T161"),
    READOUT_COURSE("readoutCourse", SettingsGroup.NAVIGATION, "Readout: Course", "SettingsPages", "T161"),
    READOUT_ELEVATION("readoutElevation", SettingsGroup.NAVIGATION, "Readout: Elevation", "SettingsPages", "T161"),
    READOUT_BAROMETER("readoutBarometer", SettingsGroup.NAVIGATION, "Readout: Barometer", "SettingsPages", "T161"),
    // T176: the position button's layout, its close zoom and the orientation lock (the Map page, after Map options)
    MAP_CONTROLS("mapControls", SettingsGroup.MAP, "Position button", "SettingsPages", "T176"),
    RECENTER_ZOOM("recenterZoom", SettingsGroup.MAP, "Position button: Close zoom", "SettingsPages", "T176"),
    ORIENTATION_LOCK("orientationLock", SettingsGroup.MAP, "Keep north up", "SettingsPages", "T176"),
    // T177: the readout bar's switch and the climb window (Navigation and tracking, after T161's rows)
    READOUT_BAR("readoutBar", SettingsGroup.NAVIGATION, "Readout bar", "SettingsPages", "T177"),
    CLIMB_WINDOW("climbWindow", SettingsGroup.NAVIGATION, "Readout: Climb window", "SettingsPages", "T177"),
}

/** T103: the layout as data, for the sheet and `GET /settings/layout` (pure, unit-tested). */
object SettingsLayout {
    /** The group the first-run card's Settings link and the "set your server first" paths open: where the server field is. */
    val SERVER_GROUP = SettingsGroup.DATA

    fun rows(group: SettingsGroup): List<SettingsRow> = SettingsRow.entries.filter { it.group == group }

    /**
     * T115 (T112's follow-up): a group's line under its title in the list. Data and storage's names the cache budget's
     * total ([cacheBudgetMb]), the one number on its page a user is most likely to look for.
     */
    fun summary(group: SettingsGroup, cacheBudgetMb: Long): String =
        if (group == SettingsGroup.DATA) {
            // A no-break space inside the size, so "1 GB" never splits across two lines.
            "${group.summary} with a ${io.github.jasondorjeshort.dystopo.app.storage.Storage.sizeLabel(cacheBudgetMb).replace(' ', '\u00A0')} cache budget"
        } else group.summary

    /** `GET /settings/layout`: the groups in list order, each with its rows in page order, and what is open now. */
    fun json(
        open: Boolean = false,
        group: SettingsGroup? = null,
        twoPane: Boolean? = null,
        cacheBudgetMb: Long = io.github.jasondorjeshort.dystopo.app.cachebudget.BudgetPlan.DEFAULT_MB,
    ): Map<String, Any?> = linkedMapOf(
        "groups" to SettingsGroup.entries.map { g ->
            linkedMapOf(
                "id" to g.id, "title" to g.title, "summary" to summary(g, cacheBudgetMb), // T115: as the list draws it
                "rows" to rows(g).map { r -> linkedMapOf("id" to r.id, "label" to r.label, "drawnBy" to r.drawnBy, "since" to r.since) },
            )
        },
        "serverGroup" to SERVER_GROUP.id,
        "open" to open,
        "group" to group?.id,
        "twoPane" to twoPane,
    )
}
