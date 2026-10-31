package io.github.jasondorjeshort.dystopo.app.viewshed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.dem.PeakSort

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B625A)
private val Paper = Color(0xEEFFFCF5)
private val Link = Color(0xFF2C7BB6)

/** The peak list's most height, dp. */
private const val LIST_MAX_DP = 150

/**
 * T182: the viewshed's sheet at the bottom of the map: the title and Close, the status (looking, or the range, the
 * peaks in view and the time), the legend and, when the data stopped, what the hatch means; the sort (Distance, the
 * default, Elevation, Bearing) and the peaks in view, one row each (name; elevation, distance, true bearing with its
 * compass letters, "on the skyline"); a tap shows the peak on the map ([ViewshedSession.select]). Hidden when no
 * viewshed is open. The list scrolls within [LIST_MAX_DP] (three or four rows) so the map above keeps most of the screen:
 * at 260 dp the sheet covered half a phone's height and the overlay with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewshedSheet() {
    if (!ViewshedSession.open) return
    LaunchedEffect(Unit) { DystopoApp.instance.map.mapView?.let { ViewshedSession.overlay.bind(it) } } // the activity was recreated
    val units = AppSettings.units
    val done = ViewshedSession.done
    Column(
        Modifier.fillMaxWidth().onGloballyPositioned { ViewshedSession.sheetTopPx = it.positionInWindow().y }
            .background(Paper, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(ViewshedText.TITLE, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Action(ViewshedText.CLOSE) { ViewshedSession.close() }
        }
        val status = when {
            ViewshedSession.error != null -> ViewshedSession.error!!
            ViewshedSession.running -> ViewshedText.looking(ViewshedSession.rangeKm, units)
            done != null -> ViewshedText.done(done.rangeKm, units, done.result.ms, done.inView.size)
            else -> ""
        }
        BasicText(status, style = TextStyle(color = Ink, fontSize = 13.sp))
        if (done != null) {
            val legend = if (done.result.noDataKm2 > 0.5) ViewshedText.LEGEND + " " + ViewshedText.DATA_STOPPED else ViewshedText.LEGEND
            BasicText(legend, style = TextStyle(color = Muted, fontSize = 11.sp))
            val listed = ViewshedSession.listed()
            if (listed.isEmpty()) {
                BasicText(if (done.peakTiles == 0) ViewshedText.NO_PEAKS_LAYER else ViewshedText.NO_PEAKS, style = TextStyle(color = Ink, fontSize = 13.sp))
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.Center) {
                    BasicText(ViewshedText.SORT, Modifier.padding(vertical = 6.dp), style = TextStyle(color = Muted, fontSize = 13.sp))
                    for (s in PeakSort.entries) {
                        val on = ViewshedSession.sort == s
                        BasicText(
                            s.label,
                            style = TextStyle(color = if (on) Ink else Link, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                            modifier = Modifier.clickable { ViewshedSession.sortBy(s) }.padding(vertical = 6.dp),
                        )
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = LIST_MAX_DP.dp)) {
                    items(listed, key = { "${it.peak.name}/${it.peak.lat}/${it.peak.lon}" }) { p ->
                        val picked = ViewshedSession.selected == p
                        Column(Modifier.fillMaxWidth().clickable { ViewshedSession.select(p) }.padding(vertical = 3.dp)) {
                            BasicText(p.peak.name, style = TextStyle(color = if (picked) Link else Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                            BasicText(ViewshedText.peakLine(p, units), style = TextStyle(color = Muted, fontSize = 12.sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
    )
}
