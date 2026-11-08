package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.settings.SettingsRow
import io.github.jasondorjeshort.dystopo.app.sync.SettingsSync
import io.github.jasondorjeshort.dystopo.controls.AndroidControls
import io.github.jasondorjeshort.dystopo.controls.Controls
import io.github.jasondorjeshort.dystopo.controls.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T238 (ADR 0053 §5, build step 2): the phone's half of the controls manifest (`shared/.../controls/`) against the
 * phone's code, both ways, by reading the sources as `HelpRegistryTest` does (comments skipped):
 *
 * - Part (c): every `Modifier.control("<id>")` names an item the manifest places on the phone outside zone `settings`
 *   (a phone id the manifest lacks), and every such item is named by one (an item no control names), except the
 *   compass ([UNTAGGED]).
 * - Part (a), widened from T208's map options to every row: every settings sheet row is named by an item in zone
 *   `settings`, in its group, in sheet order, and every such item's row exists.
 *
 * Each refusal names the id, the file and line or the manifest's file, and the edit on either side (ADR 0053's table).
 */
class ControlIdsTest {
    private val src = File("src/main/kotlin")
    private val layout = "androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/settings/SettingsLayout.kt"
    private val android = Platform.ANDROID

    /** Phone map items no tag names, with the reason. */
    private val UNTAGGED = mapOf("compass" to "MapLibre's own compass view: no Compose node to tag")

    /** The code of [f] with comments blanked and its lines kept (a line number still points at the source). */
    private fun code(f: File): String = f.readText().replace("\r\n", "\n")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .replace(Regex("//[^\n]*")) { m -> " ".repeat(m.value.length) }

    private class Tag(val id: String?, val where: String, val text: String)

    /** Every `.control(` call in the app's sources; [Tag.id] is null when its argument is not a string literal. */
    private val tags: List<Tag> by lazy {
        val call = Regex("""(?<!fun Modifier)\.control\(\s*("([^"]*)"\s*\)|[^)]*\))""")
        src.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sortedBy { it.path }.flatMap { f ->
            val t = code(f)
            call.findAll(t).map { m ->
                val line = t.substring(0, m.range.first).count { it == '\n' } + 1
                Tag(m.groupValues[2].takeIf { m.groupValues[1].startsWith("\"") }, "androidApp/src/main/kotlin/${f.invariantSeparatorsPath.substringAfter("src/main/kotlin/")}:$line", m.value)
            }.toList()
        }.toList()
    }

    private val mapItems by lazy { Controls.placedOn(android).filter { it.placements.getValue(android).zone != "settings" } }

    @Test
    fun theReaderFindsTheTags() {
        assertTrue(tags.size >= 13, "the reader found ${tags.size} .control(\"<id>\") calls under androidApp/${src.invariantSeparatorsPath}; the pattern \\.control\\(\"<id>\"\\) matched too few")
        for (t in tags) assertTrue(t.id != null, "${t.where}: ${t.text}: Modifier.control takes its id as a string literal, so ControlIdsTest can read it")
    }

    @Test
    fun everyTagNamesAPhoneItem() {
        val ids = mapItems.associateBy { it.id }
        for (t in tags) {
            val id = t.id ?: continue
            assertTrue(
                id in ids,
                "${t.where}: Modifier.control(\"$id\") names no item the controls manifest places on the phone's map. Fix: add the " +
                    "item with an android placement outside zone settings in ${Controls.sourceOf(id)}, or correct the tag to an item's id " +
                    "(${ids.keys.sorted().joinToString()})",
            )
        }
    }

    @Test
    fun everyPhoneItemIsTagged() {
        val tagged = tags.mapNotNull { it.id }.toSet()
        for (item in mapItems) {
            if (item.id in UNTAGGED) {
                assertTrue(item.id !in tagged, "${item.id} is tagged now: take it off ControlIdsTest's UNTAGGED list")
                continue
            }
            assertTrue(
                item.id in tagged,
                "The controls manifest's item \"${item.id}\" (${Controls.sourceOf(item.id)}, zone ${item.placements.getValue(android).zone}) " +
                    "is named by no Modifier.control(\"${item.id}\") in androidApp/${src.invariantSeparatorsPath}. Fix: add .control(\"${item.id}\") to the element's " +
                    "outermost modifier, where it publishes its rectangle, or remove the item's android placement",
            )
        }
    }

    @Test
    fun everySettingsRowIsAnItemAndBack() {
        val settings = Controls.placedOn(android).filter { it.placements.getValue(android).zone == "settings" }
        val rows = SettingsRow.entries.associateBy { it.id }
        val named = settings.mapNotNull { it.placements.getValue(android).row }.toSet()
        for (row in SettingsRow.entries) {
            assertTrue(
                row.id in named,
                "The settings row \"${row.id}\" in $layout is no item of the controls manifest. Fix: add it to the rows of " +
                    "${Controls.DIR}/AndroidControls.kt (its id, group \"${row.group.id}\", the order of its place in the sheet), " +
                    "or name it as a shared item's android row",
            )
        }
        for (item in settings) {
            val p = item.placements.getValue(android)
            val row = rows[p.row]
            assertTrue(
                row != null,
                "The item \"${item.id}\" (${Controls.sourceOf(item.id)}) is placed on the settings row \"${p.row}\", which $layout " +
                    "has not. Fix: give the row its id \"${p.row}\" in $layout, or set the item's android row to the row that draws it",
            )
            assertEquals(row!!.group.id, p.category, "\"${item.id}\" is in category \"${p.category}\" in ${Controls.sourceOf(item.id)}, its row \"${row.id}\" in group \"${row.group.id}\" in $layout: move one")
            item.syncKey?.let { assertEquals(SettingsSync.syncedKey(item.id), it, "${item.id}'s sync key is SettingsSync's") }
        }
        // A phone-only row is placed by its own id (ADR 0053 §4).
        for (item in AndroidControls.ROWS) assertEquals(item.id, item.placements.getValue(android).row)
        // Within each group the items follow the sheet: a later order is never an earlier row.
        val index = SettingsRow.entries.withIndex().associate { (i, r) -> r.id to i }
        for ((category, items) in settings.groupBy { it.placements.getValue(android).category }) {
            val inOrder = items.sortedBy { it.placements.getValue(android).order }.map { it.placements.getValue(android).row!! }
            assertEquals(inOrder.sortedBy { index.getValue(it) }, inOrder, "category $category: the orders follow the rows' sheet order in $layout")
        }
    }

    @Test
    fun theRouteListsEveryPhoneItemWithItsZone() {
        val json = ControlsRoutes.json(mapOf("search" to 1, "nobody" to 2))
        @Suppress("UNCHECKED_CAST")
        val items = json["items"] as List<Map<String, Any?>>
        assertEquals(Controls.placedOn(android).map { it.id }, items.map { it["id"] })
        assertEquals(items.size, json["count"])
        for (i in items) assertTrue(i["zone"] in android.zones, "$i")
        val search = items.first { it["id"] == "search" }
        assertEquals(listOf("map-top", 1, listOf("M3", "M4", "M5", "M6", "M7")), listOf(search["zone"], search["tagged"], search["inventory"]))
        assertEquals(0, items.first { it["id"] == "tripCard" }["tagged"])
        assertEquals("textSize", items.first { it["id"] == "textScale" }["row"])
        assertEquals(listOf("nobody"), json["strayTags"])
        @Suppress("UNCHECKED_CAST")
        val byZone = json["byZone"] as Map<String, Int>
        assertEquals(items.size, byZone.values.sum())
    }
}
