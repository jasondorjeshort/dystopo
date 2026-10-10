package io.github.jasondorjeshort.dystopo.app.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp

private val Link = Color(0xFF2C7BB6)

/**
 * "Sync now" in the tracks list (T44): one sync with the default peer ([AppSync.defaultPeer]) off
 * the main thread; the result shows in the list's message line.
 */
@Composable
fun SyncNowAction() {
    BasicText(
        "Sync now",
        style = TextStyle(color = Link, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable {
            val sync = AppSync.of(DystopoApp.instance)
            if (!sync.running) Thread { runCatching { sync.syncNow() } }.start()
        }.padding(vertical = 4.dp),
    )
}
