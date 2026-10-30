package io.github.jasondorjeshort.dystopo.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T143: the Diagnostics row of Settings › About and advanced, under Updates, on every build: the numbers of
 * [Diagnostics.nowLines] and, under "Last session", [Diagnostics.lastSessionLines], read on a worker thread when the
 * row opens and on Refresh (never polled), and Copy report ([copyReport]: [Diagnostics.report] as plain text; Android 13 and
 * up confirm a copy themselves, so the toast is shown only below). [DiagnosticsUi.shown] is the snapshot on screen, what
 * `GET /diagnostics?shown=1` and `POST /diagnostics/copy` read. T172: under the memory, the live or last track's numbers
 * ([Diagnostics.trackLines]: points marked, gaps by reason, the watchdog's restarts while recording).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsRow() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var snap by remember { mutableStateOf(DiagnosticsUi.shown) }
    LaunchedEffect(refresh) {
        snap = withContext(Dispatchers.Default) { Diagnostics.read(context.applicationContext) }.also { DiagnosticsUi.shown = it }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("Diagnostics", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        BasicText("How much memory the app uses and how the last recording went, read when this page opens. Nothing leaves the phone unless you copy it.", style = TextStyle(color = Muted, fontSize = 11.sp))
        val s = snap
        if (s == null) BasicText("Reading…", style = TextStyle(color = Muted, fontSize = 13.sp))
        else {
            for (l in Diagnostics.nowLines(s)) Value(l)
            BasicText(Diagnostics.LAST_SESSION, Modifier.padding(top = 6.dp), style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            for (l in Diagnostics.lastSessionLines(s)) Value(l)
            BasicText(Diagnostics.trackHeading(s), Modifier.padding(top = 6.dp), style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) // T172
            for (l in Diagnostics.trackLines(s)) Value(l)
        }
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Option("Refresh", false) { refresh++ }
            Option("Copy report", false) { snap?.let { copyReport(context, it) } }
        }
    }
}

@Composable
private fun Value(l: Diagnostics.Line) {
    BasicText(l.toString(), style = TextStyle(color = Ink, fontSize = 13.sp))
}

/** Copy report: [Diagnostics.report] of [s] on the clipboard as plain text (no sensitive flag); a toast below Android 13. Main thread. */
fun copyReport(context: Context, s: Diagnostics.Snapshot): String {
    val text = Diagnostics.report(s)
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Dystopo diagnostics", text))
    DiagnosticsUi.copied = text
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    return text
}

/** T143: the row's state that the debug routes read. */
object DiagnosticsUi {
    /** The snapshot the row shows (the last it read), or null before the row first opened. */
    @Volatile var shown: Diagnostics.Snapshot? = null

    /** The text Copy report last put on the clipboard. */
    @Volatile var copied: String? = null
}
