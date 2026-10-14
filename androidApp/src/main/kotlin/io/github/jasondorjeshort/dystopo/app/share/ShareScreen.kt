package io.github.jasondorjeshort.dystopo.app.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.sync.crypto.ShareCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Whether the Share screen is open (`POST /share/start` and `/share/stop` set it too). */
object ShareUi {
    var visible by mutableStateOf(false)

    /** T57: the address and code fields (a tapped share link or a scanned QR code fills them). */
    var addr by mutableStateOf("")
    var code by mutableStateOf("")

    /** T72: the code typed to join another phone through the server relay. */
    var relayCode by mutableStateOf("")

    /** Where the fields came from: `link` (a tapped `dystopo://share` link), `qr` (the scanner), `scan` (`POST /share/scan`), or null (typed). */
    var prefilledFrom by mutableStateOf<String?>(null)

    /** Main thread. */
    fun prefill(addr: String, code: String, source: String) {
        this.addr = addr
        this.code = code
        prefilledFrom = source
        prefills++
    }

    @Volatile var prefills = 0
        private set

    fun json(): Map<String, Any?> = linkedMapOf("addr" to addr, "codeSet" to code.isNotEmpty(), "from" to prefilledFrom, "prefills" to prefills)

    /** Start and stop in order, off the main thread (opening and closing quickly must end stopped). */
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-share").apply { isDaemon = true } }
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
private val Link = Color(0xFF2C7BB6)
private val Bad = Color(0xFFB0302A)

/** "Share" next to "Sync now" in the tracks list: opens the Share screen. */
@Composable
fun ShareAction() {
    BasicText(
        "Share",
        style = TextStyle(color = Link, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable { ShareUi.visible = true }.padding(vertical = 4.dp),
    )
}

/**
 * The Share screen over the map (one line in MainActivity): while it is composed the LAN listener and
 * NSD run ([ShareSession.start]); leaving it stops them.
 */
@Composable
fun ShareOverlay() {
    LaunchedEffect(Unit) { ShareUi.worker.execute { runCatching { PeerSources.load() } } } // T90: kept peers, retried from launch
    if (!ShareUi.visible) return
    val session = ShareSession.of(DystopoApp.instance)
    DisposableEffect(Unit) {
        ShareUi.worker.execute { runCatching { session.start() } }
        onDispose { ShareUi.worker.execute { session.stop() } }
    }
    BackHandler { ShareUi.visible = false }
    ShareScreen(session) { ShareUi.visible = false }
}

@Composable
private fun ShareScreen(session: ShareSession, close: () -> Unit) {
    var status by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    val addr = ShareUi.addr
    val code = ShareUi.code
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.IO) { session.status() }
            delay(700)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Paper).statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Share with a nearby phone", Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
            Chip("Close") { close() }
        }
        BasicText(
            "While this screen is open, a phone on the same network can sync tracks, waypoints and routes with this one. Give it the code. Farther away: sync via server, below.",
            style = TextStyle(color = Muted, fontSize = 14.sp),
        )

        // This phone: code, address, QR.
        val listeningNow = status["listening"] == true
        val shownCode = status["code"] as? String
        val port = (status["port"] as? Number)?.toInt()
        @Suppress("UNCHECKED_CAST") val addresses = (status["addresses"] as? List<String>).orEmpty()
        Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(8.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(status["name"] as? String ?: "This phone", style = TextStyle(color = Muted, fontSize = 13.sp))
            if (listeningNow && shownCode != null) {
                BasicText("Code", style = TextStyle(color = Muted, fontSize = 13.sp))
                BasicText(ShareCode.format(shownCode), style = TextStyle(color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                BasicText("The link is encrypted with this code; only a phone that has it can sync.", style = TextStyle(color = Muted, fontSize = 12.sp))
                BasicText(
                    if (addresses.isEmpty()) "No network address: connect to Wi-Fi" else "Address: " + addresses.joinToString(", ") { "$it:$port" },
                    style = TextStyle(color = Ink, fontSize = 15.sp),
                )
                (status["uri"] as? String)?.let { uri ->
                    val qr = remember(uri) { runCatching { QrCode.encode(uri) }.getOrNull() }
                    if (qr != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(qr) }
                        BasicText(uri, style = TextStyle(color = Muted, fontSize = 11.sp))
                    }
                }
                @Suppress("UNCHECKED_CAST") val gate = status["gate"] as? Map<String, Any?>
                val refused = (gate?.get("refused") as? Number)?.toLong() ?: 0L
                if (refused > 0) BasicText("Wrong codes refused: $refused", style = TextStyle(color = Bad, fontSize = 13.sp))
                @Suppress("UNCHECKED_CAST") (status["lastIncoming"] as? Map<String, Any?>)?.let {
                    BasicText("Last sync from a phone: ${it["applied"]} changes received", style = TextStyle(color = Accent, fontSize = 13.sp))
                }
                ServingLines(status) // T78: who is fetching maps from this phone, and how much
            } else {
                BasicText("Starting...", style = TextStyle(color = Muted, fontSize = 14.sp))
            }
        }

        // The other side: discovered phones, address and code.
        BasicText("Sync with another phone", style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
        val found = session.peers.peers()
        if (found.isEmpty()) BasicText("Looking for phones on this network...", style = TextStyle(color = Muted, fontSize = 13.sp))
        found.forEach { p ->
            BasicText(
                "${p.name}  (${p.hostPort})",
                style = TextStyle(color = Link, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(6.dp)).clickable { ShareUi.addr = p.hostPort ?: ""; ShareUi.prefilledFrom = null }.padding(10.dp),
            )
        }
        // T57: a tapped share link or a scanned code fills the fields; the user still taps Sync.
        ShareUi.prefilledFrom?.let { from ->
            BasicText(
                (if (from == "link") "From a share link" else "From a QR code") + ": tap Sync to sync with $addr",
                style = TextStyle(color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
        }
        Field("Address (host:port)", addr, KeyboardType.Uri) { ShareUi.addr = it; ShareUi.prefilledFrom = null }
        Field("Code", code, KeyboardType.Ascii) { ShareUi.code = codeInput(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Scan QR code") { ScanUi.visible = true } // T57: the in-app scanner
            Chip(if (busy) "Syncing..." else "Sync") {
                if (busy || addr.isBlank()) return@Chip
                busy = true
                message = null
                Thread {
                    message = runCatching { session.connect(addr, code) }.fold(
                        { r -> "Synced with ${r["peerName"] ?: addr}: ${(r["merged"] as? Map<*, *>)?.get("applied")} in, ${r["pushed"]} out" },
                        { e -> if (e is ShareClient.WrongCodeException) "Wrong code" else "Sync failed: ${e.message}" },
                    )
                    busy = false
                }.start()
            }
        }
        message?.let { BasicText(it, style = TextStyle(color = if (it.startsWith("Synced")) Accent else Bad, fontSize = 14.sp)) }

        PeerMapsSection(status, addr, code) // T78
        RelaySection(status)
        Spacer(Modifier.height(24.dp))
    }
}

/** T78: one line per phone that fetched maps from this one: its name, the bytes, the archives and tiles. */
@Composable
private fun ServingLines(status: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST") val serving = status["serving"] as? Map<String, Any?> ?: return
    @Suppress("UNCHECKED_CAST") val fetchers = (serving["fetchers"] as? List<Map<String, Any?>>).orEmpty().filter { ((it["requests"] as? Number)?.toLong() ?: 0L) > 0 }
    if (fetchers.isEmpty()) {
        BasicText("Phones with the code can also download this phone's maps.", style = TextStyle(color = Muted, fontSize = 12.sp))
        return
    }
    BasicText("Maps sent from this phone", style = TextStyle(color = Muted, fontSize = 13.sp))
    fetchers.forEach { f ->
        val bytes = (f["bytes"] as? Number)?.toLong() ?: 0L
        val tiles = (f["tiles"] as? Number)?.toLong() ?: 0L
        @Suppress("UNCHECKED_CAST") val files = (f["files"] as? Map<String, Any?>).orEmpty().keys
        val what = listOfNotNull(files.takeIf { it.isNotEmpty() }?.joinToString(", "), if (tiles > 0) "$tiles tiles" else null).joinToString("; ")
        BasicText("${f["name"] ?: "A phone"}: ${mb(bytes)}${if (what.isEmpty()) "" else " ($what)"}", style = TextStyle(color = Accent, fontSize = 13.sp))
    }
}

private fun mb(bytes: Long) = if (bytes < 1_000_000) "${(bytes + 999) / 1000} kB" else "%.1f MB".format(java.util.Locale.ROOT, bytes / 1e6)

/**
 * T78: maps from nearby phones. "Use its maps" adds the phone in the fields as a map source (its code unlocks its catalog);
 * each source lists its packs with Download (the same verified, resumable download as from a server) and its areas.
 */
@Composable
private fun PeerMapsSection(tick: Any?, addr: String, code: String) {
    val app = DystopoApp.instance
    var peers by remember { mutableStateOf(PeerSources.all()) }
    var jobs by remember { mutableStateOf<Map<String, Map<String, Any?>>>(emptyMap()) }
    var installed by remember { mutableStateOf<Map<String, String?>>(emptyMap()) } // pack name to its content hash here
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        peers = PeerSources.all()
        val manager = io.github.jasondorjeshort.dystopo.app.packs.PackManager.get(app)
        jobs = withContext(Dispatchers.IO) { manager.downloads.snapshot().associateBy { it["name"] as? String ?: "" } }
        installed = withContext(Dispatchers.IO) { manager.catalog().associate { it.name to manager.catalogClient.installed(it, compute = false).hash } }
    }
    fun run(action: () -> String?) {
        if (busy) return
        busy = true
        message = null
        Thread {
            message = runCatching(action).fold({ it }, { e -> if (e is ShareClient.WrongCodeException) "Wrong code" else (e.message ?: e.javaClass.simpleName) })
            peers = PeerSources.all()
            busy = false
        }.start()
    }

    BasicText("Maps from a nearby phone", style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
    BasicText(
        "A phone with maps can pass them on: enter its address and code above, then tap Use its maps. Downloads are checked against the other phone's list, and work offline afterwards.",
        style = TextStyle(color = Muted, fontSize = 13.sp),
    )
    Chip(if (busy) "Working..." else "Use its maps") {
        if (addr.isBlank()) { message = "Enter the other phone's address and code"; return@Chip }
        run { PeerSources.add(addr, code).let { p -> "Connected to ${p.name}: ${p.catalog?.packs?.size ?: 0} packs" } }
    }
    message?.let { BasicText(it, style = TextStyle(color = if (it.startsWith("Connected") || it.startsWith("Downloading") || it.startsWith("Forgot")) Accent else Bad, fontSize = 14.sp)) }
    peers.forEach { p ->
        Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(p.name ?: p.addr, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Chip("Forget") { run { PeerSources.remove(p.addr); "Forgot ${p.name ?: p.addr}" } } // T90: peers are kept until forgotten
            }
            BasicText(peerSeenLine(p, System.currentTimeMillis()), style = TextStyle(color = Muted, fontSize = 12.sp))
            p.error?.takeIf { p.state == "error" }?.let { BasicText(it, style = TextStyle(color = Bad, fontSize = 12.sp)) }
            val packs = p.catalog?.packs.orEmpty()
            if (packs.isEmpty()) BasicText("No packs on this phone", style = TextStyle(color = Muted, fontSize = 13.sp))
            packs.forEach { pack ->
                val job = shownJob(jobs[pack.name], p)
                val state = job?.get("state") as? String
                val here = pack.name in installed && state != "queued" && state != "running"
                val line = when {
                    state == "queued" || state == "running" -> "${pack.name}: downloading ${job?.get("percent") ?: 0}%"
                    here && installed[pack.name] == pack.hash -> "${pack.name}: on this phone"
                    here -> "${pack.name}: on this phone (another version)"
                    state == "done" -> "${pack.name}: downloaded"
                    state == "failed" -> "${pack.name}: failed (${job?.get("error")})"
                    else -> "${pack.name}  ${mb(pack.bytes)}"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(line, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 14.sp))
                    if (state != "queued" && state != "running" && state != "done" && !(here && installed[pack.name] == pack.hash)) {
                        Chip("Download") { run { PeerSources.download(p.addr, pack.id, activate = true); "Downloading ${pack.name} from ${p.name}" } }
                    }
                }
            }
            p.areas.forEach { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Area: ${a["name"]}", Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 14.sp))
                    Chip("Get") { run { PeerSources.area(p.addr, a["id"] as String); "Downloading the area ${a["name"]}" } }
                }
            }
        }
    }
}

/**
 * T96: the download job to show under [p]'s pack: none for one that failed before the peer's state last changed (its
 * code changed, or it connected again with the new one), so "failed (wrong code ...)" does not stay under a peer that
 * works again; the pack then shows its size and Download.
 */
internal fun shownJob(job: Map<String, Any?>?, p: PeerSources.Peer): Map<String, Any?>? =
    job?.takeUnless { it["state"] == "failed" && ((it["createdAt"] as? Number)?.toLong() ?: 0L) < p.stateAt }

/**
 * T90: the state line of a peer in the list: kept peers are listed after a restart before they answer, and one that
 * does not answer stays listed with when it was last seen (it is tried again in the background).
 */
internal fun peerSeenLine(p: PeerSources.Peer, now: Long): String {
    val seen = p.lastSeenAt?.let { "last seen ${ago(now - it)}" } ?: "not seen yet"
    return when (p.state) {
        "saved" -> "Saved from before; connecting... ($seen)"
        "unreachable" -> "Not reachable; trying again${p.nextRetryAt?.let { t -> " in ${ago(t - now).removeSuffix(" ago")}" } ?: ""} ($seen)"
        "codeChanged" -> "Its code changed: enter the code it shows now and tap Use its maps ($seen)"
        "error" -> "Not reachable ($seen)"
        else -> "Connected, $seen"
    }
}

internal fun ago(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 500) / 1000
    return when {
        s < 5 -> "just now"
        s < 90 -> "$s s ago"
        s < 90 * 60 -> "${(s + 30) / 60} min ago"
        s < 36 * 3600 -> "${(s + 1800) / 3600} h ago"
        else -> "${(s + 43200) / 86400} days ago"
    }
}

/** What the code fields accept while typing: letters, digits, dashes and spaces, uppercased, at most a grouped code's length. */
private fun codeInput(s: String) = s.uppercase().filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.take(ShareCode.LENGTH + 3)

/**
 * T72: "Via server": sync with a phone that is not on this network through the server's relay ([RelaySession]).
 * One phone taps New code and passes the code on (a message, a call); the other types it and taps Join.
 */
@Composable
private fun RelaySection(tick: Any?) {
    val relay = RelaySession.of(DystopoApp.instance)
    var relayStatus by remember { mutableStateOf(relay.status()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { relayStatus = withContext(Dispatchers.IO) { relay.status() } }
    fun run(label: String, action: () -> Map<String, Any?>) {
        if (busy) return
        busy = true
        message = null
        Thread {
            message = runCatching(action).fold(
                { r -> (r["lastError"] as? String)?.let { "$label: $it" } },
                { e -> "$label failed: ${e.message}" },
            )
            relayStatus = relay.status()
            busy = false
        }.start()
    }

    BasicText("Sync with a phone anywhere (via server)", style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
    BasicText(
        "Both phones need the same server. It only passes on data encrypted with the code, and forgets it after 15 minutes.",
        style = TextStyle(color = Muted, fontSize = 13.sp),
    )
    val active = relayStatus["active"] == true
    if (active) {
        Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(8.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("Code for the other phone", style = TextStyle(color = Muted, fontSize = 13.sp))
            BasicText(relayStatus["codeShown"] as? String ?: "", style = TextStyle(color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
            BasicText("Server: ${relayStatus["server"]}", style = TextStyle(color = Ink, fontSize = 14.sp))
            @Suppress("UNCHECKED_CAST") val last = relayStatus["lastResult"] as? Map<String, Any?>
            val line = when {
                last != null -> "Last sync: ${(last["merged"] as? Map<*, *>)?.get("applied")} in, ${last["pushed"]} out; received ${relayStatus["received"]} from the other phone"
                else -> "Waiting for the other phone..."
            }
            BasicText(line, style = TextStyle(color = Accent, fontSize = 13.sp))
            if (relayStatus["polling"] == true) BasicText("Checking the server every 5 s", style = TextStyle(color = Muted, fontSize = 12.sp))
            (relayStatus["lastError"] as? String)?.let { BasicText(it, style = TextStyle(color = Bad, fontSize = 13.sp)) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (busy) "Working..." else "New code") { run("Relay") { relay.start(null, null) } }
        if (active) {
            Chip("Sync again") { run("Sync") { relay.syncNow() } }
            Chip("Stop") { run("Stop") { relay.stop(forget = true) } }
        }
    }
    Field("Code from the other phone", ShareUi.relayCode, KeyboardType.Ascii) { ShareUi.relayCode = codeInput(it) }
    Chip(if (busy) "Working..." else "Join") {
        val typed = ShareUi.relayCode
        if (ShareCode.normalize(typed) == null) {
            message = "The code is ${ShareCode.LENGTH} letters and digits"
        } else {
            run("Join") { relay.start(null, typed) }
        }
    }
    message?.let { BasicText(it, style = TextStyle(color = Bad, fontSize = 14.sp)) }
}

@Composable
private fun QrImage(qr: QrCode) {
    Canvas(Modifier.size(200.dp).background(Color.White)) {
        val n = qr.size + 8 // 4 light modules of quiet zone on each side
        val cell = kotlin.math.floor(size.minDimension / n)
        val origin = (size.minDimension - cell * n) / 2 + 4 * cell
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr[x, y]) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
        }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BasicText(label, style = TextStyle(color = Muted, fontSize = 13.sp))
        BasicTextField(
            value, onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            textStyle = TextStyle(color = Ink, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(6.dp)).padding(10.dp),
        )
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(Color(0xEEFFFCF5), RoundedCornerShape(16.dp))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
