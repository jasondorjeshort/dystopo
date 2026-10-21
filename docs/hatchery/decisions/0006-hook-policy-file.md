# 0006. Hook policy lives in one file beside the hooks

Date: 2026-10-13
Status: accepted, superseded in part by 0016 and 0017
Superseded in part by [0016](0016-base-owned-and-project-owned-files.md), 2026-10-17: the sentence
under the key table that keeps the 150-line `CLAUDE.md` cap a constant in `post-edit.js` (the
project file's caps are 40 and 190, in `policy.js`, lifted by `edit.capExempt` naming `CLAUDE.md`),
and the policy file is never patched rather than kept on conflict; the living key table gains
`update.exclude` in place, as its own rule allows. Everything else stands.
Superseded in part by [0017](0017-queue-directories-list-on-demand.md), 2026-10-17: "only
exemptions are policy" gains one exemption that is not: the index check for a queue directory is
switched off by a marker line in that directory's own README, never by a key, so the key table
gains nothing. Everything else stands.

The key table and "The HOLD override" section are living: they are amended in place as keys and
formats change, and keep the provenance notes a decision record carries (the projects a key came
from): the no-project-names rule is for living docs copied into projects, and this record stays a
closed record even where a table in it is amended. The rest of the record stays as accepted.

Record: proposal `../proposals/0005-rules-that-bit-downstream.md` (option B, and its two extra
items: the PowerShell matcher and the base harness as a contract); Dystopo's ADRs 0020 and 0021
(its alignment with the base and its `git-guard` exceptions); Dystopia's `CLAUDE.md`, section
Git & Commits. The proposal is in Hatchery's tree; a decanted project reads it with
`git show hatchery/main:docs/design/proposals/<file>`. Behaviour of each script: the companion `0002-hook-specs.md`. ADR 0007 is the docs
half of the same sitting.

## Context

Three projects run these hooks. Hatchery is the base; Dystopo was its source and has since moved
ahead; Dystopia commits straight to `master` and pushes through one designated agent. Each
differs in who may commit, who may push, which branches may be deleted, where the usage lines sit
and which docs are exempt from the 300-line cap. Dystopo held those differences as edits inside
the scripts (`PROTECTED`, `isTaskRef`, `HOOKS_PATH`, `CAP_EXEMPT`, its own gate at 75% and its own
cache file name). That made every `update-from-hatchery` patch a conflict on the project's own
choices, and the base fell behind because improving it meant re-applying each project's edits.

Other faults found while merging, all fixed by this change: the guard split command text on spaces,
so a commit message containing `git` or ` -n` could trip a rule and every deny carried a note
about quoted text; the base matched the Bash tool only, so git run through PowerShell went
unchecked; the guard relied on `onFailure: "block"`, which older Claude Code lacks, so a crash there
was non-blocking; the status line wrote nulls over good numbers whenever `rate_limits` was absent;
the gate's thresholds were duplicated in the status line.

## Decision

### The file

`.claude/hooks/policy.json`, read by `.claude/hooks/policy.js` for `git-guard.js`, `usage-gate.js`,
`post-edit.js` and `.claude/statusline.js`. The scripts hold mechanism; the file holds choices. The
base ships one reproducing its own behaviour; `.claude/hooks/policy.examples/` holds one example
per model seen downstream (task branches, single branch, per-meter thresholds), each with a
`$comment` saying what it encodes and none naming its project, since the examples ship with the base.

| Key | Type | Base value | Meaning |
|---|---|---|---|
| `git.commit.subagents` | `"any"`, `"none"` or agent names | `["runner"]` | Subagents that may commit. The main session always may. |
| `git.commit.protectedBranches` | branch patterns | `[]` | No subagent commits while the current branch matches. |
| `git.integrate.subagents` | as `git.commit.subagents`, or `null` | `null` | Subagents that may `pull`, `merge` or `rebase`, which integrate a branch rather than write a commit of their own; `null` takes `git.commit.subagents` (Dystopo, its ADR 0036: a subagent integrates `main` and resolves the conflicts, a plain commit there stays refused). |
| `git.integrate.protectedBranches` | branch patterns, or `null` | `null` | No subagent pulls, merges or rebases while the current branch matches; `null` takes `git.commit.protectedBranches`, so a project that sets neither key judges the three as commits, as before the key. |
| `git.push.subagents` | as above | `["pusher"]` | Subagents that may push. |
| `git.push.branches` | branch patterns | `["*"]` | Every branch a subagent's push updates must match. |
| `git.push.mainSession` | boolean | `true` | Whether the main session pushes. |
| `git.push.forceWithLease` | branch patterns | `[]` | `--force-with-lease` is allowed, for anyone, when every target matches; otherwise it is a force push. |
| `git.branchDelete.branches` | branch patterns | `[]` | `branch -D` and `push --delete` are allowed only for these. |
| `git.branchDelete.requireMergedPr` | boolean | `false` | Also require a merged pull request on GitHub (`gh`, fails closed). |
| `git.hooksPath` | path or `null` | `null` | The one value `core.hooksPath` may be set to; `null` allows none. |
| `privacy.allow` | literal strings, case-insensitive; a `*` or `/` is malformed | `["user"]` | Lifts a hit of any class of the privacy scan whose match equals an entry. Non-personal literals only; edited by the owner from the main session (ADR 0018, scan contract section 2). |
| `privacy.identities` | list of `{ "name", "email" }`, each a non-empty string, the email with one `@` | `[]` | The identities allowed as author, committer or tagger; the first is the repository identity decant and adopt set. Empty turns the `identity` class off with a notice on stderr (ADR 0018). |
| `usage.hold` / `usage.deny` | percent / percent or `null` | `50` / `null` | No new subagents at `hold`; stop at `deny`, a ceiling no override lifts. `null` means no deny line: HOLD is the only line, and the override can lift it up to and past a saturated meter. `hold` may not exceed a `deny` that is set. |
| `usage.fiveHour` / `usage.sevenDay` | `{ "hold", "deny" }`, each percent or `null` | both `null` | Per-meter thresholds; `null` inherits `usage.hold` / `usage.deny`, so a meter has no deny line when its own and the shared `deny` are both `null`. Each meter is judged by its own pair and the worse verdict wins (ReGenesis, its ADR 0004). |
| `usage.staleSeconds` | seconds | `7200` | Older usage data is stale. |
| `usage.whenStale` | `"allow"` or `"deny"` | `"allow"` | What the gate does with stale or missing data. |
| `usage.overrideEnv` | variable name or `null` | `null` | An environment variable that, set to `1` when the session starts, lifts HOLD on both meters for that session (never the deny line): the launch-time form of a session grant (The HOLD override). |
| `usage.pool` | `null` or `{ "command": argv, "timeoutSeconds": integer 1 to 60 }` | `null` | A command run once, without a shell, only when the gate is about to deny at HOLD; its stdout is read as the `cswap list --json` contract and the deny names another account with room, or says there is none or no reading (ADR 0011). `timeoutSeconds` defaults to `15`. Hatchery's own file sets `["cswap", "list", "--json"]`. |
| `usage.holdAdvice` / `usage.denyAdvice` | text | the base's wording | The sentence after the threshold in each deny. |
| `edit.capExempt` | path patterns | `[]` | Paths exempt from the docs caps; `CLAUDE.md` by exact name also exempts the project file's 40 and the loaded 190 (ADR 0016), so a broad pattern never lifts them unmeant. |
| `update.exclude` | path patterns | `[]` | Base-owned paths an update from Hatchery never brings: files the project removed or replaced for good. A departure from a hook or skill is a departure-table row, not an exclude (ADR 0016). |
| `edit.anchorExempt` | path patterns | proposals, decisions, findings | Docs whose backtick-quoted paths are not checked: closed records that name paths as they were. |
| `edit.formatters` | extension to argv | `{}` | Formatter per file extension, e.g. `".rs": ["rustfmt"]`. |

Branch and path patterns match the whole name; `*` matches anything, `/` included. A missing key
takes the base value, so a project writes only what differs. Keys starting with `$` are ignored at
any level. An unknown key or a value of the wrong type makes the file malformed, so a typo such as
`requireMergedPR` is an error rather than a silent default. `node .claude/hooks/policy.js` prints
the effective policy or the error.

The file is the project's. `update-from-hatchery` keeps the project's copy on any conflict;
`adopt-hatchery` sets it at adoption. Caps that are conventions of the base itself (300, 40 and
150 lines) stay constants in `post-edit.js`; only exemptions are policy.

### Fail modes

| Script | File absent | File malformed | Script crashes |
|---|---|---|---|
| `git-guard` | base values | denies every command containing a git invocation, naming the error; other commands pass | exit 2 with the reason on stderr; `onFailure: "block"` stays as a second layer |
| `usage-gate` | base values | base values, error on stderr | exit 1: non-blocking, so the launch goes ahead |
| `post-edit` | base values | base values | swallowed; no output |
| status line | base values | base values | flags omitted; the line still prints |

Why the split. A wrong git command can be irreversible (a force push, a deleted branch) or
invisible (a skipped git hook), so the guard fails closed, and a policy it cannot read is a policy
it cannot honour. An unwanted subagent costs quota, which the status line shows and the user can
stop; a broken gate that blocked every launch would stop all delegation for a fault in a budget
check. So the gate fails open and has no `onFailure`. The same reasoning sets `whenStale` to
`allow` in the base; a project that would rather stop than guess sets `deny`, and then delegation
waits until a session's status line has written fresh data (the hook never runs `claude -p`).

### The HOLD override

Scoped grants replaced the single file on 2026-10-15, on the owner's decision, after one lift
written for the base on 2026-10-13 also lifted its two siblings (the idea
`override-file-is-machine-wide`, resolved by this amendment). A single file holding a list of grants
was ruled out: two sessions editing it read-modify-write over each other, and a session-only grant
could clobber another session's.

Grants. A grant is one JSON file in `~/.claude/usage-gate-overrides/`: every `*.json` directly in
that directory is a grant, other names and subdirectories are ignored, and an absent directory is
no grants. `{"until": <epoch seconds>, "reason": "<who asked and why>", "meters": ["7d"],
"session": "<session id>"}`. `until` is required on every grant; the grant lifts HOLD (never the
deny line) until then, `until` at or before now is expired, and the gate prints the reason each
time a grant is used. `meters` (`"5h"`, `"7d"`; both when absent) names the meters it lifts, so a
user who says to ignore the weekly meter and follow the session one gets exactly that; a HOLD on a
meter not named still denies, and says which meters the grants lift. An unknown meter name,
`"meters": null` or `"meters": []` makes the grant malformed, lifting nothing (2026-10-13, Dystopo's
T94, with `usage.overrideEnv`). Scope is at most one key: `"session": "<session id>"` or
`"project": "<absolute path>"`; neither key is user scope; both keys, a value that is not a
non-empty string, or a relative `project` is malformed. `~/.claude` is one user's, so the widest
scope is the user, not the machine. The legacy single file `~/.claude/usage-gate-override.json` is
still read, by the same rules as a directory file, so a legacy file with no scope key is a user
grant; writers write only to the directory. The gate never writes or deletes a grant.

Matching. The gate parses the PreToolUse payload on stdin for `session_id` and `cwd`;
unparseable stdin is an empty payload. A session grant applies when `session_id` equals its
`session`; with no `session_id`, no session grant applies. A project grant applies when `cwd` is
its `project` or under it: both are compared after every `\` becomes `/`, a trailing `/` is
dropped, and, on Windows (`process.platform` is `win32`), both are lower-cased; under means the
cwd begins with the project path followed by `/`. Symlinks and junctions are not resolved. Matching
is textual against the resolved `cwd` Claude Code supplies (the gate resolves no symlink or `..`),
so a session that moves into another directory holding its own project grant is covered by that
grant. A `project` is judged absolute by the host's rules, so a Windows drive path read on Linux or
macOS is malformed and lifts nothing. With no `cwd`, no project grant applies. A user grant applies to every session. Each file is classified
once, in this order: malformed, else expired, else out of scope, else it applies; the meters lifted
are the union over every grant that applies. A HOLD deny names each grant that does not apply, with
its scope (an override exists for session such-and-such, not this one; for project such-and-such)
or as malformed, lists every expired file in one clause, and points here rather than teaching the format, because a deny
message is read by the agent that was refused. Exact grammar: `0002-hook-specs.md`, section 3.

Writing a grant. Only on the owner's explicit instruction, by the coordinator from the main
session, as a new file: `session-<id>.json`, `project-<slug>.json` or `user.json`. The owner's
bare "OVERRIDE" is a session grant; project or user scope takes the owner's words. Whoever writes
a grant first deletes every expired grant file in the directory, so leftover files never pile up
in each deny; the gate never deletes, the coordinator does at write time. The session id
is `CLAUDE_CODE_SESSION_ID` in the Bash tool's environment, the same id the hook receives as
`session_id`. `until` is the time the owner gives, else about twelve hours ahead for a session
grant, and for a project or user grant the weekly meter's `seven_day.resets_at` in this session's
usage file, `~/.claude/usage-state/<session_id>.json` (ADR 0011), else twelve hours. `reason` names who asked and the scope they gave.
`usage.overrideEnv` is the launch-time form of a session grant: set in a policy file, and its
variable set when the session starts, only on the owner's instruction, it lifts HOLD on both meters
for that session and nothing else.

With no deny line, the base since 2026-10-13, a grant lifts every HOLD, including on a meter at
100%; a project that forbids overage sets `deny` and keeps a ceiling no scope lifts. A deny of 100
is not the same as none: it trips exactly when a meter saturates, with no lift.

### Test seams

`HOOK_POLICY_FILE` names another policy file, `GIT_GUARD_MERGED` replaces the `gh` call, and
`POST_EDIT_FORMATTERS_JSON` adds formatters. They are read from Claude Code's environment, which a
command run through a tool cannot change; `test-hooks.js` sets them per case.

### Module type

The scripts are CommonJS, and `.claude/package.json` says so (`"type": "commonjs"`). Without it, a
project whose root `package.json` declares `"type": "module"` makes Node load them as ES modules,
`require` throws, and the guard's exit 2 then blocks every shell call (seen in Dystopia on
2026-10-13, the day the hooks were first installed there).

### Deferred

- Per-rule advice text for git rules 5 to 17 stays in the script, worded for any project. Revive
  when a project needs different wording, as an `advice` map keyed by rule number.
- The pusher agent's check 7 kept its own list of cap exemptions in `.claude/agents/pusher.md`.
  Revived 2026-10-16, when two projects had exempted files: check 7's first line runs
  `node .claude/hooks/policy.js --cap-check <listfile>`, which reads `edit.capExempt` through
  `docCap` in `policy.js`, the rule `post-edit.js` also uses; a policy that does not load is a
  `FAIL` there, not the defaults.
- The gate still reads the legacy `~/.claude/usage-gate-override.json`. Drop that read, and its
  harness cases, once every project that copied the gate has taken the 2026-10-15 version.

## Consequences

- An update from the base patches scripts without touching any project's choices, and a project
  changes its rules by editing one JSON file that the harness can run against.
- The three projects can be compared by diffing two small files. Dystopo's ADR 0021 exceptions
  E1 to E5, A1 and A2 are `task-branches.json`, and the GP group of the harness runs them.
- In `single-branch.json` only `pusher` pushes among subagents: Dystopia's push cards start a fresh
  agent of that type, and every other subagent commits but never pushes.
- Dystopo's in-flight relaunch override (`DYSTOPO_GATE_OVERRIDE=1`) became the policy key
  `usage.overrideEnv` (2026-10-13), so its gate is the base script unchanged; its example has no
  deny line, so the override file can lift its 75% line on either meter or both.
- The guard now reads quoted text as data. Text handed to a shell (`sh -c`, `eval`, `| bash`,
  `powershell -Command`) is still read as commands. What the reader cannot see (a variable, a
  substitution, a brace expansion, xargs input) is denied on any subcommand that can discard work
  or bypass hooks, so `git checkout "$BRANCH"` needs the name written out; `git log $(...)` still
  runs. Still unguarded: aliases already in the user's git config, scripts, and other interpreters
  running git (`node -e`, `python -c`).
- A malformed policy stops all git work until it is fixed. That is deliberate and loud, and
  `node .claude/hooks/policy.js` says what is wrong.
- More moving parts: four scripts share a loader and the guard has a shell reader of a few hundred
  lines. The harness is what keeps them honest; a change to either needs its cases.
  It reads no file of the repository it runs in (its Hatchery policy is a fixture inside it, its
  docs a scratch tree), so it passes unchanged in every project that copies it; the project's own
  `policy.json` is checked only for loading.
- Supersede when Claude Code passes hooks a parsed argv for shell tools (the reader could go), or
  when proposal 0003 settles a branch model that wants policy keys this file lacks.
