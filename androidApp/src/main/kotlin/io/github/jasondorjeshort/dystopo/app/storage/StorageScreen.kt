package io.github.jasondorjeshort.dystopo.app.storage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.cachebudget.BudgetPlan
import io.github.jasondorjeshort.dystopo.app.firstrun.Screens
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.packs.PacksUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/** Whether the Storage screen is open (Settings, or `POST /storage/ui?open=1` in debug builds). */
object StorageUi {
    var visible by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    /** Bumped after a change, so the screen reads the numbers again at once. */
    var revision by mutableStateOf(0)
    /** T112: the custom budget row (a size, or a share of the free space) is open. */
    var custom by mutableStateOf(false)
}

/**
 * T74: the Storage screen: what the app keeps on disk per store ([Storage]: packs, online tiles, contours and
 * shading, source data), each with what it uses and Clear. T112: the one cache budget first (presets, a custom size,
 * or a share of the free space picked once), with each budgeted kind against its floor and its share of the total;
 * the pinned areas keep their own budget chips. Numbers refresh every two seconds while it is open.
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
                val budget = i["budget"] as? Map<String, Any?>
                if (budget != null) BudgetSection(budget, stores, (i["freeBytes"] as? Number)?.toLong() ?: 0L, ::act)
                @Suppress("UNCHECKED_CAST")
                val choices = (i["budgetChoicesMb"] as List<Long>)
                val totalBytes = ((budget?.get("totalMb") as? Number)?.toLong() ?: 0L) * BudgetPlan.MB
                for ((key, s) in stores) StoreRow(key, s, choices, totalBytes, (i["freeBytes"] as? Number)?.toLong() ?: 0L, ::act)
            }
            StorageUi.message?.let { BasicText(it, style = TextStyle(color = Accent, fontSize = 12.sp)) }
        }
    }
}

/** T112: the one cache budget: the total and what each kind takes of it, the presets and the custom size. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BudgetSection(b: Map<String, Any?>, stores: Map<String, Map<String, Any?>>, freeBytes: Long, act: (String, () -> String) -> Unit) {
    val totalMb = (b["totalMb"] as? Number)?.toLong() ?: return
    val total = totalMb * BudgetPlan.MB
    val kinds = budgetKinds(stores)
    val used = kinds.sumOf { it.second }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Cache budget", Modifier.weight(1f, fill = false), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                HelpIcon("storage.budget") // T115 (T109's follow-up)
            }
            BasicText("${mb(used)} of ${size(totalMb)}", style = TextStyle(color = Ink, fontSize = 13.sp))
        }
        // One bar for the total: each kind's part in its colour, then what is left.
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Line)) {
            var shown = 0f
            for ((key, bytes) in kinds) {
                val f = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f - shown) else 0f
                if (f > 0.001f) {
                    Box(Modifier.weight(f).fillMaxHeight().background(kindColor(key)))
                    shown += f
                }
            }
            if (shown < 0.999f) Spacer(Modifier.weight(1f - shown))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((key, bytes) in kinds) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(10.dp).height(10.dp).background(kindColor(key), RoundedCornerShape(2.dp)))
                    BasicText(" ${stores[key]?.get("label")} ${mb(bytes)}", style = TextStyle(color = Muted, fontSize = 11.sp))
                }
            }
        }
        BasicText(
            "Online tiles, contours and shading, and source data share it. When it is full, contours and shading go first " +
                "(the phone can make them again), then the tiles used longest ago; never what is on the map now or on the " +
                "route ahead, and no kind below its floor. Offline areas and packs are not part of it.",
            style = TextStyle(color = Muted, fontSize = 11.sp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val preset = b["preset"] as? String
            for (p in BudgetPlan.Preset.entries) {
                Chip("${p.label} ${size(p.mb)}", preset == p.key) {
                    act("Setting the budget") { setBudget(mapOf("preset" to p.key), "${p.label}: ${size(p.mb)}") }
                }
            }
            Chip(if (preset == null) "Custom ${size(totalMb)}" else "Custom", preset == null) { StorageUi.custom = !StorageUi.custom }
        }
        if (StorageUi.custom) CustomBudget(totalMb, freeBytes, used, act)
        if (total > freeBytes + used) {
            BasicText(
                "This is more than the free space (${mb(freeBytes)}): the phone fills up before the budget does.",
                style = TextStyle(color = Older, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** T112: a size typed in GB, or a share of the free space turned into a fixed size once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomBudget(totalMb: Long, freeBytes: Long, usedBytes: Long, act: (String, () -> String) -> Unit) {
    var text by remember { mutableStateOf(gbText(totalMb)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("Size in GB", Modifier.align(Alignment.CenterVertically), style = TextStyle(color = Ink, fontSize = 13.sp))
            BasicTextField(
                text, { text = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.widthIn(min = 72.dp, max = 140.dp).border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Chip("Set", false) {
                val mb = text.toDoubleOrNull()?.let { (it * 1024).roundToLong() }
                act("Setting the budget") {
                    if (mb == null || mb !in BudgetPlan.RANGE_MB) "Pick a size from ${size(BudgetPlan.RANGE_MB.first)} to ${size(BudgetPlan.RANGE_MB.last)}"
                    else setBudget(mapOf("budgetMb" to mb), "Budget: ${size(mb)}")
                }
            }
        }
        BasicText("Or a share of the free space, kept as a fixed size from now on:", style = TextStyle(color = Muted, fontSize = 12.sp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (pct in BudgetPlan.FREE_PERCENTS) {
                val mb = BudgetPlan.percentOfFreeMb(pct, freeBytes, usedBytes)
                Chip("$pct% · ${size(mb)}", false) {
                    text = gbText(mb)
                    act("Setting the budget") { setBudget(mapOf("budgetMb" to mb), "Budget: ${size(mb)} ($pct% of the free space)") }
                }
            }
        }
    }
}

private fun setBudget(params: Map<String, Any?>, done: String): String =
    Storage.setBudget(DystopoApp.instance, params) ?: run {
        val run = Storage.lastBudgetRun
        val freed = (run?.get("freedByKind") as? Map<*, *>)?.values?.sumOf { (it as Number).toLong() } ?: 0L
        if (freed > 0) "$done; ${mb(freed)} freed" else done
    }

/** The budgeted kinds in the order of the rows, with their bytes. */
private fun budgetKinds(stores: Map<String, Map<String, Any?>>): List<Pair<String, Long>> =
    stores.filter { it.value["inBudget"] == true }.map { (k, s) -> k to ((s["bytes"] as? Number)?.toLong() ?: 0L) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreRow(key: String, s: Map<String, Any?>, choices: List<Long>, totalBytes: Long, freeBytes: Long, act: (String, () -> String) -> Unit) {
    val bytes = (s["bytes"] as? Number)?.toLong() ?: 0L
    val inBudget = s["inBudget"] == true
    val budget = (s["budgetMb"] as? Number)?.toLong()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (inBudget) {
                Box(Modifier.width(10.dp).height(10.dp).background(kindColor(key), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(6.dp))
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BasicText(s["label"] as String, Modifier.weight(1f, fill = false), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                StoreHelp(key) // T115 (T109's follow-up)
            }
            BasicText(
                when {
                    inBudget -> mb(bytes) // T112: the share goes under the bar (it squeezed the label at font scale 2.0)
                    budget != null -> "${mb(bytes)} of ${size(budget)}"
                    else -> mb(bytes)
                },
                style = TextStyle(color = Ink, fontSize = 13.sp),
            )
        }
        if (inBudget && totalBytes > 0) {
            // T112: the kind against the whole budget, with its floor marked.
            val share = (bytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            val floor = ((s["floorBytes"] as? Number)?.toDouble() ?: 0.0) / totalBytes
            var widthPx by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Line).onSizeChanged { widthPx = it.width }) {
                if (share > 0f) Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(kindColor(key)))
                Box(Modifier.offset(x = with(density) { (widthPx * floor).toFloat().toDp() }).width(2.dp).fillMaxHeight().background(Ink))
            }
            BasicText(
                "${pct(s["share"])} of the budget. Never cut below its floor of ${mb(s["floorBytes"])} (the mark) to make room for the others.",
                style = TextStyle(color = Muted, fontSize = 11.sp),
            )
        } else if (budget != null) {
            val share = (bytes.toDouble() / (budget * 1024.0 * 1024.0)).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.fillMaxWidth().height(6.dp).background(Line, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(share).height(6.dp).background(Accent, RoundedCornerShape(3.dp)))
            }
        }
        holdsLine(key, s)?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 11.sp)) }
        olderLine(s)?.let { BasicText(it, style = TextStyle(color = Older, fontSize = 12.sp, fontWeight = FontWeight.Bold)) } // T95
        // T115: the pinned budget now goes up to 1 TB, so say so when it is past what the phone has free, as the cache budget does.
        if (key == Storage.PINNED && budget != null && budget * BudgetPlan.MB > freeBytes + bytes) {
            BasicText(
                "This is more than the free space (${mb(freeBytes)}): the phone fills up before the budget does.",
                style = TextStyle(color = Older, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
        }
        // T91: the chips wrap (a scrolling row clipped the 2 GB chip at the right edge).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Clear first, so it is on screen whatever the budget chips take.
            if (s["clearable"] == true) Chip("Clear", false) {
                act("Clearing") {
                    val r = Storage.clear(DystopoApp.instance, key)
                    "Cleared ${s["label"]}: ${mb(r["bytes"])}"
                }
            }
            // T112: only the pinned areas keep budget chips of their own; the other caches share the budget above.
            val setting = s["setting"] as? String
            if (!inBudget && setting != null && budget != null) {
                for (c in (choices + budget).distinct().sorted()) {
                    Chip(size(c), c == budget) {
                        act("Setting the budget") {
                            Storage.setBudgets(DystopoApp.instance, mapOf(setting to c)) ?: "Budget for ${s["label"]}: ${size(c)}"
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

/** T115: the help icon of a store's row, by the key of its text in [STORAGE_HELP] (one literal key per row, as `HelpRegistryTest` reads them). */
@Composable
private fun StoreHelp(key: String) {
    when (key) {
        Storage.PACKS -> HelpIcon("storage.packs")
        Storage.ONLINE -> HelpIcon("storage.online")
        Storage.PINNED -> HelpIcon("storage.pinned")
        Storage.DERIVED -> HelpIcon("storage.derived")
        Storage.SOURCES -> HelpIcon("storage.sources")
    }
}

/**
 * T115 (T109's and T112's follow-up): the help pop-ups of the Storage screen, by the key each [HelpIcon] names: the cache
 * budget's heading and each store's row; a row removed here takes its text with it. `POST /help/open` opens the screen.
 */
internal val STORAGE_HELP: List<HelpText> = run {
    val show = { StorageUi.visible = true }
    fun at(row: String) = "${Screens.SETTINGS} › ${Screens.DATA_AND_STORAGE} › ${Screens.STORAGE} › $row"
    listOf(
        HelpText("storage.budget", "Cache budget", at("Cache budget"), show = show) {
            "One size limit for online tiles, contours and shading, and source data together. When they pass it, " +
                "contours and shading go first (the phone can make them again), then what was used longest ago; never " +
                "what is on the map or on the route ahead. A larger budget keeps more for use with no signal."
        },
        HelpText("storage.packs", "Packs", at("Packs"), show = show) {
            "Region packs are maps you downloaded or imported whole: terrain, map data, trails for routes and places " +
                "for search. They are never cleared to make room; remove one in the pack list."
        },
        HelpText("storage.online", "Online tiles", at("Online tiles"), show = show) {
            "Map, terrain and label tiles downloaded while you looked at the map, from your server or the public " +
                "sources. A place you visited shows again with no connection until the cache budget needs the room. " +
                "Clear empties them; the map downloads them again when needed."
        },
        HelpText("storage.pinned", "Offline areas (pinned)", at("Offline areas (pinned)"), show = show) {
            "The tiles of the areas you pinned in Offline areas, kept until you unpin or remove the area. They have " +
                "their own budget, outside the cache budget: a download that would pass it is refused. Raise it here " +
                "before you pin a long trip."
        },
        HelpText("storage.derived", "Contours and shading", at("Contours and shading"), show = show) {
            "Contour lines and slope and relief shading the phone made from elevation data. They go first when the " +
                "cache budget is full, since the phone can make them again; that takes a moment and some battery."
        },
        HelpText("storage.sources", "Source data (no server)", at("Source data (no server)"), show = show) {
            "Elevation data this phone read from the public sources itself, with no server set, to draw terrain and " +
                "contours. It is kept so the same places draw faster, and cleared like the other caches when the " +
                "budget needs the room."
        },
    )
}

/** One line on what a store holds (pack count, the kinds of tiles). */
private fun holdsLine(key: String, s: Map<String, Any?>): String? {
    if (key == Storage.PACKS) return "${s["count"]} installed; remove one in the pack list"
    if (key == Storage.SOURCES) return "Elevation data read by this phone when no server is set"
    @Suppress("UNCHECKED_CAST")
    val holds = s["holds"] as? Map<String, Map<String, Any?>>
    val kinds = holds?.entries?.joinToString(", ") { (k, v) -> "$k ${mb(v["bytes"])}" }
    // T95: what the pinned tiles are, as for the online row (which counts its unpinned tiles only).
    if (key == Storage.PINNED) return "${s["areas"]} pinned" + (kinds?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") + "; outside the cache budget, kept until unpinned"
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

/** A budget in MB as the chips say it ([Storage.sizeLabel]). */
private fun size(mb: Long): String = Storage.sizeLabel(mb)

private fun gbText(mb: Long): String = if (mb % 1024 == 0L) "${mb / 1024}" else String.format(Locale.US, "%.2f", mb / 1024.0).trimEnd('0').trimEnd('.')

private fun pct(v: Any?): String {
    val f = (v as? Number)?.toDouble() ?: return "?"
    return if (f > 0 && f < 0.01) "<1%" else "${(f * 100).roundToLong()}%"
}

private fun kindColor(key: String): Color = when (key) {
    Storage.ONLINE -> Accent
    Storage.DERIVED -> Color(0xFF4F7A3A)
    else -> Color(0xFF3A6A8A)
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
