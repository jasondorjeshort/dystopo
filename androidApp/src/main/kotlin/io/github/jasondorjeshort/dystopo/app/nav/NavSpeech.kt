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
 * last queued line). With the screen off it goes on speaking as long as fixes reach navigation. Debug builds can swap
 * in a [SilentEngine] (`POST /nav/speech?engine=silent`) that only records, so the emulator check needs no sound.
 * `GET /nav/speech` lists what was spoken since navigation started (`NavRoutes.kt`).
 */
object NavSpeech {
    /** What was said: when, the index of the direction in the shown list, the card's words and what the engine got. */
    data class Line(val atMs: Long, val index: Int, val text: String, val direction: String, val voice: String, val engine: String)

    interface Engine {
        val name: String
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

    val enabled: Boolean get() = AppSettings.navSpeech

    /** Navigation started: a fresh list, nothing said yet (the engine is made on the first line). */
    @Synchronized
    fun begin() {
        lines.clear()
        gate.reset()
    }

    /** Navigation stopped: the engine is released; the list stays for `GET /nav/speech` until the next start. */
    @Synchronized
    fun end() {
        engine?.let { e -> runCatching { e.shutdown() } }
        engine = null
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
            val e = engine ?: makeEngine().also { engine = it }
            val line = Line(System.currentTimeMillis(), index, text, DirectionsText.of(ins, mode), SpokenText.forVoice(text), e.name)
            if (lines.size >= MAX_LINES) lines.removeAt(0)
            lines += line
            runCatching { e.speak(line.voice, "dystopo-nav-$index") }.onFailure { Log.w(NavSession.TAG, "speaking failed", it) }
            Log.i(NavSession.TAG, "spoke #$index: $text")
            return line
        }
    }

    private fun makeEngine(): Engine =
        if (BuildConfig.DEBUG_ENDPOINTS && engineChoice == "silent") SilentEngine() else PlatformEngine(DystopoApp.instance)

    /** Debug: chooses the engine for the next line (the current one is released). */
    @Synchronized
    fun setEngine(choice: String) {
        require(choice == "silent" || choice == "platform") { "engine must be silent or platform" }
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
    }

    /** `GET /nav/speech`. */
    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "engineChoice" to engineChoice,
        "engine" to engine?.json(),
        "count" to lines.size,
        "skippedWhileOff" to gate.skippedOff,
        "spoken" to lines.map { l ->
            linkedMapOf("atMs" to l.atMs, "index" to l.index, "text" to l.text, "direction" to l.direction, "voice" to l.voice, "engine" to l.engine)
        },
    )

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
                    synchronized(this) {
                        ready = status == TextToSpeech.SUCCESS
                        if (ready != true) lastError = "init status $status"
                        else {
                            tts?.setAudioAttributes(attrs)
                            tts?.setOnUtteranceProgressListener(listener)
                            pending.forEach { (v, id) -> say(v, id) }
                        }
                        pending.clear()
                    }
                }
            }
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
