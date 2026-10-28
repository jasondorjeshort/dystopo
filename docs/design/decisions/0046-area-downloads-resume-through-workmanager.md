# 0046. Area downloads survive the background and the process through one WorkManager chain

Date: 2026-10-27
Status: accepted (T156; auto-resume without a toggle is the owner's requirement of 2026-10-26)

Record: task T156 (`docs/TASKS.md`, wave 21), the idea of 2026-10-26 on interrupted area downloads (deleted on
the T156 branch; its in-process half is T151), the wave 21 research on long resumable downloads on Android 12 to
15 (the coordinator's `out/wave21-research/T151-resume-downloads.md`, summarised in T151's and T156's task text),
the wave 21 critic ("a short ADR with named conditions is enough for WorkManager").

## Context

An area download is a daemon thread of the app process (`AreaDownload`). T151 gave it a cursor over T152's rows,
a repair at start, a cheap check (`ResumeCheck`) on app start, on coming to the foreground and when a run ends,
and a backoff with stops that never override the user. Nothing ran it when the app was in the background for long
or its process was gone: the system may freeze or kill a background process at any time, and the next app start
can be days away. The owner (2026-10-26): losing service during a big download is normal, so the phone has to pick
it up again later without being asked, and not only at launch.

The research found no surveyed map app that resumes by itself: two open-source apps run a `dataSync` foreground
service with `START_NOT_STICKY`, cancel at the Android 15 timeout and have no boot receiver; a third resumes only
by hand; the closed apps' behaviour was not established. So there is no split to turn into a setting (ADR 0044):
auto-resume is the behaviour, with no toggle, and data use stays steered by the visible size and the pinned budget
(ADR 0023; no Wi-Fi gate).

What the emulator showed (API 35, T156's runs; the report has the numbers): a non-expedited worker started in
the background that calls `setForeground` gets `ForegroundServiceStartNotAllowedException` ("mAllowStartForeground
false"); started while the app is on screen it gets the foreground. WorkManager stops a running worker when the
network goes (`STOP_REASON_CONSTRAINT_CONNECTIVITY`), keeps the work `ENQUEUED` and runs it when the network
returns, in the living process or in a new one (after `am kill` of the cached process, JobScheduler started a new
process for the job within 5 s of the network's return, with no activity). With the `dataSync` timeout shortened
to 30 s (`device_config`), WorkManager 2.10.5 stopped the worker with `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` and
ran it again at once as a plain job. After `adb reboot` with a download running, WorkManager's boot receiver
started the process, its job ran the worker (which got the foreground that time, inside the boot broadcast's
window) and the download finished without the app being opened. One more
fact shaped the design: in the background a cached app's network is blocked outside a job (`dumpsys netpolicy`:
`blocked=APP_BACKGROUND`), and WorkManager's in-process scheduler runs a due step in a living process before
JobScheduler's job starts (the job then finds the work "already enqueued" and holds itself open while it runs), so
a download started in that window failed every tile in three seconds.

## Decision

1. **One unique one-time work**, `area-download` (`app/areas/DownloadWorker.kt`), WorkManager 2.10.5 with the
   constraint `NetworkType.CONNECTED`, never `UNMETERED` or `NOT_ROAMING` (ADR 0023), and exponential backoff
   (30 s) for a run that fails outright. The constraint is the connectivity trigger: no `ConnectivityManager`
   callback of our own. WorkManager's own persistence is the reboot trigger: no boot receiver of our own.
2. **The worker adds no resume logic.** A run reads the records (T151's repair at start), watches a download
   already running or runs T151's check with the trigger `worker` (which, like the user's triggers, skips the
   backoff's wait: the chain's delay and constraint were the wait), and watches downloads while they follow one
   another. At the end it appends the next step (`APPEND_OR_REPLACE`, a child of the running work) when a record
   may still resume, at that record's T151 backoff (at least 30 s; 10 min for a record its check left ready),
   else the chain ends. A cancelled, stopped or refused record ends it (T151's reasons).
3. **The hand-over.** Every `AreaDownload.start` enqueues the work: a user's start (`trigger` `user`: Download,
   Retry, a trip's corridor) with `REPLACE`, so a fresh worker runs now instead of after a backoff the chain was
   waiting out, and every other start with `KEEP`. A check that finds a record that may resume and starts nothing
   enqueues it with `KEEP` at the backoff's end, which is how the chain comes back after a force-stop.
4. **The foreground.** The worker asks for the foreground with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` and a
   notification on the channel "Area downloads": the title "Downloading <name>", the text "N of M tiles" with a
   progress bar ("Getting the tile list" before the manifest), and Cancel. The call is wrapped: refused (a run
   started in the background, or Android 15's six hours a day of `dataSync` spent), the run goes on as a plain job,
   which the system stops after about ten minutes. Cancel reaches `DownloadCancelReceiver` (not exported, only its
   `PendingIntent`), which is the user's cancel (`AreaDownload.cancel`, kept in the record, never resumed).
5. **A stop.** When the system stops the worker (the constraint, the job's time, the foreground service's timeout,
   quota) with the app in the background, `AreaDownload.pause` ends the walk as a network stall (the cursor saved,
   the record partial with "The download paused in the background.", no failure counted) and WorkManager runs the
   stopped work again. With the app on screen the download goes on in the process (a lost network stalls the walk
   by itself). A stop by the app (a tap's `REPLACE`) leaves the download alone.
6. **The notification permission.** `POST_NOTIFICATIONS` is asked through `tracking/NotificationPrompt.kt` at
   the first Download in the area picker, not at launch, once for recording and downloads together; denied, the
   download runs the same (the foreground notification then shows only in the system's task manager).
7. **Initialisation**: WorkManager's default `androidx.startup` initializer. A process the system starts for the
   job runs `DystopoApp.onCreate` either way (the loopback server, the records), and the default configuration
   is all the chain needs, so a `Configuration.Provider` on `DystopoApp` would be code with nothing to configure.
   Revisit if a custom executor, logging level or on-demand initialisation is wanted.
8. **The manifest**: `FOREGROUND_SERVICE_DATA_SYNC`; WorkManager's `SystemForegroundService` merged with
   `foregroundServiceType="dataSync"` (`tools:node="merge"`); the receiver. WorkManager's own manifest brings
   `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` and its job service.
9. **The network in the background.** A download starts in the process only while the app is on screen or the
   process's network is usable (`ConnectivityManager.getActiveNetwork` answers null for a blocked app); otherwise
   the check leaves a ready record to the chain. A worker in the background waits for a usable network (5 s polls,
   up to 9 min, then `Result.retry()`) before its check, which is the moment JobScheduler's job unblocks it.
10. **Debug**: `GET /areas/resume` `chain`: the work's state, attempts, last stop reason, next run time and
   network constraint, and this process's runs (foreground or the refusal, what the check started, what was
   watched, the outcome); log tag `DystopoResume`.

Not covered: a user's force-stop cancels WorkManager's jobs until the app is next opened; nothing can fix that, and
T151's start trigger then runs the check, which enqueues the chain again. A periodic safety net (6 to 12 h, only
while a record is unfinished) is not built; it comes if a device ever shows the chain lost without a force-stop.

## Rejected alternatives

- **An own `dataSync` foreground service** (what the surveyed apps do): survives the background, but nothing
  starts it after a process death or a reboot (Android 15 forbids starting a `dataSync` service from
  `BOOT_COMPLETED`), and it needs its own connectivity watch. WorkManager gives both.
- **User-initiated data transfer jobs** (JobScheduler, API 34+): no standby quota, but they must be scheduled while
  the app is visible (not for an unattended resume), have no Jetpack wrapper and would double the code beside the
  WorkManager path older phones need. Worth a look for the tap's start only, later.
- **`DownloadManager`**: survives everything, but one URL to one file; an area is up to hundreds of thousands of
  small tile requests that go through the app's own fetcher, cache and validation.
- **A periodic worker**: replaced by the constraint-driven chain (above).

## Consequences

- WorkManager 2.10.5 and its transitive set enter the app: Room 2.6.1 and its SQLite, `androidx.startup`,
  lifecycle-service, concurrent-futures, tracing; all Apache-2.0, `licenseCheck` the proof. About a megabyte of
  APK and a small Room database in the app's files.
- The app process now also starts in the background for the job (the loopback server and records come up with
  it), as it does for any job.
- Two backoffs exist: T151's in-process timer and the chain's step delay, both read from the record's
  `autoResume.nextAt`, so they agree; the one-download-at-a-time rule and the check's coalescing absorb a double
  trigger.
- A background-started run is a slice of about ten minutes, so a phone left alone for days advances in slices
  (Doze and the standby buckets set how often); a foreground run has up to six hours a day.

Predictions (the trigger scan reads these).
- Prediction, check when the owner's phone next runs a large area download over a day with the app closed: the
  record advances without the app being opened, and `GET /areas/resume` `chain` shows runs with stop reasons.
- Prediction, check by 2027-04-30: no report of a download resumed after the user cancelled it.
