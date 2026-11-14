---
date: 2026-11-14
kind: chore
related: [2026-11-14-the-sweep-reruns-only-parts-whose-inputs-changed.md]
---

# A task that adds a sweep part runs it in the sweep's order, on a used emulator, before its merge

T268 added the sweep's `startup` part and T270 its `a11y` part; each was verified alone, and both were red at
their first full sweep, the closing one of wave 32, which cost T280 and a second full sweep. The causes were of
two kinds. Order and state: a launch stage went over its ceiling only after the tour had filled the online cache,
and a file left by earlier runs was parsed at launch on a reused emulator. The clock: a line on the route card
depends on the time of day, so a screen's layout moved between runs. Neither shows when the part runs alone at the
verifier's hour. The owner, 2026-11-14: "Yes, soon."

Recommendation (the coordinator): a rule in the `wave` skill's Verify and in `.claude/skills/brief/verifier.md`,
with `tools/verify-task.ps1` doing the run. When a task's Owns line holds `tools/sweep.ps1` or a new part's script,
the verifier runs the sweep from its first part through the new one (`-Skip` for what follows) on one emulator,
not the new part alone; and the part's script pins whatever depends on the clock, or its report says why nothing
does (T280 pinned the route screens' time for `a11y`). A part late in the order costs most of a sweep at
verification; that is the price of not paying two at the close.

## Open

- A cheaper form for a late part: only the device parts before it.

Plan: one no-Gradle pass, soon (the owner's word). `tools/verify-task.ps1` gains `-SweepThrough <part>`: it runs
`tools/sweep.ps1` from its first part through the named one on one emulator (`-Skip` for what follows), so the new
part meets the state the earlier parts leave. The `wave` skill's Verify and `.claude/skills/brief/verifier.md` say
when: a task whose Owns line holds `tools/sweep.ps1` or a new part's script is verified with it, never with the
part alone; `.claude/skills/brief/implementer.md` asks such a task's report to name what in the part depends on the
clock and how it is pinned, or to say that nothing does. Done when: `tools/test_verify_task.py` covers the option's
command line from a fixture; a dry run names the parts it would run for `a11y`; the three skill lines in place. Out
of scope: the cheaper form for a late part, tried once the first has been costed.
