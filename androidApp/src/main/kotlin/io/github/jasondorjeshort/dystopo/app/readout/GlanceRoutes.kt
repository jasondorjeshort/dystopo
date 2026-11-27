package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response

/**
 * T210, the glance view's debug routes (debug builds only, on the debug port; [GlanceView], [Glance]):
 * - `GET /readout/glance`: whether the view is `open` (and `composed`), its `lines` as drawn, in order (`kind`: [GlanceLine.DEADLINE]
 *   or the readout entry's code; `caption`, `value`, `unit`, `trend`, `plusMinus`, `other`, `full`, `dimmed`, `colour`
 *   `normal`, `amber` or `red`, `boundsPx` in window pixels), `count`, `description` (what a screen reader says),
 *   `deadline` (`shown`, `source` `navigation` or `recording`: whose turn-around clock), `fit` (`valuePx`, `captionPx`,
 *   `valueSp` (the type size the font scale maps to `valuePx`), `columns`, `fits`: the smallest size fits, `areaPx`: the
 *   room under the hint line), `inside` (every line's bounds within that room), `clipped` (the texts that overflowed, as
 *   `<kind>.<part>`; empty when none), `legible` (open, laid out, fitted, inside and nothing clipped), `night`,
 *   `fontScale`, `keepScreenOn` (the view's flag while open), `opens`, `closes`, `lastOpenedBy` (`bar`, `route`, `help`)
 *   and `lastClosedBy` (`back`, `tap`, `route`), `help` (its help key). Closed, `lines` is empty. T363: each line adds
 *   `reason` (the sentence under the value, null for a plain figure) and `reasonCode`; `reasonPx` the type size each
 *   reason is drawn at; `notes` has `leftOut` (what the bar leaves out for room, or the bar hidden for room),
 *   `recentHeading` and `recent` (what went wrong in the last minute, newest first: a gap, a jump, the clock going back,
 *   the activity changed, the barometer silent, late fixes, the known height's end; a gap still going is left out). A
 *   reason's part that overflows is `<kind>.reason` in `clipped`.
 * - `POST /readout/glance?open=1|0`: opens or closes the view, as a tap on the bar and the back gesture do, and answers
 *   like `GET` once it has laid out its lines or left (at most 3 s). Anything else for `open` is a 400; opening while the
 *   activity is not on screen is a 503 with the `reason`. T372: `&hold=1` with `open=1` makes the view keep what it drew
 *   once the readout's refresh has come in (two refreshes, about 1 s), so a check's frame and its semantics read one scene
 *   (the last minute's "ago" times, a caption's "no fix N s" and a line's state no longer move between them); it answers
 *   once the view holds, and `GET` reports `hold` and `holding`. Closing the view ends the hold.
 * - T384: `lines` ends with the position line (`kind` `position`: `value` the last fix's latitude and longitude in decimal
 *   degrees, or a dash; `reason` where it came from and its age). `gps` is the receiver as the view read it: `searching`
 *   (no GPS fix for the readout's 5 s gap since the device's fixes started; an injected fix that is not `provider=network`
 *   stands in for one), `sinceS` (how long this search has run: from the last GPS fix, or the first start of the device's
 *   fixes in this process: a restart does not start it again), `seen` and `used` (satellites heard, used in a fix), `bestCn0` (dB-Hz), `network` (`accuracyM`, `ageS`) while
 *   the last fix is a network one, else null; read from the status the view holds while open (`gnssHold` true: [Gnss]'s
 *   `glance` holder, status only; `POST /gnss?case=` replaces the satellites). While it searches, every `reasonCode` `no
 *   fix` reads `searching`, and the accuracy's and the fix age's `reason` (else the first lines reading `searching`) say
 *   for how long, the satellites and the best signal. `position` has `lat`, `lon`, `source` (`gps` or `network`),
 *   `provider` (`gps`, `network`, `passive`, `debug` for an injected fix), `ageS`, `accuracyM`, `text` and `line` as drawn
 *   (`source` null before any fix).
 * - T385: `clock` is the GPS clock fault the view read ([ClockLog.fault]: `kind` `disagree` or `unreliable`, `offS`, `lasted`),
 *   null while the clocks agree. Then, unless the GPS searches, the accuracy's `reason` (else the fix age's, else the
 *   position's) reads "GPS clock unreliable" or that the two clocks differ (`reasonCode` `gps clock`; the fix is still
 *   used), the fix age's names the assistance reset once an unreliable fault has lasted 60 s, and `notes.recent` has one
 *   line per fault that ended in the last minute. `POST /readout/skew` makes one for a test.
 */
object GlanceRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/readout/glance" -> Response.json(app.map.onMain { Glance.json() })
        "POST" to "/readout/glance" -> set(app, r)
        else -> null
    }

    private fun set(app: DystopoApp, r: Request): Response {
        val open = when (r.query["open"]?.trim()?.lowercase()) {
            "1", "true", "on" -> true
            "0", "false", "off" -> false
            else -> return Response.json(mapOf("error" to "open=1 or open=0", "got" to r.query["open"]), 400)
        }
        if (open) app.map.backgroundReason()?.let {
            return Response.json(mapOf("error" to "the activity is not on screen", "reason" to it, "route" to r.path), 503)
        }
        val hold = open && r.query["hold"]?.trim()?.lowercase() in setOf("1", "true", "on") // T372
        app.map.onMain {
            if (open) {
                if (hold) Glance.hold = true
                Glance.show("route")
            } else Glance.close("route")
        }
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            val done = app.map.onMain { if (open) Glance.composed && Glance.laidOut && (!hold || Glance.holding) else !Glance.composed }
            if (done) break
            Thread.sleep(50)
        }
        // One more frame, so the lines drawn and the bounds reported are the same frame's.
        Thread.sleep(100)
        return Response.json(app.map.onMain { Glance.json() })
    }

    private const val WAIT_MS = 3000L
}
