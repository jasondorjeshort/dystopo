package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * T74: the versioned list of style assets a server offers (`GET /assets/manifest.json`): the label glyph ranges
 * and the marker sprite sheet, each with its SHA-256, so a phone that already has assets fetches exactly the
 * files that changed (the T70 sprite and glyph change never reached installs that had the old files, because
 * the phone only fetched files it lacked).
 *
 * [Entry.path] is where the file lives under the phone's files dir (`glyphs/<font>/<range>.pbf`,
 * `sprites/<file>`), [Entry.url] the server path to fetch it from. [Manifest.version] changes whenever any
 * entry does (a hash of every path and SHA-256), so one string compared per check says whether anything changed;
 * the server's `/version` carries it as `assets`.
 */
object AssetManifest {
    const val ROUTE = "/assets/manifest.json"
    const val SPRITE_DIR = "sprites"

    class Entry(val path: String, val url: String, val sha256: String, val bytes: Long) {
        fun json(): Map<String, Any?> = linkedMapOf("path" to path, "url" to url, "sha256" to sha256, "bytes" to bytes)
    }

    class Manifest(val version: String, val files: List<Entry>) {
        fun json(): String = MiniJson.encode(linkedMapOf("version" to version, "files" to files.map { it.json() }))
    }

    fun glyphPath(font: String, range: String) = "glyphs/$font/$range.pbf"

    fun spritePath(file: String) = "$SPRITE_DIR/$file"

    /** A manifest of [files], with its version computed from them (order does not matter). */
    fun of(files: List<Entry>): Manifest = Manifest(versionOf(files), files.sortedBy { it.path })

    /** 16 hex digits over every entry's path and hash (FNV-1a 64; the entries carry the cryptographic hashes). */
    fun versionOf(files: List<Entry>): String {
        var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        for (e in files.sortedBy { it.path }) for (c in "${e.path}\u0000${e.sha256}\n") {
            h = h xor (c.code.toLong() and 0xffff)
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }

    /**
     * A path a phone may write: under `glyphs/` (font and range, `.pbf`) or `sprites/`, relative, with no empty,
     * `.` or `..` segment and no backslash; anything else in a manifest is refused, so a server cannot point a
     * write outside the asset directories.
     */
    fun safePath(p: String): Boolean {
        if (p.isEmpty() || p.startsWith("/") || '\\' in p || ':' in p) return false
        val parts = p.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return when (parts[0]) {
            "glyphs" -> parts.size == 3 && parts[2].endsWith(".pbf")
            SPRITE_DIR -> parts.size == 2
            else -> false
        }
    }

    private val HEX64 = Regex("[0-9a-f]{64}")

    /** Reads a manifest; entries with an unsafe path ([safePath]), a bad hash or a URL that is not a server path are an error. */
    fun parse(text: String): Manifest {
        val o = MiniJson.parseObject(text)
        val version = o["version"] as? String ?: throw IllegalArgumentException("manifest without version")
        val files = (o["files"] as? List<*> ?: throw IllegalArgumentException("manifest without files")).map { raw ->
            val m = raw as? Map<*, *> ?: throw IllegalArgumentException("manifest entry is not an object")
            val path = m["path"] as? String ?: throw IllegalArgumentException("manifest entry without path")
            val url = m["url"] as? String ?: throw IllegalArgumentException("manifest entry without url")
            val sha = (m["sha256"] as? String)?.lowercase() ?: throw IllegalArgumentException("manifest entry without sha256")
            val bytes = (m["bytes"] as? Number)?.toLong() ?: -1L
            require(safePath(path)) { "unsafe asset path '$path'" }
            require(url.startsWith("/") && !url.startsWith("//") && ".." !in url) { "asset url must be a server path (got '$url')" }
            require(HEX64.matches(sha)) { "bad sha256 for $path" }
            Entry(path, url, sha, bytes)
        }
        return Manifest(version, files)
    }
}
