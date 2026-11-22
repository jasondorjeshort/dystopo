# 0065, critic review: the coordinator's brainstorms and a readable ideas queue

Date: 2026-11-22
Reviewer: critic (claude-opus-5-5), read-only; no edit to the proposal, nothing committed, no Gradle.
Read in order: `CLAUDE.md`, the proposal, `docs/ideas/README.md`, `.claude/rules/docs.md`,
`.claude/skills/triage/SKILL.md`, `.claude/skills/carry-back/SKILL.md`, `.claude/skills/wave/SKILL.md`
(Close step 6), ADR 0036, ADR 0038 (departure table), Hatchery ADRs 0007, 0013, 0016, 0017 and the
sibling `0017-queue-list.md`, `.claude/hooks/queue-list.js`, every `2026-11-22-*` idea that says
"coordinator's own", and the git history named below.

Verdict: **B1 is the right shape, after edits.** No objection is blocking; six are serious and each
has a cheap fix inside B1. Count: 0 blocking, 6 serious, 7 minor.

## Claims checked

1. **The 2026-11-22 count. The brief's 25 is right; the proposal's 22 is what is left.** Two
   commits added the batches: `be89dabd` (14 files, "fourteen of the coordinator's own", trailer
   `Co-Authored-By: Claude Sonnet 5.5`) and `73467e3c` (11 files, Opus trailer). Three of the 14
   were deleted at triage as folds on the owner's choice: `5144f950` (coarse-map hint into the
   coverage panel), `d268de91` (download only what is missing, into edit and resize), `2b5cb85c`
   (one time slider, into the one clock). `grep -l "coordinator's own" docs/ideas/2026-11-22-*.md`
   gives 22.
2. **The queue figures** hold: `node .claude/hooks/queue-list.js ideas --json` gives 172 rows,
   `answered` 78, `plan` 68, `waits` 15, `untriaged` 11, no `question`, nothing on stderr. Git:
   288 idea files ever added and 116 removed (`git log --diff-filter=A|D --name-only`), and no
   deletion subject says not wanted, declined or the owner's no. The weekly and origin splits were
   not rechecked (the scratchpad file is not durable; the proposal marks them (H)).
3. **`queue-list.js` reads only the top level**: confirmed, `fs.readdirSync(dir)` at line 155, then
   `.md`, not `README.md`, and `statSync(...).isFile()` (lines 161-162); the contract
   (`0017-queue-list.md` section 1) says subdirectories are ignored. Option C's lane is invisible.
4. **B2's `rests` value.** ADR 0017 item 5 promises the `--json` field names and the `?` literal,
   not the value set. Hatchery ADR 0013 says "a new block kind that older readers may ignore is
   free and needs no ADR", which a reader could cite for B2. But an older reader would not ignore
   a `Rests:` block: the reading (`0017-queue-list.md` section 2) takes the last `Plan:`,
   `Question:` or `Waits:` line, so a file ending in `Rests:` lists as `untriaged`, and triage
   would triage it again. That makes it a changed meaning, so it is incompatible, which agrees with
   ADR 0016's rule for the grammar. The proposal's conclusion (B2 needs a Hatchery ADR) stands; the
   Claims line should cite 0013's sentence and say why it does not apply.
5. **A `Waits:` from the owner's chat acceptance.** The README says "no agent writes one on its
   own judgement"; `.claude/rules/docs.md` says "only the owner writes `Waits:`"; the triage
   Bounds say "A `Waits:` is the owner's to write". Practice is already looser than both: 9 of the
   15 Waits were written on 2026-11-22 "by multiple choice at triage", and two
   (`2026-11-14-a-shared-map-picture-carries-its-place.md`,
   `2026-11-14-the-sweep-reruns-only-parts-whose-inputs-changed.md`) read "Waits: the coordinator,
   2026-11-14, by the owner's leave", a blanket leave and not a choice about that idea. My reading:
   the owner's word in chat meets the README's test when the owner's words, or the text of the
   option they picked, are quoted and dated on the line. It does not meet it when the coordinator
   offers "record at rest" as the default (proposal line 196) and the owner just doesn't object.
   The rule file and the README say different things and should say the same.
6. **The 22-file classification** is roughly reproducible (the sample is under objection 6), but
   the "8 files instead of 22" sum is not.

## Objections

1. **Serious. The proposal contradicts itself, and the base, on who rewrites a resting Waits.**
   Line 197-198: triage "reads it again only when a recurrence entry lands or the owner names it,
   then replaces the Waits with a Plan or Question." Lines 126 and 200-201: a Waits "is only flagged
   to them", "never rewritten by triage, since Waits is theirs". The base triage Bounds agree with
   the second ("it is not yours; leave it"). *Resolve:* delete 197-198's "then replaces". When a
   recurrence lands on a resting Waits, triage flags it; the coordinator rewrites the block only
   on the owner's word in chat, quoted.

2. **Serious. Resting Waits cannot be told apart from trigger Waits, and the proposal's own
   change-trigger has half fired.** Already 4 of 15 Waits are rests without saying so ("Eventually."
   No trigger named; "Unsure how valuable, but eventually maybe." No trigger named; "the owner asks
   for it"; "the owner reopens background polling"). Two more are the coordinator's on blanket
   leave. "The owner picks it, or it comes up again" is a Question with the answer "later", not a
   trigger in the README's sense ("a release, a platform change"). The coordinator controls "comes
   up again", since it can offer the idea again. The open idea
   `2026-11-22-waits-that-wake-up-when-their-trigger-lands.md` would group by trigger and is
   polluted from day one. Every Hatchery project's triage inherits this blur. *Resolve:* keep B1's
   carrier but give it a fixed opening anyone can grep, e.g. `Waits: at rest, the owner's word
   (<date>): "<their words or option text>".`, so it costs no grammar change. Require the quote.
   Never pre-select rest: the offer lists it as one choice among the others. Align `docs.md` with
   the README: "only the owner decides a Waits; an agent writes it only from the owner's quoted
   words". Name the two blanket-leave Waits in the rest review so the owner can confirm them.

3. **Serious. The recurrence count changes the drain order for every project, and the count is
   prone to echo.** The README says "A pass builds only a `Plan:` or an answered `Question:`,
   oldest first" (line 58). The proposal (lines 123-124) puts recurrent files "first among its
   block ... in wave planning". That is a change to ADR 0007's drain rule, carried to the base
   inside a triage step. The signal is also weakest exactly where the practice runs. The
   brainstorm follows a triage, where the same model has just read the queue, and the cheap check
   greps the queue. So the coordinator's "came up again" often echoes what it read minutes earlier,
   not an independent return. The owner's "often the best ones" is about ideas returning from
   separate contexts. *Resolve:* count `Came up again:` lines by distinct session and source
   (the owner, a different model or session, a report), not by line. Leave out a recurrence in the
   session that read the file. Show the count to the owner as information, and leave the build
   order as it is unless the owner rules on it (as its own line in the ADR, carried to Hatchery as
   an idea).

4. **Serious. `Use:` lines and recurrence entries die with their file.** Ideas are deleted on the
   branch that lands their work (`docs.md`, Indexes; README, "What drains it"). When the
   primitive's Plan lands (say the line-of-sight function), its `Use:` lines (coverage panel,
   Download card line, where-to-stand) go with the file, unbuilt. Nothing records that. That is a
   lapse without the owner, which the proposal's first constraint forbids. Recurrence entries on a
   file that a fold deletes have the same problem. *Resolve:* the landing pass turns each
   unbuilt `Use:` into its own untriaged file (or a `Plan:` if the owner already said yes) in the
   same commit, and the merge check names them. Or keep owner-accepted uses as their own files with
   `related:`, which is what the README already says for "overlapping but distinct" (README line 49).

5. **Serious. The triage carry-back is scoped too narrowly, and one step calls a flag that does
   not exist yet.** The proposal sends "the triage skill's step 9". But triage step 4 reads only
   `--untriaged`, so it never opens a `plan`, `answered` or `waits` file. "A `New:` that changes
   what was settled reopens the block" (line 125) and "a recurrence wakes it at the next triage"
   (line 200) have no reader. The Bounds also allow replacing a block only after an owner's
   Answer. The "until it exists, `grep -c`" fallback is right, but a carried text that ever names
   `--recurring` exits 2 (`queue-list: usage:`) in every project whose base is older. Only
   `update-from-hatchery` lifts that, and the message does not say so. *Resolve:* the carry-back
   names steps 4 and 9 and the Bounds. Step 4 gains "and every file with a dated `Came up again:`
   later than its block's date". The skill text uses the grep until the base line carries the flag.

6. **Serious. The "8 files instead of 22" claim does not hold, and the bar is not calibrated
   against the owner's own choices.** (a) There were two offers (`be89dabd`, 14; `73467e3c`, 11),
   so 3/3/3 allows up to 18 slots. The proposal's own passes (7 lever plus 5 moonshot, 12) all fit,
   and no stated rule gets to 8. (b) At 172 open, the proposal's table puts the bar at step 5
   (its own header example says so), not step 4. The step in force was the one it could not
   measure. (c) Of the 6 "fails", the owner answered the coverage panel yes, the Download card got
   a Plan, and the storage advisor got the owner's Waits. The owner kept half of what the bar would
   have cut. (d) The batch of 14 was "offered when the owner asked for more", which step 6's "the
   owner asked for its topic" would exempt. Sample recheck: the line-of-sight function, sweep
   timing ("at least four ideas wait on"), waking Waits and "why is this blank?" do show a lever.
   Camera fix (it names `2026-11-02-horizon-match-fix-from-the-camera.md`) and route skylines
   (`2026-11-21-the-horizon-view-moves-with-you.md` line 16, "precomputed skylines at intervals")
   are true recurrences. The subscription mostly repeats `2026-11-04-areas-update-themselves...`
   (priority follows routes). Distance-aware detail is contestable: its own text separates
   thinning a held map from refreshing one. Peaks seen today fails as a use of the line-of-sight
   function rather than as "depends on an unbuilt feature". My count is about 7/5/3/7 against the
   architect's 7/5/4/6, which is close. *Resolve:* restate the measurement as 25 offered in two
   offers, 3 folded by the owner at triage (which the current practice already does), 12 passing
   at step 4, step 5 unmeasured. Drop "8". Say plainly that the bar would have cut three ideas the
   owner kept, so a failing idea is recorded as a `Use:` (with objection 4's fix), never dropped.

7. **Minor. The bar's count is mostly build backlog, and it flips when a wave builds.** 146 of the
   172 are `plan` or `answered`, which the proposal calls "build capacity, not reading load"
   (line 175), yet they raise the bar for new ideas. 172 is 12 above the 160 step, so one wave that
   lands a dozen ideas drops the bar a step and the next brainstorm raises it again. Resting Waits
   count too (line 194) and only the owner drains them, so the rest pile ratchets the bar. The
   owner asked for "log scaling" of "the number of ideas", so counting every file is defensible.
   *Resolve:* state the count (all files the listing prints), read it once per session, and step
   only on crossing a boundary by 10% (hysteresis). The ADR says resting ideas count by design.

8. **Minor. The step in force today is the costliest one, on the most expensive model, possibly
   several times a session.** Step 5 ("which open idea of its kind it outranks") means reading the
   neighbours of each of up to nine ideas across 172 files. Triage, a wave close and a release can
   fall in one session, which is three offers. *Resolve:* one offer per session, at its last
   trigger, plus sparks. Step 5 is judged from titles (`queue-list.js ideas --json` gives kind and
   title) rather than bodies. Skip the offer when the 5-hour meter is past the usage gate's line
   unless the owner asks.

9. **Minor. The bar holds ideas back without telling the owner.** It "never refuses" but nothing
   records what the coordinator chose not to offer, and the owner cannot ask to see what they
   cannot see. *Resolve:* the header adds "N held below the bar; ask to see them."

10. **Minor. Model provenance is already ambiguous.** The 14 ideas of `be89dabd` say "the
    coordinator's own" but the commit is co-authored by Sonnet 5.5. The Source line's
    `coordinator (claude-opus-5-5)` cannot say whether a scribe wrote someone else's idea.
    *Resolve:* the Source line names the model that generated the idea; the commit trailer
    already names the writer. The Source line also follows the file's H1 (a file without one
    would list the Source line as its title, `0017-queue-list.md` section 2) and names only public
    context for the trigger (the privacy rule).

11. **Minor. The rest review reverses a README sentence, and its cadence can't be checked.** README
    line 67: "no agent runs that sitting". The review is agent-run, which is a principle change
    under ADR 0007 that every project inherits. "Every few waves" can't be checked. Listing
    answered-but-unbuilt older than a month re-asks about 40 questions the owner already answered
    (median 20 days on 78). *Resolve:* the owner's explicit word for the reversal, quoted in the
    ADR. A fixed cadence (every third wave close, or when resting files pass a count). The review
    lists only resting Waits (objection 2's prefix), sorted by distinct recurrences, with the
    answered-but-unbuilt left to wave planning, where line 257 already puts them.

12. **Minor. The exit, moving-parts and change lines miss the cost.** B's "Moving parts: one flag;
    a periodic review" leaves out the five conventions every option adds and every Hatchery project
    inherits (Source line, cheap check, recurrence entry, `Use:` line, bar table). None of the
    "What would change" lines has a date, and none checks objection 3's echo. *Resolve:* list the
    shared pieces as moving parts. Add dated predictions, e.g. "by 2027-02-22, at most a quarter of
    `Came up again:` lines come from the session that read the file" and "resting Waits carry the
    fixed prefix in 100% of cases", each with its check.

13. **Minor. Option C's and B2's grounds should cite the base exactly.** C: ADR 0017 item 7 defers a
    fourth queue directory "until an ADR adds a name". That is accurate, and the proposal should
    cite item 7 directly. B2: see Claim 4. *Resolve:* two citations in the Claims section.

## Mechanisms that refuse or fail closed

| Mechanism | Who lifts it, from where, mid-session | Does its message say so |
|---|---|---|
| A `Waits:` stops every build pass | The owner, in chat or by editing the file; the coordinator rewrites only on quoted words | The block names its trigger; "the owner picks it" says who, once objection 2's prefix makes it greppable |
| Triage Bounds ("a Waits is not yours") stop the wake path | The owner only; triage flags | Not today: nothing in triage reads Waits files (objection 5) |
| The bar's self-censoring at the offer | The owner, by asking "what else?" | No: objection 9 adds the held-back count |
| `--recurring` on an older base exits 2 | `update-from-hatchery`, run by a delegated agent | No: `queue-list: usage:` names no cause (objection 5) |
| Privacy pre-commit and pre-push on a committed idea | Fix the text; a false positive is the owner's | Yes, the hooks name the term; never `--no-verify` |
| The usage gate | Does not stop the coordinator's own brainstorm; the owner's meters still pay (objection 8) | n/a |

## Hidden coupling

- Resting Waits change what `Waits:` means in every project, and they decide the waking-Waits
  idea's design for it (objection 2).
- Recurrence standing changes the drain order (objection 3).
- The bar moves with build throughput, and the rest pile ratchets it (objection 7).
- Deleted-when-done silently drops `Use:` lines (objection 4).
- Recording a recurrence on an answered Question puts the coordinator's `New:` above an owner's
  `Answer:`. The README's "not yours; leave it" holds only if the entry stays a flag and never
  changes the answer.

## The strongest case against B1, and the best alternative

Against: B1 is the first change that lets an agent write the one block the grammar reserves for
the owner. It does so while the tree already shows that line slipping (two blanket-leave Waits,
nine multiple-choice Waits, four rest Waits with no trigger). Hatchery copies the slip to every
project, and the sorted rest review reverses the README's "no agent runs that sitting". Add the
recurrence ranking and a periodic review, and a modest practice the owner says works becomes five
conventions, a flag and a cadence across every project before any of it has been measured.

Best alternative, "A plus the rest prefix": the shared pieces without the ranking (Source line,
cheap check, recurrence entries counted by distinct source, `Use:` lines with objection 4's fix,
the bar as skill guidance with the held-back count) and the rest marker as a fixed `Waits: at
rest, the owner's word ...` prefix. That is B1's carrier with no rest review, no `--recurring`
flag and no change to the drain order. The review and the flag are added when the dated
predictions show the rest pile or the recurrences need them. This alternative differs from the
recommendation only in sequencing, which is why I am not recommending it outright.

## Pre-mortem

It is 2028-11 and the queue holds about 600 files in Dystopo and hundreds in each sibling. Most new
coordinator ideas reached the owner only as recurrences, because at step 6 "the owner asked for its
topic, or it recurred" (table, last row) was the only open door for an unrequested idea. The
coordinator brainstormed right after reading the queue in triage, and it re-offered what it had just
read. The `Came up again:` counts swelled from one model's echo, and "listed first ... in wave
planning" (lines 123-124) put those files ahead of the owner's own, older ideas. Meanwhile resting
Waits made up most of the Waits, so the waking-Waits tool, built on triggers, woke nothing useful.
Three primitives landed and took a dozen accepted `Use:` lines with them unnoticed. The lines that
should have caught it are 123-124 (the count as standing, counted by line rather than by
independent source) and the last row of the bar table; the "What would change" list had no check on
where recurrences came from.

## Recommendation

Adopt **B with carrier B1**, edited:
- objection 1's contradiction removed;
- the fixed rest prefix with the owner's quoted words, rest never offered as the default, and
  `docs.md` aligned with the README (objection 2);
- recurrence counted by distinct source and shown, not used to reorder the drain without a
  separate ruling by the owner (objection 3);
- `Use:` lines turned into files on landing (objection 4);
- the carry-back scoped to steps 4 and 9 and the Bounds, with the grep fallback (objection 5);
- the measurement restated without "8 files" (objection 6);
- the rest review limited to resting Waits, on a fixed cadence, with the owner's explicit word for
  reversing the README's sentence (objection 11).

Confidence about 70% that B1 so edited is the right carrier. The 30% is mostly that the rest
review and the flag are premature (the alternative above). The B2 grammar change is not justified
yet: the prefix gives the waking-Waits tool what it needs without migrating every project. Residual
risks after the edits:
- the coordinator still writes owner-reserved blocks, only on quoted words;
- the bar stays a judgement and cannot be measured above step 4;
- the cost of each offer falls on the owner's meters with no setting to turn it off but the
  owner's word in chat.
