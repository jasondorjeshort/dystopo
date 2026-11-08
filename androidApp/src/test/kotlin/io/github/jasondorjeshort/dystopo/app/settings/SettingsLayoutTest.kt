package io.github.jasondorjeshort.dystopo.app.settings

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T103: the grouped settings sheet keeps every row the one long sheet had, each in exactly one group, and the
 * server field stays two taps from the map (Settings, then the first group, whose first row is Sources).
 * The rows are read from `GET /settings/layout`'s body ([SettingsRoutes.layout]), which is the list the sheet's
 * pages draw ([GroupPage] walks [SettingsLayout.rows]).
 */
class SettingsLayoutTest {
    /** The tasks that added a row after T103 (T126: spoken directions; T143: diagnostics; T145: problem reports; T164: route times; T163: the turn-around clock's two rows; T172: recording). */
    private val AFTER_T103 = setOf("T126", "T143", "T145", "T164", "T163", "T172", "T161", "T176", "T177", "T178", "T187", "T182", "T183", "T184", "T194", "T197", "T203", "T213") // T213: the trail disagreements' switch; T203: the terrain estimate's switch; T194: the spoken terrain shade row; T161: the readout rows; T176: the position button rows; T177: the readout bar rows; T178: the pressure-fall warning; T187: the water rows; T182: the view range; T183: the printed sheet; T184: the route time form
    /** Every row of the settings sheet before T103, top to bottom, by its heading as drawn. */
    private val beforeT103 = listOf(
        "Sources", "Updates", "Storage", "Units", "Text size", "Contour warm-up", "Route snap",
        "Navigation: GPS fix stale after", "Navigation: Snooze", "Map",
    )

    @Suppress("UNCHECKED_CAST")
    private fun groups(): List<Map<String, Any?>> {
        val body = MiniJson.parseObject(JsonWriter.write(SettingsRoutes.layout()))
        return body["groups"] as List<Map<String, Any?>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(group: Map<String, Any?>): List<Map<String, Any?>> = group["rows"] as List<Map<String, Any?>>

    @Test
    fun everyRowOfTheOldSheetIsInExactlyOneGroup() {
        val all = groups().flatMap { rows(it) }
        val labels = all.map { it["label"] as String }
        for (row in beforeT103) assertEquals(1, labels.count { it == row }, "'$row' should be in exactly one group: $labels")
        assertEquals(labels.size, labels.toSet().size, "a row is listed twice: $labels")
        // Nothing else went missing or appeared unannounced: what is not an old row is a link T103 added.
        val added = all.filter { it["since"] == "T103" }.map { it["label"] as String }
        // Rows added after T103, each by its task.
        val later = all.filter { it["since"] in AFTER_T103 }.map { it["label"] as String }
        assertEquals(beforeT103.sorted(), (labels - added.toSet() - later.toSet()).sorted())
        assertEquals(listOf("Offline maps", "Sync status", "Share", "Credits"), added) // T235: the Offline areas row is "Offline maps"
        assertEquals(
            listOf(
                "Position button", "Position button: Close zoom", "Keep north up", // T176 (the Map page, second in list order)
                "View range", // T182 (the Map page, after the position button)
                "Print a map sheet", // T183 (the Map page, after T182's row)
                "Show trail disagreements", // T213 (the Map page, last)
                "Navigation: Spoken directions",
                "Route times", // T164
                "Navigation: Turn back by", "Navigation: Turn-back margin", // T163
                // T172 (the Navigation and tracking page, so before the About rows in list order)
                "Recording: Activity", "Recording: Accuracy limit", "Recording: Auto-pause", "Recording: Network location",
                "Recording: Interval", "Recording: Battery",
                // T161: the readout rows (Navigation and tracking, after T172's)
                "Readout: Speed", "Readout: Climb", "Readout: Course", "Readout: Elevation", "Readout: Barometer",
                "Readout bar", "Readout: Climb window", // T177
                "Pressure-fall warning", // T178
                "Water along the route", "Water: Spoken", // T187
                "Route times: Show as", // T184
                "Terrain shade: Spoken", // T194
                "Set my height now", // T197 (an action, after T184's row)
                "Estimate position when GPS is poor", // T203
                "Technical details", "Report a problem", // T143, the fold's heading since T223; T145
            ),
            later,
        )
    }

    @Test
    fun theServerFieldIsTwoTapsFromTheMap() {
        val body = SettingsRoutes.layout()
        val first = groups().first()
        assertEquals("data", first["id"], "Data and storage must be the first group (T79: a fresh install looks for the server first)")
        assertEquals("data", body["serverGroup"])
        assertEquals("sources", rows(first).first()["id"], "Sources must be the first row of its page")
        assertEquals(SettingsGroup.DATA, SettingsLayout.SERVER_GROUP)
    }

    @Test
    fun dataAndStorageNamesTheCacheBudget() {
        // T115 (T112's follow-up): the group's line in the list says the total; the other groups' lines are as they were.
        assertEquals("Sources (server, mobile data), offline maps, storage with a 1\u00A0GB cache budget", SettingsLayout.summary(SettingsGroup.DATA, 1024))
        assertEquals("Sources (server, mobile data), offline maps, storage with a 128\u00A0GB cache budget", SettingsLayout.summary(SettingsGroup.DATA, 131072))
        assertEquals(SettingsGroup.MAP.summary, SettingsLayout.summary(SettingsGroup.MAP, 1024))
        assertTrue((groups().first()["summary"] as String).endsWith("cache budget"), "GET /settings/layout reports the line as drawn")
    }

    @Test
    fun theSummariesNameWhatTheGroupsHold() { // T223
        val navigation = SettingsGroup.NAVIGATION.summary.lowercase()
        for (word in listOf("water", "pressure", "shade", "estimate")) assertTrue(word in navigation, "'$word' is not in \"$navigation\"")
        assertTrue("technical details" in SettingsGroup.ABOUT.summary, SettingsGroup.ABOUT.summary)
    }

    @Test
    fun everyBooleanIsTheOneControl() { // T223: On then Off, in one composable; ADR 0053's ids are the keys these rows set
        val src = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app")
        val pages = File(src, "settings/SettingsPages.kt").readText()
        val options = File(src, "options/MapOptionsSection.kt").readText()
        assertEquals(1, Regex("""Option\("On"""").findAll(pages).count(), "the pair is drawn in OnOff, and only there")
        assertEquals(0, Regex("""Option\("On"""").findAll(options).count(), "the Map page uses OnOff")
        assertTrue("Chip(" !in options, "the Map page's choices are the settings rows' Option")
        for (key in listOf("trails", "water", "landcover", "pois", "boundaries")) {
            assertTrue("change(mapOf(\"$key\" to it))" in options, "the layer '$key' sets a state rather than flipping it")
        }
        assertEquals(6, Regex("""\bOnOff\(""").findAll(options).count(), "the layers' row, Shade, Drainage, Airport gates (T230), Night and the scale bar's both units")
    }

    @Test
    fun groupsAndIds() {
        assertEquals(listOf("data", "map", "display", "navigation", "sync", "about"), groups().map { it["id"] })
        for (g in groups()) assertTrue(rows(g).isNotEmpty(), "group ${g["id"]} has no rows")
        assertEquals(SettingsGroup.DATA, SettingsGroup.byId(" Data"))
        assertNull(SettingsGroup.byId("list"))
        assertNull(SettingsGroup.byId("tabs"))
        val ids = SettingsRow.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun theLabelsAreWhatThePagesDraw() {
        val src = File("src/main/kotlin")
        val pages = File(src, "io/github/jasondorjeshort/dystopo/app/settings/SettingsPages.kt").readText()
        for (row in SettingsRow.entries) {
            assertTrue("SettingsRow.${row.name} ->" in pages, "SettingsPages draws no ${row.name}")
            if (row.drawnBy == "SettingsPages") continue
            // A section another file draws: its heading must still be the label the layout reports.
            val file = src.walkTopDown().first { it.name == "${row.drawnBy}.kt" }
            assertTrue(
                "BasicText(\"${row.label}\"" in file.readText(),
                "${row.drawnBy} no longer draws the heading \"${row.label}\": set SettingsRow.${row.name}'s label to its heading now",
            )
        }
    }

    @Test
    fun closingStartsAgainAtTheList() {
        SettingsUi.open(SettingsGroup.MAP)
        assertTrue(SettingsUi.visible)
        assertEquals("map", SettingsRoutes.layout()["group"])
        assertEquals(true, SettingsRoutes.layout()["open"])
        SettingsUi.visible = false
        assertNull(SettingsUi.group)
        SettingsUi.visible = true
        assertNull(SettingsRoutes.layout()["group"], "the Settings chip opens the list")
        SettingsUi.visible = false
    }
}

// T208: ADR 0053 part (a) for the map options. The placement manifest (shared/.../controls/MapControls.kt) places each
// map option in zone `settings` on the row that draws it; that row must exist in SettingsLayout.kt, in the item's
// category, and every row that draws map options must be named by an item. The categories are the settings groups.
class SettingsLayoutMapControlsTest {
    private val manifest = "shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/controls/MapControls.kt"
    private val layout = "androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/settings/SettingsLayout.kt"
    private val android = io.github.jasondorjeshort.dystopo.controls.Platform.ANDROID

    @Test
    fun everyMapOptionRowIsAManifestItemAndBack() {
        val items = io.github.jasondorjeshort.dystopo.controls.MapControls.items
        val rows = SettingsRow.entries.associateBy { it.id }
        for (item in items) {
            val place = item.placements[android] ?: continue
            if (place.zone != "settings") continue
            val row = kotlin.test.assertNotNull(
                rows[place.row],
                "The map option \"${item.id}\" is placed on the settings row \"${place.row}\", which $layout has no longer. " +
                    "Fix: give that row its id \"${place.row}\" back in $layout, or set the item's android row in $manifest to the row that draws it",
            )
            assertEquals(
                row.group.id, place.category,
                "The map option \"${item.id}\" is in category \"${place.category}\" in $manifest, but its row \"${row.id}\" is in group " +
                    "\"${row.group.id}\" in $layout. Fix: move the item's android category in $manifest, or the row's group in $layout",
            )
            assertEquals(item.syncKey, io.github.jasondorjeshort.dystopo.app.sync.SettingsSync.syncedKey(item.id), "${item.id}'s sync key")
        }
        val named = items.mapNotNull { it.placements[android]?.row }.toSet()
        for (row in SettingsRow.entries.filter { it.drawnBy == "MapOptionsSection" }) {
            assertTrue(
                row.id in named,
                "The settings row \"${row.id}\" in $layout draws map options (MapOptionsSection), and no item of $manifest names it. " +
                    "Fix: set the android row of the items it draws to \"${row.id}\" in $manifest, or draw it with another section in $layout",
            )
        }
    }

    @Test
    fun theCategoriesAreTheSettingsGroups() {
        val categories = io.github.jasondorjeshort.dystopo.controls.Controls.categories
        assertEquals(
            SettingsGroup.entries.mapIndexed { i, g -> Triple(g.id, g.title, i + 1) },
            categories.map { Triple(it.id, it.title, it.order) },
            "The manifest's placeholder categories (shared/.../controls/Controls.kt) are the settings groups of $layout, in order " +
                "(ADR 0053 §4): change both together until the owner's categories replace them",
        )
    }
}
// end T208
