package io.github.jasondorjeshort.dystopo.app

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import io.github.jasondorjeshort.dystopo.ui.OverlayElement
import io.github.jasondorjeshort.dystopo.ui.OverlayIds
import io.github.jasondorjeshort.dystopo.ui.OverlayPlacement
import io.github.jasondorjeshort.dystopo.ui.OverlayState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T224: what `GET /layout` reports from the elements' rectangles ([MapLayout]); T241: the nodes' record ([MeasuredControls]);
 * T239: the rule's targets through it, and what makes a placed node be placed or measured again in the same frame.
 * T243: a semantics node left where its element is not drawn ([MeasuredControls.semanticsAway]), and the order that keeps
 * it with its element: [control]'s node before its tag, and nothing of the element's semantics or input before [control].
 */
class MapLayoutTest {
    private fun seen(id: String, vararg r: Float, state: String = "shown", within: String? = null) =
        MapLayout.Seen(id, if (r.isEmpty()) null else r, state, null, within)

    @Test
    fun aPairIsAnOverlapFromOneWholePixel() {
        val list = listOf(
            seen(OverlayIds.NAV_CARD, 20f, 200f, 1060f, 900f),
            seen(OverlayIds.TRACK_BAR, 800f, 899f, 1060f, 1000f), // shares one whole row with the card
            seen(OverlayIds.ROUTES, 900f, 1000f, 1060f, 1100f), // touches the bar's bottom edge
            seen(OverlayIds.ONLINE, 0f, 900.5f, 100f, 1000f), // a sub-pixel share is the rounding of two bounds
            seen(OverlayIds.STRIP, state = "hidden"),
        )
        val o = MapLayout.overlaps(list)
        assertEquals(1, o.size, "$o")
        assertEquals(OverlayIds.NAV_CARD, o[0]["a"])
        assertEquals(OverlayIds.TRACK_BAR, o[0]["b"])
        assertEquals("navigationCard", o[0]["aName"])
        assertEquals(listOf(260, 1), o[0]["sizePx"])
    }

    @Test
    fun anElementInsideAnotherByDesignIsNoOverlap() {
        // T241: the Online chip's card lies in the online column (M8), which grows with it; another element over it still counts.
        val o = MapLayout.overlaps(listOf(
            seen(OverlayIds.ONLINE, 20f, 200f, 500f, 700f),
            seen(MapLayout.ONLINE_CARD, 20f, 300f, 500f, 700f, within = OverlayIds.ONLINE),
            seen(OverlayIds.NAV_CARD, 0f, 650f, 1080f, 900f),
        ))
        assertEquals(listOf(OverlayIds.ONLINE to OverlayIds.NAV_CARD, MapLayout.ONLINE_CARD to OverlayIds.NAV_CARD), o.map { it["a"] to it["b"] }, "$o")
        assertEquals(MapLayout.ONLINE_CARD, o[1]["aName"]) // no M-number: named by its manifest id
    }

    @Test
    fun theRulesChangesAreListed() {
        val placed = OverlayPlacement(
            listOf(
                OverlayElement(OverlayIds.TRACK_BAR, null, OverlayState.HIDDEN, "noRoom"),
                OverlayElement(OverlayIds.STRIP, null, OverlayState.HIDDEN, "yieldOrder"),
            ),
            buttonsDrawn = 1, entriesDrawn = 0, sideColumn = false, cardColumns = 1,
        )
        val d = MapLayout.differences(listOf(seen(OverlayIds.TRACK_BAR, 0f, 0f, 1f, 1f), seen(OverlayIds.STRIP, state = "hidden"), seen(OverlayIds.ROUTES, 0f, 0f, 1f, 1f)), placed)
        assertEquals(1, d.size, "$d")
        assertEquals(OverlayIds.TRACK_BAR, d[0]["id"])
        assertEquals("shown", d[0]["today"])
        assertEquals("hidden", d[0]["rule"])
    }

    @Test
    fun thePlacementIsComputedOncePerPass() {
        val c = MeasuredControls()
        val chips = c.attach("waypointAndTracks", null)
        val card = c.attach("planningCard", null)
        // A pass: every node that needs it is measured, then each placement block asks for the placement.
        c.measured(chips, IntSize(300, 120))
        c.measured(card, IntSize(1000, 600))
        val first = c.placement()
        assertSame(first, c.placement(), "a second node placed in the same pass gets the same placement")
        assertEquals(1, c.computed)
        // The next pass begins with the next measure, and its placement is computed again, once.
        c.measured(card, IntSize(1000, 640))
        assertEquals(first.pass + 1, c.pass)
        val second = c.placement()
        assertEquals(first.pass + 1, second.pass)
        assertSame(second, c.placement())
        assertEquals(2, c.computed)
        // A placement with no measure before it (a node moved, not resized) computes nothing.
        c.placed(chips, floatArrayOf(0f, 0f, 1f, 1f))
        assertSame(second, c.placement())
        assertEquals(2, c.computed)
        // With no rule (T239's ruler) nothing is placed by it: every element stays where its own parent put it.
        assertNull(second.target("planningCard"))
        assertNull(second.ruled)
    }

    @Test
    fun thePlacementReadsThisPassesSizes() {
        val c = MeasuredControls()
        val card = c.attach("navigationCard", null)
        val chips = c.attach("waypointAndTracks", null)
        c.measured(card, IntSize(1040, 300))
        c.measured(chips, IntSize(280, 110))
        assertEquals(IntSize(1040, 300), c.placement().size("navigationCard"))
        c.placed(card, floatArrayOf(20f, 150f, 1060f, 450f))
        c.placed(chips, floatArrayOf(780f, 470f, 1060f, 580f))
        // The turn text wraps: the card grows in this pass, and a node placed in it reads the new size, not the last pass's (T146).
        c.measured(card, IntSize(1040, 360))
        val p = c.placement()
        assertEquals(IntSize(1040, 360), p.size("navigationCard"))
        assertEquals(IntSize(280, 110), p.size("waypointAndTracks"), "a node not measured in this pass keeps its size")
    }

    @Test
    fun aHiddenElementKeepsItsSize() {
        val c = MeasuredControls()
        val online = c.attach("onlineIndicator", null)
        c.measured(online, IntSize(400, 90))
        c.placement()
        c.placed(online, floatArrayOf(21f, 190f, 421f, 280f))
        assertTrue(online.onScreen)
        assertContentEquals(floatArrayOf(21f, 190f, 421f, 280f), c.rectOf("onlineIndicator"))
        // Measured and not placed (the column with no room above the credit line): hidden, and its size stays.
        c.measured(online, IntSize(400, 90))
        c.placement()
        assertFalse(online.onScreen)
        assertNull(c.rectOf("onlineIndicator"))
        assertEquals(IntSize(400, 90), c.placement().size("onlineIndicator"))
        assertEquals(IntSize(400, 90), c.sizeOf("onlineIndicator"))
        assertEquals(emptySet(), c.onScreen())
        // Placed again with no new measure: back on screen.
        c.placed(online, floatArrayOf(21f, 190f, 421f, 280f))
        assertTrue(online.onScreen)
        // Its layout node no longer placed by its parent (the scale bar's line with no room), as its semantics say: hidden, size kept.
        assertNull(c.rectOf("onlineIndicator", laidOut = setOf("search")))
        assertEquals(emptySet(), c.onScreen(laidOut = setOf("search")))
        assertContentEquals(floatArrayOf(21f, 190f, 421f, 280f), c.rectOf("onlineIndicator", laidOut = setOf("onlineIndicator")))
        assertEquals(IntSize(400, 90), c.sizeOf("onlineIndicator"))
        // Out of the composition: the size goes with it.
        c.detach(online)
        assertNull(c.sizeOf("onlineIndicator"))
        assertNull(c.placement().size("onlineIndicator"))
    }

    @Test
    fun aCardInAHiddenColumnIsNotOnScreen() {
        val c = MeasuredControls()
        val column = c.attach("onlineIndicator", null)
        val card = c.attach("onlineCard", column)
        c.measured(column, IntSize(683, 474))
        c.measured(card, IntSize(683, 285))
        c.placement()
        c.placed(column, floatArrayOf(21f, 190f, 704f, 664f))
        c.placed(card, floatArrayOf(21f, 278f, 704f, 563f))
        assertEquals(setOf("onlineIndicator", "onlineCard"), c.onScreen())
        // The column is measured and not placed; the card inside it, not measured again, is not placed either.
        c.measured(column, IntSize(683, 474))
        assertEquals(emptySet(), c.onScreen())
        assertNull(c.rectOf("onlineCard"))
        // An empty rectangle (clipped out of the window) is not on screen.
        c.placed(column, floatArrayOf(21f, 190f, 704f, 664f))
        c.placed(card, floatArrayOf(21f, 664f, 704f, 664f))
        assertEquals(setOf("onlineIndicator"), c.onScreen())
    }

    @Test
    fun getControlsSaysWhatIsOnScreen() {
        // A chip column hidden above the credit line is composed (tagged) but not on screen.
        val json = ControlsRoutes.json(mapOf("search" to 1, "onlineIndicator" to 1), setOf("search"))
        @Suppress("UNCHECKED_CAST")
        val items = (json["items"] as List<Map<String, Any?>>).associateBy { it["id"] }
        assertEquals(listOf(1, true), listOf(items.getValue("search")["tagged"], items.getValue("search")["onScreen"]))
        assertEquals(listOf(1, false), listOf(items.getValue("onlineIndicator")["tagged"], items.getValue("onlineIndicator")["onScreen"]))
        assertEquals(false, items.getValue("tripCard")["onScreen"])
        assertFalse(items.values.filter { it.containsKey("row") }.any { it.containsKey("onScreen") }, "a settings row has no onScreen")
    }

    /** T239: a ruler that draws the chips under the card's bottom edge as the card is drawn, and gives the bar a room. */
    private fun chipsUnderTheCard(c: MeasuredControls): MeasuredControls.Ruled {
        val cardBottom = c.rectOf("navigationCard")?.get(3)?.toInt() ?: 300
        val hide = c.sizeOf("routesChip")?.let { it.height > 500 } ?: false
        return MeasuredControls.Ruled(
            targets = mapOf(
                "waypointAndTracks" to MeasuredControls.Target(780, cardBottom + 16, false),
                "routesChip" to MeasuredControls.Target(830, cardBottom + 150, hide, if (hide) "noRoom" else null),
            ),
            limits = mapOf("waypointAndTracks" to MeasuredControls.Limit(maxHeightPx = 2000 - cardBottom)),
        )
    }

    private class Node(val slot: MeasuredControls.Slot) {
        var relayouts = 0
        var remeasures = 0

        init {
            slot.relayout = { relayouts++ }
            slot.remeasure = { remeasures++ }
        }
    }

    @Test
    fun theRuleSaysWhereAPlacedElementGoes() {
        val c = MeasuredControls(::chipsUnderTheCard)
        val chips = c.attach("waypointAndTracks", null)
        val card = c.attach("navigationCard", null)
        c.measured(chips, IntSize(280, 110))
        c.measured(card, IntSize(1040, 300))
        c.placed(card, floatArrayOf(20f, 150f, 1060f, 450f))
        val p = c.placement()
        assertEquals(MeasuredControls.Target(780, 466, false), p.target("waypointAndTracks"))
        assertNull(p.target("navigationCard"), "a card places itself until Layout C")
        // The limit is the bar's room, which its measure takes from then on.
        val k = c.constrain(chips, Constraints(maxWidth = 1080, maxHeight = 2400))
        assertEquals(2000 - 450, k.maxHeight)
        assertEquals(1080, k.maxWidth)
        assertEquals(Constraints(maxWidth = 1080, maxHeight = 2400), c.constrain(card, Constraints(maxWidth = 1080, maxHeight = 2400)))
    }

    @Test
    fun aCardThatMovesHasThePlacedElementsPlacedAgainInTheSameFrame() {
        // T146 through the rule: the card's turn text wraps, the card is drawn taller, and the chips under it follow in the
        // same layout, where Compose re-places only the nodes it is asked to.
        val c = MeasuredControls(::chipsUnderTheCard)
        val chips = Node(c.attach("waypointAndTracks", null))
        val card = c.attach("navigationCard", null)
        c.measured(chips.slot, IntSize(280, 110))
        c.measured(card, IntSize(1040, 300))
        c.placed(card, floatArrayOf(20f, 150f, 1060f, 450f))
        assertEquals(466, c.placement().target("waypointAndTracks")?.topPx)
        assertEquals(1, chips.remeasures, "measured before the rule gave it a room: measured again within it")
        val k = Constraints(maxWidth = 1080, maxHeight = 2400)
        c.constrain(chips.slot, k) // the node's second measure, within the room
        c.measured(chips.slot, IntSize(280, 110))
        assertEquals(466, c.placement().target("waypointAndTracks")?.topPx)
        assertEquals(1, chips.remeasures, "the same room: no third measure")
        c.placed(chips.slot, floatArrayOf(780f, 466f, 1060f, 576f))
        val before = chips.relayouts
        c.measured(card, IntSize(1040, 360)) // the card grows: a new pass, the chips asked to be placed again
        assertEquals(before + 1, chips.relayouts)
        c.placed(card, floatArrayOf(20f, 150f, 1060f, 510f))
        assertEquals(before + 1, chips.relayouts, "one ask per pass")
        assertEquals(526, c.placement().target("waypointAndTracks")?.topPx)
        // Its room changed, so the bar is measured again within the new one.
        assertEquals(2, chips.remeasures)
        assertEquals(2000 - 510, c.constrain(chips.slot, k).maxHeight)
        // A card drawn where it was asks nothing.
        c.placed(card, floatArrayOf(20f, 150f, 1060f, 510f))
        assertEquals(before + 1, chips.relayouts)
        // A move by itself (no new size) does: the warning card follows the chips a frame later and is drawn lower.
        val warning = c.attach("warningCard", null)
        c.measured(warning, IntSize(1000, 200))
        c.placement()
        c.placed(warning, floatArrayOf(40f, 600f, 1040f, 800f))
        c.placement()
        c.placed(warning, floatArrayOf(40f, 640f, 1040f, 840f))
        assertTrue(chips.relayouts > before + 1)
        // A change from outside the layout (the map turned: the compass comes up) asks too.
        val n = chips.relayouts
        c.placement()
        c.invalidate()
        assertEquals(n + 1, chips.relayouts)
    }

    @Test
    fun anElementTheRuleHidesSaysWhyAndKeepsItsSize() {
        val c = MeasuredControls(::chipsUnderTheCard)
        val routes = c.attach("routesChip", null)
        val hidden = ArrayList<Pair<String, String?>>()
        val shown = ArrayList<String>()
        c.onHidden = { id, why -> hidden += id to why }
        c.onShown = { id -> shown += id }
        c.measured(routes, IntSize(200, 600)) // too tall for its room in this ruler
        val t = c.placement().target("routesChip")!!
        assertTrue(t.hidden)
        c.hidden(routes, t.reason)
        c.hidden(routes, t.reason)
        assertEquals<List<Pair<String, String?>>>(listOf("routesChip" to "noRoom"), hidden, "said once")
        assertEquals("noRoom", routes.ruledOut)
        assertFalse(routes.onScreen)
        assertEquals(IntSize(200, 600), c.sizeOf("routesChip"))
        c.measured(routes, IntSize(200, 100))
        assertFalse(c.placement().target("routesChip")!!.hidden)
        c.placed(routes, floatArrayOf(830f, 450f, 1030f, 550f))
        assertEquals(listOf("routesChip"), shown)
        assertNull(routes.ruledOut)
        assertTrue(routes.onScreen)
    }

    @Test
    fun theStripsEntriesAreTheRules() {
        val c = MeasuredControls({ _: MeasuredControls -> MeasuredControls.Ruled(mapOf("readoutStrip" to MeasuredControls.Target(21, 1800, false)), mapOf("readoutStrip" to MeasuredControls.Limit(maxWidthPx = 600, entries = 2))) })
        val strip = Node(c.attach("readoutStrip", null))
        assertNull(c.entriesFor("readoutStrip"), "before the rule's first pass: every entry")
        c.measured(strip.slot, IntSize(700, 190))
        c.placement()
        assertEquals(2, c.entriesFor("readoutStrip"))
        assertEquals(1, strip.remeasures, "measured before the rule gave it a width: measured again within it")
        assertEquals(600, c.constrain(strip.slot, Constraints(maxWidth = 1080, maxHeight = 2400)).maxWidth)
    }

    @Test
    fun aSemanticsNodeLeftBehindIsAway() {
        val c = MeasuredControls()
        val strip = c.attach("readoutStrip", null)
        val bar = c.attach("scaleBar", null)
        val routes = c.attach("routesChip", null)
        c.measured(strip, IntSize(404, 126))
        c.measured(bar, IntSize(166, 74))
        c.measured(routes, IntSize(190, 84))
        c.placed(strip, floatArrayOf(336f, 1619f, 740f, 1745f))
        c.placed(bar, floatArrayOf(21f, 1661f, 187f, 1735f))
        c.placed(routes, floatArrayOf(870f, 360f, 1060f, 444f))
        // 478deae5 under the long-press card: the strip and the scale bar drawn where the rule put them, their semantics
        // nodes left at the bottom left where the scale bar's own Layout places them, over the card.
        val semantics = mapOf(
            "readoutStrip" to listOf(floatArrayOf(0f, 2063f, 404f, 2189f)),
            "scaleBar" to listOf(floatArrayOf(21f, 2189f, 187f, 2253f)),
            "routesChip" to listOf(floatArrayOf(870.4f, 359.6f, 1060.4f, 443.6f)), // where it is drawn, to the rounding
        )
        val away = c.semanticsAway(semantics)
        assertEquals(listOf("readoutStrip", "scaleBar"), away.map { it["id"] }, "$away")
        assertEquals(listOf(336, 1619, 740, 1745), away[0]["rectPx"])
        assertEquals(listOf(listOf(0, 2063, 404, 2189)), away[0]["semanticsPx"])
        // Where the fix puts them: with their drawing. A tag on two nodes (the way-back card) passes when one matches.
        val fixed = semantics + mapOf(
            "readoutStrip" to listOf(floatArrayOf(0f, 2063f, 404f, 2189f), floatArrayOf(336f, 1619f, 740f, 1745f)),
            "scaleBar" to listOf(floatArrayOf(21f, 1661f, 187f, 1735f)),
        )
        assertTrue(c.semanticsAway(fixed).isEmpty())
        // Not judged: an element with no semantics rectangle, one not laid out, one the rule hides.
        assertTrue(c.semanticsAway(semantics - "readoutStrip" - "scaleBar").isEmpty())
        assertEquals(listOf("scaleBar"), c.semanticsAway(semantics, laidOut = setOf("scaleBar", "routesChip")).map { it["id"] })
        c.hidden(strip, "noRoom")
        assertEquals(listOf("scaleBar"), c.semanticsAway(semantics).map { it["id"] })
    }

    /** The code of [f] with comments blanked and its lines kept, as `ControlIdsTest` reads it. */
    private fun code(f: File): String = f.readText().replace("\r\n", "\n")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .replace(Regex("//[^\n]*")) { m -> " ".repeat(m.value.length) }

    @Test
    fun theControlNodeComesBeforeTheTag() {
        // T243: a semantics modifier outside the node stays where the parent put the node, and the layout node's whole
        // semantics node with it (Compose bounds it by its outermost semantics modifier), while the node draws the content
        // at the rule's rectangle.
        val body = code(File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app/MapLayout.kt"))
            .substringAfter("fun Modifier.control(id: String): Modifier =").substringBefore("\n").trim()
        val node = body.indexOf("ControlElement(id)")
        val tag = body.indexOf("testTag(id)")
        assertTrue(node >= 0 && tag > node, "Modifier.control must read this.then(ControlElement(id)).testTag(id), the tag inside the node (T243); it reads: $body")
    }

    @Test
    fun nothingOfAnElementsSemanticsOrInputComesBeforeItsControl() {
        // T243: at every `.control(` call the chain from its `Modifier` up to the call holds only what places the element
        // (align, padding, insets, size limits): a description, a click or a pointer handler before it stays behind.
        val before = Regex("""\b(semantics|clearAndSetSemantics|testTag|clickable|combinedClickable|pointerInput|toggleable|selectable|focusable|draggable|scrollable|verticalScroll|horizontalScroll)\b""")
        val call = Regex("""(?<!fun Modifier)\.control\(""")
        val bad = ArrayList<String>()
        var calls = 0
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sortedBy { it.path }.forEach { f ->
            val t = code(f)
            for (m in call.findAll(t)) {
                calls++
                val start = t.lastIndexOf("Modifier", m.range.first)
                val chain = if (start < 0) "" else t.substring(start, m.range.first)
                val hit = before.find(chain) ?: continue
                val line = t.substring(0, m.range.first).count { it == '\n' } + 1
                bad += "androidApp/src/main/kotlin/${f.invariantSeparatorsPath.substringAfter("src/main/kotlin/")}:$line: " +
                    "'${hit.value}' comes before .control(: move it after the call, inside the control node (T243)"
            }
        }
        assertTrue(calls >= 15, "the reader found $calls .control( calls (19 at T243)")
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    /**
     * T261: the API 35 pixel_6 AVD (1080 x 2400 px at 420 dpi, 914 x 411 dp on its side) at font scale 2.0 with a
     * warning up, as `GET /layout` read it: the chips' column at the right ended 162 dp down and set the card's top at
     * 170 dp; the card keeps its title, advice and actions (169 dp) and reached 13 dp over the credit line, which a pack
     * credited to two elevation sources wraps to two lines (top at 855 px). Under the elements across its own span only
     * (the online column), it starts at 120 dp and ends 37 dp above the credit line. Upright the chips cross it, as before.
     */
    @Test
    fun theWarningStartsUnderWhatIsAboveItNotUnderTheChipsBesideIt() {
        val d = 2.625f
        val bands = listOf(floatArrayOf(1747f, 95f, 2368f, 195f), floatArrayOf(21f, 84f, 599f, 191f))
        val landscape = MapLayout.topChrome(
            bands,
            listOf(floatArrayOf(149f, 210f, 675f, 295f), floatArrayOf(1860f, 210f, 2379f, 310f), floatArrayOf(2151f, 326f, 2379f, 426f), floatArrayOf(0f, 0f, 0f, 0f)),
            2400f, 63f, d,
        )
        assertEquals(6, landscape.size, "the empty one left out, the status bar in")
        val left = (914.3f - 420f) / 2
        val chromeTop = 426f / d + 8f // the old top: under the Routes chip
        val compass = floatArrayOf(2253f / d, 442f / d, 2379f / d, 568f / d)
        val top = io.github.jasondorjeshort.dystopo.app.routing.CardBands.warningTop(chromeTop, compass, left, left + 420f, 191.4f, over = landscape)
        assertEquals(295f / d + 8f, top, 0.01f)
        assertTrue(top + 443f / d <= 855f / d - 6f, "the card's least ends above the credit line")
        // At 1.0 the online column ends left of the card: Search's and the settings row's bands still hold it under them
        // (the rule's frame), never up beside Search; with no element read it keeps the old top.
        val narrowOnline = MapLayout.topChrome(bands, listOf(floatArrayOf(149f, 195f, 569f, 267f), floatArrayOf(1954f, 195f, 2379f, 280f)), 2400f, 63f, d)
        assertEquals(195f / d + 8f, io.github.jasondorjeshort.dystopo.app.routing.CardBands.warningTop(chromeTop, null, left, left + 420f, 191.4f, over = narrowOnline), 0.01f)
        assertEquals(63f / d + 8f, io.github.jasondorjeshort.dystopo.app.routing.CardBands.warningTop(chromeTop, null, left, left + 420f, 191.4f, over = MapLayout.topChrome(emptyList(), emptyList(), 2400f, 63f, d)), 0.01f)
        assertEquals(chromeTop, io.github.jasondorjeshort.dystopo.app.routing.CardBands.warningTop(chromeTop, null, left, left + 420f, 191.4f), 0.01f)
        // Upright (411 dp wide): the card spans the window, the chips cross it and set its top as they did.
        val upright = MapLayout.topChrome(
            listOf(floatArrayOf(21f, 84f, 700f, 280f), floatArrayOf(760f, 95f, 1059f, 195f)), listOf(floatArrayOf(700f, 300f, 1059f, 520f)),
            1080f, 63f, d,
        )
        assertEquals(520f / d + 8f, io.github.jasondorjeshort.dystopo.app.routing.CardBands.warningTop(520f / d + 8f, null, 12f, 399f, 700f, over = upright), 0.01f)
    }

    /**
     * T277: on a window that is not edge to edge (Android 14 and earlier, the owner's phone: Compose's root lies between
     * the status bar and the navigation bar), the strip, the scale bar and the position column stand above the track
     * panels as Compose draws them. Before T277 the rule laid out in the whole window while the panels' column and every
     * placed element are in the Compose root's coordinates, so they sat the two bars' height down, over the panel's header
     * and statistics line.
     */
    @Test
    fun theLowerColumnsClearTheTrackPanelsOnAWindowThatIsNotEdgeToEdge() {
        for (edgeToEdge in listOf(false, true)) {
            val window = floatArrayOf(411f, 914f)
            val bars = floatArrayOf(0f, 49f, 0f, 24f)
            val content = if (edgeToEdge) floatArrayOf(0f, 0f, 411f, 914f) else floatArrayOf(0f, 49f, 411f, 890f)
            val composeH = content[3] - content[1]
            val (root, ins) = MapLayout.ruleFrame(window, content, bars)
            // The column as fromWindowBottom reports it: 300 dp up to 40 dp up from the Compose root's bottom.
            val panels = MapLayout.panelsRect(root[0], root[1], 300f, 40f)
            val drawn = io.github.jasondorjeshort.dystopo.ui.OverlayRect(8f, composeH - 300f, 403f, composeH - 40f)
            val input = io.github.jasondorjeshort.dystopo.ui.OverlayInput(
                windowWidth = root[0], windowHeight = root[1],
                insetLeft = ins[0], insetTop = ins[1], insetRight = ins[2], insetBottom = ins[3],
                fontScale = 1f,
                search = io.github.jasondorjeshort.dystopo.ui.OverlaySize(io.github.jasondorjeshort.dystopo.ui.OverlayLayout.SEARCH_WIDTH, 48f),
                topRow = io.github.jasondorjeshort.dystopo.ui.OverlaySize(190f, 40f),
                credit = io.github.jasondorjeshort.dystopo.ui.OverlaySize(380f, 30f),
                trackBar = io.github.jasondorjeshort.dystopo.ui.OverlaySize(160f, 40f),
                scaleBar = io.github.jasondorjeshort.dystopo.ui.OverlaySize(80f, 28f),
                strip = List(3) { io.github.jasondorjeshort.dystopo.ui.OverlaySize(72f, 40f) },
                buttons = 3,
                panels = panels,
                cardsDrawn = true,
            )
            val placed = io.github.jasondorjeshort.dystopo.ui.OverlayLayout.place(input)
            for (id in listOf(OverlayIds.STRIP, OverlayIds.SCALE_BAR, OverlayIds.POSITION, OverlayIds.ORIENTATION, OverlayIds.LOCK)) {
                val r = placed[id]?.rect ?: continue
                assertTrue(r.bottom <= drawn.top || r.top >= drawn.bottom || r.right <= drawn.left || r.left >= drawn.right,
                    "edgeToEdge=$edgeToEdge: $id at $r covers the track panels drawn at $drawn")
            }
            assertTrue(placed[OverlayIds.STRIP]?.rect != null, "edgeToEdge=$edgeToEdge: the strip has room above the panels")
        }
    }

    /**
     * T263: the activity chip on both window kinds (T277's recipe: the rule works in the Compose root's coordinates), upright
     * and on its side at font scales 1.0 and 2.0: under the settings row, the track bar and the Routes chip, at the chips'
     * right edge, inside the root and clear of the track panels drawn at the bottom.
     */
    @Test
    fun theActivityChipSitsUnderTheRoutesChipOnBothWindowKinds() {
        for (edgeToEdge in listOf(false, true)) for (landscape in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            val w = if (landscape) 914f else 411f
            val h = if (landscape) 411f else 914f
            val window = floatArrayOf(w, h)
            val bars = if (landscape) floatArrayOf(0f, 24f, 48f, 0f) else floatArrayOf(0f, 49f, 0f, 24f)
            val content = if (edgeToEdge) floatArrayOf(0f, 0f, w, h) else floatArrayOf(0f, bars[1], w - bars[2], h - bars[3])
            val (root, ins) = MapLayout.ruleFrame(window, content, bars)
            val k = if (scale > 1f) 1.3f else 1f // the chips' capped font scale (MapChrome)
            val panels = MapLayout.panelsRect(root[0], root[1], 100f, 40f)
            val input = io.github.jasondorjeshort.dystopo.ui.OverlayInput(
                windowWidth = root[0], windowHeight = root[1],
                insetLeft = ins[0], insetTop = ins[1], insetRight = ins[2], insetBottom = ins[3],
                fontScale = scale,
                search = io.github.jasondorjeshort.dystopo.ui.OverlaySize(io.github.jasondorjeshort.dystopo.ui.OverlayLayout.SEARCH_WIDTH, 48f * k),
                topRow = io.github.jasondorjeshort.dystopo.ui.OverlaySize(190f * k, 40f * k),
                credit = io.github.jasondorjeshort.dystopo.ui.OverlaySize(380f, 30f * k),
                trackBar = io.github.jasondorjeshort.dystopo.ui.OverlaySize(160f * k, 40f * k),
                routes = io.github.jasondorjeshort.dystopo.ui.OverlaySize(70f * k, 36f * k),
                activity = io.github.jasondorjeshort.dystopo.ui.OverlaySize(78f * k, 36f * k),
                scaleBar = io.github.jasondorjeshort.dystopo.ui.OverlaySize(80f, 28f),
                strip = List(2) { io.github.jasondorjeshort.dystopo.ui.OverlaySize(72f, 40f) },
                buttons = 1,
                panels = panels,
                cardsDrawn = true,
            )
            val at = "edgeToEdge=$edgeToEdge landscape=$landscape scale=$scale"
            val placed = io.github.jasondorjeshort.dystopo.ui.OverlayLayout.place(input)
            assertTrue(placed.overlaps().isEmpty(), "$at: ${placed.overlaps()}")
            val chip = placed[OverlayIds.ACTIVITY]?.rect
            assertTrue(chip != null, "$at: the activity chip is shown")
            val routes = placed[OverlayIds.ROUTES]!!.rect!!
            val row = placed[OverlayIds.SETTINGS_ROW]!!.rect!!
            assertTrue(chip.top >= routes.bottom && chip.top >= row.bottom, "$at: $chip under Routes $routes and the settings row $row")
            assertTrue(kotlin.math.abs(chip.right - routes.right) < 0.01f, "$at: $chip at the chips' edge with Routes $routes")
            assertTrue(chip.top >= ins[1] && chip.bottom <= root[1] - ins[3] && chip.right <= root[0] - ins[2], "$at: $chip inside the root $root less its insets")
            assertTrue(chip.bottom <= panels!!.top, "$at: $chip clear of the track panels $panels")
        }
    }
}
