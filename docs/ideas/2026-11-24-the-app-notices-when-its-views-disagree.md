---
date: 2026-11-24
kind: feature
related: [2026-11-24-the-views-cross-check-what-they-read-at-one-point.md, 2026-11-10-real-phone-check-as-a-one-tap-self-test.md, 2026-11-07-search-and-routes-find-nothing-outside-a-pack.md]
---

# The app notices when its views disagree, and offers to send the evidence (moonshot)

Source: coordinator (claude-opus-5-5), after the ideas triage of 2026-11-24; offered and accepted by the owner; not sanity-checked. Moonshot.

The blank Panorama on the owner's phone needs a USB capture because a release build has no debug routes and the app
said nothing beyond "No elevation data here". The idea: on the phone, in release builds, the views' reads are compared
quietly (the cross-check of its sibling idea, run in the app rather than the sweep); when they disagree, for example
the Horizon's terrain from the online source, peaks none and the Panorama blank while a map is on and covers the
place, the app records the moment (the maps open and not opened with their reasons, each view's read, recent log
lines) and offers "Send diagnostics" with it attached, through the existing reports (`androidApp/.../app/reports/`,
`DiagnosticDump.kt`). Lever: faults of this kind report themselves at the moment they happen; it outranks the one-tap
self-test, which runs only when the owner thinks to run it.

## Open

- Which disagreements are faults (shared with the sweep's cross-check), and how often the check runs.
- Nothing leaves the phone without the user's tap (the existing reports' rule).

Question: build the in-app disagreement check after the sweep's cross-check has run for a while, or now beside it?
Options: (1) after `2026-11-24-the-views-cross-check-what-they-read-at-one-point.md` has run in a few closing sweeps
(recommended: the sweep's version settles which disagreements are faults without risking false alarms on a phone, and
the in-app check then reuses its rules from `shared`); (2) one pass now, the check in release builds recording into
the existing reports (`app/reports/DiagnosticDump.kt`) with no prompt, the "Send diagnostics" offer later; (3) rest it.

Answer:
