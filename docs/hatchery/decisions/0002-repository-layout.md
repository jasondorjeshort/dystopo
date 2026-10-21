# 0002. Repository layout for agent-driven projects

Date: 2026-10-11
Status: accepted, superseded in part by 0014, 0016 and 0017

Superseded in part by 0014 (`0014-handoff-layout.md`): Session continuity's Updated line and handoff commit; the 40-line cap now also covers `docs/handoff/*.md`, 120 in total.
Superseded in part by 0016 (`0016-base-owned-and-project-owned-files.md`), 2026-10-17: the layout
gains the base-owned subtree `docs/hatchery/` (Hatchery's records), and `CLAUDE.md` becomes a
40-line project file importing the rule sections from `.claude/agreement/`. Everything else stands.
Superseded in part by 0017 (`0017-queue-directories-list-on-demand.md`), 2026-10-17: `docs/ideas/`,
`docs/operator/` and `docs/findings/` keep no per-file index ("every directory under `docs/` has a
`README.md` index", the `PostToolUse` row's missing-from-index case, "costs one index line"); a
script lists them and a marker line in each README switches the hook. Everything else stands.

Record: proposal `../proposals/0002-repository-layout.md` (with the critic's objections answered),
review `../proposals/0002-critic-review.md`. These proposals are in Hatchery's tree; a decanted
project reads them with `git show hatchery/main:docs/design/proposals/<file>`. Hook behaviour for the implementer: `0002-hook-specs.md`.
Verified against Claude Code 2.1.296. Docs cited: hooks (H) https://code.claude.com/docs/en/hooks,
sub-agents (S) https://code.claude.com/docs/en/sub-agents, skills (K) https://code.claude.com/docs/en/skills,
memory (M) https://code.claude.com/docs/en/memory, permissions (P) https://code.claude.com/docs/en/permissions.

## Context

Hatchery is forked to start projects in which Claude Code agents do all coding and a main session
coordinates. Beyond the working agreement and six agents it needs: places for knowledge and working
notes, procedures, enforcement of what prose cannot enforce, pre-approved read-only commands, and a way
to resume after an abrupt stop at the usage gate. The design follows how Claude Code loads files:
`CLAUDE.md` and its `@imports` on every session and every subagent launch (M, S); `.claude/rules/*.md`
with `paths:` only when a matching file is touched (M); skills on description match or `/name`, and by
subagents through the `Skill` tool (K, S); `docs/` never, unless a brief or an index points at it.
Scale is one owner, one coordinator session, a handful of in-flight items; Node and `gh` are present.

## Decision

### Layout

```
CLAUDE.md                     rules only, under 130 lines; imports docs/HANDOFF.md; points at docs/README.md
README.md                     for humans; decanting is the `decant` skill
.claude/settings.json         status line, hooks, permissions (committed); settings.local.json is per machine
.claude/agents/*.md           six subagents; every tools: list includes Skill
.claude/skills/<name>/SKILL.md  nine procedures, templates beside them
.claude/hooks/*.js            usage-gate (exists), git-guard, post-edit
.claude/rules/docs.md         path-scoped conventions for docs/**; rules/README.md explains the slot
docs/README.md                the map: what goes where, in the order an agent should ask
docs/HANDOFF.md               now, next, waiting on user, open findings; hard cap 40 lines
docs/backlog.md               ideas and deferred work with triggers; one file
docs/design/                  00-06, proposals/, decisions/ (unchanged)
docs/reference/               durable facts, one topic per file, verified: date in frontmatter
docs/findings/                dated, status-tagged investigation results
```

Where things go, asked in order: a rule for every turn, `CLAUDE.md` (path-scoped: `.claude/rules/`);
a procedure, a skill; a decision about what we build, `docs/design/`; a lasting fact about a tool or
environment, `docs/reference/`; an investigation result, `docs/findings/`; now or next, `HANDOFF.md`;
later, `backlog.md`. Every directory under `docs/` has a `README.md` index, one line per file. File
shapes and caps are in `.claude/rules/docs.md`: 300 lines for any `docs/**/*.md` (except
`docs/design/proposals/`, closed records that hold the objections and responses too), 40 for
`HANDOFF.md`, enforced once, by the `pusher` (check 7). No `check-docs.js`.

### Session continuity

`CLAUDE.md` imports `docs/HANDOFF.md` with `@docs/HANDOFF.md`. The import is re-read after compaction
(M: project-root CLAUDE.md is re-injected after `/compact`), needs no code and no state, and costs at
most 40 lines per subagent launch; the file's first line says it is coordinator state so subagents
follow their brief. Sections are fixed: Now, Next, Waiting on user, Open findings. No "recently done":
`git log` has it. The `handoff` skill rewrites the file; the usage gate's HOLD and STOP reasons name it.

The usage gate blocks only `Agent|Workflow` launches, never `Bash`. So wrap-up commits (`HANDOFF.md`,
WIP) are made by the main session itself at any usage level; the "runner commits" rule governs normal
work below 50%. `CLAUDE.md` says this in both the Usage gating table and the Git section.

### Skills

Nine, under `.claude/skills/<name>/`, each `SKILL.md` under 120 lines, descriptions written to trigger
on the situation: `proposal` (major-choice loop through the critic), `adr` (write or supersede, update
indexes), `handoff` (rewrite `HANDOFF.md`), `debug-interface` (per-feature agent-drivable surface),
`finding` (record and promote), `decant` (new project from Hatchery; step one verifies `node` and `gh`;
second remote is named `hatchery`), `update-from-hatchery` (merge `hatchery/main` into a decanted
project: base files win in `.claude/`, project files win in `README.md`, project sections of
`CLAUDE.md`, `HANDOFF.md`, `backlog.md`, `docs/design/`, `docs/findings/`, `docs/reference/`),
`commit` (brief the runner; brief a fresh pusher with the user's request quoted), `brief` (one template
for all agents). `README.md` opens with the two prompts that invoke `decant` and `update-from-hatchery`.
Amended by ADR 0004: a tenth skill, `adopt-hatchery`, and a patch mode in `update-from-hatchery` for adopted projects.
No `skills:` preloads: every agent has `Skill` in `tools:` and loads on demand (S:
without preloads "the subagent can still discover and invoke project ... skills through the Skill tool").
Each agent file names the skills relevant to it in one line. Rejected: `push` (lives in `pusher.md`),
`review`, `usage`, `reference`, `test`.

### Hooks (`.claude/settings.json`)

| Event | Matcher | Script | Behaviour |
|---|---|---|---|
| `PreToolUse` | `Agent\|Workflow` | `usage-gate.js` | Exists. HOLD and STOP reasons name the `handoff` skill. |
| `PreToolUse` | `Bash` | `git-guard.js`, `"onFailure": "block"` | Denies destructive git (force push, `reset --hard`, `clean`, whole-tree `checkout`/`restore`, `branch -D`, `rebase -i`, `stash drop/clear`, `filter-branch`) and pushes from any subagent other than `pusher`. Runs for subagent tool calls too (H: settings hooks "also run inside subagents"). `onFailure: block` (H, 2.1.295+) means a missing `node` blocks `Bash` loudly instead of silently disarming the guard. |
| `PostToolUse` | `Write\|Edit` | `post-edit.js` | Non-blocking. Returns `hookSpecificOutput.additionalContext` (H) when a `docs/**` file breaks a cap, a finding or reference file lacks frontmatter, or a new `docs/` file is missing from its index. Holds the empty formatter table, the one language slot in the base. |

Exact matching rules, JSON, exit codes, and a test table: `0002-hook-specs.md`.

### Permissions

Allow, all read-only (P: `Bash(git log *)` also matches bare `git log`, so bare forms are not listed):
`git status *`, `git log *`, `git diff *`, `git show *`, `git branch`, `git branch --list *`,
`git branch --show-current`, `git rev-parse *`, `git remote -v`, `git stash list`, `git blame *`,
`git ls-files *`, `ls *`, `cat *`, `head *`, `tail *`, `wc *`, `node --version`, `python --version`,
`gh --version`. `git fetch` is not listed: it writes remote refs and belongs to the pusher. Deny:
`Read(.env)`, `Read(.env.*)`, `Read(*.pem)`, `Read(*.key)`. Not pre-approved: `git add`, `git commit`,
`git push`, `rm`, `curl`, package managers. Destructive git is a hook, not a deny rule, because a deny
rule "doesn't match the same program by path or inside `sh -c`" (P) and cannot see who is calling.

### Agent memory and tracking

No `memory:` on any agent: it is a second knowledge location without an index, status, or reader other
than the agent that wrote it. Build and environment facts go in `docs/reference/`. In-flight work is
tracked in files (`HANDOFF.md`, `backlog.md`), not GitHub Issues: files are discoverable with the tools
agents already have, version with the work, and need no network or auth. If Issues ever coexist with
`backlog.md`, the file wins until this ADR is superseded. Amended by ADR 0007: `backlog.md` is now `docs/ideas/`.

### Deferred, with triggers

- `SessionStart` hook printing git log, open-finding count, usage: revisit when the import proves
  insufficient (a session resumes without knowing where it is) or the handoff needs data a file cannot
  hold. Must not write any baseline on `compact`, `clear`, or `fork`.
- `Stop` hook that blocks once per session when work happened and `HANDOFF.md` is unchanged, with
  `.claude/state/`: revisit after three or more sessions end with a stale handoff despite the usage
  gate text and the `commit` skill. Fix the critic's three holes first (baseline rewritten on compact,
  deleted handoff, `session_id` across `/clear`).
- Denying `Write|Edit` on `CLAUDE.md` and `.claude/settings.json` from subagents via `agent_type`
  (H exposes it): revisit the first time a subagent edits either without being asked.
- `memory: project` for `coder`: revisit when the same environment fact appears in two findings or
  coder briefs keep restating it; then memory holds pointers into `docs/reference/` only.
- GitHub Issues as intake: a second regular contributor, parallel coordinator worktrees, or public users.

### Not in the base

No toolchain, CI, test runner, formatter, or linter config (slots: the formatter table in
`post-edit.js`, `.claude/rules/README.md`, pusher checks 8 and 9). No `.claude/commands/` (K: legacy;
prefer skills). No `docs/active/`, `ideas/`, `future/`, `test/`, `journal/`, archive folders, root
`templates/`, issue or PR templates, `CONTRIBUTING.md`, `CHANGELOG.md`, or MCP configuration.

## Consequences

- Briefs get shorter: "read `docs/reference/README.md`" is a complete pointer. Every new file under
  `docs/` costs one index line, and the pusher refuses a push while any cap is broken.
- Every subagent launch carries up to 40 extra lines of coordinator state. The cap is what makes the
  import affordable; the pusher enforces it.
- Node is required for the status line, the gate, and both new hooks. With `onFailure: block`, a
  machine without `node` cannot run `Bash` until it is installed; the `decant` skill checks first.
- A decanted project edits `CLAUDE.md`, `README.md`, `HANDOFF.md`, `backlog.md`, and empties
  `findings/` and `reference/`; ADRs 0001 and 0002 still apply and are kept. Amended by ADR 0008:
  every Hatchery ADR and the Windows reference are kept; the project numbers its own from 0020.
- Two caps (300, 40) live in one rule file and one pusher check; hooks only warn. If a third
  enforcement point appears, move the numbers into a shared `limits.json` first.
- Every deferred item above has a trigger. When one fires, the result is a new ADR superseding the
  relevant section, not an unrecorded change to `settings.json`.
