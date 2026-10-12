package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import java.io.File

/**
 * `devtool serve`: a thin command over the server pieces in `serverlib` (T52). Without `--live`,
 * [PackServer] (the Android app's loopback tile server on the desktop, for the phone style and the
 * on-device contour tiles); with `--live`, [LiveTiles] (packs first, else live DEM and OpenFreeMap,
 * pack builds, the sync peer, the catalog). The standalone `server` module runs the same live routes
 * behind a config file (docs/SERVER.md).
 */
class ServeCommand : CliktCommand(name = "serve") {
    override fun help(context: Context) =
        "Serve a region pack like the Android app does (style, DEM, OSM, generated contours, glyphs) on 127.0.0.1. " +
            "Also hosts out/ at /out/<file> for pack downloads (byte ranges, ETag, If-Range); add ?kbps=N to a /out/ URL " +
            "to throttle the body to N kilobytes per second, e.g. /out/rainier-dem.pmtiles?kbps=20 to interrupt a download mid-way. " +
            "GET /catalog lists the packs in out/ with sizes and SHA-256 hashes (single archives as single-file packs); " +
            "POST /catalog/refresh rehashes every file, ignoring the .sha256 sidecars, and lists those that changed. " +
            "Map options as on the phone, in the query string of /style.json, / and /phone: hillshade=off|single|multi, " +
            "hillshadeIntensity=0..1, slope=on, contourInterval=auto|<n>, contourLabels=sparse|normal|dense, night=on, " +
            "trails|water|landcover|pois|boundaries=off (tiles at /shade/{z}/{x}/{y}.png and /contours/...?interval=<n>). " +
            "With --live: /contours/{z}/{x}/{y}.pbf?units=ft|m and /style.json?units=ft|m (or ?lat=&lon= for the default there: " +
            "feet in the US, metres elsewhere), /live/units?lat=&lon=, /live/sources?bbox=w,s,e,n&dz=&oz= (sources and attribution of a view)."

    private val dem by option("--dem", help = "Terrarium DEM PMTiles").default("out/rainier-dem.pmtiles")
    private val osm by option("--osm", help = "OSM vector PMTiles (empty for terrain only)").default("out/rainier-outdoors.pmtiles")
    private val glyphs by option("--glyphs", help = "glyph PBF directory (<font>/<range>.pbf); tools/android-push-pack.ps1 fills cache/glyphs")
    private val port by option("--port").int().default(8687)
    private val textScale by option("--text-scale", help = "label size multiplier, as the app's textScale setting (default 1.0)").double().default(1.0)
    private val live by option("--live", help = "live world mode: packs in out/ first, else DEM from the cloud sources and OSM from OpenFreeMap, cached (see LiveTiles)").flag()

    override fun run() {
        val glyphDir = File(glyphs ?: (System.getenv("DYSTOPO_CACHE") ?: "cache") + "/glyphs")
        if (live) return LiveTiles.findPacks(listOf(File(dem), File(osm))).let { (d, o) -> LiveTiles(d, o, tileCacheRoot = LiveTiles.tileCacheFromEnv(), caps = LiveTiles.CacheCaps.fromEnv()).serve(port, glyphDir, textScale = textScale) }
        PackServer(File(dem), osm.takeIf { it.isNotEmpty() }?.let { File(it) }, glyphDir, textScale).serve(port)
    }
}
