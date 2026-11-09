package io.github.jasondorjeshort.dystopo.app.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.settings.LinkBlue
import java.util.Locale
import kotlin.concurrent.thread

/**
 * T83: the Updates row of the settings sheet: this build's version, "Check for updates" (a manual check, which
 * also shows a version declined earlier), what the last check found, and Install when a verified update waits.
 * T106: the two buttons wrap (a row that did not wrap could push Install off the sheet at font scale 2.0).
 * T110: Install while navigating waits for the session to end (installing replaces the running app); the row then says
 * so and offers "Install now".
 * T223: the version line is short ("This build: 0.1.19-alpha", "Up to date · 0.1.19-alpha" after a check, in the same
 * place); a tap on it or on Details shows the full build id, the version code and the newest release.
 * T247: a failed check says one sentence of its kind ([UpdateFailure.sentence]); the kind, the status code and the raw
 * text fold under "Technical details" (Show / Hide, the Diagnostics row's pattern), collapsed whenever the row opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateRow() {
    if (!Updater.initialized()) Updater.start(DystopoApp.instance)
    val s = Updater.state
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("Updates", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        var details by remember { mutableStateOf(false) }
        val upToDate = s.message == UpdateText.upToDate(Updater.ownName) // not "No release with an APK", the same phase
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(
                if (upToDate) UpdateText.upToDate(Updater.ownName) else UpdateText.thisBuild(Updater.ownName),
                Modifier.weight(1f, fill = false).clickable { details = !details }.padding(vertical = 4.dp),
                style = TextStyle(color = if (upToDate) Accent else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            BasicText(
                if (details) "Hide" else "Details",
                Modifier.clickable { details = !details }.padding(vertical = 4.dp),
                style = TextStyle(color = LinkBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
        }
        if (details) BasicText(UpdateText.fullBuild(Updater.ownName, Updater.ownCode, s.tag), style = TextStyle(color = Ink, fontSize = 12.sp))
        BasicText("New alphas come from the GitHub releases page; checked daily on any network.", style = TextStyle(color = Muted, fontSize = 11.sp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val busy = s.phase == Updater.Phase.CHECKING || s.phase == Updater.Phase.DOWNLOADING
            Button(if (busy) "Checking..." else "Check for updates", false) { if (!busy) thread(name = "dystopo-update-check") { Updater.check(manual = true) } }
            if (Updater.readyFile != null && (s.phase == Updater.Phase.READY || s.phase == Updater.Phase.DECLINED)) {
                if (Updater.waitingForNavigation) Button("Install now", true) { thread(name = "dystopo-update-install") { Updater.install(now = true) } }
                else Button("Install ${s.versionName ?: ""}".trim(), true) { thread(name = "dystopo-update-install") { Updater.install() } }
            }
        }
        val line = when (s.phase) {
            Updater.Phase.IDLE -> null
            Updater.Phase.DOWNLOADING -> s.message + if (s.totalBytes > 0) String.format(Locale.US, ": %.1f of %.1f MB", s.bytes / 1048576.0, s.totalBytes / 1048576.0) else ""
            else -> s.message.takeUnless { upToDate } // T223: the line above says it
        }
        line?.let {
            val bad = s.phase == Updater.Phase.REFUSED || s.phase == Updater.Phase.FAILED || it.startsWith("Install failed")
            BasicText(it, style = TextStyle(color = if (bad) Red else Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
        val failure = s.failure.takeIf { s.phase == Updater.Phase.FAILED }
        DisposableEffect(Unit) { onDispose { UpdateUi.foldOpen = false } } // collapsed again the next time the row opens
        if (failure != null) {
            val open = UpdateUi.foldOpen
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText("Technical details", Modifier.clickable { UpdateUi.foldOpen = !open }.padding(vertical = 4.dp),
                    style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                BasicText(if (open) "Hide" else "Show", Modifier.clickable { UpdateUi.foldOpen = !open }.padding(vertical = 4.dp),
                    style = TextStyle(color = LinkBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
            if (open) for (l in failure.technical(s.attempts)) BasicText(l, style = TextStyle(color = Muted, fontSize = 12.sp))
        }
    }
}

/** T247: whether the failed check's "Technical details" fold is open (`GET /update` `technicalDetailsOpen`, `POST /update/fold`). Main thread. */
object UpdateUi {
    var foldOpen by mutableStateOf(false)
}

/** T223: the Updates row's version text (pure, unit-tested). `GET /update`'s `message` is what the row shows. */
internal object UpdateText {
    /** What `git describe` adds to a build past its tag: "-81-g0b74b66f-sweep". */
    private val PAST_THE_TAG = Regex("-\\d+-g[0-9a-f]+.*$")

    /** "0.1.18-alpha-81-g0b74b66f-sweep" reads "0.1.18-alpha"; a release's name is already short. */
    fun shortVersion(name: String): String = name.replace(PAST_THE_TAG, "")

    fun thisBuild(name: String): String = "This build: ${shortVersion(name)}"

    fun upToDate(name: String): String = "Up to date · ${shortVersion(name)}"

    /** Behind the tap: the whole build id and code, and the newest release when a check has found one. */
    fun fullBuild(name: String, code: Long, newest: String?): String =
        "Full build: $name ($code)" + if (newest != null) "; newest release $newest" else ""
}
