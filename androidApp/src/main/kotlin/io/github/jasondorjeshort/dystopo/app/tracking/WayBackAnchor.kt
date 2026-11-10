package io.github.jasondorjeshort.dystopo.app.tracking

import android.content.Context
import io.github.jasondorjeshort.dystopo.app.DystopoApp

/**
 * T180: "The way back starts here" for a recording, kept per device in its own preferences file ([PREFS], like
 * `LastFix`), keyed by the track id; never a synced setting, never in the track's schema or its sync ops (ADR 0033's
 * cost for a per-trip choice the other phone has no use for). Without one, the way back starts where the speed rule or
 * the recording's first good fix puts it (`nav/TurnAround.kt` › `WayBack`). The file holds the recording track's
 * override only: setting one for a track forgets the others.
 *
 * The override is stored as the fix time of the point it was set at (the recorder's last point then), so the way back
 * re-finds it in the track after a restart: the first point at or after that time.
 *
 * T252: beside it, whether the turn-back card is on for the track ([on], [setOn]): off unless the recording panel's
 * "Turn-back time" chip turned it on, kept so a restart of the app keeps the choice. Setting either for a track forgets
 * the other tracks' entries, and setting the override keeps the track's flag.
 *
 * T272: or a waypoint as the return point ([ToWaypoint], `POST /tracks/wayback?waypoint=<id>`, the waypoint card's "Turn
 * back to here"): its id and the time of the recording's last point when it was chosen; the way back then goes to the
 * recorded point nearest the waypoint among the points up to that time ([nearestIndex]), resolved again when the waypoint
 * moves, and to the start (the rule) when it is deleted. One return point at a time: choosing a waypoint forgets "here",
 * and "here" forgets the waypoint; `reset=1` forgets both. A new stored source (ADR 0033), in the same per-device file.
 */
object WayBackAnchor {
    const val PREFS = "dystopo-wayback"

    /** An override: the point's fix time and place, and when it was set (the phone's clock). */
    data class Here(val trackId: String, val pointTimeMs: Long, val lat: Double, val lon: Double, val setAtMs: Long)

    /** T272: a waypoint as the return point: the waypoint's id, the last recorded point's time when chosen, and when (the phone's clock). */
    data class ToWaypoint(val trackId: String, val waypointId: String, val upToMs: Long, val setAtMs: Long)

    private fun prefs() = DystopoApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun get(trackId: String): Here? = runCatching {
        val p = prefs()
        val t = p.getLong("$trackId.time", Long.MIN_VALUE)
        if (t == Long.MIN_VALUE) null else Here(
            trackId, t, p.getString("$trackId.lat", null)!!.toDouble(), p.getString("$trackId.lon", null)!!.toDouble(), p.getLong("$trackId.setAt", 0L),
        )
    }.getOrNull()

    @Synchronized
    fun set(h: Here) {
        val p = prefs()
        val on = p.getBoolean("${h.trackId}.on", false) // T252: the flag stays
        p.edit().clear()
            .putBoolean("${h.trackId}.on", on)
            .putLong("${h.trackId}.time", h.pointTimeMs)
            .putString("${h.trackId}.lat", h.lat.toString())
            .putString("${h.trackId}.lon", h.lon.toString())
            .putLong("${h.trackId}.setAt", h.setAtMs)
            .apply()
    }

    /** Back to the rule (`POST /tracks/wayback?reset=1`); T272: the waypoint is forgotten too. */
    @Synchronized
    fun clear(trackId: String) {
        val p = prefs()
        val keys = listOf("time", "lat", "lon", "setAt", "waypoint", "waypointUpTo", "waypointSetAt").map { "$trackId.$it" }
        if (keys.any { p.contains(it) }) p.edit().apply { for (k in keys) remove(k) }.apply()
    }

    /** T272: the waypoint chosen as [trackId]'s return point, or null. */
    @Synchronized
    fun waypoint(trackId: String): ToWaypoint? = runCatching {
        val p = prefs()
        p.getString("$trackId.waypoint", null)?.let { ToWaypoint(trackId, it, p.getLong("$trackId.waypointUpTo", Long.MAX_VALUE), p.getLong("$trackId.waypointSetAt", 0L)) }
    }.getOrNull()

    /** T272: [w] becomes its track's return point; "here" and the other tracks' entries are forgotten, the card's flag kept. */
    @Synchronized
    fun setWaypoint(w: ToWaypoint) {
        val p = prefs()
        val on = p.getBoolean("${w.trackId}.on", false)
        p.edit().clear()
            .putBoolean("${w.trackId}.on", on)
            .putString("${w.trackId}.waypoint", w.waypointId)
            .putLong("${w.trackId}.waypointUpTo", w.upToMs)
            .putLong("${w.trackId}.waypointSetAt", w.setAtMs)
            .apply()
    }

    /** T252: whether the turn-back card is on for [trackId]; off by default. */
    @Synchronized
    fun on(trackId: String): Boolean = runCatching { prefs().getBoolean("$trackId.on", false) }.getOrDefault(false)

    /** T252: turns the card on or off for [trackId]; other tracks' entries are forgotten, this track's override kept. */
    @Synchronized
    fun setOn(trackId: String, on: Boolean) {
        val p = prefs()
        val e = p.edit()
        for (k in p.all.keys) if (!k.startsWith("$trackId.")) e.remove(k)
        e.putBoolean("$trackId.on", on).apply()
    }
}

/**
 * T272: the index of the recorded point nearest ([lat], [lon]) among [points] whose time is at most [upToMs] (a point with no
 * time counts), the earliest on a tie; null with none. Bounded by the time so a walker coming back past the waypoint does
 * not move the return point to where they are now. Pure, tested.
 */
internal fun nearestIndex(points: List<io.github.jasondorjeshort.dystopo.model.TrackPoint>, lat: Double, lon: Double, upToMs: Long): Int? {
    var best: Int? = null
    var bestD = Double.MAX_VALUE
    val k = Math.cos(Math.toRadians(lat))
    for ((i, p) in points.withIndex()) {
        if ((p.time ?: Long.MIN_VALUE) > upToMs) continue
        val dx = (p.lon - lon) * k
        val dy = p.lat - lat
        val d = dx * dx + dy * dy
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

/** T272: the great-circle distance in metres (how far the waypoint is from the recorded point it resolves to). Pure. */
internal fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
    return 2 * 6_371_008.8 * Math.asin(Math.min(1.0, Math.sqrt(a)))
}
