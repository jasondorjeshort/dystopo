package io.github.jasondorjeshort.dystopo.app.firstrun

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import io.github.jasondorjeshort.dystopo.app.online.DirectPrefs
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import kotlin.concurrent.thread

/**
 * T80: the rows of Settings › Sources about the phone's own source reading (no server set): mobile data on or off
 * (off by default: a fresh view is 75 to 100 MB) and the per-session download limit. The same settings are
 * `directOnMobileData` and `directSessionMb` in `POST /settings`.
 */
@Composable
fun DirectSourcesRows() {
    val app = DystopoApp.instance
    var mobile by remember { mutableStateOf(DirectPrefs.onMobileData(app)) }
    var mb by remember { mutableStateOf(DirectPrefs.sessionMb(app)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("With no server the phone reads the public sources itself.", style = TextStyle(color = Muted, fontSize = 11.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Mobile data", style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            for ((label, value) in listOf("Wi-Fi only" to false, "Allow" to true)) {
                Choice(label, mobile == value) {
                    mobile = value
                    thread(name = "dystopo-direct-setting") { OnlineService.get(app).setDirect(null, value) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Limit per session", style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            for (value in (DirectPrefs.SESSION_MB_CHOICES + mb).distinct().sorted()) {
                Choice(if (value >= 1000) "${value / 1000} GB" else "$value MB", mb == value) {
                    mb = value
                    thread(name = "dystopo-direct-setting") { OnlineService.get(app).setDirect(value, null) }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        softWrap = false,
        style = TextStyle(color = if (selected) Paper else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(if (selected) Accent else Color(0xEEFFFCF5), RoundedCornerShape(16.dp))
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
