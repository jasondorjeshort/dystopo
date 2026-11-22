# 0065. The coordinator's brainstorms and a readable ideas queue

Date: 2026-11-22
Status: under review

## Question

How and when does the AI coordinator generate new ideas (near-term, intermediate, moonshots;
product, process and tooling, research questions), how do they enter `docs/ideas/`, and how does the
queue stay readable as it grows? The practice already works: the owner reports that ideas offered at
the end of a piece of work "have been very high quality", "nearly always interesting", with some
discarded and many "postponed indefinitely", which is an accepted outcome, not a failure. What is
open is the shape: the offer, the provenance, a quality bar that rises with the queue's size, the
handling of half-baked ideas and moonshots, and what a recurring idea does. The triage skill is
base-owned, so the answer reaches every project built on Hatchery. In ten years the queue should be
a place where a returning idea gets stronger, a far idea rests visibly without crowding the ready
ones, and the owner's reading per session stays a handful of lines whatever the file count.

## Constraints that matter here

- Only the owner prunes for taste (`docs/ideas/README.md`, ADR 0036, Hatchery ADR 0007); no pass
  deletes an idea as not worth building, and nothing lapses into deletion without the owner.
- Idea frontmatter is `date`, `kind`, optional `related`, nothing else (`.claude/rules/docs.md`);
  `queue-list.js` lists a file with another key as `?`. The kind set and the three blocks are the
  grammar Hatchery ADR 0007 and ADR 0017 keep identical across projects; a grammar change is an
  incompatible surface change with a migration (Hatchery ADR 0016).
- The owner's answers of 2026-11-22, binding on every option: no hard cap, a bar that rises roughly
  logarithmically with the queue ("log scaling"); offered after a triage, at a wave close or release,
  and mid-session when something sparks; recorded only on the owner's acceptance; about 3 near-term,
  3 intermediate, 3 moonshots per offer ("10-15 feels high"); the sanity check stays at triage;
  provenance on every idea, the agent's model included, marked not sanity-checked; a moonshot is
  welcome and marked; one primitive must not be presented as several features (corrected twice).
- Duplicates are updates (owner, 2026-11-22): a recurring idea carries both the signal that it came
  up again and any new modification it brings, recorded on the existing file; recurrent ideas are
  "often the best ones".
- A separate pile for moonshots and not-ready ideas is a direction the owner leans toward.
- The triage skill's text is a carry-back; a rule, an ADR or a grammar change reaches Hatchery as an
  idea filed there (`.claude/skills/carry-back/SKILL.md`). The held carry-back
  `carry-back/triage-brainstorm-step` at 4d00b15 (ADR 0038, departure table) may be replaced.
- No user settings in scope: ADR 0044's settings are the app's; this is the process (each option
  says so).

## Prior art

Published practice (fetched and checked 2026-11-22; unverified sources were dropped):

- Shape Up, ch. 7 "Bets, Not Backlogs" (https://basecamp.com/shapeup/2.1-chapter-07): "Backlogs are
  a big weight we don't need to carry"; "Really important ideas will come back to you." Ch. 8
  (https://basecamp.com/shapeup/2.2-chapter-08): idea work in a cool-down after each cycle, "no
  'grooming' or backlog to organize"; an old pitch is revived only when someone chooses to.
- PEP 1 (https://peps.python.org/pep-0001/): Deferred "when no progress is being made"; a sponsor
  must be found; vetting an idea before writing it up saves the author's time. Rust RFC 1068
  (https://github.com/rust-lang/rfcs/blob/master/text/1068-rust-governance.md): "closed as
  postponed". KEP process
  (https://github.com/kubernetes/enhancements/tree/master/keps/sig-architecture/0000-kep-process):
  "provisional" first; an owning group accepts the work. The common thread: who proposes is separate
  from who decides, and an idea at rest has a named state rather than a deletion.
- Brainstorming research: Diehl and Stroebe 1987 (JPSP 53:497-509) and the Mullen et al. 1991
  meta-analysis, individuals out-produce groups on quantity and quality; Nemeth et al. 2004 (EJSP
  34:365-374, https://escholarship.org/uc/item/4k70n7v8), debate instructions beat "no criticism"
  instructions, so "defer judgement" is not evidence-backed.
- Sharma et al. 2023 (https://arxiv.org/abs/2310.13548): "sycophancy is a general behavior of
  state-of-the-art AI assistants", the reason an agent's idea is marked as not checked and the
  decision stays with the owner. Anthropic's "Building effective agents"
  (https://www.anthropic.com/engineering/building-effective-agents): human feedback at checkpoints;
  silent on agents proposing work.
- No source gives a per-session cap or a moonshot practice; those are design choices.

This queue's measured history (git, 2026-10-13 to 2026-11-22; classifications marked (H) by regex
over commit subjects): 288 idea files added, 116 removed, 172 present; added per ISO week 21, 9, 64,
41, 53, 100. Origin (H): coordinator-side 155, owner 104, process 29. Removed (H): built 84 (median 0
days, mostly recorded at wave planning), already in the code 6, folded 6, converted 13, scan
outcomes 10, **not wanted 0**. Brainstorm batches: 12 of 57 built (21%); the moonshots of
2026-10-29 all open. Blocks today: 78 answered-but-unbuilt (median 20 days; 34 "as recommended"),
68 Plans (median 7 days), 15 Waits, 11 untriaged, 0 blank Questions. Triage median 0.44 days from
add; the owner answers in sittings (37, 19, 24 at a time), median 3.8 days. The README's "the owner
reads the whole of it in one sitting" has not happened over the whole queue. Only one file today
carries a recurrence note; recurrence is not recorded anywhere systematically.

## The pieces every option shares

These follow from the owner's answers and are the same in A to D; the options differ in where an
accepted idea lands, how the bar is applied and what handles ideas that sit.

**The offer.** A numbered list of at most nine lines, grouped near-term, intermediate, moonshot,
about three each, one or two lines per idea naming its anchor. A header line states the queue size
and the bar (below). Empty slots are normal. The owner answers by number: record, record at rest
(where an option has a rest state), fold into an existing file, or no.

**The Source line**, within the frontmatter rule: the first line of the body under the title,

```
Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22; offered and accepted by
the owner; not sanity-checked. Moonshot.
```

with the trigger (a triage, a wave close, a release, a spark during named work), the word
`Moonshot.` when it is one, and the owner's own ideas written `Source: the owner, <context>`. This
replaces the free-form "Status: untriaged and not sanity-checked (...)" paragraph of the 2026-11-22
batches, which already says most of this but not the model. A key `source:` in frontmatter would be
a grammar change in base-owned files and every project's listing; the body line costs nothing and
`grep -l "^Source: coordinator"` finds them. A `kind: moonshot` was considered and rejected: kind
says what the work is (feature, chore), moonshot says how far it is, and a moonshot feature would
lose its kind. The `(moonshot)` title suffix already used on four files stays allowed.

**The cheap check before offering** (a grep, not a sanity check, which stays at triage): the anchor
named exists in the tree; the idea is not already built; a matching idea is searched for (README,
"Before adding"); and the list is read once for one primitive offered as several uses. A match is
not suppressed: it becomes a recurrence entry, offered as "came up again" with what is new.

**The recurrence entry.** When an idea comes up again, the existing file gains a dated entry above
its block (or at the end when it has none) recording both the recurrence and any modification:

```
Came up again: 2026-11-22, coordinator (claude-opus-5-5), at the close of wave 46, while reading
T331's heading correction. New: the manual correction gives the match a seed bearing, so the
search narrows to plus or minus 15 degrees; anchor `shared/.../HeadingCorrection.kt`.
```

The `New:` part is optional (a bare recurrence is still recorded) and may add an angle, a scope, an
anchor or a constraint. A primitive split into uses is recorded the same way: the uses become `Use:`
lines on the primitive's file instead of their own files. Triage reads the entries: the count of
`Came up again:` lines raises the idea's standing (it is listed first among its block at the owner's
sittings and in wave planning, oldest first among equals); a `New:` that changes what was settled
reopens the block (a Plan whose shape changed becomes a Question; a Question whose choice the
modification removes may become a Plan; a `Waits:` is the owner's and is only flagged to them). A
`queue-list.js` column or flag counting the entries (`--recurring`, an addition to a promised CLI,
not a change) makes the standing visible; until it exists, `grep -c "^Came up again:"` does.

**The rising bar.** One step per doubling of the open queue, stated as guidance the offer's header
shows, never a number that refuses an idea:

| Open ideas | What an offered idea also shows |
|---|---|
| under 20 | an anchor in the tree |
| 20 to 40 | no existing file says it (else it is a recurrence entry) |
| 40 to 80 | it is not one use of a primitive another file or this list names |
| 80 to 160 | its lever: what it unblocks, which report or correction it answers, or for a moonshot why it is worth carrying far |
| 160 to 320 | which open idea of its kind it outranks, and why |
| 320 and up | the owner asked for its topic, or it recurred |

The coordinator judges at the offer, the owner at acceptance, triage afterwards. Offering below the
bar is allowed with a stated reason ("below the bar: no lever yet, but the owner asked about
skylines"). The 3/3/3 shape is the ceiling; the bar rises inside it, so slots thin as the queue grows
rather than the shape changing. Header example: `Queue 172 open, step 5: each shows its lever and
the open idea it outranks. 2 near-term, 2 intermediate, 3 moonshots; 2 recurrences.`

**Measured on the 2026-11-22 batches.** Grep finds 22 files saying "coordinator's own" dated
2026-11-22 (the brief said 25; see Claims). Classifying them by title and body against step 4 (the
architect's reading, for the critic to recheck): 7 show a lever (the line-of-sight function, the
"what does the phone hold here?" function, the emulator fixture for six reports, the sweep's phone
timing four ideas wait on, the waking Waits, the "why is this blank?" route, the beta-defaults
ledger); 5 are moonshots with a reason (skyline geolocation, skylines in packs, the photo retake,
the split download, the shared sky reading); 4 are recurrences of older files (distance-aware detail
and the subscription of 2026-11-04 and 2026-10-13, the camera heading fix of 2026-11-02, route
skylines of 2026-11-21); 6 fail, of which 3 are uses of the "what does the phone hold?" or
line-of-sight primitives (the coverage panel, the Download card's line, the "where to stand" tint)
and 3 depend on an unbuilt feature with no lever of their own (the star cutoff's first value, the
storage advisor, peaks seen today). Under 3/3/3 that is 8 files and 4 recurrence entries instead of
22 files, with 3 uses as `Use:` lines. Step 5 (outranks an open neighbour) could not be measured
mechanically. The cost is real: the owner answered the coverage panel "yes" and the Download card
got a Plan; under the bar both would live as `Use:` lines on the primitive's file, which the owner
can still promote.

## Options

### A. Offer, file on yes, one pile (the current practice, sharpened)

Accepted ideas land in `docs/ideas/` like any other, untriaged, with the Source line; moonshots and
half-baked ideas are triaged with the rest and usually end in a `Question:` or, on the owner's word,
a `Waits:`. Handling of ideas that sit: none new; the README's whole-listing sitting stays.
- Bar: the shared table, judged at the offer; shown in the header.
- Moonshots: `Moonshot.` in the Source line; triaged like the rest, which costs a triage read and a
  Question per moonshot the owner will answer "later".
- Ideas that sit: the 78 answered-but-unbuilt and 68 Plans are build capacity, not reading load;
  nothing changes them. Half-baked ideas get a Question each, adding to the owner's sittings.
- Reading load: per session at most nine offer lines plus each recorded idea's Question at the next
  sitting; for the whole queue, the unexercised whole-listing sitting.
- Files: the triage skill's step 9 (the held carry-back, rewritten with the shared pieces), the
  wave skill's Close step 6 (points at it; adds the spark case), the `CLAUDE.md` bullet (shortened
  to a pointer), the ideas README ("Before adding" gains the recurrence entry and the Source line).
- Hatchery: the triage text as a carry-back; the Source line and recurrence entry as README
  practice (project-owned READMEs, so an idea in Hatchery asking the base README to show them).
- Exit cost: delete a step and a paragraph. Moving parts: none new. Settings: none.

### B. Offer, file on yes, with a rest pile on the owner's word (recommended shape)

As A, plus a rest state the owner chooses at acceptance: "record at rest" for a moonshot or an idea
not ready. Carrier B1, no grammar change: a `Waits:` written on the owner's word, whose trigger is
the owner's pick or a recurrence (`Waits: the owner picks it, or it comes up again (moonshot, at rest
on the owner's word, 2026-11-22).`). `Waits:` is already "the owner's own deferral to a trigger",
and the 2026-11-22 triage already wrote Waits from the owner's multiple-choice answers. Carrier B2,
if B1 crowds the Waits: a fourth block `Rests:` (grammar change, below).
- Bar: the shared table. Resting ideas count toward the queue size for the bar (they are ideas the
  owner holds) but are listed apart, so the ready queue stays short.
- Moonshots: `Moonshot.` in the Source line and, by default, offered as "record at rest"; triage
  skips a resting file (it has a block) and reads it again only when a recurrence entry lands or the
  owner names it, then replaces the Waits with a Plan or Question.
- Ideas that sit: a resting idea is at rest by the owner's choice, visibly (`queue-list.js` shows
  `waits`); nothing lapses. A recurrence wakes it at the next triage (a dated entry on a Waits file
  is flagged to the owner, never rewritten by triage, since Waits is theirs). The README's
  whole-listing sitting is replaced by a short **rest review** at a wave close every few waves or
  when the owner asks: the coordinator lists resting and answered-but-unbuilt ideas older than a
  month, most-recurrent first, one line each; the owner says keep, wake or delete. PEP 1's Deferred
  and the RFC's "postponed" are the model, with the owner as the one who moves the state.
- Reading load: per session nine offer lines; per rest review one line per old idea, sorted so the
  owner can stop early; triage reads fewer files (resting ones skipped).
- Files: as A, plus the ideas README (the rest meaning of Waits; the rest review replacing the
  whole-listing sentence), the wave skill's Close step 6 (the rest review's cadence), a
  `queue-list.js` `--recurring` flag (base-owned code; a carry-back once built, the flag an addition
  to ADR 0017's promised CLI, so an idea filed in Hatchery first).
- Hatchery: the triage text as a carry-back; the rest meaning of Waits, the rest review and the
  recurrence rule as an idea filed there with this proposal's ADR as its source. B2 alone needs a
  Hatchery ADR (ADR 0007's own supersession trigger: "the grammar wants a fourth state").
- Exit cost: B1, rewrite resting Waits into Questions or leave them (they read as Waits anyway);
  B2, a migration of every `Rests:` file in every project. Moving parts: one flag; a periodic review
  the coordinator runs. Settings: none (process, not the app).

### C. A separate lane with a lapse rule (PEP 1's Deferred, owner-approved)

New coordinator ideas land in their own lane, a subdirectory `docs/ideas/lane/` [no-check: not
created] or a lane file, with their own 3/3/3 cap; an idea not picked by the owner at two sittings is
proposed for lapse in a batch the owner approves once ("lapse these six"), after which they are
deleted (git keeps them) or moved to a `later` pile.
- Bar: the lane's size, not the whole queue's, drives it; the lane is small, so the bar stays low
  there and the main queue is untouched.
- Moonshots: the lane holds them; triage never reads the lane; the owner promotes by name.
- Ideas that sit: lapse after two sittings, approved by the owner, which squares with "only the
  owner prunes" only if the approval is real reading, not a rubber stamp of a batch.
- Reading load: a second listing to read; the lapse batches.
- Files: `queue-list.js` reads only the top level of `docs/ideas/` (`readdirSync`), so a
  subdirectory is invisible to it and the post-edit hook would want an index there; Hatchery ADR
  0017 defers "a fourth queue directory ... until an ADR adds a name". So a lane is a Hatchery ADR
  and code change before it is usable; a single lane file is the shared list ADR 0007 retired.
- Exit cost: merge the lane back and drop the lapse rule. Moving parts: a lane, a listing mode, a
  lapse count per idea. Settings: none.
- Against it: the owner's notes value "postponed indefinitely but interesting"; a lapse clock turns
  that into a deletion prompt. Recurrence across lanes needs a cross-search.

### D. Only through triage

A brainstorm writes no new files: every suggestion is a recurrence entry or a `Use:` line on an
existing idea, or a new Question on one; a genuinely new idea is filed only when triage finds no home.
- Bar: implicit; new files are rare. Moonshots: no home unless an existing idea fits.
- Ideas that sit: unchanged. Reading load: the lowest. Files: the triage skill only.
- Against it: the owner wants sparks "any time" and moonshots welcome; most of the 2026-11-22
  moonshots had no file to hang on. Kept as the record of the narrowest shape.
- Exit cost: none. Moving parts: none. Settings: none.

## Recommendation

**B with carrier B1.** It keeps what the owner says works (offer at the end of work, 3/3/3, file on
yes), adds the pile the owner leans toward without a grammar change (a `Waits:` on the owner's word
is already the grammar's owner-only deferral), makes duplicates accrete as dated recurrence entries
with their modifications, and states the log-scaled bar as a per-doubling table the offer's header
shows, so the bar rises without a number that refuses anything. The whole-listing sitting nobody ran
becomes a short, sorted rest review. The 78 answered-but-unbuilt are a capacity question for wave
planning, not a reading problem, and the recurrence count is the one new signal planning gains.

First step: rewrite the held carry-back's step 9 with the shared pieces (offer, Source line, cheap
check, recurrence entry, bar table) as one triage step that the wave skill's Close step 6 and the
spark case call; the ideas README's paragraphs; the `CLAUDE.md` bullet as a pointer; the Source line
added to the 2026-11-22 files that lack the model only if the owner wants it (they say "not
sanity-checked" already). Then an idea in Hatchery for the rule and the `--recurring` flag.

## What would change this recommendation

- Resting Waits outnumber trigger Waits so that the waking-Waits idea cannot tell them apart: take
  B2 (a `Rests:` block) through a Hatchery ADR.
- The owner skips two rest reviews running: the review is the wrong cadence; fold it into triage.
- Recurrence entries pile up on files nobody builds: the standing signal is not used; drop the flag.
- The owner finds the bar's step text noise in the header: keep the table in the skill only.
- Hatchery declines a project-owned README practice spreading through an idea: keep it here only.

## Claims to verify

- The 2026-11-22 count: grep finds 22 files with "coordinator's own"; the brief's 25 may count
  ideas worded differently (commit 73467e3c says eleven from the triage batch).
- The step-4 classification of those 22 (7 lever, 5 moonshot, 4 recurrence, 6 fail) is one reader's;
  recheck against the files.
- `queue-list.js` lists only the top level of `docs/ideas/` (its `readdirSync`), so option C's
  subdirectory is invisible to it.
- Whether a new `block` value in `--json` (B2's `rests`) is a promised-surface change under
  Hatchery ADR 0017 item 5 (field names are promised; values are unstated).
- That writing a `Waits:` from the owner's chat acceptance fits "no agent writes one on its own
  judgement" (README); the 2026-11-22 triage did so from multiple-choice answers.
- The queue history figures come from `0065-research-queue.md` (scratchpad, not durable; the
  example commits there: be89dabd, 6549db59, 904b484f, 4733f465, 5144f950).

## Critic's objections and responses

(none yet)
