# 0065. The coordinator's brainstorms: offered on yes, recurrence as updates, a rest pile on the owner's word

Date: 2026-11-22
Status: accepted

Record: proposal `../proposals/0065-coordinator-brainstorms-and-the-ideas-queue.md` (option B,
carrier B1, as revised) and `../proposals/0065-critic-review.md` (0 blocking, 6 serious, 7 minor;
all answered in the proposal). Formats and the bar table: the sibling
[`0065-entry-formats-and-the-bar.md`](0065-entry-formats-and-the-bar.md). Settled by the coordinator
after the critic, with the owner's answers of 2026-11-22. Applies to Hatchery too (below).

## Context

On 2026-11-22 the owner asked to make brainstorming new ideas, moonshots included, standard after a
triage or a wave. A human friend suggested it; the owner: "The ideas suggested at the end of doing
work have been very high quality ... Some have been discarded, many have been postponed
indefinitely, but they're nearly always interesting." The owner's answers, binding: no hard cap but
"log scaling" of the quality bar as the queue grows; offers after a triage, at a wave close or
release, and "any time something sparks ... so long as we have a good workflow for HANDLING the
half-baked ideas later"; recorded only on the owner's acceptance; "3 short/3 intermediate/3
moonshots has felt cleanest"; the sanity check at triage; the source recorded "including agent
model", marked not sanity-checked; duplicates are "often the best ones", and "updates to existing
ideas, is more the idea behind duplicates ... Both can be recorded"; "keeping moonshots, or ideas
that are not ready yet, in their own pile might be a thing"; one primitive never presented as
several features (corrected twice that session). By multiple choice the owner also chose that the
rest review lists resting ideas only and that there are no cost guards on offers.

A step 9 for the triage skill was already sent as `carry-back/triage-brainstorm-step` at 4d00b15
and held for this decision (ADR 0038's departure table).

The queue's history (git, 2026-10-13 to 2026-11-22; (H) classified by regex over commit subjects):
288 idea files added, 116 removed, 172 present; added per ISO week W42 to W47: 21, 9, 64, 41, 53,
100. Origin (H): coordinator-side 155, owner 104, process 29. Removed (H): built 84, already in the
code 6, folded 6, converted 13, scan outcomes 10, not wanted 0. Brainstorm batches: 12 of 57 built;
the moonshots of 2026-10-29 all open. Blocks: 78 answered but unbuilt (median 20 days), 68 Plans,
15 Waits (4 rests with no trigger, 2 written by the coordinator on a blanket leave), 11 untriaged,
0 blank Questions. Triage median 0.44 days; the owner answers in sittings of 11 to 37. The README's
whole-listing sitting never happened. The critic rechecked the totals and the zero. Example commits:
a coordinator batch be89dabd, owner reports 6549db59, built 904b484f, already in the code 4733f465,
folded 5144f950. The 2026-11-22 offers were be89dabd (14 ideas, Sonnet trailer) and 73467e3c (11,
Opus); 3 were folded at triage. Against the bar at step 4, about 12 of the 22 left would pass (the
architect 7 lever, 5 moonshot, 4 recurrence, 6 fail; the critic 7, 5, 3, 7); the owner kept three
that would fail, so a failing idea is folded in, never dropped. Step 5 cannot be measured.

Research (fetched 2026-11-22): Shape Up ch. 7 and 8 (https://basecamp.com/shapeup/2.1-chapter-07,
https://basecamp.com/shapeup/2.2-chapter-08: no backlog grooming, "really important ideas will come
back to you"); PEP 1 (https://peps.python.org/pep-0001/: Deferred), Rust RFC 1068
(https://github.com/rust-lang/rfcs/blob/master/text/1068-rust-governance.md: postponed), the KEP
process (https://github.com/kubernetes/enhancements/tree/master/keps/sig-architecture/0000-kep-process:
provisional; proposer and decider separate); Nemeth et al. 2004
(https://escholarship.org/uc/item/4k70n7v8: debate beats "no criticism"); Sharma et al. 2023
(https://arxiv.org/abs/2310.13548: AI assistants are sycophantic, so the owner decides).

## Decision

1. **The offer.** After a triage, at a wave close or a release, and on a spark, the coordinator
   offers at most nine numbered lines, about three each near-term, intermediate and moonshot (scope:
   features, moonshots, process and tooling, research questions). A header states the open count at
   the session's start, the bar's step, and "held back: N" (the owner may ask to see them). The
   owner answers per line: record, record at rest, fold, or no. Nothing is written unasked. No cost
   guard limits the offers.
2. **The cheap check before offering** is a grep, not the sanity check (which stays at triage): the
   anchor exists; it is not already built; a matching idea is searched for; the list is read for one
   primitive split into uses. A match is offered as a recurrence entry or a `Use:` line.
3. **The Source line** is the first body line after the H1: source, generating model and recorder,
   public trigger, "offered and accepted by the owner; not sanity-checked", and `Moonshot.` when it
   is one. Frontmatter stays `date`, `kind`, `related`; no `source:` key, no `kind: moonshot`.
4. **Recurrence is an update.** A recurring idea adds a `Came up again:` entry to the existing file,
   with an optional `New:` modification. It counts once per distinct session or source; an echo in
   the session of a triage that read the file is kept for its `New:` and does not count. The count
   is shown at the owner's sittings and in the coordinator's wave-planning read; the oldest-first
   build order is unchanged, and the owner may pick by recurrence.
5. **Uses** of one primitive are `Use:` lines on the primitive's file, not files of their own.
6. **The bar** rises one step per doubling of the open count (the sibling's table), read at the
   session's start, guidance only; an offer below it is allowed with a reason. The 3/3/3 shape is the
   ceiling and the bar rises inside it.
7. **The rest pile.** "Record at rest" writes `Waits: at rest — "<quoted words>" (the owner,
   <date>)`, only on the owner's quoted word; it is never the coordinator's default. Resting ideas
   count toward the open count.
8. **Triage never rewrites a Waits.** Triage step 4 also lists every file whose last dated entry
   (`Came up again:`, `Use:`) is newer than its block: a Plan or Question may be reopened; a Waits is
   flagged to the owner in the triage report, and only the owner's answer changes it.
9. **Landing keeps what is unbuilt.** A pass that lands an idea removes what it built and rewrites
   the block while any unbuilt `Use:` or `New:` remains; the file is deleted only when nothing
   unbuilt is left.
10. **The rest review.** Every third wave close (recorded in the wave record) or when the owner asks,
    the coordinator offers the resting ideas only, sorted by distinct recurrences, one line each; the
    owner decides each line: keep, wake or delete. It is an offered list, not a sitting an agent
    runs. At the first review the 4 Waits with no trigger and the 2 written on a blanket leave are
    listed for the owner to restate or convert; triage converts none.

**Files changed by the pass after this ADR** (the triage skill's text is base-owned):
`.claude/skills/triage/SKILL.md` (steps 4 and 9 and the Bounds); `.claude/skills/wave/SKILL.md`
(Close step 6: the offer and the rest review every third close); `CLAUDE.md` (the brainstorm
bullet as a pointer); `docs/ideas/README.md` (the Source line, the entries, the rest prefix, the
landing rule; "no agent runs that sitting" becomes "the coordinator offers the rest review on its
cadence; the owner decides each line"); `.claude/rules/docs.md` ("only the owner decides a Waits;
an agent writes one only from the owner's quoted words"); a departure row in ADR 0038.

**Hatchery.** The triage text (steps 4 and 9 and the Bounds) goes as a carry-back that replaces the
held `carry-back/triage-brainstorm-step` at 4d00b15. The rule, the Source line, the recurrence
entry, the at-rest prefix and the rest review go as an idea filed in Hatchery citing this ADR,
because rules and decisions are not carry-backs (`carry-back` skill).

**Deferred.** A fourth block `Rests:` (B2): when resting Waits crowd the trigger Waits so that the
waking-Waits idea cannot use the prefix; it needs a Hatchery ADR (an older `queue-list.js` would list
a file ending in `Rests:` as untriaged, so Hatchery ADR 0013's free new block kind does not apply).
A `--recurring` flag on `queue-list.js`: filed as a Hatchery idea; no skill depends on it until the
base line carries it. Out of scope: user settings (ADR 0044's are the app's; this is process).

## Consequences

- Easier: the owner sees at most nine lines per offer; a returning idea grows its file and its
  count; moonshots rest visibly without a triage Question each; accepted uses survive a landing.
- Harder and costs: five conventions every Hatchery project inherits (Source line, cheap check,
  recurrence entry, `Use:` line, bar table), a triage reader, a landing rule and a review every
  third wave; the coordinator writes the owner's block, though only on quoted words; the bar stays a
  judgement, unmeasurable above step 4; offers spend the owner's meters with no guard but their word.
- Maintained: the prefix and entry formats exact (greps depend on them); the review line in each
  third wave record.
- Superseded when the predictions below fail, or when Hatchery decides the rule differently.

Rejected:
- A (one pile): every half-baked idea and moonshot costs a triage Question; no reading mechanism.
- B2 (a `Rests:` block): a grammar migration in every project for what a prefix gives now.
- C (a lane with a lapse rule): invisible to `queue-list.js` and deferred by Hatchery ADR 0017 item
  7; a lapse clock turns "postponed but interesting" into deletion prompts.
- D (only through triage): sparks and moonshots get no home.
- The critic's "A plus a fixed rest prefix": the owner put readability in scope, and the rest review
  is the only reading mechanism with a cadence.

Predictions (the trigger scan reads these):
- Prediction, check by 2027-01-31: idea files added to `docs/ideas/` per ISO week, averaged over the
  four weeks before the check, are fewer than 50 (half of W47's 100), with recurrences landing as
  entries rather than files.
- Prediction, check when the second rest review is recorded: at each of the first two the owner
  decides at least half the lines in one sitting.
- Prediction, check by 2027-02-22: at most a quarter of `Came up again:` entries name the session of
  a triage that read the file, and every resting Waits carries the prefix.
