package io.github.jasondorjeshort.dystopo.app.share

/**
 * T96: which share codes this phone sends to which phone on its own (a kept peer's retries, discovery), so that it
 * never spends another phone's wrong-code budget: [ShareGate] replaces its code after five wrong ones, and a phone that
 * rotates the other's code under its user breaks the link it was trying to restore. T90's verifier did exactly that
 * with four known codes: the launch retry's wrong code was not remembered, so discovery sent it again and then every
 * other known code, five failures in all.
 *
 * The rules, for tries this phone makes without the user asking:
 * - **No code twice to a phone that refused it.** A refusal (the other phone answered 403) is kept per code and phone
 *   ([refuse]; the phone by any of its keys: address, NSD name, device id) and written to the peers file, so it
 *   survives a restart. A code that later opens that phone clears it ([opened]).
 * - **One stale code per phone per session.** A code kept from before this process started (restored from the file:
 *   a kept peer's own code, or one learned earlier) is a stale code; each phone gets at most one stale code per
 *   process, whichever path sends it (retry or discovery). Codes learned in this session (the user just entered or
 *   scanned them) are fresh and are tried on each phone once, like T90.
 * - **A kept peer's code goes only to that phone** ([plan]: the caller passes the codes other kept peers hold as
 *   `bound`); its own code goes first, and it is the only code from before that phone gets.
 * - **One try of a code on a phone at a time** (two resolves of one phone do not both send it).
 * - **A burst sends one wrong code** ([send], every request to a phone, the map's tiles and a download's pieces
 *   included): a code that has not answered for [confirmMs] goes out as one request; the others to that address
 *   with that code wait for its answer, then go together if it opened, or fail without being sent if it was
 *   refused. T96's verifier: a cold map view right after the other phone reopened its screen sent seven tiles with
 *   the old code at once (seven failures, a new code), eight parallel pieces three. T106: and within a second of an
 *   answer at most [MAX_UNANSWERED] go out before one of them answers (a code a third phone rotated meanwhile costs at
 *   most four failures); only a reply confirms a code, an [Error] does not.
 *
 * So a kept peer whose screen was reopened costs its phone one failure (the first 403, from whichever path sent the
 * old code), however often this phone relaunches, unless the code changes within [confirmMs] of an answer while
 * requests are going out; a phone this one does not know gets at most one stale code per relaunch and each code once,
 * and with at most [PeerSources.MAX_CODES] (4) codes known that stays under five. What the user does by hand ("Use its
 * maps" with a typed code) is not limited here, but a code known to be refused is still not sent.
 * Pure JVM (no Android classes); `PeerCodesTest` runs it against a real [ShareGate]. Thread-safe.
 */
internal class PeerCodes(
    private val maxCodes: Int = PeerSources.MAX_CODES,
    private val maxKeysPerCode: Int = 16,
    /** How long after an answer requests with that code go out without waiting for one to answer first ([send]). */
    private val confirmMs: Long = CONFIRM_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** T106: the most requests with one code out at once to one phone before one of them answers ([send]). */
    private val maxUnanswered: Int = MAX_UNANSWERED,
) {
    /** A phone as this phone knows it; any part may be missing. Two phones with one key in common are the same phone. */
    data class Phone(val addr: String? = null, val nsdName: String? = null, val device: String? = null) {
        val keys: List<String> get() = listOfNotNull(addr?.let { "a:$it" }, nsdName?.let { "n:$it" }, device?.let { "d:$it" })
    }

    /** How one try went: the code opened the link, the phone refused it (403), no connection at all, or anything else. */
    enum class Outcome { OPENED, WRONG, UNREACHABLE, FAILED }

    private val known = LinkedHashSet<String>()
    private val fresh = HashSet<String>()
    private val refused = LinkedHashMap<String, LinkedHashSet<String>>()
    private val staleSent = HashMap<String, String>()
    private val inFlight = HashSet<String>()

    /** Tries sent on this phone's own (a retry or discovery) and tries held back by the rules above. */
    @Volatile var sent = 0
        private set
    @Volatile var held = 0
        private set

    /** The known codes, oldest first (at most [maxCodes]). */
    val codes: List<String> @Synchronized get() = known.toList()

    /** Codes kept from the file: stale, with their refusals (code to phone keys). */
    @Synchronized
    fun restore(codes: Collection<String>, refusals: Map<String, Collection<String>>) {
        codes.forEach { known.remove(it); known.add(it) }
        while (known.size > maxCodes) known.remove(known.first())
        refusals.forEach { (c, keys) -> refused.getOrPut(c) { LinkedHashSet() }.addAll(keys) }
    }

    /** Remembers [code] as the newest (fresh unless it comes from the file); true when it was not known, or not fresh. */
    @Synchronized
    fun learn(code: String, isFresh: Boolean = true): Boolean {
        val had = known.remove(code)
        known.add(code)
        val newlyFresh = isFresh && fresh.add(code)
        while (known.size > maxCodes) fresh -= known.first().also { known.remove(it) }
        return !had || newlyFresh
    }

    /** [code] opens nothing any more (its phone shows a new one, or it was forgotten): not tried, its refusals gone. */
    @Synchronized
    fun retire(code: String): Boolean {
        refused.remove(code)
        fresh -= code
        return known.remove(code)
    }

    @Synchronized
    fun clear() {
        known.clear()
        fresh.clear()
    }

    @Synchronized
    fun isFresh(code: String) = code in fresh

    @Synchronized
    fun isRefused(phone: Phone, code: String): Boolean = refused[code]?.let { r -> phone.keys.any { it in r } } == true

    /** [phone] refused [code] (a 403): never sent to it again by [claim], nor by [PeerSources.request]. */
    @Synchronized
    fun refuse(phone: Phone, code: String) {
        val r = refused.getOrPut(code) { LinkedHashSet() }
        r.addAll(phone.keys)
        while (r.size > maxKeysPerCode) r.remove(r.first())
    }

    /** [code] opened [phone]: any refusal of it under that phone's keys was someone else at the same address. */
    @Synchronized
    fun opened(phone: Phone, code: String) {
        val r = refused[code] ?: return
        r.removeAll(phone.keys.toSet())
        if (r.isEmpty()) refused.remove(code)
    }

    /** The refusals worth keeping in the file: those of [keep] (the known codes and the kept peers' codes). */
    @Synchronized
    fun refusals(keep: Set<String>): Map<String, List<String>> =
        refused.filterKeys { it in keep }.mapValues { it.value.toList() }.toSortedMap()

    /**
     * The codes discovery tries on [phone], in order: [own] (the code of the kept peer this phone is), then the codes no
     * kept peer holds, fresh ones newest first, then stale ones newest first. Codes in [bound] (other kept peers') and
     * codes [phone] refused are left out, and so are the stale ones when [own] is given: a phone this one knows gets only
     * its own code from before (once it is refused, the code its screen shows now has to be entered, which makes it fresh).
     * [claim] applies the rest of the rules as each one is tried.
     */
    @Synchronized
    fun plan(phone: Phone, own: String?, bound: Set<String>): List<String> {
        val unbound = known.filter { it !in bound && it != own }.reversed()
        val stale = if (own != null) emptyList() else unbound.filter { it !in fresh }
        return (listOfNotNull(own) + unbound.filter { it in fresh } + stale).distinct().filter { !isRefused(phone, it) }
    }

    /**
     * May [code] go to [phone] now, unasked? No when the phone refused it, when it is being tried there already, or when
     * it is stale and the phone had another stale code this session. A yes reserves it: call [done] with the outcome.
     */
    @Synchronized
    fun claim(phone: Phone, code: String): Boolean {
        val keys = phone.keys
        val ok = !isRefused(phone, code) && keys.none { "$it|$code" in inFlight } &&
            (code in fresh || keys.mapNotNull { staleSent[it] }.all { it == code })
        if (!ok) {
            held++
            return false
        }
        if (code !in fresh) keys.forEach { staleSent[it] = code }
        keys.forEach { inFlight += "$it|$code" }
        sent++
        return true
    }

    /**
     * [code] goes to [phone] because the user asked (a typed code, a refresh, a download): not limited, but a stale code
     * takes the phone's one stale slot when it is free, so discovery does not add another.
     */
    @Synchronized
    fun noteSent(phone: Phone, code: String) {
        if (code in fresh) return
        val keys = phone.keys
        if (keys.none { it in staleSent }) keys.forEach { staleSent[it] = code }
    }

    /** The end of a [claim]ed try. A try that never connected leaves the phone's stale slot free again. */
    @Synchronized
    fun done(phone: Phone, code: String, outcome: Outcome) {
        val keys = phone.keys
        keys.forEach { inFlight -= "$it|$code" }
        when (outcome) {
            Outcome.WRONG -> refuse(phone, code)
            Outcome.OPENED -> opened(phone, code)
            Outcome.UNREACHABLE -> keys.forEach { if (staleSent[it] == code) staleSent.remove(it) }
            Outcome.FAILED -> {}
        }
    }

    /**
     * Tries [candidates] on [phone] in order through [send], each only when [claim] allows it, until one opens (returned)
     * or the phone does not answer (null; the rest wait for the next resolve). A refused code goes on to the next.
     */
    fun attempt(phone: Phone, candidates: List<String>, send: (String) -> Outcome): String? {
        for (c in candidates) {
            if (!claim(phone, c)) continue
            val o = try {
                send(c)
            } catch (e: Exception) {
                Outcome.FAILED
            }
            done(phone, c, o)
            when (o) {
                Outcome.OPENED -> return c
                Outcome.WRONG -> continue
                Outcome.UNREACHABLE, Outcome.FAILED -> return null
            }
        }
        return null
    }

    /** One address and code: when it last answered, and whether a request is out to find out whether it still opens. */
    private class Flight {
        val lock = java.util.concurrent.locks.ReentrantLock()
        val answered: java.util.concurrent.locks.Condition = lock.newCondition()
        var probing = false
        var confirmedAt = Long.MIN_VALUE / 2
        /** Counts finished probes; [failure] is how the last one failed (not a 403), for the requests that waited on it. */
        var round = 0
        var failure: Exception? = null
        /** T106: requests out now with this code (probes and the rest) that have not answered yet. */
        var outstanding = 0
    }

    private val flights = java.util.concurrent.ConcurrentHashMap<String, Flight>()

    /** Requests that waited for another one's answer ([send]), and those that then failed without being sent. */
    private val waitedCount = java.util.concurrent.atomic.AtomicInteger()
    private val sparedCount = java.util.concurrent.atomic.AtomicInteger()
    val waited: Int get() = waitedCount.get()
    val spared: Int get() = sparedCount.get()

    /**
     * Every request to [phone] (its address) with [code] goes through here. A code refused there is not sent (a
     * [ShareClient.WrongCodeException] at once). A code that answered within [confirmMs] goes out at once. Otherwise one
     * request goes out and the others wait (up to [waitMs]) for its answer, then look again: refused, they fail unsent;
     * answered, they go together; failed otherwise (no connection, a timeout), they fail the same way at once instead
     * of each trying in turn. A 403 is recorded as a refusal ([refuse]); any other failure means the next burst asks
     * first again (the other phone may have closed its screen, which gives it a new code).
     *
     * T106, two follow-ups of T96's verifier: (1) Even within [confirmMs] of an answer at most [maxUnanswered] requests are
     * out at once (the rest wait for one of them to answer): a third phone could make the other phone replace its code
     * in that second, and every request already let through then went out with the old code, one failure each; now at
     * most four do, under the five that replace a code. (2) Only a reply confirms a code: a request that ends in an
     * [Error] (out of memory, an assertion) counted as one, so the next burst went out unprobed.
     */
    fun <T> send(phone: Phone, code: String, waitMs: Long, call: () -> T): T {
        val f = flights.computeIfAbsent("${phone.addr}|$code") { Flight() }
        var probe = false
        f.lock.lock()
        try {
            val deadline = clock() + waitMs
            val round = f.round
            var hadToWait = false
            while (true) {
                if (isRefused(phone, code)) {
                    if (hadToWait) sparedCount.incrementAndGet()
                    throw ShareClient.WrongCodeException("${phone.addr} refused this code before: ${PeerSources.CODE_CHANGED}")
                }
                if (clock() - f.confirmedAt <= confirmMs) {
                    if (f.outstanding < maxUnanswered) break
                } else {
                    if (hadToWait && f.round != round) f.failure?.let { throw it }
                    if (!f.probing) {
                        f.probing = true
                        probe = true
                        break
                    }
                }
                val left = deadline - clock()
                if (left <= 0) throw java.io.IOException("${phone.addr}: no answer to the request this one waited for")
                if (!hadToWait) waitedCount.incrementAndGet()
                hadToWait = true
                f.answered.await(left, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            f.outstanding++
        } finally {
            f.lock.unlock()
        }
        var failure: Throwable? = null
        try {
            return call()
        } catch (e: Throwable) {
            failure = e
            if (e is ShareClient.WrongCodeException) refuse(phone, code)
            throw e
        } finally {
            f.lock.lock()
            try {
                f.outstanding--
                f.confirmedAt = if (failure == null) clock() else Long.MIN_VALUE / 2
                if (probe) {
                    f.probing = false
                    f.failure = when (failure) {
                        null, is ShareClient.WrongCodeException -> null
                        is Exception -> failure
                        else -> java.io.IOException("${phone.addr}: the request this one waited for failed: $failure", failure)
                    }
                    f.round++
                }
                f.answered.signalAll() // T106: every answer frees a place ([maxUnanswered]), not only a probe's
            } finally {
                f.lock.unlock()
            }
        }
    }

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "known" to known.size, "fresh" to known.count { it in fresh }, "refused" to refused.values.sumOf { it.size },
        "sent" to sent, "held" to held, "waited" to waited, "spared" to spared,
    )

    companion object {
        /** [send]: after an answer, a second of requests go out without asking first (a screen reopened by hand takes longer). */
        const val CONFIRM_MS = 1_000L

        /**
         * T106: [send]'s cap on requests out at once with one code to one phone: under the five wrong codes that replace
         * a code, and as many pieces as a pack download asks for ahead ([PeerFiles.AHEAD]).
         */
        const val MAX_UNANSWERED = 4
    }
}
