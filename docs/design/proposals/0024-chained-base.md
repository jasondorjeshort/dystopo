# 0024. A chained base: a project's base may itself have a base

Date: 2026-11-10
Status: draft, parked (the owner, 2026-11-10: ER-Workspace was built as a plain shared repository with Hatchery optional per person, close to option E; this stays the design if the relay through an intermediate base is wanted later)

## Question

Hatchery assumes exactly one base. The owner wants a second level for the Exiled Reign (ER) game: Hatchery,
then `ER-Workspace` (a repository the owner alone pushes, carrying an ER layer on top of the practice), then
one private workspace per person: a top-level folder holding that person's handoff, notes and work in
progress, with the ER module repositories cloned inside it as ignored subfolders. Each person sets up from
ER-Workspace and updates only from it; Hatchery's changes reach them through it. Decided here: how a tree
names its base, what is base-owned at two levels, where an intermediate base's rules and records live, the
update and carry-back routes, setup and the migration of an existing direct adopter, and what the skills are
called. Why now: one workspace was adopted straight from Hatchery on 2026-11-10 (at `f78e0dc`) and two more
are about to be, each re-deriving the ER rules by hand. In ten years this should read: a base is a tree that
ships a declared set of paths to the trees one hop below it, any number of hops deep, with ADR 0016's one
patch at every hop.

## Constraints that matter here

- ADR 0016: one three-way patch per update, the map read at the base commit and never the project's copy,
  nothing the project wrote is touched; `CLAUDE.md` at most 40 lines loading at most 190; overflow never
  goes to a free `@` import.
- ADRs 0012 and 0013: the base line `^Hatchery base: [0-9a-f]{7,40}`, the map's four lists, the agreement
  file names and the record paths are promised. Additions are free; an incompatible change is this loop
  with a migration per sibling. Eight repositories hold today's line: broodforge, inertia, ERIS,
  ER-Accounts, droplet-management, regenesis, dystopo and the front-man workspace.
- One base is hard-coded in these places (read 2026-11-10; completed after the critic's claim 8): the
  remote name `hatchery` and its URL in `update-from-hatchery` step 2, `decant` step 2 and `adopt-hatchery`
  step 2; `adopt-hatchery` step 3 (a literal list of paths to copy, not the map), step 7 (the skeleton from
  `hatchery/main`) and step 8; `update-from-hatchery` step 4 (`git grep ... -- .claude docs/hatchery LICENSE
  LICENSES`); the `carry-back` skill (`git fetch hatchery`, `--remotes=hatchery`, a path check that parses
  `ownership.json` alone); `template-adr.md`; the line's regex in `ownership.js` (`findFrom`), the helper's
  own messages, and the two-part "is this Hatchery" test of `adr`, `triage` and `handoff`; `BASE_REMOTE =
  'hatchery'` in `privacy-scan.js`; the skip of harness cases O1, A1 and CB1, keyed on a remote named
  `hatchery` (four checkouts with the line and no such remote run them as if they were Hatchery today);
  `triage`'s carry-back queue, which reads `../*/docs/design/decisions/` for the line; `pusher.md` check 3
  (an upstream on any remote but `origin` stops the push); the literal `docs/hatchery` in `post-edit.js`,
  the docs rule, the `edit.anchorExempt` default and each tree's own `edit.anchorExempt`.
- A session runs the hooks of the directory it starts in (`policy.js` reads the `policy.json` beside it).
  A workspace session's guard therefore applies the workspace's policy to git in every module checkout,
  and the modules' own policies, hooks and agents do not load (ideas
  `2026-10-21-git-guard-uses-the-policy-of-the-repo-it-runs-in.md` and
  `2026-10-30-git-guard-scopes-commits-by-repository-location.md`). The modules are Hatchery siblings.
- How a person works stays per person: one long-lived front-man coordinator (a 31-line delegation
  replacement), one session per task, or mostly inside one module. `.claude/project/` and `policy.json`
  are the person's.
- The shared layer carries no person's identity (ADR 0018): `privacy.identities` is filled per person. An
  empty list switches the identity class off today (`privacy-scan.js` says so on stderr; ADR 0021's
  own-identity rule is not built).
- Budget: the agreement is 123 lines (cap 150, case A1); the front-man workspace loads 183 of 190. ADR
  0016 sized the 190 as 40 for `CLAUDE.md` plus 150 for the agreement, so a layer has no room of its own.

Decided by the owner, 2026-11-10, and taken as inputs:

- Only the owner pushes ER-Workspace's `main`. It will be public, or in a GitHub organization where
  everyone else holds the Read role (item 9).
- A change goes back into ER-Workspace by pull request, landed by the owner (item 5).
- No licence for ER-Workspace or the workspaces; the licence of ER's code is open for all of ER (item 3).
- ER-Workspace itself names the module repositories (`<owner>/<name>`) with a short overview of each, so a
  workspace agent knows what to clone and why; `repos.local` selects a subset. That list is the main
  reason the repository is not public (items 6 and 9).
- The names `update-from-base`, `adopt-base`, `.claude/bases/`, `docs/er-workspace/`; the 15-line budget.
- What a module session loads from the workspace: no strong view; the architect picks (item 8).

## Options

### A. Alias: the intermediate is "hatchery" to the trees below it

No code. In a person's workspace the remote `hatchery` points at ER-Workspace and `Hatchery base:` holds an
ER-Workspace sha. ER-Workspace edits the base-owned `ownership.json` to add its paths and the URL lines of
three skills: four permanent departure rows there. ER rules are copied into each `## Project rules` by the
runbook, or into an edited agreement file; ER's records do not ship. Updates have one upstream; carry-back's
`git fetch hatchery` reaches ER-Workspace, which lands with the Land steps as they are. Risks: one word with
two meanings in every skill and message; a fresh clone on a second machine has no remote, so the skill adds
the real Hatchery unless ER-Workspace's departed copy is the one read; Hatchery's own `triage` reads a
workspace beside it as its sibling and classifies ER shas against its `main`; ER-Workspace's own queue is
skipped (it has a `hatchery` remote); every Hatchery change to the four rowed files is a hand merge there
for good; a third level cannot be told from the second.
Exit cost: per workspace one remote renamed and one line rewritten; ER-Workspace drops four rows.
Moving parts: none new; four standing departure rows and a convention nobody can check.

### B. Named bases, one upstream per tree

Each base declares itself in one small file it ships (name, URL, branch, parent, its records directory, the
paths it adds to the base-owned set). A tree's base line names the base one hop up (`ER-Workspace base:
<sha>`); the helper reads the chain and prints who the tree is and where it updates from. The patch, the
conflict rules and the departure table are ADR 0016 unchanged, applied at each hop; the carry-back branch is
ADR 0019's. Hatchery reaches a workspace inside the intermediate's commits. Detail under Recommendation.
Risks: the workspaces lag Hatchery by the intermediate's last update; the intermediate's owner is the relay
for every update and every fix, and a single point for both; every Hatchery promise becomes a promise to
workspaces the owner cannot read or migrate; more names for a reader to hold.
Exit cost (corrected, objection 3): not a plain reverse re-base, which deletes the layer's files under
`.claude` silently; the exit of item 6, which keeps them by exclude, per workspace. Hatchery keeps a helper
that reads as today where no descriptor exists.
Moving parts: one descriptor per base; `--whoami`, a layered map and two printing flags in `ownership.js`;
two skills renamed with stubs at the old paths; a records subtree per intermediate; harness cases; and for
this chain an organization with its forking policy (or a public repository), a fork remote per person,
`gh` on the owner's side, the `er-setup` skill, a local settings entry per module checkout.

### C. Flat layers: every tree updates from each base directly

A workspace holds two base lines and two remotes. ER-Workspace ships only its own paths, by its own map
file, disjoint from Hatchery's; Hatchery's set arrives straight from Hatchery. Ownership is a partition,
carry-back routes by the path's owner, nothing lags. Against it: ER-Workspace cannot depart from Hatchery on
behalf of the workspaces (a changed skill or agreement text for all of ER becomes one identical departure
per person, made by a runbook and drifting); nobody runs the combination, since each workspace pairs the
layer with a Hatchery commit ER-Workspace never tested; two updates, two lines and two reports per sitting;
it contradicts the owner's stated flow, and "the same layout on every machine" weakens when Hatchery levels
differ per person. It still needs B's descriptor (a second map file) and the generalised line.
Exit cost: to B, drop the Hatchery line and remote in each workspace once ER-Workspace has caught up.
Moving parts: B's descriptor and helper, plus a second line, remote and update per tree and a check that
the two sets stay disjoint.

### D. No chain: ER-Workspace is a template, its layer copied once

ER-Workspace is an ordinary Hatchery project. A person copies its ER files at setup as project-owned files
and updates from Hatchery as today; a change to the layer is carried by hand (the propagate-out idea) or
re-copied. Its real strength: no change to Hatchery. Against it: a change to an ER rule reaches the
workspaces only if someone carries it to each; the repository list and its overviews, which the owner has
placed in ER-Workspace and which grow with every new module, are re-copied by hand too; runbook fixes reach
nobody; departures as in C. The variant that reads the layer in place instead of copying it was one
sentence here and misdescribed (objection 1); it is option E.
Exit cost: to B, each workspace runs the migration of item 6. Moving parts: none in Hatchery; a habit.

### E. The checkout variant: the layer is read in place (added after the critic review, objection 1)

ER-Workspace is cloned read-only inside each workspace like a module (`ER-Workspace/`, ignored, fetched and
fast-forwarded by the clone script it carries). The list, the script, the runbook and the records are read
in place; the every-turn rules arrive by one import line in the workspace's `CLAUDE.md`,
`@ER-Workspace/er-modules.md`. Each workspace is an ordinary Hatchery project, adopted from Hatchery and
updating from it. The import resolves inside the workspace root, so `--claude-check` reads it, counts it
toward the 190 and fails when the checkout is absent, and Claude Code loads it without the external-import
dialog. It needs one amendment: ADR 0016 item 3's "never to a free `@` import" admits one declared file.
Hatchery gains no descriptor, no `--whoami`, no layered map, no renamed skills, no stubs and no promised
rows; the direct adopter does not migrate (it has this shape already, less the checkout); nobody waits on
an intermediate; ADR 0019 is not departed from; the rules still arrive, by pull. It gives up three things:
one curated Hatchery level (each person updates from Hatchery when and as far as they choose, with agents
the owner cannot debug); one place to depart from Hatchery for everyone (each departure is repeated per
person: the licensing section today); and a private channel for another person's fix to a Hatchery file
(0019's fetch by URL needs the owner to read that workspace; its bundle form is deferred). What the layer
would ship into the tree (the `.gitignore` pattern, the policy example) is copied once by the runbook, and
the every-turn rules come from a checkout the workspace neither versions nor checks.
Exit cost: delete one import line and the checkout; to B, the migration of item 6.
Moving parts: the checkout and its fast-forward; one amended sentence. The O1, A1 and CB1 skip is fixed
either way, as a small change of its own.

## Recommendation

B, on the owner's stated flow (updates only from ER-Workspace, Hatchery through it), and argued on three
things no other option gives together (rewritten after objection 1): curation, since a Hatchery change is
run once by the owner in the intermediate before anybody else's agents take it, and everyone is on one
Hatchery level the owner can reproduce; one place where a departure from Hatchery is made for everyone
(one instance today, the licensing section); and a private channel by which another person's fix to a base
file reaches the owner. Rule arrival is not the reason: E delivers the rules by pull, and item 8 with the
guard change takes most rule text out of the layer. Whether those three are worth the owner being the
relay for every update and every fix is the owner's choice between B and E (For the owner, 1); what
follows specifies B. The names a person types are accepted (the owner, 2026-11-10).

1. Naming. Each base ships `.claude/bases/<remote>.json`; the file name is the remote name and the
   lower-cased `name`:

   ```
   { "name": "ER-Workspace", "url": "<clone URL>", "branch": "main", "parent": "Hatchery",
     "records": "docs/er-workspace", "prefix": "er-",
     "baseOwned": ["clone-repos.sh", "repos.txt", "docs/er-workspace", "LICENSES"],
     "mixed": [".gitignore"], "baseExcluded": [], "projectOwned": [] }
   ```

   Hatchery ships `hatchery.json` (no `parent`, `records` `docs/hatchery`, no lists: its map stays
   `ownership.json`). The base line is `^<Name> base: <hex>` with `<Name>` a descriptor's name, one line
   per tree; two names stop the helper with `ownership: two base lines, <a> in <file> and <b> in <file>;
   keep one`. A tree with no `.claude/bases/` reads as today (Hatchery, remote `hatchery`, today's URL), so
   every existing sibling's line, remote and next update are untouched: an addition under ADR 0013. `node
   .claude/hooks/ownership.js --whoami` prints the tree's kind, its upstream (name, remote, URL, branch,
   `FROM`), where `adr` writes, what the scans read, and the chain. The rule: the top layer `T` is the
   descriptor no other names as `parent`; the tree is base `T` when its `docs/design/decisions/` holds no
   `T base:` line (its upstream is then `T`'s parent), otherwise a project of `T`. That one command
   replaces the two-part test in `adr`, `triage` and `handoff` and the remote test behind O1, A1 and CB1
   (it closes `2026-10-30-o1-lift-names-the-hatchery-remote.md`). Item 10 says what each kind runs.
2. Ownership. The effective map at `<sha>` is `ownership.json` at `<sha>`, then each descriptor's lists at
   `<sha>`, root first; a literal entry a later layer repeats takes the later class (`.gitignore`:
   project-owned for Hatchery, `mixed` for the ER layer); the class is the longest entry, as today. An
   intermediate's commit holds Hatchery's files as the intermediate has them, so a workspace's base-owned
   set is Hatchery's set plus the layer's additions, in one diff between two intermediate commits. The
   intermediate never edits `ownership.json`. Under `.claude` a path Hatchery does not ship is already the
   intermediate's own (the map sibling: by the diff's construction) and ships down because `.claude` is
   base-owned, so added agreement files, skills, agents and policy examples need no entry. A later
   Hatchery file of the same name is listed under `collisions` in the intermediate's report whatever its
   name (the critic's claim 3); the `prefix` only makes that unlikely and names the files the layer's
   15-line case counts. Root paths and the records subtree need entries. Departures compose by applying
   one rule twice: the intermediate's rows (against Hatchery) decide what its commits hold; to a workspace
   every base-owned file is the base's, whoever wrote it; the workspace's rows (against the intermediate)
   are item 6 of ADR 0016 as it stands. A change to a Hatchery section for the whole layer is an in-place
   edit in the intermediate with a row there, never `.claude/project/<section>.md`, which is
   project-owned, does not ship, and is each person's slot (the front-man delegation lives there).
3. Rules and records. A layer's every-turn rules are agreement files it adds:
   `.claude/agreement/<prefix><name>.md`, here `er-modules.md`, at most 15 lines for all of a layer's added
   sections. Being an agreement section, the existing check already makes every workspace import it or
   replace it with `.claude/project/er-modules.md`: no free import, no new check, and setup builds the
   import lines from the files present. The 40 and the 190 do not move, and the layer's lines come out of
   Hatchery's headroom under the 150 (objection 6): in an intermediate, A1 counts the agreement as
   shipped, Hatchery's files plus the layer's, so a Hatchery addition that no longer fits fails at the
   intermediate's update, on the owner, not two hops down: `FAIL A1 agreement: <n> lines as shipped
   (<parent> <h>, layer <l>), cap 150; lift: trim the layer, or a Hatchery ADR amendment (architect)`,
   and `FAIL A2 agreement: layer sections <l> lines, cap 15; lift: an ADR amendment in this base
   (architect)`. Licence (the owner, 2026-11-10: none for ER-Workspace and the workspaces): the layer
   edits `.claude/agreement/licensing.md` in place, one row in ER-Workspace against Hatchery, saying that
   the workspace's own files carry no licence and a module's files follow the module; it holds no
   `LICENSE` and keeps `LICENSES/`, the text Hatchery's GPL-headed scripts ask to travel with
   (objection 15); setup from this base asks no licence question, and a workspace's own licensing
   replacement goes at its next update. The front-man workspace loads 183 today, 9 of them two
   project-rule paragraphs the section restates and 9 its licensing replacement, so it lands near 187 with
   the section at its budget; its 22 lines over the base's delegation section are its own departure, and
   `edit.capExempt` is its own lift. Records: `docs/er-workspace/` (`README.md`, `decisions/`,
   `reference/`), shipped by the descriptor, never edited below. The intermediate's provenance ADR with
   its departure table is the one file in its `docs/design/decisions/`, project-owned and unshipped, so a
   workspace holds exactly one base line; every other record of the intermediate goes in its records
   subtree, one number series across both. The layer's own text cites `ER-Workspace ADR NNNN`. The wider
   convention for Hatchery's text (`2026-11-03-base-adr-numbers-collide-with-project-adrs.md`) is taken
   out of this proposal (objection 12): it is its own change, after the chain's first step. Each literal
   `docs/hatchery` in a check becomes "every descriptor's `records`".
4. Update flow. One upstream. `update-from-base` is today's procedure with the remote, branch and line
   name taken from `--whoami`; subject `Update from <Name> <FROM7>..<sha7>`; the body carries, after the
   incoming list, every `Carry-back:` and `Carry-back declined:` line of the incoming range, so a landing
   two hops up stays visible to a `--grep` one hop down (objection 11). The intermediate updates from
   Hatchery by the same skill, runs the harness and its layer's cases, pushes; the workspaces follow.
   When it lags, the workspaces wait, and the report prints the intermediate's own Hatchery base (`git
   grep -h "^Hatchery base:" <sha> -- docs/design/decisions/`) with its date and the date of Hatchery's
   `main`. For an urgent Hatchery fix a person takes one path ahead (`git fetch hatchery`, `git checkout
   <sha> -- <path>`, a row `none: taken ahead from Hatchery <sha7>`); the next update finds it identical
   and the row goes. No routine second upstream. Two things follow from workspaces the owner cannot read
   or migrate (objection 8): a migration for an incompatible change must be executable by the update
   skill alone, from text read at `<sha>` (ADR 0013's deferred migration note, whose trigger this is);
   and the update report ends with the way back, `git revert <the update commit>`, run from a terminal
   outside the session when a broken hook has closed the guard.
5. Carry-back. Always one hop, to the tree's own upstream, the branch cut from a named `<remote>/<branch>`
   sha; the path check reads the effective map. Into ER-Workspace the channel is a pull request (the owner,
   2026-11-10). The person pushes `carry-back/<slug>`, made by ADR 0019's Send as it stands, to their fork
   of ER-Workspace (a third, push-only remote, pushed without `-u` so pusher check 3 still sees `origin`;
   the branch's history is ER-Workspace's, so it pushes from the workspace repository) and opens a PR to
   `main`. The owner lands it by 0019's Land with one substitution, `git fetch er-workspace
   refs/pull/<n>/head` for the fetch by URL: the same checks, the diff applied `--3way`, the review, a
   fresh commit under the base's identity pushed through its own scan, then a closing comment naming the
   landing sha, or the refusal's text, written to a file and passed through `--text` first (the form the
   `carry-back` skill already holds for PR 1). The landing line reads `Carry-back: PR <n>
   carry-back/<slug> at <sha40>`, so no private repository is named in a commit every member reads, and
   the row's key is unchanged. Never the merge button: a server-side merge is no push, so no scan reads
   it, and it keeps the sender's commit. Nothing refuses the button; CB1 runs in an intermediate (item
   10) and fails on the foreign author at the owner's next harness run, and the recovery is a revert of
   the merge and a landing by the procedure, the identity staying in history as it does in the PR ref.
   In an intermediate the queue is `gh pr list` beside the local read of `../*/` (another person's
   workspace is on another machine). A request without a diff, or a rule change, is a PR whose only entry
   adds one `docs/ideas/YYYY-MM-DD-<slug>.md`; Land files it as an idea and closes the PR, and the send
   check's refusal of a project-owned path names that form. No issue flow: the owner's `Waits:` stands.
   Kept from 0019: one change per branch, the `Carry-back-base:` line, the send and path checks, the
   landing line that settles the workspace's row at its next update. Departed from, in an ER-Workspace
   record citing Hatchery ADR 0019: item 1's "no PR" for this base; "nothing foreign enters the base's
   repository", since `refs/pull/<n>/head` keeps the sender's commit and identity there and the PR's
   title and body leave outside the guard; and the PR page as a reading surface, which 0019 records as
   declined for Hatchery. Hatchery takes no PRs, as now, and the hop from ER-Workspace to Hatchery is
   0019 unchanged. A path Hatchery does not ship ends at the intermediate. A path Hatchery ships has two
   forms: forwarded, the owner re-cutting the branch on a Hatchery sha with the body line `Forwarded: PR
   <n> carry-back/<slug> at <sha40>`, one landing and one review in Hatchery, the workspace's row settled
   when the intermediate's next update commit carries that line; or, when it cannot wait, landed in the
   intermediate as its own departure and sent on as its own carry-back.
6. Setup and migration. One path, adoption-shaped: a fresh `git init` or the person's existing repository,
   then `adopt-base`. No clone: a clone would put Hatchery's and the intermediate's history in every
   private workspace, and the scan bounds its range only by a remote named `hatchery`. `adopt-base` reads
   the upstream from the top descriptor at the fetched sha and copies what `ownership.js --paths --ref
   <sha>` prints, the base-owned entries of the effective map (today's literal list would bring neither
   `.claude/bases/` nor a root path, and the tree would then read as Hatchery itself). It stops before
   copying on any printed path that exists at `HEAD` with a different blob, naming each: `adopt-base:
   <path> exists here and differs from the base's; rename yours or delete it, then run again` (`git
   checkout <sha> -- <path>` would replace it silently; the owner's notes workspace has its own
   `clone-repos.sh`, whose list moves to `repos.local`). It writes the `<Name> base:` provenance ADR and
   builds the import lines from the agreement files. `decant` stays for clones and reads `--whoami`;
   `BASE_REMOTE` becomes every chain remote that is configured. The ER runbook is the layer's own skill,
   `er-setup`, wrapping `adopt-base`: add the remote and disable its push URL; the policy from
   `.claude/hooks/policy.examples/er-workspace.json` (an empty identities list; `git.push.branches`
   without `master`, so a pusher's push of a shipping `master` in any module is refused by the guard and
   the lift is the main session on the owner's word); the commit identity; the identities of the module
   committers, proposed as the union of the lists in the cloned modules' own policy files for the person
   to confirm, with `er-setup: privacy.identities is empty, so the scan's identity class is off; lift:
   confirm the proposed list or name the identities` until it is not; ADR 0015's handoff question; how
   the person works (a front-man delegation text ships as an example to copy into
   `.claude/project/delegation.md`, nothing by default); `repos.txt`, the layer's list (the owner,
   2026-11-10: one `<owner>/<name> <dir>` line per module under `#` lines saying what it is, read by the
   agent and the script alike), narrowed by the untracked `repos.local`; `clone-repos.sh`, which prints
   one line for each repository `git ls-remote` cannot reach and skips it, and one for each top-level
   directory that is ignored and is not a listed checkout (the blanket pattern would otherwise hide a
   person's own new folder, objection 13); existing checkouts are moved in, then `git worktree repair
   <every moved worktree path>` in each moved checkout (without paths it repairs nothing and says
   nothing), and `git worktree list` must show no `prunable` entry before anything else runs there;
   item 8's exclude.
   The direct adopter re-bases without a new mechanism, because `git diff` takes any two commits in the
   object store (the critic's claim 1, run on the real shape): (a) clean tree, both remotes fetched, `E`
   the intermediate's sha, and its Hatchery base at `E` is `f78e0dc` or a descendant, else the helper
   stops with `ownership: <Name> at <E7> is based on Hatchery <b7>, older than this tree's <f7>; lift:
   that base's owner updates it from Hatchery; nothing to do here`; (b) with the old line still in place,
   the helper extracted from `E` prints `git diff -M --binary f78e0dc <E> -- <effective set at E> | git
   apply --3way`: the layer's files arrive as additions, the intermediate's departures and newer Hatchery
   as edits; the adopter's `update.exclude` must keep `LICENSE`, which the diff deletes and whose absence
   would fail the patch whole, and drops `LICENSES`; (c) conflicts by the usual rules: the delegation and
   agent rows keep, the licensing row and its replacement go, `.gitignore` is hand-merged to the layer's
   pattern (the one conflict in the critic's run), `unimported` names `er-modules.md`, the two restated
   paragraphs leave `CLAUDE.md`, `edit.anchorExempt` gains `docs/er-workspace/*`; (d) a new ADR,
   `Re-based onto ER-Workspace`, carries the living line and the departure table, and the old line
   becomes a sentence so one anchored line remains; (e) the `hatchery` remote goes or stays fetch-only;
   one commit.
   The way out (corrected after the critic's experiment, objection 3). A plain reverse re-base onto a
   Hatchery commit deletes the layer's files under `.claude` (the section, the setup skill, the
   descriptor, the policy example) and reverts its edits, cleanly, exit 0, because they are in
   Hatchery's set by path; only the root paths and the records subtree stay. The exit is therefore
   `ownership.js --command --ref <H> --leave`: `FROM` is the workspace's line `E`, `H` a Hatchery commit
   at or after the intermediate's Hatchery base `b` (read from `E`); one exclude for every path `git diff
   --name-only --diff-filter=A <b> <E>` lists inside the set, which stay as the workspace's own; every
   `M` entry of that diff (the intermediate's edits to Hatchery files, the licensing section today) is
   named in the report for the person to keep as a rowed replacement or let go; the descriptor is deleted
   and the line rewritten to `Hatchery base: <H>` last. A harness case in group O runs the critic's
   fixture. A person removed from the organization runs it alone: it reads `E`, already fetched, and
   Hatchery, which is public.
7. Skills and promises. `update-from-base` and `adopt-base` replace the two Hatchery-named skills;
   `decant` and `carry-back` keep their names and read `--whoami`. Stubs stay at
   `.claude/skills/update-from-hatchery/SKILL.md` and `.claude/skills/adopt-hatchery/SKILL.md` for good:
   every older copy reads its skill at `<sha>` by that path before applying, and the README prompts name
   them. Still promised unchanged: the `Hatchery base:` line, the four lists, the `hatchery` remote and URL
   where no descriptor exists, `docs/hatchery/`. Rows for ADR 0013, recorded as provisional (objection 4)
   until the first workspace has re-based and one update and one carry-back have run through the chain:
   the descriptor (path and fields), the generalised line, the `--whoami` lines, the two stub paths. ADR
   0016's "one set for every tree" becomes one set per chain: a supersession in part, no sibling migrates.
8. Module sessions (the architect's pick; the owner has no strong view). A session started inside a module
   should not load the workspace's `CLAUDE.md`. Its settings, hooks, skills and agents come from the
   module (the critic's claim 2: only `CLAUDE.md` is read from directories above a repository), so its
   rules should come from the same tree. The workspace file would add the workspace's handoff, a second
   copy of the agreement at another Hatchery level, `Branch:` and `Test command:` lines that are wrong
   for the module, and the person's delegation replacement: up to 190 lines the module's own agreement
   says already or contradicts. `claudeMdExcludes` exists at every settings layer and matches absolute
   paths by glob, so `er-setup` writes `**/<workspace folder>/CLAUDE.md` (no home path, no drive letter)
   into each cloned module's untracked `.claude/settings.local.json`, for a module that has its own
   `CLAUDE.md`. Three limits, from the critic's reading (objection 10). Seen from a module session the
   workspace file's imports are external imports and wait for a one-time approval; until it is given the
   file's body loads and its imports do not, so the yielding sentence, "inside a module, that module's
   `CLAUDE.md` wins over anything loaded from here", goes in the body of the workspace `CLAUDE.md`
   template and not only in `er-modules.md`. On Windows the local settings file is read from the starting
   directory, so a session started in a linked worktree of a module has no exclude and has the sentence
   only. And the other direction is left as it is: in a workspace session a module's `CLAUDE.md`, its
   imports and its nested skills load once a file there is read, which suits an agent working in that
   module and costs the coordinator context. The sentinel procedure runs from a module root and from a
   module worktree, on Windows, before the ADR.
9. Where ER-Workspace lives (the owner, 2026-11-10: one of two; only the owner pushes `main` in both).
   An organization with Read for everyone else: a fetch needs membership; someone removed keeps a working
   tree that no longer updates, and leaves by item 6's way out. A PR needs a fork; the Read role may fork
   and send pull requests from a fork, but a new organization disallows forking private repositories by
   default, so the owner switches that on, and no plan limit on it was found. A removed person's fork is
   deleted with their access; the upstream's owners can read every fork in the network; protected
   branches on a private repository are a paid-plan feature. Where forking stays off, the fallback (the
   branch on the person's own workspace repository, fetched by URL as 0019 has it) puts the
   intermediate's history on that repository, which item 6 avoids clones to prevent, and needs the owner
   to read it. The descriptor's `url` changes with the transfer. Public: anyone fetches, nobody loses
   updates, forks are free; but the layer is then published, so `repos.txt` with its account names and
   overviews has to leave it again, every PR ref publishes its sender's identity, and the scan on the
   owner's pushes is the only gate. The licence text travels with Hatchery's files in both (item 3).
10. The three kinds, and what each runs (objection 4):

    | Reader | Root base (Hatchery) | Intermediate | Project |
    |---|---|---|---|
    | `adr` writes | `docs/hatchery/decisions/` | its `records` subtree; the provenance ADR alone in `docs/design/decisions/` | `docs/design/decisions/` |
    | `triage`, `handoff` scan | `docs/hatchery/decisions/` | both of the above | `docs/design/decisions/` |
    | `update-from-base` | nothing to update from | from its parent | from its upstream |
    | O1 (`--check`, the effective map) | runs | runs | skips |
    | A1, A2 (agreement as shipped) | A1 runs | both run | skip |
    | CB1 (authors on `main`) | runs | runs | skips |
    | carry-back queue | `../*/` | `gh pr list`, then `../*/` | skips |
    | `carry-back` Send, Land | Land | Send to its parent; Land by PR | Send to its upstream |
    | `decant`, `adopt-base` from it | yes | yes | no |

The ER layer is then: `clone-repos.sh`, `repos.txt`, the `.gitignore` pattern (every top-level directory
ignored but the workspace's own), `er-modules.md` (the live-ship and redeploy rules, `git -C <module>`,
never `git add` a module, no secrets or live data in notes, a module brief names the module's `CLAUDE.md`),
the edited licensing section with `LICENSES/`, the policy example, `er-setup`, and its records. The chain
does not change the guard, but it makes the guard's limit the normal case: in a workspace session the rule
that protects the live game is prose, and the policy must list every module committer the person pushes.
The shipped example now refuses a pusher's push of `master`; it cannot tell a module whose shipping branch
is `main` from the workspace's own `main`, and it does not bind the main session. Closing that needs the
2026-10-21 idea (the policy of the repository the command runs in) or server-side protection of the
modules' shipping branches. Whether one of them gates the second person's setup is the owner's (For the
owner, 2).

First step: the helper (`--whoami`, descriptors, the layered map, `--paths`, `--leave`, Hatchery's own
`hatchery.json`) with harness cases in group O, the skip of O1, A1 and CB1 moved to `--whoami`, and no
other behaviour change for any sibling. Then the skills and stubs; then ER-Workspace is decanted from
Hatchery and given its layer; then the front-man workspace re-bases as the first real case and one update
and one carry-back run through the chain, after which the ADR 0013 rows stop being provisional; then the
gate of For the owner, 2, if the owner sets one; then the owner's notes workspace (`../exiledreign`, not a
Hatchery tree today) and the next person adopt. The citation convention is a separate change after this.

## What would change this recommendation

- Curation: in a year, every update of the intermediate from Hatchery went through unchanged and caught
  nothing before the workspaces took it: E.
- One place: in a year the intermediate made no departure from Hatchery on behalf of the workspaces
  beyond the licensing section: E.
- The private channel: in a year no carry-back came from a person other than the owner: E.
- Lag: the intermediate's Hatchery base is more than 14 days older than Hatchery's `main` at three
  workspace updates running (the update report prints both dates): C, or E.
- Withdrawn (objection 1): "the guard reads the target repository's policy and the modules carry the ER
  rules themselves: D". The guard change is recommended whatever is chosen; when it lands the layer's
  section shrinks to the yielding sentence and the two git rules, and B stands or falls on the three
  lines above, not on rule text.
- Claim 2's first half stops holding (no ancestor loading): item 8 is moot and its exclude is dropped.
- A second intermediate appears with a different shape (not a workspace): check the descriptor's fields
  against it before its rows are promised for that shape.

## Claims to verify

Each was checked by the critic (`0024-critic-review.md`, section 1); the result is at the end of the line.

1. `git diff -M --binary <A> <B> -- <paths> | git apply --3way` applies where `A` and `B` come from
   unrelated histories, given both are in the object store (item 6). Holds by experiment, on the real
   shape too; a deletion of a file the tree lacks fails the patch whole, an addition onto an existing
   file is `AA`.
2. Claude Code loads `CLAUDE.md` from directories above the one a session starts in, with their `@`
   imports, and loads no settings, hooks, skills or agents from above a repository; `claudeMdExcludes`
   exists. Holds from the docs, with item 8's three limits; the sentinel procedure is still to run.
3. A path under `.claude` that Hatchery does not ship arrives in a workspace from a diff of two
   intermediate commits, and a later Hatchery file of the same name is listed under `collisions`. Holds;
   the `prefix` plays no part in it (item 2 corrected).
4. `--claude-check` treats any `.claude/agreement/*.md` as a section and accepts
   `.claude/project/<same name>.md` as its replacement. Holds.
5. The numbers: 123 agreement lines in Hatchery; 183 loaded in the front-man workspace. Hold.
6. `privacy-scan.js`: an empty `privacy.identities` turns the identity class off; only the push target and
   a remote named `hatchery` bound the range. Holds.
7. An older `update-from-hatchery` reads its skill at `<sha>` by the old path, so a stub there is reached
   by every sibling. Holds.
8. The list of hard-coded sites under Constraints is complete. Failed: seven sites were missing; the
   list is completed in place.
9. `git worktree repair <path>...` run in a moved main checkout reconnects linked worktrees that moved
   too. Holds on Windows; without paths it is a silent no-op (item 6 corrected).
10. GitHub: the Read role can fork a private organization repository only where the organization allows
    it, and can open a PR from the fork; the base can fetch `refs/pull/<n>/head`. Holds, with the
    additions now in item 9; that the ref outlives a deleted fork rests on ADR 0019's sources.
11. `triage`'s queue matches only `^Hatchery base:`; the update settles a `carry-back/<slug>` row only
    from the upstream's own log. Holds.
12. GPL-3.0-or-later files copied from Hatchery must travel with the licence text. Holds, and wider than
    stated: it reaches the organization option too (item 3 corrected).

## Critic's objections and responses

Review: `0024-critic-review.md` (no blocking, nine serious, nine minor). Twelve accepted, six accepted with
a change, none rejected; the deciding part of 1 and of 2 is the owner's and is not decided here.

1. The stated reason for B is one the proposal expects to remove, and the best alternative did not get
   equal depth. Response: right on both counts. Item 8 and the guard recommendation retire most of the
   rule text that "the rules keep arriving" rested on, and the read-in-place variant was one sentence
   with a wrong fact in it (the import is inside the workspace root, so it is checked and counted).
   Resolution: accepted with change. Option E is written at equal depth; the Recommendation argues B on
   curation, the one-place departure and the private channel; each has a reversal line; the third
   reversal line is withdrawn and the guard recommendation wins. The choice between B so argued and E is
   put to the owner, not made here.
2. The layer is rolled out to other people's agents while the only protection of the live game in a
   workspace session is prose. Response: the exposure is real and exists today in the direct adopter;
   the chain copies it to more people. The cheap part is design and is taken: the shipped policy example
   refuses a pusher's push of `master`, lift named. It does not cover a module that ships from `main` or
   the main session. Resolution: accepted with change; whether the guard change or server-side protection
   gates the second person's setup is the owner's, and the First step list holds the slot.
3. B's exit cost line is wrong by experiment. Response: it is; the reverse diff deletes the layer's
   `.claude` half silently. Resolution: accepted. Option B's exit line, item 6 ("the way out") and item 9
   are rewritten around `--leave` with an exclude per layer path, the intermediate's edits reported, and
   a harness case on the critic's fixture.
4. The surfaces are promised before the intermediate's own behaviour is defined. Response: `--whoami`
   printed one decisions directory for a tree that has two, and nothing said what the middle kind runs.
   Resolution: accepted. Item 10 is the table of three kinds against each reader; `--whoami` prints the
   write directory and the scan set; the ADR 0013 rows are provisional until the chain has carried one
   re-base, one update and one carry-back.
5. Claim 8 fails, and the adoption path is under-specified as a result. Response: the literal copy list
   would have produced a workspace the item 1 rule reads as Hatchery, and a silent overwrite of the
   second adopter's script. Resolution: accepted. The Constraints list is completed; `adopt-base` copies
   what `--paths` prints from the effective map and stops, naming the path, on a differing file; the
   migration gains the `edit.anchorExempt` entry.
6. The 190 is spent twice. Response: 40 plus 150 leaves a layer nothing; the headroom was Hatchery's.
   Resolution: accepted. The layer's lines come out of the 150; A1 in an intermediate counts the
   agreement as shipped, so the conflict lands on the owner at the intermediate's update; A2 holds the
   layer's 15. Both lines carry their lift.
7. ADR 0019's single-owner assumptions are inherited where they no longer hold. Response: agreed for the
   queue, the request without a diff, and the private repository named in the landing line. Resolution:
   accepted with change. The queue in an intermediate is `gh pr list` beside the local read; the landing
   line names `PR <n>`; of the two transports offered, the PR that adds one idea file is taken and the
   issue is not, because the owner's `Waits:` on an issue flow stands.
8. Every Hatchery promise becomes a promise to people the owner cannot migrate. Response: true of any
   option in which other people run Hatchery, B and E alike, and worth saying where it is decided.
   Resolution: accepted. Option B's risks say so; item 4 carries the rule that a migration runs from the
   update skill alone at `<sha>`, and the report's last line names the revert.
9. Four mechanisms refuse without a documented lift or message. Response: agreed, and the review also
   answers the identities question the proposal had left with the owner: the cloned modules carry their
   own lists. Resolution: accepted. The texts and lifts are in items 1, 3, 5 and 6; `er-setup` proposes
   the union of the modules' lists for the person to confirm; CB1 runs in an intermediate; the clone
   script prints each skip.
10. Item 8's fallback does not load where it is needed. Response: agreed on the approval dialog, the
    Windows settings location and the pattern form. The reverse direction is how nested instructions
    work and is what a subagent in a module needs. Resolution: accepted with change. The sentence moves
    to the template body, the pattern is the home-free glob, a worktree session is stated to have the
    sentence only, the reverse direction is stated and not designed around, and the sentinel procedure
    is a step before the ADR.
11. A person's fix to a Hatchery file costs two sends, two lands and two updates. Response: the update
    commit's body carrying the `Carry-back` lines of its range removes the reason given against a single
    landing, and costs one `git log`. Forwarding cannot be the only form: an urgent fix should not wait
    for a Hatchery landing and an intermediate update. Resolution: accepted with change. The body line is
    in item 4; item 5 has both forms, forwarded by default and landed first when it cannot wait.
12. The citation convention is a second decision riding in this one. Response: it is, and it would break
    "no behaviour change for any sibling" in the first step. Resolution: accepted. Taken out; the layer's
    own new text names its base, and the idea file stays open for its own change.
13. The blanket ignore pattern fails silently. Response: agreed. The `handoff` skill is Hatchery's and a
    layer cannot edit it without a departure. Resolution: accepted with change. The layer's own script
    prints every ignored top-level directory that is not a listed checkout, on each run and in
    `er-setup`.
14. The lag reversal line cannot fire, and B's moving parts omit the carry-back route's. Resolution:
    accepted. The line has a number and the report prints the two dates; the moving parts are completed.
15. Claim 12 reaches the organization option too. Resolution: accepted. ER-Workspace keeps `LICENSES/`
    through its descriptor and holds no `LICENSE`; the edited section covers the workspace's own files
    and leaves a module's files to the module.
16. Item 9 leaves out what the organization option does to a fork. Resolution: accepted. The default
    forking policy, the deleted fork, the owners' read of every fork and the cost of the fallback are in
    item 9.
17. `git worktree repair` without paths is a silent no-op. Resolution: accepted. Item 6 passes every
    moved path and requires a list with no `prunable` entry.
18. The Constraints count is off by one. Resolution: accepted. Eight repositories; ER-Accounts was the
    one missing.

## For the owner

1. B or E (objection 1, serious; not decided here). For B: the owner runs each Hatchery change once
   before anyone else's agents take it and everyone sits on one known level; a departure from Hatchery is
   made once for all; another person's fix to a base file has a private way back. Its price: the owner
   relays every update and every fix, the workspaces wait on the intermediate, Hatchery gains a
   descriptor, a helper mode, two renamed skills with permanent stubs and new promised rows, and the
   direct adopter migrates. For E: none of that is built, nobody waits, the direct adopter stays as it
   is, and the rules still arrive by pull; its price is the three things B is argued on, one amended
   sentence in ADR 0016, and every-turn rules read from a checkout the workspace does not version. The
   critic puts about 65% on B, revised, for this owner. The architect's recommendation stays B only
   because the owner asked for updates through ER-Workspace alone; an owner who would rather not be the
   relay should take E.
2. Does protection of the modules' shipping branches gate the second person's setup (objection 2,
   serious; not decided here)? The critic: yes, one of the guard reading the target repository's policy
   or a recorded check that the module repositories protect those branches server-side, before anyone
   else is set up, as a step and not a recommendation. The other position: recommend and do not gate,
   because the direct adopter already runs with this exposure, the stricter shipped example removes the
   commonest case (a subagent pushing `master`), and people kept waiting will set up by hand with less.
3. Public or an organization (item 9): public is only possible once `repos.txt` lives somewhere private;
   an organization needs forking of private repositories switched on for the pull-request route.
4. The licence of ER's code, for all of ER; nothing here depends on it.
