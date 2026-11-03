package io.github.jasondorjeshort.dystopo.app.divergence

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu
import io.github.jasondorjeshort.dystopo.routing.DivergenceText

private val Ink = Color(0xFF2B2420)
private val Teal = Color(0xFF00838F)
private val Link = Color(0xFF2C7BB6)

/** A press within this of a stretch's line opens it, or a fingertip at the map's zoom when that is wider (metres). */
const val PRESS_MIN_M = 30.0

/** How far from the pressed point the card looks for a stretch, metres, at the map's zoom (main thread). */
fun pressRadiusM(lat: Double): Double {
    val metersPerDp = runCatching { DystopoApp.instance.map.map?.projection?.getMetersPerPixelAtLatitude(lat) }.getOrNull() ?: 0.0
    return maxOf(PRESS_MIN_M, metersPerDp * LongPressMenu.FINGER_DP)
}

/**
 * T213: the long-press card's lines when the press is on a stretch the user's tracks agree on ([DivergenceStore.near]):
 * "Your tracks leave the mapped trail here", then "2 of your tracks follow a line about 140 m long that is not on the map"
 * ([DivergenceText]), with Dismiss (kept per stretch) and the help icon. Nothing when the press is elsewhere.
 */
@Composable
fun DivergenceCardLine() {
    @Suppress("UNUSED_VARIABLE") val rev = DivergenceStore.revision
    val (lat, lon) = LongPressMenu.point ?: return
    val d = DivergenceStore.near(lat, lon, pressRadiusM(lat)) ?: return
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(DivergenceText.TITLE, Modifier.weight(1f), style = TextStyle(color = Teal, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            HelpIcon("longPress.divergence")
        }
        BasicText(DivergenceText.body(d, AppSettings.units), style = TextStyle(color = Ink, fontSize = 13.sp))
        BasicText(
            DivergenceText.DISMISS,
            style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.clickable { DivergenceStore.dismiss(d.id) }.padding(vertical = 6.dp),
        )
    }
}

/** T213: the help pop-up of the card's lines above (the survey's hedged wording: a line not on the map, not a mistake). */
internal val DIVERGENCE_HELP: List<HelpText> = listOf(
    HelpText("longPress.divergence", "Your tracks and the map", "The map: long-press a dashed teal mark") {
        "Your recorded tracks keep following a line the map does not have here: perhaps a reroute, a new switchback or an " +
            "unmapped path, or perhaps a GPS that strayed. It needs two tracks on different days, or one with a good fix " +
            "all the way. Dismiss hides it. Nothing is sent."
    },
)
