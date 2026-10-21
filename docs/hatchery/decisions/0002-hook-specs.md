# ADR 0002 companion: hook specifications

Behaviour of the scripts under `.claude/hooks/` and of `.claude/statusline.js`, as implemented and
tested on 2026-10-13 (`node .claude/hooks/test-hooks.js`, 325 cases). The code is the reference;
this document describes it exactly enough to re-implement or extend it. What each project may choose
lives in `.claude/hooks/policy.json`, read through `policy.js`; its schema, defaults and fail modes
are ADR 0006. Every script: Node, `'use strict'`, standard library only, SPDX header, reads all of
stdin as JSON, runs under Git Bash and PowerShell on Windows and bash on Linux. Hook contract:
https://code.claude.com/docs/en/hooks; status line: https://code.claude.com/docs/en/statusline.

Facts the design relies on (from those pages): exit 0 with JSON on stdout means "the fields decide";
exit 1 is a non-blocking error; exit 2 blocks, with stderr as the reason; `hookSpecificOutput` must
wrap `additionalContext`; `agent_type` (the agent's name) and `agent_id` are present only when the
hook fires inside a subagent; a matcher such as `Bash|PowerShell` is an exact list of tool names;
`onFailure: "block"` (Claude Code 2.1.295 and later) turns a crash, timeout or missing interpreter
into a block, and without it only exit 2 blocks. The status line refreshes on events (session
start, each assistant message, `/compact`, a rate-limit reset), debounced 300 ms, and also every
`refreshInterval` seconds when that is set; `rate_limits` appears only on claude.ai plans, only
after the first API response, and each window may be absent on its own.

## 1. `git-guard.js` (PreToolUse, matcher `Bash|PowerShell`, `"onFailure": "block"`)

Input: `tool_input.command`, `tool_name`, `agent_type`, `cwd`. Caller: `agent_type` when it is a
non-empty string, otherwise the main session; an empty string counts as absent, because it carries
no identity and must not lock the main session out. A missing or non-string command allows.

Parsing (`shell-parse.js`). `tool_name` `PowerShell` selects PowerShell rules, anything else Bash.
The reader splits the line into simple commands at `;`, `|`, `&`, `&&`, `||`, newlines and
parentheses (and, in PowerShell, braces), and gives each its dequoted words. Bash: single quotes are
literal, `$'...'` takes backslash escapes, double quotes take `\` escapes, backslash escapes outside
quotes, `{` and `}` as words are grouping, `#` at a word start is a comment. PowerShell: single
quotes double a quote to escape it, double quotes and bare words take backtick escapes, `@'...'@`
and `@"..."@` here-strings, `#` and `<# #>` comments; a backslash is literal. Redirections (`>`,
`2>&1`, `*>`, `<`, ...) and their targets are not words. Bash heredocs (`<<`, `<<-`, quoted or not)
are read at the next newline; their bodies, quoted strings and here-strings are *data*. Commands
inside `$(...)`, backticks and `<(...)`, and in an unquoted heredoc's `$(...)`, are listed as
commands, because they run; their value in the enclosing word is unknown. A word is *opaque* when
it holds something the reader cannot see: a substitution, a variable (`$X`, `${F:-...}`, a `$name`
inside double quotes), a Bash brace expansion (`{-f,}`, `{1..3}`), or a PowerShell splat (`@a`).
Data becomes commands only when a runner runs in the line: `sh`, `bash`, `zsh`, `dash`, `ksh`,
`fish`, `pwsh`, `powershell`, `cmd`, `wsl`, `eval`, `iex`, `Invoke-Expression`, `Invoke-Command`,
`Start-Process`, `xargs`, `source`, `.` or `[scriptblock]::Create`, as a command word or after a
wrapper (`sudo`, `env`, `xargs`, `timeout`, `nohup`, `Start-Process`, ...); a runner's name as a
plain argument runs nothing. Then every data string is parsed with both dialects, three levels
deep. So `git commit -m "fix git push -n"`, a heredoc commit message and `echo "git push --force"`
allow, while `bash -c "git reset --hard"` and `echo "git push -f" | sh` are read as the git commands
they run. `Start-Process git -ArgumentList "push --force"` (or `"push","--force"`) is reassembled
into the git command it starts.

Data is all or nothing: once a runner appears, every quoted string and heredoc body in the line is
read as commands, even one that only `echo` or `cat` holds, because which text reaches the runner
(a pipe, a loop or function body, `tee >(sh)`, a file written and then run) cannot be told from the
line. So `cat <<'EOF'` with a `git push origin $B` body, beside a `| xargs ...` pipeline, is rule 19's;
split such a call in two.

Commands git runs. The command of every `git rebase --exec <cmd>`, `--exec=<cmd>`, `-x <cmd>` or
`-x<cmd>` (each repeat, and `--exec` abbreviated) is a command line of its own, read whether or not
a runner appears: parsed as Bash, because git hands it to `sh`, its own data and runners read as
above, and every rule applied with the line's caller. A deny quotes the inner command and carries the
note. A substitution of the outer line inside it (`-x "git push $(...)"`) makes the word holding it
opaque. A reassembled `Start-Process` command is read the same way; its `-ArgumentList` string splits
at whitespace and commas, with double quotes grouping. The `--exec` of `push` and `send-pack` names a
program on the remote and is not read.

Depth. Data and rebase commands are read three levels deep. Text still unread past the third level
fails closed: it becomes one invocation with an unknown subcommand, which rule 19 denies with advice
that the shells nest deeper than the guard reads. Known limit: commands read from data or a rebase
command are judged in the directory the line ends in; a `git -C <dir>` around them is not followed.

Locating git. In each command every word whose base name is `git` or `git.exe` (any case) starts an
invocation; a word that is a path ending in it (`/usr/bin/git`, `<dir>/git`) does so only when the
word after it is an option or a git subcommand, so `cat <dir>/git` and `<var>/.claude/hooks/git
<arg>` are file names while `timeout 10 /usr/bin/git push -f` is git. Words after it that start with `-` are global options; `-C`, `-c`,
`--git-dir`, `--work-tree`, `--namespace`, `--config-env` and `--super-prefix` take the next word,
and `--opt=value` is split. The next word is the subcommand; a git word with nothing after it is not
an invocation. Arguments are normalised: a short cluster becomes single flags (`-nm x` is `-n -m`);
an abbreviated long option of four or more characters becomes every guarded option it starts
(`--har` is `--hard`, `--forc` is each `--force*`), because git accepts any unambiguous prefix; and
each subcommand's value-taking options drop their value (`VALUE_OPTS` in the code), so `-m "-n"` and
`-mnote` are a message, not `-n`, and `-m "add $f"` is not opaque. An invocation is opaque when a
remaining argument is, when a `-c`/`--config-env` value is, or when `xargs` runs it (xargs appends
arguments from stdin). A command whose command word is opaque, in a line that names git, is an
invocation with an unknown subcommand, unless it is a PowerShell assignment (`$h = git ...`) or the
word ends in a literal file name that is not git (`$(dirname "$0")/check.sh`).

An invocation is *first* when only `NAME=value`
words or prefix words (`sudo`, `env`, `time`, `then`, `do`, ...) precede it and it did not come
from data. Its directory is the input `cwd`, moved by earlier `cd`, `pushd`, `Set-Location` or
`sl` commands in the line and then by `-C`; Git Bash `/c/...` resolves as `C:/...` on Windows.

Policy. The guard loads the policy once. A malformed file denies every command that contains a git
invocation, with reason `git-guard: policy file is malformed (<error>): `<text>`. Every git command
is denied until it is fixed; ask the user.` and allows commands without one.

Rules, tried in order, each over every invocation in source order. "Branch patterns" match a whole
name with `*` matching anything, `/` included. "Targets" of a push are the destinations of its
refspecs (`HEAD` and no refspec mean the current branch, read with `git rev-parse --abbrev-ref
HEAD`); `--all`, `--branches`, `--tags`, `--mirror` and `--follow-tags`, a detached HEAD or an
unreadable branch match no pattern list but `*`.

| # | Denies ... | Reason prefix |
|---|---|---|
| 1 | `push` or `send-pack` with `--force`, `--force=...`, `-f` or `+<refspec>`; or `--force-with-lease`/`--force-if-includes` unless `git.push.forceWithLease` lists patterns matching every target of a `push` | `force push` |
| 2 | `push` or `send-pack` with `--mirror` or `--prune`; or `--delete`, `-d` or `:<ref>`, unless every deleted branch matches `git.branchDelete.branches` and, when `requireMergedPr`, GitHub shows a merged PR from it | `remote branch deletion` |
| 3 | `push` or `send-pack` from the main session when `git.push.mainSession` is false; from a subagent not allowed by `git.push.subagents`; from an allowed subagent when `git.push.branches` is not `["*"]` and some target matches none of them | `push not permitted for this caller` |
| 4 | `commit`, `cherry-pick`, `revert` or `am` from a subagent not allowed by `git.commit.subagents`, or on a current branch matching `git.commit.protectedBranches`; `pull`, `merge` or `rebase` the same under `git.integrate`, each of whose keys left `null` (the default) takes `git.commit`'s value, so by default all seven are judged alike; none with `--abort` or `--quit`. The advice is the base's until a project sets the deciding `git.integrate` key apart; a commit refused on a branch the caller may integrate adds that a merge or rebase in progress there ends with `git merge --continue` or `git rebase --continue` | `commit not permitted for this caller` |
| 5 | `reset` with `--hard` or `--merge` | `destructive reset` |
| 6 | `clean` without `-n` or `--dry-run` | `git clean deletes untracked files` |
| 7 | `checkout` whose pathspec (words after `--`, else the last argument) covers the whole tree: `.`, `./.`, `*`, `:/`, `:(top)`, or a path that `..` walks back to the start (`src/..`, `..`) | `whole-tree checkout discards edits` |
| 8 | `restore` with one of those pathspecs (after `--`, else any non-dash argument), unless `--staged`/`-S` without `--worktree`/`-W` | `whole-tree restore discards edits` |
| 9 | `branch` with `-D`, or a delete flag and a force flag, unless the branches pass the rule-2 test | `forced branch delete` |
| 10 | `rebase` with `-i`, `--interactive` or `--interactive=...` | `interactive rebase is unsupported here` |
| 11 | `stash` whose first positional is `drop` or `clear` | `stash drop/clear is unrecoverable` |
| 12 | `filter-branch`, `filter-repo`, `update-ref`, `replace` unless it is `-l` or `--list` with at most one pattern and no opaque argument (a listing), or `reflog expire`/`delete` | `history rewrite` |
| 13 | `--no-verify` on `commit`, `push`, `merge`, `pull`, `rebase`, `am`, `cherry-pick` or `revert`; `commit -n` (`push -n` is `--dry-run` and allowed) | `hooks bypass` |
| 14 | any `GIT_CONFIG`, `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM` or `GIT_CONFIG_PARAMETERS` assignment in the line (prefix, `export` or `$env:`); `core.hooksPath`, `include.*` or `includeIf.*.path` set by `-c`, `--config-env` or `GIT_CONFIG_KEY_n`; `git config` writing `include.path`/`includeIf.*.path`, or unsetting `core.hooksPath` or setting it to anything but `git.hooksPath` (null: any value). A read allows. | `hooks path override` |
| 15 | `alias.*` set by `-c`, `--config-env` or `GIT_CONFIG_KEY_n`, or `git config alias.<x> <value>` | `git alias definition` |
| 16 | `checkout` with `-f`/`--force`; `switch` with `-f`, `--force` or `--discard-changes` | `forced checkout discards edits` |
| 17 | `worktree remove` with `-f` or `--force` | `forced worktree removal` |
| 18 | `checkout -B <b>`, `switch -C`/`--force-create <b>`, or `branch -f <b>` (not deleting, moving or copying) when `<b>` is the current branch or matches `git.commit.protectedBranches` | `forced branch reset` |
| 19 | an invocation with an unknown subcommand; or an opaque invocation of `push`, `send-pack`, `reset`, `checkout`, `switch`, `restore`, `clean`, `branch`, `worktree`, `stash`, `config`, `reflog` or a rule-4 subcommand. Other subcommands (`log`, `diff`, `add`, ...) stay allowed. Text past the depth limit counts as an unknown subcommand. | `unexpanded argument` |
| 20 | tried right after rule 3: `push` or `send-pack` from any caller, the main session and `--dry-run` included, when the push shares its line with another git invocation, or when `privacy-scan.js` reports a hit or an error for the commits the push sends (an opaque push is left to rule 19). The require and the scan sit in one try: any throw is this rule's deny, and no other rule is affected. Advice: shared line, run the push on a line of its own; hits, fix the text and fold the edit into the unpushed commit, a false positive is the owner's via `privacy.allow` or the user allow file; error, run the scan by hand and read its `cannot` line. | `personal data in the outgoing commits` |

The merged-PR check runs `gh pr list --head <branch> --state merged --json number --limit 1` in the
invocation's directory, once per branch; any failure counts as not merged. Test seam:
`GIT_GUARD_MERGED`, comma-separated branch names, replaces the call.

Deny output, exit 0. The advice is the rule's own sentence, or, for rules 1 to 4, 9, 14 and 18, a
sentence built from the policy (which agents may act, which branches); rule 19's says the guard
cannot see what the expansion becomes:

```json
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny",
 "permissionDecisionReason":"git-guard: <reason prefix>: `<command text>`. <advice>[ <note>]"}}
```

The command text is the simple command's source, cut at 200 characters. The note is appended when
the invocation is not first: `git-guard reads the command line, not what runs: it counted this git
word as a command because it is unquoted or sits in text handed to a shell. If nothing here runs
git, tell the user it is a false positive.` Decision: the note asks for a report rather than a
rewording, so a deny message never teaches the reader how to get past the guard.

Failure: any exception, unparseable stdin (empty included) or a missing module writes `git-guard:
internal error (<message>); command not run. Fix the hook or ask the user to run it.` to stderr and
exits 2. Decision: exit 2 blocks on every Claude Code version; `onFailure: "block"` stays as the
second layer for a crash before the handler is installed or a missing `node`.

Not covered, by design: aliases already in the user's git config; scripts that run git; another
interpreter running git (`node -e "...execSync('git push -f')"`, `python -c "os.system(...)"`),
whose code is text to the guard; `gh pr merge --delete-branch`; and `branch -f` onto a branch that
is neither current nor protected (the reflog keeps the old commit).

Test groups: G01 to G25 and GX01 to GX25 are the original table (G24 now allows: quoted text is
data); GN covers rules 12 to 17 and lease-as-force; GQ the parser in both shells; GR the shapes an
independent review reproduced as bypasses on 2026-10-13 (abbreviated options, opaque arguments,
PowerShell launchers, commit-making subcommands, config by environment and include, forced branch
resets, `send-pack`, whole-tree spellings), with the false positives they must not cause; GE
`git rebase --exec` and `-x` in both shells (repeats, `--exec=`, `-x<cmd>`, a shell or a
substitution inside, the depth limit at three and four levels, `Start-Process` with one argument
string); GA `git replace --list` (an audit false positive of 2026-10-17) and the shapes rule 19 keeps
denying, text held by `echo`, `cat` or a message while a runner can reach it (pipes, loop and
function bodies, groups, `tee >(sh)`, `| & iex`, a file written then run), which any exemption for
held text must still deny; GF
malformed and absent policy files (GF07 and GF08 a malformed `git.integrate`); GI the
`git.integrate` entry (a subagent's `pull`, `merge --ff-only`, `merge`, `rebase` and their
`--continue` allowed on `main` under the task-branches example while `commit`, `cherry-pick`,
`revert` and `am` stay denied there; the default's behaviour and wording with no key set; each key
set apart); GP one command set under the Hatchery policy, no policy, and
the two examples, in scratch repositories on `main` and on `task/T1`, which shows the absent file
behaving as the
Hatchery one. LIVE01 to LIVE04 check that the repository's own `policy.json` and the examples
load and that the harness's Hatchery fixture equals the loader's defaults; LIVE05 and LIVE06 that
`policy.js` prints `git.integrate`. H, HS and SL run every command hook of the repository's own
`.claude/settings.json` and its status line from a subfolder with no `.claude/`, as Claude Code runs
them (exec form without a shell, the placeholder substituted; shell form through Git Bash, PowerShell
when it is absent or the hook asks for it, or `sh -c`), in this checkout and in a copy under a path
with a space; each must name its script through `${CLAUDE_PROJECT_DIR}`, and H00 and SL00 are the
relative-path controls that fail there; H99 checks that the three hooks are named. Group F is the
reference fitness check of ADR 0013 (the dependency budget): F1 runs it on this repository and F2 to F6 run it
on scratch trees (a forbidden require, a skip entry present and absent, a vendored file with and
without its licence line). It counts a `require('<m>')` literal wherever it appears in a scanned
file, comments and strings included, by design. Group CB is the carry-back fitness check of ADR 0019:
CB1 checks that every author and committer on `main` is in `privacy.identities` or lifted by a
`privacy.allow` entry, and SKIPs where a `hatchery` remote exists; its FAIL line gives the count and
the first bad sha on a separate `first:` line.

Four cases read this repository instead of a scratch tree: F1 (`.claude/hooks/fitness.json` and the
files it names), O1 (the tracked paths against `ownership.json`), A1 (the line count of
`.claude/agreement/`) and CB1 (the history of `main`); groups H, HS and SL read its
`.claude/settings.json` (an absent one skips them). Every other case runs in a scratch tree: the
Hatchery policy is a fixture inside the harness, written there, and the docs it checks (a 41-line
handoff included) are synthetic. The harness needs
`.claude/statusline.js` beside the hooks. A project that copies `.claude/hooks/` and sets its own
policy and fitness config runs it unchanged.

Debug interface: `node .claude/hooks/test-hooks.js` runs every case for all hooks, printing a
`PASS`, `FAIL` or `SKIP` line per case plus a summary (a skip is not a failure); exit 1 on any
failure, 2 on a usage error. Flags: `--only <id-prefix>` (`--only deps` runs the reference check
alone, F1),
`--verbose`, `--keep`, `--input <file> <hook>` (reproduce one input from a file; `statusline` is
accepted as a hook name), and with `usage-gate` or `statusline` `--home <dir>` (HOME and
USERPROFILE set to it, `CLAUDE_CONFIG_DIR` removed) and `--config-dir <dir>` (`CLAUDE_CONFIG_DIR`
set to it).
`node .claude/hooks/policy.js [file]` prints the effective policy or the error;
`node .claude/hooks/policy.js --cap-check <listfile>` is pusher check 7's per-file cap line (one
`FAIL <path>: N lines, cap C` per file over its cap, a `FAIL policy:` line for a policy that does
not load, exit 1 on any `FAIL`; stdout always ends with `cap-check: <N> files, <M> over cap`, and a run without that line is a FAIL). Group CAP runs it on a scratch tree and the pusher's line through
the guard.

## 2. `post-edit.js` (PostToolUse, matcher `Write|Edit`, no `onFailure`)

Input: `tool_input.file_path` (non-empty string, else nothing happens), `cwd`. Decision: the project
root is the input `cwd`, falling back to `process.cwd()`; `CLAUDE_PROJECT_DIR` is not used, because
`cwd` is what a relative `file_path` resolves against and the harness drives a scratch tree through
it. "Inside" means the path relative to the root does not start with `..`. The docs checks run only
for files inside, under `docs/`, ending in `.md`; the root `CLAUDE.md` has its own cap check. The
formatter runs for any file whose lower-cased extension has one. Never exits non-zero; any
exception is swallowed. On Windows the `cwd` must be a Windows path (`C:/...`); a Git Bash
`/c/...` path resolves to a missing file and the hook stays silent. A malformed policy means the
base defaults here, since the hook only advises.

`CLAUDE.md` at the root over 150 lines: `post-edit: CLAUDE.md is N lines; cap is 150
(.claude/rules/docs.md). Move facts to docs/reference/ and path-scoped rules to .claude/rules/.`
An `@` line of the root `CLAUDE.md` whose target is absent: the import message below.

Checks on `docs/**/*.md` (`<path>` from the root with forward slashes; line count as `wc -l`):

| Check | Message (one line each, joined with newlines) |
|---|---|
| over 300 lines, except under `docs/design/proposals/`, paths matching `edit.capExempt`, and the two handoff paths of the next row (`docCap` in `policy.js`, shared with pusher check 7) | `post-edit: <path> is N lines; cap is 300 (.claude/rules/docs.md). Split by topic; the pusher will refuse to push.` |
| `docs/HANDOFF.md` or `docs/handoff/*.md` over 40 lines, `edit.capExempt` notwithstanding | `post-edit: <path> is N lines; cap is 40. Move detail to docs/ideas/ or a finding.` |
| when the edited file is either, `docs/HANDOFF.md` plus every `docs/handoff/*.md` on disk (`.md` only) over 120 lines in total | `post-edit: the handoff loads N lines in total (docs/HANDOFF.md plus docs/handoff/*.md); cap is 120. Close a stream or trim.` |
| a `docs/handoff/*.md` with no line `@handoff/<name>` in `docs/HANDOFF.md` (this replaces the README row for that directory, which has no README) | `post-edit: <path> is not imported by docs/HANDOFF.md. Add the line @handoff/<name> or delete the file.` |
| a line starting `@<target>`, outside fenced blocks, in `CLAUDE.md`, `docs/HANDOFF.md` or `docs/handoff/*.md`, whose target does not exist when resolved from the file's directory | `post-edit: <path> imports @<target>, which resolves to <resolved> and does not exist (an import resolves from the importing file's directory).` |
| `docs/findings/*.md` (not README) missing frontmatter `date:`, `status:` or `area:` | `post-edit: <path> lacks finding frontmatter (date, status, area). See the finding skill.` |
| `docs/reference/*.md` (not README) missing `verified:` | `post-edit: <path> lacks verified: frontmatter. See .claude/rules/docs.md.` |
| a file at least one directory below `docs/` (not `docs/handoff/*.md`, see the import row above), basename not `README.md` or `HANDOFF.md`, named in no `README.md` from its own folder up to `docs/<top>/README.md`, unless it is `docs/<dir>/<file>.md` and `docs/<dir>/README.md` carries the marker line of ADR 0017 (`0017-queue-list.md`, section 4) | `post-edit: <path> is not listed in <folder>/README.md. Add one line.` |
| `docs/<dir>/<file>.md` under a README with the valid marker for `<dir>`: each problem `read(<dir>, file)` of `queue-list.js` returns (a value outside the grammar that would list as `?`), after the frontmatter row | `post-edit: <path> would list as ? (<field> is "<value>"; allowed: <set>). See docs/<dir>/README.md.` (`<field> missing; allowed: <set>` for an absent value) |
| the same file when the marker is valid but `queue-list.js` does not load, or its `read` throws (the index check is skipped too) | `post-edit: queue-list.js did not load; <path> was not checked.` |
| an edited `docs/<dir>/README.md` (three segments) with a line starting `Listed on demand:` that is not the marker for `<dir>` (wrong mode word, wrong shape); one message per README; deeper READMEs are not read | `post-edit: docs/<dir>/README.md carries a Listed on demand: line that is not "Listed on demand: node .claude/hooks/queue-list.js <dir>"; the directory counts as indexed.` |
| a backtick-quoted token holding `/` whose first segment is a top-level directory of the root (not `.git`), that does not exist | `post-edit: <path> names paths that do not exist: <list>. Fix them, or mark the paragraph [no-check: <reason>] (.claude/rules/docs.md).` |

Path anchors skip files matching `edit.anchorExempt` (by default the closed records under
`docs/design/proposals/`, `docs/design/decisions/` and `docs/findings/`, which name paths as they
were), fenced code blocks (indented ones too), any paragraph (blank-line separated) or list item
containing `[no-check:` (a list item scopes it even without blank lines between items), and tokens
with `*`, `<`, `>`, `{`, `}`, `$`, `NNNN` or `YYYY`, and elided paths, a token with a segment that is
exactly `...` (`src/.../X.kt`, a task board's shorthand); a `#anchor` or `:line` suffix is dropped
before the lookup. Each missing path is listed once.

Frontmatter means the file starts with `---`, closes with `---` within 20 lines, and each named key
starts a line between them. Lines split on LF or CRLF.

Formatters: `edit.formatters` in the policy, keyed by lower-case extension with the dot, value an
argv array; the file's absolute path is appended. Test seam: a JSON object in
`POST_EDIT_FORMATTERS_JSON` is merged over them. Run as `spawnSync(argv[0], [...argv.slice(1), abs],
{ timeout: 20000 })`, no shell, so on Windows an npm `.cmd` shim cannot be the command. Failure is a
spawn error or a non-zero status: `post-edit: formatter <argv[0]> failed on <path>: <detail>`, the
first 5 non-empty stderr lines, else the spawn error, else `killed by <signal> (timeout 20 s?)`.

Output with at least one message, exit 0:
`{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"<messages>"}}`; none:
exit 0, no stdout. Harness cases P01 to P04 and PX01 to PX14 cover the checks above, the
formatter and the `CLAUDE.md` cap; PA the path anchors, closed records, list-item scope and elided
paths (PA09); PC cap exemptions and formatters from a policy file, a malformed policy, and the task-branches example;
PH the handoff paths (ADR 0014): the stream and total caps, the map import, and the `@` line
resolution, each case in its own scratch root (`ph-<id>`) so P02 keeps its tree; Q the queue
listing and the marker switch (ADR 0017): `queue-list.js` run from scratch roots (Q01 to Q09) and
the three hook messages above (Q10 to Q13, Q13 on a scratch copy of the hooks whose
`queue-list.js` throws). The hook reads the README with CR removed, so a CRLF marker line is valid;
it loads `queue-list.js` only when that README holds a `Listed on demand:` line, uses its exported
`MARKER`, and keeps a copy of the regular expression for the case where the script does not load.

## 2b. `git/pre-push` (a git hook, not a Claude Code hook)

`.claude/hooks/git/pre-push`, POSIX sh, executable in git. Enabled per clone by `git config
core.hooksPath .claude/hooks/git`, the value `git.hooksPath` names (rule 14 allows exactly that
value). git passes the remote name and URL as arguments and the refs on stdin; the script finds the
top level and runs `privacy-scan.js --pre-push "$1" "$2"` with stdout redirected to stderr (`>&2`),
so its exit status is the scan's: 0 clean, 1 hits, 2 cannot compute; any non-zero refuses the push.
Git for Windows 2.56 shows only a hook's stderr, so without `>&2` the `HIT` lines are lost
(`docs/findings/2026-10-17-git-for-windows-hides-pre-push-stdout.md`). Node absent refuses the
push. The CLI and its output lines: ADR 0018's scan sibling, section 3. Harness group `PV`.

## 3. `usage-gate.js` and the status line

Moved on 2026-10-16 to `0002-hook-specs-usage.md`, still section 3: the per-session cache, the
account label, the status line's text and flags, the gate's thresholds, staleness, grants and pool
advice, and the harness cases U, UO, UG, UX, UP and S. Other records that cite "section 3" of the
hook specs mean that file.
