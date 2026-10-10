package io.github.jasondorjeshort.dystopo.app.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.attribution.Credits
import java.lang.ref.WeakReference

/**
 * About / attribution screen: every data source the open packs were built from (and the label font),
 * each with its required credit, license and links, composed by [AppCredits] from the packs'
 * metadata and the shared data source registry. Opened from the map's attribution line, or
 * `POST /attribution/open` in debug builds.
 */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true // T33: dark icons on the light page
        val credits = AppCredits.compose()
        setContent { AboutScreen(credits, onBack = { finish() }, open = ::openUrl) }
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // No browser on the device (e.g. a bare emulator image); the URL is shown on screen anyway.
        }
    }

    companion object {
        /** The open screen, if any; `POST /attribution/close` finishes it. */
        @Volatile var current: WeakReference<AboutActivity>? = null
    }
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val LinkColor = Color(0xFF1F5E8C)
private val Paper = Color(0xFFFFFCF5)
private val Card = Color(0xFFF3ECDD)

@Composable
private fun AboutScreen(credits: Credits, onBack: () -> Unit, open: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        BasicText("‹ Map", style = TextStyle(color = LinkColor, fontSize = 16.sp), modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp))
        BasicText("Map data and sources", style = TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        BasicText(credits.compact, style = TextStyle(color = Muted, fontSize = 13.sp))

        Heading("Region pack")
        if (credits.packs.isEmpty()) Body("No region pack is installed.")
        for (p in credits.packs) {
            CardColumn {
                BasicText("${if (p.role == "dem") "Elevation" else "Map data"}: ${p.name ?: p.file}", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                if (p.attribution.isNotEmpty()) Body(p.attribution)
                p.license?.let { Small("License: $it") }
                Small("Sources: " + p.registryIds.joinToString(", ").ifEmpty { "not recorded in the pack" })
                if (p.unknownIds.isNotEmpty()) Small("Not in this app's registry: " + p.unknownIds.joinToString(", "))
            }
        }

        Heading("Credits")
        for (c in credits.sources) {
            val s = c.source
            CardColumn {
                BasicText(s.name, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Small("${s.kind.json} · ${s.provider}")
                Body(s.attribution)
                Link("License: ${s.licenseName}", s.licenseUrl, open)
                AppCredits.homePage(s.attributionHtml)?.let { Link(it, it, open) }
                Small("Used by: " + c.usedBy.joinToString(", ") { if (it == "app") "map labels" else "$it pack" })
            }
        }

        Heading("App")
        Body("Dystopo ${BuildConfig.VERSION_NAME}, free software under the GNU GPL v3. Map rendering: MapLibre Native (BSD-2-Clause).")
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CardColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Card, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) { content() }
}

@Composable
private fun Heading(text: String) =
    BasicText(text, style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))

@Composable
private fun Body(text: String) = BasicText(text, style = TextStyle(color = Ink, fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Small(text: String) = BasicText(text, style = TextStyle(color = Muted, fontSize = 12.sp), modifier = Modifier.padding(top = 3.dp))

@Composable
private fun Link(text: String, url: String, open: (String) -> Unit) = BasicText(
    text,
    style = TextStyle(color = LinkColor, fontSize = 12.sp, textDecoration = TextDecoration.Underline),
    modifier = Modifier.clickable { open(url) }.padding(top = 4.dp),
)
