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
    /** The tasks that added a row after T103 (T126: spoken directions; T143: diagnostics; T145: problem reports). */
    private val AFTER_T103 = setOf("T126", "T143", "T145", "T164") // T164: route times
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
        assertEquals(listOf("Offline areas", "Sync status", "Share", "Credits"), added)
        assertEquals(listOf("Navigation: Spoken directions", "Route times", "Diagnostics", "Report a problem"), later) // T164: Route times
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
        assertEquals("Sources (server, mobile data), offline areas, storage with a 1\u00A0GB cache budget", SettingsLayout.summary(SettingsGroup.DATA, 1024))
        assertEquals("Sources (server, mobile data), offline areas, storage with a 128\u00A0GB cache budget", SettingsLayout.summary(SettingsGroup.DATA, 131072))
        assertEquals(SettingsGroup.MAP.summary, SettingsLayout.summary(SettingsGroup.MAP, 1024))
        assertTrue((groups().first()["summary"] as String).endsWith("cache budget"), "GET /settings/layout reports the line as drawn")
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
