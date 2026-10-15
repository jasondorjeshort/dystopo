package io.github.jasondorjeshort.dystopo.app.firstrun

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T79: every user-facing string that sends the user to a screen names one in [Screens], and every screen
 * there has a chip that opens it. The alpha told a fresh install "No server set (Settings)."; Settings had no
 * server field. This test cannot read a screen's fields, but it keeps the names honest: a message may only
 * name screens (and `Screen › Section` pairs) listed in one place, and that list is tied to the chips.
 */
class ScreenNamesTest {
    private val sources: List<File> = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** String literals on code lines (not comments), with `${Screens.X}` templates and `›` resolved. */
    private fun literals(file: File): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        file.readLines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
            val code = raw.substringBefore(" // ")
            for (m in literal.findAll(code)) out += (i + 1) to resolve(m.groupValues[1])
        }
        return out
    }

    private fun resolve(s: String): String {
        var t = s.replace("\\u203a", "›")
        for ((name, value) in mapOf("SETTINGS" to Screens.SETTINGS, "PACKS" to Screens.PACKS, "TRACKS" to Screens.TRACKS, "ROUTES" to Screens.ROUTES, "SHARE" to Screens.SHARE, "SOURCES" to Screens.SOURCES)) {
            t = t.replace("\${Screens.$name}", value)
        }
        return t
    }

    @Test
    fun messagesNameOnlyKnownScreens() {
        val problems = ArrayList<String>()
        var references = 0
        for (f in sources) for ((line, text) in literals(f)) {
            for (ref in ScreenRefs.find(text)) {
                references++
                ScreenRefs.problem(ref)?.let { problems += "${f.name}:$line \"$text\": $it" }
            }
        }
        assertEquals(emptyList(), problems, "messages naming screens that are not in Screens.ALL")
        assertTrue(references >= 4, "expected the first-run and pack-list messages to be found (found $references references)")
    }

    @Test
    fun everyScreenHasAChip() {
        val all = sources.joinToString("\n") { it.readText() }
        for (screen in Screens.ALL.keys) {
            val const = Screens::class.java.declaredFields.firstOrNull { f ->
                f.type == String::class.java && runCatching { f.isAccessible = true; f.get(null) }.getOrNull() == screen
            }?.name
            // A chip, or a clickable text link (the Share screen's "Share" in the tracks list), with the screen's label.
            val opener = Regex("""(?:Chip|BasicText)\(\s*(?:"${Regex.escape(screen)}"|Screens\.$const)\s*[,)]""")
            assertTrue(opener.containsMatchIn(all), "no Chip(\"$screen\") or text link with that label opens the $screen screen")
        }
        val settings = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app/settings/SettingsPages.kt").readText() // T103: the sheet's rows
        assertTrue("BasicText(Screens.SOURCES" in settings, "the settings sheet has no Sources heading")
        val packs = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app/packs/PacksScreen.kt").readText()
        assertTrue("\"From server\"" in packs, "the pack list has no From server section")
    }

    @Test
    fun theCheckerCatchesUnknownScreens() {
        assertEquals(listOf("Downloads"), ScreenRefs.find("Open the Downloads screen to see it.").map { it.screen })
        assertTrue(ScreenRefs.problem(ScreenRefs.find("Set it in Preferences.").single()) != null)
        assertTrue(ScreenRefs.problem(ScreenRefs.find("Set one in Settings › Servers").single()) != null)
        assertEquals(null, ScreenRefs.problem(ScreenRefs.find("Set one in Settings › Sources ›").single()))
        assertEquals(null, ScreenRefs.problem(ScreenRefs.find("No server set (Settings).").single()))
        assertEquals(emptyList(), ScreenRefs.find("a 404 from the server (HTTP 404) in Overpass mode"))
    }
}

/** Finds screen references in a message: `(Screen)`, `Screen › Section`, `in|from|open Screen.`, `the Screen screen|list|sheet`. */
object ScreenRefs {
    data class Ref(val screen: String, val section: String?)

    private const val WORD = "[A-Z][a-z]+"
    private val arrow = Regex("($WORD) › ([A-Z][^.,;:)›]*)")
    private val paren = Regex("\\(($WORD)\\)")
    private val preposition = Regex("\\b(?:in|from|open|Open|In|From) ($WORD)(?=[.,;:)]|$| ›)")
    private val noun = Regex("\\b(?:the|The) ($WORD) (?:screen|list|sheet)\\b")

    fun find(text: String): List<Ref> {
        val refs = LinkedHashMap<Int, Ref>() // by start offset, so one place is one reference
        for (m in arrow.findAll(text)) refs[m.range.first] = Ref(m.groupValues[1], m.groupValues[2].trim())
        for (r in listOf(paren, preposition, noun)) for (m in r.findAll(text)) {
            val g = m.groups[1]!!
            if (refs.keys.none { it == g.range.first }) refs[g.range.first] = Ref(g.value, null)
        }
        return refs.values.toList()
    }

    fun problem(ref: Ref): String? {
        val sections = Screens.ALL[ref.screen] ?: return "'${ref.screen}' is not a screen (Screens.ALL: ${Screens.ALL.keys})"
        // The section is the start of what follows the arrow ("Settings › Sources to download maps").
        if (ref.section != null && sections.none { ref.section == it || ref.section.startsWith("$it ") }) return "'${ref.screen} › ${ref.section}' is not a section (${ref.screen} has $sections)"
        return null
    }
}
