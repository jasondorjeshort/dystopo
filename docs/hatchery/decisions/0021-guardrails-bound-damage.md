# 0021. Guardrails bound damage, not who may act

Date: 2026-10-21
Status: accepted

Record: proposal `../../design/proposals/0021-guardrails-bound-damage.md` and
`../../design/proposals/0021-critic-review.md` (3 blocking, 8 serious, 9 minor; all resolved).
Supersedes in part ADR 0006 (base values of the role keys; who writes a HOLD grant), ADR 0007 (one
review per change, for the light lane), ADR 0011 (the pool advice rides the HOLD deny in either
mode, wording only), ADR 0013 (the meaning of `privacy.identities` and the role defaults) and ADR
0018 (item 4's identity rules, item 6's pusher as the only pusher). Builds on ADR 0020 (the web
committer; no placeholder identity at decant and adopt).

## Context

The base was meant to let agents run unattended safely and had come to decide who may act. On
2026-10-21 in the downstream project Dystopo a tested hook change needed the owner's terminal, a
backup branch could not be deleted, the scan refused the owner's commits, and the carry-back of the
change stopped at Hatchery's commit rule (only `runner` commits, only `pusher` pushes, agent types
absent from a session rooted elsewhere). The deny lines were not captured; the downstream's
`push.branches` lacked the `carry-back/*` pattern ADR 0019 prescribed, so the roles rest on the
evidence below, not on the incidents.

Verified (proposal, Claims to verify, checked by the critic against primary sources): vendor
defaults bound damage, not role (Claude Code auto mode refuses force pushes, discarding commands,
rewriting pushed or foreign commits, deleting what the session did not create, repointing remotes,
and allows pushing any branch under the server's protection); every incident found destroyed
uncommitted work outside the agent's own space; the reflog keeps committed work 90 days; on one
machine every agent shares the owner's admin credential, so a role split is advisory; rulesets on a
private repository need GitHub Pro or above; both repositories are public with no rulesets
(2026-10-21); a subagent shares its parent's `session_id` (observed), so "created this session" is
not a per-agent fact. The owner agreed six items on 2026-10-21, the usage item included (a paying
decision); this record narrows three of them on the critic's evidence, each with a deferred trigger.

## Decision

1. Server side. Each repository carries a ruleset on `main` (block non-fast-forward, block deletion)
   and one on `refs/tags/v*` (block update and deletion); no required pull request, no bypass actor.
   The owner applies them (operator entry: the `gh api` call, a re-check of `GET /rulesets` after any
   visibility change, and the disable-then-re-enable step for the owner's own rewrites). `triage`
   lists a repository whose ruleset list is empty.
2. Who acts. Any session or agent commits and pushes any branch outside `main`, `master` and `v*`
   tags (the protected refs). The main session pushes a protected ref without a per-push word from
   the owner when the range is `light` with checks green or `full` with a verifier's merge verdict
   (item 5); any other protected push waits for the owner's word. `runner` and `pusher` remain as
   optional helpers. Policy: `git.commit.subagents` and `git.push.subagents` default `"any"`;
   `policy.examples/roles.json` keeps the split for a project that wants it.
3. Guard rules, stateless (`git-guard.js`; `git.localHistory: "bounded"`, the default). Refused for
   every caller, each advice naming its lift and who holds it:
   - a non-fast-forward push: a plain or `+` force, or `--force-with-lease` outside
     `git.push.forceWithLease` or onto a protected ref (pre-empting the server's rule violation);
   - a local rewrite of commits reachable from a remote-tracking ref (`commit --amend`, `rebase`
     including `--onto`, `reset` of any mode to a non-descendant of the upstream, `branch -f`,
     `checkout -B`, `switch -C`) unless the branch matches `git.push.forceWithLease`; lift: that key;
   - remote deletion of a branch with no merged PR (rule 2's `gh` check) whose tip no protected
     remote branch contains; a push deleting or moving a `v*` tag; lift: the owner's terminal;
   - repointing a remote (`remote set-url`, `rename`, `remove`; `config remote.<n>.url` or `pushurl`);
   - `--no-verify` and `commit -n` (rule 13, no lift);
   - a discarding command (`reset --hard`, `clean` without `-n`, `checkout -- <tree>`, `restore
     <tree>`, forced checkout or switch, `worktree remove --force`) only when `git status
     --porcelain` in its target (the tree it runs in; the named worktree for `worktree remove`;
     with `--ignored` for `clean -x`) lists work it would discard; the advice gives the count and
     says commit or stash first.
   Allowed: a forced local branch delete whose tip a remote-tracking ref reaches. Paths compare after
   `fs.realpathSync.native`. Unchanged: rules 10, 12, 14, 15, 19, 20; stash drop and clear (one stash
   per repository). `git.localHistory: "strict"` restores the per-verb refusals of rules 5 to 9, 16
   to 18. A `gh` tripwire refuses `gh api` writes (a method, `-f`, `-F`, `--input`) to rulesets,
   branch protection and repository settings, `gh api graphql` mutations, `gh auth token`, and `gh
   repo delete`, `rename`, `edit --visibility`; it is a tripwire, not a bound, while agents hold an
   admin credential.
4. Identity (`privacy-scan.js`, class `identity`). Accepted as author, committer or tagger: the
   name and email from the pushed repository's own config (`--local`, or `--worktree` where set);
   that name with a `users.noreply.github.com` address; GitHub's web committer as committer (ADR 0020
   item 3); the extras in `privacy.identities`, which no longer switch the class off when empty. An
   identity found only in global or system config and not listed is a hit; its advice: set the
   repository identity to the one the owner uses. No skill or agent proposes or writes a placeholder
   identity anywhere. Fitness check CB1 accepts the same set. Content classes are unchanged.
5. Lanes. `node .claude/hooks/lane.js <range>` (Node built-ins) prints `light` or `full` and the
   deciding paths. Light: every changed path, both sides of a rename, is a regular file ending `.md`
   under `docs/` or `.claude/skills/`. Anything else is full; `.claude/hooks/**`, the git hooks
   directory, `.claude/settings.json` and workflows are always full. Light with the test command,
   caps and scan green reaches `main` on the implementer's report. Full needs a reviewed branch and
   one fresh-context verifier first: a PR where the project has a PR flow; in Hatchery a pushed
   branch merged after the verifier's report, or an ADR 0019 landing after its review. No human is
   needed for either. The skills that push `main` (`commit`, `carry-back`, a downstream's merge
   sequence) run `lane.js` and quote its line; the guard does not, yet (deferred b).
6. Audit. At wave close or handoff a reviewer reads everything that reached `main` outside a
   reviewed branch since the audited sha the handoff records, light skill changes first, and
   reports; the coordinator reads the report and records the new sha.
7. Usage (`usage.mode`). The gate denies at HOLD in both modes. `"warn"` (default): the coordinator
   may lift it by writing a session grant (ADR 0006's file and rules) whose `reason` says why the
   launch is worth it; the gate prints the reason on each use, which is the log. `"block"`: only the
   owner's word writes a grant, as before. `usage.deny`, when set, is a ceiling nobody lifts;
   Hatchery sets none, so in `"warn"` it has no hard line.
8. Permissions (`.claude/settings.json`). Allow: `git add`, `git commit`, `git fetch`, `git
   switch`, `git worktree add`, the test command, each as its own narrow entry; never `git:*` or a
   whole-tool Bash rule. Deny: `Edit` and `Write` on `/.claude/hooks/**` and
   `/.claude/settings.json`, anchored at the project root, so the live bound changes through a
   worktree and the full lane; the owner lifts it from a terminal. A shell write is not covered.

Out of scope: who reviews what humans see and touch (unchanged); the history rewrite (ADR 0018).
Deferred, each with its trigger:
- a. An own-worktree allowance for discarding commands, keyed on `agent_id` (the main session its
  own key), one record per creation, verified with `git worktree list --porcelain` at use. Trigger:
  a wave's reports show more than five stashes forced in agents' own worktrees, and a captured
  subagent hook payload settles what `session_id` and `agent_id` carry.
- b. The guard runs `lane.js` on a protected push, admitting a range whose non-merge commits and
  merges' non-first parents are on a remote non-protected branch, or an ADR 0019 landing by its
  `Carry-back:` line. Trigger: one wave run with skill-side enforcement.
- c. A fine-grained token without administration permission for agents. Trigger: Claude Code or Git
  Credential Manager lets a session use a credential other than the machine's.
- d. A WSL2 sandbox. Trigger: a sibling whose toolchain runs under WSL2.

Follow-up tasks T-a to T-g, with the files each touches: the proposal's Recommendation, built in
that order so no rule loosens before its replacement holds; T-g is the migration below.

Migration (ADR 0013 promise; ADR 0016 update). A sibling's next update brings the new defaults; a
sibling that relies on the old ones writes them before updating: `git.commit.subagents: ["runner"]`
and `git.push.subagents: ["pusher"]` for the split; `git.localHistory: "strict"`; `usage.mode:
"block"` for a hard gate (Dystopo, its owner's rule of 2026-10-17); a non-empty `privacy.identities`
keeps working and its entries become extras. Dystopo also adds `carry-back/*` to `push.branches`.
Every sibling sets its repository identity (`git config user.name`, `user.email`) before the first
push under the new scan. Hatchery's own `policy.json` drops its role lists at T-e; its identity
entry stays until T-c lands, then is the owner's to remove.

## Consequences

- An agent in any session commits, pushes a work branch, deletes a pushed local branch and resets
  a clean tree without a hand-off or the owner's terminal; carry-backs land from any root.
- A dirty tree costs a stash or a commit before a discarding command, even in an agent's own worktree.
- Docs and skill text reach `main` before anyone else reads them; the audit reads them after, so a
  bad skill change can drive agents for up to one wave.
- The coordinator pushes `main` unattended for lane-cleared ranges, a change from "when the user
  asked in so many words"; the owner narrows it by saying so, recorded as an amendment.
- While a repository is private on a plan without rulesets, the guard's stateless rules are the
  only bound on its protected refs; any agent can still edit them by shell or use the admin token.
- Costs: one script, rewritten guard rules and harness cases, an operator step per repository,
  seven skills and five agreement sections, one migration per sibling.
- Supersede when hooks get a parsed push plan or per-agent credentials (deferred c), or when a wave
  shows the bounded rules lost committed work.

Predictions (the trigger scan reads these):
- Prediction, check by 2027-01-21: no agent in a project on this base destroyed uncommitted work
  under the bounded rules, and no forced push reached a protected ref.
- Prediction, check by 2027-01-21: the owner typed no terminal command for routine commit, push or
  branch cleanup in a project on this base; each one typed is named in a finding.
- Prediction, check when the first audit after T-d runs: it reports no light-lane change that had
  to be reverted.
