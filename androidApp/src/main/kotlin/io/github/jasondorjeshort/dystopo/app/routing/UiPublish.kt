package io.github.jasondorjeshort.dystopo.app.routing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T119: how [RouteSession] hands its Compose state (phase, plan, message) to the main thread, the only thread that writes
 * it. [post] queues a block there, in order (on the main thread it runs at once). [publish] does the same and, off the
 * main thread, also waits until the block has run (at most `timeoutMs`), so a planning call returns only once its
 * route is the session's for every reader: the card, `GET /route` and `GET /nav/directions?route=current`.
 *
 * Before T119 a plan's result was only posted, so `POST /route` could answer while the main thread was still busy (a
 * long frame, a recomposition) and a request right after it read no route: the post-wave 16 sweep's routing tour got
 * 404 from `GET /nav/directions?route=current` after a 200 from `POST /route`, a few tries in a hundred on an emulator.
 */
class UiPublish(private val onMain: () -> Boolean, private val toMain: (Runnable) -> Unit) {
    fun post(block: () -> Unit) {
        if (onMain()) block() else toMain(Runnable(block))
    }

    /** Runs [block] on the main thread after everything posted before it; true once it has run, false after `timeoutMs`. */
    fun publish(timeoutMs: Long, block: () -> Unit): Boolean {
        if (onMain()) {
            block()
            return true
        }
        val ran = CountDownLatch(1)
        toMain(Runnable { try { block() } finally { ran.countDown() } })
        return ran.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
