package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.controls.ControlsDocument
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.server.LiveTiles
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T208 (ADR 0053 §2): the committed controls document (`tools/demo/controls.json`) is what the registry in `shared`
 * (`controls/`) writes. A registry edit without `./gradlew updateGoldens` fails here.
 */
class ControlsDocumentTest {
    @Test
    fun theCommittedDocumentIsTheRegistrys() {
        val file = File("../${ControlsDocument.FILE}") // tests run in devtool/
        val want = ControlsDocument.text()
        val have = if (file.isFile) file.readText().replace("\r\n", "\n") else null
        if (have == want) return
        val diff = have?.let { firstDifference(it, want) } ?: "the file is missing"
        fail(
            "${ControlsDocument.FILE} differs from the controls registry (shared/src/commonMain/kotlin/.../controls/): $diff. " +
                "Run ./gradlew updateGoldens (it runs devtool controls), then view the diff (git diff ${ControlsDocument.FILE}) before committing.",
        )
    }

    @Test
    fun theLivePageCarriesTheSameDocument() {
        val cache = Files.createTempDirectory("t208-controls").toFile()
        try {
            val live = LiveTiles(emptyList(), emptyList(), cacheRoot = cache, threads = 1)
            val page = live.injectLive(File("../tools/demo/index.html").readText())
            val line = assertNotNull(page.lineSequence().firstOrNull { it.startsWith("window.DYSTOPO_CONTROLS = ") }, "injectLive inlines no window.DYSTOPO_CONTROLS")
            val inlined = MiniJson.parseObject(line.removePrefix("window.DYSTOPO_CONTROLS = ").substringBefore(";</script>"))
            assertEquals(MiniJson.parseObject(File("../${ControlsDocument.FILE}").readText()), inlined)
            assertTrue(page.indexOf("window.DYSTOPO_CONTROLS") < page.indexOf("<script type=\"module\">"), "ahead of the page's module script")
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun firstDifference(have: String, want: String): String {
        val a = have.split('\n')
        val b = want.split('\n')
        val i = (0 until maxOf(a.size, b.size)).first { a.getOrNull(it) != b.getOrNull(it) }
        return "line ${i + 1} is ${a.getOrNull(i)?.trim()?.let { "'$it'" } ?: "absent"}, the registry writes ${b.getOrNull(i)?.trim()?.let { "'$it'" } ?: "nothing"}"
    }
}
