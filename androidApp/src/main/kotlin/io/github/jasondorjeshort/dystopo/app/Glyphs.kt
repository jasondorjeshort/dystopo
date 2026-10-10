package io.github.jasondorjeshort.dystopo.app

import java.io.File

/**
 * Where MapLibre loads label glyphs from. With glyph PBFs on disk (`files/glyphs/<font>/<range>.pbf`,
 * pushed by tools/android-push-pack.ps1) the style points MapLibre straight at them with a `file://`
 * template, so glyphs never queue behind slow tile requests on the loopback server. On the first
 * emulator run the glyph requests waited behind 10 s contour tiles, MapLibre's HTTP client timed out
 * and cancelled them, and Native then drew no text at all. A missing range is a NotFound, which Native
 * treats as an empty range (those characters are skipped). Without a glyph directory the HTTP route
 * (`/glyphs/...`, an empty PBF for anything missing) is the fallback.
 */
object Glyphs {
    /**
     * The glyph ranges the style's text needs, as tools/android-push-pack.ps1 pushes them; T41 fetches the same
     * ones from the server with a pack download. T70: the list lives with the style (the local-script second
     * name line needs Greek, Cyrillic, Hebrew, Arabic and more; see [MapLibreStyle.GLYPH_RANGES]).
     */
    val RANGES = io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle.GLYPH_RANGES

    fun styleUrl(glyphDir: File?, httpBase: String): String =
        if (glyphDir != null && glyphDir.isDirectory) "file://${glyphDir.absolutePath}/{fontstack}/{range}.pbf"
        else "$httpBase/glyphs/{fontstack}/{range}.pbf"
}
