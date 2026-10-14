package io.github.jasondorjeshort.dystopo.app.storage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.packs.PacksUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread

/** Whether the Storage screen is open (Settings, or `POST /storage/ui?open=1` in debug builds). */
object StorageUi {
    var visible by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    /** Bumped after a change, so the screen reads the numbers again at once. */
    var revision by mutableStateOf(0)
}

/**
 * T74: the Storage screen: what the app keeps on disk per store ([Storage]: packs, online tiles, contours and
 * shading, source data), each with what it uses against its budget, budget choices (the `onlineCacheMb` and
 * `contourCacheMb` settings) and Clear. Numbers refresh every two seconds while it is open.
 */
@Composable
fun StorageScreen() {
    if (!StorageUi.visible) return
    BackHandler { StorageUi.visible = false }
    var info by remember { mutableStateOf<Map<String, Any?>?>(null) }
    LaunchedEffect(StorageUi.revision) {
        while (true) {
            info = withContext(Dispatchers.IO) { runCatching { Storage.json(DystopoApp.instance) }.getOrNull() }
            delay(2_000)
        }
    }

    fun act(label: String, block: () -> String) {
        if (StorageUi.busy) return
        StorageUi.busy = true
        StorageUi.message = "$label..."
        thread(name = "dystopo-storage-ui") {
            try {
                StorageUi.message = block()
            } catch (e: Exception) {
                StorageUi.message = e.message ?: e.javaClass.simpleName
            } finally {
                StorageUi.busy = false
                StorageUi.revision++
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp)
                .heightIn(max = 640.dp)
                .background(Paper, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Storage", Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                Chip("Close", false) { StorageUi.visible = false }
            }
            val i = info
            if (i == null) {
                BasicText("Measuring...", style = TextStyle(color = Muted, fontSize = 13.sp))
            } else {
                BasicText(
                    "Using ${mb(i["totalBytes"])} in all; ${mb(i["freeBytes"])} free on the device.",
                    style = TextStyle(color = Muted, fontSize = 12.sp),
                )
                @Suppress("UNCHECKED_CAST")
                val stores = i["stores"] as Map<String, Map<String, Any?>>
                @Suppress("UNCHECKED_CAST")
                val choices = (i["budgetChoicesMb"] as List<Long>)
                for ((key, s) in stores) StoreRow(key, s, choices, ::act)
            }
            StorageUi.message?.let { BasicText(it, style = TextStyle(color = Accent, fontSize = 12.sp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreRow(key: String, s: Map<String, Any?>, choices: List<Long>, act: (String, () -> String) -> Unit) {
    val bytes = (s["bytes"] as? Number)?.toLong() ?: 0L
    val budget = (s["budgetMb"] as? Number)?.toLong()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(s["label"] as String, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
            BasicText(
                if (budget != null) "${mb(bytes)} of ${budget} MB" else mb(bytes),
                style = TextStyle(color = Ink, fontSize = 13.sp),
            )
        }
        if (budget != null) {
            val share = (bytes.toDouble() / (budget * 1024.0 * 1024.0)).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.fillMaxWidth().height(6.dp).background(Line, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(share).height(6.dp).background(Accent, RoundedCornerShape(3.dp)))
            }
        }
        holdsLine(key, s)?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 11.sp)) }
        olderLine(s)?.let { BasicText(it, style = TextStyle(color = Older, fontSize = 12.sp, fontWeight = FontWeight.Bold)) } // T95
        // T91: the chips wrap (a scrolling row clipped the 2 GB chip at the right edge).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Clear first, so it is on screen whatever the budget chips take.
            if (s["clearable"] == true) Chip("Clear", false) {
                act("Clearing") {
                    val r = Storage.clear(DystopoApp.instance, key)
                    "Cleared ${s["label"]}: ${mb(r["bytes"])}"
                }
            }
            val setting = s["setting"] as? String
            if (setting != null && budget != null) {
                for (c in (choices + budget).distinct().sorted()) {
                    Chip(if (c >= 1024 && c % 1024 == 0L) "${c / 1024} GB" else "$c MB", c == budget) {
                        act("Setting the budget") {
                            Storage.setBudgets(DystopoApp.instance, mapOf(setting to c)) ?: "Budget for ${s["label"]}: ${c} MB"
                        }
                    }
                }
            }
            if (key == Storage.PACKS) Chip("Packs ›", false) {
                StorageUi.visible = false
                PacksUi.visible = true
            }
            if (key == Storage.PINNED) Chip("Offline areas ›", false) {
                StorageUi.visible = false
                io.github.jasondorjeshort.dystopo.app.areas.Areas.screenOpen = true
            }
        }
    }
}

/** One line on what a store holds (pack count, the kinds of tiles). */
private fun holdsLine(key: String, s: Map<String, Any?>): String? {
    if (key == Storage.PACKS) return "${s["count"]} installed; remove one in the pack list"
    if (key == Storage.SOURCES) return "Elevation data read by this phone when no server is set"
    @Suppress("UNCHECKED_CAST")
    val holds = s["holds"] as? Map<String, Map<String, Any?>>
    val kinds = holds?.entries?.joinToString(", ") { (k, v) -> "$k ${mb(v["bytes"])}" }
    // T95: what the pinned tiles are, as for the online row (which counts its unpinned tiles only).
    if (key == Storage.PINNED) return "${s["areas"]} pinned" + (kinds?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") + "; kept out of Online tiles' budget until unpinned"
    if (holds == null) return null
    if (holds.isEmpty()) return "Empty"
    return kinds
}

/** T95: the pinned areas whose source has moved on to newer data, by name. */
private fun olderLine(s: Map<String, Any?>): String? {
    val names = (s["olderThanSource"] as? List<*>)?.map { it.toString() }?.takeIf { it.isNotEmpty() } ?: return null
    return "Older than the source: ${names.joinToString(", ")}. Update in Offline areas."
}

private fun mb(v: Any?): String {
    val b = (v as? Number)?.toDouble() ?: return "?"
    val m = b / (1024 * 1024)
    return when {
        m >= 1024 -> String.format(Locale.US, "%.1f GB", m / 1024)
        m >= 10 -> String.format(Locale.US, "%.0f MB", m)
        else -> String.format(Locale.US, "%.1f MB", m)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        softWrap = false,
        style = TextStyle(color = if (selected) Paper else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(if (selected) Accent else Color(0xEEFFFCF5), RoundedCornerShape(16.dp))
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
private val Older = Color(0xFF9A5B00)
