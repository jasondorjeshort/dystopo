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
        tiles = openTiles()
        val routes = Routes(this)
        server = LocalServer(BuildConfig.SERVER_PORT, threads = 4, handler = routes::handle)
        try {
            server.start()
        } catch (e: Exception) {
            Log.e(LocalServer.TAG, "server failed to start on port ${BuildConfig.SERVER_PORT}", e)
        }
    }

    /** Pack roots: internal files dir first (adb push + run-as), then the app-specific external dir. */
    fun packRoots() = listOfNotNull(filesDir, getExternalFilesDir(null))

    private fun openTiles() = TileService(PackSet.open(packRoots()), cacheDir, BuildConfig.SERVER_PORT)

    /** Reopens the packs (after pushing new ones) and reloads the map style. */
    fun reload() {
        val old = tiles
        tiles = openTiles()
        old.packs.dem?.close()
        old.packs.osm?.close()
        map.onMain { map.loadStyle(tiles.style()) }
    }

    companion object {
        lateinit var instance: DystopoApp
            private set
    }
}
