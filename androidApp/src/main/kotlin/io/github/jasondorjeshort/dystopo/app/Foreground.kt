package io.github.jasondorjeshort.dystopo.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

/**
 * T150: whether the map is on screen, one flag for the whole process, so nothing polls while the app is in the
 * background (the routing-graph finding: the main thread at 50% and the GC daemon at 20% with the app on HOME, then a kill
 * for excessive CPU). [MainActivity] counts itself in at `onStart` and out at `onStop`; the flag is on while at least one
 * is started (an activity started over another runs its `onStart` before the other's `onStop`).
 *
 * Every Compose polling loop waits with [delay] instead of `kotlinx.coroutines.delay`, which pauses it after its next pass
 * once the app is stopped and resumes it at the next start; the plain-thread loops (`CountrySizes`, `AheadPrefetch`) wait
 * with [block]. One shared flag rather than `repeatOnLifecycle(STARTED)` in each effect: the thread loops have no lifecycle
 * owner, a paused loop keeps its local state (a restarted effect would redo its first pass and re-attach its listeners),
 * and the flag is a plain object the JVM tests drive and `GET /debug/cpu` reports ([json]).
 */
object Foreground {
    private val state = MutableStateFlow(false)
    private val activities = AtomicInteger()
    private val lock = Object()
    private val paused = AtomicInteger()

    /** True while an activity of the app is started (visible). */
    val started: Boolean get() = state.value

    val flow: StateFlow<Boolean> get() = state

    /** Changes of [started] since the process started. */
    @Volatile var transitions = 0L
        private set

    /** An activity reached `onStart`; true when this put the app in the foreground. */
    fun activityStarted(): Boolean = if (activities.incrementAndGet() == 1) set(true) else false

    /** An activity reached `onStop`; true when this put the app in the background. */
    fun activityStopped(): Boolean {
        val n = activities.decrementAndGet()
        if (n < 0) activities.set(0)
        return if (n <= 0) set(false) else false
    }

    private fun set(on: Boolean): Boolean = synchronized(lock) {
        if (state.value == on) return false
        state.value = on
        transitions++
        lock.notifyAll()
        true
    }

    /** Suspends until the app is in the foreground (at once when it is). */
    suspend fun await() {
        if (state.value) return
        paused.incrementAndGet()
        try {
            state.first { it }
        } finally {
            paused.decrementAndGet()
        }
    }

    /** A polling loop's pause: [ms], then as long as the app is in the background. */
    suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
        await()
    }

    /**
     * Blocks a plain thread until the app is in the foreground or [timeoutMs] has passed (0: no limit); returns [started].
     * Throws [InterruptedException] as `Object.wait` does.
     */
    fun block(timeoutMs: Long = 0): Boolean {
        if (state.value) return true
        paused.incrementAndGet()
        try {
            val until = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE
            synchronized(lock) {
                while (!state.value) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.wait(if (timeoutMs > 0) left else 0)
                }
            }
        } finally {
            paused.decrementAndGet()
        }
        return state.value
    }

    /** `GET /debug/cpu` `foreground`: the flag, the started activities, the loops waiting now, the changes. */
    fun json(): Map<String, Any?> = linkedMapOf(
        "started" to started, "activities" to activities.get(), "pausedLoops" to paused.get(), "transitions" to transitions,
    )

    /** Tests: back to the state at process start. */
    internal fun reset() {
        activities.set(0)
        set(false)
        transitions = 0
    }
}
