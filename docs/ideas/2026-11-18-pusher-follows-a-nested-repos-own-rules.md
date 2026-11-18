---
date: 2026-11-18
kind: chore
---

# The pusher follows a nested repository's own rules

Anchor: `.claude/agents/pusher.md`.

A sibling's pusher, briefed to push a nested repository (`git -C <repo> push`), ran its own tree's
doc-cap and handoff checks over that repository and refused on over-threshold docs and a handoff
total the push had not touched, though the nested repository's own docs rule refuses only a file the
push adds or changes. Its test check likewise asked for the full suite where the nested repository
forbids it before a push and runs a push-time set instead.

The sibling's fix, a departure there: a paragraph in the pusher saying that for a nested repository
every check runs inside it and that repository's own rules win where they state a check differently;
a cap or test it marks warning-only is reported as a warning, not a stop.

It is the pusher's half of the mismatch guard rule 20 fixed on 2026-11-18 (a nested push is scanned
with that repository's own privacy section too). It came without a diff, so it is an idea, not a
carry-back: an agent definition's behaviour, worth a design pass on how the pusher finds the
nested repository's rules (its `CLAUDE.md` imports, its `policy.json`) before an edit.
