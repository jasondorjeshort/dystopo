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

        RelaySection(status)
        Spacer(Modifier.height(24.dp))
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
