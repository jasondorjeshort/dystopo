---
date: 2026-11-26
kind: chore
related: [2026-11-25-the-emulator-script-waits-for-a-free-slot.md]
---

# The sweep marks its timed parts when the PC is shared

Source: coordinator (claude-fable-5-2), a wave close (wave 48); offered and accepted by the owner; not sanity-checked.

The sweep's `startup` part compares launch medians with fixed ceilings (`tools/launch-budget.json`). In wave 48 two
verifier sweeps that ran beside another sweep read the first frame at about 4500 ms and the map fully loaded at about
7400 ms, where sweeps alone on the PC read about 3700 and 5800; one launch came within 40 ms of its ceiling, and a
verifier spent a whole pass building `main` to show the branch was not the cause (T357; T358's sweep read the same).
ADR 0070 rules that timed runs go alone, which the coordinator can hold for the closing sweep but not for two
verifiers at once, and four agents at once will make it the usual case.

The idea: `tools/sweep.ps1` (the `startup` part, and any part whose figures are held to a budget) looks at what else
runs when it starts and while it runs (another sweep's lock or servers, another emulator's lock under
`emulators.lockDir`, a Gradle build), says so in `steps.tsv` and `summary.json`, and reports a median over its ceiling
as advisory, not red, when the PC was shared; alone, it is red as today. The closing sweep stays the one run whose
budgets count. Among the sweep's open ideas it comes before
`2026-11-10-random-place-of-the-day-rendered-by-the-sweep.md`: it removes false reds that cost verifier passes now.

Plan: one pass, tools only. Checked at triage: the `startup` part (`tools/sweep.ps1`, the `Part 'startup'` block) runs
`tools/android-startup.py --budget tools/launch-budget.json`, which exits 1 on a median over its ceiling. (1)
`tools/sweep.ps1` samples, at the part's start and every 30 s while it runs, what else holds the PC: emulator lock
files other than its own under the policy's `emulators.lockDir` (read as `Get-EmuLockFile` already reads it; every
sweep and verifier boots through `tools/android-emu.ps1`, which writes one), and the machine's CPU load beside it (a
reading, no process matched by name, `CLAUDE.md`'s rule); it writes `sharedWith` (the other locks' checkouts, the load)
into the part's line of `steps.tsv` and into `summary.json`.
(2) `android-startup.py` gains `--shared` (the sweep passes it when anything was seen): a median over its ceiling is
then `advisory` in the report with its reason and exits 0; an undeclared StrictMode kind stays red either way.
(3) `tools/sweep-summary.py` prints advisory lines apart. Tests in `tools/test_android_startup.py` and
`tools/test_sweep_summary.py`. Verify: a `-Only startup` sweep alone (red on an over-ceiling median as today, using a
lowered copy of the budget) and beside a second emulator (advisory). Docs: the part's lines in `docs/reference/` where
the sweep's parts are described. Out of scope: the closing sweep (stays alone, ADR 0070), other timed parts until
they hold a budget.
