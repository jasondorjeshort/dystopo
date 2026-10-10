package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * Debug routes for [AppSettings] (registered from [Routes]):
 * - `GET /settings`: the current settings as JSON.
 * - `POST /settings?textScale=1.5` (or a JSON body `{"textScale": 1.5}`): sets, persists and applies
 *   the value (the style is rebuilt and reloaded). Unknown keys are a 400, not silently ignored.
 */
class SettingsRoutes(private val app: DystopoApp) {
    /** The response for `/settings`, or null for any other path. */
    fun handle(r: Request): Response? {
        if (r.path != "/settings") return null
        return when (r.method) {
            "GET" -> Response.json(AppSettings.json())
            "POST" -> post(r)
            else -> Response.json(mapOf("error" to "GET or POST"), 405)
        }
    }

    private fun post(r: Request): Response {
        val params = HashMap<String, Any?>(r.query)
        val body = r.bodyText.trim()
        if (body.startsWith("{")) params.putAll(MiniJson.parseObject(body))
        val unknown = params.keys - setOf("textScale", "server")
        if (unknown.isNotEmpty()) return Response.json(mapOf("error" to "unknown settings: ${unknown.sorted()}"), 400)
        params["server"]?.let { OnlineRoutes.setServer(app, it.toString())?.let { e -> return Response.json(mapOf("error" to e), 400) } } // T38
        val raw = params["textScale"] ?: return Response.json(AppSettings.json())
        val scale = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
        if (scale == null || !scale.isFinite()) return Response.json(mapOf("error" to "textScale must be a number"), 400)
        AppSettings.setTextScale(scale)
        val style = app.tiles.style()
        app.map.onMain { app.map.loadStyle(style) }
        return Response.json(AppSettings.json())
    }
}
