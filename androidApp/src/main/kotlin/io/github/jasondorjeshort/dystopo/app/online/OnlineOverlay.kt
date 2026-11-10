package io.github.jasondorjeshort.dystopo.app.online

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.areas.OfflineMapList
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.firstrun.Screens
import io.github.jasondorjeshort.dystopo.app.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread

private val Ink = Color(0xFF2B2420)
private val Line = Color(0xFFE2D8C6)
private val Chip = Color(0xEEFFFCF5)
private val Green = Color(0xFF3C8A3F)
private val Grey = Color(0xFF9A8F84)
private val Red = Color(0xFFB0302A)
private val LinkBlue = Color(0xFF2C7BB6)

/**
 * T144: what the overlay shows, a snapshot made off the UI thread once a second ([make]) and only read by the composable:
 * the online state, the view's source, the region download's record, the reading notice. The UI thread never calls into
 * [OnlineService], [RegionDownload] or the pack manager for it. The lines it shows are worked out here; pure, tested
 * (`OnlineOverlaySnapshotTest`).
 *
 * T236 (ADR 0056 B3, proposal 0056's C1 and its two tables): the chip reads `<state word> · <where this view's map is>` in plain
 * words, from three more inputs: [network] (the phone has a network, read as [DirectSources.onCellular] reads it),
 * [serverSet] (a server the user set, so a red dot can blame it; a fetcher of nearby phones alone is not one) and the view's
 * [OnlineView.kept]. "From the network" is said only under Online, the one state where it is true of the next tile; every other
 * network memo reads "viewed earlier". A tap opens a card of two sentences, at most one link, and the cache's figure ([cache]).
 */
class OnlineUi(
    val state: String, val view: OnlineView?, val download: Map<String, Any?>, val running: Boolean, val notice: String? = null, val direct: Boolean = false,
    val firstRunCard: Boolean = false,
    val network: Boolean = true, val serverSet: Boolean = !direct, val server: String? = null, val cache: CacheFigures? = null,
) {
    enum class Dot { GREEN, GREY, RED }

    /** T236: the chip's rows (the proposal's first table, a row per state). */
    enum class Row(val word: String, val dot: Dot) {
        ONLINE("Online", Dot.GREEN), PAUSED("Paused", Dot.GREY), SERVER_OFFLINE("Server offline", Dot.RED),
        OFFLINE_RED("Offline", Dot.RED), OFFLINE_GREY("Offline", Dot.GREY), CONNECTING("Connecting", Dot.GREY), NO_SERVER("No server", Dot.GREY),
    }

    /**
     * T236: the table's columns: where the view's map is. A network tile names its source: public, the user's server, a nearby
     * phone. T255: [WIDER], a tile the phone lacked, cut from the nearest one it holds at a wider zoom (T254, [OnlineTiles.WIDER]).
     */
    enum class Column { PACK, NETWORK_PUBLIC, NETWORK_SERVER, NETWORK_PEER, CACHE_KEPT, CACHE, WIDER, NO_DATA }

    /** T236: the online tile cache's figures for the card, read with [TileCache]'s getters (never a whole-index method). */
    class CacheFigures(val bytes: Long, val pinnedBytes: Long, val capMb: Long, val partial: Boolean)

    /** A link on the card: its words and where a tap goes. */
    enum class Link(val text: String) {
        SOURCES("${Screens.SETTINGS} › ${Screens.SOURCES} ›"),
        OFFLINE_MAPS("${Screens.OFFLINE_MAPS} ›"),
    }

    /** Hidden: no server and nothing downloading. */
    val hidden: Boolean get() = state == "none" && !running

    /**
     * The chip's state (T85: not green while mobile data is off for it or the session limit is reached). T192: red only for a
     * server the user set that does not answer; with no server set (the public sources read directly) being offline is the
     * plain state of an offline-first map, grey. T236: "Server offline" when the phone has a network and the server does not
     * answer, "Offline" (still red) without one; a fetcher of nearby phones alone takes the grey row. With no network the
     * state is never Online: the phone's own source reader answers its probe without one (seen on the emulator with Wi-Fi
     * and data off), and nothing can be reached then.
     */
    val row: Row get() = when (state) {
        "online" -> if (!network) offlineRow else if (notice != null) Row.PAUSED else Row.ONLINE
        "offline" -> offlineRow
        "none" -> Row.NO_SERVER
        else -> Row.CONNECTING
    }

    private val offlineRow: Row get() = if (!serverSet) Row.OFFLINE_GREY else if (network) Row.SERVER_OFFLINE else Row.OFFLINE_RED

    /** The chip's state word and its dot. */
    val label: Pair<String, Dot> get() = row.word to row.dot

    /** T236: the view's column (a cache hit inside an offline map on the list is "kept"; T80: a network tile names its source). */
    val column: Column get() = when (view?.source) {
        OnlineTiles.PACK -> Column.PACK
        OnlineService.ONLINE -> if (direct) Column.NETWORK_PUBLIC else if (serverSet) Column.NETWORK_SERVER else Column.NETWORK_PEER
        OnlineService.CACHED -> if (view.kept) Column.CACHE_KEPT else Column.CACHE
        OnlineTiles.WIDER -> Column.WIDER // T255: drawn, so never "no data here"
        else -> Column.NO_DATA
    }

    private val fromNetwork: Boolean get() = row == Row.ONLINE && column in NETWORK

    /** The chip's second part: where this view's map is. */
    val sourceText: String get() = when (column) {
        Column.PACK, Column.CACHE_KEPT -> ON_PHONE
        Column.NO_DATA -> NO_DATA
        Column.NETWORK_PUBLIC -> if (fromNetwork) "from public sources" else VIEWED
        Column.NETWORK_SERVER -> if (fromNetwork) "from your server" else VIEWED
        Column.NETWORK_PEER -> if (fromNetwork) "from a nearby phone" else VIEWED
        Column.CACHE -> VIEWED
        Column.WIDER -> WIDER_ZOOM
    }

    /** T236: the chip's whole text. */
    val text: String get() = "${row.word} · $sourceText"

    private val address: String get() = server?.takeIf { it.isNotBlank() } ?: "its address"

    /** T236: the card's first sentence, about the connection (the proposal's second table). Paused: the notice as it reads (ADR 0023). */
    val stateSentence: String get() = when (row) {
        Row.ONLINE -> "The phone can reach the map sources."
        Row.PAUSED -> notice ?: "Reading the map sources is paused."
        Row.SERVER_OFFLINE -> "Your server at $address is not answering. It may be off or out of reach."
        Row.OFFLINE_RED -> "No connection, so your server at $address cannot be reached. The map still works where it is on this phone."
        Row.OFFLINE_GREY -> if (network && direct) SOURCES_NOT_ANSWERING else "No connection. The map still works where it is on this phone."
        Row.CONNECTING -> "Checking the connection."
        Row.NO_SERVER -> "No server is set and the public sources are off."
    }

    /**
     * T236: the card's second sentence, about where this view's map is. T255: an offline map (a pack, with search and routes
     * where it has places and trails) told from tiles kept by a map-only download, and a view cut from a wider zoom.
     */
    val sourceSentence: String get() = when {
        column == Column.PACK -> SAVED_OFFLINE_MAP
        column == Column.CACHE_KEPT -> KEPT_TILES
        column == Column.WIDER -> CUT_FROM_WIDER
        sourceText == NO_DATA -> "Nothing on this phone covers this view."
        sourceText == VIEWED -> "You viewed this part earlier. It stays until space is needed."
        else -> "This part of the map is coming over the network and is kept only until space is needed."
    }

    /**
     * T236: the card's one link: the state's when it has one (Settings › Sources, for a server to check or a limit to raise),
     * else the source's (Offline maps); none while the map comes over the network (the Download button is below).
     */
    val link: Link? get() = when (row) {
        Row.PAUSED, Row.SERVER_OFFLINE, Row.OFFLINE_RED, Row.NO_SERVER -> Link.SOURCES
        else -> if (fromNetwork) null else Link.OFFLINE_MAPS
    }

    /**
     * T236 with T242: the card's figure for the tiles kept from viewing, "Viewed earlier: 120 MB of 2 GB" (of the cap the user set
     * in Settings › Storage), or [TileCache.INDEXING_LINE] while the cache's index is still being built and its figures are partial.
     */
    val cacheLine: String? get() = cache?.let { c ->
        if (c.partial) TileCache.INDEXING_LINE
        else "Viewed earlier: ${OfflineMapList.sizeText((c.bytes - c.pinnedBytes).coerceAtLeast(0))} of ${Storage.sizeLabel(c.capMb)}"
    }

    /** T236: `chip` in `GET /online`: the text and dot, whether the card is [open], its sentences, figure and link. */
    fun chipJson(open: Boolean): Map<String, Any?> = linkedMapOf(
        "text" to text, "word" to row.word, "source" to sourceText, "dot" to row.dot.name.lowercase(Locale.US), "row" to row.name.lowercase(Locale.US),
        "column" to column.name.lowercase(Locale.US), "open" to open, "hidden" to hidden,
        "detail" to listOf(stateSentence, sourceSentence), "figure" to cacheLine, "link" to link?.text,
    )

    /** The progress line while a download runs, else null. */
    val progress: String? get() {
        if (!running) return null
        val pct = (download["percent"] as? Number)?.toDouble() ?: 0.0
        val phase = if (download["state"] != "building") "Downloading" else if (direct) "Building on this phone" else "Building on server" // T80
        val kind = download["packLabel"]?.let { " ($it)" } ?: "" // T64: which vector profile the server is making
        return String.format(Locale.US, "%s %s%s: %.0f%%", phase, download["name"], kind, pct)
    }

    /** "Download this area" is offered: online (T236: with a network), a view, not all pack, nothing running; T220: not while the first-run card is up (its first choice is the same thing). */
    val offerDownload: Boolean get() = !running && state == "online" && network && view != null && view.source != OnlineTiles.PACK && !firstRunCard

    /** The line after a finished or failed download, or null. T236: "Saved as an offline map: <name>" (ADR 0056). */
    val result: Pair<String, Dot?>? get() = when (download["state"]) {
        "done" -> if (view?.source == OnlineTiles.PACK) ("Saved as an offline map: ${download["name"]}" + (download["packLabel"]?.let { " · $it" } ?: "")) to null else null
        "failed" -> ((download["sentence"] as? String) ?: "Download failed: ${download["error"]}") to Dot.RED // T222
        "paused" -> (download["sentence"] as? String)?.let { it to null } // T222
        else -> null
    }

    /** T222: what the map offers beside the result line (the row in the pack list has Remove too). */
    val resultActions: List<String> get() = (download["actions"] as? List<*>).orEmpty().map { it.toString() }.filter { it != "Remove" }

    companion object {
        const val ON_PHONE = "on this phone"
        const val VIEWED = "viewed earlier"
        const val NO_DATA = "no data here"
        /** T255: the chip's source over a view cut from a wider zoom (T254). */
        const val WIDER_ZOOM = "from a wider zoom"
        /** T255 (ADR 0031: listed for the owner): the card's source sentences that tell an offline map from kept tiles. */
        const val SAVED_OFFLINE_MAP = "This part of the map is saved on this phone as an offline map."
        const val KEPT_TILES = "This part of the map is kept on this phone as map tiles only, so search and routes do not work here."
        const val CUT_FROM_WIDER = "This part of the map is cut from a wider zoom kept on this phone, so it shows less detail."
        /** T255 (T236's follow-up): the grey Offline row's first sentence when the phone has a network and the public sources fail. */
        const val SOURCES_NOT_ANSWERING = "The public map sources are not answering. The map still works where it is on this phone."
        /** T236: the overlay's button (ADR 0056: "area", not "region"). */
        const val DOWNLOAD = "Download this area"
        private val NETWORK = setOf(Column.NETWORK_PUBLIC, Column.NETWORK_SERVER, Column.NETWORK_PEER)

        /** The snapshot for camera [cam] (read on the UI thread, source unknown): everything else is read here, on a worker. */
        fun make(service: OnlineService, app: DystopoApp, cam: OnlineView?): OnlineUi {
            val view = cam?.let { OnlineView.resolve(app, it) }
            val server = AppSettings.server
            val c = service.cache // T242: the getters never wait and are partial while the index is built
            return OnlineUi(
                service.state(), view, service.region.json(), service.region.running, if (service.isDirect) service.direct.notice() else null, service.isDirect,
                io.github.jasondorjeshort.dystopo.app.firstrun.FirstRunCardState.visible,
                network = hasNetwork(app), serverSet = server.isNotEmpty() && service.fetcher != null, server = server.ifEmpty { null },
                cache = CacheFigures(c.bytes, c.pinnedBytes, AppSettings.onlineCacheMb, c.partial),
            )
        }

        /** T236: the phone has a network now (an active one with capabilities, as [DirectSources.onCellular] reads it). */
        fun hasNetwork(context: android.content.Context): Boolean = runCatching {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork) != null
        }.getOrDefault(true)
    }
}

/**
 * T236: the chip's card, open or closed (main thread; a tap on the chip and `POST /online/chip?open=` set it), and the rectangle
 * it was last drawn at, for `GET /online` and `GET /layout` (px from the window's top left, null while closed or hidden).
 */
object OnlineChip {
    var open by mutableStateOf(false)
    @Volatile var cardPx: FloatArray? = null
        internal set

    /** `onlineCard` in `GET /layout` (main thread): the card's rectangle; it lies inside the online column (`M8`), which grows with it. */
    fun layoutJson(): Map<String, Any?> = linkedMapOf("open" to open, "rectPx" to cardPx?.map { Math.round(it) })
}

/**
 * The online indicator over the map (top left): online / offline with the source of the current view
 * (pack, online, cached, none) and "Download this area" with its progress (T153: the button opens the area picker's
 * summary for the view, `AreaPicker.openRegion`; the progress line is the pack path's). Hidden when no server is set. Drawn by
 * one line in MainActivity's map screen. T106: no credit line of its own any more: the map's credit line at the bottom
 * has named the online sources in view since T41 (from the provenance of the tiles in view), while this one, under the
 * top chips and over the map's labels, named the server's whole list ("Elevation: Copernicus DEM GLO-30" at Rainier,
 * where 3DEP is the source).
 *
 * T144: the composable reads [OnlineUi] snapshots; each second the camera is read here (the map's own calls, no lock) and
 * the rest is made on [Dispatchers.Default].
 *
 * T236: a tap on the chip opens its card under it, in this column ([OnlineChip.open]); a second tap closes it. The card says
 * what the two parts mean ([OnlineUi.stateSentence], [OnlineUi.sourceSentence]), the cache's figure and at most one link; the
 * Paused notice moves into it while it is open (ADR 0023). Its rectangle is published for `GET /layout` ([OnlineChip.cardPx]).
 */
@Composable
fun OnlineOverlay() {
    val app = DystopoApp.instance
    val service = remember { OnlineService.get(app) }
    var ui by remember { mutableStateOf<OnlineUi?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val cam = OnlineView.currentOnMain(app)
            ui = withContext(Dispatchers.Default) { runCatching { OnlineUi.make(service, app, cam) }.getOrNull() } ?: ui
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(1000) // T150: paused in the background
        }
    }
    val u = ui
    if (u == null || u.hidden) {
        OnlineChip.cardPx = null
        return io.github.jasondorjeshort.dystopo.app.MapInsets.onlineChipsHidden() // T57
    }
    io.github.jasondorjeshort.dystopo.app.MapChrome { Box(Modifier.fillMaxSize()) { // T91: capped font scale
        Column(
            // Below the search box (T39), which is drawn after this overlay so its results cover it; T91: below its
            // measured bottom, so the "Indexing places" line no longer runs into it; T239: where the layout rule puts it
            // (under the navigation card while navigating), the chip's card (T236) inside it.
            Modifier.align(Alignment.TopStart).padding(start = 8.dp).widthIn(max = 260.dp).control("onlineIndicator") // T238
                .onGloballyPositioned { io.github.jasondorjeshort.dystopo.app.MapInsets.onlineChipsAt(it) }, // T57: catalog fits keep clear
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val (_, dot) = u.label
            val open = OnlineChip.open
            Row(
                Modifier.background(Chip, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
                    .clickable(onClickLabel = if (open) "Close the explanation" else "Explain") { OnlineChip.open = !OnlineChip.open } // T236
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(9.dp).background(color(dot), CircleShape))
                BasicText(u.text, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
            if (open) ChipCard(u) else {
                OnlineChip.cardPx = null
                // T80: why the phone is not reading the sources (the session's download limit, or mobile data turned off by the user; T83).
                u.notice?.let { Small(it, Red) {} }
            }
            u.progress?.let { Small(it) { message = null; thread { service.region.cancel() } } }
            // T153: the button opens the area picker's size-first summary for the visible box (no span cap, the detail chosen
            // for the view, the estimate, the pack path or the area path said); hidden while that card is up.
            if (u.offerDownload && io.github.jasondorjeshort.dystopo.app.areas.AreaPicker.mode !is io.github.jasondorjeshort.dystopo.app.areas.AreaPicker.Mode.Region) {
                Button(OnlineUi.DOWNLOAD) {
                    message = null
                    thread(name = "region-open") {
                        message = try {
                            io.github.jasondorjeshort.dystopo.app.areas.AreaPicker.openRegionOffMain() // the view as it is at the tap
                            null
                        } catch (e: Exception) {
                            e.message
                        }
                    }
                }
            }
            u.result?.let { (text, dot) -> Small(text, if (dot == null) Ink else color(dot)) {} }
            u.resultActions.forEach { a -> // T222
                Button(a) {
                    if (a == io.github.jasondorjeshort.dystopo.app.firstrun.Screens.STORAGE) io.github.jasondorjeshort.dystopo.app.storage.StorageUi.visible = true
                    else thread(name = "region-retry") { service.region.retry() }
                }
            }
            message?.let { Small(it, Red) { message = null } }
        }
    } }
}

/** T236: the chip's card: the two sentences, the cache's figure (T242: the indexing line while partial), at most one link. */
@Composable
private fun ChipCard(u: OnlineUi) {
    Column(
        Modifier.control("onlineCard").background(Chip, RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp)) // T238: the tag
            .clickable { OnlineChip.open = false } // a tap on the card closes it too, as on the chip; the link has its own
            .onGloballyPositioned { c -> val b = c.boundsInRoot(); OnlineChip.cardPx = floatArrayOf(b.left, b.top, b.right, b.bottom) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(u.stateSentence, style = TextStyle(color = if (u.row == OnlineUi.Row.PAUSED) Red else Ink, fontSize = 12.sp))
        BasicText(u.sourceSentence, style = TextStyle(color = Ink, fontSize = 12.sp))
        u.cacheLine?.let { BasicText(it, style = TextStyle(color = Grey, fontSize = 12.sp)) }
        u.link?.let { l ->
            BasicText(
                l.text, style = TextStyle(color = LinkBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable {
                    OnlineChip.open = false
                    when (l) {
                        OnlineUi.Link.SOURCES -> io.github.jasondorjeshort.dystopo.app.SettingsUi.open(io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout.SERVER_GROUP)
                        OnlineUi.Link.OFFLINE_MAPS -> io.github.jasondorjeshort.dystopo.app.packs.PacksUi.visible = true
                    }
                }.padding(vertical = 4.dp),
            )
        }
    }
}

private fun color(d: OnlineUi.Dot) = when (d) {
    OnlineUi.Dot.GREEN -> Green
    OnlineUi.Dot.GREY -> Grey
    OnlineUi.Dot.RED -> Red
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(Chip, RoundedCornerShape(16.dp))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Small(text: String, color: Color = Ink, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 12.sp),
        modifier = Modifier.background(Chip, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
