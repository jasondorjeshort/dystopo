---
date: 2026-11-10
kind: chore
---

# ADR 0056's first prediction is due: the first sweep after B2 shipped

The ADR 0012 scan of 2026-11-10. ADR 0056 (`docs/design/decisions/0056-one-name-one-list-for-offline-maps.md`)
predicts, "check at the first sweep after B2 ships: no screenshot of that sweep's `ui/` set shows a retired noun".
B2 is T237 (merged 2026-11-07); v0.1.24-alpha's release sweep ran after it (its `strings` part read 0 hits, the
dated log of 2026-11-08), and the line has no Outcome.

Plan: the coordinator (architect) reads that sweep's `ui/` sheets (on the second PC, or the wave 31 mid-wave sweep's
if that one is not at hand, saying which) for the retired nouns `RetiredNounsTest` lists, and records the Outcome on
the prediction's line with the sweep's date; a finding only if a retired noun is seen.
