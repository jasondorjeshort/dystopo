---
date: 2026-10-25
kind: feature
related: [2026-10-13-battery-efficiency.md, 2026-10-25-crash-capture-and-github-issue-reports.md]
---

# A read-only diagnostics row under About and advanced that shows the heap on any build

Today heap is read only from outside the app, and fully only on a debug build: `GET /health`'s
`memory` block and `GET /online`'s `direct.memory` (`app/Routes.kt`, `app/online/HeapGuard.kt`), read
by the device tour's direct step in `tools/android-check.py`. A release build on a real phone has
nothing but `adb shell dumpsys meminfo` over a cable, so the open questions of
`docs/reference/android-online.md` (the real-phone heap on the large heap, the 0.8 bound, the cold
z8 view) cannot be answered on the walk where they matter, and a background kill leaves no trace
of whether memory was the cause. Differs from the battery idea: that one spends less, this one
only reports.

Shape agreed with the owner in chat (2026-10-25):

- A **Diagnostics** row under Settings › About and advanced (`settings/SettingsLayout.kt`,
  `settings/SettingsPages.kt`, beside the Updates row), on every build, no map overlay. It shows the
  max heap, the heap used now, the peak since launch, the native heap and PSS (the same numbers
  `/health` reports), and the direct sources' counters: renders admitted and waited, trims and the
  bytes they dropped, the time of the last trim.
- A **high-water mark kept across launches**: the app records its peak heap and the last trim level
  every so often (`DystopoApp.onTrimMemory` is the hook for the level), so after a kill the page says
  what the previous session reached.
- A **Copy report** button that puts the same numbers as text on the clipboard, so they can be pasted
  into chat and land in the log as real-phone measurements.
- Read-only; no setting changes; nothing leaves the phone.

## Open

- Whether the peak is sampled on a timer or only at trims and view ends; a timer costs battery.
- Agent-testable: `GET /health` already has the live numbers; the persisted peak needs a route or a
  field of its own, and the release build is checked by `tools/android-ui.py` reading the row's text.
- The strings (ADR 0031: built in alpha, shown with screenshots, the owner adjusts afterwards).

Plan: one wave 20 task owning `settings/SettingsLayout.kt`, `settings/SettingsPages.kt`, a new
`settings/DiagnosticsRow.kt`, `HeapGuard.kt`'s counters and the persisted peak, its test, the
`android-app.md` and `android-debug-routes.md` lines; done when the row shows on a release APK
driven by `tools/android-ui.py`, the copied report matches `/health` on a debug build within one
sample, and the peak survives a force-stop and relaunch on the emulator.
