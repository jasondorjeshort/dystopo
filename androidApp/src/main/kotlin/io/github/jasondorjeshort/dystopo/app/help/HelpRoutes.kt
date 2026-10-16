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
 * - `POST /help/close`: closes the open pop-up (as a tap outside it or Back does).
 */
object HelpRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/help" -> list(r)
        "POST" to "/help/open" -> open(app, r)
        "POST" to "/help/close" -> {
            app.map.onMain { HelpUi.close() }
            Response.json(mapOf("open" to null))
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
        if (key !in HelpUi.anchors) app.map.onMain { help.show() }
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
