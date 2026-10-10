package io.github.jasondorjeshort.dystopo.app.about

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.attribution.HtmlText

/**
 * The always-visible credit line on the map: the composed compact attribution (plain text, no HTML
 * entities), tapping it opens [AboutActivity]. [fallback] (the packs' raw attribution) is shown,
 * unescaped, only when no pack credits could be composed.
 */
@Composable
fun AttributionLine(fallback: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val credits = runCatching { AppCredits.compose(DystopoApp.instance) }.getOrNull()
    val text = credits?.takeIf { it.packs.isNotEmpty() }?.compact ?: HtmlText.toPlain(fallback)
    BasicText(
        text,
        style = TextStyle(color = Color(0xFF3B2A1A), fontSize = 11.sp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color(0xCCF6F1E4), RoundedCornerShape(4.dp))
            .clickable { context.startActivity(Intent(context, AboutActivity::class.java)) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
