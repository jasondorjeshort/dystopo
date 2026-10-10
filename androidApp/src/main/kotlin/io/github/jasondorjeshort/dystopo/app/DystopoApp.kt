package io.github.jasondorjeshort.dystopo.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import org.maplibre.android.MapLibre

/**
 * Process-wide state: the opened region pack, the tile service and the loopback server. The server
 * starts with the process so MapLibre can load the style the moment the map view exists.
 */
class DystopoApp : Application() {
    lateinit var tiles: TileService
        private set
    lateinit var server: LocalServer
        private set
    val map = MapController()
    val startedAt = SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        instance = this
        MapLibre.getInstance(this)
        MapLibre.setConnected(true) // T38: every tile comes from our loopback server; MapLibre's own offline check would stop even those
        AppSettings.load(this)
        map.onUnitsSeeded = { Thread({ reload() }, "units-reload").start() } // T33: a first fix seeded the units
        tiles = openTiles()
        io.github.jasondorjeshort.dystopo.app.packs.PackManager.get(this).downloads.resumePending() // T29: finish interrupted pack downloads
        val routes = Routes(this)
        server = LocalServer(BuildConfig.SERVER_PORT, threads = 8, isMapRequest = { Routes.isMapRequest(it) }, handler = routes::handle) // T33: debug routes get their own pool
        try {
            server.start()
        } catch (e: Exception) {
            Log.e(LocalServer.TAG, "server failed to start on port ${BuildConfig.SERVER_PORT}", e)
        }
    }

    /** Pack roots: internal files dir first (adb push + run-as), then the app-specific external dir. */
    fun packRoots() = listOfNotNull(filesDir, getExternalFilesDir(null))

    private fun openTiles(): TileService {
        val packs = PackSet.open(packRoots())
        // T33: the first pack opened seeds the units preference (once; see AppSettings.seedUnits).
        packs.dem?.header?.let { h -> AppSettings.seedUnits((h.minLat + h.maxLat) / 2, (h.minLon + h.maxLon) / 2, "pack:${packs.name ?: "default"}") }
        return TileService(packs, cacheDir, BuildConfig.SERVER_PORT)
    }

    /** Reopens the packs (after pushing new ones) and reloads the map style. */
    fun reload() {
        val old = tiles
        tiles = openTiles()
        old.close() // T35: stop the old service's contour warm-up before its packs close
        old.packs.dem?.close()
        old.packs.osm?.close()
        map.onMain { map.loadStyle(tiles.style()) }
    }

    companion object {
        lateinit var instance: DystopoApp
            private set
    }
}
