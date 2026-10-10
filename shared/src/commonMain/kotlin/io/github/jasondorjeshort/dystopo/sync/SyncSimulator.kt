package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.random.Random

/**
 * The sync simulator (T44): in-process peers that change their data while they gossip over a fake
 * network with latency, message loss, duplicates, reordering and partitions, then heal and check that
 * every peer converged to the same state, and that this state equals a fresh replica built from the
 * union of all operations. Everything derives from [Config.seed], so a failing run replays exactly.
 *
 * Time is simulated (milliseconds); peers' wall clocks are skewed by up to [Config.skewMs]. Local
 * changes are drawn from a weighted mix (record fixes, new segments, imports including the same GPX
 * on two peers, renames racing on several peers, descriptions, waypoints, deletes and re-imports of
 * deleted tracks, routes, settings) over what each peer currently sees. Each peer starts a sync with
 * a random other peer every [Config.syncIntervalMs] (jittered): pull request, pull response (merged),
 * push of what the other lacks. After [Config.ops] changes the partitions heal, changes stop, and
 * syncing continues until every peer holds the same vector and state (or [Config.maxQuiesceRounds]
 * sync intervals pass).
 *
 * T51 adds three hazards. [Config.bigSkewMs] moves the last peer's clock far off (two days ahead or
 * behind), past the clock-skew guard ([HlcClock.observe]); [Config.crashes] crash-restarts peer 1 that
 * many times during the change phase (it is down for [Config.downMs]: messages to it are lost, its
 * in-flight syncs never finish, and it comes back from its durable state through [restart]);
 * [Config.pageOps] makes every pull return pages of that many operations (the receiver pulls the
 * next page at once) and every push one page.
 */
class SyncSimulator(
    private val config: Config,
    /** Builds a peer's store; the default is [InMemoryTrackStore]. `sync-sim --store sql` passes SQLite stores. */
    private val newStore: (deviceId: String, wallClock: () -> Long) -> SyncStore = { d, c -> InMemoryTrackStore(d, c) },
    /** A crash and restart of a peer: the same replica reopened from what it keeps durably (T51). */
    private val restart: (SyncStore) -> SyncStore = { (it as InMemoryTrackStore).restarted() },
) {
    data class Config(
        val peers: Int = 3,
        val ops: Int = 2000,
        val seed: Long = 42,
        val partitions: Int = 5,
        val reorder: Boolean = false,
        /** Mean simulated time between two local changes (any peer). */
        val opIntervalMs: Long = 1000,
        val syncIntervalMs: Long = 10_000,
        val minLatencyMs: Long = 20,
        val maxLatencyMs: Long = 3_000,
        val dropRate: Double = 0.05,
        /** Messages delivered twice (only with [reorder]). */
        val duplicateRate: Double = 0.03,
        /**
         * A received bundle forwarded to a third peer as a push (gossip relay, like a `.dsync` file
         * passed on): its base is another peer's vector, so the gap rule decides what the third takes.
         */
        val relayRate: Double = 0.1,
        val skewMs: Long = 5_000,
        val maxQuiesceRounds: Int = 200,
        /** Compaction on a random peer every this many changes (tombstones are kept: retention is far longer than the run). */
        val compactEvery: Int = 250,
        /** T51: added to the last peer's clock (negative: behind), on top of [skewMs]. */
        val bigSkewMs: Long = 0,
        /** T51: crash-restarts of peer 1 during the change phase. */
        val crashes: Int = 0,
        /** T51: how long a crashed peer stays down. */
        val downMs: Long = 30_000,
        /** T51: page size of pulls and pushes (0: whole bundles). */
        val pageOps: Int = 0,
    ) {
        init {
            require(peers >= 2) { "at least 2 peers" }
            require(ops >= 0 && partitions >= 0 && crashes >= 0 && pageOps >= 0)
        }

        fun commandLine(): String = "sync-sim --peers $peers --ops $ops --seed $seed --partitions $partitions" + (if (reorder) " --reorder" else "") +
            (if (bigSkewMs != 0L) " --big-skew-ms $bigSkewMs" else "") + (if (crashes > 0) " --crashes $crashes" else "") +
            (if (pageOps > 0) " --page $pageOps" else "")
    }

    private val rnd = Random(config.seed)
    private val ids = UuidGenerator(Random(config.seed xor 0x5eed))
    private val epoch = 1_700_000_000_000L
    private var time = 0L

    private class Peer(val index: Int, var store: SyncStore, val skew: Long) {
        /** Track this peer is "recording" into (appends go there), and the last fix time it used. */
        var recording: String? = null
        var lastFix = 0L

        /** T51: bumped by a crash; messages addressed to an older epoch are lost. */
        var epoch = 0
        var downUntil = -1L
    }

    private val skews = List(config.peers) { i ->
        (if (config.skewMs > 0) rnd.nextLong(-config.skewMs, config.skewMs + 1) else 0L) + (if (i == config.peers - 1) config.bigSkewMs else 0L)
    }
    private val peers: List<Peer> = List(config.peers) { i ->
        val skew = skews[i]
        Peer(i, newStore(ids.next()) { epoch + time + skew }, skew)
    }

    private fun now(p: Peer) = epoch + time + p.skew

    // ---- events ----

    private sealed class Msg(val from: Int, val to: Int) {
        /** T51: the recipient's epoch this message belongs to (a crash in between loses it), and the sender's. */
        var toEpoch = 0
        var fromEpoch = 0

        class PullRequest(from: Int, to: Int, val since: VersionVector) : Msg(from, to)
        class PullResponse(from: Int, to: Int, val bundle: Bundle) : Msg(from, to)
        class Push(from: Int, to: Int, val bundle: Bundle) : Msg(from, to)
    }

    private sealed class Event(val at: Long, val seq: Long) {
        class LocalChange(at: Long, seq: Long) : Event(at, seq)
        class SyncTick(at: Long, seq: Long, val peer: Int) : Event(at, seq)
        class Deliver(at: Long, seq: Long, val msg: Msg, val sentAt: Long) : Event(at, seq)
        class Crash(at: Long, seq: Long, val peer: Int) : Event(at, seq)
    }

    /** A binary min-heap on (at, seq): deterministic order for equal times. */
    private class Queue {
        private val a = ArrayList<Event>()
        private fun less(x: Event, y: Event) = x.at < y.at || (x.at == y.at && x.seq < y.seq)
        val size get() = a.size
        fun add(e: Event) {
            a += e
            var i = a.size - 1
            while (i > 0) {
                val p = (i - 1) / 2
                if (!less(a[i], a[p])) break
                val t = a[i]; a[i] = a[p]; a[p] = t; i = p
            }
        }
        fun poll(): Event? {
            if (a.isEmpty()) return null
            val top = a[0]
            val last = a.removeAt(a.size - 1)
            if (a.isNotEmpty()) {
                a[0] = last
                var i = 0
                while (true) {
                    val l = 2 * i + 1; val r = l + 1
                    var m = i
                    if (l < a.size && less(a[l], a[m])) m = l
                    if (r < a.size && less(a[r], a[m])) m = r
                    if (m == i) break
                    val t = a[i]; a[i] = a[m]; a[m] = t; i = m
                }
            }
            return top
        }
    }

    private val queue = Queue()
    private var seq = 0L
    private fun schedule(e: (Long) -> Event) = queue.add(e(seq++))

    // ---- partitions ----

    class Partition(val start: Long, val end: Long, val groupOf: List<Int>) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "startMs" to start, "endMs" to end,
            "groups" to groupOf.indices.groupBy { groupOf[it] }.toList().sortedBy { it.first }.map { it.second },
        )
    }

    private val changePhaseMs = config.ops * config.opIntervalMs

    private val partitions: List<Partition> = makePartitions()

    private fun makePartitions(): List<Partition> {
        val n = config.partitions
        if (n == 0) return emptyList()
        val slot = changePhaseMs / n
        return List(n) { i ->
            val len = (slot * (0.3 + 0.5 * rnd.nextDouble())).toLong()
            val start = i * slot + (rnd.nextDouble() * (slot - len)).toLong()
            // Two non-empty groups.
            val groups = MutableList(config.peers) { rnd.nextInt(2) }
            if (groups.all { it == groups[0] }) groups[rnd.nextInt(config.peers)] = 1 - groups[0]
            Partition(start, start + len, groups)
        }
    }

    private fun cut(a: Int, b: Int, t: Long): Boolean = partitions.any { t >= it.start && t < it.end && it.groupOf[a] != it.groupOf[b] }

    // ---- counters ----

    private val changes = LinkedHashMap<String, Int>()
    private var sent = 0; private var delivered = 0; private var dropped = 0; private var droppedByPartition = 0
    private var duplicated = 0; private var reordered = 0; private var relayed = 0; private var syncsStarted = 0; private var syncsCompleted = 0
    private var applied = 0L; private var duplicates = 0L; private var dead = 0L; private var gapSkips = 0L; private var rejected = 0L
    private var compactions = 0; private var errors = ArrayList<String>()
    private var clamped = 0L; private var maxAheadMs = 0L; private var conflicts = 0L
    private var crashes = 0; private var droppedByCrash = 0; private var pagesFollowed = 0
    private val lastDeliveryOnLink = HashMap<Pair<Int, Int>, Long>()

    private fun send(msg: Msg, toEpoch: Int = peers[msg.to].epoch) {
        msg.toEpoch = toEpoch
        msg.fromEpoch = peers[msg.from].epoch
        sent++
        if (cut(msg.from, msg.to, time)) { droppedByPartition++; return }
        if (rnd.nextDouble() < config.dropRate) { dropped++; return }
        // With --reorder, latencies spread over four times the range, so messages often overtake each other.
        val spread = (config.maxLatencyMs - config.minLatencyMs) * (if (config.reorder) 4 else 1)
        val latency = config.minLatencyMs + (rnd.nextDouble() * spread).toLong()
        val link = msg.from to msg.to
        val last = lastDeliveryOnLink[link] ?: 0L
        val at = if (config.reorder) time + latency else maxOf(time + latency, last + 1)
        if (at < last) reordered++
        lastDeliveryOnLink[link] = maxOf(last, at)
        schedule { s -> Event.Deliver(at, s, msg, time) }
        if (config.reorder && rnd.nextDouble() < config.duplicateRate) {
            duplicated++
            val again = time + config.minLatencyMs + (rnd.nextDouble() * (config.maxLatencyMs - config.minLatencyMs) * 2).toLong()
            schedule { s -> Event.Deliver(again, s, msg, time) }
        }
    }

    /** Every bundle goes through the wire format, so the codec is part of what converges. */
    private var wireBytes = 0L
    private fun wire(b: Bundle): Bundle = b.encode().also { wireBytes += it.size }.let { Bundle.decode(it) }

    private fun merge(p: Peer, b: Bundle) {
        val r = p.store.apply(b, now(p))
        applied += r.applied; duplicates += r.duplicate; dead += r.dead; gapSkips += r.gapDevices.size; rejected += r.rejected
        clamped += r.clamped; maxAheadMs = maxOf(maxAheadMs, r.maxAheadMs); conflicts += r.conflicts.size
    }

    private fun outgoing(p: Peer, since: VersionVector): Bundle =
        if (config.pageOps > 0) p.store.page(since, config.pageOps) else p.store.bundle(since)

    private fun deliver(msg: Msg) {
        if (cut(msg.from, msg.to, time)) { droppedByPartition++; return }
        val p = peers[msg.to]
        if (p.epoch != msg.toEpoch || time < p.downUntil) { droppedByCrash++; return }
        delivered++
        when (msg) {
            is Msg.PullRequest -> send(Msg.PullResponse(p.index, msg.from, wire(outgoing(p, msg.since))), toEpoch = msg.fromEpoch)
            is Msg.PullResponse -> {
                merge(p, msg.bundle)
                if (msg.bundle.more) {
                    // A page: pull the next one at once, from the new vector.
                    pagesFollowed++
                    send(Msg.PullRequest(p.index, msg.from, p.store.vector()))
                } else {
                    val out = outgoing(p, msg.bundle.vector)
                    if (out.ops.isNotEmpty()) send(Msg.Push(p.index, msg.from, wire(out)))
                    syncsCompleted++
                }
                if (peers.size > 2 && rnd.nextDouble() < config.relayRate) {
                    var third = rnd.nextInt(peers.size - 2)
                    for (x in listOf(p.index, msg.from).sorted()) if (third >= x) third++
                    relayed++
                    send(Msg.Push(p.index, third, msg.bundle))
                }
            }
            is Msg.Push -> merge(p, msg.bundle)
        }
    }

    // ---- local changes ----

    private val names = listOf("Skyline loop", "Camp Muir", "Paradise", "Snow Lake", "Pinnacle Peak", "Mazama Ridge", "Comet Falls", "Van Trump Park")

    /** Imports remembered so another peer can import the same file (same ids), and deleted tracks re-imported. */
    private val imports = ArrayList<Pair<Track, List<Waypoint>>>()

    private fun count(kind: String) { changes[kind] = (changes[kind] ?: 0) + 1 }

    private fun point(p: Peer): TrackPoint {
        p.lastFix = maxOf(p.lastFix + 1000 + rnd.nextLong(0, 4000), now(p) - 60_000)
        return TrackPoint(46.78 + rnd.nextDouble() * 0.1, -121.80 + rnd.nextDouble() * 0.1, 1500.0 + rnd.nextInt(0, 1500), p.lastFix)
    }

    private fun <T> pick(list: List<T>): T? = if (list.isEmpty()) null else list[rnd.nextInt(list.size)]

    private fun newImport(p: Peer): Pair<Track, List<Waypoint>> {
        val id = ids.next()
        val segs = List(1 + rnd.nextInt(2)) { TrackSegment(List(5 + rnd.nextInt(25)) { point(p) }) }
        val wps = List(rnd.nextInt(3)) { Waypoint(ids.next(), "WP ${rnd.nextInt(100)}", 46.8, -121.7, 1600.0, p.lastFix) }
        return Track(id, pick(names)!! + " import", segs, description = "imported") to wps
    }

    private fun localChange() {
        var p = peers[rnd.nextInt(peers.size)]
        if (time < p.downUntil) p = peers[(p.index + 1) % peers.size] // a crashed peer makes no changes
        val s = p.store
        val t = now(p)
        val tracks = s.list()
        val r = rnd.nextInt(1000)
        when {
            r < 60 -> {
                val id = ids.next()
                s.create(id, pick(names)!!, t, recording = true)
                p.recording = id
                count("createTrack")
            }
            r < 480 -> {
                val id = p.recording?.takeIf { s.summary(it) != null } ?: pick(tracks)?.id ?: return localChange0(p)
                s.append(id, point(p), now = t)
                count("appendFix")
            }
            r < 510 -> {
                val id = pick(tracks)?.id ?: return localChange0(p)
                s.append(id, point(p), newSegment = true, now = t)
                count("newSegment")
            }
            r < 540 -> {
                val imp = newImport(p)
                imports += imp
                s.insert(imp.first, imp.second, imp.first.points.first().time!!, t)
                count("importGpx")
            }
            r < 560 -> {
                // The same GPX imported on another peer (same ids): a duplicate import, or a re-import after a delete.
                val imp = pick(imports.filter { s.summary(it.first.id) == null }) ?: return localChange0(p)
                val wps = imp.second.filter { s.waypoint(it.id) == null }
                s.insert(imp.first, wps, imp.first.points.first().time!!, t)
                count("reimportGpx")
            }
            r < 680 -> {
                // Renames concentrate on the first tracks, so peers race on the same ones.
                val target = tracks.take(3).let { pick(it) } ?: return localChange0(p)
                s.rename(target.id, pick(names)!! + " " + rnd.nextInt(10), t)
                count("renameTrack")
            }
            r < 720 -> {
                val target = pick(tracks) ?: return localChange0(p)
                s.setField(EntityKind.TRACK, target.id, "description", if (rnd.nextBoolean()) "note ${rnd.nextInt(100)}" else null, t)
                count("describeTrack")
            }
            r < 780 -> {
                val trackId = if (rnd.nextInt(4) == 0) null else pick(tracks)?.id
                s.addWaypoint(trackId, Waypoint(ids.next(), "Mark ${rnd.nextInt(100)}", 46.8 + rnd.nextDouble() * 0.05, -121.75, null, t), t)
                count("addWaypoint")
            }
            r < 820 -> {
                val w = pick(tracks.flatMap { s.waypoints(it.id) } + s.waypoints(null)) ?: return localChange0(p)
                s.renameWaypoint(w.id, "Renamed ${rnd.nextInt(100)}", t)
                count("renameWaypoint")
            }
            r < 840 -> {
                val w = pick(tracks.flatMap { s.waypoints(it.id) } + s.waypoints(null)) ?: return localChange0(p)
                s.deleteWaypoint(w.id, t)
                count("deleteWaypoint")
            }
            r < 870 -> {
                val target = pick(tracks) ?: return localChange0(p)
                s.delete(target.id)
                if (p.recording == target.id) p.recording = null
                count("deleteTrack")
            }
            r < 920 -> {
                val existing = pick(s.routes())
                val id = if (existing != null && rnd.nextBoolean()) existing.id else ids.next()
                s.putRoute(Route(id, "Route ${rnd.nextInt(50)}", List(2 + rnd.nextInt(5)) { RoutePoint(46.8 + it * 0.01, -121.7, null, null, "rp$it") }), t)
                count("putRoute")
            }
            r < 940 -> {
                val route = pick(s.routes()) ?: return localChange0(p)
                s.setField(EntityKind.ROUTE, route.id, "name", "Route renamed ${rnd.nextInt(50)}", t)
                count("renameRoute")
            }
            r < 950 -> {
                val route = pick(s.routes()) ?: return localChange0(p)
                s.deleteRoute(route.id, t)
                count("deleteRoute")
            }
            r < 990 -> {
                s.setSetting(pick(listOf("units", "textScale", "gapS", "warmup"))!!, rnd.nextInt(5).toString(), t)
                count("setSetting")
            }
            else -> {
                s.setSetting(pick(listOf("units", "textScale", "gapS", "warmup"))!!, null, t)
                count("removeSetting")
            }
        }
    }

    /** When the drawn change has nothing to act on: start a recording instead. */
    private fun localChange0(p: Peer) {
        val id = ids.next()
        p.store.create(id, pick(names)!!, now(p), recording = true)
        p.recording = id
        count("createTrack")
    }

    // ---- run ----

    data class Report(val json: Map<String, Any?>, val converged: Boolean)

    fun run(elapsed: () -> Long = { 0L }): Report {
        val started = elapsed()
        // Local changes at exponential-ish intervals; sync ticks per peer, jittered.
        var t = 0L
        repeat(config.ops) {
            t += 1 + (rnd.nextDouble() * 2 * config.opIntervalMs).toLong()
            schedule { s -> Event.LocalChange(t, s) }
        }
        val changeEnd = maxOf(t, changePhaseMs)
        for (p in peers) schedule { s -> Event.SyncTick(rnd.nextLong(0, config.syncIntervalMs), s, p.index) }
        // T51: crash-restarts of peer 1, spread over the change phase.
        val crashPeer = 1 % peers.size
        repeat(config.crashes) { i ->
            val slot = changeEnd / config.crashes
            val at = i * slot + rnd.nextLong(0, maxOf(1L, slot))
            schedule { s -> Event.Crash(at, s, crashPeer) }
        }

        var changesDone = 0
        var convergedAt: Long? = null
        var quiesceRounds = 0
        val quiesceLimit = changeEnd + config.maxQuiesceRounds * config.syncIntervalMs
        while (true) {
            val e = queue.poll() ?: break
            time = e.at
            when (e) {
                is Event.LocalChange -> {
                    try {
                        localChange()
                    } catch (ex: Exception) {
                        errors += "t=$time: ${ex::class.simpleName}: ${ex.message}"
                    }
                    changesDone++
                    if (config.compactEvery > 0 && changesDone % config.compactEvery == 0) {
                        val p = peers[rnd.nextInt(peers.size)]
                        p.store.compact(now(p), retentionMs = 1000L * changeEnd + SyncReplica.DEFAULT_RETENTION_MS)
                        compactions++
                    }
                }
                is Event.SyncTick -> {
                    if (time > changeEnd) {
                        if (convergedAt == null && settled()) { convergedAt = time; break }
                        if (time > quiesceLimit) break
                    }
                    val p = peers[e.peer]
                    var other = rnd.nextInt(peers.size - 1)
                    if (other >= p.index) other++
                    if (time >= p.downUntil) {
                        syncsStarted++
                        send(Msg.PullRequest(p.index, other, p.store.vector()))
                    }
                    val jitter = (config.syncIntervalMs * (0.5 + rnd.nextDouble())).toLong()
                    schedule { s -> Event.SyncTick(time + jitter, s, p.index) }
                    if (time > changeEnd && e.peer == 0) quiesceRounds++
                }
                is Event.Deliver -> deliver(e.msg)
                is Event.Crash -> {
                    // The process dies: in-flight syncs are lost, volatile state is gone; it comes back
                    // from its durable state after downMs.
                    val p = peers[e.peer]
                    try {
                        p.store = restart(p.store)
                    } catch (ex: Exception) {
                        errors += "t=$time: restart of peer ${p.index}: ${ex::class.simpleName}: ${ex.message}"
                    }
                    p.epoch++
                    p.downUntil = time + config.downMs
                    crashes++
                }
            }
        }

        // Verdict: same vector and state everywhere, equal to a fresh replica of the union of all operations.
        val snapshots = peers.map { it.store.snapshot() }
        val digests = snapshots.map { it.digest() }
        val vectors = peers.map { it.store.vector() }
        var divergence: Map<String, Any?>? = null
        for (i in 1 until peers.size) {
            val d = snapshots[0].firstDifference(snapshots[i])
            if (d != null) {
                divergence = linkedMapOf("entity" to d.first, "peerA" to 0, "peerB" to i, "a" to d.second, "b" to d.third)
                break
            }
        }
        val union = LinkedHashMap<Hlc, Op>()
        peers.forEach { p -> p.store.opsSince(VersionVector.EMPTY).forEach { union[it.hlc] = it } }
        val shuffled = union.values.toMutableList().also { it.shuffle(rnd) }
        val unionVector = VersionVector(union.values.groupBy { it.device }.mapValues { (_, ops) -> ops.maxOf { it.hlc } })
        val reference = InMemoryTrackStore(ids.next())
        reference.apply(Bundle("union", VersionVector.EMPTY, unionVector, shuffled), epoch + time)
        val refDigest = reference.snapshot().digest()
        val refDiff = snapshots[0].firstDifference(reference.snapshot())
        val sameVectors = vectors.all { it == vectors[0] }
        val converged = divergence == null && sameVectors && refDiff == null && errors.isEmpty()
        val final = snapshots[0]
        val renamers = union.values.filter { (it.body as? SetField)?.let { b -> b.kind == EntityKind.TRACK && b.field == "name" } == true }
            .groupBy { it.body.id }.values.count { ops -> ops.map { it.device }.toSet().size > 1 }
        val json = linkedMapOf<String, Any?>(
            "command" to "sync-sim",
            "seed" to config.seed,
            "peers" to config.peers,
            "ops" to config.ops,
            "partitions" to config.partitions,
            "reorder" to config.reorder,
            "converged" to converged,
            "firstDivergence" to (divergence ?: refDiff?.let { linkedMapOf("entity" to it.first, "peerA" to 0, "peerB" to "reference", "a" to it.second, "b" to it.third) }),
            "digest" to digests[0],
            "referenceDigest" to refDigest,
            "sameVectors" to sameVectors,
            "convergedAtMs" to convergedAt,
            "changePhaseMs" to changeEnd,
            "quiesceRounds" to quiesceRounds,
            "changes" to changes.toList().sortedBy { it.first }.toMap(),
            "errors" to errors.take(10),
            "state" to linkedMapOf(
                "tracks" to final.tracks.size, "points" to final.tracks.sumOf { it.pointCount }, "waypoints" to final.waypoints.size,
                "routes" to final.routes.size, "settings" to final.settings.size, "opsInLog" to peers.map { it.store.opCount }, "opsEver" to union.size,
                "tracksRenamedOnSeveralPeers" to renamers,
                // T51: recorded fixes merge into runs, so the log holds fewer appends than fixes.
                "appendOpsInLog" to union.values.count { it.body is AppendPoints },
            ),
            "messages" to linkedMapOf(
                "sent" to sent, "delivered" to delivered, "dropped" to dropped, "droppedByPartition" to droppedByPartition,
                "droppedByCrash" to droppedByCrash, "duplicated" to duplicated, "reorderedOnLink" to reordered, "relayed" to relayed,
                "pagesFollowed" to pagesFollowed, "bundleBytes" to wireBytes,
            ),
            "syncs" to linkedMapOf("started" to syncsStarted, "completed" to syncsCompleted),
            "merge" to linkedMapOf(
                "applied" to applied, "duplicates" to duplicates, "dead" to dead, "gapSkips" to gapSkips, "rejected" to rejected,
                "clamped" to clamped, "maxAheadMs" to maxAheadMs, "conflicts" to conflicts,
            ),
            "crashes" to crashes,
            "bigSkewMs" to config.bigSkewMs,
            // T51: how far each peer's newest own operation is ahead of its own clock at the end (the skew guard keeps honest peers near 0).
            "ownClockLeadMs" to peers.map { p -> p.store.vector().entries[p.store.deviceId]?.let { it.wall - now(p) } },
            "pageOps" to config.pageOps,
            "compactions" to compactions,
            "partitionWindows" to partitions.map { it.json() },
            "skewMs" to skews,
            "elapsedMs" to elapsed() - started,
            "replay" to config.commandLine(),
        )
        return Report(json, converged)
    }

    private fun settled(): Boolean {
        val v = peers[0].store.vector()
        if (peers.any { it.store.vector() != v }) return false
        val d = peers[0].store.snapshot().digest()
        return peers.all { it.store.snapshot().digest() == d }
    }
}
