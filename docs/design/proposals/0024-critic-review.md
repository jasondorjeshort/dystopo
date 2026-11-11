# 0024, critic review: a chained base

Date: 2026-11-10
Reviewer: the critic (Fable). Read in order: `CLAUDE.md` and the ten agreement sections, the
proposal, ADRs 0013, 0016 (with its map and procedure), 0019, the parts of 0018 and 0021 on
identities, the skills `update-from-hatchery`, `adopt-hatchery`, `decant`, `carry-back`, `triage`,
the hooks `ownership.js`, `policy.js` (`claudeCheck`), `privacy-scan.js`, `test-hooks.js` (O1, A1,
CB1), `pusher.md`, the four ideas the proposal cites, a read-only clone of the direct adopter, the
owner's notes workspace and the sibling checkouts beside Hatchery (read only). Experiments ran in
a scratch directory outside this checkout, git 2.56.0.windows.4. Not re-argued, as briefed: the
owner's decisions of 2026-11-10, and "no shared ER layer at all".

Verdict in one line: B is the right option of the four as they are written, and its mechanism
holds by experiment (claims 1, 3, 4, 6, 7, 9, 11); it is not ready for an ADR, because its exit
line is wrong by experiment, claim 8 fails, the intermediate's own behaviour is unspecified in the
surfaces it promises, and its stated reason for the chain is one its own text expects to remove.
No blocking objection; nine serious, nine minor.

## 1. Claims verified

| # | Finding | Source | Status |
|---|---|---|---|
| 1 | Three repositories with unrelated roots (no merge-base, checked). `git diff -M --binary A B -- <paths> \| git apply --3way` in the third: additions staged, a modification applied, a local departure merged three-way with the base's edit, a deletion staged, a binary file byte-identical, a file changed on both sides left `UU` with markers, exit 1. Then the real shape: a clone of the direct adopter, an intermediate simulated as a clone of Hatchery at `93df7d2` plus the layer of item 1, diff from `f78e0dc` to it over the effective set with the adopter's excludes: nine entries applied, one conflict, `.gitignore`, as item 6 (c) predicts. Two additions to the claim: a `D LICENSE` entry against a tree without the file fails the whole patch (`does not exist in index`, nothing applied), so the re-base depends on the adopter's `update.exclude`; an addition onto an existing file is `AA` with markers. | experiment | holds |
| 2 | "Claude Code loads `CLAUDE.md` and `CLAUDE.local.md` from your current working directory and every directory above it", ordered "from the filesystem root down". Settings: "reads the shared `.claude/settings.json` from the session's primary working directory". Skills load from "every parent directory up to the repository root"; subagents by "walking up from the current working directory" to "the repository root". A module is its own repository, so nothing but `CLAUDE.md` comes from the workspace. `claudeMdExcludes` exists: "Patterns are matched against absolute file paths using glob syntax", "at any settings layer: user, project, local, or managed policy", the example placed in `.claude/settings.local.json`. Three qualifications in objection 10: the ancestor's imports are external imports and need a one-time approval; on Windows the local settings file is read from the starting directory, not the repository root; the sentinel procedure was not run in this review. | code.claude.com/docs/en/memory, /settings, /skills, /sub-agents | holds, qualified |
| 3 | `pathspecs` includes `.claude` whole, less `baseExcluded` and `.claude/project`, so a layer file under `.claude` is in the diff of two intermediate commits (seen in claim 1's run). `report` lists under `collisions` every added or renamed path whose blob at `HEAD` differs. The `prefix` field plays no part in either; it only names the files the 15-line case counts. | `ownership.js`; experiment | holds |
| 4 | `claudeCheck`: `sections = mdIn('.claude/agreement')`, each satisfied by an import of `.claude/agreement/<s>.md` or `.claude/project/<s>.md`, else `FAIL CLAUDE.md: section <s> neither imported nor replaced`. | `policy.js` | holds |
| 5 | Agreement: 6+9+22+7+19+16+8+11+17+8 = 123. Adopter: 36 + 31 + 9 + 107 = 183. | `wc -l` | holds |
| 6 | `if (identities.length === 0) notices.push('privacy.identities is empty: the identity class is off')`; the range is bounded by the target and by `ls-remote` of `BASE_REMOTE`. | `privacy-scan.js` | holds |
| 7 | Step 2: "Read `git show <sha>:.claude/skills/update-from-hatchery/SKILL.md` and follow that copy if it differs." | the skill | holds |
| 8 | The list omits: the `carry-back` skill (`git fetch hatchery`, `--remotes=hatchery`, a path check that parses `ownership.json` alone); `adopt-hatchery` step 3 (a literal list of paths to copy, not the map), step 7 (the skeleton from `hatchery/main`) and step 8; `update-from-hatchery` step 4 (`git grep ... -- .claude docs/hatchery LICENSE LICENSES`); `template-adr.md`; the helper's own messages; `pusher.md` check 3 (an upstream on any remote but `origin` stops the push, and the PR route pushes to a fork remote); `docs/hatchery/*` in each tree's own `edit.anchorExempt`, which ADR 0016 says every policy file sets. | `grep -rn -i hatchery .claude` | fails: incomplete |
| 9 | git-worktree(1): "If both the main worktree and linked worktrees have been moved or copied manually, then running `repair` in the main worktree and specifying the new `<path>` of each linked worktree will reestablish all connections in both directions." Run on Windows: after moving both, `git worktree list` marks the worktree `prunable` and it fails with `gitfile does not point to a valid repository`; `repair <path>` fixes both directions. `repair` with no path exits 0, prints nothing and repairs nothing. | git docs; experiment | holds, one trap |
| 10 | "By default, new organizations are configured to disallow the forking of private repositories." The Read role may "Fork the person or team's assigned repositories" and "Send pull requests from forks". GitHub Free for organizations: "unlimited collaborators ... unlimited private repositories with a limited feature set"; no page read limits the Read role, outside collaborators or the forking policy by plan ("Unless you are on a free plan, adding an outside collaborator to a private repository will use one of your paid licenses"). `git fetch origin pull/ID/head:BRANCH_NAME`; "The remote `refs/pull/` namespace is read-only". Not in the proposal: "If you remove a person's access to a private repository, any of their forks of that private repository are deleted"; "Owners of an upstream repository can read all forks in the repository network"; protected branches in private repositories are a paid-plan feature. That the ref outlives a deleted fork rests on ADR 0019's sources, not re-read. | docs.github.com (forking policy, repository roles, fork permissions, plans, outside collaborators, checking out pull requests locally) | holds, with additions |
| 11 | The queue's one test is `grep -rlE "^Hatchery base: [0-9a-f]{7,40}" ../*/docs/design/decisions/`; the update settles a row by `git log <FROM>..<sha> --grep`. | `triage`, the update skill | holds |
| 12 | Section 4 gives the right to convey verbatim copies provided one gives "all recipients a copy of this License along with the Program"; section 0: to convey is "any kind of propagation that enables other parties to make or receive copies". Section 2 excepts only work done "exclusively on your behalf". So the duty is not special to the public option (objection 15). | `LICENSE` | holds, wider than stated |

The siblings, read only: four trees beside Hatchery and five module checkouts inside the owner's
notes workspace hold the line; none has `.claude/bases/`; none with `.claude/agents` lacks the line
but Hatchery. "A tree with no descriptor reads as today" therefore holds for all of them. Four of
those checkouts have the line and no `hatchery` remote, so they run O1, A1 and CB1 as if they were
Hatchery today: the proposal's replacement of the remote test fixes a real fault. The Constraints
say seven trees hold the line; there are eight repositories (one module is missing from the list).

## 2. The case against B, and for the best alternative

B is chosen because "the layer's rules and fixes keep arriving" and D loses that; the rules are
called safety rules. Three of the proposal's own sentences undo this reason. Item 8 makes a module
session load nothing of the workspace, so the rules bind workspace sessions only, and the person who
"mostly works inside a single module" never sees them. The closing paragraph says that in a
workspace session "the rule that protects the live game is prose" and recommends building the
2026-10-21 guard idea "before the layer is relied on". And the third reversal line says that once
"the guard reads the target repository's policy and the modules carry the ER rules themselves", the
answer is D; item 8 already has the live engine recording its own rule. So the recommended next
step is half of the recommendation's stated reversal, and the other half is asserted as present.

What is left for the layer then is a list, a clone script, an ignore pattern, a runbook and a
licensing sentence. For that content the strongest alternative is the one the proposal gives one
sentence, D's variant, here called the checkout variant: ER-Workspace is cloned read-only inside
each workspace like any module (the clone script already fetches every checkout), the list, the
script, the runbook and the records are read in place, and the every-turn rules arrive by one
import line. The proposal dismisses it as "outside the workspace's repository and checks". That is
half wrong: the file is inside the workspace root, so `claudeCheck` reads it, counts it toward the
190 and fails when it is absent, and Claude Code loads it without the external-import dialog
(memory page: an import is external only when it "resolves outside your working directory"). What
stands against it is ADR 0016 item 3's "never to a free `@` import", a Hatchery sentence this loop
can amend for one declared file at far less cost than the chain. Under that variant Hatchery gains
no descriptor, no `--whoami`, no layered map, no renamed skills, no stubs and no new promised rows;
the direct adopter does not migrate at all (it already has this shape, less the checkout); nobody
waits on the intermediate; ADR 0019 is not departed from.

What the checkout variant loses is what B should be argued on instead of rule arrival: one curated
Hatchery level for a team whose agents the owner cannot debug (a Hatchery change is run once by the
owner before it reaches anyone); one place where a departure from Hatchery is made for everyone (one
instance today, the licensing section, which the adopter already carries as an ordinary
replacement); and a private channel by which another person's fix to a Hatchery file reaches the
owner (ADR 0019 cannot fetch from a private workspace the owner cannot read; its bundle form is
deferred to "the first such sibling"). These are real, and with the owner's stated flow they carry
B. The proposal does not state them as the reason, and its reversal lines do not test them.

## 3. Refusals and their lifts

| Mechanism | Who lifts, from where, in a running session | Does the text say so |
|---|---|---|
| Helper stops on two base-line names | the person, by editing one of the two project-owned ADRs, in session | "naming both files"; the lift wording is not given (today's says "keep one") |
| `--claude-check` FAIL, `er-modules` neither imported nor replaced | the person: the import line, or a replacement file | names the section, no lift text (as today) |
| The 190 | the person, `edit.capExempt`; it lifts the 40 and the 190 together | yes |
| The layer's 15-line case in the intermediate | not stated; ADR 0013 requires `lift:` in the line | no |
| `er-setup` refuses while `privacy.identities` is empty | the person, by answering; but where the module committers' identities come from is open question 1 | not specified |
| The scan refusing a module push from a workspace session | the person's main session, after asking, by listing the identity; same open source | existing text; the source is not named |
| Re-base precondition (a), the intermediate's Hatchery base older than the workspace's | only the intermediate's owner, by updating and pushing; not from the person's session | no check and no message are specified |
| Carry-back send and land checks | ADR 0019's texts, with the rebuild command | yes; a landing refusal reaches the sender only as a PR comment, which itself must pass `--text` |
| "Never the merge button" | nothing refuses it; prose in a skill, on a page that shows the owner a merge button; no recovery is stated, and whether CB1 runs in an intermediate to detect it is not said | no mechanism |
| Organization membership removed | the organization's owner; else the exit of objection 3 | git's authentication error only |
| `clone-repos.sh` skips an unreachable repository | not a refusal; it must print each skip | not specified |

## 4. Pre-mortem

Ten years on, the regret is that Hatchery promised a chain to third parties for a reason that did
not last. The guard learned to read the policy of the repository a command runs in, the modules
carried their own rules, and the layer shrank to a list and a script; but the descriptor fields,
the `--whoami` lines, the generalised line and two permanent stub skills were promised surfaces by
then, held in private workspaces the owner could neither read nor migrate, so every later
incompatible change to any Hatchery surface needed a migration that strangers' agents ran
unattended. Meanwhile the owner was the relay for everything: each Hatchery change was updated
twice before a person had it, and each fix a person made to a Hatchery file was sent twice, landed
twice and reviewed twice by the same owner. The line that should have caught it is the proposal's
own: "The guard reads the target repository's policy and the modules carry the ER rules themselves,
so the workspace's shared text shrinks to a list and a clone script: D", read beside "Building the
2026-10-21 idea ... is recommended before the layer is relied on".

## 5. Objections

1. Serious. The stated reason for B is one the proposal expects to remove, and the best
   alternative did not get equal depth. Evidence: section 2. Resolves it: argue B on curation, the
   one-place departure and the private channel, with a reversal line for each (for instance: no
   departure made in the intermediate on behalf of the workspaces in a year, and no carry-back by
   another person); give the checkout variant its own option with exit cost and moving parts; and
   say which of the third reversal line and the guard recommendation wins.
2. Serious. The layer is rolled out to other people's agents while the only protection of the live
   game in a workspace session is prose. Evidence: the adopter's policy lets its pusher push
   `master` and `main` in every module checkout (its provenance ADR says so); the shipped example
   carries "module branch names"; the guard matches the branch name, never the repository
   (2026-10-21 idea); protected branches in private repositories need a paid plan, and this review
   could not see whether the modules have them. Resolves it: make the guard change, or a shipped
   example that denies the shipping branch names to subagents with the lift named in the refusal,
   or a recorded check that the module repositories protect those branches server-side, a gate
   before the second person is set up, in the First step list, not a recommendation.
3. Serious. B's exit cost line is wrong by experiment. Evidence: in the simulated workspace, after
   the re-base, the reverse diff to a Hatchery commit over Hatchery's own set deleted
   `.claude/agreement/er-modules.md`, the `er-setup` skill, the descriptors and the policy example
   and reverted the licensing edit, all applied cleanly with exit 0; only the root paths and
   `docs/er-workspace` stayed. "The layer's files become each workspace's own" is true of the root
   half only, and the half that leaves silently is the rules. Item 9 gives the same procedure to a
   person removed from the organization, who runs it alone. Resolves it: an exit procedure that
   keeps the layer's `.claude` paths (an `--exclude` per path the reverse diff would delete, the
   licensing edit kept as a replacement file with its row), tested as a harness case, and the exit
   line rewritten from that test.
4. Serious. The surfaces are promised before the intermediate's own behaviour is defined, against
   the proposal's sixth reversal line. Evidence: `--whoami` prints one "decisions directory", and
   the intermediate has two (`docs/er-workspace/decisions/` shipped, `docs/design/decisions/` not,
   "one series across both"), so `adr` cannot tell where a record goes and the scans of `triage`
   and `handoff` read half the predictions; a tree's "kind" has three values (root base,
   intermediate, project) and the text does not say which of O1, A1, CB1 and the queue run in the
   middle one; item 7 promises "the descriptor (path and fields)" and "the `--whoami` lines" while
   the last reversal line says to check the fields against a second shape "before promising them".
   Resolves it: a table of the three kinds against each reader (`adr`, `triage`, `handoff`, O1, A1,
   CB1, the queue, `decant`, `carry-back`); the ADR 0013 rows recorded as provisional until the
   first workspace has re-based and one update and one carry-back have run through the chain.
5. Serious. Claim 8 fails, and the adoption path is under-specified as a result. Evidence: claim
   8's row. `adopt-hatchery` step 3 copies a literal list that contains neither `.claude/bases/`
   nor a root path, so "`adopt-hatchery` with the upstream read from the top descriptor" produces a
   workspace with an `ER-Workspace base:` line and no descriptor, which the item 1 rule reads as
   Hatchery itself. Adoption also copies with `git checkout <sha> -- <path>`, which replaces an
   existing file silently (experiment: exit 0, the file overwritten), where the update reports
   `AA`; the owner's notes workspace, named as the second adopter, has its own `clone-repos.sh`
   holding its list. Resolves it: the helper prints the adoption list from the effective map at
   `<sha>`; `adopt-base` stops on any listed path that exists at `HEAD` with a different blob and
   names it; the Constraints list completed, the migration of item 6 (c) gaining the
   `edit.anchorExempt` entry for `docs/er-workspace/*`.
6. Serious. The 190 is spent twice. Evidence: ADR 0016 sizes it as 40 for `CLAUDE.md` plus 150 for
   the agreement (case A1), which leaves nothing for a layer; "the caps do not move" is true of the
   numbers and false of the headroom. The front-man workspace lands at 187 by the proposal's own
   count, Hatchery may still add 27 lines under A1, and the first four of them fail a check in a
   workspace two hops away, where the named lift, `edit.capExempt`, removes both caps for good.
   The intermediate's own `CLAUDE.md` is short, so its checks pass and nobody upstream sees it.
   Resolves it: the intermediate's harness case counts the agreement as shipped, Hatchery's plus
   the layer's, against the 150, so the conflict lands on the owner at the intermediate's update;
   and the ADR says the layer's lines come out of Hatchery's headroom.
7. Serious. ADR 0019's single-owner assumptions are inherited where they no longer hold. Evidence:
   the queue finds a sibling by reading `../*/` on one machine (from Hatchery it sees four trees
   today; the module checkouts inside a workspace are already out of its reach), and another
   person's workspace is on another machine, so "the `triage` queue runs in any base" lists nothing
   of theirs; "a rule change goes by idea file to the upstream, re-filed upward by its coordinator"
   names no transport for a person who holds only the Read role (an idea file is project-owned, so
   the path check refuses it in a carry-back branch); the landing line names the sender's private
   repository in a commit every member reads. Resolves it: the open pull requests are the queue in
   an intermediate (`gh pr list`, with the local read kept); one named transport for a request
   without a diff (an issue there, or a PR whose only entry is a new idea file, which Land files
   and closes), stated in the refusal texts; the landing line's `<repo>` form decided.
8. Serious. Hidden coupling: every Hatchery promise becomes a promise to people the owner cannot
   migrate. Evidence: ADR 0013 has an incompatible change say "how each sibling repository
   migrates", and each migration so far was run by the owner in the owner's checkouts or side
   clones (the propagate-out idea); a private workspace two hops down admits neither. A bad update
   there also has no stated way back: the guard fails closed on a broken hook, and the lift is the
   person reverting the one update commit from a terminal outside the session, which no text says.
   Resolves it: a Consequence that says so; the rule that a migration is executable by the update
   skill alone from text read at `<sha>` (ADR 0013's deferred migration note, whose trigger this
   is); one line in the update report naming the revert.
9. Serious. Four mechanisms refuse without a documented lift or message. Evidence: section 3: the
   15-line case, `er-setup` on an empty identities list, the re-base precondition, the merge
   button. On identities the source asked for in open question 1 is already in the tree: the
   module checkouts carry their own lists in their policy files (0, 8, 0, 13 and 8 entries, so
   two of them have the class off). Resolves it: each refusal's text and
   lift written in the proposal; `er-setup` proposes the union of the cloned modules' lists for the
   person to confirm; CB1 runs in an intermediate, so a button merge is at least detected.
10. Minor. Item 8's fallback does not load where it is needed. Evidence: from a module session the
    workspace's agreement imports resolve outside the working directory, and "The first time Claude
    Code encounters external imports in a project, it shows an approval dialog"; when that is
    declined or not yet given, the workspace's `CLAUDE.md` body still loads (its `Branch:`, `Test
    command:` and phase lines) and `er-modules.md`, which carries the yielding sentence, does not.
    On Windows the settings page keeps `.claude/settings.local.json` with the starting directory,
    so a session started in a linked worktree of a module, which sits inside the workspace, has no
    exclude. An absolute pattern holds the person's home path; the docs' own form
    `**/<workspace folder>/CLAUDE.md` avoids that and the drive-letter question. The opposite
    direction is not treated: in a workspace session a module's `CLAUDE.md` with its imports, and
    its nested skills of the same names, load once a file there is read. Resolves it: the yielding
    sentence in the workspace's `CLAUDE.md` template body; the pattern form fixed; the sentinel
    procedure run from a module root and from a module worktree on Windows before the ADR.
11. Minor. A person's fix to a Hatchery file costs two sends, two lands and two updates, read twice
    by the same owner. Evidence: item 5. The reason given against going direct, that the landing
    is invisible inside an `Update from Hatchery` commit, is removed by having that commit's body
    carry the `Carry-back:` lines of its incoming range. Resolves it: that line in the update
    commit, and the intermediate's land of a Hatchery path reduced to forwarding the branch.
12. Minor. The citation convention is a second decision riding in this one. Evidence: item 3
    reverses ADR 0016 item 5's meaning of a bare `ADR NNNN` in base-owned files; the pass touches
    most shipped files, so a departure row on any of them conflicts once in every tree that has
    one, and rewrapped agreement lines move the count objection 6 depends on. It is not needed for the chain, and it
    contradicts "no behaviour change for any sibling" if it rides the first step. Resolves it: its
    own change, after the chain's first step.
13. Minor. The blanket ignore pattern fails silently. Evidence: with every top-level directory
    ignored, a directory a person creates for their own notes never shows in `git status`; an
    explicit list fails loudly instead (git warns on adding an embedded repository). The file is
    `mixed`, so each person hand-merges it at every layer change. Resolves it: `er-setup` and the
    handoff check print top-level directories that are ignored and are not a listed checkout.
14. Minor. Two lines the brief asks about. The first reversal line, "lags by more than a few days
    as a rule", has no number and nothing records the lag, so it cannot fire; the update report
    could print the dates of the two Hatchery bases and the line name a threshold. B's moving
    parts omit what the carry-back route runs: an organization and its forking policy, a fork
    remote per person, `gh` on the owner's side, `er-setup`, a local settings entry per module.
15. Minor. Claim 12 reaches the organization option too. Evidence: claim 12's row; members are
    recipients unless they work exclusively on the owner's behalf. The owner as copyright holder is
    not bound, but the files then sit in each workspace with a GPL header and no text. A workspace
    session also writes module code under a licensing section that says "none". Resolves it:
    ER-Workspace keeps `LICENSES/` (the text) while holding no `LICENSE` of its own, and the edited
    section says it covers the workspace's own files and that a module's files follow the module.
16. Minor. Item 9 leaves out what the organization option does to a fork. Evidence: claim 10's
    additions: a removed person's fork is deleted, the upstream's owners can read every fork, and
    the fallback (the branch on the person's own repository) puts the intermediate's whole history
    on that repository, which item 6 avoids clones in order to prevent. Resolves it: three
    sentences in item 9.
17. Minor. `git worktree repair` without paths is a silent no-op. Evidence: claim 9. Resolves it:
    the runbook passes every moved worktree path and then requires `git worktree list` to show no
    `prunable` entry, before anything else runs in the moved checkout.
18. Minor. The Constraints count is off by one. Evidence: section 1, eight repositories hold the
    line. Resolves it: the list corrected, since ADR 0013 sizes a migration per sibling from it.

## 6. Recommendation

Take B, but not yet as an ADR, and for different reasons than the proposal gives. The mechanism is
sound: the patch between unrelated histories, the layered set under `.claude`, the section check,
the stubs and the worktree repair all hold on this machine. What does not hold is the argument (the
rules that justify the chain are prose in the sessions they reach, and the proposal's own next step
retires them), one exit line, one claim, and the definition of the middle tree. So: revise for
objections 1 to 9; build the helper as the first step with its ADR 0013 rows provisional; re-base
the direct adopter and run one update and one carry-back through the chain before anything is
promised; gate the second person's setup on objection 2. If the owner, shown the checkout variant
at equal depth, would rather give up curation than be the relay, that variant is the better choice
and costs Hatchery one amended sentence. Confidence: about 65% that B so revised is better than the
checkout variant for this owner; about 90% that objections 3, 5 and 17 stand as written, since each
was reproduced; about 80% for the other serious ones, which rest on reading the sources named.
