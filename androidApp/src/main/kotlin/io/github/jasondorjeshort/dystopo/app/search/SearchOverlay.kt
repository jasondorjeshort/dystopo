package io.github.jasondorjeshort.dystopo.app.search

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.search.PackHit
import java.util.Locale
import kotlin.concurrent.thread

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xF2FFFCF5)
private val Line = Color(0xFFE2D8C6)
private val Accent = Color(0xFF7A4E2D)

/** Search box state (main thread; the debug routes change it through `onMain`). */
object SearchUi {
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<PackHit>>(emptyList())
    /** The result list is open (typing opens it, choosing a result closes it). */
    var open by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var selected by mutableStateOf<PackHit?>(null)
    /** T149: the text field has the focus (the box is open for typing). */
    var focused by mutableStateOf(false)

    /** T149: the indexes are wanted: the box is open (focused, or its result list open) or holds text. */
    val wanted: Boolean get() = focused || open || query.isNotBlank()

    /**
     * Runs the query against every pack's index (T41), centred on the camera. Main thread. T149: loads or builds the
     * indexes only while they are [wanted] (else it reads their states and starts nothing), so no index is built at
     * launch or after a download until the search box is used.
     */
    fun refresh() {
        val q = query.trim()
        val want = wanted
        val states = if (want) PlaceSearch.ensure() else PlaceSearch.peek()
        val building = states.filter { it.status == "building" }
        status = when {
            !want && states.none { it.busy } -> null // not asked for yet: nothing to say
            states.isEmpty() -> "No map data in these packs to search"
            building.isNotEmpty() -> "Indexing places" + building.sumOf { it.total }.let { t -> if (t > 0) " ${building.sumOf { it.done } * 100 / t}%" else "..." } +
                if (building.size > 1) " (${building.size} packs)" else ""
            states.all { it.status == "error" } -> "Search index failed: ${states.first().error}"
            states.none { it.index != null } && states.any { it.status == "refused" } -> SEARCH_NO_MEMORY // T149
            states.none { it.index != null } -> "Loading search index..."
            else -> null
        }
        if (q.isEmpty()) { hits = emptyList(); return }
        val target = DystopoApp.instance.map.map?.cameraPosition?.target
        hits = PlaceSearch.search(q, target?.latitude, target?.longitude, limit = 8).orEmpty()
    }

    /**
     * Shows [hit]: moves the camera there at the zoom its kind calls for (never zooming out of a closer
     * view), drops the marker and closes the list. Any thread.
     */
    fun select(hit: PackHit): Map<String, Any?> {
        val app = DystopoApp.instance
        val e = hit.hit.entry
        val zoom = maxOf(e.kind.zoom, app.map.map?.let { m -> app.map.onMain { m.cameraPosition.zoom } } ?: 0.0).coerceAtMost(16.0)
        val camera = app.map.setCamera(e.lat, e.lon, zoom, null, null)
        app.map.onMain {
            app.map.map?.style?.let { SearchMarker.show(it, e.lat, e.lon, e.name) } // T41: with its name
            selected = hit
            open = false
        }
        return linkedMapOf("selected" to hit.json(), "camera" to camera)
    }

    /** Clears the text, the results and the marker. Main thread. */
    fun clear() {
        query = ""
        hits = emptyList()
        open = false
        selected = null
        DystopoApp.instance.map.map?.style?.let { SearchMarker.clear(it) }
    }
}

/** T149: the box's line when the heap guard refused every index build (asked again after a while). */
internal const val SEARCH_NO_MEMORY = "Not enough memory to index places now; try again in a minute"

/**
 * The search box over the map (top left): type a place name, pick a result, and the camera moves
 * there with a temporary marker. Drawn by one line in MainActivity's map screen. While the active
 * pack's index is still being built the box says so, with progress.
 */
@Composable
fun SearchOverlay() {
    val ui = SearchUi
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) {
        // Follows the indexes of the packs on the map; T149: starts them only while the box is open or holds text.
        while (true) {
            ui.refresh()
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(if (PlaceSearch.states.any { it.busy }) 500 else 2000) // T150: paused in the background
        }
    }
    LaunchedEffect(ui.focused) { ui.refresh() } // T149: opening the box starts the indexes at once
    LaunchedEffect(ui.query) { ui.refresh() }

    val density = androidx.compose.ui.platform.LocalDensity.current
    io.github.jasondorjeshort.dystopo.app.MapChrome { Box(Modifier.fillMaxSize()) { // T91: capped font scale
        Column(
            // T91: ends before the Settings chip; its bottom edge places the online indicator below it.
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).width(io.github.jasondorjeshort.dystopo.app.TopChips.searchWidth())
                .onGloballyPositioned { c ->
                    // Without the open result list, which covers the indicator on purpose.
                    if (!ui.open || ui.hits.isEmpty()) io.github.jasondorjeshort.dystopo.app.TopChips.edge(c, density).let { if (it != io.github.jasondorjeshort.dystopo.app.TopChips.searchBottom) io.github.jasondorjeshort.dystopo.app.TopChips.searchBottom = it }
                },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (ui.query.isEmpty()) BasicText("Search places", style = TextStyle(color = Muted, fontSize = 15.sp))
                    BasicTextField(
                        ui.query,
                        { ui.query = it; ui.open = true },
                        singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 15.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            ui.hits.firstOrNull()?.let { h -> thread(name = "dystopo-search-select") { ui.select(h) } }
                            focus.clearFocus()
                        }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { ui.focused = it.isFocused },
                    )
                }
                if (ui.query.isNotEmpty() || ui.selected != null) {
                    BasicText(
                        "×",
                        style = TextStyle(color = Muted, fontSize = 15.sp),
                        modifier = Modifier.clickable { ui.clear(); focus.clearFocus() }.padding(start = 10.dp),
                    )
                }
            }
            // T142 (ADR 0041 §5): a chosen result offers its surroundings offline, named after it.
            ui.selected?.takeIf { !ui.open }?.hit?.entry?.let { e ->
                BasicText(
                    io.github.jasondorjeshort.dystopo.app.areas.AROUND_HERE,
                    Modifier.background(Paper, RoundedCornerShape(8.dp)).clickable { io.github.jasondorjeshort.dystopo.app.areas.AreaPicker.startAround(e.lat, e.lon, e.name) }.padding(8.dp, 5.dp),
                    style = TextStyle(color = Color(0xFF2C7BB6), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
            }
            ui.status?.let { BasicText(it, Modifier.background(Paper, RoundedCornerShape(8.dp)).padding(8.dp, 4.dp), style = TextStyle(color = Accent, fontSize = 12.sp)) }
            if (ui.open && ui.query.isNotBlank()) {
                Column(Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp))) {
                    if (ui.hits.isEmpty() && ui.status == null) {
                        BasicText("No places match", Modifier.padding(12.dp), style = TextStyle(color = Muted, fontSize = 14.sp))
                    }
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(ui.hits) { i, h ->
                            ResultRow(h, first = i == 0) {
                                focus.clearFocus()
                                thread(name = "dystopo-search-select") { ui.select(h) }
                            }
                        }
                    }
                }
            }
        }
    } }
}

@Composable
private fun ResultRow(p: PackHit, first: Boolean, onClick: () -> Unit) {
    val h = p.hit
    val e = h.entry
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).let { if (first) it else it.border(0.5.dp, Line) }.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BasicText(e.name, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val parts = listOfNotNull(
            e.kind.label.replaceFirstChar { it.uppercase() },
            e.alt,
            e.ele?.let { UnitFormat.elevation(it.toDouble(), AppSettings.units) },
            h.distanceKm?.let { UnitFormat.distance(it * 1000, AppSettings.units) },
            p.pack.takeIf { PlaceSearch.states.size > 1 }, // T41: which pack, when there are several
        )
        BasicText(parts.joinToString("  ·  "), style = TextStyle(color = Muted, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
