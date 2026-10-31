package io.github.jasondorjeshort.dystopo.app.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.DirectionsText
import io.github.jasondorjeshort.dystopo.routing.Instruction
import io.github.jasondorjeshort.dystopo.routing.TravelMode

/**
 * T126: spoken turn directions. While navigating with the `navSpeech` setting on ([AppSettings.navSpeech], off by
 * default; Settings › Navigation and tracking › Spoken directions), each direction the card shows (the default
 * detail level, priorities 1 and 2, [Directions.shown]) is said once, when the card turns it to the alert colour:
 * the first fix within its lead distance ([Instruction.leadM]). The words are the card's ([SpokenText]): the distance
 * as the card writes it in the units preference, then the direction with its "then" cue, "In 300 m, turn left onto
 * Stevens Canyon Road, then turn right"; at the turn itself (closer than [Directions.passM]) the direction alone. The
 * arrival is one of the directions ("In 150 m, arrive at the end of the route"); nothing else is spoken (off-route
 * and other cues are later work, the detail level too).
 *
 * The engine is the platform text-to-speech ([PlatformEngine]), created on the first spoken direction and shut down
 * when navigation stops; other audio is ducked while it speaks (transient audio focus with ducking, released after the
 * last queued line). With the screen off it goes on speaking as long as fixes reach navigation (a recording's foreground service keeps them
 * coming; without one Android's background location limits thin them out: a navigation service is later work). Debug builds can swap
 * in a [SilentEngine] (`POST /nav/speech?engine=silent`) that only records, so the emulator check needs no sound.
 * `GET /nav/speech` lists what was spoken since navigation started (`NavRoutes.kt`).
 *
 * T137: a mute for this navigation only ([setMuted], the speaker button on the card's header): the `navSpeech` setting
 * stays on, a direction due while muted is listed with `muted` and never reaches the engine (it counts as said, so
 * unmuting does not say it late), and the next navigation starts unmuted. When the engine cannot speak (no
 * text-to-speech engine, or none with a voice for the directions' English: [failed]) the card shows a notice once per
 * navigation and hides the mute button; debug builds fake both with `engine=failing|novoice`.
 */
object NavSpeech {
    /** What was said: when, the index of the direction in the shown list, the card's words and what the engine got. */
    data class Line(val atMs: Long, val index: Int, val text: String, val direction: String, val voice: String, val engine: String, val muted: Boolean = false)

    /** T137: what the card shows of speech (Compose state, written on the main thread): the mute and the notice. */
    data class Ui(val muted: Boolean = false, val notice: String? = null, val failure: String? = null)

    /** T137: the notice's words when the engine cannot speak (ADR 0031: the owner's to adjust). */
    const val NOTICE_NO_ENGINE = "No text-to-speech engine for spoken directions. Install one in Android text-to-speech settings."
    const val NOTICE_NO_VOICE = "No voice installed for spoken directions. Install one in Android text-to-speech settings."

    interface Engine {
        val name: String
        /** T137: called once, after it became the current engine (so a failure it reports is taken). */
        fun open() {}
        fun speak(voice: String, id: String)
        fun stop()
        fun shutdown()
        fun json(): Map<String, Any?>
    }

    private const val MAX_LINES = 500

    private val lines = ArrayList<Line>()
    private val gate = SpeechGate()
    @Volatile private var engine: Engine? = null
    /** Debug only: `silent` records without audio; `platform` (the default) is the text-to-speech engine. */
    @Volatile var engineChoice: String = "platform"
        private set

    /** T137: this navigation is silent (the setting is untouched); reset by [begin]. */
    @Volatile var muted = false
        private set
    /** T137: why the engine cannot speak in this navigation: `noEngine` or `noVoice` (null while it can or is not made). */
    @Volatile var failure: String? = null
        private set
    @Volatile private var failureDetail: String? = null
    /** T137: the notice was shown in this navigation (once per navigation), and when it was dismissed. */
    @Volatile private var noticeAtMs: Long? = null
    @Volatile private var noticeDismissedAtMs: Long? = null
    private var mutedCount = 0

    private val main by lazy { Handler(Looper.getMainLooper()) }
    var ui by mutableStateOf(Ui())
        private set

    private fun publish() {
        val u = Ui(muted, if (noticeAtMs != null && noticeDismissedAtMs == null) noticeText(failure) else null, failure)
        main.post { if (ui != u) ui = u }
    }

    fun noticeText(f: String?): String? = when (f) {
        "noEngine" -> NOTICE_NO_ENGINE
        "noVoice" -> NOTICE_NO_VOICE
        else -> null
    }

    val enabled: Boolean get() = AppSettings.navSpeech

    /** Navigation started: a fresh list, nothing said yet (the engine is made on the first line). */
    @Synchronized
    fun begin() {
        lines.clear()
        gate.reset()
        muted = false // T137: the mute is per navigation
        mutedCount = 0
        noticeAtMs = null
        noticeDismissedAtMs = null
        failure = null
        failureDetail = null
        publish()
    }

    /** Navigation stopped: the engine is released; the list stays for `GET /nav/speech` until the next start. */
    @Synchronized
    fun end() {
        engine?.let { e -> runCatching { e.shutdown() } }
        engine = null
        muted = false
        noticeAtMs = null
        publish()
    }

    /** T137: mutes or unmutes this navigation; muting stops a line being said. The `navSpeech` setting is not changed. */
    fun setMuted(on: Boolean) {
        synchronized(this) {
            muted = on
            publish()
        }
        if (on) silence()
    }

    /** T137: the card's notice was dismissed (it does not come back in this navigation). */
    @Synchronized
    fun dismissNotice() {
        if (noticeAtMs != null && noticeDismissedAtMs == null) noticeDismissedAtMs = System.currentTimeMillis()
        publish()
    }

    /**
     * T137: the engine cannot speak ([kind] `noEngine` or `noVoice`): the card's notice, once per navigation. Called by an
     * engine from its init (any thread); an engine left over from an earlier navigation is ignored.
     */
    fun failed(from: Engine, kind: String, detail: String) {
        synchronized(this) {
            if (from !== engine) return
            Log.w(NavSession.TAG, "spoken directions unavailable: $kind ($detail)")
            failure = kind
            failureDetail = detail
            if (noticeAtMs == null) noticeAtMs = System.currentTimeMillis()
            publish()
        }
    }

    /** The setting went off: anything being said stops at once. */
    fun silence() {
        engine?.let { runCatching { it.stop() } }
    }

    /**
     * One placed fix (navigation's worker thread): [index] is the next shown direction [ins], [aheadM] ahead. Speaks it
     * when it is within its lead distance, the setting is on and it was not said before. Returns the line, or null.
     */
    fun onNext(index: Int, ins: Instruction, aheadM: Double, mode: TravelMode, units: ContourUnits): Line? {
        synchronized(this) {
            if (!gate.take(index, aheadM, ins.leadM, enabled)) return null
            val text = SpokenText.of(ins, aheadM, units, mode)
            if (muted) { // T137: listed as muted; the engine gets nothing (and is not made for it)
                val line = Line(System.currentTimeMillis(), index, text, DirectionsText.of(ins, mode), SpokenText.forVoice(text), engine?.name ?: "none", muted = true)
                if (lines.size >= MAX_LINES) lines.removeAt(0)
                lines += line
                mutedCount++
                Log.i(NavSession.TAG, "muted #$index: $text")
                return line
            }
            val e = engine ?: makeEngine().also { engine = it; it.open() }
            val line = Line(System.currentTimeMillis(), index, text, DirectionsText.of(ins, mode), SpokenText.forVoice(text), e.name)
            if (lines.size >= MAX_LINES) lines.removeAt(0)
            lines += line
            runCatching { e.speak(line.voice, "dystopo-nav-$index") }.onFailure { Log.w(NavSession.TAG, "speaking failed", it) }
            Log.i(NavSession.TAG, "spoke #$index: $text")
            return line
        }
    }

    /** T163: the index the turn-around clock's line is listed under (not a direction's). */
    const val TURN_AROUND_INDEX = -2

    /**
     * T163: the turn-around clock's one spoken line ([io.github.jasondorjeshort.dystopo.nav.TurnClock.spoken], "Turn back by
     * 14:10 to be out by sunset."), said once per navigation by the caller, under the same setting and mute as the
     * directions; listed in `GET /nav/speech` with index -2. Returns the line, or null when the setting is off.
     */
    fun sayTurnAround(text: String): Line? {
        synchronized(this) {
            if (!enabled) return null
            val now = System.currentTimeMillis()
            if (muted) {
                val line = Line(now, TURN_AROUND_INDEX, text, "turn-around", text, engine?.name ?: "none", muted = true)
                if (lines.size >= MAX_LINES) lines.removeAt(0)
                lines += line
                mutedCount++
                return line
            }
            val e = engine ?: makeEngine().also { engine = it; it.open() }
            val line = Line(now, TURN_AROUND_INDEX, text, "turn-around", text, e.name)
            if (lines.size >= MAX_LINES) lines.removeAt(0)
            lines += line
            runCatching { e.speak(line.voice, "dystopo-turn") }.onFailure { Log.w(NavSession.TAG, "speaking failed", it) }
            Log.i(NavSession.TAG, "spoke the turn-around line: $text")
            return line
        }
    }

    private fun makeEngine(): Engine = when {
        !BuildConfig.DEBUG_ENDPOINTS -> PlatformEngine(DystopoApp.instance)
        engineChoice == "silent" -> SilentEngine()
        engineChoice == "failing" -> FailingEngine("noEngine") // T137
        engineChoice == "novoice" -> FailingEngine("noVoice")
        else -> PlatformEngine(DystopoApp.instance)
    }

    /** Debug: the engines `POST /nav/speech?engine=` takes. */
    val ENGINES = listOf("platform", "silent", "failing", "novoice")

    /** Debug: chooses the engine for the next line (the current one is released). */
    @Synchronized
    fun setEngine(choice: String) {
        require(choice in ENGINES) { "engine must be ${ENGINES.joinToString(", ")}" }
        require(BuildConfig.DEBUG_ENDPOINTS || choice == "platform") { "the silent engine is for debug builds" }
        if (choice != engineChoice) {
            engineChoice = choice
            engine?.let { runCatching { it.shutdown() } }
            engine = null
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        gate.reset()
        mutedCount = 0
    }

    /** T137: the engine's state in one word: `off` (setting off), `notStarted`, `starting`, `ready`, `noEngine`, `noVoice`. */
    private fun state(): String = failure ?: when {
        !enabled -> "off"
        engine == null -> "notStarted"
        engine is PlatformEngine && engine?.json()?.get("ready") == null -> "starting"
        else -> "ready"
    }

    /** T137: the speech part of `GET /nav`. */
    @Synchronized
    fun navJson(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled, "muted" to muted, "state" to state(), "mutedCount" to mutedCount,
        "notice" to noticeJson(), "muteButtonShown" to (enabled && failure == null),
    )

    private fun noticeJson(): Map<String, Any?>? = noticeAtMs?.let {
        linkedMapOf("text" to noticeText(failure), "kind" to failure, "detail" to failureDetail, "shownAtMs" to it, "dismissedAtMs" to noticeDismissedAtMs)
    }

    /** `GET /nav/speech`. */
    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "engineChoice" to engineChoice,
        "engine" to engine?.json(),
        "muted" to muted, // T137
        "state" to state(),
        "notice" to noticeJson(),
        "mutedCount" to mutedCount,
        "count" to lines.size,
        "skippedWhileOff" to gate.skippedOff,
        "spoken" to lines.map { l ->
            linkedMapOf("atMs" to l.atMs, "index" to l.index, "text" to l.text, "direction" to l.direction, "voice" to l.voice, "engine" to l.engine, "muted" to l.muted)
        },
    )

    /**
     * T137, debug builds: an engine whose init failed as the platform's does with no engine installed (`noEngine`) or
     * none with an English voice (`noVoice`); it says nothing, so the card's notice can be checked on the emulator.
     */
    class FailingEngine(private val kind: String) : Engine {
        override val name = if (kind == "noVoice") "novoice" else "failing"
        @Volatile private var refused = 0
        override fun open() { NavSpeech.failed(this, kind, "debug engine") }
        override fun speak(voice: String, id: String) { refused++ }
        override fun stop() {}
        override fun shutdown() {}
        override fun json(): Map<String, Any?> = linkedMapOf("name" to name, "ready" to false, "said" to 0, "refused" to refused, "lastError" to "$kind (debug engine)")
    }

    /** Records only (debug builds): the emulator has no useful audio. */
    class SilentEngine : Engine {
        override val name = "silent"
        @Volatile private var said = 0
        override fun speak(voice: String, id: String) { said++ }
        override fun stop() {}
        override fun shutdown() {}
        override fun json(): Map<String, Any?> = linkedMapOf("name" to name, "said" to said)
    }

    /**
     * The platform text-to-speech engine with the phone's default voice, on the navigation-guidance audio stream. Lines
     * that arrive before it is ready wait for it; each line asks for transient audio focus with ducking, and the focus is
     * given back when the last queued line is done.
     */
    class PlatformEngine(context: Context) : Engine {
        override val name = "platform"
        private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        private val main = Handler(Looper.getMainLooper())
        private val pending = ArrayList<Pair<String, String>>()
        @Volatile private var tts: TextToSpeech? = null
        @Volatile private var ready: Boolean? = null // null until onInit
        @Volatile private var queued = 0
        @Volatile private var said = 0
        @Volatile private var focusRequests = 0
        @Volatile private var focusGranted = 0
        @Volatile private var focusReleased = 0
        @Volatile private var lastError: String? = null

        init {
            main.post {
                tts = TextToSpeech(context.applicationContext) { status ->
                    var failedKind: String? = null
                    synchronized(this) {
                        ready = status == TextToSpeech.SUCCESS
                        if (ready != true) { lastError = "init status $status"; failedKind = "noEngine" }
                        else voiceProblem()?.let { problem -> ready = false; lastError = problem; failedKind = "noVoice" } // T137
                        if (ready == true) {
                            tts?.setAudioAttributes(attrs)
                            tts?.setOnUtteranceProgressListener(listener)
                            pending.forEach { (v, id) -> say(v, id) }
                        }
                        pending.clear()
                    }
                    failedKind?.let { NavSpeech.failed(this, it, lastError ?: it) } // T137: the card's notice
                }
            }
        }

        /**
         * T137: the directions are English: null when the engine can say them (its voice is English, or it has English
         * and switches to it), else why not. Caller holds the lock, on the main thread.
         */
        private fun voiceProblem(): String? {
            val t = tts ?: return "no engine"
            return runCatching {
                val current = t.voice?.locale ?: t.defaultVoice?.locale
                if (current?.language == "en") return null
                val target = if (Locale.getDefault().language == "en") Locale.getDefault() else Locale.US
                val r = t.setLanguage(target)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) "no voice for $target (setLanguage $r)" else null
            }.getOrElse { "voice check failed: ${it.message}" }
        }

        private val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finished(null) }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) { finished("utterance error") }
            override fun onError(utteranceId: String?, errorCode: Int) { finished("utterance error $errorCode") }
        }

        @Synchronized
        private fun finished(error: String?) {
            if (error != null) lastError = error else said++
            queued = (queued - 1).coerceAtLeast(0)
            if (queued == 0) releaseFocus()
        }

        private fun releaseFocus() {
            runCatching { audio.abandonAudioFocusRequest(focus) }.onSuccess { focusReleased++ }
        }

        @Synchronized
        override fun speak(voice: String, id: String) {
            when (ready) {
                null -> pending += voice to id
                true -> say(voice, id)
                false -> lastError = lastError ?: "no text-to-speech engine"
            }
        }

        private fun say(voice: String, id: String) {
            val t = tts ?: return
            focusRequests++
            if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusGranted++
            queued++
            if (t.speak(voice, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                lastError = "speak refused"
                finished("speak refused")
            }
        }

        @Synchronized
        override fun stop() {
            pending.clear()
            runCatching { tts?.stop() }
            queued = 0
            releaseFocus()
        }

        override fun shutdown() {
            stop()
            val t = tts
            tts = null
            main.post { runCatching { t?.shutdown() } }
        }

        override fun json(): Map<String, Any?> = linkedMapOf(
            "name" to name, "ready" to ready, "said" to said, "queued" to queued,
            "focus" to linkedMapOf("requests" to focusRequests, "granted" to focusGranted, "released" to focusReleased),
            "voice" to runCatching { tts?.voice?.let { "${it.locale}" } }.getOrNull(),
            "lastError" to lastError,
        )
    }

    // ---- T178: the pressure-fall warning's spoken line (appended under the wave's NavSpeech rule) ----

    /** T178: the index the pressure-fall warning's line is listed under (not a direction's). */
    const val PRESSURE_INDEX = -3

    /**
     * T178: the pressure-fall warning's one spoken line (`readout/PressureWatch`, "Air pressure is falling fast. Weather
     * may get worse."), said once per warning by the caller, under the same setting and mute as the directions, queued
     * behind whatever is being said (`QUEUE_ADD`, no priority); listed in `GET /nav/speech` with index -3. Returns the
     * line, or null when the setting is off.
     */
    fun sayPressure(text: String): Line? {
        synchronized(this) {
            if (!enabled) return null
            val now = System.currentTimeMillis()
            if (muted) {
                val line = Line(now, PRESSURE_INDEX, text, "pressure", text, engine?.name ?: "none", muted = true)
                if (lines.size >= MAX_LINES) lines.removeAt(0)
                lines += line
                mutedCount++
                return line
            }
            val e = engine ?: makeEngine().also { engine = it; it.open() }
            val line = Line(now, PRESSURE_INDEX, text, "pressure", text, e.name)
            if (lines.size >= MAX_LINES) lines.removeAt(0)
            lines += line
            runCatching { e.speak(line.voice, "dystopo-pressure-$now") }.onFailure { Log.w(NavSession.TAG, "speaking failed", it) }
            Log.i(NavSession.TAG, "spoke the pressure line: $text")
            return line
        }
    }

    // ---- T187: the water line (navigation's NavSession.speakWater; one line per long dry stretch) ----

    /** T187: the index the water line is listed under in `GET /nav/speech` (not a direction's). */
    const val WATER_INDEX = -4

    /**
     * T187: the last water before a stretch of more than 5 km without any ("In 400 m, water: stream, likely flowing. Then
     * none for 6.2 km."), said once per such stretch by the caller, under the `waterSpoken` setting (not `navSpeech`) and
     * this navigation's mute; listed in `GET /nav/speech` with index -4 and direction `water`. Queued behind any line being
     * said (the engine's QUEUE_ADD, no priority). Returns the line, or null when the setting is off.
     */
    fun sayWater(text: String): Line? {
        synchronized(this) {
            if (!AppSettings.waterSpoken) return null
            val now = System.currentTimeMillis()
            val voice = SpokenText.forVoice(text)
            if (muted) {
                val line = Line(now, WATER_INDEX, text, "water", voice, engine?.name ?: "none", muted = true)
                if (lines.size >= MAX_LINES) lines.removeAt(0)
                lines += line
                mutedCount++
                return line
            }
            val e = engine ?: makeEngine().also { engine = it; it.open() }
            val line = Line(now, WATER_INDEX, text, "water", voice, e.name)
            if (lines.size >= MAX_LINES) lines.removeAt(0)
            lines += line
            runCatching { e.speak(line.voice, "dystopo-water-$now") }.onFailure { Log.w(NavSession.TAG, "speaking failed", it) }
            Log.i(NavSession.TAG, "spoke the water line: $text")
            return line
        }
    }
}

/**
 * T126: when a direction is said (pure; `SpeechGateTest`): the first time the walker is within its lead distance with
 * the setting on, and never again for the same index in navigation's shown list until [reset] (a new navigation).
 * Within the lead with the setting off counts in [skippedOff] and is not remembered, so turning the setting on before
 * the turn still says it.
 */
class SpeechGate {
    private val said = HashSet<Int>()
    var skippedOff = 0
        private set

    fun reset() {
        said.clear()
        skippedOff = 0
    }

    fun take(index: Int, aheadM: Double, leadM: Double, enabled: Boolean): Boolean {
        if (aheadM > leadM || index in said) return false
        if (!enabled) { skippedOff++; return false }
        said += index
        return true
    }
}

/**
 * T126: the spoken words for a direction (pure; `SpokenTextTest`). [of] is the card's text with the distance in front
 * as the card writes it ([DirectionsText.withDistance], the units preference through
 * [io.github.jasondorjeshort.dystopo.render.UnitFormat.distance]), or the direction alone at the turn; [forVoice]
 * spells out the unit symbols so every engine reads "300 metres", "0.2 miles".
 */
object SpokenText {
    fun of(ins: Instruction, aheadM: Double, units: ContourUnits, mode: TravelMode): String =
        if (aheadM < Directions.passM(mode)) DirectionsText.of(ins, mode)
        else DirectionsText.withDistance(ins, aheadM, units, mode)

    private val UNIT = Regex("""(\d) (m|km|ft|mi)\b""")

    fun forVoice(text: String): String = UNIT.replace(text) { m ->
        val n = m.groupValues[1]
        val one = Regex("""(^|[^\d.])1$""").containsMatchIn(text.substring(0, m.range.first + 1))
        n + " " + when (m.groupValues[2]) {
            "m" -> if (one) "metre" else "metres"
            "km" -> if (one) "kilometre" else "kilometres"
            "ft" -> if (one) "foot" else "feet"
            else -> if (one) "mile" else "miles"
        }
    }
}
