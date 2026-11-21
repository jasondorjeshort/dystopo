---
date: 2026-11-21
kind: chore
---

# Check every screen a wave names against the sweep's steps before launch, not after the sweep

Wave 43's post-wave sweep named `flights-slow` in `-First` (from T316's report). The frame is written by the debug
`flights` part's `slow` step, which the sweep never runs, so no frame of either sweep carried the name. The miss
surfaced only at the end of a 1.8-hour sweep, as a red `diff` step (T288's rule in `tools/sweep.ps1`), and was dropped
by hand with `-SummaryOnly`. The wave skill already asks that every new screen, row or card line name the sweep step
that opens it; nothing checks that the name is a frame the sweep writes.

The owner, 2026-11-21: record it and do it basically right away, next pass perhaps.

Plan: a check that takes screen names and says, for each, which sweep part and step writes a frame of that name, or
"none". The frame names come from the last sweep folder's frames (`ui/`, `a11y/`, `device/`, `first-run/`, as
`tools/sweep-diff.py` pairs them), plus a static scan of the `shot(` / save-name calls in `tools/android-ui.py` and
`tools/android-a11y.py` for frames new since then. Shape: `python -I tools/sweep-diff.py --check-first <names>
[--sweep <folder>]` (or a small sibling script), exit 1 on a name no part writes, JSON out. Use: the wave skill's
Plan and launch step 1 and the sweep brief run it on the Done-when screen names, and the coordinator runs it on the
`-First` list before launching the sweep. Verify: `flights-slow` reports none (or the debug part only); `horizon-point`,
`flights-row` and `flights-refresh-help` report the release `features` part. Docs: the sweep skill's `-First` paragraph and
`docs/reference/release-realism.md`. Out of scope: making the debug flights part write into the sweep.
