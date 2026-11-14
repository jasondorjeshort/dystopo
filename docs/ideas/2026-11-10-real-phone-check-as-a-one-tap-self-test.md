---
date: 2026-11-10
kind: feature
related: [2026-11-08-the-owners-phone-in-the-sweep.md, 2026-11-14-report-recent-stats-in-place-of-report-the-last-start.md]
---

# The real-phone check as one tap: a self-test report sent like "Send diagnostics"

Every alpha's notes end "the real-phone checks of this build are still to come", and the release skill's phone
steps need the owner's phone on a cable at a session. T258 built "Send diagnostics" under Report a problem
(`androidApp/.../app/reports/DiagnosticDump.kt`, `ReportRoutes.kt`): one tap makes a file and the share sheet sends
it. A self-test beside it turns the real-phone check into one tap on the phone and a file, not a session. The owner,
2026-11-10: "Sure."

- "Run the release check" under Report a problem: the build's tag and versionCode, the sensors present (barometer,
  the GNSS providers), a 60 s sample of fixes (accuracy, fix age, speed; positions rounded to the degree), the packs
  installed and the world overview's presence, the tile store's location and state, the last update check's result,
  free storage, the readout's entries and which are shown, the screen's font scale and size; written as JSON and
  sent by the share sheet, with the same rounding and no names.
- `devtool dump-scan` reads it as it reads a diagnostics dump and prints the checks that passed; the release record
  notes "real-phone check: self-test received" with its date.
- Agent-testable: `GET /reports/selftest` on the emulator returns the JSON; a tour step runs it.

## Open

- What the check cannot see: the install dialog, Play Protect, the first-run card; those stay the owner's eyes.

Plan: one pass in `androidApp/.../app/reports/`, after the "Report recent stats" pass (the related idea; both
change `ReportRow.kt`, so the two never run at once). "Run the release check" under Report a problem builds the
JSON the body lists (the build, the sensors, a 60 s sample of fixes with each fix's age, accuracy and speed and the
position rounded to the degree, the offline maps and the world overview, the tile store's place and its
`moveState`, the last update check, free storage, the readout's entries, the font scale and the screen's size),
through `DiagnosticDump`'s scrubber, and hands it to the share sheet as Send diagnostics does;
`GET /reports/selftest` returns the same JSON on a debug build; `devtool dump-scan` reads it and prints the checks
that passed; the `release` skill's phone step gains "or a self-test received, with its date". Done when: `check`
green with a test of the JSON's rounding; a tour step runs it on the emulator; the scan clean on its output;
strings listed (ADR 0031). Out of scope: the install dialog, Play Protect and the first-run card.
