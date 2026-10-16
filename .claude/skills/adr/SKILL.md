---
name: adr
description: Write an architecture decision record or supersede an existing one, and update the decisions index. Use when asked to record a decision, write an ADR, promote a proposal, turn a decision made in prose (PLANNING, a PR, the owner's words) into a record, or supersede or reverse an earlier decision.
---

# ADR

One file per decision in `docs/design/decisions/NNNN-short-title.md`, under 150 lines, written so
someone can implement from it without asking. Non-obvious decisions get an ADR even without a
proposal; major choices arrive here through the `proposal` skill. From the Hatchery base.

## Write a new ADR

1. Number: if promoting a proposal, reuse its `NNNN`; otherwise the highest number across
   `docs/design/decisions/` and `docs/design/proposals/` plus one. This project's numbers start at
   0020 (ADR 0020). This tree adopted the base (the `Hatchery base:` line in ADR 0020), so it keeps
   its own numbering whatever `git merge-base HEAD hatchery/main` says (Hatchery's ADRs 0008 to
   0010); a proposal takes the number of the ADR it will become.
2. Write from `template.md` in this directory with the Write tool. Status `accepted` for a technical
   decision the coordinator settled after the critic step, or an owner's decision; `proposed` while
   the owner must still decide (anything humans see or touch: front end, flows, wording, visuals).
3. Context says what forced the decision and what was verified, with sources (a PLANNING section, a
   log entry, a PR, a measurement). Decision is concrete: names, paths, data shapes, boundaries,
   failure modes, and every deferred item with the trigger that revives it. Consequences include the
   costs, not only the benefits. Rejected alternatives stay, each with its reason.
4. If anything exceeds 150 lines (specs, tables), put it in a sibling file `NNNN-<topic>.md` and link
   it from the ADR.
5. Index: add a row to `docs/design/decisions/README.md`. If the decision came from a section of
   `docs/PLANNING.md`, that section gets a one-line pointer to the ADR (the measurements stay there).
6. If promoted from a proposal, set the proposal Status to `promoted to ADR NNNN`.

## Supersede an ADR

1. Write the new ADR as above. In its Context, name the ADR it replaces and what changed.
2. In the old ADR set `Status: superseded by NNNN` and add one line under the status pointing at the
   new file. Do not edit the old body; it is the record.
3. Update the index row so the old entry reads "superseded by NNNN".
4. Partial supersession (one section of a long ADR) is still a whole new ADR; say which sections of
   the old one still stand.

## Amend without superseding

Only for tables the ADR itself says are living. Edit the table, nothing else, and say so in the
commit message.
