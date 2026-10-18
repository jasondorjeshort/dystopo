package io.github.jasondorjeshort.dystopo.app.help

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response

/**
 * T109, the help pop-ups' debug routes (debug builds only, on the debug port):
 * - `GET /help[?units=ft|m]`: every help text ([HelpRegistry]): `key`, `title`, `text` (in the units setting, or the
 *   given units), `placed` (where its icon is, as a user gets there), `needs` (the state the icon is drawn in, when
 *   it is not always there), `onScreen` and `iconPx` (the icon's touch target in window pixels while composed);
 *   `open` (the key whose pop-up is open, or null) and `count`.
 * - `POST /help/open?key=<key>`: opens that pop-up as a tap on its icon does. When the icon is not on screen it first
 *   brings its screen up (the settings page, the Offline areas sheet, the track bar, the long-press card at the
 *   map's centre) and waits up to 3 s for the icon; the icon scrolls itself into view. Answers once the pop-up is laid
 *   out: `onScreen`, `iconPx`, and `popup` (`side`: below or above the icon, `over` it when the text fits on
 *   neither side, `centre` with no icon; `boundsPx`, `windowPx`, `inside`: within the window, `textFits`: the text
 *   needed no scrolling, `coversIcon`, `fontScale`). Then `GET /screenshot` shows it. An unknown key is a 404 naming the keys; an icon still missing after 3 s (its `needs` is not met) is a 409
 *   with the pop-up left closed.
 * - `POST /help/close`: closes the open pop-up (as a tap outside it or Back does). T115: and what `POST /help/open`
 *   brought up for its icon since the last close (the settings sheet or the page it was on, Offline areas, the track bar,
 *   the long-press card, the Storage screen: [HelpScreens]), so the next request starts from the map it found; `closed`
 *   lists them. Screens open before that `/help/open`, or opened by taps since, stay.
 */
object HelpRoutes {
    /**
     * T115: what `POST /help/open` brought up since the last `/help/close` (names from [HelpScreens.opened]), with the
     * screens as they stood before the first of those opens. Main thread.
     */
    private var opened: Pair<HelpScreens, List<String>>? = null

    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/help" -> list(r)
        "POST" to "/help/open" -> open(app, r)
        "POST" to "/help/close" -> {
            val closed = app.map.onMain {
                HelpUi.close()
                val shown = opened
                opened = null
                shown?.let { (before, names) -> HelpScreens.close(before, names, HelpScreens.now()) } ?: emptyList()
            }
            Response.json(mapOf("open" to null, "closed" to closed))
        }
        else -> null
    }

    private fun list(r: Request): Response {
        val units = when (r.query["units"]?.trim()?.lowercase()) {
            null, "" -> AppSettings.units
            "ft", "feet" -> io.github.jasondorjeshort.dystopo.render.ContourUnits.FEET
            "m", "metres", "meters" -> io.github.jasondorjeshort.dystopo.render.ContourUnits.METERS
            else -> return Response.json(mapOf("error" to "units must be ft or m (got ${r.query["units"]})"), 400)
        }
        return Response.json(HelpRegistry.json(units))
    }

    private fun open(app: DystopoApp, r: Request): Response {
        val key = r.query["key"]?.trim().orEmpty()
        val help = HelpRegistry.byKey(key)
            ?: return Response.json(mapOf("error" to "no help text '$key'", "keys" to HelpRegistry.all.map { it.key }), 404)
        if (key !in HelpUi.anchors) app.map.onMain {
            val before = HelpScreens.now()
            help.show()
            // T115: what show() brought up, for /help/close (kept with the state before the first such open).
            val names = before.opened(HelpScreens.now())
            if (names.isNotEmpty()) opened = opened?.let { (b, n) -> b to (n + names).distinct() } ?: (before to names)
        }
        val until = System.currentTimeMillis() + 3000
        while (key !in HelpUi.anchors && System.currentTimeMillis() < until) Thread.sleep(50)
        if (key !in HelpUi.anchors) {
            return Response.json(mapOf("error" to "the icon of '$key' is not on screen", "placed" to help.placed, "needs" to help.needs), 409)
        }
        app.map.onMain { HelpUi.open(key) }
        // Wait for the icon to scroll into view and the pop-up to be laid out beside it, then for one more frame.
        val laid = System.currentTimeMillis() + 3000
        var last: Any? = null
        var steady = 0
        while (System.currentTimeMillis() < laid && steady < 4) {
            Thread.sleep(80)
            val now = HelpUi.popup?.get("boundsPx") to HelpUi.anchors[key]
            steady = if (HelpUi.popup != null && now == last) steady + 1 else 0
            last = now
        }
        return Response.json(
            linkedMapOf(
                "key" to key, "title" to help.title, "text" to help.text(AppSettings.units), "placed" to help.placed,
                "onScreen" to (key in HelpUi.anchors), "iconPx" to HelpUi.anchors[key]?.json(), "popup" to HelpUi.popup,
            ),
        )
    }
}

/**
 * T115: the screens a help text's [HelpText.show] brings up for `POST /help/open` (the settings sheet on a group's page,
 * Offline areas, the track bar, the long-press card, the Storage screen), as they stand; `POST /help/close` closes those
 * that were not open before ([opened], pure) so a script's next step finds the map it left. Read and closed on the main
 * thread. A new `show` that opens another screen adds it here, or `/help/close` leaves it open.
 */
internal data class HelpScreens(
    val settings: Boolean,
    val settingsGroup: String?,
    val areas: Boolean,
    val trackBar: Boolean,
    val longPress: Boolean,
    val storage: Boolean,
) {
    /** What [now] has open that this did not, by name; `settingsPage` when the sheet was open on another page. */
    fun opened(now: HelpScreens): List<String> = buildList {
        if (now.settings && !settings) add(SETTINGS)
        else if (now.settings && settings && now.settingsGroup != settingsGroup) add(SETTINGS_PAGE)
        if (now.areas && !areas) add(AREAS)
        if (now.trackBar && !trackBar) add(TRACK_BAR)
        if (now.longPress && !longPress) add(LONG_PRESS)
        if (now.storage && !storage) add(STORAGE)
    }

    companion object {
        const val SETTINGS = "settings"
        const val SETTINGS_PAGE = "settingsPage"
        const val AREAS = "areas"
        const val TRACK_BAR = "trackBar"
        const val LONG_PRESS = "longPress"
        const val STORAGE = "storage"

        /** The screens open now (main thread). */
        fun now(): HelpScreens = HelpScreens(
            settings = io.github.jasondorjeshort.dystopo.app.SettingsUi.visible,
            settingsGroup = io.github.jasondorjeshort.dystopo.app.SettingsUi.group?.id,
            areas = io.github.jasondorjeshort.dystopo.app.areas.Areas.screenOpen,
            trackBar = io.github.jasondorjeshort.dystopo.app.tracking.TrackBar.open,
            longPress = io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu.point != null,
            storage = io.github.jasondorjeshort.dystopo.app.storage.StorageUi.visible,
        )

        /**
         * Of [shown] (what the opens brought up), the screens still open that were not open [before] (pure): a screen the
         * user closed since, or one open before the first open, is not closed again.
         */
        fun toClose(before: HelpScreens, shown: List<String>, now: HelpScreens): List<String> = before.opened(now).filter { it in shown }

        /** Closes [toClose] (main thread); returns their names. */
        fun close(before: HelpScreens, shown: List<String>, now: HelpScreens): List<String> {
            val names = toClose(before, shown, now)
            for (n in names) when (n) {
                SETTINGS -> io.github.jasondorjeshort.dystopo.app.SettingsUi.visible = false
                SETTINGS_PAGE -> io.github.jasondorjeshort.dystopo.app.SettingsUi.group = before.settingsGroup?.let { io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup.byId(it) }
                AREAS -> io.github.jasondorjeshort.dystopo.app.areas.Areas.screenOpen = false
                TRACK_BAR -> io.github.jasondorjeshort.dystopo.app.tracking.TrackBar.open = false
                LONG_PRESS -> io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu.cancel()
                STORAGE -> io.github.jasondorjeshort.dystopo.app.storage.StorageUi.visible = false
            }
            return names
        }
    }
}
