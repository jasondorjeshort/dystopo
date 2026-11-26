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
