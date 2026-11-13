package io.github.jasondorjeshort.dystopo.launch

/**
 * ADR 0061: the launch as a declared list of stages. Pure data: no thread, no lock, no clock here. The Android app's runner
 * (`app/launch/`) binds a body to each stage name and runs it on the thread the stage declares; the validator ([LaunchCheck])
 * reads the list in a JVM test.
 */
enum class Runs { MAIN, WORKER }

/** What a stage touches besides memory: the work the main thread must not do before the first frame unless allowed ([Stage.allowed]). */
enum class Work { DISK, BINDER, NATIVE, SOCKET }

/**
 * By when a stage (or a value) is ready, in launch order:
 * - [BEFORE_ACTIVITY]: the main thread's one wait, in `MainActivity.onCreate` before `startCamera`, bounded and traced.
 * - [BEFORE_STYLE]: a worker-side completion that builds the style and posts it; never a main-thread wait.
 * - [FIRST_FRAME]: the activity's own steps, on the main thread after the gate and before the platform's first frame
 *   (an addition to the ADR's three: the activity's stages need a gate of their own for the validator's order).
 * - [LATER]: nothing waits.
 */
enum class Gate { BEFORE_ACTIVITY, BEFORE_STYLE, FIRST_FRAME, LATER }

/** What a value's late reader does when its bounded wait expires (ADR 0061, the readiness rule). */
enum class OnTimeout {
    /** The reader builds the value itself (T229's fallback: the tracker). */
    NEXT_READER_BUILDS,
    /** The map stays on paper until the value publishes and the style is posted; a reload rebuilds (`tiles`). */
    RELOAD_REPAIRS,
    /** The value is late; nothing else. */
    NONE,
}

/** A value a stage publishes, as a typed key: the app's runner keeps one slot per value. [T] is the value's type there. */
class Value<T>(val name: String, val gate: Gate, val onTimeout: OnTimeout = OnTimeout.NONE) {
    override fun toString() = "Value($name)"
}

/**
 * One stage of the launch: [name], the T228 marks it owns ([marks]; a trailing `*` matches a mark's dynamic end, as
 * `view from *`), what it [needs] (stage names, each declared earlier in the list), the thread it [runs] on, the [work] it
 * does, its [gate], the values it [publishes] and the values it [reads] (each published by a stage it needs).
 * [allowed] is the reason a MAIN stage may do DISK or BINDER work before the first frame. [bound] says who runs it:
 * the runner ([Bound.RUNNER]), or a thread the stage's owner starts and the runner only observes ([Bound.OBSERVED]).
 */
data class Stage(
    val name: String,
    val marks: List<String> = emptyList(),
    val needs: List<String> = emptyList(),
    val runs: Runs = Runs.MAIN,
    val work: Set<Work> = emptySet(),
    val gate: Gate,
    val publishes: List<Value<*>> = emptyList(),
    val reads: List<Value<*>> = emptyList(),
    val allowed: String? = null,
    val bound: Bound = Bound.RUNNER,
)

enum class Bound { RUNNER, OBSERVED }

/** True when [mark] is [pattern], or [pattern] ends in `*` and [mark] starts with what precedes it. */
fun markMatches(pattern: String, mark: String): Boolean =
    if (pattern.endsWith("*")) mark.startsWith(pattern.dropLast(1)) else mark == pattern
