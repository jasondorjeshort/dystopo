package io.github.jasondorjeshort.dystopo.app.firstrun

/**
 * T79: the screens a user-facing message may send the user to, in one place. The alpha's "No server set
 * (Settings)" pointed at a screen without the field it promised; `ScreenNamesTest` now checks every string
 * in the app that names a screen (`(Packs)`, `in Settings`, `Settings › Sources`, `the Routes list`, ...)
 * against this list, and that each screen here has a chip or a link with that label that opens it.
 * A new screen or settings section goes here first.
 */
object Screens {
    const val SETTINGS = "Settings"
    const val PACKS = "Packs"
    const val TRACKS = "Tracks"
    const val ROUTES = "Routes"
    const val SHARE = "Share"

    /** Sections of the settings sheet, by their heading (`Settings › Sources`). */
    const val SOURCES = "Sources"
    /** T91: the Storage row of the settings sheet (opens the Storage screen; budgets live there). */
    const val STORAGE = "Storage"

    /**
     * T115 (T109's follow-up): the settings sheet's groups (T103), each a page of the sheet by its title, so a message or
     * a help placement may say `Settings › Map options`. [io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup]
     * takes its titles from here.
     */
    const val DATA_AND_STORAGE = "Data and storage"
    const val MAP_OPTIONS = "Map options"
    const val UNITS_AND_DISPLAY = "Units and display"
    const val NAVIGATION_AND_TRACKING = "Navigation and tracking"
    const val SYNC_AND_SHARING = "Sync and sharing"
    const val ABOUT_AND_ADVANCED = "About and advanced"
    val SETTINGS_GROUPS: Set<String> = setOf(DATA_AND_STORAGE, MAP_OPTIONS, UNITS_AND_DISPLAY, NAVIGATION_AND_TRACKING, SYNC_AND_SHARING, ABOUT_AND_ADVANCED)

    /**
     * Screen name -> the sections a message may name after `›` (each must appear as a heading in that screen's file;
     * T115: Settings' groups are its pages, the group list draws their titles).
     */
    val ALL: Map<String, Set<String>> = mapOf(
        SETTINGS to setOf(SOURCES, STORAGE) + SETTINGS_GROUPS,
        PACKS to setOf("From server"),
        TRACKS to emptySet(),
        ROUTES to emptySet(),
        SHARE to emptySet(),
    )
}
