package io.github.jasondorjeshort.dystopo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.settings.GroupList
import io.github.jasondorjeshort.dystopo.app.settings.GroupPage
import io.github.jasondorjeshort.dystopo.app.settings.HeaderLink
import io.github.jasondorjeshort.dystopo.app.settings.Ink
import io.github.jasondorjeshort.dystopo.app.settings.Line
import io.github.jasondorjeshort.dystopo.app.settings.Muted
import io.github.jasondorjeshort.dystopo.app.settings.Option
import io.github.jasondorjeshort.dystopo.app.settings.Paper
import io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup
import io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout
import io.github.jasondorjeshort.dystopo.app.firstrun.Screens
import kotlin.concurrent.thread

/**
 * Whether the settings sheet is open (the Settings chip, or `POST /settings/ui?open=1` and `POST /settings/open`
 * in debug builds), and T103: which group's page it shows.
 */
object SettingsUi {
    private var shown by mutableStateOf(false)

    /** Open or closed; closing forgets the page, so the Settings chip opens on the list of groups again. */
    var visible: Boolean
        get() = shown
        set(v) {
            shown = v
            if (!v) group = null
        }

    /** T103: the open group's page, or null for the list of groups (two panes: null shows [SettingsLayout.SERVER_GROUP]). */
    var group by mutableStateOf<SettingsGroup?>(null)

    /** T103: whether the last frame showed the list and a page side by side (a window at least [TWO_PANE_MIN_WIDTH] wide). */
    @Volatile var twoPane: Boolean? = null
        internal set

    /** T103: opens the sheet on [group]'s page (null: the list). Main thread. */
    fun open(group: SettingsGroup?) {
        this.group = group
        shown = true
    }

    /** Bumped after a change is applied, so the sheet re-reads [AppSettings]. */
    var revision by mutableStateOf(0)
    var busy by mutableStateOf(false)
}

/** T103: from this window width the list of groups and the open page sit side by side (tablets, phones in landscape not). */
private val TWO_PANE_MIN_WIDTH = 720.dp

/**
 * The settings sheet at the bottom of the map. T103: grouped by theme ([SettingsGroup]: Data and storage, Map
 * options, Units and display, Navigation and tracking, Sync and sharing, About and advanced). It opens on the
 * list of groups and a tap opens a group's page; on a wide window the list and the page sit side by side. A
 * list rather than tabs because six tab labels do not fit across a phone at font scale 2.0 (a scrolling tab
 * strip hides most of them), while list rows wrap at any text size. Changes go through the same
 * [SettingsRoutes.apply] as `POST /settings`.
 */
@Composable
fun SettingsSheet() {
    if (!SettingsUi.visible) return
    @Suppress("UNUSED_VARIABLE") val rev = SettingsUi.revision // recompose after apply
    val group = SettingsUi.group

    fun change(parsed: SettingsRoutes.Parsed.Ok) {
        if (SettingsUi.busy) return
        SettingsUi.busy = true
        thread(name = "dystopo-settings-ui") {
            try {
                SettingsRoutes(DystopoApp.instance).apply(parsed)
            } finally {
                SettingsUi.busy = false
                SettingsUi.revision++
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= TWO_PANE_MIN_WIDTH
        SideEffect { SettingsUi.twoPane = wide }
        // Back: from a page to the list (one pane), else closes the sheet.
        BackHandler { if (group != null && !wide) SettingsUi.group = null else SettingsUi.visible = false }
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 960.dp).fillMaxWidth().navigationBarsPadding().padding(8.dp)
                .heightIn(max = min(640.dp, maxHeight * 0.8f)) // T47: the map stays in sight above the sheet
                .background(Paper, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (group != null && !wide) {
                    HeaderLink("‹ " + Screens.SETTINGS, Modifier.weight(1f)) { SettingsUi.group = null }
                } else {
                    BasicText(Screens.SETTINGS, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                }
                if (SettingsUi.busy) BasicText("applying...", Modifier.padding(end = 12.dp), style = TextStyle(color = Muted, fontSize = 12.sp))
                Option("Close", false) { SettingsUi.visible = false }
            }
            if (wide) {
                val shown = group ?: SettingsLayout.SERVER_GROUP
                Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                        GroupList(shown) { SettingsUi.group = it }
                    }
                    Column(Modifier.weight(1f)) { key(shown) { Page(shown, ::change) } }
                }
            } else if (group != null) {
                Column(Modifier.weight(1f, fill = false)) { key(group) { Page(group, ::change) } }
            } else {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    GroupList(null) { SettingsUi.group = it }
                }
            }
        }
    }
}

/** A group's title and its rows, scrolling under the sheet's header. */
@Composable
private fun Page(group: SettingsGroup, change: (SettingsRoutes.Parsed.Ok) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(group.title, style = TextStyle(color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold))
        GroupPage(group, change)
    }
}
