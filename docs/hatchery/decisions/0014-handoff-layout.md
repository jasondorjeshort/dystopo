# 0014. Handoff layout: one file by default, a per-stream split the base accepts

Date: 2026-10-16
Status: accepted, superseded in part by 0015

Superseded in part by 0015 (`0015-handoff-shape-at-decant.md`): the Deferred switch trigger (two
stops or two conflicts) is replaced by the writer count asked at decant and the day a second
parallel session opens. Everything else stands.

Record: proposal `../proposals/0014-handoff-layout.md` and `../proposals/0014-critic-review.md`.
Evidence: `docs/findings/2026-10-16-handoff-layout-practices.md`. Supersedes in part ADR 0002,
Session continuity (the Updated line, the handoff's own commit, the cap's scope); the rest of 0002
stands. The exact text of the two `handoff` skill files is the sibling `0014-handoff-skill.md`.

## Context

A downstream build-phase project split `docs/HANDOFF.md` into a map of `@` lines plus one file per
workstream, dropped the commit hash from the Updated line and edits the stream file in the work
commit; its reasons were several parallel sessions rebasing before every push, a per-push suite the
hash forced to run twice, and 49 handoff commits in a week of 439. Hatchery has one of those three
causes: the hash, which forces a handoff-only commit and is wrong by one the moment it lands (85
commits since 2026-10-11, 24 touching the handoff, 10 touching nothing else; counted 2026-10-16).
The finding found no single-writer project that split a status file and no team that went back;
every split answers parallel writers on the same lines. Verified 2026-10-16 (critic review, claims
1 to 9): Claude Code 2.1.296 resolves a relative `@` import from the directory of the importing
file, to four hops (https://code.claude.com/docs/en/memory, "Import additional files"), so the
downstream map's `@docs/handoff/<file>.md` lines load `docs/docs/handoff/<file>.md`, which does
not exist, with no warning anywhere; its sessions since the split have loaded no stream. Code
spans and fences are skipped; there is no glob or directory import; subagents receive the resolved
imports; `wc -l docs/handoff/*.md` with no such directory exits 1 while `git ls-files -- 'docs/handoff/*.md'`
exits 0; `post-edit.js` lists a `docs/handoff/x.md` only against `docs/handoff/README.md`. The
pusher never rebases: it stops when behind (check 3), and the base has no procedure for two
sessions on one branch. Unverified: whether nested imports re-expand after `/compact`. The
downstream conflict rate is the owner's observation; this clone's reflog cannot confirm it.

## Decision

The default stays one `docs/HANDOFF.md` of four sections, cap 40, with `Updated: <date> by <who>`
(no hash; the last change is `git log -1 -- docs/HANDOFF.md`), and a handoff edit rides in the
next work commit; only a wrap-up handoff is its own commit. The split is a documented second shape
in `.claude/skills/handoff/split.md`: the map's lines are `@handoff/<file>.md`, one file per
stream under `docs/handoff/` with no README and no fixed files, 40 lines each and 120 in total,
the map as the directory's index. The tree says which shape a project is in: `docs/handoff/`
exists or it does not, and the base ships nothing there. The tooling accepts both shapes
unconditionally and resolves every `@` line the way Claude Code does. A project switches at the
trigger in Deferred by its own one-page ADR, touching nothing under `.claude/`. The handoff paths
join ADR 0013's surfaces (item 13); its content is not a surface. Each edit below is exact; a
`tester` makes them from this ADR and the sibling alone, then runs `node .claude/hooks/test-hooks.js`
and the three command lines of item 6, and reports `wc -l` of every file it touched.

1. `.claude/skills/handoff/SKILL.md`: replaced by section A of the sibling. What changes: the
   Updated line loses the hash; step 1 gathers `git log -1 --format='%h %cs' -- docs/HANDOFF.md docs/handoff/`;
   step 5 says the edit rides in the next work commit and only a wrap-up is its own; one sentence
   sends a project whose `docs/handoff/` exists to `split.md`; Rules gain where each section lives
   per shape and the two chain checks (`/context` under Memory files; the hook and check 7).
2. `.claude/skills/handoff/split.md` (new): section B of the sibling, verbatim.
3. `.claude/rules/docs.md`. Size caps, the handoff line: "`docs/HANDOFF.md`, and each
   `docs/handoff/*.md` where that directory exists: at most 40 lines, at most 120 together. Detail
   goes to an idea or a finding; the handoff links." Indexes, after the first bullet: "Exception:
   `docs/handoff/` has no README; its index is the `@handoff/<file>.md` lines of `docs/HANDOFF.md`
   (`handoff` skill, `split.md`)." Content rules, the `HANDOFF.md` bullet becomes: "`HANDOFF.md`
   in the single-file shape has exactly the sections Now, Next, Waiting on user, Open findings; in
   the split shape it is the map `split.md` describes. In both, the first line after the title
   says it is coordinator state, and it never contains procedure or rationale; it links." New
   bullet after it: "An `@` import line in `CLAUDE.md`, `docs/HANDOFF.md` or `docs/handoff/*.md`
   resolves from the importing file's directory, so the map writes `@handoff/<file>.md`; a target
   that does not exist loads nothing and says nothing. The post-edit hook and pusher check 7
   resolve every such line."
4. `.claude/hooks/post-edit.js`, five behaviours, messages as in item 7: (a) the 40-line cap
   applies to `docs/HANDOFF.md` and to `docs/handoff/*.md`, the message naming the file; (b) when
   the edited file is either, the total of `docs/HANDOFF.md` plus every `docs/handoff/*.md` on disk
   (`fs.readdirSync`, `.md` only) over 120 warns; (c) a `docs/handoff/*.md` counts as listed when
   `docs/HANDOFF.md` has a line `@handoff/<basename>`; no README is consulted for that directory;
   (d) for `CLAUDE.md` at the root, `docs/HANDOFF.md` and `docs/handoff/*.md`, each line matching
   `^@(\S+)` outside fenced blocks is resolved against the file's own directory and warns when
   absent, naming the resolved path; (e) nothing else changes, `INDEX_EXEMPT` included.
5. `.claude/hooks/test-hooks.js`: cases for item 4 by behaviour, in their own scratch root so P02
   stands: a 41-line `docs/handoff/x.md` warns with its path and 40; a map plus streams totalling
   121 warns the total and 120, and 120 does not; a stream the map does not import warns, one it
   imports is silent; a map holding `@docs/handoff/x.md` beside an existing `docs/handoff/x.md`
   warns naming `docs/docs/handoff/x.md`, and `@handoff/x.md` is silent; a root `CLAUDE.md` with
   `@docs/HANDOFF.md` present is silent and with `@docs/GONE.md` warns; an `@` inside a fence or a
   code span warns nothing. Case ids and labels are the tester's (not promised, ADR 0013).
6. `.claude/agents/pusher.md`, check 7 becomes: "Doc size caps and the handoff chain (ADR 0002,
   ADR 0014, `.claude/rules/docs.md`): every tracked Markdown file under `docs/` is at most 300
   lines; `docs/HANDOFF.md` and each `docs/handoff/*.md` at most 40 and 120 together; every `@`
   line in `CLAUDE.md`, `docs/HANDOFF.md` and `docs/handoff/*.md` resolves from its file's
   directory; `docs/design/proposals/` is exempt from the 300 (closed records). Run:" then the
   three lines, each on its own line in the file:
   `git ls-files -- 'docs/*.md' | while read -r f; do case "$f" in docs/design/proposals/*) continue;; esac; n=$(wc -l < "$f"); cap=300; case "$f" in docs/HANDOFF.md|docs/handoff/*.md) cap=40;; esac; [ "$n" -gt "$cap" ] && echo "FAIL $f: $n lines, cap $cap"; done`
   `t=$(git ls-files -- docs/HANDOFF.md 'docs/handoff/*.md' | xargs cat | wc -l); [ "$t" -gt 120 ] && echo "FAIL handoff total: $t lines, cap 120"`
   `for f in CLAUDE.md docs/HANDOFF.md $(git ls-files -- 'docs/handoff/*.md'); do d=$(dirname "$f"); grep -oE '^@[^[:space:]]+' "$f" | cut -c2- | while read -r p; do [ -e "$d/$p" ] || echo "FAIL $f: @$p resolves to $d/$p, which does not exist"; done; done`
   then the existing sentence: any `FAIL` line means stop and report it; the fix is a trim, a
   closed stream or a corrected line, not a cap change.
7. `docs/design/decisions/0002-hook-specs.md`, section 2. The `CLAUDE.md` paragraph gains: "An `@`
   line of the root `CLAUDE.md` whose target is absent: the import message below." Table: the
   `docs/HANDOFF.md` row becomes `docs/HANDOFF.md` or `docs/handoff/*.md` over 40 lines, message
   `post-edit: <path> is N lines; cap is 40. Move detail to docs/ideas/ or a finding.`; new rows:
   the two together over 120, `post-edit: the handoff loads N lines in total (docs/HANDOFF.md plus docs/handoff/*.md); cap is 120. Close a stream or trim.`;
   a `docs/handoff/*.md` with no `@handoff/<name>` line in `docs/HANDOFF.md`,
   `post-edit: <path> is not imported by docs/HANDOFF.md. Add the line @handoff/<name> or delete the file.`
   (this replaces the README row for that directory; the README row's text says so); an `@` line
   in `CLAUDE.md`, `docs/HANDOFF.md` or `docs/handoff/*.md` whose target is absent,
   `post-edit: <path> imports @<target>, which resolves to <resolved> and does not exist (an import resolves from the importing file's directory).`
   The harness paragraph names the new case group and its own scratch root.
8. `.claude/skills/commit/SKILL.md`. Intro, second sentence: "Wrap-up commits (the handoff, WIP)
   at HOLD or STOP are made by the main session, because the usage gate blocks subagent launches
   but not Bash." Step 2 gains: "A handoff edit made while the session goes on rides in the next
   commit that carries work; list it with that work's files. Only a wrap-up handoff, with nothing
   to ride with, is its own commit." Pushing, after the fresh-pusher sentence: "When the pusher
   stops on behind or diverged (check 3), the committing agent under the policy (the `runner` in
   the base) runs `git pull --rebase origin <branch>`; a conflict on the handoff is resolved by
   rewriting the file with the `handoff` skill, any other goes back to whoever wrote the hunk; then
   a fresh pusher. Note the stop in the handoff's Now: it is the evidence for ADR 0014's trigger."
9. `.claude/skills/finding/SKILL.md`, Record step 4: "If status is open and the coordinator should
   act, add the path under the handoff's Open findings (where that lives per shape: `handoff`
   skill)." Resolve step 3: "Update the index line and remove the entry from the handoff."
10. `.claude/skills/proposal/SKILL.md`, step 6: "put both positions to the user under the
    handoff's Waiting on user (`handoff` skill)". `.claude/skills/brief/SKILL.md`, the paste rule:
    "Do not paste the handoff or design docs into the brief; they are imported or indexed."
11. `.claude/skills/update-from-hatchery/SKILL.md`. Step 5, project wins: `docs/HANDOFF.md` and
    `docs/handoff/`. Step 6, the `wc -l` bullet becomes: "`wc -l CLAUDE.md` at most 150; the three
    command lines of pusher check 7 (`.claude/agents/pusher.md`) print no `FAIL`."
    `decant` and `adopt-hatchery`: no change; the default is the single file and their verify
    lines (`wc -l CLAUDE.md docs/HANDOFF.md`) stay true in it.
12. `docs/design/decisions/0002-repository-layout.md`: `Status: accepted, superseded in part by
    0014` and the line below it: "Superseded in part by 0014 (`0014-handoff-layout.md`): Session
    continuity's Updated line and handoff commit; the 40-line cap now also covers
    `docs/handoff/*.md`, 120 in total. Everything else stands." No other edit.
    `docs/design/decisions/README.md`, template Status line: add `| superseded in part by NNNN`.
13. `docs/design/decisions/0013-hatchery-promised-surfaces.md`, surfaces table, new last row, one
    line, the backticks as in the other rows:

    ```
    | The handoff paths: `@docs/HANDOFF.md` in `CLAUDE.md`; `docs/HANDOFF.md` and `docs/handoff/*.md` capped at 40 each and 120 together; the map's `@handoff/<file>.md` lines as the directory's index | `CLAUDE.md`, `post-edit.js`, `pusher.md` | every project in either shape | ADR 0014 |
    ```

    ADR 0013 is `proposed`, so the row is an edit it still takes.
14. `docs/design/README.md`: the 0002 entry gains ", superseded in part by 0014 (handoff)"; a
    0014 entry after 0013: "[0014 Handoff layout](decisions/0014-handoff-layout.md): one
    `docs/HANDOFF.md` by default, no hash, the edit rides in the work commit; a per-stream split
    (`@handoff/<file>.md` lines, 40 each, 120 together, no README, no fixed files) the tooling
    accepts and a project switches to at a stated trigger; every `@` line resolved by the hook
    and the pusher. Skill text in [the sibling](decisions/0014-handoff-skill.md). Supersedes in
    part 0002; adds a row to 0013."
15. The finding `docs/findings/2026-10-16-handoff-layout-practices.md`: `status: resolved`,
    `promoted_to: docs/design/decisions/0014-handoff-layout.md`; trimmed to the `finding` skill's
    150-line cap keeping every source URL, the Result section whole and the Next section dropped
    (the critic settled its questions). Its row in `docs/findings/README.md`: status `resolved`,
    one line ending "Promoted to ADR 0014."

Deferred, each with its trigger (the `triage` scan reads these):
- Hatchery switches to the split shape when the pusher has stopped on behind or diverged twice in
  a working week, or a merge or rebase has conflicted on `docs/HANDOFF.md` twice (noted in the
  handoff's Now; `git log --merges -- docs/HANDOFF.md`). A decanted project has the same trigger.
  Replaced 2026-10-16 by ADR 0015: the shape is chosen at decant by writer count, and a single-file
  project switches the day a second parallel session opens.
- A parallel-session procedure for the base (who rebases, whose handoff wins, how two coordinators
  divide streams): the first time item 8's one line does not resolve a stop on diverged.
- A `triage` line listing a stream whose file is older than the working week: the first stale
  stream found by hand in a split project.
- Compaction: whether nested imports re-expand after `/compact` is unverified. The first session
  of a split project that compacts asks for a token only a stream holds and records the answer in
  the finding; if they do not, the map gains a line saying so and the skill says to re-read it.
- A reader that does not inline `@` imports (Codex, Cursor): a project that adopts one folds back
  to the single file; nothing in the base changes until one does.
- Moving the caps to a shared `limits.json`: a third enforcement point (ADR 0002); there are two.

Sibling migration (ADR 0013 asks). The downstream project, at its next `update-from-hatchery`,
takes the base's `post-edit.js`, `test-hooks.js`, `pusher.md`, `.claude/rules/docs.md` and the
`handoff` skill (base wins under `.claude/`), dropping its own copies and the bare-glob verify
lines in its `decant`, `adopt-hatchery` and `update-from-hatchery`. Its own work, in one commit:
rewrite the map's lines to `@handoff/<file>.md`; delete `docs/handoff/README.md` (the format is
now the base's `split.md`, the rows are the map); keep or fold `live.md` and `waiting.md` by its
ADR 0017, which gains one line recording the spelling fix and that the base accepts the shape
(ADR 0014); check the total against 120 (92 on review day); close its "carry the split back"
stream item, since the carry-back as spelled would have brought the defect into the base. Until
that update, every session there starts with an empty handoff; the coordinator reports this to the
owner, and nothing in Hatchery edits the sibling.

## Consequences

- Easier: no handoff-only commits except wrap-ups; an honest Updated line; a broken import chain
  is named in the same turn and refused at push, in either shape, including the base's own
  `@docs/HANDOFF.md`; the sibling's divergence on five base files closes without a hand merge.
- Costs: one more skill file (`split.md`) in every project, read only when the directory exists;
  about thirty lines in the hook and three command lines in the pusher; a split project loads up
  to 120 lines per launch, three times the single file; the base documents a shape it does not
  run, and its first switch will find what the sibling's use did not.
- The base still has no parallel-session procedure; item 8's line is the minimum, and the
  Deferred item names the gap. The `/context` check is the only one a human runs.
- Supersede when Claude Code changes import resolution or depth (fold every split project back,
  drop `split.md`), when three decanted projects switch within their first month (the default was
  wrong), or when the 120 total is raised twice (the shape, not the cap, is the problem).

Predictions (the trigger scan reads these; an outcome is recorded on the line):
- Prediction, check when the sibling's next update runs: it keeps none of its own copies of
  `post-edit.js`, `test-hooks.js`, `pusher.md`, `.claude/rules/docs.md` or the `handoff` skill,
  and the update resolves no conflict under the "recorded reason to differ" exception.
- Prediction, check by 2027-01-16: of Hatchery's commits touching `docs/HANDOFF.md` since
  2026-10-17, those touching nothing else are wrap-ups (subject starting `Handoff:` or `WIP:`)
  and fewer than half; the baseline was 10 of 24.
- Prediction, check by 2027-04-16: Hatchery has not switched shapes, and every decanted project
  that did has a project ADR citing the trigger evidence. Outcome 2026-10-16: moot in part, ADR 0015; a shape chosen at decant needs no project ADR, the clause holds for projects that switch.
