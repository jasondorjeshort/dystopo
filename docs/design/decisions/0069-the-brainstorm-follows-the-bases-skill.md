# 0069. The brainstorm follows the base's skill; ADR 0065 superseded in part

Date: 2026-11-25
Status: accepted

Record: Hatchery's ADR 0026 (`../../hatchery/decisions/0026-brainstorm-offer-and-rest-pile.md`) and
its sibling `../../hatchery/decisions/0026-brainstorm-contract.md`, whose section 5 holds the
migration table and this tree's row. No proposal here: the choice was proposed, criticised and
decided in Hatchery with the owner's answers of 2026-11-22 (its Decision, item 10); this record
applies it to this tree. Supersedes [ADR 0065](0065-coordinator-brainstorms-offered-on-yes-with-a-rest-pile.md)
in part (below).

## Context

ADR 0065 made the coordinator's brainstorm standard here and sent its triage text to Hatchery as a
carry-back. Hatchery's review found the text could not land as sent (its formats lived in a
project-owned README; an entry below a blank `Answer:` read as the answer; the git loop of triage
step 4 misread four cases) and decided the practice as a base-owned skill with a reader behind it.
The update of 2026-11-25 (base line `6939e79`) brought that set: the `brainstorm` skill, the base's
`triage` text in place of this project's, `queue-list.js` with five more fields per row (`rest`,
`offered`, `recurrences`, `news`, `uses`) and two placement warnings, and the post-edit hook that
prints the warnings at the edit. The departure row on the `triage` skill closed (ADR 0038).

Verified after the update with `node .claude/hooks/queue-list.js ideas --json`: every idea file
lists, one at rest, none offered, none with a `New:` line; two warnings, both on one entry written
below its block on 2026-11-24, which this commit moves above the block as two lines.

## Decision

1. **One home.** The `brainstorm` skill holds the offer, the cheap check, the formats, the bar's
   table and the rest review. `0065-entry-formats-and-the-bar.md` becomes a pointer to it, and
   `docs/ideas/README.md` names the skill instead of that file. `.claude/rules/docs.md`, Queues, is
   the record where the README says otherwise.
2. **What changes from ADR 0065**, each by its item there:
   - Item 1, "nothing is written unasked": a line answered no still writes nothing; a line the
     owner leaves unanswered is written as an idea file ending in the offer Question (`Question:
     offered <date> and not answered: ...` with a blank `Answer:`). The owner's answer in Hatchery:
     "Yes, but silence is not a no". Such a file counts in the open queue and is the coordinator's
     to act on, never triage's or a build pass's.
   - Item 4, recurrence: an entry is one physical line above the block. `New:` is an entry of its
     own, not a clause of a `Came up again:`. A `Came up again:` is written only for an independent
     source (the owner, a report, a finding, a model the file does not yet name), so every entry
     counts and the "same session as a triage" rule goes.
   - Item 8, triage step 4: no git loop. Triage re-reads the rows whose `block` is `plan` or
     `answered` and whose `news` is not 0, and the block it writes takes the `New:` lines in and
     removes them. A file with a `New:` line is not ready to build, whatever its block.
   - Item 9, landing: a pass that lands a `Plan:` deletes the file when no `Use:` line remains;
     otherwise it removes the block and the file is untriaged again.
   - Item 10, the rest review: given whenever the owner asks; prompted at a release and by one
     closing line on the offer after a triage, at most once in seven days from the last review's
     record. "Every third wave close" goes, and so does its line in the wave record.
   - Item 7, who writes a `Waits:`: the session the owner answered, or the coordinator from words
     the owner wrote under `Answer:`; never a subagent, never by default.
   - New: every offer and every rest review leaves one record line of counts (the skill's formats),
     as a commit subject or one body line of the wrap-up commit. One prompted offer per session; an
     offer the owner asks for in so many words is always made. The offer is made at HOLD too.
3. **What stays from ADR 0065:** at most nine lines, about three each near-term, intermediate and
   moonshot; the cheap check; the Source line; `Use:` lines for the uses of one primitive; the bar,
   one step per doubling of the open count, guidance only; the rest prefix on the owner's quoted
   words; triage never rewriting a `Waits:`; no cost guard on offers; the first rest review listing
   the `Waits:` with no trigger and those written on a blanket leave. The offer's answers are taken
   by multiple choice (`CLAUDE.md`, Questions to the owner).
4. **Cadence here.** The `wave` skill's Close step 6 offers after the release and after a push of
   triaged ideas, through the skill; the `handoff` skill's wrap-up makes an offer the session owed.
5. **Files written before this date** are not rewritten: a `New:` clause inside an older `Came up
   again:` paragraph was read by the triage that followed it and stays as body text. The reader's
   warnings name any entry that must move.

Out of scope: the base's STOP rule (this project's policy sets no deny line). Deferred: a `Rests:`
block and candidates kept at STOP, as Hatchery's ADR 0026 defers them, on its triggers.

## Consequences

- Easier: the formats have one home that every update keeps current; one reader prints every
  state, so no skill greps idea files; an offer the owner does not get to is kept as a question in
  the queue instead of being lost with the session.
- Harder and costs: silence now writes, up to nine files an offer, each raising the open count and
  the bar; entries are long single lines; two prefixes (`Waits: at rest`, `Question: offered`) and
  three entry prefixes to keep exact; a file whose `Use:` lines outlive its `Plan:` returns to
  triage.
- Maintained: the README's three sentences (the skill's README check); the wave skill's pointer.
- Superseded when Hatchery decides the practice differently, or when the prediction below fails.

Predictions (the trigger scan reads these):
- Prediction, check by 2027-01-31: `node .claude/hooks/queue-list.js ideas` prints no placement
  warning, and no row has `offered` true with `block` `question` older than four weeks.
