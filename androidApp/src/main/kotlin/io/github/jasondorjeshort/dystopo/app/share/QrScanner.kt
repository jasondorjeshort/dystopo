package io.github.jasondorjeshort.dystopo.app.share

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * T57: the in-app QR scanner ("Scan QR code" on the Share screen). CameraX (Apache-2.0) delivers the
 * preview and the frames; [QrDecoder] (pure Kotlin) reads the luminance plane. A `dystopo://share` link
 * fills the Share screen's address and code ([ShareLink.open], source `qr`) and closes the scanner; the
 * user then taps Sync. Anything else read is shown and ignored.
 */
object ScanUi {
    var visible by mutableStateOf(false)

    @Volatile var frames = 0L
        private set
    @Volatile var decodeMsTotal = 0L
        private set
    @Volatile var camera: String? = null
    @Volatile var error: String? = null
    @Volatile var lastText: String? = null
        private set
    @Volatile var reads = 0
        private set

    fun counted(ms: Long) {
        frames++
        decodeMsTotal += ms
    }

    fun read(text: String) {
        lastText = text
        reads++
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "visible" to visible, "camera" to camera, "frames" to frames,
        "decodeMsAvg" to if (frames > 0) decodeMsTotal / frames else null,
        "reads" to reads, "lastText" to lastText, "lastMiss" to QrDecoder.lastMiss?.toString(), "error" to error,
    )
}

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xFFFFFCF5)
private val Line = Color(0xFFE2D8C6)

@Composable
fun QrScannerOverlay() {
    if (!ScanUi.visible) return
    BackHandler { ScanUi.visible = false }
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) ScanUi.error = "camera permission denied"
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var status by remember { mutableStateOf("Point the camera at the QR code on the other phone's Share screen.") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val t = ScanUi.lastText
            status = when {
                ScanUi.error != null -> "Camera: ${ScanUi.error}"
                t != null && ShareLink.parse(t) == null -> "That code is not a Dystopo share link: ${t.take(60)}"
                ScanUi.frames > 0 -> "Looking for a QR code... (${ScanUi.frames} frames)"
                else -> status
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) CameraPreview()
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp)
                .background(Paper, RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("Scan a share code", Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
                BasicText(
                    "Close", style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.border(1.dp, Line, RoundedCornerShape(16.dp)).clickable { ScanUi.visible = false }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            BasicText(if (granted) status else "Dystopo needs the camera to read the code. You can also type the address and code.", style = TextStyle(color = Ink, fontSize = 13.sp))
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
}

@Composable
private fun CameraPreview() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-qr").apply { isDaemon = true } }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image -> image.use { analyze(it) } }
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA.also { ScanUi.camera = "back" }
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA.also { ScanUi.camera = "front" }
                    else -> throw IllegalStateException("no camera")
                }
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, analysis)
                ScanUi.error = null
            } catch (e: Exception) {
                ScanUi.error = e.message ?: e.javaClass.simpleName
                Log.w(ShareSession.TAG, "camera failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
}

/** One frame: the Y plane to grey levels, then the decoder; a share link fills the Share screen. */
private fun analyze(image: ImageProxy) {
    val plane = image.planes[0]
    val w = image.width
    val h = image.height
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val lum = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * rowStride
        for (x in 0 until w) lum[y * w + x] = buf.get(row + x * pixelStride).toInt() and 0xFF
    }
    val t0 = SystemClock.elapsedRealtime()
    val r = QrDecoder.decode(lum, w, h)
    ScanUi.counted(SystemClock.elapsedRealtime() - t0)
    if (r != null) {
        ScanUi.read(r.text)
        DystopoApp.instance.map.onMain {
            if (ShareLink.open(r.text, "qr")) ScanUi.visible = false
        }
    }
}
