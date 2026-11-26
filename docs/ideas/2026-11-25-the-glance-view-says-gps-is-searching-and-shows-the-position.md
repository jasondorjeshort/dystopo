---
date: 2026-11-25
kind: feature
related: [2026-11-22-every-value-that-can-dash-or-dim-gets-a-question-mark-and-a-reason.md, 2026-11-25-no-silent-background-work.md]
---

# The glance view says GPS is searching, and shows the position

Seen on the owner's phone, 2026-11-25 (v0.1.43-alpha): the glance view (`app/readout/GlanceView.kt`) showed only dashes
for elevation, speed, accuracy and fix age for over four minutes. Android's GPS engine was running at the app's
request the whole time (11 satellites in view, signal 15 to 28 dB-Hz, none used), and network positions reached the
app every 10 to 20 s; the readout takes GPS fixes only (`MapController.kt` `goodFix`; network fixes feed only a
recording's fallback, `Tracking.kt` `onFallbackFix`), so nothing on screen said why. The first GPS fix came 4 min 20 s
after the request. Wave 48's T363 (merged as d8c9e38a, after v0.1.43-alpha) gives the dashes a reason ("No GPS fix:
shown once fixes come."); it does not say that GPS is searching, how it is going, or where the phone is meanwhile.

The owner, 2026-11-25: "There's already a plan to put this on the (currently open) gps data popup screen. If that's not
in the current wave it needs to be mandatory for the next one. While we're at it though we should add lat/lon here."

Plan: mandatory in wave 49 (the owner, 2026-11-25). (1) While no GPS fix has come, the glance view says GPS is
searching and how: time since the request, satellites seen and used, the best signal, in plain words (the GNSS figures
`app/gnss/Gnss.kt` already reads), and, when a network position is there, that position's accuracy marked as not GPS.
(2) A latitude and longitude line on the glance view, from the last fix (GPS, else the network position, marked), with
its age. Coordinate formats split users (decimal degrees, degrees and minutes, degrees minutes seconds; UTM or MGRS for
some): a benchmark survey first (`brief` skill, `benchmark.md`), then a setting with the researched default (ADR 0044).
(3) The reason table of T363 gains the searching state; the glance and readout routes report it; tests in `shared`; the
emulator shows it with GPS fixes held back and network positions injected. Screenshots and the new sentences in the
report (ADR 0031). Coordinates on a screen are fine; in reports and tests use public places only.
