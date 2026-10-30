package io.github.jasondorjeshort.dystopo.app.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile

/**
 * T172: the recording settings the user sets per [Activity] (ADR 0044; the research of 2026-10-30,
 * `out/wave22-research/T158-track-recording.md`, and the interval survey of the same day): the accuracy bound, auto-pause,
 * the network fallback and the interval, over [ActivityProfile.of]'s defaults. Pure (unit-tested in
 * `RecordingProfilesTest`).
 *
 * Each activity's choices are one synced settings value, `trackProfile.<code>` (synced as `app.trackProfile.<code>`), in
 * [ActivityProfile.encode]'s `key=value;` form with the four keys this row set edits only, always all four:
 * `acc=50;autopause=0;net=1;interval=1000` (`acc=none`: no bound). Unset, the activity's defaults hold. A value's other
 * keys are dropped ([parseValue]), so a synced value can never move a bound the user cannot see (the speed bound, the gap
 * rule, the watchdog stay the defaults).
 */
object RecordingProfiles {
    /** The settings key of [a]'s profile value. */
    fun key(a: Activity) = "trackProfile.${a.code}"

    val KEYS: Set<String> = Activity.entries.map(::key).toSet()

    fun activityOfKey(key: String): Activity? = Activity.entries.firstOrNull { key(it) == key }

    /** The accuracy bound's choices, metres (null: off), from the research: off, 20, 50, 100, 200 m. */
    val ACCURACY_CHOICES: List<Double?> = listOf(null, 20.0, 50.0, 100.0, 200.0)

    /** The interval's choices, seconds (the interval survey of 2026-10-30: every second by default, 5 s as OsmAnd's default, 1 min the long end). */
    val INTERVAL_CHOICES_S: List<Int> = listOf(1, 2, 5, 10, 30, 60)

    /** What a value or a debug request may set: any bound or interval in these ranges (the rows offer the choices above). */
    val ACCURACY_RANGE_M = 1.0..10_000.0
    val INTERVAL_RANGE_MS = 1_000L..3_600_000L

    /** The user-visible name of an activity (ADR 0031: built in alpha, adjusted by the owner). */
    fun label(a: Activity): String = when (a) {
        Activity.FOOT -> "On foot"
        Activity.BIKE -> "Bike"
        Activity.ROAD -> "Car or bus"
        Activity.RAIL -> "Train"
        Activity.BOAT -> "Boat"
        Activity.AIR -> "Flying"
    }

    /** [a]'s profile: its defaults with the [stored] value's four choices over them. */
    fun profile(a: Activity, stored: String?): ActivityProfile =
        if (stored.isNullOrBlank()) ActivityProfile.of(a) else ActivityProfile.decode("activity=${a.code};$stored") ?: ActivityProfile.of(a)

    /** The value stored for [p]: its four user choices. */
    fun encode(p: ActivityProfile): String = buildString {
        append("acc=").append(p.accuracyBoundM?.let(::num) ?: "none")
        append(";autopause=").append(if (p.autoPause) 1 else 0)
        append(";net=").append(if (p.networkFallback) 1 else 0)
        append(";interval=").append(p.intervalMs)
    }

    private fun num(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    /**
     * Checks a stored or synced value and returns it in the form [encode] writes (the four keys, the others dropped), or
     * null when a known key has a value out of range or the value has none of the four keys. Missing keys take [a]'s defaults.
     */
    fun parseValue(a: Activity, raw: String): String? {
        val m = raw.split(';').mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it).trim() to kv.substring(it + 1).trim() } }.toMap()
        if (m.keys.none { it in VALUE_KEYS }) return null
        m["acc"]?.let { v -> if (v != "none" && v.toDoubleOrNull()?.takeIf { it in ACCURACY_RANGE_M } == null) return null }
        m["interval"]?.let { v -> if (v.toLongOrNull()?.takeIf { it in INTERVAL_RANGE_MS } == null) return null }
        for (k in listOf("autopause", "net")) m[k]?.let { v -> if (v != "0" && v != "1") return null }
        return encode(profile(a, m.filterKeys { it in VALUE_KEYS }.entries.joinToString(";") { "${it.key}=${it.value}" }))
    }

    private val VALUE_KEYS = setOf("acc", "autopause", "net", "interval")

    /** A change of some of the four choices, applied to the remembered activity's profile ([SettingsRoutes.Parsed.Ok.trackEdit]). */
    data class Edit(
        val accuracyGiven: Boolean = false,
        /** Metres; null with [accuracyGiven]: no bound. */
        val accuracyM: Double? = null,
        val autoPause: Boolean? = null,
        val networkFallback: Boolean? = null,
        val intervalMs: Long? = null,
    ) {
        val isEmpty: Boolean get() = !accuracyGiven && autoPause == null && networkFallback == null && intervalMs == null

        fun applyTo(p: ActivityProfile): ActivityProfile = p.copy(
            accuracyBoundM = if (accuracyGiven) accuracyM else p.accuracyBoundM,
            autoPause = autoPause ?: p.autoPause,
            networkFallback = networkFallback ?: p.networkFallback,
            intervalMs = intervalMs ?: p.intervalMs,
        )
    }

    /** `POST /settings`'s `trackAccuracyM` (`off` or metres), `trackAutoPause`, `trackNetworkFallback` (1/0) and `trackIntervalS`: null when none was given. Throws IllegalArgumentException for a bad value. */
    fun parseEdit(params: Map<String, Any?>): Edit? {
        var e = Edit()
        params["trackAccuracyM"]?.let { raw ->
            val s = raw.toString().trim().lowercase()
            e = if (s == "off" || s == "none" || s == "0") e.copy(accuracyGiven = true, accuracyM = null) else {
                val v = (raw as? Number)?.toDouble() ?: s.toDoubleOrNull()
                require(v != null && v in ACCURACY_RANGE_M) { "trackAccuracyM must be off or ${ACCURACY_RANGE_M.start.toInt()} to ${ACCURACY_RANGE_M.endInclusive.toInt()} m (got $raw)" }
                e.copy(accuracyGiven = true, accuracyM = v)
            }
        }
        params["trackAutoPause"]?.let { raw -> e = e.copy(autoPause = flag(raw) ?: throw IllegalArgumentException("trackAutoPause must be 1 or 0 (got $raw)")) }
        params["trackNetworkFallback"]?.let { raw -> e = e.copy(networkFallback = flag(raw) ?: throw IllegalArgumentException("trackNetworkFallback must be 1 or 0 (got $raw)")) }
        params["trackIntervalS"]?.let { raw ->
            val v = (raw as? Number)?.toDouble() ?: raw.toString().trim().toDoubleOrNull()
            val ms = v?.let { Math.round(it * 1000) }
            require(ms != null && ms in INTERVAL_RANGE_MS) { "trackIntervalS must be 1 to 3600 s (got $raw)" }
            e = e.copy(intervalMs = ms)
        }
        return e.takeUnless { it.isEmpty }
    }

    private fun flag(raw: Any): Boolean? = when (raw.toString().trim().lowercase()) {
        "1", "true", "on", "yes" -> true
        "0", "false", "off", "no" -> false
        else -> null
    }

    /** The four choices of [p] as `GET /settings` and `GET /tracks` report them. */
    fun json(p: ActivityProfile): Map<String, Any?> = linkedMapOf(
        "activity" to p.activity.code, "accuracyM" to p.accuracyBoundM, "autoPause" to p.autoPause,
        "networkFallback" to p.networkFallback, "intervalS" to p.intervalMs / 1000.0, "value" to encode(p),
    )
}

/**
 * T172: the activity picker (Garmin and Locus tie the recording settings to an activity, the research of 2026-10-30):
 * Record opens it with the remembered activity ([AppSettings.trackActivity]) marked; a choice starts the recording with
 * that activity's profile and becomes the remembered one (synced, `app.trackActivity`; T161's readout reads it). The same
 * panel changes a track's activity afterwards: the recording one's (which then records with that activity's profile and
 * remembers it) from the recording panel, any other's from the Tracks list (the profile it was recorded with is kept).
 */
object ActivityPicker {
    /** What the picker opens for: [NEW] (Record was tapped), or a track's id. */
    const val NEW = "new"

    /** Null: closed. Compose state, main thread. */
    var target by mutableStateOf<String?>(null)

    /** The name of the track [target] names, for the panel's title. */
    var targetName by mutableStateOf<String?>(null)

    /** The activity the panel marks. */
    var marked by mutableStateOf<Activity?>(null)

    /** The last choice (`GET /tracks/picker`). */
    @Volatile var lastChoice: Map<String, Any?>? = null
        private set

    /** Opens the picker for a new recording, the remembered activity marked (main thread). */
    fun openForRecord() {
        target = NEW
        targetName = null
        marked = AppSettings.trackActivity
    }

    /** Opens the picker for a stored track (main thread). */
    fun openForTrack(id: String, name: String, current: Activity?) {
        target = id
        targetName = name
        marked = current
    }

    fun close() {
        target = null
        targetName = null
        marked = null
    }

    /** The remembered activity becomes [a] (through the settings, so it syncs). Off the main thread. */
    fun remember(app: DystopoApp, a: Activity) {
        if (AppSettings.trackActivity != a) SettingsRoutes(app).apply(SettingsRoutes.Parsed.Ok(null, false, null, null, trackActivity = a))
    }

    /**
     * A choice for [target] (off the main thread): a new recording starts with [a], or the track's activity changes.
     * Returns what happened, for the route.
     */
    fun choose(app: DystopoApp, target: String, a: Activity): Map<String, Any?> {
        val t = Tracking.of(app)
        val result: Map<String, Any?> = if (target == NEW) {
            remember(app, a)
            val s = t.start(null, Tracking.Mode.ALL, a)
            linkedMapOf("started" to s.id, "activity" to a.code)
        } else {
            val recordingIt = t.call { t.recorder.activeId } == target
            if (recordingIt) remember(app, a)
            linkedMapOf("track" to target, "changed" to t.setActivity(target, a), "recording" to recordingIt, "activity" to a.code)
        }
        lastChoice = result + mapOf("at" to System.currentTimeMillis())
        return result
    }

    /** `GET /tracks/picker`: what the panel shows. Main thread. */
    fun json(): Map<String, Any?> = linkedMapOf(
        "open" to (target != null), "target" to target, "title" to target?.let { title() },
        "marked" to marked?.code, "remembered" to AppSettings.trackActivity.code,
        "choices" to Activity.entries.map { linkedMapOf("activity" to it.code, "label" to RecordingProfiles.label(it)) },
        "lastChoice" to lastChoice,
    )

    private fun title(): String = if (target == NEW) "Record as" else "Activity of ${targetName ?: "this track"}"

    private val trackActivityPath = Regex("^/tracks/([0-9a-fA-F-]{36})/activity$")

    /**
     * T172 debug routes, consulted by [io.github.jasondorjeshort.dystopo.app.TrackRoutes] first:
     * - `GET /tracks/picker`: the panel (`open`, `target`: `new` or a track id, `title`, `marked`, `remembered`, `choices`
     *   with their labels, `lastChoice`).
     * - `POST /tracks/picker?open=new|<track id>|close`: opens it as the Record chip does (`new`) or as a track's activity
     *   line does, or closes it; `POST /tracks/picker/choose?activity=foot|bike|road|rail|boat|air` taps a choice.
     * - `POST /tracks/{id}/activity?activity=`: changes a track's activity (the recording one also takes that activity's
     *   profile and makes it the remembered one).
     */
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path == "/tracks/picker" && r.method == "GET") return Response.json(app.map.onMain { json() })
        if (r.path == "/tracks/picker" && r.method == "POST") {
            val open = r.query["open"]?.trim().orEmpty()
            val t = Tracking.of(app)
            when (open) {
                "", NEW -> app.map.onMain { TrackBar.open = true; openForRecord() }
                "close" -> app.map.onMain { close() }
                else -> {
                    val s = t.call { t.store.summary(open) } ?: return Response.json(mapOf("error" to "open must be new, close or a track id (no track $open)"), 404)
                    app.map.onMain { TrackBar.open = true; openForTrack(s.id, s.name, s.activity) }
                }
            }
            return Response.json(app.map.onMain { json() })
        }
        if (r.path == "/tracks/picker/choose" && r.method == "POST") {
            val a = activity(r) ?: return badActivity(r)
            val target = app.map.onMain { target } ?: return Response.json(mapOf("error" to "the picker is not open"), 409)
            app.map.onMain { close() }
            return Response.json(choose(app, target, a))
        }
        val m = trackActivityPath.matchEntire(r.path) ?: return null
        if (r.method != "POST") return Response.json(mapOf("error" to "POST"), 405)
        val a = activity(r) ?: return badActivity(r)
        val t = Tracking.of(app)
        if (t.call { t.store.summary(m.groupValues[1]) } == null) return Response.json(mapOf("error" to "no track ${m.groupValues[1]}"), 404)
        return Response.json(choose(app, m.groupValues[1], a))
    }

    private fun activity(r: Request): Activity? = Activity.of(r.query["activity"]?.trim()?.lowercase())

    private fun badActivity(r: Request) =
        Response.json(mapOf("error" to "activity must be one of ${Activity.entries.map { it.code }} (got ${r.query["activity"]})"), 400)
}

/**
 * T172: the picker's panel under the track chips, while [ActivityPicker.target] is set: a title ("Record as", or "Activity
 * of <track>"), the six activities with the marked one filled, and Cancel. [onRecordStarted]: what a new recording asks
 * once (the notification permission, the battery exemption), from [TrackControls], which holds the permission's launcher.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityPickerPanel(onRecordStarted: () -> Unit) {
    val target = ActivityPicker.target ?: return
    val app = DystopoApp.instance
    Column(
        Modifier.widthIn(max = 300.dp).background(PickerPaper, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            if (target == ActivityPicker.NEW) "Record as" else "Activity of ${ActivityPicker.targetName ?: "this track"}",
            style = TextStyle(color = PickerInk, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
        if (target == ActivityPicker.NEW) {
            BasicText("Sets the recording's accuracy limit, auto-pause and interval; change them in Settings.", style = TextStyle(color = PickerMuted, fontSize = 11.sp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in Activity.entries) {
                val on = a == ActivityPicker.marked
                BasicText(
                    RecordingProfiles.label(a),
                    softWrap = false,
                    style = TextStyle(color = if (on) Color.White else PickerInk, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier
                        .background(if (on) PickerAccent else Color.White, RoundedCornerShape(16.dp))
                        .border(1.dp, if (on) PickerAccent else PickerLine, RoundedCornerShape(16.dp))
                        .clickable {
                            ActivityPicker.close()
                            Thread { ActivityPicker.choose(app, target, a) }.start()
                            if (target == ActivityPicker.NEW) onRecordStarted()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        BasicText("Cancel", Modifier.clickable { ActivityPicker.close() }.padding(vertical = 4.dp), style = TextStyle(color = PickerLink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}

private val PickerInk = Color(0xFF2B2420)
private val PickerMuted = Color(0xFF6B5A4A)
private val PickerPaper = Color(0xEEFFFCF5)
private val PickerAccent = Color(0xFF7A4E2D)
private val PickerLine = Color(0xFFE2D8C6)
private val PickerLink = Color(0xFF2C7BB6)
