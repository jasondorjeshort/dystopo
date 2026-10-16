package io.github.jasondorjeshort.dystopo.app.update

/**
 * T110 (T106's follow-up): installing an update replaces the running app, so it ends a navigation session. An install
 * asked for while navigating ([navigating]) therefore waits for the session to end, unless the user said "Install now"
 * ([request] with `now`); [poll] says when the wait is over. Pure (no Android), so `InstallWaitTest` runs it.
 */
class InstallWait(private val navigating: () -> Boolean) {
    enum class Decision { INSTALL, WAIT }

    @Volatile var waiting = false
        private set

    /** Installs that waited for navigation to end and then went ahead. */
    @Volatile var waitedInstalls = 0L
        private set

    /** An install was asked for; [now]: the user chose to install during navigation anyway. */
    @Synchronized
    fun request(now: Boolean): Decision {
        if (now || !navigating()) {
            waiting = false
            return Decision.INSTALL
        }
        waiting = true
        return Decision.WAIT
    }

    /** True once, when an install was waiting and navigation has ended: the caller installs now. */
    @Synchronized
    fun poll(): Boolean {
        if (!waiting || navigating()) return false
        waiting = false
        waitedInstalls++
        return true
    }

    /** The user declined the version, or another state replaced it: nothing waits any more. */
    @Synchronized
    fun cancel() {
        waiting = false
    }
}
