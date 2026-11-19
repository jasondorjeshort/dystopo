---
name: decant
description: Turn a fresh clone of the Hatchery base repository into a new project. Use when starting a new project from Hatchery, when asked to decant, or when a repository still has the Hatchery README and no project of its own.
---

# Decant

Hatchery is the base; a project is decanted from it. The result keeps the working agreement, the
agents, the skills, the hooks and Hatchery's records in `docs/hatchery/` (the practice it
inherits, never edited; its own ADRs start at 0001, ADR 0016), and starts its own design, handoff,
ideas, findings and reference. Everything below runs in the new project's clone.

## Steps

1. Prerequisites. Run `node --version` and `gh --version`. Without Node the status line and the
   hooks fail to start (git-guard then blocks every Bash call, and the pre-push hook every push);
   without `gh` the pusher cannot authenticate. Stop and tell the user if either is missing; do
   not continue with hooks disabled.
2. Remotes. `git remote -v`. If `origin` still points at Hatchery, rename it:
   `git remote rename origin hatchery`, then `git remote add origin <new project remote>`. If
   `origin` is already the project, add `git remote add hatchery https://github.com/jasondorjeshort/hatchery.git`.
   The second remote is always named `hatchery`; the `update-from-hatchery` skill relies on it.
   After the rename run `git branch --unset-upstream` (step 11 checks it): the rename carries the branch's upstream
   to `hatchery`, and a bare `git push` would then go to Hatchery. The first push sets `origin`
   (the pusher's `git push -u origin <checked-sha>:refs/heads/<branch>`, then `git branch --set-upstream-to=origin/<branch>`, since `-u` sets none from a sha). Then `git fetch --prune hatchery`, before any
   other step (ADR 0018).
3. `README.md`: replace with the project's README, keeping the License section. Keep nothing about
   decanting; that lives here.
4. `CLAUDE.md`: write it anew with the Write tool, exactly this shape (ADR 0016; at most 40 lines;
   the rule text lives in the base-owned `.claude/agreement/` files, which are never copied in).
   `<NNNN>` is the provenance ADR of step 9; `Phase` is one or two lines; the branch is this
   project's. Nothing else goes in: a rule the project adds goes under `## Project rules`, a rule
   that contradicts a section is a replacement file later (the departure table of step 9).
   Decant fills `Test command:`, or leaves it for the first coder brief when there is no command yet.

   ```
   # <Project> working agreement

   Decanted from Hatchery; provenance and departures: `docs/design/decisions/<NNNN>-decanted-from-hatchery.md`.
   Where work stands right now (coordinator state; subagents follow their brief, not this):

   @docs/HANDOFF.md

   ## Phase

   <one or two lines>

   Branch: <name>

   @.claude/agreement/this-agreement.md
   @.claude/agreement/where-things-live.md
   @.claude/agreement/delegation.md
   @.claude/agreement/debug-interface.md
   @.claude/agreement/major-decisions.md
   @.claude/agreement/usage-gating.md
   @.claude/agreement/model-routing.md
   @.claude/agreement/one-review.md
   @.claude/agreement/git.md
   @.claude/agreement/licensing.md

   ## Project rules

   Test command: <the project's test command>
   ```
5. `.claude/hooks/policy.json`: the project's choices for the hooks, never edits to the scripts
   (ADR 0006): usage thresholds (`usage.hold`, `usage.deny`, per meter under `usage.fiveHour` and
   `usage.sevenDay`), who commits and pushes (`git.commit.subagents`, `git.push.subagents`,
   `git.push.mainSession`), and cap exemptions (`edit.capExempt`). Thresholds and overage are the
   owner's money: ask, do not guess. `usage.pool` is `null` unless this machine has the account
   switcher (ADR 0011). `.claude/hooks/policy.examples/` has worked ones; copy the closest and
   edit. `node .claude/hooks/policy.js` must print the effective policy, not an error.
   Identity (ADR 0018, ADR 0020): ask, in these words, "Which name and email go on this project's
   commits?" The answer is the owner's real git identity unless the owner names another (a
   `users.noreply.github.com` address is an option; list both during a transition). An agent never
   invents one: no placeholder, `.invalid` address or role, and none recorded as the owner's choice. Write
   `privacy.identities` (`[{ "name", "email" }]`) and `git.hooksPath` `.claude/hooks/git`, then
   set repo-local `git config user.name` and `user.email` from the first entry (never the global
   identity) and run `git config core.hooksPath .claude/hooks/git`, in that order.
6. The handoff. Ask the owner one question, in these words: "Will more than one session work this
   branch at the same time?" (ADR 0015: the shape follows the writer count; the answer goes in the
   commit body of step 12). Before the architect's brief for `docs/design/00-vision.md`, either
   branch, interview the owner with AskUserQuestion on implementation, UX, edge cases and
   tradeoffs; the brief carries the answers, and since a later session writes that brief, they go on
   the handoff's Next line for `00-vision.md`. Then one of:
   - No: `docs/HANDOFF.md`, rewritten with the `handoff` skill; Now is "decanting from Hatchery",
     Next is the design sequence starting at `docs/design/00-vision.md`.
   - Yes: the split shape (`handoff` skill, `split.md`). Write `docs/HANDOFF.md` as the map, with
     today's date, exactly:

     ```
     # Handoff

     Coordinator state. Subagents follow their brief, not this file.
     Updated: YYYY-MM-DD by main session
     Split shape (handoff skill, split.md): this file is the map; each @ line below imports one stream.
     Waiting on user: the Question: blocks in docs/ideas/ and each stream's Waiting line.
     Open findings: node .claude/hooks/queue-list.js findings --status open

     @handoff/design.md
     ```

     and one stream file `docs/handoff/<stream>.md`, named for the first workstream (`design.md`
     in a decant; rename both the file and the map's line if the project starts elsewhere):

     ```
     # Design

     Updated: YYYY-MM-DD by main session

     ## Now
     - Decanting from Hatchery.

     ## Next
     1. `docs/design/00-vision.md` with the architect, then the sequence in `docs/design/README.md`. [no-check: written next]
     ```

     No `README.md` in the stream directory: the map is its index, and `@handoff/`, never
     `@docs/handoff/` (an import resolves from the importing file's directory). Add a `handoff/` row
     to `docs/README.md`: "one file per open workstream; indexed by the map's `@handoff/` lines".
7. `docs/ideas/`, `docs/operator/` and `docs/findings/` (Hatchery's findings are about Hatchery):
   delete every file except `README.md`; no table to reset (ADR 0017).
8. `docs/reference/`: keep `README.md` with its table reset to the header row; delete every other
   reference file and its row (Hatchery's entries ship in `docs/hatchery/reference/`).
9. `docs/design/`: keep `README.md` and `proposals/README.md`; delete every proposal and reset the
   proposals index table to its header; delete every file under `decisions/` except `README.md`
   (`docs/hatchery/` stays as shipped). Then write
   `docs/design/decisions/0001-decanted-from-hatchery.md` (`adr` skill, step 1) from
   `.claude/skills/adopt-hatchery/template-adr.md`, title `Decanted from`: the base line
   `Hatchery base: ` plus `git rev-parse hatchery/main`, the licence decision, the departure table
   with its header row and no rows. Add it to 'Decisions so far' in `docs/design/README.md`. A
   different licence: the next ADR supersedes `docs/hatchery/decisions/0001-licensing.md` by
   citation and replaces `LICENSE`.
10. `.claude/settings.json`: keep as is. `.claude/rules/`: keep `docs.md`; add language rules
    later per `.claude/rules/README.md`.
11. Verify: `git status --short` shows only the intended edits; `node .claude/hooks/policy.js
    --claude-check` prints no `FAIL` (the 40 and 190 caps, every section imported once);
    stage the handoff first, since the commit step takes it anyway (`git add docs/HANDOFF.md` and,
    where it exists, `git add docs/handoff/`), so an untracked stream file is checked too; then the
    three command lines of pusher check 7 (`.claude/agents/pusher.md`) print no `FAIL` (they
    hold in both handoff shapes); `node .claude/hooks/policy.js` exits 0; `git config --get
    branch.<branch>.remote` prints nothing or `origin`, never `hatchery`; `git config
    core.hooksPath` prints `.claude/hooks/git`.
12. Commit via the runner (`commit` skill): subject `Decant <project> from Hatchery`, body listing
    what was removed and kept, and the handoff answer of step 6 with the shape it gave. Do not
    push; the user decides when.
13. Report: remotes, files changed, policy choices, handoff shape, first design step.

## Rules

- Do not edit agent files, skills, or hooks during decanting. If something in them is wrong for
  the project, that is a change to make in Hatchery and pull back with `update-from-hatchery`.
  `policy.json` is the project's file and is the one thing under `.claude/hooks/` this skill edits.
- Use the Write tool for files with apostrophes or backticks.
