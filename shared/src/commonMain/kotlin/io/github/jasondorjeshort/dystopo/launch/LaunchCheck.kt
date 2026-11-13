package io.github.jasondorjeshort.dystopo.launch

/**
 * ADR 0061's validator: pure, read by `LaunchTest` over [Launch.stages]. Each problem is one line naming the stage.
 * - names are unique and every need is a stage declared earlier in the list (so the list is a DAG in launch order);
 * - every mark belongs to exactly one stage and none is one of the [boundaries];
 * - gates are monotone along needs (a stage needs nothing with a later gate);
 * - no MAIN stage does DISK or BINDER work unless [Stage.allowed] gives the reason (every MAIN stage runs before the first
 *   frame or on the frame's thread);
 * - every value is published by exactly one stage, with the value's gate, and every value a stage reads is published by a
 *   stage it needs, directly or through its needs.
 */
object LaunchCheck {
    fun problems(stages: List<Stage>, boundaries: List<String> = emptyList()): List<String> {
        val out = ArrayList<String>()
        val seen = HashMap<String, Stage>()
        for (s in stages) {
            if (s.name.isBlank()) out += "a stage has no name"
            if (s.name in seen) out += "${s.name}: declared twice"
            for (n in s.needs) {
                when {
                    n == s.name -> out += "${s.name}: needs itself"
                    n !in seen -> out += "${s.name}: needs $n, which is not declared before it"
                    else -> seen.getValue(n).let { need ->
                        if (need.gate > s.gate) out += "${s.name}: gate ${s.gate} needs $n, whose gate ${need.gate} is later"
                    }
                }
            }
            if (s.runs == Runs.MAIN && (Work.DISK in s.work || Work.BINDER in s.work) && s.allowed.isNullOrBlank()) {
                out += "${s.name}: ${s.work.filter { it == Work.DISK || it == Work.BINDER }.joinToString()} on the main thread with no reason allowed"
            }
            seen[s.name] = s
        }
        val owner = HashMap<String, String>()
        for (s in stages) for (m in s.marks) {
            owner[m]?.let { out += "${s.name}: mark \"$m\" also belongs to $it" } ?: owner.put(m, s.name)
            if (boundaries.any { markMatches(m, it) || markMatches(it, m) }) out += "${s.name}: mark \"$m\" is one of the trace's boundaries"
        }
        val publisher = HashMap<Value<*>, Stage>()
        for (s in stages) for (v in s.publishes) {
            publisher[v]?.let { out += "${s.name}: value ${v.name} also published by ${it.name}" } ?: publisher.put(v, s)
            if (v.gate != s.gate) out += "${s.name}: publishes ${v.name} with gate ${v.gate}, the stage's is ${s.gate}"
        }
        val byName = stages.associateBy { it.name }
        for (s in stages) for (v in s.reads) {
            val w = publisher[v]
            when {
                w == null -> out += "${s.name}: reads ${v.name}, which no stage publishes"
                w.name !in needsOf(s, byName) -> out += "${s.name}: reads ${v.name} without needing its writer ${w.name}"
            }
        }
        return out
    }

    /** Every stage [s] needs, directly or through its needs. */
    fun needsOf(s: Stage, byName: Map<String, Stage>): Set<String> {
        val out = LinkedHashSet<String>()
        val todo = ArrayDeque(s.needs)
        while (todo.isNotEmpty()) {
            val n = todo.removeFirst()
            if (out.add(n)) byName[n]?.let { todo.addAll(it.needs) }
        }
        return out
    }

    /**
     * The runner's policy on a model clock, for the seeded simulation: the main thread runs its stages in list order, each
     * once its needs are done (waiting for a worker if it must); a worker stage starts when its needs are done, with as many
     * workers as wanted. [length] is each stage's length. Returns each stage's start and end.
     */
    fun schedule(stages: List<Stage>, length: (Stage) -> Long): Map<String, LongRange> {
        val end = HashMap<String, Long>()
        val out = LinkedHashMap<String, LongRange>()
        var mainFree = 0L
        val pending = stages.toMutableList()
        while (pending.isNotEmpty()) {
            val s = pending.firstOrNull { p -> p.needs.all { it in end } } ?: break
            pending.remove(s)
            val ready = s.needs.maxOfOrNull { end.getValue(it) } ?: 0L
            val start = if (s.runs == Runs.MAIN) maxOf(ready, mainFree) else ready
            val e = start + length(s)
            if (s.runs == Runs.MAIN) mainFree = e
            end[s.name] = e
            out[s.name] = start..e
        }
        return out
    }
}
