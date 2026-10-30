package io.github.jasondorjeshort.dystopo.app.tracking

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response

/**
 * T172: the battery-optimisation exemption, asked once on the first Record (the recorder idea's item 2; the research of
 * 2026-10-30: every recorder leaves it to the user, asked once with a plain explanation and a remembered "not now"; the
 * mechanism survey of the same day: Play allows the direct exemption dialog only for listed use cases, and a track recorder
 * is not one, so the card opens the system's own list, `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, which needs no
 * permission, and falls back to the app's details page). A card under the track chips says why in one sentence, with
 * "Open battery settings" and "Not now"; either answer is remembered in the `dystopo-battery` preferences, so the card shows
 * at most once. Already exempt ([exempt]): never shown. The pointer to the phone maker's own setting names no maker or app.
 * Settings › Navigation and tracking › Recording: Battery opens the same list at any time ([openSettings]).
 */
object BatteryExemption {
    private const val PREFS = "dystopo-battery"
    private const val KEY_ANSWER = "answer"
    const val ALLOW = "allow"
    const val NOT_NOW = "not-now"
    private const val TAG = "DystopoTracks"

    /** The card is shown (Compose state, main thread). */
    var cardOpen by mutableStateOf(false)

    /** Times the card was shown in this process, and what the last "Open battery settings" opened (`GET /tracks/battery`). */
    @Volatile var shown = 0L
        private set
    @Volatile var lastOpened: String? = null
        private set

    /** The user's answer ([ALLOW], [NOT_NOW]) or null when the card has not been answered. */
    fun answer(context: Context): String? = prefs(context).getString(KEY_ANSWER, null)

    /** True when the system already lets the app run without battery optimisation. */
    fun exempt(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)

    /** Whether a Record now should show the card: not exempt and never answered. */
    fun shouldAsk(context: Context): Boolean = answer(context) == null && !exempt(context)

    /** Called when a recording is started from the picker (main thread): shows the card when [shouldAsk]. */
    fun onRecord(context: Context) {
        if (!shouldAsk(context)) return
        cardOpen = true
        shown++
    }

    /** "Not now": remembered, the card closes and is not shown again. */
    fun notNow(context: Context) {
        prefs(context).edit().putString(KEY_ANSWER, NOT_NOW).apply()
        cardOpen = false
    }

    /** "Open battery settings": remembered, the card closes and the system's list opens. */
    fun allow(context: Context) {
        prefs(context).edit().putString(KEY_ANSWER, ALLOW).apply()
        cardOpen = false
        openSettings(context)
    }

    /**
     * Opens the system's list of apps and their battery optimisation, else this app's details page (where Battery is).
     * Returns which one opened, or null when neither could.
     */
    fun openSettings(context: Context): String? {
        val tries = listOf(
            "battery-optimisation-list" to Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            "app-details" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        for ((name, intent) in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                lastOpened = name
                return name
            } catch (e: Exception) {
                Log.w(TAG, "could not open $name", e)
            }
        }
        lastOpened = "none"
        return null
    }

    /** Forgets the answer (debug: `POST /tracks/battery/reset`), so the next Record asks again. */
    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_ANSWER).apply()
        cardOpen = false
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun json(context: Context): Map<String, Any?> = linkedMapOf(
        "exempt" to exempt(context), "answer" to answer(context), "shouldAsk" to shouldAsk(context),
        "cardOpen" to cardOpen, "shown" to shown, "lastOpened" to lastOpened,
    )

    /** The card's sentence and the pointer to the maker's own setting (ADR 0031: listed for the owner). */
    const val TITLE = "Keep recording with the screen off"
    const val TEXT = "To save battery, Android may stop an app that works in the background, which would end a long recording early. " +
        "In the list that opens, find Dystopo and turn off its battery optimisation."
    const val MAKER = "Some phones also have their own setting that puts apps to sleep, under Battery or Apps in the phone's " +
        "Settings (look for background activity, autostart or sleeping apps). If a recording stops by itself, allow Dystopo there too."

    /**
     * T172 debug routes, consulted by [io.github.jasondorjeshort.dystopo.app.TrackRoutes] first:
     * - `GET /tracks/battery`: `exempt` (the system's answer), `answer` (`allow`, `not-now` or null), `shouldAsk`,
     *   `cardOpen`, `shown` (this process), `lastOpened` (`battery-optimisation-list`, `app-details` or `none`).
     * - `POST /tracks/battery/answer?choice=allow|not-now`: taps the card's button (allow opens the system's list).
     * - `POST /tracks/battery/reset`: forgets the answer, so the next Record asks again.
     */
    fun handle(app: DystopoApp, r: Request): Response? {
        if (!r.path.startsWith("/tracks/battery")) return null
        when (r.method to r.path) {
            "GET" to "/tracks/battery" -> {}
            "POST" to "/tracks/battery/answer" -> when (r.query["choice"]) {
                ALLOW -> app.map.onMain { allow(app) }
                NOT_NOW -> app.map.onMain { notNow(app) }
                else -> return Response.json(mapOf("error" to "choice must be $ALLOW or $NOT_NOW"), 400)
            }
            "POST" to "/tracks/battery/reset" -> app.map.onMain { reset(app) }
            else -> return Response.notFound()
        }
        return Response.json(app.map.onMain { json(app) })
    }
}

/** T172: the card [BatteryExemption.onRecord] opens, under the track chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatteryExemptionCard() {
    if (!BatteryExemption.cardOpen) return
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(Color(0xF5FFFCF5), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(BatteryExemption.TITLE, style = TextStyle(color = CardInk, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        BasicText(BatteryExemption.TEXT, style = TextStyle(color = CardInk, fontSize = 13.sp))
        BasicText(BatteryExemption.MAKER, style = TextStyle(color = CardMuted, fontSize = 12.sp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardAction("Open battery settings") { BatteryExemption.allow(context) }
            CardAction("Not now") { BatteryExemption.notNow(context) }
        }
    }
}

@Composable
private fun CardAction(text: String, onClick: () -> Unit) {
    BasicText(text, Modifier.clickable(onClick = onClick).padding(vertical = 6.dp), style = TextStyle(color = CardLink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
}

private val CardInk = Color(0xFF2B2420)
private val CardMuted = Color(0xFF6B5A4A)
private val CardLink = Color(0xFF2C7BB6)
