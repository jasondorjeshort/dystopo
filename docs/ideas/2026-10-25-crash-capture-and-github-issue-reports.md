---
date: 2026-10-25
kind: feature
related: [2026-10-25-ui-thread-freezes-after-saving-a-view-as-pack.md, 2026-10-25-heap-diagnostics-page-in-release-builds.md]
---

# Crashes, freezes and kills are kept on the phone and can be filed as a GitHub issue

Today a crash or a freeze on a real phone leaves nothing behind: the app has no uncaught-exception
handler and does not read `ApplicationExitInfo`, so a tester can only describe what they saw. The
owner's freeze after saving a pack (the related bug) left no thread dump for that reason.

Shape agreed with the owner in chat (2026-10-25):

- **Capture, on the phone only.** An uncaught-exception handler writes the stack trace to the app's
  private files before the default handler runs. On the next launch the app reads
  `ApplicationExitInfo` (Android 11+): the reason (crash, ANR, low memory, killed by the system), the
  time, and for an ANR the thread dump from `getTraceInputStream()`. The UI-thread watchdog's freeze
  dumps (the related bug's Plan) go in the same store. A few recent reports are kept; the oldest are
  dropped.
- **A "Report a problem" row** under Settings › About and advanced. It lists the kept reports, shows
  one exactly as it would be sent, and offers **Open GitHub issue**: a prefilled
  `https://github.com/jasondorjeshort/dystopo/issues/new?title=…&body=…` link opened in the browser,
  where the tester signs in and submits under their own account. The full text also goes to the
  clipboard, because long reports are cut at the URL length limit. No token in the app, no server,
  nothing sent without the tester's tap.
- **A privacy notice** on that screen, above the button, for example: "This report will be posted
  publicly on GitHub under your account. It contains the app version, your device model and the
  error details. Locations, place names and pack names are removed; read it before you submit."
- **Scrubbing before display**, so what the tester reads is what goes out: coordinates (decimal
  pairs, `lat=`/`lon=`), tile `z/x/y` paths, bounding boxes, pack and area names, search text, file
  paths under the app's storage and URL query strings become placeholders. Generated pack names
  carry the view's coordinates (`online/RegionDownload.kt`, the `view-%.2f…` format), so they are
  scrubbed here, and should stop carrying coordinates (Open).
- The report body: app version and versionCode, build type, Android version, device model, the
  reason and time, the trimmed stacks (the crashing thread, the UI thread and the blocked workers),
  and the heap numbers of the diagnostics idea when present.

## Open

- Whether generated pack names drop the coordinates (an alias like `view-<date>-<n>`): a data format
  change under ADR 0033, so the task report would name it.
- The strings (ADR 0031: built in alpha, shown with screenshots, the owner adjusts afterwards).
- A separate report site (an endpoint that rate-limits and files issues with its own token) only if
  outside testers outgrow manual filing; it is a new data flow and an API, so it needs the owner and
  a proposal.

Plan: one wave 20 task, after the bug's task lands (it reads the watchdog's dumps), owning a new
`app/reports/` package (handler, `ApplicationExitInfo` reader, store, issue link), the scrubber in
`shared/src/commonMain` (pure Kotlin) with its tests in `shared/src/commonTest`, a new
`settings/ReportRow.kt` and its lines in `settings/SettingsLayout.kt` and `settings/SettingsPages.kt`,
`GET /reports` and `POST /debug/crash` debug routes, and the `android-app.md` and
`android-debug-routes.md` lines. Done when, on the emulator, `/debug/crash` and a forced ANR each
leave a report on the next launch; the shown text has no coordinate, tile path or pack name (scrubber
tests on synthetic input and public areas only); the link opens a prefilled issue page (screenshot);
and the row works on a release APK driven by `tools/android-ui.py`.
