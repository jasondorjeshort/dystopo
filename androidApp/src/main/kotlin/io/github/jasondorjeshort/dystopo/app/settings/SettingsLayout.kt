package io.github.jasondorjeshort.dystopo.app.settings

import io.github.jasondorjeshort.dystopo.app.firstrun.Screens

/**
 * T103: the settings sheet's groups, in the order its list shows them. The sheet opens on this list (two panes,
 * list and page side by side, on a wide window); a group opens its page. [DATA] comes first because a fresh
 * install needs the server field before anything else (T79): Settings, then Data and storage, and the field is
 * the first thing on the page; the first-run card's Settings link opens [DATA] at once.
 */
enum class SettingsGroup(val id: String, val title: String, val summary: String) {
    DATA("data", Screens.DATA_AND_STORAGE, "Sources (server, mobile data), offline maps, storage, flight tracking"), // T115: titles from Screens; T370: flight tracking
    MAP("map", Screens.MAP_OPTIONS, "Hillshade, slope, shade, contours, labels, layers, drainage, night, scale bar, flights, position button, north up, view range, print, trail disagreements"), // T223: names what the page holds; T370: Flight tracking's rows moved to DATA, "flights" is Show flights (T364)
    DISPLAY("display", Screens.UNITS_AND_DISPLAY, "Feet or metres, pressure units, text size, coordinate format"), // T337: pressure units; T388: coordinate format
    NAVIGATION("navigation", Screens.NAVIGATION_AND_TRACKING, "Route snap, spoken directions, GPS fix, off-route snooze, route times, turn-back time, recording, readout, pressure warning, water, shade, position estimate, drive planning, Horizon view, sky range, Panorama steering"), // T223: names what the page holds; T308: Horizon view; T312: sky range; T330: Panorama steering
    SYNC("sync", Screens.SYNC_AND_SHARING, "Sync status, sharing with a nearby phone"),
    ABOUT("about", Screens.ABOUT_AND_ADVANCED, "Version and updates, technical details, problem reports, contour warm-up, credits"),
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
    OFFLINE_AREAS("offlineAreas", SettingsGroup.DATA, Screens.OFFLINE_MAPS, "SettingsPages", "T103"), // T235: the list's name; the id stays (ADR 0053)
    MAP_OPTIONS("mapOptions", SettingsGroup.MAP, "Map", "MapOptionsSection", "T47"),
    UNITS("units", SettingsGroup.DISPLAY, "Units", "SettingsPages", "T33"),
    // T337: the unit air pressure reads in, right under Units (the owner, 2026-11-22: easy to find)
    PRESSURE_UNIT(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_PRESSURE_UNIT, SettingsGroup.DISPLAY, "Pressure units", "SettingsPages", "T337"),
    // end T337
    TEXT_SIZE("textSize", SettingsGroup.DISPLAY, "Text size", "SettingsPages", "T33"),
    // T388: how every position reads, after Text size (the controls manifest's next free order: no existing order moves)
    COORDINATE_FORMAT(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_COORDINATE_FORMAT, SettingsGroup.DISPLAY, "Coordinate format", "SettingsPages", "T388"),
    // end T388
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
    DIAGNOSTICS("diagnostics", SettingsGroup.ABOUT, "Technical details", "DiagnosticsRow", "T143"), // T223: the fold's heading; the id is unchanged
    REPORTS("reports", SettingsGroup.ABOUT, "Report a problem", "ReportRow", "T145"),
    WARMUP("warmup", SettingsGroup.ABOUT, "Contour warm-up", "SettingsPages", "T33"),
    CREDITS("credits", SettingsGroup.ABOUT, "Credits", "SettingsPages", "T103"),
    // T172: recording per activity (the remembered one's profile) and the battery exemption
    TRACK_ACTIVITY("trackActivity", SettingsGroup.NAVIGATION, "Recording: Activity", "SettingsPages", "T172"),
    // T263: whether Record asks (the activity picker) or starts with the current activity
    RECORD_ASK_ACTIVITY(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_RECORD_ASK_ACTIVITY, SettingsGroup.NAVIGATION, "Ask which activity when a recording starts", "SettingsPages", "T263"),
    // end T263
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
    // T178: the pressure-fall warning (Navigation and tracking, after T177's rows)
    PRESSURE_FALL_WARNING("pressureFallWarning", SettingsGroup.NAVIGATION, "Pressure-fall warning", "SettingsPages", "T178"),
    // T187: water along the route and its spoken line (Navigation and tracking, after T177's rows)
    WATER_ALONG_ROUTE("waterAlongRoute", SettingsGroup.NAVIGATION, "Water along the route", "SettingsPages", "T187"),
    WATER_SPOKEN("waterSpoken", SettingsGroup.NAVIGATION, "Water: Spoken", "SettingsPages", "T187"),
    // T182: how far "What can I see from here" looks (the Map page, after the position button)
    VIEW_RANGE("viewRange", SettingsGroup.MAP, "View range", "SettingsPages", "T182"),
    // T183: the printed sheet of the current view (the Map page, last): an action, not a setting
    PRINT_SHEET("printSheet", SettingsGroup.MAP, "Print a map sheet", "SettingsPages", "T183"),
    // T184: the route card's time as a range or one number (Navigation and tracking, after T187's rows)
    TIME_ESTIMATE_FORM("timeEstimateForm", SettingsGroup.NAVIGATION, "Route times: Show as", "SettingsPages", "T184"),
    // T194: the spoken terrain shade line (Navigation and tracking, after T187's water rows and T184's)
    SHADE_SPOKEN("shadeSpoken", SettingsGroup.NAVIGATION, "Terrain shade: Spoken", "SettingsPages", "T194"),
    // end T194
    // T197: the readout's height set from a sign (Navigation and tracking, after the readout's rows): an action, not a setting
    SET_HEIGHT(io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings.SET_HEIGHT, SettingsGroup.NAVIGATION, io.github.jasondorjeshort.dystopo.readout.KnownHeights.SET_NOW, "SettingsPages", "T197"),
    // end T197
    // T203: the terrain estimate's switch (Navigation and tracking, last)
    ESTIMATE_POSITION(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_ESTIMATE_POSITION, SettingsGroup.NAVIGATION, "Estimate position when GPS is poor", "SettingsPages", "T203"),
    // end T203
    // T257: the extended readout and the pressure (Navigation and tracking, last: the manifest's orders up to the map's elements are taken)
    READOUT_EXTENDED("readoutExtended", SettingsGroup.NAVIGATION, "Extended readout", "SettingsPages", "T257"),
    // T282: how a drive is planned (Navigation and tracking, last: the controls manifest's next free order)
    DRIVE_PREFERENCE(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_DRIVE_PREFERENCE, SettingsGroup.NAVIGATION, "Plan drives for", "SettingsPages", "T282"),
    // end T282
    // T307: the GPS view and its sky plot's orientation (Navigation and tracking, last: the controls manifest's next free orders)
    // T308: the row's label is the Horizon view's title (its id `gnssStatus` stays)
    GNSS_STATUS("gnssStatus", SettingsGroup.NAVIGATION, io.github.jasondorjeshort.dystopo.app.gnss.GnssText.TITLE, "SettingsPages", "T307"),
    SKY_PLOT(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_SKY_PLOT, SettingsGroup.NAVIGATION, "Sky plot", "SettingsPages", "T307"),
    // end T307
    // T312: which flights the Horizon view draws, beside the view's other row (the controls manifest's next free order)
    SKY_RANGE(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_SKY_RANGE, SettingsGroup.NAVIGATION, io.github.jasondorjeshort.dystopo.app.horizon.HorizonText.SKY_RANGE, "SettingsPages", "T312"),
    // end T312
    // T330: how the Panorama view is steered, under Sky range (the controls manifest's next free order)
    PANORAMA_STEERING(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_PANORAMA_STEERING, SettingsGroup.NAVIGATION, io.github.jasondorjeshort.dystopo.app.panorama.PanoramaStrings.STEERING, "SettingsPages", "T330"),
    // end T330
    // T213: the trail disagreements' layer switch (the Map page, last)
    SHOW_DIVERGENCE(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_SHOW_DIVERGENCE, SettingsGroup.MAP, io.github.jasondorjeshort.dystopo.routing.DivergenceText.SETTING, "SettingsPages", "T213"),
    // end T213
    // T311: the Flights layer: Flight tracking, its refresh interval and airport names
    // T370: on the Data and storage page, last (after Sources, Storage and Offline maps; the owner, 2026-11-23); keys unchanged
    AIRCRAFT(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_AIRCRAFT, SettingsGroup.DATA, io.github.jasondorjeshort.dystopo.aircraft.AircraftText.SETTING, "SettingsPages", "T311"),
    AIRCRAFT_REFRESH(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_AIRCRAFT_REFRESH, SettingsGroup.DATA, "Flight tracking: Refresh", "SettingsPages", "T311"),
    AIRCRAFT_AIRPORTS(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_AIRCRAFT_AIRPORTS, SettingsGroup.DATA, "Flight tracking: Airport names", "SettingsPages", "T311"),
    // end T311
    // T314: which flights show route lines (after the Flights rows; T370: on the Data and storage page with them)
    AIRCRAFT_ROUTE_LINES(io.github.jasondorjeshort.dystopo.app.AppSettings.KEY_AIRCRAFT_ROUTE_LINES, SettingsGroup.DATA, "Flight tracking: Route lines", "SettingsPages", "T314"),
    // end T314
}

/** T103: the layout as data, for the sheet and `GET /settings/layout` (pure, unit-tested). */
object SettingsLayout {
    /** The group the first-run card's Settings link and the "set your server first" paths open: where the server field is. */
    val SERVER_GROUP = SettingsGroup.DATA

    fun rows(group: SettingsGroup): List<SettingsRow> = SettingsRow.entries.filter { it.group == group }

    /**
     * T115 (T112's follow-up): a group's line under its title in the list. Data and storage's names the cache budget's
     * total ([cacheBudgetMb]), the one number on its page a user is most likely to look for, right after "storage"
     * (T370: flight tracking, the page's last rows, follows it).
     */
    fun summary(group: SettingsGroup, cacheBudgetMb: Long): String =
        if (group == SettingsGroup.DATA) {
            // A no-break space inside the size, so "1 GB" never splits across two lines.
            val budget = "storage with a ${io.github.jasondorjeshort.dystopo.app.storage.Storage.sizeLabel(cacheBudgetMb).replace(' ', '\u00A0')} cache budget"
            group.summary.replace(", storage,", ", $budget,")
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
