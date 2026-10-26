package io.github.jasondorjeshort.dystopo.app.settings

import android.content.ClipboardManager
import android.content.Context
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response

/**
 * T143, the Diagnostics row's debug routes (debug builds only, on the debug port):
 * - `GET /diagnostics`: a fresh [Diagnostics.Snapshot] as JSON ([Diagnostics.json]): `heapMaxBytes`, `heapUsedBytes`,
 *   `peakHeapBytes` (since launch), `nativeAllocatedBytes`, `pssKb` and `pssReadMs` (how long the PSS read took),
 *   `availMemBytes`, `lowMemory`; the heap guard's `rendersAdmitted`, `rendersWaited`, `trims`, `droppedBytes`,
 *   `lastTrimAt`, and every `onTrimMemory` level as `signals`, `lastSignalLevel`, `lastSignalName`, `lastSignalAt`; the
 *   previous process's end `exit` (`reason`, `reasonName`, `at`, `rssKb`, `pssKb`, the scrubbed `description`; null
 *   below API 30, `exitSupported`); the persisted mark of the session before, `persisted` ([HeapMark.Mark]: peak heap,
 *   native and PSS, last signal level and time, `sessionStartedAt`, `writtenAt`), this session's `markNow` and
 *   `markWrites`; `system` (version, build type, Android, device, memory classes, large heap); the row's `lines` and
 *   `lastSession` as drawn; and `report`, the text Copy report puts on the clipboard. `?shown=1` answers for the
 *   snapshot the row shows instead (404 before the row first opened).
 * - `POST /diagnostics/copy`: what the row's Copy report does, for the row's snapshot (a fresh one when the row has not
 *   opened), then reads the clipboard back on the main thread: `copied`, `clipboard`, `equal`, and the snapshot as
 *   `GET /diagnostics` gives it (`diagnostics`), so the copied numbers can be compared with the JSON's.
 * - `POST /diagnostics/reset`: clears the persisted mark ([HeapMark.reset]); answers like `GET /diagnostics`.
 */
object DiagnosticsRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/diagnostics" -> {
            if (r.query["shown"] == "1") DiagnosticsUi.shown?.let { Response.json(Diagnostics.json(it)) }
                ?: Response.json(mapOf("error" to "the Diagnostics row has not opened in this process"), 404)
            else Response.json(Diagnostics.json(Diagnostics.read(app)))
        }
        "POST" to "/diagnostics/copy" -> copyRoute(app)
        "POST" to "/diagnostics/reset" -> {
            HeapMark.reset()
            Response.json(Diagnostics.json(Diagnostics.read(app)))
        }
        else -> null
    }

    private fun copyRoute(app: DystopoApp): Response {
        val s = DiagnosticsUi.shown ?: Diagnostics.read(app)
        val (copied, clip) = app.map.onMain {
            val text = copyReport(app, s)
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text to cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return Response.json(linkedMapOf("copied" to copied, "clipboard" to clip, "equal" to (copied == clip), "diagnostics" to Diagnostics.json(s)))
    }
}
