package io.github.jasondorjeshort.dystopo.app.sync

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.jasondorjeshort.dystopo.app.AppSettings
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Link = Color(0xFF2C7BB6)
private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Line = Color(0xFFE2D8C6)
private val Warn = Color(0xFFB5541C)

/** The sync status sheet's state (T51): open or not, and the last status read off the main thread. */
object SyncUi {
    var visible by mutableStateOf(false)
    var status by mutableStateOf<Map<String, Any?>?>(null)
        private set

    /** Re-reads [AppSync.status] in the background (cheap; the sheet calls it on open and after a sync). */
    fun refresh() {
        if (!visible) return
        Thread {
            val s = runCatching { AppSync.of(DystopoApp.instance).status() }.getOrElse { mapOf("error" to it.message) }
            DystopoApp.instance.map.onMain { status = s }
        }.start()
    }
}

/**
 * "Sync now" and "Status" in the tracks list (T44, T51): one sync with the default peer
 * ([AppSync.defaultPeer]) off the main thread (the result shows in the list's message line), and the
 * sync status sheet ([SyncStatusSheet]).
 */
@Composable
fun SyncNowAction() {
    BasicText(
        "Sync now",
        style = TextStyle(color = Link, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable { syncInBackground() }.padding(vertical = 4.dp),
    )
    BasicText(
        "Sync status",
        style = TextStyle(color = Link, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable { SyncUi.visible = true; SyncUi.refresh() }.padding(vertical = 4.dp),
    )
}

private fun syncInBackground() {
    val sync = AppSync.of(DystopoApp.instance)
    if (!sync.running) Thread { runCatching { sync.syncNow() } }.start()
}

/**
 * The "edited on two devices" note on a track in the list (T51): shown when a merge found a field of it
 * written on two devices (see [io.github.jasondorjeshort.dystopo.sync.Conflicts]); a tap says which
 * value was kept and dismisses it.
 */
@Composable
fun SyncConflictNote(id: String) {
    val sync = AppSync.of(DystopoApp.instance)
    val list = sync.conflicts.filter { it["id"] == id }
    if (list.isEmpty()) return
    BasicText(
        "edited on 2 devices",
        style = TextStyle(color = Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable {
            val c = list.first()
            Tracking.of(DystopoApp.instance).refreshUi("${c["field"]}: kept \"${c["kept"]}\", other device had \"${c["lost"]}\"")
            sync.dismiss(id)
        },
    )
}

/**
 * The sync status sheet (T51; "Sync status" in the tracks list, or `POST /sync/ui?open=1`): the peer,
 * the last sync and what it moved, operations the peer does not have yet, fields edited on two devices
 * (newest first), what the clock-skew guard saw, and this replica's counts.
 */
@Composable
fun SyncStatusSheet() {
    if (!SyncUi.visible) return
    BackHandler { SyncUi.visible = false }
    LaunchedEffect(Unit) { SyncUi.refresh() }
    val s = SyncUi.status
    val sync = AppSync.of(DystopoApp.instance)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp)
                .heightIn(max = 560.dp)
                .background(Paper, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText("Sync", Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                Button(if (sync.running) "Syncing..." else "Sync now") { syncInBackground() }
                Button("Close") { SyncUi.visible = false }
            }
            if (s == null) {
                BasicText("Reading...", style = TextStyle(color = Muted, fontSize = 13.sp))
                return@Column
            }
            @Suppress("UNCHECKED_CAST") val last = s["last"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val conflicts = s["conflicts"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val skew = s["clockSkew"] as? Map<String, Any?>
            Item("Peer", (s["peer"] as? String)?.ifBlank { null }?.let { AppSync.shortName(it) } ?: "none set")
            Item("Last sync", lastLine(last))
            if (last?.get("ok") == true) {
                @Suppress("UNCHECKED_CAST") val m = last["merged"] as? Map<String, Any?>
                Item("Moved", "${m?.get("applied") ?: 0} in, ${last["pushed"] ?: 0} out in ${last["pullPages"] ?: 1}+${last["pushPages"] ?: 0} pages, ${kb(last["bytesIn"])} / ${kb(last["bytesOut"])}")
            }
            Item("Pending", "${s["pendingOps"] ?: "?"} operations the peer does not have yet")
            val total = (conflicts?.get("total") as? Number)?.toLong() ?: 0L
            Item("Conflicts resolved", "$total ${if (total == 1L) "field" else "fields"} edited on two devices (newest value kept)")
            @Suppress("UNCHECKED_CAST")
            for (c in (conflicts?.get("recent") as? List<Map<String, Any?>>).orEmpty().take(5)) {
                BasicText(
                    "${c["kind"]} ${c["field"]}: kept \"${c["kept"]}\", replaced \"${c["lost"]}\"" + if (c["concurrent"] == true) " (at the same time)" else "",
                    style = TextStyle(color = Muted, fontSize = 12.sp),
                )
            }
            val clamped = (skew?.get("clamped") as? Number)?.toLong() ?: 0L
            // T57: the clock comparison with the last peer, then what the skew guard saw.
            @Suppress("UNCHECKED_CAST") val clock = s["clockCheck"] as? Map<String, Any?>
            val clockLine = when {
                clock == null -> "not compared yet (at the next sync)"
                clock["warn"] == true -> clock["message"] as? String ?: "clocks differ"
                else -> "agrees with ${clock["peer"]} (within ${((clock["uncertaintyMs"] as? Number)?.toLong() ?: 0L) / 1000 + 1} s)"
            }
            Item("Clock check", clockLine, if (clock?.get("warn") == true) Warn else Ink)
            Item(
                "Far-ahead edits",
                if (clamped == 0L) "no device more than a day ahead"
                else "$clamped operations from a device up to ${((skew?.get("maxAheadMs") as? Number)?.toLong() ?: 0L) / 3_600_000} h ahead (merged; clock not moved)",
            )
            PeerSettings(s)
            Item("This device", "${(s["device"] as? String)?.take(8)}  ${s["ops"]} operations, ${s["tracks"]} tracks, ${s["settings"]} synced settings")
        }
    }
}

private fun lastLine(last: Map<String, Any?>?): String {
    if (last == null) return "never"
    val at = (last["at"] as? Number)?.toLong()?.let { SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(it)) } ?: "?"
    val with = (last["peer"] as? String)?.let { " with ${AppSync.shortName(it)}" } ?: "" // T57: which peer
    return if (last["ok"] == true) "$at$with, ${(last["ms"] as? Number)?.toLong() ?: 0} ms" else "$at$with failed: ${last["error"]}"
}

private fun kb(v: Any?): String = ((v as? Number)?.toLong() ?: 0L).let { if (it < 10_240) "$it B" else "${it / 1024} kB" }

@Composable
private fun Item(label: String, value: String, color: Color = Ink) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label, Modifier.width(120.dp), style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        BasicText(value, style = TextStyle(color = color, fontSize = 13.sp))
    }
}

/**
 * T57: the peer and the bearer token for a server whose `/sync` is gated (the `server` module's token), as
 * `POST /sync/settings?peer=&token=` sets them. The token is never shown back: the field starts empty and
 * says whether one is set; Save keeps the stored token when the field is left empty; Clear token removes it.
 */
@Composable
private fun PeerSettings(s: Map<String, Any?>) {
    val sync = AppSync.of(DystopoApp.instance)
    var peer by remember { mutableStateOf(s["peerSetting"] as? String ?: "") }
    var token by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val tokenSet = s["tokenSet"] == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("Sync peer (empty: the online server ${AppSettings.server.ifEmpty { "(none set)" }})", style = TextStyle(color = Muted, fontSize = 12.sp))
        BasicTextField(
            peer, { peer = it }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            textStyle = TextStyle(color = Ink, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(6.dp)).padding(8.dp),
        )
        BasicText(if (tokenSet) "Token: set (type a new one to replace it)" else "Token: none (for a server that requires one)", style = TextStyle(color = Muted, fontSize = 12.sp))
        BasicTextField(
            token, { token = it.trim() }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            textStyle = TextStyle(color = Ink, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(6.dp)).padding(8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button("Save") {
                val p = peer.trim()
                val t = token
                Thread {
                    sync.setPeer(p)
                    if (t.isNotEmpty()) sync.setSyncToken(t)
                    note = "Saved" + if (t.isNotEmpty()) " (token set)" else ""
                    SyncUi.refresh()
                }.start()
                token = ""
            }
            if (tokenSet) Button("Clear token") {
                Thread { sync.setSyncToken(""); note = "Token cleared"; SyncUi.refresh() }.start()
            }
            note?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 12.sp)) }
        }
    }
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier.background(Color(0xEEFFFCF5), RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
