package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.UuidGenerator

/**
 * T281 (ADR 0062): the drive search, a bidirectional A* on directed-edge states, so a drive can pay for its turns
 * ([TurnCost]). Hike and bike keep [TileRouter]'s node search, which can also still plan a drive
 * ([TileRouter.driveTurns] off: the owner's fast option).
 *
 * **States.** In a base piece a state is a directed edge arrived at its head: id `block + 2 * edge + direction`
 * (direction 0 from the edge's from node to its to node). In an overlay piece ([DriveOverlay]) a border node has an
 * in label and an out label (`block + 2 * node + 0 | 1`): a shortcut runs from an in label to an out label, a border
 * crossing from an out label (or a base edge) to an in label, so no reversal is free at a cell border. Each piece the
 * search touches gets a block of dense state ids (twice its edges for a base piece, twice its nodes for an overlay
 * piece) and a block of node slots for the potential, which is read at the state's head node.
 *
 * **Junctions.** Where a node continues into other pieces (tile cuts, pack borders: [GraphPiece.twin]) the node and its
 * twins are one junction: a state arriving at it is offered the outgoing edges of every piece there directly (no twin
 * step of its own) and pays [TurnCost] for each, with the junction's roads counted across all of them. Turns at an
 * overlay border node are not priced (a shortcut's cost holds the turns inside its cell; the step out of a shortcut
 * into a detail piece reads as straight on): ADR 0062's residual.
 *
 * **Ends.** The forward search starts on both directions of the start's edge at the cost of its part of that edge;
 * the backward search starts from the goal edge entered at either end (`GOAL_FROM`, `GOAL_TO`, costing the part of the
 * edge up to the goal) and reaches the states arriving there through their turn onto it, so the turn off the first
 * edge and the turn onto the last are both priced. Both ends on one edge, travelled the way it allows, is the direct
 * route (no turn).
 *
 * **Costs.** Forward, F(s) is the cost from the start up to the head of s, its own edge included; backward, B(s) the
 * cost from the head of s (having arrived by s) to the goal: the turn onto the next edge, that edge, and so on. The two
 * keys are F + p(head) and B - p(head) with [TileRouter]'s average potentials; turn costs only add, so the potentials
 * stay consistent and the stop rule (the two smallest keys reach the best meeting) keeps the route optimal.
 *
 * **Budget.** [maxStates] settled states, then [RouteFailure.SEARCH_LIMIT] with the message the node search gives at
 * [maxSettled] junctions (`TileRouter.DRIVE_STATES_PER_JUNCTION` converts one to the other).
 */
internal class DriveSearch(
    private val router: TileRouter,
    private val view: TileRouter.View,
    private val model: CostModel,
    private val s: TileRouter.End,
    private val t: TileRouter.End,
    private val turns: TurnCost,
    private val maxSettled: Int,
    private val maxStates: Long,
) {
    private val pieces = ArrayList<GraphPiece>()
    private var stateBase = IntArray(16)
    private var nodeBase = IntArray(16)
    private val indexOf = HashMap<GraphPiece, Int>()
    private var states = FIRST
    private var nodes = 0
    private var cap = 0
    private var nodeCap = 0
    private val dist = arrayOf(DoubleArray(0), DoubleArray(0))
    private val parent = arrayOf(IntArray(0), IntArray(0))
    private val closed = arrayOf(BooleanArray(0), BooleanArray(0))
    private var pot = DoubleArray(0)
    private val heaps = arrayOf(MinHeap(), MinHeap())
    private val networkM = DoubleArray(2)
    private val hw = model.minWeight
    /** T73: `destination` edges this drive may use (those joined to an end through other destination edges), by [edgeKey]. */
    private val destOk = HashSet<Long>()
    var expanded = 0
        private set

    init {
        growStates(FIRST)
        destinationZone(s)
        destinationZone(t)
    }

    // ---- Blocks, ids and potentials ----

    private fun index(p: GraphPiece): Int = indexOf.getOrPut(p) {
        val i = pieces.size
        if (i == stateBase.size) { stateBase = stateBase.copyOf(i * 2); nodeBase = nodeBase.copyOf(i * 2) }
        stateBase[i] = states
        nodeBase[i] = nodes
        states += 2 * (if (p.overlay != null) p.graph.nodeCount else p.graph.edgeCount)
        nodes += p.graph.nodeCount
        pieces += p
        growStates(states)
        growNodes(nodes)
        i
    }

    private fun growStates(n: Int) {
        if (n <= cap) return
        val c = maxOf(n, cap * 2, 256)
        for (d in 0..1) {
            val old = cap
            dist[d] = dist[d].copyOf(c).also { it.fill(Double.POSITIVE_INFINITY, old, c) }
            parent[d] = parent[d].copyOf(c).also { it.fill(-1, old, c) }
            closed[d] = closed[d].copyOf(c)
        }
        cap = c
    }

    private fun growNodes(n: Int) {
        if (n <= nodeCap) return
        val c = maxOf(n, nodeCap * 2, 256)
        pot = pot.copyOf(c).also { it.fill(Double.NaN, nodeCap, c) }
        nodeCap = c
    }

    private fun state(p: GraphPiece, local: Int): Int {
        val i = index(p) // first: it may grow stateBase
        return stateBase[i] + local
    }

    /** Index into [pieces] of state [v] (not one of the reserved ids). */
    private fun pieceIndex(v: Int): Int {
        var lo = 0
        var hi = pieces.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (stateBase[mid] <= v) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun edgeKey(p: GraphPiece, e: Int): Long = (index(p).toLong() shl 32) or (e.toLong() and 0xFFFFFFFFL)

    /** The potential at node [n] of piece [pi]. */
    private fun potential(pi: Int, n: Int): Double {
        val slot = nodeBase[pi] + n // pi is a known piece: nodeBase does not grow here
        val v = pot[slot]
        if (!v.isNaN()) return v
        val g = pieces[pi].graph
        val lat = g.nodeLat[n] / E6
        val lon = g.nodeLon[n] / E6
        val toEnd = TrackStats.distanceM(lat, lon, t.snap.lat, t.snap.lon)
        val fromStart = TrackStats.distanceM(s.snap.lat, s.snap.lon, lat, lon)
        return (hw * (toEnd - fromStart) / 2).also { pot[slot] = it }
    }

    /** The potential at the head of state [v] (for a goal root, the node it enters the goal edge at). */
    private fun headPotential(v: Int): Double {
        if (v == GOAL_FROM || v == GOAL_TO) {
            val g = t.piece.graph
            return potential(index(t.piece), if (v == GOAL_FROM) g.edgeFrom[t.snap.edge] else g.edgeTo[t.snap.edge])
        }
        val pi = pieceIndex(v)
        val p = pieces[pi]
        val local = v - stateBase[pi]
        if (p.overlay != null) return potential(pi, local shr 1)
        val e = local shr 1
        return potential(pi, if (local and 1 == 0) p.graph.edgeTo[e] else p.graph.edgeFrom[e])
    }

    // ---- Costs ----

    /** Edge [e] of base piece [p] travelled whole, from its from node when [fwd]; infinite where the drive may not. */
    private fun edgeCost(p: GraphPiece, e: Int, fwd: Boolean): Double = partCost(p, e, if (fwd) 0.0 else 1.0, if (fwd) 1.0 else 0.0)

    /** [TileRouter.stepCost], and infinite on a `destination` edge outside the ends' zones (T73). */
    private fun partCost(p: GraphPiece, e: Int, f0: Double, f1: Double): Double {
        if (p.graph.flags(e) and WayAttrs.MOTOR_DESTINATION != 0 && edgeKey(p, e) !in destOk) return Double.POSITIVE_INFINITY
        return router.stepCost(model, p, e, f0, f1)
    }

    /**
     * T73: `motor_access=destination` roads only at a route's ends: when an end lies on one, every destination edge joined
     * to it through other destination edges (up to [MAX_DESTINATION_EDGES]) is usable; any other is not.
     */
    private fun destinationZone(end: TileRouter.End) {
        val g0 = end.piece.graph
        val e0 = end.snap.edge
        if (g0.flags(e0) and WayAttrs.MOTOR_DESTINATION == 0) return
        destOk += edgeKey(end.piece, e0)
        val queue = ArrayDeque<Pair<GraphPiece, Int>>()
        val seen = HashSet<Long>()
        queue.addLast(end.piece to g0.edgeFrom[e0]); queue.addLast(end.piece to g0.edgeTo[e0])
        var added = 0
        while (queue.isNotEmpty() && added < MAX_DESTINATION_EDGES) {
            val (p, n) = queue.removeFirst()
            if (!seen.add(edgeKey(p, n))) continue
            val g = p.graph
            for (i in g.adjStart[n] until g.adjStart[n + 1]) {
                val e = g.adjEdge[i] shr 1
                if (g.flags(e) and WayAttrs.MOTOR_DESTINATION == 0 || !model.usable(g, e)) continue
                if (destOk.add(edgeKey(p, e))) {
                    added++
                    queue.addLast(p to g.edgeFrom[e]); queue.addLast(p to g.edgeTo[e])
                }
            }
            if (p.boundary[n]) {
                for (q in neighbourPieces(g.nodeLon[n], g.nodeLat[n])) {
                    if (q === p || !q.graph.source.drive) continue
                    val lq = q.twin(g.nodeLon[n], g.nodeLat[n], tolerant = q.graph.source != p.graph.source, level = g.nodeLevel[n].toInt())
                    if (lq >= 0) queue.addLast(q to lq)
                }
            }
        }
    }

    // ---- Junctions: a node and its twins ----

    private val memPieces = ArrayList<GraphPiece>()
    private var memNodes = IntArray(8)

    /** Fills [memPieces] and [memNodes] with node [n] of [p] and every twin reachable from it through twins. */
    private fun junction(p: GraphPiece, n: Int) {
        memPieces.clear()
        memPieces += p
        memNodes[0] = n
        var i = 0
        while (i < memPieces.size) {
            val q = memPieces[i]
            val m = memNodes[i]
            i++
            if (!q.boundary[m]) continue
            val g = q.graph
            val lon = g.nodeLon[m]
            val lat = g.nodeLat[m]
            val level = g.nodeLevel[m].toInt()
            for (r in neighbourPieces(lon, lat)) {
                if (r === q || !r.graph.source.drive) continue
                val lr = r.twin(lon, lat, tolerant = r.graph.source != q.graph.source, level = level) // T89: same level only
                if (lr < 0) continue
                var known = false
                for (k in memPieces.indices) if (memPieces[k] === r && memNodes[k] == lr) { known = true; break }
                if (known) continue
                if (memPieces.size == memNodes.size) memNodes = memNodes.copyOf(memNodes.size * 2)
                memNodes[memPieces.size] = lr
                memPieces += r
            }
        }
    }

    /** The junction just filled, for the turn function (a copy: the next expansion refills the buffers). */
    private fun junctionView(): Junction = Junction(ArrayList(memPieces), memNodes.copyOf(memPieces.size))

    /** Pieces of every tile within [GraphPieces.JOIN_M] of the point. */
    private fun neighbourPieces(lonE6: Int, latE6: Int): List<GraphPiece> {
        val box = TiledGraph.around(latE6 / E6, lonE6 / E6, GraphPieces.JOIN_M * 2)
        val tl = router.tiles.tilesIn(box[0], box[1], box[2], box[3])
        return if (tl.size == 1) view.pieces(tl[0].first, tl[0].second) else tl.flatMap { (x, y) -> view.pieces(x, y) }
    }

    // ---- The search ----

    private var mu = Double.POSITIVE_INFINITY
    private var meet = -1
    private var direct = false

    /** Offers cost [c] for state [v] in direction [d] (0 forward, 1 backward), reached from [from]. */
    private fun relax(d: Int, v: Int, c: Double, from: Int) {
        if (closed[d][v] || c >= dist[d][v]) return
        dist[d][v] = c
        parent[d][v] = from
        heaps[d].push(if (d == 0) c + headPotential(v) else c - headPotential(v), v)
        val other = dist[1 - d][v]
        if (other < Double.POSITIVE_INFINITY && c + other < mu) {
            mu = c + other
            meet = v
            direct = false
        }
    }

    private fun expandForward(u: Int) {
        val du = dist[0][u]
        val pi = pieceIndex(u)
        val p = pieces[pi]
        val local = u - stateBase[pi]
        val o = p.overlay
        if (o != null) {
            val n = local shr 1
            val g = p.graph
            if (local and 1 == IN) {
                // An in label: the cell's shortcuts from this border node.
                for (i in g.adjStart[n] until g.adjStart[n + 1]) {
                    val a = g.adjEdge[i]
                    if (a and 1 != 0) continue // shortcuts are directed: taken from their from node only
                    val e = a shr 1
                    relax(0, state(p, 2 * g.edgeTo[e] + OUT), du + o.cost(e), u)
                }
            } else {
                // An out label: across the border, into whatever has this node on the other side; no turn priced.
                junction(p, n)
                leaveAcross(p, du, u)
            }
            return
        }
        val g = p.graph
        val e = local shr 1
        val fwd = local and 1 == 0
        junction(p, if (fwd) g.edgeTo[e] else g.edgeFrom[e])
        val j = if (turns.free) null else junctionView()
        for (k in memPieces.indices) {
            val q = memPieces[k]
            val m = memNodes[k]
            if (q.overlay != null) { relax(0, state(q, 2 * m + IN), du, u); continue }
            val gq = q.graph
            for (i in gq.adjStart[m] until gq.adjStart[m + 1]) {
                val a = gq.adjEdge[i]
                val e2 = a shr 1
                val atFrom = a and 1 == 0
                val c = edgeCost(q, e2, atFrom)
                if (c == Double.POSITIVE_INFINITY) continue
                networkM[0] += gq.edgeLength[e2] / 2.0
                val tc = if (j == null) 0.0 else turns.cost(p, e, fwd, j, q, e2, atFrom)
                relax(0, state(q, 2 * e2 + if (atFrom) 0 else 1), du + tc + c, u)
            }
        }
    }

    /** From an overlay out label of [p] (the junction filled): into every other piece there, priced as straight on. */
    private fun leaveAcross(p: GraphPiece, du: Double, u: Int) {
        for (k in memPieces.indices) {
            val q = memPieces[k]
            if (q === p) continue
            val m = memNodes[k]
            if (q.overlay != null) { relax(0, state(q, 2 * m + IN), du, u); continue }
            val gq = q.graph
            for (i in gq.adjStart[m] until gq.adjStart[m + 1]) {
                val a = gq.adjEdge[i]
                val e2 = a shr 1
                val atFrom = a and 1 == 0
                val c = edgeCost(q, e2, atFrom)
                if (c == Double.POSITIVE_INFINITY) continue
                networkM[0] += gq.edgeLength[e2] / 2.0
                relax(0, state(q, 2 * e2 + if (atFrom) 0 else 1), du + c, u)
            }
        }
    }

    private fun expandBackward(v: Int) {
        val dv = dist[1][v]
        // The edge travelled after the junction: a goal root's partial edge (already in dv) or a base state's whole edge.
        val outPiece: GraphPiece
        val outEdge: Int
        val outFwd: Boolean
        val add: Double
        if (v == GOAL_FROM || v == GOAL_TO) {
            outPiece = t.piece
            outEdge = t.snap.edge
            outFwd = v == GOAL_FROM
            add = 0.0
        } else {
            val pi = pieceIndex(v)
            val q = pieces[pi]
            val local = v - stateBase[pi]
            val o = q.overlay
            if (o != null) {
                val n = local shr 1
                val g = q.graph
                if (local and 1 == OUT) {
                    // An out label: reached by the shortcuts ending at this border node, from their in labels.
                    for (i in g.adjStart[n] until g.adjStart[n + 1]) {
                        val a = g.adjEdge[i]
                        if (a and 1 == 0) continue
                        val e = a shr 1
                        relax(1, state(q, 2 * g.edgeFrom[e] + IN), dv + o.cost(e), v)
                    }
                } else {
                    // An in label: entered across the border from every other piece there, priced as straight on.
                    junction(q, n)
                    arriveAcross(q, dv, v)
                }
                return
            }
            outPiece = q
            outEdge = local shr 1
            outFwd = local and 1 == 0
            add = edgeCost(q, outEdge, outFwd)
            if (add == Double.POSITIVE_INFINITY) return
        }
        val go = outPiece.graph
        junction(outPiece, if (outFwd) go.edgeFrom[outEdge] else go.edgeTo[outEdge])
        val j = if (turns.free) null else junctionView()
        for (k in memPieces.indices) {
            val p = memPieces[k]
            val m = memNodes[k]
            if (p.overlay != null) { relax(1, state(p, 2 * m + OUT), dv + add, v); continue }
            val g = p.graph
            for (i in g.adjStart[m] until g.adjStart[m + 1]) {
                val a = g.adjEdge[i]
                val e = a shr 1
                val inFwd = a and 1 != 0 // m is the edge's to node: it arrives travelling forwards
                // Not skipped when the drive may not travel it: a start at a node is a zero-length seed in either
                // direction of its edge, and must meet here; such a state is settled but never expanded.
                if (edgeCost(p, e, inFwd) < Double.POSITIVE_INFINITY) networkM[1] += g.edgeLength[e] / 2.0
                val tc = if (j == null) 0.0 else turns.cost(p, e, inFwd, j, outPiece, outEdge, outFwd)
                relax(1, state(p, 2 * e + if (inFwd) 0 else 1), dv + add + tc, v)
            }
        }
    }

    /** Into an overlay in label of [q] (the junction filled): from every other piece there, priced as straight on. */
    private fun arriveAcross(q: GraphPiece, dv: Double, v: Int) {
        for (k in memPieces.indices) {
            val p = memPieces[k]
            if (p === q) continue
            val m = memNodes[k]
            if (p.overlay != null) { relax(1, state(p, 2 * m + OUT), dv, v); continue }
            val g = p.graph
            for (i in g.adjStart[m] until g.adjStart[m + 1]) {
                val a = g.adjEdge[i]
                val e = a shr 1
                val inFwd = a and 1 != 0
                if (edgeCost(p, e, inFwd) < Double.POSITIVE_INFINITY) networkM[1] += g.edgeLength[e] / 2.0
                relax(1, state(p, 2 * e + if (inFwd) 0 else 1), dv, v)
            }
        }
    }

    private fun seed() {
        val es = s.snap.edge
        val f = s.snap.fraction
        partCost(s.piece, es, f, 1.0).let { if (it < Double.POSITIVE_INFINITY) relax(0, state(s.piece, 2 * es), it, START) }
        partCost(s.piece, es, f, 0.0).let { if (it < Double.POSITIVE_INFINITY) relax(0, state(s.piece, 2 * es + 1), it, START) }
        val et = t.snap.edge
        val g = t.snap.fraction
        index(t.piece) // the goal's block, for its potential
        for ((root, c) in listOf(GOAL_FROM to partCost(t.piece, et, 0.0, g), GOAL_TO to partCost(t.piece, et, 1.0, g))) {
            if (c == Double.POSITIVE_INFINITY) continue
            dist[1][root] = c
            parent[1][root] = -1
        }
        // The roots are settled at once (as the node search settles its two ends), so the states arriving at the goal
        // edge carry their backward cost before the forward search can reach them.
        for (root in listOf(GOAL_FROM, GOAL_TO)) if (dist[1][root] < Double.POSITIVE_INFINITY) {
            closed[1][root] = true
            expanded++
            expandBackward(root)
        }
    }

    fun run(name: String, ids: UuidGenerator): RouteResult {
        if (s.piece === t.piece && s.snap.edge == t.snap.edge) {
            mu = partCost(s.piece, s.snap.edge, s.snap.fraction, t.snap.fraction)
            direct = mu < Double.POSITIVE_INFINITY // a one-way travelled backwards is not direct (T73)
        }
        seed()
        var exhausted = -1
        while (true) {
            val f = heaps[0]
            val r = heaps[1]
            if (f.size == 0 || r.size == 0) {
                exhausted = if (f.size == 0) 0 else 1
                break
            }
            if (f.peekKey() + r.peekKey() >= mu) break
            if (expanded >= maxStates && mu == Double.POSITIVE_INFINITY) {
                // T89: the budget, in junctions as the node search counts them (T281: states / DRIVE_STATES_PER_JUNCTION).
                return RouteResult.Failed(
                    RouteFailure.SEARCH_LIMIT,
                    "no route found within the search limit ($maxSettled junctions settled, ${router.distanceText(networkM[0])} of " +
                        "road around the start and ${router.distanceText(networkM[1])} around the end); the ends may not be connected in the maps",
                    linkedMapOf("expanded" to expanded, "maxSettled" to maxSettled, "maxStates" to maxStates),
                )
            }
            val d = if (f.size <= r.size) 0 else 1
            val u = heaps[d].pop()
            if (closed[d][u]) continue
            closed[d][u] = true
            expanded++
            if (d == 0) expandForward(u) else expandBackward(u)
        }
        if (mu == Double.POSITIVE_INFINITY) {
            val (sAt, eAt) = if (exhausted == 0) router.distanceText(networkM[0]) to "at least ${router.distanceText(networkM[1])}"
                else "at least ${router.distanceText(networkM[0])}" to router.distanceText(networkM[1])
            return RouteResult.Failed(
                RouteFailure.NOT_CONNECTED,
                "no road open to cars connects start and end (one-way streets and roads closed to cars count; " +
                    "drive mode only knows the roads in the maps): $sAt of road reachable from the start, $eAt to the end",
                linkedMapOf(
                    "startNetworkKm" to Router.km(networkM[0]), "endNetworkKm" to Router.km(networkM[1]),
                    "exhausted" to if (exhausted == 0) "start" else "end", "expanded" to expanded,
                ),
            )
        }
        val steps = ArrayList<TileRouter.Step>()
        if (direct) {
            steps += TileRouter.Step(s.piece, s.snap.edge, s.snap.fraction, t.snap.fraction)
        } else {
            var v = meet
            while (v != START) {
                val prev = parent[0][v]
                forwardStep(prev, v)?.let { steps += it }
                v = prev
            }
            steps.reverse()
            v = meet
            var root: Int
            while (true) {
                val next = parent[1][v]
                if (next == GOAL_FROM || next == GOAL_TO) { root = next; break }
                backwardStep(v, next)?.let { steps += it }
                v = next
            }
            steps += TileRouter.Step(t.piece, t.snap.edge, if (root == GOAL_FROM) 0.0 else 1.0, t.snap.fraction)
        }
        val memory = linkedMapOf<String, Any?>(
            "tiles" to pieces.size, "graphBytes" to pieces.sumOf { it.graph.approxBytes() },
            "searchBytes" to cap.toLong() * SEARCH_BYTES_PER_STATE + nodeCap.toLong() * SEARCH_BYTES_PER_NODE,
            "searchNodes" to nodes, "searchStates" to states, // T281
        )
        if (view.overlay != null) {
            memory["overlayTiles"] = view.overlayTiles // T77
            if (view.overlay.level2) memory["level2Tiles"] = view.level2Tiles // T89
        }
        val expandedSteps = ArrayList<TileRouter.Step>(steps.size)
        for (st in steps) {
            val o = st.piece.overlay
            if (o == null) expandedSteps += st
            else for ((p, e, fwd) in o.expand(st.e)) expandedSteps += if (fwd) TileRouter.Step(p, e, 0.0, 1.0) else TileRouter.Step(p, e, 1.0, 0.0)
        }
        return RouteResult.Found(router.plan(model, expandedSteps.filter { it.f0 != it.f1 }, mu, s, t, expanded, name, ids, memory))
    }

    /** The step that took the forward search from [prev] to [v], in travel order; null for a border crossing. */
    private fun forwardStep(prev: Int, v: Int): TileRouter.Step? {
        val pi = pieceIndex(v)
        val p = pieces[pi]
        val local = v - stateBase[pi]
        if (p.overlay == null) {
            val e = local shr 1
            val fwd = local and 1 == 0
            if (prev == START) return TileRouter.Step(s.piece, e, s.snap.fraction, if (fwd) 1.0 else 0.0)
            return if (fwd) TileRouter.Step(p, e, 0.0, 1.0) else TileRouter.Step(p, e, 1.0, 0.0)
        }
        return shortcut(prev, v)
    }

    /** The step travelled after state [v] to reach [next] (the backward search's parent), in travel order; null for a border crossing. */
    private fun backwardStep(v: Int, next: Int): TileRouter.Step? {
        val pi = pieceIndex(next)
        val p = pieces[pi]
        val local = next - stateBase[pi]
        if (p.overlay == null) {
            val e = local shr 1
            return if (local and 1 == 0) TileRouter.Step(p, e, 0.0, 1.0) else TileRouter.Step(p, e, 1.0, 0.0)
        }
        return shortcut(v, next)
    }

    /** The shortcut from in label [a] to out label [b] of one overlay piece, as a step; null when they are not that pair. */
    private fun shortcut(a: Int, b: Int): TileRouter.Step? {
        if (a < FIRST || b < FIRST) return null
        val pa = pieceIndex(a)
        val pb = pieceIndex(b)
        if (pa != pb) return null
        val la = a - stateBase[pa]
        val lb = b - stateBase[pb]
        if (la and 1 != IN || lb and 1 != OUT) return null
        val p = pieces[pa]
        val g = p.graph
        val from = la shr 1
        val to = lb shr 1
        var best = -1
        for (i in g.adjStart[from] until g.adjStart[from + 1]) {
            val ad = g.adjEdge[i]
            if (ad and 1 != 0) continue
            val e = ad shr 1
            if (g.edgeTo[e] == to && (best < 0 || p.overlay!!.cost(e) < p.overlay.cost(best))) best = e
        }
        check(best >= 0) { "no shortcut $from -> $to in $p" }
        return TileRouter.Step(p, best, 0.0, 1.0)
    }

    companion object {
        /** The forward search's root (the start, inside its edge). */
        const val START = 0
        /** The backward search's roots: the goal edge entered at its from node, and at its to node. */
        const val GOAL_FROM = 1
        const val GOAL_TO = 2
        const val FIRST = 3
        const val IN = 0
        const val OUT = 1
        const val E6 = 1e6
        /** Per state: two directions of cost (8), parent (4) and closed (1). */
        const val SEARCH_BYTES_PER_STATE = 26L
        /** Per node of a touched piece: the potential. */
        const val SEARCH_BYTES_PER_NODE = 8L
        /** T73: how many `destination` edges one end's zone may hold (as the node search). */
        const val MAX_DESTINATION_EDGES = 5000
    }
}
