package io.github.jasondorjeshort.dystopo.app.reports

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.settings.Accent
import io.github.jasondorjeshort.dystopo.app.settings.Ink
import io.github.jasondorjeshort.dystopo.app.settings.Line
import io.github.jasondorjeshort.dystopo.app.settings.Muted
import io.github.jasondorjeshort.dystopo.app.settings.Option
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * T145: Settings › About and advanced › Report a problem, on every build. Lists the kept reports ([Reports], newest
 * first, read on a worker when the page opens and after a change); a tap shows one exactly as it would be sent
 * ([Report.shown], scrubbed), with the privacy notice above **Open GitHub issue**, which puts that text on the
 * clipboard and then opens the prefilled issue page ([IssueLink]) in the browser, and **Delete**. Nothing is sent by the
 * app: the tester reads the page, signs in and submits. No email option (the crash-report survey: it needs a mailbox and
 * sends unseen through a third-party mail app).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportRow() {
    val context = LocalContext.current
    val selected = ReportUi.selected
    LaunchedEffect(ReportUi.revision, selected) {
        val loaded = withContext(Dispatchers.IO) { ReportUi.load(selected) }
        ReportUi.items = loaded.first
        ReportUi.open = loaded.second
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("Report a problem", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        BasicText(INTRO, style = TextStyle(color = Muted, fontSize = 11.sp))
        val items = ReportUi.items
        when {
            items == null -> BasicText("Reading…", style = TextStyle(color = Muted, fontSize = 13.sp))
            items.isEmpty() -> BasicText(NONE, style = TextStyle(color = Ink, fontSize = 13.sp))
            else -> FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (it in items) Option(it.label, it.id == selected) {
                    ReportUi.message = null
                    ReportUi.selected = if (it.id == selected) null else it.id
                }
            }
        }
        val open = ReportUi.open?.takeIf { it.id == selected }
        if (open != null) {
            Box(
                Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = 320.dp)
                    .background(Color.White, RoundedCornerShape(6.dp)).border(1.dp, Line, RoundedCornerShape(6.dp))
                    .verticalScroll(rememberScrollState()).padding(8.dp),
            ) {
                BasicText(open.text, style = TextStyle(color = Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
            }
            BasicText(NOTICE, Modifier.padding(top = 6.dp), style = TextStyle(color = Ink, fontSize = 12.sp))
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Option(OPEN_ISSUE, false) { ReportUi.message = openIssue(context, open) }
                Option(DELETE, false) {
                    val id = open.id
                    ReportUi.selected = null
                    ReportUi.message = null
                    thread(name = "report-delete") { Reports.delete(id); ReportUi.changed() }
                }
            }
        }
        ReportUi.message?.let { BasicText(it, style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)) }
    }
}

/** Open GitHub issue (main thread): what the issue body lacks on the clipboard first (nothing when it is complete), then the link in the browser. Returns the line shown under the buttons. */
fun openIssue(context: Context, open: ReportUi.Open): String {
    val clip = open.link.clipboard
    ReportUi.copied = clip
    if (clip != null) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, clip))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) Toast.makeText(context, COPIED_TOAST, Toast.LENGTH_SHORT).show()
    }
    ReportUi.lastLink = open.link
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(open.link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        when {
            open.link.titleOnly -> AFTER_OPEN_TITLE_ONLY
            clip == null -> AFTER_OPEN_COMPLETE
            else -> AFTER_OPEN
        }
    } catch (_: ActivityNotFoundException) {
        NO_BROWSER
    }
}

/** T145: the row's state (Compose state, made and written on the main thread; the routes go through `onMain`). */
object ReportUi {
    /** One report in the list: its id, the chip's label and the line the route reports. */
    data class Item(val id: String, val label: String, val kind: String, val atMs: Long, val headline: String?)

    /** The report on show: its scrubbed text and the link made from it. */
    data class Open(val id: String, val text: String, val link: IssueLink.Link)

    var selected by mutableStateOf<String?>(null)
    var revision by mutableStateOf(0)
    var items by mutableStateOf<List<Item>?>(null)
    var open by mutableStateOf<Open?>(null)
    var message by mutableStateOf<String?>(null)

    /** The text Open GitHub issue last put on the clipboard, and the link it opened (for the routes). */
    @Volatile var copied: String? = null
    @Volatile var lastLink: IssueLink.Link? = null

    /** A report was written or deleted off the main thread: the row reads the list again. */
    fun changed() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { revision++ }
    }

    /** The list and the [selected] report's text and link (a worker thread: lists the folders [Reports.known] reads). */
    fun load(selected: String?): Pair<List<Item>, Open?> {
        val reports = Reports.all()
        val items = reports.map { Item(it.id, label(it), it.kind, it.atMs, it.headline) }
        val open = selected?.let { id -> reports.firstOrNull { it.id == id } }?.let { prepare(it) }
        return items to open
    }

    fun prepare(r: Report): Open {
        val known = Reports.known()
        return Open(r.id, r.shown(known), IssueLink.build(r, known))
    }

    fun label(r: Report): String = r.kindLabel + ", " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(r.atMs))
}

const val INTRO = "Crashes, freezes and closes of the app on this phone, the last five kept. Tap one to read it. Nothing leaves the phone unless you open an issue."
const val NONE = "No problems recorded."
const val NOTICE = "This report will be posted publicly on GitHub under your account. It contains the app version, your phone model, " +
    "your Android version and the error details. Locations, place names and pack names have been removed. It does not contain " +
    "your maps, tracks or searches. Read it before you submit; nothing is sent until you do."
const val OPEN_ISSUE = "Open GitHub issue"
const val DELETE = "Delete"
const val CLIP_LABEL = "Dystopo problem report"
const val COPIED_TOAST = "Report copied"
const val AFTER_OPEN = "The rest of the stack trace is on your clipboard; paste it below."
const val AFTER_OPEN_COMPLETE = "The issue is complete; press Submit."
const val AFTER_OPEN_TITLE_ONLY = "The report is too long for the link; it is on your clipboard: paste it into the issue."
const val NO_BROWSER = "No browser to open the issue in; the report is on your clipboard."
