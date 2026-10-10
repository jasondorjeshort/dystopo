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
            "While this screen is open, a phone on the same network can sync tracks, waypoints and routes with this one. Give it the code.",
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
                BasicText(shownCode.chunked(3).joinToString(" "), style = TextStyle(color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
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
        Field("Code", code, KeyboardType.Number) { ShareUi.code = it.filter(Char::isDigit).take(6) }
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
        Spacer(Modifier.height(24.dp))
    }
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
