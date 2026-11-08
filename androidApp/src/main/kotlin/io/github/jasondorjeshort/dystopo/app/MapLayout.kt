package io.github.jasondorjeshort.dystopo.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jasondorjeshort.dystopo.app.controls.RecenterLayout
import io.github.jasondorjeshort.dystopo.app.controls.ScaleBarLayout
import io.github.jasondorjeshort.dystopo.app.nav.NavCardBand
import io.github.jasondorjeshort.dystopo.app.readout.CompassView
import io.github.jasondorjeshort.dystopo.app.readout.PressureWatch
import io.github.jasondorjeshort.dystopo.app.readout.StripLayout
import io.github.jasondorjeshort.dystopo.app.readout.WarningCardLayout
import io.github.jasondorjeshort.dystopo.app.routing.BottomPanels
import io.github.jasondorjeshort.dystopo.app.routing.CardLayout
import io.github.jasondorjeshort.dystopo.ui.OverlayCard
import io.github.jasondorjeshort.dystopo.ui.OverlayIds
import io.github.jasondorjeshort.dystopo.ui.OverlayInput
import io.github.jasondorjeshort.dystopo.ui.OverlayLayout
import io.github.jasondorjeshort.dystopo.ui.OverlayPlacement
import io.github.jasondorjeshort.dystopo.ui.OverlaySize

/**
 * T224: the map screen's layout as it is and as the pure rule ([OverlayLayout]) would place it, for `GET /layout`
 * ([LayoutRoutes]). The first half of a layout authority: this only reads what the elements publish, and no element reads
 * this. Every read is on the main thread ([observed], [input]).
 */
object MapLayout {
    /** One element as drawn today: px from the window's top left, null while not drawn. [derived]: worked out from a published edge. */
    class Seen(val id: String, val rectPx: FloatArray?, val state: String, val reason: String?, val derived: Boolean = false)

    /** T224 (pure, `MapLayoutTest`): every pair of drawn elements that shares at least a whole pixel, in the order given. */
    fun overlaps(seen: List<Seen>): List<Map<String, Any?>> {
        val drawn = seen.filter { it.rectPx != null }
        val out = ArrayList<Map<String, Any?>>()
        for (i in drawn.indices) for (j in i + 1 until drawn.size) {
            val a = drawn[i]
            val b = drawn[j]
            val ra = a.rectPx!!
            val rb = b.rectPx!!
            val w = minOf(ra[2], rb[2]) - maxOf(ra[0], rb[0])
            val h = minOf(ra[3], rb[3]) - maxOf(ra[1], rb[1])
            if (w >= 1f && h >= 1f) out += linkedMapOf(
                "a" to a.id, "b" to b.id, "aName" to OverlayIds.NAMES[a.id], "bName" to OverlayIds.NAMES[b.id],
                "sizePx" to listOf(Math.round(w), Math.round(h)), "derived" to (a.derived || b.derived),
            )
        }
        return out
    }

    /** T224 (pure): the elements whose state the rule would change, with both states. */
    fun differences(seen: List<Seen>, placed: OverlayPlacement): List<Map<String, Any?>> = seen.mapNotNull { s ->
        val e = placed[s.id] ?: return@mapNotNull null
        if (e.state.code == s.state) null else linkedMapOf("id" to s.id, "name" to OverlayIds.NAMES[s.id], "today" to s.state, "rule" to e.state.code, "reason" to e.reason)
    }

    private const val SHOWN = "shown"
    private const val COMPACT = "compact"
    private const val HIDDEN = "hidden"

    private fun gone(id: String, why: String?) = Seen(id, null, HIDDEN, why ?: "notComposed")

    private fun card(id: String, rect: FloatArray?, l: CardLayout, compactWhy: String?, whyGone: String = "notUp"): Seen =
        if (rect == null) gone(id, whyGone)
        else if (compactWhy != null) Seen(id, rect, COMPACT, compactWhy)
        else if (l.scrollMaxPx > 0) Seen(id, rect, COMPACT, OverlayLayout.REASON_SCROLLS)
        else Seen(id, rect, SHOWN, null)

    /** What the elements publish now. Main thread. */
    fun observed(): List<Seen> {
        val d = DystopoApp.instance.resources.displayMetrics.density
        val root = rootSize()
        val out = ArrayList<Seen>()
        val packsOpen = io.github.jasondorjeshort.dystopo.app.packs.PacksUi.visible
        val row = TopChips.topRowPx
        out += if (packsOpen) gone(OverlayIds.SETTINGS_ROW, "packsScreenOpen") else if (row != null) Seen(OverlayIds.SETTINGS_ROW, row, SHOWN, null) else gone(OverlayIds.SETTINGS_ROW, null)
        // Search: the published width and bottom edge, under the status bar and its padding (`search/SearchOverlay.kt`).
        val insets = insetsPx()
        out += if (TopChips.searchBottom.value > 0f) {
            val left = 8f * d
            Seen(OverlayIds.SEARCH, floatArrayOf(left, insets[1] + 8f * d, left + TopChips.searchWidth().value * d, TopChips.searchBottom.value * d), if (TopChips.searchWidth().value < OverlayLayout.SEARCH_WIDTH) COMPACT else SHOWN, if (TopChips.searchWidth().value < OverlayLayout.SEARCH_WIDTH) OverlayLayout.REASON_NARROWED else null, derived = true)
        } else gone(OverlayIds.SEARCH, null)
        out += MapInsets.onlineChips?.let { Seen(OverlayIds.ONLINE, it, SHOWN, null) } ?: gone(OverlayIds.ONLINE, TopChips.hiddenReason(TopChips.Chip.ONLINE))
        out += TopChips.trackColumnPx?.let { Seen(OverlayIds.TRACK_BAR, it, SHOWN, null) } ?: gone(OverlayIds.TRACK_BAR, TopChips.hiddenReason(TopChips.Chip.TRACKS))
        out += TopChips.routesChipPx?.let { Seen(OverlayIds.ROUTES, it, SHOWN, null) } ?: gone(OverlayIds.ROUTES, TopChips.hiddenReason(TopChips.Chip.ROUTES))
        out += CompassView.boundsPx()?.let { Seen(OverlayIds.COMPASS, it, SHOWN, null) } ?: gone(OverlayIds.COMPASS, "mapFacingNorth")
        // The two bottom cards that publish only their top: centred, as wide as they draw (`trips/TripControls.kt`, `update/UpdateOverlay.kt`).
        val credit = io.github.jasondorjeshort.dystopo.app.about.AttributionInsets.bottom.value * d
        fun bottomCard(id: String, topFromBottomDp: Float, maxDp: Float): Seen {
            if (topFromBottomDp <= 0f) return gone(id, "notUp")
            val w = minOf(root[0] - 16f * d, maxDp * d)
            val x = (root[0] - w) / 2
            return Seen(id, floatArrayOf(x, root[1] - topFromBottomDp * d, x + w, root[1] - credit - 6f * d), SHOWN, null, derived = true)
        }
        out += bottomCard(OverlayIds.TRIP_CARD, MapInsets.tripCardTop.value, 440f)
        out += bottomCard(OverlayIds.UPDATE_CARD, MapInsets.updateCardTop.value, 440f)
        val routeUp = BottomPanels.routeCardTop.value > 0f
        val route = BottomPanels.routeCard
        out += card(OverlayIds.PLANNING_CARD, if (routeUp) MapInsets.routeCardPx else null, route, null)
        out += card(OverlayIds.NAV_CARD, TopChips.navCardPx, NavCardBand.layout, null)
        val warning = WarningCardLayout
        out += card(OverlayIds.WARNING_CARD, PressureWatch.cardBoundsPx, warning.layout, if (!warning.expanded) "collapsed" else null)
        for ((id, key) in listOf(OverlayIds.POSITION to "position", OverlayIds.ORIENTATION to "orientation", OverlayIds.LOCK to "lock").take(AppSettings.mapControls.coerceIn(1, 3))) {
            val b = if (RecenterLayout.shown) RecenterLayout.buttonBounds[key] else null
            out += if (b != null) Seen(id, b, SHOWN, null) else gone(id, RecenterLayout.hiddenBecause ?: "noRoomBelowChips")
        }
        out += if (ScaleBarLayout.shown && ScaleBarLayout.boundsPx != null) Seen(OverlayIds.SCALE_BAR, ScaleBarLayout.boundsPx, SHOWN, null) else gone(OverlayIds.SCALE_BAR, ScaleBarLayout.hiddenBecause)
        out += if (StripLayout.shown && StripLayout.boundsPx != null) {
            if (StripLayout.droppedItems.isEmpty()) Seen(OverlayIds.STRIP, StripLayout.boundsPx, SHOWN, null)
            else Seen(OverlayIds.STRIP, StripLayout.boundsPx, COMPACT, OverlayLayout.REASON_ENTRIES)
        } else gone(OverlayIds.STRIP, StripLayout.hiddenBecause)
        out += MapInsets.creditPx?.let { Seen(OverlayIds.CREDIT, it, SHOWN, null) } ?: gone(OverlayIds.CREDIT, null)
        return out
    }

    /** [l]'s heights in dp: the card's [rect] whole with what scrolls or folds, its least and its headline. */
    private fun cardInput(rect: FloatArray?, l: CardLayout, extraPx: Float, lineDp: Float, chromeDp: Float, d: Float): OverlayCard? {
        val r = rect ?: return null
        val natural = (r[3] - r[1] + l.scrollMaxPx + extraPx) / d
        val head = l.headerPx / d + chromeDp
        val least = (l.headerPx + l.footerPx) / d + lineDp + chromeDp
        return OverlayCard(maxOf(natural, least), least, head, (r[2] - r[0]) / d)
    }

    /** The measured inputs of the rule, dp. Main thread. */
    fun input(): OverlayInput {
        val res = DystopoApp.instance.resources
        val d = res.displayMetrics.density
        val fontScale = res.configuration.fontScale
        val root = rootSize()
        val ins = insetsPx()
        fun size(r: FloatArray?) = r?.let { OverlaySize((it[2] - it[0]) / d, (it[3] - it[1]) / d) }
        fun own(kind: TopChips.Chip) = TopChips.ownHeightPx(kind).takeIf { it > 0 }?.let { OverlaySize(TopChips.ownWidthPx(kind) / d, it / d) }
        // Two 13 sp lines of a card's scrolling body, as `CappedCard` keeps them (the system's curve is near this at 2.0).
        val twoLines = 36f * minOf(fontScale, 1f + (fontScale - 1f) * 0.75f)
        val routeUp = BottomPanels.routeCardTop.value > 0f
        val creditRect = MapInsets.creditPx
        val tracks = own(TopChips.Chip.TRACKS)
        // The track bar's own row when a panel is open under it: the rule places the chips, not the panel.
        val trackBar = tracks?.let { if (TopChips.trackRowPx > 0) OverlaySize(it.width, TopChips.trackRowPx / d) else it }
        val search = TopChips.searchBottom.value
        return OverlayInput(
            windowWidth = root[0] / d, windowHeight = root[1] / d,
            insetLeft = ins[0] / d, insetTop = ins[1] / d, insetRight = ins[2] / d, insetBottom = ins[3] / d,
            fontScale = fontScale,
            search = OverlaySize(OverlayLayout.SEARCH_WIDTH, if (search > 0f) (search - ins[1] / d - 8f).coerceAtLeast(0f) else 48f),
            topRow = size(TopChips.topRowPx) ?: OverlaySize(150f, 40f),
            credit = size(creditRect) ?: OverlaySize(0f, 0f),
            online = own(TopChips.Chip.ONLINE),
            trackBar = trackBar,
            routes = own(TopChips.Chip.ROUTES),
            compass = size(CompassView.boundsPx()),
            scaleBar = ScaleBarLayout.plateHeightPx.takeIf { it > 0 }?.let { OverlaySize(ScaleBarLayout.plateWidthPx / d, it / d) },
            strip = if (AppSettings.readoutBar) StripLayout.entrySizesPx.map { OverlaySize(it[0] / d, it[1] / d) } else emptyList(),
            buttons = AppSettings.mapControls.coerceIn(1, 3),
            offCentre = runCatching { offCentre() }.getOrDefault(false),
            navigation = cardInput(TopChips.navCardPx, NavCardBand.layout, 0f, twoLines, 28f, d),
            warning = cardInput(PressureWatch.cardBoundsPx, WarningCardLayout.layout, if (WarningCardLayout.expanded) 0f else (WarningCardLayout.detailDp ?: 0f) * d, twoLines, 28f, d),
            planning = cardInput(if (routeUp) MapInsets.routeCardPx else null, BottomPanels.routeCard, 0f, twoLines, 32f, d),
        )
    }

    private fun offCentre(): Boolean {
        val o = io.github.jasondorjeshort.dystopo.app.controls.MapOrientation.json()
        return o["centred"] == false
    }

    private fun mapView(): View? = DystopoApp.instance.map.mapView

    /** The window: the map view's root, px. */
    private fun rootSize(): FloatArray {
        val root = mapView()?.rootView
        val m = DystopoApp.instance.resources.displayMetrics
        return floatArrayOf((root?.width ?: m.widthPixels).toFloat(), (root?.height ?: m.heightPixels).toFloat())
    }

    /** The system bars and the display cutout, px: left, top, right, bottom. */
    private fun insetsPx(): FloatArray {
        val v = mapView() ?: return FloatArray(4)
        val i = ViewCompat.getRootWindowInsets(v)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: return FloatArray(4)
        return floatArrayOf(i.left.toFloat(), i.top.toFloat(), i.right.toFloat(), i.bottom.toFloat())
    }

    private fun rectJson(r: FloatArray?) = r?.map { Math.round(it) }

    /** `GET /layout`'s answer. Main thread. */
    fun json(): Map<String, Any?> {
        val res = DystopoApp.instance.resources
        val d = res.displayMetrics.density
        val root = rootSize()
        val ins = insetsPx()
        val seen = observed()
        val i = input()
        val placed = OverlayLayout.place(i)
        return linkedMapOf(
            "window" to linkedMapOf(
                "widthPx" to root[0].toInt(), "heightPx" to root[1].toInt(), "density" to d,
                "widthDp" to Math.round(root[0] / d), "heightDp" to Math.round(root[1] / d),
                "insetsPx" to ins.map { Math.round(it) },
                "orientation" to if (root[0] > root[1]) "landscape" else "portrait",
            ),
            "fontScale" to res.configuration.fontScale,
            "foreground" to Foreground.started,
            "elements" to seen.map { s ->
                linkedMapOf(
                    "id" to s.id, "name" to OverlayIds.NAMES[s.id], "controls" to OverlayIds.CONTROLS[s.id],
                    "rectPx" to rectJson(s.rectPx), "state" to s.state, "reason" to s.reason, "derived" to s.derived,
                )
            },
            "overlaps" to overlaps(seen),
            "onlineCard" to io.github.jasondorjeshort.dystopo.app.online.OnlineChip.layoutJson(), // T236: the chip's card, inside M8's column
            "rule" to linkedMapOf(
                "sideColumn" to placed.sideColumn, "cardColumns" to placed.cardColumns, "buttonsDrawn" to placed.buttonsDrawn, "entriesDrawn" to placed.entriesDrawn,
                "elements" to placed.elements.map { e ->
                    linkedMapOf(
                        "id" to e.id, "name" to OverlayIds.NAMES[e.id],
                        "rectPx" to e.rect?.let { listOf(Math.round(it.left * d), Math.round(it.top * d), Math.round(it.right * d), Math.round(it.bottom * d)) },
                        "state" to e.state.code, "reason" to e.reason,
                    )
                },
                "overlaps" to placed.overlaps().map { listOf(it.first, it.second) },
                "differs" to differences(seen, placed),
                "offCentre" to i.offCentre,
            ),
        )
    }
}
