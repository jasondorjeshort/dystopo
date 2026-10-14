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

    /** Screen name -> the sections a message may name after `›` (each must appear as a heading in that screen's file). */
    val ALL: Map<String, Set<String>> = mapOf(
        SETTINGS to setOf(SOURCES, STORAGE),
        PACKS to setOf("From server"),
        TRACKS to emptySet(),
        ROUTES to emptySet(),
        SHARE to emptySet(),
    )
}
