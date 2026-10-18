# 0035. The hooks are the base's scripts under this project's policy

Date: 2026-10-16
Status: superseded by 0036
0036 (2026-10-17, the owner's answers) allows a subagent's pull, merge and rebase on `main` and takes `triage`; every other section here stands.

Record: the first `update-from-hatchery` run here (Hatchery `0ce6670` to `c50b0da`, the base line in
ADR 0020); Hatchery's ADRs 0006 (the policy file), 0010 (the base line decides the update mode) and
0011 (one usage cache per session, pool advice), and its hook specification
`docs/design/decisions/0002-hook-specs.md` in the Hatchery tree; ADR 0021, which this record
supersedes; ADR 0026.

## Context

ADR 0021 kept the wave workflow's git rules as constants inside copies of the base's `git-guard.js`
and `post-edit.js` and said it would be superseded when the base's scripts read a policy file. The
base did that on 2026-10-13 (its ADR 0006, which took this project's rules as its `task-branches`
example), then added quote-aware parsing of Bash and PowerShell command lines (`shell-parse.js`),
closed the bypasses an independent review had reproduced, moved the usage cache to one file per
session with scoped override grants and pool advice (ADR 0011), and gave `post-edit` a check that
paths named in docs exist. ADR 0020 adopted the base by hand, so this is patch mode (Hatchery ADR
0010: the base line wins over `git merge-base`).

Verified before taking the scripts: the base harness under this policy; this project's previous
harness run against the new guard (the same verdict in every case but the four tabled below, the
rest wording); probes of the commands the wave uses from a subagent on `main` and on a task branch
and from the main session.

## Decision

### From the base, verbatim

`.claude/hooks/git-guard.js`, `policy.js`, `post-edit.js`, `shell-parse.js`, `usage-gate.js`,
`policy.examples/`, `.claude/package.json`, `.claude/statusline.js`, the `update-from-hatchery`
skill, and `test-hooks.js` (the base's cases first; this project's group is appended, below).
Patched by hand with the base's new lines: `.claude/rules/docs.md` (an index is the one list a doc
carries; the code wins; a behaviour change updates its doc in the same commit; the path check and
`[no-check: <reason>]`; the guard reads quoted text as data), the `adr`, `handoff` and `brief`
skills and the critic brief.

### This project's, with the reason the update rule needs

- `.claude/hooks/policy.json` (always the project's). The `git` block is E1 to E5, A1 and A2 of
  ADR 0021 unchanged; `usage` is unchanged (hold 75, deny 100, `DYSTOPO_GATE_OVERRIDE`);
  `edit.anchorExempt` adds `docs/TASKS.md`, `docs/PLANNING.md` and `docs/ARCHITECTURE.md` to the
  base's closed records, because their Owns lines and module map elide paths as `shared/.../X.kt`
  by convention.
- `.claude/settings.json`: the hooks stay in exec form (T99; ADR 0021, How the hooks are run). The
  base's shell form with `${CLAUDE_PROJECT_DIR:-.}` is not taken; its `refreshInterval` and the
  PowerShell matcher were already here.
- `test-hooks.js` ends with this project's group: `settings.json`'s commands and the status line run
  from a subfolder and from a path with a space (H, HS and SL cases). The base harness never reads
  `settings.json`, so the group is re-appended after each update.
- `CLAUDE.md` is this project's text (ADR 0020). The base's rule changes since `0ce6670` were read:
  its one-independent-review rule is the verifier here; "what the owner pays needs the owner" is
  already how the gate's numbers are set; its delegation wording is ADR 0026.
- Not taken, as ADR 0020 records: `.claude/agents/` and the skills that need the base's agents
  (`commit`, `decant`, `adopt-hatchery`); `debug-interface` (anchored on the base's design doc 05;
  the rule here is "every feature agent-testable" with devtool commands and debug routes); `triage`
  (the base's ideas queue is one file per item with `Plan:`, `Question:` or `Waits:` blocks, deleted
  when done; `docs/ideas/` here carries `status` and `pipeline` frontmatter instead, which is the
  owner's to align or keep); `LICENSES/`.

### What changed in behaviour

| Before (ADR 0021) | Now |
|---|---|
| Text that only mentioned a git command (an `echo`, a heredoc) was denied | Quoted text and heredoc bodies are data; denied only when the line hands them to a shell (`sh -c`, `eval`, a pipe into `bash`) |
| Malformed hook input produced a deny JSON | Exit 2 with the reason on stderr; `onFailure: block` in `settings.json` keeps it blocking |
| `git push --force-with-lease origin HEAD` from a subagent on a task branch was denied | Allowed: `HEAD` resolves to the current `task/*` branch |
| A subagent's `git pull`, `merge` or `rebase` on `main` was allowed; only `commit` was checked | Denied, `--ff-only` included: the base's rule 4 counts `pull`, `merge`, `rebase`, `cherry-pick`, `revert` and `am` as commits on a protected branch. On a task branch they are allowed, so the verifier's rebase stands |
| Cache `~/.claude/usage-state.json`; override `~/.claude/usage-gate-override.json` | One file per session, `~/.claude/usage-state/<session_id>.json`, pruned after a day; grants in `~/.claude/usage-gate-overrides/*.json` (`until`, `reason`, `meters`, optionally `session` or `project`); the legacy file is still read; `usage.pool` stays `null` here |
| `post-edit` checked caps, frontmatter and indexes | Also that a path named in a doc exists, and warns when `CLAUDE.md` passes 150 lines (the rule here stays 130) |
| The status line showed the meters | Also the account's email local part before them, and `n/a` after an account change until the next response |

Pulls of `main`. No skill has an agent pull `main`: worktrees are made from `origin/main`
(`docs/reference/machines.md`), `git fetch` is allowed, and the coordinator's own main checkout is
refreshed by the main session, which rule 4 exempts; ADR 0026 reads that as bookkeeping of its own
checkout, not running the project. A chore that needs the newest `main` runs `git fetch` and works
from `origin/main`.

### Updating

The `update-from-hatchery` skill, patch mode from the base line in ADR 0020. On a conflict under
`.claude/` the base wins except for the files listed above; `docs/` is this project's.

## Consequences

- ADR 0021 is superseded. Its rule table still describes what the policy's `git` block encodes; its
  harness and "How the hooks are run" sections are history, and the exec form stays.
- The owner's existing override file keeps working; new grants go in the directory. The old
  `usage-state.json` can be deleted once no project on the machine reads it.
- Carry-backs to Hatchery (the handoff lists them): the settings-command harness group; `--ff-only`
  pulls and merges under rule 4, since they make no commit; elided `...` paths in the anchor check.
  T113's parser fix was already in the base.
- Supersede when the base takes either git carry-back as a setting, or when `docs/ideas/` adopts
  the base's queue grammar and `triage` comes in.
