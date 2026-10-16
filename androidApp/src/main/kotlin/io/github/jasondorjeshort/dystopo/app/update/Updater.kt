package io.github.jasondorjeshort.dystopo.app.update

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * T83: in-app updates from GitHub Releases. Android installs nothing silently outside a privileged store, so
 * the best a sideloaded app can do is find the new build, fetch and check it, and hand it to the system
 * installer with one tap:
 *
 * 1. Once a day (release builds; the first check about 15 s after start; a newly installed version checks at its
 *    first start whatever the build before it recorded, [UpdateFeed.CheckRecord]) and on "Check for updates" in Settings,
 *    read the public releases API ([UpdateFeed.DEFAULT_API], no token, pre-releases included during alpha) on
 *    any network (owner's rule, 2026-10-11: nothing is gated on Wi-Fi).
 * 2. Pick the newest release with a universal APK and decide whether it is newer ([UpdateFeed.verdict]: its
 *    `version.json` versionCode, else its tag against our version name). Not newer: quiet.
 * 3. Download `dystopo-<tag>-universal.apk` into the app's cache (`cache/updates/`), check its SHA-256 against
 *    the release's `SHA256SUMS.txt` (and `version.json` when present; they must agree), then read the APK with
 *    the package manager: the same package, a higher versionCode, and exactly the running app's signing
 *    certificates ([Signing]). Any mismatch refuses the update and says why (map and Settings).
 * 4. Offer it: "Update to <version>" on the map ([UpdateOverlay]) and as a notification; Install hands the file to
 *    [UpdateInstaller] (a `PackageInstaller` session; the system asks the user, and to allow installs from this
 *    app the first time). "Not now", or cancelling the system dialog, declines that version: the daily check
 *    stays quiet about it; "Check for updates" shows it again.
 *
 * Where it reads from: [UpdateFeed.DEFAULT_API], unless `update-api.txt` in the app's external files dir names a
 * releases URL on this device or the home network (loopback, private ranges, `.local`; anything else is ignored),
 * which is how `tools/android-ui.py update` points a release build at its mock API (the file can be written only by
 * adb or the app itself on Android 11+, and a feed can only ever offer an APK signed with our own key). Debug
 * builds also take `POST /update/check?api=` ([UpdateRoutes]) and check only when asked.
 */
object Updater {
    const val TAG = "DystopoUpdate"
    const val API_FILE = "update-api.txt"
    private const val PREFS = "dystopo-updates"
    private const val FIRST_CHECK_DELAY_MS = 15_000L
    private const val POLL_MS = 30L * 60 * 1000
    private const val MAX_APK_BYTES = 300L shl 20
    private const val MAX_TEXT_BYTES = 4L shl 20

    enum class Phase { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, INSTALLING, INSTALLED, DECLINED, REFUSED, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val tag: String? = null,
        val versionName: String? = null,
        val versionCode: Long? = null,
        val message: String? = null,
        val bytes: Long = 0,
        val totalBytes: Long = 0,
        val manual: Boolean = false,
        val at: Long = 0,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "phase" to phase.name.lowercase(), "tag" to tag, "versionName" to versionName, "versionCode" to versionCode,
            "message" to message, "bytes" to bytes, "totalBytes" to totalBytes, "manual" to manual, "at" to at,
        )
    }

    /** The current state; Compose reads it ([UpdateOverlay], [UpdateRow]). */
    var state by mutableStateOf(State())
        private set

    /** The verified APK of a [Phase.READY] state. */
    @Volatile var readyFile: File? = null
        private set

    private lateinit var app: Context
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-update").apply { isDaemon = true } }
    @Volatile private var started = false
    val checks = java.util.concurrent.atomic.AtomicLong()

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called once from the application ([io.github.jasondorjeshort.dystopo.app.DystopoApp]): restores a verified download and, in release builds, starts the daily check. */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        acknowledgedTag = prefs().getString("acknowledgedTag", null)
        worker.submit { restore() }
        if (!BuildConfig.DEBUG_ENDPOINTS) {
            Thread({
                try {
                    Thread.sleep(FIRST_CHECK_DELAY_MS)
                    while (true) {
                        if (due()) runCatching { check(manual = false).get() }.onFailure { Log.w(TAG, "update check", it) }
                        Thread.sleep(POLL_MS)
                    }
                } catch (_: InterruptedException) {
                }
            }, "dystopo-update-daily").apply { isDaemon = true }.start()
        }
    }

    fun initialized() = ::app.isInitialized

    private fun due(): Boolean = checkRecord().due(System.currentTimeMillis(), ownCode)

    /** `lastCheckAt`, `lastAttemptAt` and the versionCode of the build that wrote them (`checkedByCode`, since T99). */
    private fun checkRecord(): UpdateFeed.CheckRecord {
        val p = prefs()
        return UpdateFeed.CheckRecord(p.getLong("lastCheckAt", 0), p.getLong("lastAttemptAt", 0),
            if (p.contains(CHECKED_BY)) p.getLong(CHECKED_BY, 0) else null)
    }

    private fun saveCheckRecord(r: UpdateFeed.CheckRecord) {
        prefs().edit().putLong("lastCheckAt", r.lastCheckAt).putLong("lastAttemptAt", r.lastAttemptAt).putLong(CHECKED_BY, r.byCode ?: ownCode).apply()
    }

    private fun attempted() = saveCheckRecord(checkRecord().attempted(System.currentTimeMillis(), ownCode))

    private const val CHECKED_BY = "checkedByCode"

    // --- the running build ---------------------------------------------------------------------------------------

    val ownCode: Long get() = BuildConfig.VERSION_CODE.toLong()
    val ownName: String get() = BuildConfig.VERSION_NAME

    /** The releases URL and where it came from: `debug` (POST /update/check?api=), `file` ([API_FILE]) or `default`. */
    fun api(): Pair<String, String> {
        if (BuildConfig.DEBUG_ENDPOINTS) prefs().getString("apiOverride", null)?.takeIf { it.isNotBlank() }?.let { return it to "debug" }
        fileApi()?.let { return it to "file" }
        return UpdateFeed.DEFAULT_API to "default"
    }

    /** [API_FILE]'s URL when it names a host on this device or the home network; null otherwise. */
    private fun fileApi(): String? {
        val f = app.getExternalFilesDir(null)?.let { File(it, API_FILE) } ?: return null
        if (!f.isFile || f.length() > 4096) return null
        val url = runCatching { f.readText().lineSequence().firstOrNull()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        if (!CleartextPolicy.isPrivateHost(host) || CleartextPolicy.problem(url) != null) {
            Log.w(TAG, "$API_FILE ignored: only a feed on this device or the home network may replace the GitHub API")
            return null
        }
        return url
    }

    /** Debug builds: replace the releases URL (`default` or empty: back to [UpdateFeed.DEFAULT_API]). */
    fun setDebugApi(url: String?): String? {
        if (!BuildConfig.DEBUG_ENDPOINTS) return "debug builds only"
        if (url.isNullOrBlank() || url == "default") {
            prefs().edit().remove("apiOverride").apply()
            return null
        }
        CleartextPolicy.problem(url)?.let { return it }
        prefs().edit().putString("apiOverride", url).apply()
        return null
    }

    // --- check -------------------------------------------------------------------------------------------------

    /** Runs a check on the update thread. [manual]: "Check for updates" (shows a declined or refused version again). */
    fun check(manual: Boolean): Future<State> = worker.submit<State> {
        checks.incrementAndGet()
        try {
            doCheck(manual)
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            attempted()
            set(State(Phase.FAILED, state.tag, state.versionName, state.versionCode, "Update check failed: ${reason(e)}", manual = manual))
        }
        state
    }

    private fun reason(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "no network (${e.message})"
        is java.net.SocketTimeoutException -> "the server did not answer in time"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun set(s: State) {
        state = s.copy(at = System.currentTimeMillis())
        Log.i(TAG, "update ${s.phase.name.lowercase()}${s.tag?.let { " $it" } ?: ""}${s.message?.let { ": $it" } ?: ""}")
    }

    private fun doCheck(manual: Boolean) {
        val p = prefs()
        attempted()
        set(State(Phase.CHECKING, manual = manual, message = "Checking for updates"))
        val (api, _) = api()
        val releases = UpdateFeed.parseReleases(fetchText(api, json = true))
        val release = UpdateFeed.pick(releases)
        if (release == null) {
            done(State(Phase.UP_TO_DATE, message = "No release with an APK for this phone yet", manual = manual))
            return
        }
        val apk = release.universalApk!!
        val info = release.versionJson?.let { UpdateFeed.parseVersionJson(fetchText(it.url)) }
        val verdict = UpdateFeed.verdict(release, info, ownCode, ownName)
        val name = UpdateFeed.versionName(release.tag)
        if (!verdict.newer) {
            dropDownloads(keep = null)
            done(State(Phase.UP_TO_DATE, release.tag, name, info?.versionCode, "Up to date ($ownName; newest release ${release.tag})", manual = manual))
            return
        }
        if (!manual && p.getString("declinedTag", null) == release.tag) {
            done(State(Phase.DECLINED, release.tag, name, info?.versionCode, "Update to $name not installed (you said not now); Check for updates shows it again", manual = false))
            return
        }
        if (!manual && p.getString("refusedTag", null) == release.tag) {
            // Refused once already (checksum or certificate): not fetched again every day; "Check for updates" retries.
            done(State(Phase.REFUSED, release.tag, name, info?.versionCode, p.getString("refusedWhy", null), manual = false))
            return
        }
        val sumsAsset = release.sums ?: return refuse(release, name, info, manual, "the release publishes no ${UpdateFeed.SUMS}")
        val expected = UpdateFeed.parseSums(fetchText(sumsAsset.url))[apk.name]
            ?: return refuse(release, name, info, manual, "${UpdateFeed.SUMS} has no line for ${apk.name}")
        if (info?.sha256 != null && info.sha256 != expected) return refuse(release, name, info, manual, "${UpdateFeed.VERSION_JSON} and ${UpdateFeed.SUMS} disagree about the APK's checksum")

        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, apk.name)
        dropDownloads(keep = file)
        // A fresh download stays `<name>.part` until the checksum, package, version and certificate checks all pass;
        // only then is it renamed to the name Install uses. A file kept from an earlier check is checked again.
        val candidate = if (file.isFile && sha256(file) == expected) file else {
            file.delete()
            File(dir, apk.name + ".part").also { download(apk.url, it, apk.size, release.tag, name, manual) }
        }
        val actual = sha256(candidate)
        if (actual != expected) {
            candidate.delete()
            return refuse(release, name, info, manual, "the download's checksum (${actual.take(12)}...) does not match ${UpdateFeed.SUMS} (${expected.take(12)}...)")
        }
        verify(candidate)?.let { why ->
            candidate.delete()
            return refuse(release, name, info, manual, why)
        }
        if (candidate != file && !candidate.renameTo(file)) {
            candidate.delete()
            throw IOException("could not store the checked download")
        }
        val archive = Signing.archive(app, file)
        readyFile = file
        p.edit().putString("readyTag", release.tag).putString("readyFile", file.name).putString("readySha", expected)
            .remove("refusedTag").remove("refusedWhy").apply()
        done(State(Phase.READY, release.tag, archive?.versionName ?: name, archive?.versionCode ?: info?.versionCode, "Update to ${archive?.versionName ?: name} is ready", manual = manual))
        UpdateNotification.post(app, release.tag, archive?.versionName ?: name)
    }

    private fun done(s: State) {
        saveCheckRecord(checkRecord().checked(System.currentTimeMillis(), ownCode))
        set(s)
    }

    private fun refuse(release: UpdateFeed.Release, name: String, info: UpdateFeed.VersionInfo?, manual: Boolean, why: String) {
        prefs().edit().putString("refusedTag", release.tag).putString("refusedWhy", "Update to $name refused: $why").apply()
        done(State(Phase.REFUSED, release.tag, name, info?.versionCode, "Update to $name refused: $why", manual = manual))
    }

    /** Why [apk] may not be offered (another package, not newer, another signing key), or null. */
    fun verify(apk: File): String? = UpdateFeed.apkProblem(Signing.archive(app, apk), app.packageName, ownCode, Signing.own(app))

    /** At start: a download verified earlier is offered again when it is still newer, still matches, and was not declined. */
    private fun restore() {
        try {
            val p = prefs()
            val tag = p.getString("readyTag", null) ?: return dropDownloads(keep = null)
            val file = File(File(app.cacheDir, "updates"), p.getString("readyFile", null) ?: return)
            val sha = p.getString("readySha", null)
            if (!file.isFile || sha == null || sha256(file) != sha || verify(file) != null) {
                file.delete()
                p.edit().remove("readyTag").remove("readyFile").remove("readySha").apply()
                return
            }
            val a = Signing.archive(app, file)
            val name = a?.versionName ?: UpdateFeed.versionName(tag)
            readyFile = file
            // Declined: quiet on the map, but Settings keeps its Install button.
            if (p.getString("declinedTag", null) == tag) set(State(Phase.DECLINED, tag, name, a?.versionCode, "Update to $name not installed (you said not now); Install is here when you want it"))
            else set(State(Phase.READY, tag, name, a?.versionCode, "Update to $name is ready"))
        } catch (e: Exception) {
            Log.w(TAG, "restore", e)
        }
    }

    private fun dropDownloads(keep: File?) {
        File(app.cacheDir, "updates").listFiles()?.forEach { if (it != keep) it.delete() }
        if (keep == null) {
            readyFile = null
            prefs().edit().remove("readyTag").remove("readyFile").remove("readySha").apply()
        }
    }

    // --- the user's answers ----------------------------------------------------------------------------------

    /** "Not now" on the map (or the system installer cancelled): this version is not offered again by the daily check. */
    fun decline(why: String = "you said not now") {
        installWait.cancel() // T110: an install waiting for navigation to end does not happen
        waitingForNavigation = false
        val s = state
        val tag = s.tag ?: return
        prefs().edit().putString("declinedTag", tag).apply()
        UpdateNotification.cancel(app)
        set(s.copy(phase = Phase.DECLINED, message = "Update to ${s.versionName ?: tag} not installed ($why); Check for updates in Settings shows it again", manual = false))
    }

    /** "OK" on a refusal shown on the map: that version's refusal is not shown on the map again (Settings still has it). */
    fun acknowledge() {
        val tag = state.tag ?: return
        prefs().edit().putString("acknowledgedTag", tag).apply()
        acknowledgedTag = tag
    }

    /** The refused version whose map message the user dismissed. */
    var acknowledgedTag by mutableStateOf<String?>(null)
        private set

    /**
     * T110: whether an install waits for navigation to end ([InstallWait]); Compose state for the map's offer and the
     * Settings row. Installing replaces the running app, which ends navigation.
     */
    var waitingForNavigation by mutableStateOf(false)
        private set
    private val installWait = InstallWait { io.github.jasondorjeshort.dystopo.app.nav.NavSession.active }

    /** T110: the message an install waiting for navigation shows (map card and Settings). */
    fun waitingMessage(name: String?) = "Update to ${name ?: "the new version"} installs when navigation ends"

    /**
     * Install: hands the verified file to the system installer; returns a problem or null. T110: while navigating it waits
     * for the session to end ([waitingForNavigation], then installs by itself), unless [now] (the user's "Install now").
     */
    fun install(now: Boolean = false): String? {
        val s = state
        val file = readyFile
        if (s.phase != Phase.READY && s.phase != Phase.INSTALLING && s.phase != Phase.DECLINED || file == null || !file.isFile) return "no verified update to install"
        val expected = prefs().getString("readySha", null) ?: return "no checksum recorded for the update; check for updates again"
        if (installWait.request(now) == InstallWait.Decision.WAIT) {
            if (!waitingForNavigation) {
                waitingForNavigation = true
                set(s.copy(phase = Phase.READY, message = waitingMessage(s.versionName ?: s.tag)))
                Thread({
                    try {
                        while (installWait.waiting) {
                            Thread.sleep(1000)
                            if (installWait.poll()) {
                                waitingForNavigation = false
                                Log.i(TAG, "navigation ended: installing ${state.versionName}")
                                install(now = true)
                            }
                        }
                    } catch (_: InterruptedException) {
                    } finally {
                        waitingForNavigation = installWait.waiting
                    }
                }, "dystopo-update-after-nav").apply { isDaemon = true }.start()
            }
            return null
        }
        waitingForNavigation = false
        set(s.copy(phase = Phase.INSTALLING, message = "Installing ${s.versionName}"))
        return try {
            // The installer hashes the bytes it writes into the session and abandons it if they are not the checked ones.
            UpdateInstaller.install(app, file, s.tag ?: "", expected)
            null
        } catch (e: UpdateInstaller.ApkChanged) {
            Log.w(TAG, "install refused", e)
            dropDownloads(keep = null)
            set(s.copy(phase = Phase.REFUSED, message = "Update to ${s.versionName} refused: ${e.message}"))
            "refused: ${e.message}"
        } catch (e: Exception) {
            Log.w(TAG, "install", e)
            set(s.copy(phase = Phase.READY, message = "Install failed: ${e.message}"))
            "install failed: ${e.message}"
        }
    }

    /**
     * From [UpdateInstaller]'s result receiver. The success broadcast usually reaches the new build's fresh process,
     * whose state knows no version yet (T99: the update part's updater-log.txt showed "Installed null"); that build is
     * the installed one.
     */
    internal fun installResult(status: Int, message: String?) {
        val s = state
        when (status) {
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> set(s.copy(phase = Phase.INSTALLED, message = "Installed ${s.versionName ?: ownName}"))
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> decline("the install was cancelled")
            else -> set(s.copy(phase = Phase.READY, message = "Install failed: ${message ?: "status $status"}"))
        }
    }

    /** Debug: forget the declined, refused and ready versions and the last check time. */
    fun reset() {
        installWait.cancel()
        waitingForNavigation = false
        prefs().edit().clear().apply()
        dropDownloads(keep = null)
        UpdateNotification.cancel(app)
        acknowledgedTag = null
        set(State())
    }

    fun json(): Map<String, Any?> {
        val p = prefs()
        val (api, origin) = api()
        return linkedMapOf(
            "state" to state.json(),
            "app" to mapOf("versionName" to ownName, "versionCode" to ownCode, "signers" to runCatching { Signing.own(app) }.getOrDefault(emptyList()), "sdk" to Build.VERSION.SDK_INT),
            "api" to api, "apiFrom" to origin,
            "auto" to !BuildConfig.DEBUG_ENDPOINTS,
            "lastCheckAt" to p.getLong("lastCheckAt", 0), "lastAttemptAt" to p.getLong("lastAttemptAt", 0), "checkedByCode" to checkRecord().byCode,
            "declinedTag" to p.getString("declinedTag", null), "refusedTag" to p.getString("refusedTag", null),
            "readyTag" to p.getString("readyTag", null), "readyFile" to readyFile?.path, "checks" to checks.get(),
            "canRequestInstalls" to (Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()),
            // T110: an install asked for while navigating waits for the session to end; the offer's place on the map
            "waitingForNavigation" to waitingForNavigation, "installsAfterNavigation" to installWait.waitedInstalls,
            "navigating" to io.github.jasondorjeshort.dystopo.app.nav.NavSession.active, "overlay" to UpdateOverlayLayout.json(),
        )
    }

    // --- HTTP --------------------------------------------------------------------------------------------------

    /**
     * Opens [start], following redirects. With the default feed every URL, the first and each redirect target, must
     * be a GitHub host ([UpdateFeed.githubUrlProblem]); a feed from `update-api.txt` or `?api=` keeps only the
     * [CleartextPolicy] rule (its asset URLs point at the same home-network host).
     */
    private fun connect(start: String, json: Boolean): HttpURLConnection {
        var url = start
        val github = api().second == "default"
        repeat(CleartextPolicy.MAX_REDIRECTS + 1) {
            if (github) UpdateFeed.githubUrlProblem(url)?.let { throw IOException("refused: $it") }
            val c = CleartextPolicy.open(url)
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Dystopo/$ownName (Android ${Build.VERSION.SDK_INT})")
            if (json) {
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val next = CleartextPolicy.redirectTarget(c)
            if (next == null) {
                if (c.responseCode != 200) {
                    val code = c.responseCode
                    c.disconnect()
                    throw IOException("HTTP $code from ${URI(url).host}" + if (code == 403 || code == 429) " (rate limited; try later)" else "")
                }
                return c
            }
            c.disconnect()
            url = next
        }
        throw IOException("too many redirects from $start")
    }

    private fun fetchText(url: String, json: Boolean = false): String {
        val c = connect(url, json)
        try {
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 shl 10)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_TEXT_BYTES) throw IOException("answer from ${URI(url).host} is over ${MAX_TEXT_BYTES shr 20} MB")
                }
                out.toByteArray()
            }
            return bytes.decodeToString()
        } finally {
            c.disconnect()
        }
    }

    /** Downloads [url] into [part] (left there for the caller's checks; deleted on any failure). */
    private fun download(url: String, part: File, size: Long, tag: String, name: String, manual: Boolean) {
        if (size > MAX_APK_BYTES) throw IOException("the APK is ${size shr 20} MB, over the ${MAX_APK_BYTES shr 20} MB limit")
        val c = connect(url, json = false)
        var ok = false
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: size
            var got = 0L
            var shown = 0L
            set(State(Phase.DOWNLOADING, tag, name, null, "Downloading $name", 0, total, manual))
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 shl 10)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got > MAX_APK_BYTES) throw IOException("the download is over ${MAX_APK_BYTES shr 20} MB")
                        if (got - shown >= (512 shl 10)) {
                            shown = got
                            state = state.copy(bytes = got)
                        }
                    }
                }
            }
            if (total > 0 && got != total) throw IOException("the download stopped at $got of $total bytes")
            state = state.copy(bytes = got)
            ok = true
        } finally {
            c.disconnect()
            if (!ok) part.delete()
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 shl 10)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return Signing.hex(md.digest())
    }
}
