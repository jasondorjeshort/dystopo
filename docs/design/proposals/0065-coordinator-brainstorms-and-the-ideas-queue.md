# 0065. The coordinator's brainstorms and a readable ideas queue

Date: 2026-11-22
Status: promoted to ADR 0065

## Question

How and when does the AI coordinator generate new ideas (near-term, intermediate, moonshots;
product, process and tooling, research questions), how do they enter `docs/ideas/`, and how does the
queue stay readable as it grows? The practice already works: the owner reports that ideas offered at
the end of a piece of work "have been very high quality", "nearly always interesting", with some
discarded and many "postponed indefinitely", an accepted outcome, not a failure. What is open is the
shape: the offer, the provenance, a bar that rises with the queue, the handling of half-baked ideas
and moonshots, and what a recurring idea does. The triage skill is base-owned, so the answer reaches
every project built on Hatchery. In ten years a returning idea should grow stronger, a far idea rest
visibly without crowding the ready ones, and the owner's reading per session stay a handful of lines.

## Constraints that matter here

- Only the owner prunes for taste (`docs/ideas/README.md`, ADR 0036, Hatchery ADR 0007); nothing
  lapses into deletion, or out of the record, without the owner.
- Idea frontmatter is `date`, `kind`, optional `related` (`.claude/rules/docs.md`); the kinds and the
  three blocks are a grammar shared across projects (Hatchery ADRs 0007, 0017); changing it is an
  incompatible surface change with a migration (Hatchery ADR 0016). Only the owner decides a `Waits:`.
- The owner's answers of 2026-11-22, binding: no hard cap, a bar rising roughly logarithmically with
  the queue; offers after a triage, at a wave close or release, and when something sparks; recorded
  only on the owner's acceptance; about 3 near-term, 3 intermediate, 3 moonshots ("10-15 feels
  high"); the sanity check at triage; provenance with the agent's model, marked not sanity-checked;
  moonshots welcome and marked; one primitive never presented as several features.
- Duplicates are updates (owner, 2026-11-22): a recurrence records both that it came up again and
  any new modification, on the existing file; recurrent ideas are "often the best ones".
- A separate pile for moonshots and not-ready ideas is a direction the owner leans toward.
- The triage skill's text is a carry-back; a rule, an ADR or a grammar change reaches Hatchery as an
  idea filed there (`carry-back` skill). The held carry-back `carry-back/triage-brainstorm-step` at
  4d00b15 (ADR 0038's departure table) may be replaced.
- No user settings in scope: ADR 0044's settings are the app's; this is process.

## Prior art

Published practice (fetched and checked 2026-11-22; unverified sources dropped):

- Shape Up ch. 7 (https://basecamp.com/shapeup/2.1-chapter-07): "Backlogs are a big weight we don't
  need to carry"; "Really important ideas will come back to you." Ch. 8
  (https://basecamp.com/shapeup/2.2-chapter-08): idea work in a cool-down after each cycle, no
  grooming; an old pitch is revived only when someone chooses to.
- PEP 1 (https://peps.python.org/pep-0001/): Deferred "when no progress is being made"; a sponsor
  must be found. Rust RFC 1068
  (https://github.com/rust-lang/rfcs/blob/master/text/1068-rust-governance.md): "closed as
  postponed". KEP process
  (https://github.com/kubernetes/enhancements/tree/master/keps/sig-architecture/0000-kep-process):
  "provisional" first, an owning group accepts. Common thread: proposer and decider are separate,
  and an idea at rest has a named state rather than a deletion.
- Diehl and Stroebe 1987 (JPSP 53:497-509) and Mullen et al. 1991: individuals out-produce groups;
  Nemeth et al. 2004 (EJSP 34:365-374, https://escholarship.org/uc/item/4k70n7v8): debate beats
  "no criticism", so "defer judgement" is not evidence-backed.
- Sharma et al. 2023 (https://arxiv.org/abs/2310.13548): "sycophancy is a general behavior of
  state-of-the-art AI assistants", the reason an agent's idea is marked unchecked and the owner
  decides. No source gives a per-session cap or a moonshot practice; those are design choices.

This queue (git, 2026-10-13 to 2026-11-22; (H) = classified by regex over commit subjects): 288
idea files added, 116 removed, 172 present; added per ISO week 21, 9, 64, 41, 53, 100. Origin (H):
coordinator-side 155, owner 104, process 29. Removed (H): built 84, already in the code 6, folded 6,
converted 13, scan outcomes 10, **not wanted 0** (the critic rechecked the totals and the zero).
Brainstorm batches: 12 of 57 built; the moonshots of 2026-10-29 all open. Blocks today: 78 answered
but unbuilt (median 20 days), 68 Plans, 15 Waits, 11 untriaged, 0 blank Questions. Of the 15 Waits,
4 are rests with no trigger and 2 were written by the coordinator on a blanket leave. Triage median
0.44 days; the owner answers in sittings of 11 to 37, median 3.8 days. The README's whole-listing
sitting has never happened.

## The pieces every option shares

**The offer.** At most nine lines, grouped near-term, intermediate, moonshot, about three each, one
or two lines per idea naming its anchor. The header states the open count at the session's start,
the bar's step, and how many ideas were held back below it ("held back: 4; ask to see them"). The
owner answers by number: record, record at rest (where an option has rest), fold into a named file,
or no. "Record" and "record at rest" are equal choices; neither is pre-selected.

**The Source line**, the first body line after the H1 (before it, the listing would read it as the
title), within the frontmatter rule:

```
Source: coordinator, generated by claude-sonnet-5-5 and recorded by claude-opus-5-5, after the
triage of 2026-11-22; offered and accepted by the owner; not sanity-checked. Moonshot.
```

It names the model that generated the idea (the agent's own model id, or the Co-Authored-By trailer
of the commit that brought it) and, when the recorder differs, both; the trigger in public terms
only; `Moonshot.` when it is one; the owner's own ideas read `Source: the owner, <context>`. A
`source:` key would change the grammar in every project; a `kind: moonshot` would lose the kind
(a moonshot is a feature or a chore that is far). The `(moonshot)` title suffix stays allowed.

**The cheap check before offering**: a grep, not the sanity check, which stays at triage. The anchor
exists; it is not already built; a matching idea is searched for (README, "Before adding"); the list
is read once for one primitive offered as several uses. A match is offered as "came up again".

**The recurrence entry.** The existing file gains a dated entry above its block, recording the
recurrence and any modification:

```
Came up again: 2026-12-03, coordinator (claude-opus-5-5), at the close of wave 47, while reading
the merged heading correction. New: the manual correction seeds the camera match's bearing, so the
search narrows to plus or minus 15 degrees.
```

`New:` is optional and may add an angle, scope, anchor or constraint. A primitive offered as
several uses records them as `Use:` lines on the primitive's file. Recurrence counts by distinct
source or session: a recurrence in the same session as the triage that read the file is recorded
(for its `New:`) but does not count. The count is shown, as information, at the owner's sittings
and in the coordinator's wave-planning read; the README's oldest-first build order is unchanged,
and the owner may pick by recurrence. Triage reads the entries (below); a `New:` that changes what
was settled may reopen a Plan or a Question; a Waits is only flagged.

**Landing keeps what is unbuilt.** A pass that lands an idea removes from the file what it built
and rewrites the block while any unbuilt `Use:` or `New:` remains; the file is deleted only when
nothing unbuilt is left. No accepted use is lost with its primitive.

**Triage's reader.** Triage step 4 also lists every file whose last dated entry (`Came up again:`,
`Use:`) is newer than its block, for re-reading: a Plan or a Question may be reopened, a Waits is
only flagged to the owner in the triage report. Every step works with grep alone
(`grep -c "^Came up again:"`); a `--recurring` flag for `queue-list.js` is optional, filed in
Hatchery as an idea, and no skill text depends on it (an older base would exit 2 on it).

**The rising bar.** One step per doubling of the open count, read once at the session's start and
shown in the header; a step is guidance and may lag a session when a wave lands ideas near a
boundary. It never refuses; the held-back count makes it visible.

| Open ideas | What an offered idea also shows |
|---|---|
| under 20 | an anchor in the tree |
| 20 to 40 | no existing file says it (else it is a recurrence entry) |
| 40 to 80 | it is not one use of a primitive another file or this list names (else a `Use:` line) |
| 80 to 160 | its lever: what it unblocks, which report or correction it answers, or why a moonshot is worth carrying |
| 160 to 320 | which open idea of its kind it outranks, judged on the shortlist only, one line of reason each |
| 320 and up | the owner asked for its topic, or it recurred from a distinct source |

The coordinator judges at the offer, the owner at acceptance, triage afterwards. Offering below the
bar is allowed with a reason. The 3/3/3 shape is the ceiling; the bar rises inside it, so slots thin.
Resting ideas count toward the open count by design (they are ideas the owner holds).

**Measured on the 2026-11-22 batches.** There were two offers: `be89dabd` (14 files, generated
under a Sonnet trailer) and `73467e3c` (11, Opus); the owner folded 3 at triage, leaving the 22
files that say "coordinator's own". At 172 open the bar is at step 5, which cannot be measured
mechanically; step 4 is the last measurable one. At step 4, the architect's reading of the 22: 7
lever, 5 moonshot with a reason, 4 recurrences, 6 fail; the critic's recheck: 7, 5, 3, 7 (the
subscription a recurrence, distance-aware detail contestable, peaks seen today a `Use:` of line of
sight). So about 12 pass at step 4, within the 18 slots two offers allow. Of the failures, the owner
answered the coverage panel yes, gave the Download card line a Plan and the storage advisor a Waits:
the bar would have cut three ideas the owner kept. Hence a failing idea is offered as a `Use:` line
or a recurrence entry on its home file, never dropped, and the held-back count shows the rest.

## Options

### A. Offer, file on yes, one pile (the current practice, sharpened)

Accepted ideas land untriaged with the Source line; moonshots and half-baked ideas are triaged like
the rest and end in a `Question:`.
- Bar, check, provenance, recurrence, landing: the shared pieces.
- Moonshots: `Moonshot.` in the Source line; each costs a triage read and a Question answered "later".
- Ideas that sit: no new mechanism; the README's whole-listing sitting stays, unexercised.
- Reading load: nine offer lines per session plus a Question per recorded idea at the next sitting.
- Files: triage steps 4 and 9 and the Bounds (the carry-back, replacing 4d00b15); the wave skill's
  Close step 6 points at step 9 and adds the spark case; the `CLAUDE.md` bullet becomes a pointer;
  the ideas README gains the Source line, the recurrence entry and landing-keeps-unbuilt.
- Hatchery: the triage text as a carry-back; the README practice and the landing rule as an idea
  (the queue READMEs are project-owned).
- Exit cost: revert one skill step and two README paragraphs; the Source lines and recurrence
  entries already written stay as plain text. Moving parts: the five shared conventions (Source
  line, cheap check, recurrence entry, `Use:` line, bar table), the triage reader, the landing
  rule. Settings: none (process).

### B. Offer, file on yes, with a rest pile on the owner's word (recommended)

As A, plus a rest state the owner chooses at the offer. **B1**, no grammar change: a Waits with a
fixed, greppable prefix, written only on the owner's quoted word:

```
Waits: at rest — "keep it, later" (the owner, 2026-11-22)
```

The quote is the owner's words or the text of the option they picked. "At rest" is never the
coordinator's default. **B2**, if B1 proves insufficient: a fourth block `Rests:` (grammar change).
- Bar: the shared table; resting ideas count toward it and list apart (`grep "^Waits: at rest"`).
- Moonshots: `Moonshot.` in the Source line, offered with "record" and "record at rest" side by side.
- Ideas that sit: triage never rewrites any Waits. A recurrence entry on a resting file is flagged
  in the triage report; only the owner's answer turns it into a Plan or Question. Nothing lapses.
- **The rest review**: an offered list, not a sitting an agent runs. Every third wave close
  (recorded in the wave record) or when the owner asks, the coordinator lists the resting Waits
  sorted by distinct recurrences, one line each, with the 4 trigger-less Waits and the 2
  blanket-leave Waits of today for the owner to restate or convert (triage converts none). The
  owner decides each line: keep, wake, or delete. The answered-but-unbuilt stay with wave planning,
  which already drains them oldest first; listing them would re-ask answered questions. The
  README's "no agent runs that sitting" becomes "the coordinator offers the rest review on its
  cadence; the owner decides each line", the owner's explicit word quoted in the ADR.
- Reading load: nine offer lines per session; one line per resting idea every third wave, sorted so
  the owner can stop early; triage reads fewer files (resting ones only when flagged).
- Files: as A, plus the ideas README (the rest prefix, the review), `.claude/rules/docs.md` aligned
  with the README ("only the owner decides a Waits; an agent writes one only from the owner's quoted
  words"), the wave skill's Close step 6 (the review every third close).
- Hatchery: the triage text as a carry-back; the rest prefix, the review, the recurrence and landing
  rules and the `docs.md` line as an idea filed there citing this ADR. B2 alone needs a Hatchery ADR:
  ADR 0013's "a new block kind that older readers may ignore is free" does not apply, since an older
  `queue-list.js` would list a file ending in `Rests:` as untriaged and triage it again; ADR 0007's
  own trigger ("the grammar wants a fourth state") would govern.
- Exit cost: B1, drop the review step and leave the prefixed Waits, which read as ordinary Waits;
  B2, a migration of every `Rests:` file in every project. Moving parts: A's, plus the prefix, the
  review and its cadence line in the wave record. Settings: none (process).

**Considered: A plus the fixed rest prefix** (the critic's alternative, no review, no flag). B1
keeps the review: the owner put readability in scope and it is the only reading mechanism with a
cadence; the flag is already deferred.

### C. A separate lane with a lapse rule (PEP 1's Deferred, owner-approved)

Coordinator ideas land in their own capped lane, skipped by triage; one not picked at two sittings
is proposed for lapse in a batch the owner approves. `queue-list.js` ignores subdirectories (line
155; `0017-queue-list.md` section 1) and Hatchery ADR 0017 item 7 defers a fourth queue directory
"until an ADR adds a name"; a lane file is the list ADR 0007 retired. Against it: "postponed but
interesting" becomes a lapse prompt. Exit cost: merge the lane back. Moving parts: a lane, a listing
mode, a lapse count, a Hatchery ADR. Settings: none.

### D. Only through triage

No new files: each suggestion is a recurrence entry, a `Use:` line or a Question on an existing
idea. Sparks and moonshots have no home. Exit cost: none. Moving parts: one paragraph. Settings: none.

## Recommendation

**B with carrier B1.** It keeps what the owner says works (offers at the end of work, 3/3/3, file on
yes), adds the pile the owner leans toward without a grammar change, using a prefixed Waits written
only on quoted words, lets duplicates accrete as recurrence entries counted by distinct source, keeps
accepted uses alive past a landing, and states the log-scaled bar as a per-doubling table with a
held-back count. The whole-listing sitting nobody ran becomes a rest review offered every third wave.

First step: the held carry-back rewritten as triage steps 4 and 9 and the Bounds, with the wave
skill, README, `docs.md` and `CLAUDE.md` lines; then the idea in Hatchery (the flag optional).

## What would change this recommendation

- Resting Waits crowd the trigger Waits so that the waking-Waits idea cannot use the prefix: B2.
- The owner skips two rest reviews running: drop the review, which leaves the critic's alternative.
- Recurrence entries pile up on files nobody builds: drop the count from the planning read.
- Predictions, each with its check:
  - Prediction, check 2027-01-31: the open queue's growth per week (`git log --diff-filter=A` on
    `docs/ideas/` per ISO week, minus deletions) is below half of W47's 100 added.
  - Prediction, check at the second rest review: at each of the first two the owner decides at least
    half the lines in one sitting (the wave record's review line).
  - Prediction, check 2027-02-22: at most a quarter of `Came up again:` entries name the same session
    as a triage that read the file, and every rest Waits carries the prefix (grep).

## Claims to verify

- The two offers and the folds: `be89dabd` (14, Sonnet trailer), `73467e3c` (11, Opus), folds
  `5144f950`, `d268de91`, `2b5cb85c` (the critic's Claim 1).
- The step-4 classification: the architect's 7/5/4/6 and the critic's 7/5/3/7.
- `queue-list.js` lists only the top level (line 155, `0017-queue-list.md` section 1); Hatchery ADR
  0017 item 7 defers a fourth directory.
- Hatchery ADR 0013's sentence on new block kinds, and why it does not cover `Rests:` (option B).
- The Waits counts: 4 trigger-less rests and 2 blanket-leave Waits among the 15 (grep `^Waits:`).
- The weekly and origin figures were not rechecked by the critic (scratchpad research, not durable).

## Critic's objections and responses

1. Serious, who rewrites a resting Waits. Response: triage never rewrites any Waits; a recurrence
   on one is flagged in the triage report and only the owner's answer turns it into a Plan or
   Question. Resolution: accepted (option B, "Ideas that sit").
2. Serious, rest and trigger Waits indistinguishable. Response: the fixed prefix
   `Waits: at rest — "<quoted words>" (the owner, <date>)`, written only on quoted words; record and
   record at rest equal in the offer; the 4 trigger-less and 2 blanket-leave Waits listed at the first
   rest review for the owner; `docs.md` aligned. Resolution: accepted.
3. Serious, recurrence reorders the drain and echoes. Response: counted by distinct source or
   session, the same-session echo excluded; a visible count at sittings and planning only; oldest
   first unchanged. Resolution: accepted with change (shown in planning, not only to the owner).
4. Serious, `Use:` and `New:` lines die with their file. Response: the landing pass removes what it
   built and keeps the file while anything unbuilt remains, instead of spawning new files.
   Resolution: accepted with change (the file kept, not split).
5. Serious, carry-back too narrow; the flag. Response: triage step 4 gains the reader for files with
   an entry newer than their block; the carry-back names steps 4 and 9 and the Bounds; grep only,
   the flag optional and filed as an idea. Resolution: accepted.
6. Serious, "8 instead of 22" does not hold. Response: restated in place: two offers, 3 folds, 12
   passing at step 4, step 5 unmeasured, the critic's recheck beside the architect's, three kept
   ideas the bar would have cut, so failures become `Use:` lines. Resolution: accepted.
7. Minor, the count flips as waves build. Response: read once at the session's start, shown in the
   header; a step may lag a session; resting ideas count by design. Resolution: accepted with change
   (a lag note instead of hysteresis).
8. Minor, step 5's cost. Response: judged on the shortlist only, one line of reason each.
   Resolution: accepted (one offer per session and the meter rule not taken: the owner's triggers
   stand, and the usage gate governs launches, not the coordinator's own text).
9. Minor, the silent bar. Response: "held back: N" in the header. Resolution: accepted.
10. Minor, model provenance. Response: the Source line names the generating model, from the agent's
    id or the commit trailer, and both when the recorder differs; after the H1; public context only.
    Resolution: accepted.
11. Minor, the rest review against the README; cadence. Response: an offered list the owner decides
    line by line; every third wave close, recorded in the wave record, or on request; resting Waits
    only, the answered-but-unbuilt left to wave planning; the README sentence changes, the owner's
    word quoted in the ADR. Resolution: accepted with change.
12. Minor, boilerplate exit and moving-parts lines, no prediction. Response: concrete lines per
    option, the shared conventions listed, three dated predictions. Resolution: accepted.
13. Minor, citations for C and B2. Response: ADR 0017 item 7 for C; ADR 0013's sentence and why it
    does not apply for B2. Resolution: accepted.
