package io.github.jasondorjeshort.dystopo.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.MainActivity

/**
 * T159: the recording's notification (owner, 2026-10-28: ongoing, tied to the recording, opening it). One
 * notification ([ID]) on the [CHANNEL] channel, the [TrackingService]'s foreground notification while the service
 * runs and a plain ongoing one while it cannot ([RecordingStatus.serviceDown]). It shows the state ([RecordingStatus]):
 * the title and text, the time since the start as the system's chronometer while recording (so the text is not
 * redrawn every second), Pause or Resume and Stop. A tap opens the app on the track ([EXTRA_TRACK], [handle]).
 *
 * The channel is "Track recording" at low importance: silent, no heads-up, an icon in the status bar; the
 * foreground-service type is `location` (the only type under which a service started in the foreground keeps the
 * fixes coming with the app in the background, Android 10 and up).
 *
 * Dismissal (Android 14 and up): the platform lets the user swipe away a foreground service's ongoing notification
 * while the service runs on (https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications).
 * The swipe reaches [TrackActionReceiver] through the delete intent; the recording goes on and the notification is
 * posted again at the next tick ([update] with a re-post pending, within [Tracking.TICK_MS]) or the next state change.
 *
 * Posting is throttled: a changed state or a re-post goes out at once, a changed distance at most every [MIN_UPDATE_MS].
 * Called on the tracking thread.
 */
class TrackNotification(private val context: Context) {
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private var lastKey: String? = null
    private var lastState: RecState? = null
    private var lastPostAt = 0L

    @Volatile var posts = 0L
        private set
    @Volatile var dismissals = 0L
        private set
    @Volatile var reposts = 0L
        private set
    @Volatile private var repostPending = false
    @Volatile var shown: RecordingStatus? = null
        private set

    /** The user swiped the notification away (its delete intent); it comes back at the next [update]. */
    fun dismissed() {
        dismissals++
        repostPending = true
        Log.i(Tracking.TAG, "recording notification dismissed (${dismissals}); the recording goes on, re-posted at the next tick")
    }

    /** Builds the notification for [s] (the service's `startForeground` needs one). */
    fun build(s: RecordingStatus, trackId: String?, startedAtWall: Long?): Notification {
        ensureChannel(context)
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(s.title)
            .setContentText(s.text)
            .setStyle(Notification.BigTextStyle().bigText(s.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent(context, trackId))
            .setDeleteIntent(action(context, ACTION_DISMISSED, 3))
        if (s.state == RecState.RECORDING && startedAtWall != null) b.setWhen(startedAtWall).setShowWhen(true).setUsesChronometer(true)
        else b.setShowWhen(false)
        if (s.state != RecState.IDLE) {
            if (!s.serviceDown) {
                if (s.state == RecState.PAUSED) b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Resume", action(context, ACTION_RESUME, 1)).build())
                else b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Pause", action(context, ACTION_PAUSE, 1)).build())
            }
            b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", action(context, ACTION_STOP, 2)).build())
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    /** Posts [s] when it changed (see the class comment); [force] posts it now. */
    fun update(s: RecordingStatus, trackId: String?, startedAtWall: Long?, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val key = s.title + "\n" + s.text
        val due = force || repostPending || s.state != lastState || (key != lastKey && now - lastPostAt >= MIN_UPDATE_MS)
        if (!due) return
        if (repostPending) reposts++
        repostPending = false
        lastKey = key
        lastState = s.state
        lastPostAt = now
        shown = s
        try {
            nm.notify(ID, build(s, trackId, startedAtWall))
            posts++
        } catch (e: Exception) {
            Log.w(Tracking.TAG, "recording notification not posted", e)
        }
    }

    /** Called with the notification the service has just put up with `startForeground`. */
    fun posted(s: RecordingStatus) {
        lastKey = s.title + "\n" + s.text
        lastState = s.state
        lastPostAt = SystemClock.elapsedRealtime()
        shown = s
        posts++
        repostPending = false
    }

    fun cancel() {
        runCatching { nm.cancel(ID) }
        lastKey = null
        lastState = null
        shown = null
        repostPending = false
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "channel" to CHANNEL, "id" to ID, "title" to shown?.title, "text" to shown?.text, "state" to shown?.state?.code,
        "posts" to posts, "dismissals" to dismissals, "reposts" to reposts, "repostPending" to repostPending,
    )

    companion object {
        const val CHANNEL = "tracking"
        const val ID = 31
        const val MIN_UPDATE_MS = 10_000L
        const val EXTRA_TRACK = "io.github.jasondorjeshort.dystopo.track"
        const val ACTION_PAUSE = "io.github.jasondorjeshort.dystopo.track.PAUSE"
        const val ACTION_RESUME = "io.github.jasondorjeshort.dystopo.track.RESUME"
        const val ACTION_STOP = "io.github.jasondorjeshort.dystopo.track.STOP"
        const val ACTION_DISMISSED = "io.github.jasondorjeshort.dystopo.track.DISMISSED"

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW))
        }

        private fun openIntent(context: Context, trackId: String?): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TRACK, trackId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            context, code, Intent(context, TrackActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )

        /**
         * The notification's tap: [MainActivity] calls this with its start intent and each new one; an intent carrying
         * [EXTRA_TRACK] shows that track and unfolds the track bar. Main thread.
         */
        fun handle(intent: Intent?) {
            val id = intent?.getStringExtra(EXTRA_TRACK) ?: return
            intent.removeExtra(EXTRA_TRACK) // a recreated activity does not show it again
            TrackBar.open = true
            val app = io.github.jasondorjeshort.dystopo.app.DystopoApp.instance
            Thread { runCatching { Tracking.of(app).show(id) } }.start()
        }
    }
}

/** T159: the notification's Pause, Resume and Stop, and its dismissal ([TrackNotification]). Not exported. */
class TrackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val t = Tracking.of(context)
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    TrackNotification.ACTION_PAUSE -> t.pause()
                    TrackNotification.ACTION_RESUME -> t.resume()
                    TrackNotification.ACTION_STOP -> t.stop()
                    TrackNotification.ACTION_DISMISSED -> t.notificationDismissed()
                }
            } catch (e: Exception) {
                Log.w(Tracking.TAG, "notification action ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
