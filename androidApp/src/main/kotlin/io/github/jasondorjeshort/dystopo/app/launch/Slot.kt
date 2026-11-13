package io.github.jasondorjeshort.dystopo.app.launch

import io.github.jasondorjeshort.dystopo.launch.Gate
import io.github.jasondorjeshort.dystopo.launch.OnTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR 0061: a value one launch stage builds and other threads read; T229's `TrackerReady`, generalised. The value is held in
 * a volatile field and its readiness is a [CompletableDeferred] (made at the first wait), completed once by [set] or [failed]. Three reads:
 * - [value] (peek) never waits and may be null: the UI and the routes.
 * - [get]: the value, from any thread. On the main thread before it is ready: when [strict] (debug builds) a failed check
 *   naming [name], its [gate] and the caller; otherwise (release builds) a bounded wait, told to [onMainWait] first (the
 *   start trace's `waiting for <stage>` and its `mainThreadRead` reason). Off the main thread: [await].
 * - [await]: off the main thread, a wait of at most `waitMs`; a [TimeoutException] naming the caller after it.
 * A failed stage: [onTimeout] [OnTimeout.NEXT_READER_BUILDS] answers null (the caller builds it, and may [set] it later);
 * otherwise the failure is rethrown, naming the stage. Pure (no Android), so `TrackingUiStateTest` and `SlotTest` run it.
 */
class Slot<T : Any>(
    private val isMain: () -> Boolean,
    @Volatile var onReady: (T) -> Unit = {},
    val name: String = "value",
    val gate: Gate = Gate.LATER,
    val onTimeout: OnTimeout = OnTimeout.NEXT_READER_BUILDS,
    val stage: String = name,
    private val strict: Boolean = true,
    @Volatile var hint: String = "read its peek (null until ready), or call it on a thread",
    private val onMainWait: (Slot<*>, String, Boolean) -> Unit = { _, _, _ -> },
) {
    /** Made at the first wait or callback, so a slot set before anyone waits loads no coroutine machinery on its thread. */
    private var done: CompletableDeferred<Unit>? = null
    @Volatile private var completed = false
    private val lock = Any()
    private val pending = AtomicBoolean(false)

    private fun deferred(): CompletableDeferred<Unit> = synchronized(lock) {
        done ?: CompletableDeferred<Unit>().also { d -> if (completed) d.complete(Unit); done = d }
    }

    private fun complete() {
        synchronized(lock) {
            completed = true
            done?.complete(Unit)
        }
    }

    @Volatile var value: T? = null
        private set
    @Volatile var failure: Throwable? = null
        private set

    val isDone: Boolean get() = completed

    /** An action of [once] is waiting. */
    val waiting: Boolean get() = pending.get()

    fun set(v: T) {
        value = v
        failure = null
        complete()
        onReady(v)
    }

    fun failed(e: Throwable) {
        failure = e
        complete()
    }

    /** Runs [action] once the slot is done (set or failed), on the thread that completes it, or now when it is. */
    fun whenDone(action: () -> Unit) {
        deferred().invokeOnCompletion { action() }
    }

    /**
     * The value, waiting at most [waitMs] for it; null when the build failed and [onTimeout] lets the reader build it. On the
     * main thread before it is ready: unless [allowMain], [get]'s rule (a failed check when [strict], else a bounded wait).
     */
    fun await(waitMs: Long, caller: () -> String, allowMain: Boolean = false): T? {
        value?.let { return it }
        if (!completed && isMain() && !allowMain) {
            val who = caller()
            check(!strict) { "$name read on the main thread before it is ready (gate $gate, stage $stage), from $who: $hint" }
            onMainWait(this, who, true)
            try {
                return block(waitMs, who)
            } finally {
                onMainWait(this, who, false)
            }
        }
        return block(waitMs, caller())
    }

    /** [await] with this slot's rules and the default bound: the value, or an exception naming the stage or the caller. */
    fun get(waitMs: Long, caller: () -> String): T =
        await(waitMs, caller) ?: throw IllegalStateException("$name: stage $stage failed (${caller()})", failure)

    private fun block(waitMs: Long, who: String): T? {
        if (!completed) {
            val d = deferred()
            val finished = runBlocking { withTimeoutOrNull(waitMs) { d.await() } }
            if (finished == null) throw TimeoutException("$name was not ready in $waitMs ms (stage $stage, $who)")
        }
        value?.let { return it }
        val f = failure
        if (onTimeout == OnTimeout.NEXT_READER_BUILDS) return null
        throw IllegalStateException("$name: stage $stage failed ($who)", f)
    }

    /** T229's Record rule: [onWaiting] true when an action starts to wait, false once it ran (or failed); a second ask while one waits is refused (null). */
    fun <R> once(waitMs: Long, caller: () -> String, build: () -> T, onWaiting: (Boolean) -> Unit, action: (T) -> R): R? {
        value?.let { return action(it) }
        if (!pending.compareAndSet(false, true)) return null
        onWaiting(true)
        try {
            return action(await(waitMs, caller) ?: build())
        } finally {
            pending.set(false)
            onWaiting(false)
        }
    }
}
