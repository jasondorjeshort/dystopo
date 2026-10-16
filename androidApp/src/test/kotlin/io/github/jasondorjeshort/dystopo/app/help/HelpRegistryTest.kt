package io.github.jasondorjeshort.dystopo.app.help

import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T109: the help registry against the screens that show it. The screens name a text by its key, in a [HelpIcon] call
 * or as the `help = "…"` of a row helper; the test reads those references from the app's sources (comments skipped)
 * and checks both ways: every key a screen references has a text, and every text has a key a screen references (no
 * orphan either way). Each text is declared in the same file as its icon, so a removed control takes its text along.
 */
class HelpRegistryTest {
    private val src = File("src/main/kotlin")

    private fun code(f: File): String =
        f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")

    private val files: List<File> by lazy { src.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }

    /** key -> the files whose code shows it (a HelpIcon call with the key, or a row helper's `help = "key"`). */
    private val references: Map<String, Set<String>> by lazy {
        val rx = Regex("""(?:HelpIcon\(\s*|\bhelp\s*=\s*)"([A-Za-z0-9_.]+)"""")
        val out = HashMap<String, MutableSet<String>>()
        for (f in files) for (m in rx.findAll(code(f))) out.getOrPut(m.groupValues[1]) { mutableSetOf() } += f.name
        out
    }

    /** key -> the files that declare its text (`HelpText("key", …)`). */
    private val declarations: Map<String, Set<String>> by lazy {
        val rx = Regex("""HelpText\(\s*"([A-Za-z0-9_.]+)"""")
        val out = HashMap<String, MutableSet<String>>()
        for (f in files) for (m in rx.findAll(code(f))) out.getOrPut(m.groupValues[1]) { mutableSetOf() } += f.name
        out
    }

    private val keys get() = HelpRegistry.all.map { it.key }

    @Test
    fun everyKeyTheScreensReferenceHasText() {
        assertTrue(references.isNotEmpty(), "no HelpIcon references found under $src")
        val missing = references.keys - keys.toSet()
        assertTrue(missing.isEmpty(), "screens reference help keys with no text: $missing (files ${missing.map { references[it] }})")
    }

    @Test
    fun everyTextHasAKeyOnAScreen() {
        val orphans = keys.toSet() - references.keys
        assertTrue(orphans.isEmpty(), "help texts no screen shows: $orphans (remove them with their control)")
        assertEquals(keys.size, keys.toSet().size, "a help key is declared twice: $keys")
        // Every declared text is in the registry (a list declared but not gathered would be an orphan of another kind).
        assertEquals(declarations.keys.sorted(), keys.sorted())
    }

    @Test
    fun eachTextSitsInTheFileOfItsIcon() {
        for (h in HelpRegistry.all) {
            val declared = declarations[h.key].orEmpty()
            assertEquals(1, declared.size, "${h.key} is declared in $declared")
            assertTrue(
                declared.single() in references[h.key].orEmpty(),
                "${h.key}'s text is in ${declared.single()} but its icon is in ${references[h.key]}: keep the text next to the control",
            )
        }
    }

    @Test
    fun theFirstPlacementsAreAllThere() {
        // The task's list (T109): every Map options row, the Offline areas sheet, the Settings rows that need it
        // (Sources, mobile data and its limit, units, text size, the navigation thresholds, sync and sharing), the
        // track bar and the long-press menu.
        val want = listOf(
            "map.hillshade", "map.intensity", "map.slope", "map.slopeLegend", "map.contours", "map.contourLabels",
            "map.layers", "map.night", "map.scaleBar",
            "areas.offline", "areas.corridor",
            "settings.sources", "direct.mobileData", "direct.sessionLimit", "settings.units", "settings.textSize",
            "settings.routeSnap", "settings.navStale", "settings.navSnooze", "settings.syncStatus", "settings.share",
            "settings.warmup",
            "tracks.bar", "longPress.menu",
        )
        assertEquals(want.sorted(), keys.sorted())
        assertEquals(setOf("MapOptionsSection.kt"), references["map.slope"])
        assertEquals(setOf("LongPressMenu.kt"), references["longPress.menu"])
        assertEquals(setOf("DirectSourcesRows.kt"), references["direct.mobileData"])
    }

    @Test
    fun eachTextIsOneShortParagraph() {
        for (u in ContourUnits.entries) for (h in HelpRegistry.all) {
            val t = h.text(u)
            assertTrue(h.title.isNotBlank() && h.placed.isNotBlank(), "${h.key}: title and placement")
            assertFalse('\n' in t, "${h.key}: one paragraph")
            assertTrue(t.length in 60..330, "${h.key}: ${t.length} characters (one short paragraph)")
            assertTrue(t.trimEnd().endsWith("."), "${h.key}: ends with a full stop")
            assertFalse("  " in t, "${h.key}: a double space")
        }
    }

    @Test
    fun noOtherProductNames() {
        // The names tools/user-text-check.py checks (CLAUDE.md > Rules); that script also scans these literals.
        val names = File("../tools/user-text-names.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(names.size > 10)
        for (u in ContourUnits.entries) for (h in HelpRegistry.all) for (n in names) {
            val rx = Regex("(?<![A-Za-z0-9])" + n.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
            assertFalse(rx.containsMatchIn(h.title + " " + h.text(u)), "${h.key} names $n")
        }
    }

    @Test
    fun distancesFollowTheUnitsSetting() {
        // One text in both units: the corridor's buffer, 500 m (AreaOfInterest.DEFAULT_BUFFER_M) through UnitFormat (1640 ft reads in miles).
        val corridor = HelpRegistry.byKey("areas.corridor")!!
        assertTrue("0.3 mi each side" in corridor.text(ContourUnits.FEET), corridor.text(ContourUnits.FEET))
        assertTrue("500 m each side" in corridor.text(ContourUnits.METERS), corridor.text(ContourUnits.METERS))
        assertTrue("miles" in HelpRegistry.byKey("settings.units")!!.text(ContourUnits.FEET))
        assertTrue("kilometres" in HelpRegistry.byKey("settings.units")!!.text(ContourUnits.METERS))
        // And no text shows a number in the other system's units.
        val metric = Regex("""\d\s?(m|km)\b""")
        val imperial = Regex("""\d\s?(ft|mi)\b""")
        for (h in HelpRegistry.all) {
            assertFalse(metric.containsMatchIn(h.text(ContourUnits.FEET)), "${h.key} in feet: ${h.text(ContourUnits.FEET)}")
            assertFalse(imperial.containsMatchIn(h.text(ContourUnits.METERS)), "${h.key} in metres: ${h.text(ContourUnits.METERS)}")
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun getHelpListsEveryKeyWithTextAndPlace() {
        val body = MiniJson.parseObject(JsonWriter.write(HelpRegistry.json(ContourUnits.METERS)))
        val help = body["help"] as List<Map<String, Any?>>
        assertEquals(keys, help.map { it["key"] })
        assertEquals(keys.size.toDouble(), (body["count"] as Number).toDouble())
        assertEquals("meters", body["units"])
        for (h in help) {
            assertTrue((h["text"] as String).isNotBlank() && (h["placed"] as String).isNotBlank() && (h["title"] as String).isNotBlank(), "$h")
            assertEquals(false, h["onScreen"], "nothing is composed in a unit test")
        }
        assertEquals("hillshade on", help.first { it["key"] == "map.intensity" }["needs"])
    }

    @Test
    fun openAndClose() {
        HelpUi.open("map.slope")
        assertEquals("map.slope", HelpUi.openKey)
        HelpUi.close()
        assertEquals(null, HelpUi.openKey)
        assertEquals(null, HelpUi.popup)
    }
}
