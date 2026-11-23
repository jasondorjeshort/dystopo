---
date: 2026-11-22
kind: chore
related: [2026-11-17-a-blank-screenshot-is-retaken-or-fails.md]
---

# A failed tour view saves its own evidence before anyone runs it again

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

In T330's verification the device tour's `longmire-z11` never went idle twice (131 s, the app at 3.5 cores) and then passed; a second run failed three scroll checks of the cards step and nothing else. Both were read as flakes because nothing was left to examine: by the time a person looks, the app is idle and the re-run has overwritten the report. Neither has been seen again, so neither can be explained.

What it would take: when a view or a cards check of `tools/android-check.py` fails, the tool itself, before returning, saves `GET /debug/cpu` twice ten seconds apart (the busiest threads by name), the pending tiles and their sources, the last 200 lines of logcat for the package and, for a cards check, the accessibility dump it judged; `tools/verify-task.ps1` and the sweep keep that folder beside the step's JSON and never overwrite it on a re-run.

## Open

- The same for the release parts of `tools/android-ui.py`, which cannot ask debug routes: logcat and `dumpsys` only.
- A flake ledger: one line per unexplained failure with its evidence folder, so a second sight is recognised (the handoff carried these two by hand).

Plan: Owns `tools/android-check.py`, `tools/test_android_check.py`, `tools/verify-task.ps1`, `tools/sweep.ps1`,
`tools/test_verify_task.py`, `docs/reference/android-debug-routes.md` (or the tour's section where the tour is
documented). Today the summary keeps `logcat_since` lines and a view's `notIdleBecause`, nothing else. Add an
`evidence(server, exe, serial, label, kind)` helper called the moment a view, a cards check or the direct step fails:
`GET /debug/cpu` twice 10 s apart, `/health` (pending tiles and `notIdleBecause`), `/stats/requests`, the package's
last 200 logcat lines, and for a cards check the accessibility dump it judged, into `<stem>-evidence/<label>/`; the
view's report gains `evidence: <folder>`; each read is guarded so a dead app still leaves what it could. In
`verify-task.ps1` and `sweep.ps1` a re-run of the tour writes to a new numbered stem instead of over the failed one.
Verify: a fixture test in `test_android_check.py` (a fake server that fails one view: the folder and its files exist);
one emulator tour with a view forced to fail (`--wait-ms 1`), the folder read by hand. Out of scope: `android-ui.py`'s
release parts and the flake ledger (both under Open).
