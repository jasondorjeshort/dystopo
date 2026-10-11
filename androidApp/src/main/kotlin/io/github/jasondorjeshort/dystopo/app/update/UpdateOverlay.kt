package io.github.jasondorjeshort.dystopo.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import kotlin.concurrent.thread

/**
 * T83: the update offer on the map, above the credit line: "Update to <version>" with Install and Not now once a
 * download has been verified ([Updater]), or why an update was refused (checksum or certificate) with OK. Hidden
 * while the settings sheet is open (its Updates row has the same buttons) and after the user answered.
 */
@Composable
fun UpdateOverlay() {
    if (!Updater.initialized() || SettingsUi.visible) return
    val s = Updater.state
    val refusedShown = s.phase == Updater.Phase.REFUSED && s.tag != null && s.tag != Updater.acknowledgedTag
    if (s.phase != Updater.Phase.READY && s.phase != Updater.Phase.INSTALLING && !refusedShown) return
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 34.dp).widthIn(max = 420.dp)
                .background(Paper, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (refusedShown) {
                BasicText(s.message ?: "Update refused", style = TextStyle(color = Red, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button("OK", true) { Updater.acknowledge() } }
            } else {
                BasicText("Update to ${s.versionName ?: s.tag}", style = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                BasicText(
                    if (s.phase == Updater.Phase.INSTALLING) "Opening the installer..." else "Downloaded and checked (signature and checksum). Your tracks, trips, packs and settings stay.",
                    style = TextStyle(color = Muted, fontSize = 12.sp),
                )
                if (s.phase == Updater.Phase.READY) {
                    s.message?.takeIf { it.startsWith("Install failed") }?.let { BasicText(it, style = TextStyle(color = Red, fontSize = 12.sp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button("Install", true) { thread(name = "dystopo-update-install") { Updater.install() } }
                        Button("Not now", false) { Updater.decline() }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Button(label: String, primary: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        softWrap = false,
        style = TextStyle(color = if (primary) Paper else Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(if (primary) Accent else Color(0xEEFFFCF5), RoundedCornerShape(16.dp))
            .border(1.dp, if (primary) Accent else Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

internal val Ink = Color(0xFF2B2420)
internal val Muted = Color(0xFF6B5A4A)
internal val Paper = Color(0xFFFFFCF5)
internal val Accent = Color(0xFF7A4E2D)
internal val Line = Color(0xFFE2D8C6)
internal val Red = Color(0xFFB0302A)
