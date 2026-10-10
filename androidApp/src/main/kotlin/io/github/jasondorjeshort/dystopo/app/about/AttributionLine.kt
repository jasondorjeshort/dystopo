package io.github.jasondorjeshort.dystopo.app.about

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
/**
 * T41: how much of the bottom of the map the credit line takes (its height plus its padding to the
 * window edge, above the navigation bar), so panels at the bottom (the track profile) sit above it.
 */
object AttributionInsets {
    var bottom by mutableStateOf(0.dp)
}

@Composable
fun AttributionLine(fallback: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val credits = runCatching { AppCredits.compose(DystopoApp.instance) }.getOrNull()
    val text = credits?.takeIf { it.packs.isNotEmpty() }?.compact ?: HtmlText.toPlain(fallback)
    BasicText(
        text,
        style = TextStyle(color = Color(0xFF3B2A1A), fontSize = 11.sp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .onGloballyPositioned { c ->
                // The line sits at the bottom-left; its top edge measured from the window bottom is what panels must clear.
                val fromBottom: Dp = with(density) { (rootHeight(c) - c.positionInRoot().y).toDp() }
                if (fromBottom != AttributionInsets.bottom) AttributionInsets.bottom = fromBottom
            }
            .background(Color(0xCCF6F1E4), RoundedCornerShape(4.dp))
            .clickable { context.startActivity(Intent(context, AboutActivity::class.java)) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Height of the window the layout is in, in pixels. */
private fun rootHeight(c: androidx.compose.ui.layout.LayoutCoordinates): Float {
    var r = c
    while (true) r = r.parentLayoutCoordinates ?: return r.size.height.toFloat()
}
