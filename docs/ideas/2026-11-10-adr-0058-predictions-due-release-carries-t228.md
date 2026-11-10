---
date: 2026-11-10
kind: chore
---

# ADR 0058's two predictions are due: releases carry T228 and release-build reports have arrived

The ADR 0012 scan of 2026-11-10. ADR 0058 (`docs/design/decisions/0058-release-builds-keep-true-stacks.md`)
predicts (1) "when a release carries T228" that on the owner's phone the startup history's first-frame median and
the feel of panning are no worse than an optimised build of the same commit, within 10 percent; and (2) "when the
first `startup-` or `freeze-` report arrives from a release build" that its app frame names a real class and a line
matching the source at that tag. Releases since v0.1.22-alpha carry T228, and the owner's startup reports from a
release build (issues 31 and 32, wave 30's T242) have arrived; neither line has an Outcome.

Plan: the coordinator (architect) records (2)'s Outcome from issues 31 and 32 (the frame `TileCache.<init>` and its
line checked against the source at that release's tag). For (1), an Outcome of "not measured" with the reason unless
the owner has run the optimised build; if the comparison is still wanted, it goes to the owner as a Question in a new
idea (it needs a second signed build on the phone), not guessed.
