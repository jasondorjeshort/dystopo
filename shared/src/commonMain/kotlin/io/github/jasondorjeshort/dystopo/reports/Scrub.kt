package io.github.jasondorjeshort.dystopo.reports

import kotlin.math.abs

/**
 * T143: the report scrubber. Every text a user may copy or send from the phone (the Diagnostics row's report; T145's
 * crash, freeze and kill reports) passes through [text] first, so what the user reads is what leaves the phone, and
 * it names no place: the scrub goes further than the surveyed apps, which tell the user to look for locations
 * themselves (the crash-report research of wave 20).
 *
 * The placeholders, in the order the rules run (a later rule never sees what an earlier one replaced):
 *
 * - [FILE] `<file>`: a path under the app's storage or the shared storage (`/data/user/N/...`, `/data/data/...`,
 *   `/data/user_de/N/...`, `/storage/emulated/N/...`, `/storage/XXXX-XXXX/...`, `/sdcard/...`, `/mnt/...`) and any root
 *   given in [Known.roots], the whole path to its end: a file name can be a pack's or an area's.
 * - [QUERY] `?<query>`: the query string of a URL or a route (`http://host/route?from=...` keeps `http://host/route`).
 * - [PACK] `<pack>`: a generated pack name (`view-46.85N-121.76W-z8-14`, the view it was saved from) and the stem of
 *   any `.pmtiles` or `.mbtiles` file name (`<pack>.pmtiles`).
 * - [BBOX] `<bbox>`: four numbers in a row separated by commas, two or more with a fractional part, all within
 *   +-180 (west, south, east, north in either order).
 * - [COORDS] `<coords>`: a pair of decimals, both with a fractional part, separated by a comma or blanks, one within
 *   +-90 and the other within +-180 (either order: `lat, lon` and GeoJSON's `[lon, lat]`).
 * - [COORD] `<coord>`: the value after a key whose name is or contains the word lat, lon, lng, latitude or longitude
 *   (`lat=`, `"lon": `, `centerLat:`, `latitude=` in `LatLng [latitude=..., longitude=...]`), a value in degrees and
 *   minutes (`46°51'N`), and a lone decimal within +-180 with five or more fractional digits (a metre's precision).
 * - [TILE] `<tile>`: a tile path `z/x/y` with z up to 24 and x and y inside that zoom's grid (its extension kept).
 * - [PACK], [AREA] and [SEARCH]: every name in [Known.packs], [Known.areas] and [Known.searches] (three characters or
 *   more, case-insensitive, anywhere in the text, the longest first).
 *
 * Integers alone, sizes in MB, times and versions are left as they are. Pure; `ScrubTest` runs it on synthetic input.
 */
object Scrub {
    const val FILE = "<file>"
    const val QUERY = "<query>"
    const val PACK = "<pack>"
    const val AREA = "<area>"
    const val SEARCH = "<search>"
    const val BBOX = "<bbox>"
    const val COORDS = "<coords>"
    const val COORD = "<coord>"
    const val TILE = "<tile>"

    /** The names the app knows a report could carry: its packs', its offline areas', recent search texts, extra storage roots. */
    data class Known(
        val packs: Collection<String> = emptyList(),
        val areas: Collection<String> = emptyList(),
        val searches: Collection<String> = emptyList(),
        val roots: Collection<String> = emptyList(),
    )

    /** [text] with every rule's count, for a report that says what was removed. */
    data class Result(val text: String, val replaced: Map<String, Int>)

    /** The scrubbed [s] (see the class KDoc for what each placeholder replaces). */
    fun text(s: String, known: Known = Known()): String = scrub(s, known).text

    fun scrub(s: String, known: Known = Known()): Result {
        val counts = LinkedHashMap<String, Int>()
        fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
        var t = s

        // Paths: the given roots first (longest first), then the platform's storage roots.
        for (root in known.roots.map { it.trimEnd('/') }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }) {
            t = Regex(Regex.escape(root) + PATH_TAIL).replace(t) { count("file"); FILE }
        }
        t = STORAGE.replace(t) { m -> count("file"); m.groupValues[1] + FILE }

        t = QUERY_RE.replace(t) { m -> count("query"); m.groupValues[1] + "?" + QUERY }

        t = VIEW_PACK.replace(t) { m -> count("pack"); m.groupValues[1] + PACK }
        t = PACK_FILE.replace(t) { m -> count("pack"); m.groupValues[1] + PACK + "." + m.groupValues[2] }

        t = BBOX_RE.replace(t) { m ->
            val v = (2..5).map { m.groupValues[it] }
            if (v.count { '.' in it } >= 2 && v.all { abs(it.toDouble()) <= 180.0 }) { count("bbox"); m.groupValues[1] + BBOX } else m.value
        }
        t = PAIR.replace(t) { m ->
            val a = m.groupValues[2].toDouble()
            val b = m.groupValues[3].toDouble()
            if ((abs(a) <= 90 && abs(b) <= 180) || (abs(a) <= 180 && abs(b) <= 90)) { count("coords"); m.groupValues[1] + COORDS } else m.value
        }
        t = KEYED.replace(t) { m ->
            if (isCoordinateKey(m.groupValues[2])) { count("coord"); m.groupValues[1] + COORD } else m.value
        }
        t = DMS.replace(t) { m -> count("coord"); m.groupValues[1] + COORD }
        t = LONE.replace(t) { m ->
            if (abs(m.groupValues[2].toDouble()) <= 180.0) { count("coord"); m.groupValues[1] + COORD } else m.value
        }
        t = TILE_RE.replace(t) { m ->
            val z = m.groupValues[2].toInt()
            val x = m.groupValues[3].toLong()
            val y = m.groupValues[4].toLong()
            if (z <= 24 && x < (1L shl z) && y < (1L shl z)) { count("tile"); m.groupValues[1] + TILE } else m.value
        }

        for ((placeholder, names) in listOf(PACK to known.packs, AREA to known.areas, SEARCH to known.searches)) {
            for (name in names.map { it.trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }) {
                t = Regex(Regex.escape(name), RegexOption.IGNORE_CASE).replace(t) { count(placeholder.trim('<', '>')); placeholder }
            }
        }
        return Result(t, counts)
    }

    /** Whether [key] (an identifier) names a latitude or longitude: one of its words (camel case, `_`, digits) is one. */
    fun isCoordinateKey(key: String): Boolean {
        val words = WORD.findAll(key).map { it.value.lowercase() }
        return words.any { it in COORDINATE_WORDS }
    }

    private val COORDINATE_WORDS = setOf("lat", "lon", "lng", "latitude", "longitude", "long")

    /** The words of an identifier: `centerLat` -> center, Lat; `lon_min` -> lon, min; `LAT` -> LAT. */
    private val WORD = Regex("[A-Z]{2,}(?![a-z])|[A-Z]?[a-z]+|[A-Z]")

    /** What a path runs on to: everything up to a blank, a quote, a bracket, a colon or a separator of a list. */
    private const val PATH_TAIL = "(?:/[^\\s\"'<>()\\[\\]{},;:]*)?"

    private val STORAGE = Regex("(^|[^\\w.])(?:/data/(?:user(?:_de)?/\\d+|data)|/storage/(?:emulated/\\d+|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})|/sdcard|/mnt/[\\w.]+)$PATH_TAIL")

    private val QUERY_RE = Regex("([a-zA-Z][a-zA-Z0-9+.-]*://[^\\s?#\"'<>]*|(?:^|[\\s\"'(=])/[\\w./%~-]*)\\?[^\\s\"'<>)]+")

    private const val NUM = "-?\\d{1,3}(?:\\.\\d+)?"
    private const val DEC = "-?\\d{1,3}\\.\\d+"
    private const val NOT_BEFORE = "(^|[^\\w.\\-<])"

    private val VIEW_PACK = Regex("(^|[^\\w])view-\\d{1,3}(?:\\.\\d+)?[NS]-\\d{1,3}(?:\\.\\d+)?[EW](?:-z\\d+(?:-\\d+)?)?")
    private val PACK_FILE = Regex("(^|[^\\w.\\-])[\\w.\\-]*[\\w-]\\.(pmtiles|mbtiles)\\b")

    private val BBOX_RE = Regex("$NOT_BEFORE($NUM)\\s*,\\s*($NUM)\\s*,\\s*($NUM)\\s*,\\s*($NUM)(?![\\w.])")
    private val PAIR = Regex("$NOT_BEFORE($DEC)(?:\\s*,\\s*|\\s+)($DEC)(?![\\w.])")
    private val KEYED = Regex("(\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*[=:]\\s*\"?)-?\\d+(?:\\.\\d+)?")
    private val DMS = Regex("(^|[^\\w.])-?\\d{1,3}(?:\\.\\d+)?\\s?°(?:\\s*\\d{1,2}(?:\\.\\d+)?\\s?['′])?(?:\\s*\\d{1,2}(?:\\.\\d+)?\\s?(?:[\"″]|''))?(?:\\s*[NSEW](?![A-Za-z]))?")
    private val LONE = Regex("$NOT_BEFORE(-?\\d{1,3}\\.\\d{5,})(?![\\w.])")
    private val TILE_RE = Regex("(^|[^\\w.])(\\d{1,2})/(\\d{1,8})/(\\d{1,8})(?=$|[^\\w/]|\\.\\w)")
}
