package io.github.jasondorjeshort.dystopo.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import java.util.Locale
import kotlin.concurrent.thread

/**
 * T83: the Updates row of the settings sheet: this build's version, "Check for updates" (a manual check, which
 * also shows a version declined earlier), what the last check found, and Install when a verified update waits.
 * T106: the two buttons wrap (a row that did not wrap could push Install off the sheet at font scale 2.0).
 * T110: Install while navigating waits for the session to end (installing replaces the running app); the row then says
 * so and offers "Install now".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateRow() {
    if (!Updater.initialized()) Updater.start(DystopoApp.instance)
    val s = Updater.state
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("Updates", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        BasicText("This build: ${Updater.ownName} (${Updater.ownCode}). New alphas come from the GitHub releases page; checked daily on any network.", style = TextStyle(color = Muted, fontSize = 11.sp))
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
            else -> s.message
        }
        line?.let {
            val bad = s.phase == Updater.Phase.REFUSED || s.phase == Updater.Phase.FAILED || it.startsWith("Install failed")
            BasicText(it, style = TextStyle(color = if (bad) Red else Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }
}
