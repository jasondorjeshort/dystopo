package io.github.jasondorjeshort.dystopo.app.help

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.areas.AREAS_HELP
import io.github.jasondorjeshort.dystopo.app.firstrun.DIRECT_SOURCES_HELP
import io.github.jasondorjeshort.dystopo.app.nav.NAV_HELP
import io.github.jasondorjeshort.dystopo.app.options.MAP_OPTIONS_HELP
import io.github.jasondorjeshort.dystopo.app.settings.SETTINGS_HELP
import io.github.jasondorjeshort.dystopo.app.storage.STORAGE_HELP
import io.github.jasondorjeshort.dystopo.app.tracking.LONG_PRESS_HELP
import io.github.jasondorjeshort.dystopo.app.tracking.TRACK_BAR_HELP
import io.github.jasondorjeshort.dystopo.render.ContourUnits

/**
 * T109: one help pop-up's text. [key] is what the control's [HelpIcon] names; [title] heads the pop-up and is the
 * icon's spoken label ("Help: <title>"); [placed] says where the icon is, the way a user gets there ("Settings › Map
 * options › Hillshade"; `GET /help` output only; T115: in the `Screen › Section` form of messages, which `HelpRegistryTest`
 * checks against [io.github.jasondorjeshort.dystopo.app.firstrun.Screens] now that the settings groups are sections there);
 * [needs] names the state the icon is drawn in when it is not always there ("hillshade on"); [show] brings its screen up for
 * `POST /help/open` (main thread). The text is one short paragraph in the user's units: a distance in it goes through
 * [io.github.jasondorjeshort.dystopo.render.UnitFormat] with the units [text] is given.
 *
 * Each screen declares its texts in a list next to the controls that show them (the same file, `HelpRegistryTest`
 * checks it), so a removed control takes its text with it; [HelpRegistry] only gathers the lists.
 */
class HelpText(
    val key: String,
    val title: String,
    val placed: String,
    val needs: String? = null,
    val show: () -> Unit = {},
    private val body: (ContourUnits) -> String,
) {
    fun text(units: ContourUnits): String = body(units)
}

/**
 * T109: every help text of the app, gathered from the screens' lists (Map options, Settings, Settings › Sources'
 * direct rows, Offline areas, the track bar, the long-press card; T115: the Storage screen; T122: the navigation card).
 * `GET /help` lists them.
 */
object HelpRegistry {
    val all: List<HelpText> by lazy {
        MAP_OPTIONS_HELP + SETTINGS_HELP + DIRECT_SOURCES_HELP + AREAS_HELP + TRACK_BAR_HELP + LONG_PRESS_HELP + STORAGE_HELP + // T115: Storage
            NAV_HELP // T122: the navigation card
    }

    fun byKey(key: String): HelpText? = all.firstOrNull { it.key == key }

    /** `GET /help`: each key with its title, text in [units], where it is placed, and whether its icon is on screen now. */
    fun json(units: ContourUnits): Map<String, Any?> = linkedMapOf(
        "units" to units.name.lowercase(),
        "open" to HelpUi.openKey,
        "count" to all.size,
        "help" to all.map { h ->
            linkedMapOf(
                "key" to h.key, "title" to h.title, "text" to h.text(units), "placed" to h.placed, "needs" to h.needs,
                "onScreen" to (h.key in HelpUi.anchors), "iconPx" to HelpUi.anchors[h.key]?.json(),
            )
        },
    )
}

/**
 * T122: where the help pop-up goes and at what text scale (pure, `PopupPlaceTest`). Beside its icon at the system's
 * font scale when the text fits there (below first, then above); else on the larger side with the text a step
 * ([STEP]) smaller at a time, down to [MIN_TEXT_SCALE] of the font scale (never below 1.0), until it fits; else at that
 * floor, scrolling. Never over the icon: at font scale 2.0 two texts used to cover theirs (T115). `centre` with no icon.
 */
data class PopupPlace(val side: String, val textScale: Float, val fits: Boolean) {
    companion object {
        const val STEP = 0.1f
        const val MIN_TEXT_SCALE = 0.75f

        /** [need] gives the bubble's height in px at a text scale; [below] and [above] are the room on each side. */
        fun choose(hasIcon: Boolean, below: Int, above: Int, fontScale: Float, need: (Float) -> Int): PopupPlace {
            if (!hasIcon) return PopupPlace("centre", fontScale, true)
            val full = need(fontScale)
            if (full <= below) return PopupPlace("below", fontScale, true)
            if (full <= above) return PopupPlace("above", fontScale, true)
            val side = if (below >= above) "below" else "above"
            val room = maxOf(below, above)
            val floor = maxOf(minOf(1f, fontScale), fontScale * MIN_TEXT_SCALE)
            var s = fontScale
            while (s - STEP >= floor - 1e-4f) {
                s = Math.round((s - STEP) * 100f) / 100f
                if (need(s) <= room) return PopupPlace(side, s, true)
            }
            val last = Math.round(floor * 100f) / 100f
            return PopupPlace(side, last, need(last) <= room)
        }
    }
}

/** A rectangle in window pixels (the app's content view), as the icons and the pop-up report it. */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun json(): List<Int> = listOf(left, top, right, bottom)
    fun overlaps(o: PxRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/**
 * T109: which help pop-up is open ([openKey], null for none) and where every help icon on screen is ([anchors],
 * by key, in window pixels; an icon adds itself while it is composed). [popup] is where the open pop-up was last
 * laid out, with whether its text fitted, for `POST /help/open`. Compose state, written on the main thread.
 */
object HelpUi {
    var openKey by mutableStateOf<String?>(null)
        private set

    val anchors = mutableStateMapOf<String, PxRect>()

    /** How the open pop-up was last laid out: its bounds, the window, whether it fits inside and its text is whole. */
    @Volatile var popup: Map<String, Any?>? = null
        internal set

    /** Opens so far; Compose state (T122: an icon scrolls itself into view again on each open of its key). */
    var opened by mutableStateOf(0)
        private set

    fun open(key: String) {
        popup = null
        openKey = key
        opened++
    }

    fun close() {
        openKey = null
        popup = null
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * T122: closes [key]'s pop-up unless its icon is composed again within [BACK_MS] (main thread): a lazy list disposes
     * a row and composes it again while it scrolls, and the pop-up opened for that row used to close on the way.
     */
    fun closeUnlessBack(key: String) {
        main.postDelayed({ if (openKey == key && key !in anchors) close() }, BACK_MS)
    }

    const val BACK_MS = 300L
}
