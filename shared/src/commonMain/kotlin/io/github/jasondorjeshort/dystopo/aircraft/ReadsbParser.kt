package io.github.jasondorjeshort.dystopo.aircraft

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * T311: the readsb "v2" aircraft JSON (`{"ac": [...], "now": <ms>, ...}`), as adsb.lol's `/v2/lat/../lon/../dist/..`
 * answers it (adsb.fi and airplanes.live answer the same form). Fields read (readsb's README-json): `hex`, `flight`
 * (the call sign, space-padded), `r` (registration), `t` (ICAO type), `desc`, `lat`, `lon`, `alt_geom` (feet above the
 * WGS84 ellipsoid), `alt_baro` (feet, or the word `ground`), `gs` (knots), `track` (degrees true; `true_heading` when
 * there is no track), `geom_rate` or `baro_rate` (feet a minute), `seen_pos` (seconds). An entry without a position is
 * skipped. Pure; [atMs] is when the phone received the answer.
 */
object ReadsbParser {
    fun parse(text: String, atMs: Long): AircraftPoll {
        val root = MiniJson.parseObject(text)
        val list = root["ac"] as? List<*> ?: root["aircraft"] as? List<*> ?: emptyList<Any?>()
        val out = ArrayList<AircraftReport>(list.size)
        for (e in list) {
            @Suppress("UNCHECKED_CAST")
            val m = e as? Map<String, Any?> ?: continue
            parseOne(m)?.let { out += it }
        }
        return AircraftPoll(atMs, out, text.length)
    }

    fun parseOne(m: Map<String, Any?>): AircraftReport? {
        val hex = (m["hex"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val lat = num(m["lat"]) ?: return null
        val lon = num(m["lon"]) ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val baro = m["alt_baro"]
        val ground = baro == "ground"
        return AircraftReport(
            hex = hex,
            callSign = (m["flight"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            registration = (m["r"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            type = (m["t"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            description = (m["desc"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            lat = lat, lon = lon,
            altGeomFt = num(m["alt_geom"]),
            altBaroFt = if (ground) null else num(baro),
            onGround = ground,
            trackDeg = num(m["track"]) ?: num(m["true_heading"]),
            groundSpeedKt = num(m["gs"]),
            verticalRateFpm = num(m["geom_rate"]) ?: num(m["baro_rate"]),
            seenPosS = num(m["seen_pos"]) ?: num(m["seen"]) ?: 0.0,
        )
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        else -> null
    }
}
