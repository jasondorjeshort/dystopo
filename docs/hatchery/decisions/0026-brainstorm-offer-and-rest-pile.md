# 0026. The brainstorm offer: a skill of its own, entries on existing ideas, a rest pile

Date: 2026-11-22
Status: accepted
Amended by [0028](0028-brainstorm-after-update.md), 2026-11-25, by additions only: an offer after
every update from the base, outside the one prompted offer of item 2, in two groups; the title
`For the base: ` on an idea a project leaves to the base. Every item below stands.

Record: proposal `../../design/proposals/0026-brainstorm-offer-and-rest-pile.md` (option D as
revised) and `../../design/proposals/0026-critic-review.md` (one blocking, seven serious, five
minor; all accepted, four with a change). Evidence: the finding of 2026-11-22 under
`../../findings/` on the carry-back's review. Formats, the reader's additions, the edits to the
carried text, the build order and the migration: [the sibling](0026-brainstorm-contract.md).
Supersedes in part ADR 0007 (what `Waits:` means and who writes it; when a pass builds and deletes)
and ADR 0013 (the idea-grammar row's record, five field names on the listing row, its two Deferred
items); amends ADR 0017 and `0017-queue-list.md` by additions. The owner decided what the owner sees,
who writes `Waits:`, what it means and what an offer spends (Decision, item 10); the rest is
technical and the coordinator's.

## Context

A downstream project made brainstorming standard at its owner's request (dystopo ADR 0065): after a
triage or a wave the coordinator offers at most nine ideas, an idea is written only on the owner's
answer, a returning idea updates its file, a half-baked one rests. It sent the `triage` text as
`carry-back/triage-brainstorm-and-rest`. A critic's review found the text could not land as sent:
its step 9 took its formats and a table from `docs/ideas/README.md`, which is project-owned and
never patched (ADR 0016), and it had an agent write `Waits:`, against the rule file and the
promised idea grammar (ADR 0013). A runner then ran the review's cases for the carried step 4 git
loop: it listed an answered `Question:`, a removed `Use:` line and `README.md`, and missed a
same-second entry. The proposal's critic ran seventeen cases against `queue-list.js`: an entry below
a blank `Answer:` reads `answered`, and a wrapped entry whose next line starts `Plan:` reads `plan`,
with `--check` silent. It also showed that a build pass reads the whole idea file, so a stale
`Plan:` cannot be left for the pass to judge. Hatchery holds 42 idea files; the sibling added 288 in
six weeks and accepted its first nine-line offer nine of nine.

## Decision

1. **Carrier.** A base-owned skill, `.claude/skills/brainstorm/SKILL.md`, is the one home of the
   offer, the cheap check, the formats, the bar's table and the rest review. `triage` step 9 and the
   `handoff` skill's wrap-up name it; its description carries the other triggers (a spark, the
   owner's request, a project's wave close or release). No agreement line. No base skill cites a
   project-owned file for a format it needs, and no skill greps idea files for state: the reader
   prints it (item 8).
2. **The offer.** The coordinator generates from what is already in its context; a subagent that
   ran a triage reports up to nine candidates, one line and an anchor each. No agent is launched to
   brainstorm, at any usage level. Before offering, a cheap check per candidate: the anchor exists,
   the tree lacks it, a matching idea is searched for, one primitive is not split into features. At
   most nine numbered lines, about three each near-term, intermediate and moonshot, under the header
   of the sibling, section 1. One prompted offer per session; an offer the owner asks for in so many
   words is always made and does not count. The offer is made at HOLD too. At STOP, where a policy
   sets one, nothing is offered or written and candidates in hand are not kept; the wrap-up report
   says how many.
3. **What an answer writes**, all in the offer's one commit. Record: a new idea file with the Source
   line and no block. Record at rest: the same, ending in the rest line. Fold into a named file: an
   entry on that file, no new file. No: nothing. No answer (the owner answered other lines, or the
   session reached its wrap-up with no reply): a new idea file with the unanswered Source line,
   ending in `Question: offered <date> and not answered: ...` and a blank `Answer:`. Such a file
   counts in the open queue, has a block and so does not make triage due, and is marked by the
   reader's `offered` field. A session that ends with neither a reply nor a wrap-up writes nothing.
4. **A later answer** to an offer Question, in its `Answer:` or in words to the coordinator, is
   acted on by the coordinator alone, at the start of every offer and when a triage report lists
   rows with `offered` true and `block` `answered`. Record: the Source line takes its accepted form
   and the block is removed, so the next triage reads the file. Record at rest: the block becomes
   the rest line, quoting the answer. Fold: the entry is written on the named file and this file is
   deleted in the same commit. No: the file is deleted, the commit message quoting the owner's
   words and the date. An answer in other words is read for which of the four it means; unclear, it
   is asked at the next offer. The date quoted is that of the commit that brought the answer, or the
   day it is acted on. Until then no pass builds the file and triage writes no block from it.
5. **Entries.** `Came up again:` (written only for an independent source, so its count needs no
   judging), `New:` (scope no block has taken in) and `Use:` (one more use of the file's primitive).
   Each is one physical line above the block, a blank line before the block; no line of a block
   starts with an entry prefix. The Source line is one physical line too.
6. **Readiness and landing.** A file with a `New:` line is not ready to build, whatever its block:
   the writer rewrites the `Plan:` in the same commit when the change is settled, taking the line in
   and removing it; otherwise `triage` step 4 finds the file by `news` and does so. A pass that
   lands a `Plan:` deletes the file when no `Use:` line remains and otherwise removes the block; the
   file is then untriaged. Ready means `block` is `plan` or `answered`, `news` is 0 and `offered` is
   false.
7. **The rest pile and who writes `Waits:`.** `Waits:` is the owner's deferral: to a trigger it
   names, or, as `Waits: at rest — "<the owner's words>" (the owner, <date>)`, until the owner wakes
   it. Only the owner decides a `Waits:` or that an idea is not wanted; the session the owner
   answered (or the coordinator, from words under `Answer:`) writes the line or deletes the file
   from those words, quoted with the date; never a subagent, never by default. The twelve `Waits:`
   lines Hatchery already holds are not rewritten. The rest review lists the `rest` rows sorted by
   `recurrences`, one line each; the owner says keep, wake (the `Waits:` line is removed) or
   delete. It is given on request always, and prompted at a release and by one closing line on the
   offer after a triage, at most once in seven days from the last review record.
8. **The reader.** `queue-list.js ideas --json` rows gain `rest`, `offered`, `recurrences`, `news`
   and `uses`; `read()` returns placement warnings W1 (an entry below a block) and W2 (a wrapped or
   glued entry) that the CLI prints on stderr and `post-edit.js` prints at the edit; nothing fails
   and `--check` is unchanged. Contract and harness cases Q14 to Q18: the sibling, section 2.
9. **Records and refusals.** Every offer and every rest review leaves one record line of counts, as
   a commit subject or one body line of the wrap-up commit (at HOLD, or when nothing was written);
   no candidate is stored in a commit message. "Nothing is written unasked" covers ideas nobody
   asked for and no task implies: a line answered no writes nothing, a line left unanswered writes
   the offer Question. Work a session was given or started, a defect it saw, a scan hit and a review
   follow-up are filed as before.
10. **The owner's answers**, 2026-11-22. Who writes `Waits:`: "Yes, from my quoted words". The
    offer's shape: "Yes, but silence is not a no". At HOLD: "Offer anyway". The review: "On request
    and at a release, but no more than every week?" and, for a prompt every project has, "Yes, one
    line after a triage". What `Waits:` may mean: "Yes, widen Waits:". Where an unanswered line
    lives: "As an idea file with a Question:", chosen knowing that such files are written without a
    yes and count in the open queue; a commit-message store was rejected.
11. **The carry-back** lands with the seven edits of the sibling, section 3; the carried git loop
    does not land. Build order, files and checks: section 4. Migration per tree: section 5.
12. **Surfaces (ADR 0013).** This is the idea grammar's first incompatible change: authorship and
    meaning of `Waits:`, and a pass's terms. The grammar row's record gains this ADR. The listing
    row gains the five field names. The Deferred "migration note format" lands as the five-column
    table of the sibling, section 5, named in the `adr` skill. The Deferred "compatibility check"
    lands for the idea grammar as harness group `Q` (a fixture per block kind and `kind:` value,
    extended here); its policy-schema half has been owed since ADR 0021 and stays deferred.

Deferred, each with its trigger: a `Rests:` block (a reader must separate rests in a way `rest`
cannot serve); candidates kept at STOP as offer Questions (the owner says so); a pusher check for
the offer record (a session ends after a triage with no record in its wrap-up commit); entries that
may wrap (the warnings fire on more than one in ten edits of files with entries); a cadence in a
base wave skill (proposals 0003 or 0025 land one); the policy-schema compatibility check (the next
change to the policy schema, or the triage scan listing it).

## Consequences

- Easier: every project gets the practice whole from the base, with or without waves and releases;
  one reader prints every state a skill or a pass needs; a returning idea grows its file and a
  count; an offer the owner does not get to is kept as a question in the queue they already read.
- Harder and costs: five fields, two warnings and two block prefixes (`Waits: at rest`,
  `Question: offered`) to keep exact; one README edit per project; one description in every
  session's skill listing. Silence now writes: an offer left unanswered adds up to nine files, each
  raising the open count and the bar. The spark is the one trigger only a description carries.
- The base takes a practice one project has run for a day, before any outcome is known; the
  predictions below are checked where the volume is.
- Exit: delete the skill, `triage` step 9's pointer and the `handoff` line. The authorship,
  readiness and landing rules outlive the skill; reverting them is a second incompatible change with
  a README edit per project, after a sweep of `New:` and `Use:` lines into blocks or files. The
  fields stay and read zero or false.

Predictions (the trigger scan reads these; superseding this ADR records each outcome first):
- Prediction, check by 2027-01-31: in dystopo's log, idea files added per ISO week, averaged over
  the four weeks before the check and counting offer Questions, are fewer than 50.
- Prediction, check by 2027-02-22: in every tree checked out beside Hatchery, rows with `offered`
  true and `block` `question` number fewer than 30, and at least half of the offer Questions
  written (the sum of the offer records' `unanswered`) have been answered.
- Prediction, check by 2027-02-22: at least one offer record in some tree names a spark as its
  trigger.
- Prediction, check by 2027-04-17: `node .claude/hooks/queue-list.js ideas` prints no placement
  warning in any tree checked out beside Hatchery.
- Prediction, check when the second rest review is recorded in any tree: the two records are seven
  or more days apart and the owner decided at least half the lines listed.
