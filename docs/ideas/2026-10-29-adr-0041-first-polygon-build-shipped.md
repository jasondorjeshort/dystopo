---
date: 2026-10-29
kind: chore
---

# ADR 0041's first-polygon-build trigger has fired

`docs/design/decisions/0041-polygon-areas.md` has two items on one trigger, the first polygon build
shipping, which T140 did in wave 20 (v0.1.12-alpha): the prediction that a v0.1.10 phone reading its
`areas.json` after polygon records are written keeps every box and corridor area it had (no
Outcome on the line), and the Deferred boundary search (county, state, park) of its item 6, revived
at the same trigger or when the owner asks. The board's Later already holds the boundary search
"after a licensed boundary source". Found by the triage scan of 2026-10-29.

Plan: the coordinator writes the prediction's Outcome from T140's report and merge commit (its
fixture reads of older `areas.json` files); if the report does not say, a Sonnet verifier runs a
v0.1.10 fixture through the reader on `main` first. For item 6, a dated line on the ADR records that
the trigger fired and that the search waits on the board's Later for a boundary source with a licence
and a registry entry (an amendment, not a build). This file is deleted in the same commit.
