package io.github.jasondorjeshort.dystopo.app.share

/**
 * The phones found by discovery while the Share screen is open (T54). NSD reports a service by name
 * first ([found]), then its address after a resolve ([resolved]), and [lost] when it goes away; a
 * phone may be found again with a new address (Wi-Fi roam), and our own announcement comes back too
 * ([self]). Thread-safe; [peers] is what the screen lists (resolved, not self, newest first).
 */
class PeerList(private val clock: () -> Long = System::currentTimeMillis) {
    data class Peer(val name: String, val host: String?, val port: Int?, val self: Boolean, val seenAt: Long) {
        val hostPort: String? get() = if (host != null && port != null) (if (':' in host) "[$host]" else host) + ":" + port else null

        fun json(): Map<String, Any?> = linkedMapOf("name" to name, "addr" to hostPort, "self" to self, "seenAt" to seenAt)
    }

    private val byName = LinkedHashMap<String, Peer>()

    /** The name we announce under (NSD may rename it on a clash, so it is set when registration reports it). */
    @Volatile var selfName: String? = null
        set(value) {
            field = value
            synchronized(this) { byName.replaceAll { k, p -> p.copy(self = k == value) } }
        }

    @Synchronized
    fun found(name: String) {
        val old = byName[name]
        byName[name] = old?.copy(seenAt = clock()) ?: Peer(name, null, null, name == selfName, clock())
    }

    @Synchronized
    fun resolved(name: String, host: String, port: Int) {
        byName[name] = Peer(name, host, port, name == selfName, clock())
    }

    @Synchronized
    fun lost(name: String) {
        byName.remove(name)
    }

    @Synchronized
    fun clear() = byName.clear()

    /** Everything known, self and unresolved included (for `/share/status`). */
    @Synchronized
    fun all(): List<Peer> = byName.values.sortedByDescending { it.seenAt }

    /** Phones to offer on the screen. */
    fun peers(): List<Peer> = all().filter { !it.self && it.hostPort != null }
}
