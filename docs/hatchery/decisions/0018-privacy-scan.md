# 0018, sibling: the privacy scan contract, harness group, briefs and migration

Companion to [ADR 0018](0018-personal-data-never-leaves-the-machine.md). Sections 1 to 5 are the
contract a coder builds from; section 6 the harness group; 7 and 8 the two briefs; 9 the
migration per sibling. No value that would itself be a hit appears here: every example is a
placeholder with `<seg>`, `<login>` or `<name>`.

## 1. What the scan reads

Inputs: a repository directory, a list of local refs or shas, a remote name or none, for
`--pre-push` git's remote shas and URL, the policy. The refs and remote come from the caller:
rule 20 and `--pre-push` compute them, the CLI takes them.

Refs a push sends (ported from the downstream guard's `pushSources`, unchanged in meaning): each
refspec's source (`HEAD` as itself; a glob such as `refs/tags/*` as every ref it matches, a bare
glob under `refs/heads/`; a deletion sends nothing); with no refspec, every ref when
`remote.<r>.mirror` is true, else `remote.<r>.push`, else `push.default` (`matching`: each local
branch the remote has, by `ls-remote` at its push URLs; `nothing`: none; `current`, `simple`, `upstream` or unset: `HEAD`); every
local branch for `--all` or `--branches`; every tag for `--tags` or `--follow-tags`. The remote as
git picks it: the command line's, `--repo`, `branch.<b>.pushRemote`, `remote.pushDefault`,
`branch.<b>.remote`, `origin`; a URL or path is "no remote"; a name that is not a configured remote
is an error. The repository and config an invocation acts on (`-C`, `--git-dir`, `--work-tree`,
`-c`, `--config-env`, `GIT_DIR`, `GIT_WORK_TREE`, `GIT_CONFIG_COUNT`/`_KEY_n`/`_VALUE_n`, prefixed
or exported earlier in the line) are honoured; a value the shell reader cannot see is an error.

Range: `git rev-list <refs> --not <held>`, where `<held>` is only what a remote itself confirms
it holds, never a local remote-tracking ref (amended 2026-10-19: a remote replaced by an empty
repository leaves its old `origin/main` behind, and trusting it let a whole old history out
unscanned). The target: for `--pre-push`, each remote sha git sends that is not all zeros plus
every sha `git ls-remote <remote-url>` lists (git's URL; without it a new branch would rescan the
whole history); otherwise every sha `git ls-remote` lists at each of the target's push URLs
(`git remote get-url --push --all`), only those all of them hold when there are several. The
base: every sha `git ls-remote hatchery` lists, when a remote named `hatchery` is configured and
is not the target (the base's history). A target that cannot be asked is an error for the guard
and the CLI; for `--pre-push` its `ls-remote` term is dropped with a notice and git's remote shas
alone bound it (an owner whose push asked for a typed credential is not blocked); a `hatchery`
that cannot be asked bounds nothing, with a notice. Each fallback only widens the scan. `ls-remote`
runs with `GIT_TERMINAL_PROMPT=0` and `GCM_INTERACTIVE=never`, once per URL per run. Only shas the
repository has bound the range; when a remote's branch or tag tips are missing here, a notice
says `<n> tips of <remote> are not here; fetch it to bound the range`. An annotated tag whose
object a remote holds is not scanned. No other remote is trusted: a commit on a private backup
remote is not published. With "no remote" outside `--pre-push` the target term is dropped and a
notice says the range is bounded only by the base. A commit is scanned once per run however many
refs reach it. A URL in a notice or reason shows no userinfo (up to the last `@` before the host).

Per commit, one `git log -p -U0 --cc --no-renames --format=<sentinel>%n%H%n%an%n%ae%n%cn%n%ce%n%B`
over the range (the coder picks a sentinel no message can contain, such as a NUL-prefixed line),
never one `git show` per commit. From it, in this order: author name and email, committer name and
email, the message, added lines (the `+` lines of each hunk; for a merge, `--cc` gives the lines
added against every parent), added file names (the path after `--- /dev/null`). Per annotated tag
among the refs whose tag object no remote confirmed holding (`<held>` above): `git for-each-ref`
for its message, tagger name and email. Each git call has a timeout of 20 s and a 512 MiB buffer; a failure is an error,
never a pass.

## 2. Classes, patterns, exemptions

Matching is per line of text. A hit is `{ class, where, match }`; `where` is `<sha7> message`,
`<sha7> author`, `<sha7> committer`, `<sha7> <path>:<n>` (line number in the new file),
`<sha7> file <path>`, `tag <name> message` or `tag <name> tagger`; with `--text`, `<file>:<n>`.

Lifts, applied after the class's own pass rules. `privacy.allow` (policy, tracked): a hit of any
class whose `match` (the whole matched text, lower-cased, trailing dots dropped; for `home` the
user segment) equals an entry; the tracked list is for non-personal literals, section 5. The user
allow list `~/.claude/privacy-allow.txt`: the same equality, for `word`, `host` and `home` only;
an entry that would lift another class is ignored with a notice. Both are literal strings,
case-insensitive, never patterns (`*` or `/` in an entry is malformed). `~` is `HOME`, else
`USERPROFILE`; a file absent is an empty list; a file that cannot be read is an error.

| Class | Matches | Passes when |
|---|---|---|
| `email` | `[\w.+-]+@[\w-]+(\.[\w-]+)+`, not preceded by `[\w.+-]` | the domain, lower-cased, is `example.com`, `example.org`, `example.net` or ends `.invalid`; the address is `noreply@anthropic.com` or ends `@noreply.anthropic.com`; the address is in `privacy.identities` and its domain is `users.noreply.github.com` (anywhere) or the field is author, committer or tagger (an identity's other address is a hit in a message or an added line); in an added line of `.claude/hooks/policy.json`, the file that declares it, a listed identity's address at any domain passes, and in no other file; its user part is `git`, `x-access-token` or `oauth2`; or it directly follows `http://`, `https://`, `ssh://` or `git://` (URL userinfo) |
| `home` | a user segment `[A-Za-z0-9._-]+` in one of: the drive spelling `<d>:[\\/]+Users[\\/]+<seg>` anywhere, any case; the root spellings `/home/<seg>`, `/Users/<seg>`, `/cygdrive/<d>/Users/<seg>`, `/mnt/<d>/Users/<seg>` (and the Git Bash `/<d>/Users/<seg>`) with the path start preceded by line start, whitespace, a quote (`'`, `"`, backtick), `(`, `=`, `:`, `[`, `+`, `/`, `\`, `,` or `>`; the WSL share `\\wsl$\<distro>\home\<seg>` (or `wsl.localhost`, either slash) anywhere; the project-folder slug `<d>--Users-<seg>-` and `-home-<seg>-` (also `-Users-<seg>-`, the macOS slug), preceded by line start, whitespace, a quote (`'`, `"`, backtick), `(`, `=`, `:`, `/`, `\`, `,` or `>`, where the segment ends at the first `-` because `-` is the slug's separator (so `[A-Za-z0-9._]+` there); the slug's trailing `-` is required | the segment starts with `<`, `$`, `%` or `~` (a placeholder); is a Windows system profile (`Public`, `Default`, `Default User`, `All Users`, any case) or exactly `...` |
| `host` | `<word>-Desktop` or `DESKTOP-<word>`, any case, a word character on the far side of the hyphen | never on its own; the lifts apply to the host word without the marker |
| `word` | each literal in the never set as a whole word (`\b`, any case): `os.userInfo().username`, `os.hostname()`, and every non-empty, non-`#` line of `~/.claude/privacy-never.txt`, trimmed | a literal shorter than 3 characters is skipped with a notice |
| `identity` | an author, committer or tagger whose `name` or `email` (email lower-cased, name exact after trim) is not one entry's pair in `privacy.identities` | the list is empty: class off, one notice on stderr |
| `session` | a trailer line `^Claude-Session:`; a URL whose host is `claude.ai` or ends `.claude.ai` and whose path has at least one segment | never |
| `transcript` | a line that parses as a JSON object with both `sessionId` and `parentUuid` keys (the Claude Code transcript shape; the coder verifies the key names against one real transcript line and records the check in the commit message) | never |
| `secret` | full key shapes only: `-----BEGIN [A-Z ]*PRIVATE KEY-----`; `AKIA[A-Z0-9]{16}`; `ghp_[A-Za-z0-9]{36}`; `github_pat_[A-Za-z0-9_]{82}`; `sk-ant-[A-Za-z0-9_-]{80,}`; each bounded by non-key characters on both sides; and an added file named `.env` or ending `.pem` or `.key` | never: a bare prefix in prose, a `password=` phrase and the harness's pieced samples do not match, so no file is exempt |

The scan never reads `git config user.email` or `user.name` as an exemption. The `email` class
runs on names and emails of author, committer and tagger too, so a third-party address there is
reported under both `email` and `identity`. Harness fixtures use addresses at `example.invalid`.

## 3. The CLI (promised)

```
node .claude/hooks/privacy-scan.js [--remote <name> | --no-remote] [--json | --list] [<ref> ...]
node .claude/hooks/privacy-scan.js --pre-push <remote-name> <remote-url>     # stdin: git's lines
node .claude/hooks/privacy-scan.js --text <file> [--json]
```

Defaults: refs `HEAD`; remote as git picks it (section 1). `--pre-push` reads git's
`<local ref> <local sha> <remote ref> <remote sha>` lines; for each line whose local sha is not all
zeros it scans the local sha (not the ref name, which may move) and adds the remote sha to the
`--not` list when that is not all zeros, with what `git ls-remote <remote-url>` lists (section 1);
the remote name is `<remote-name>`, "no remote" when it is a URL; `--pre-push` with `--json` is a
usage error (exit 2). `--list` scans nothing: it prints the commits of section 1's range, one
40-character sha per line, oldest first (built by the same range code, so it is exactly what the
scan reads; pusher check 6 takes its commits from it), notices on stderr, no last line; exit 0, or
2 with the `cannot` line; no policy is read; with `--json`, `--pre-push` or `--text` it is a usage
error. `--text` scans a file's lines as added lines under `<file>:<n>` (classes `email`, `home`,
`host`, `word`, `session`, `transcript`, `secret`). Policy through `HOOK_POLICY_FILE` as the hooks
do; a policy that does not load is an error.

Output, stdout: one line per hit, `HIT <class> <where>: <match>`; last line
`privacy-scan: <n> hits in <c> commits` or `privacy-scan: clean, <c> commits` (`<c> lines` with
`--text`). Notices on stderr, prefixed `privacy-scan:`. Exit 0 clean, 1 hits, 2 cannot compute,
with `privacy-scan: cannot <reason>` on stderr and no last line. `--json` prints one object:
`{ "commits": <c>, "hits": [{ "class", "where", "match", "commit": "<sha40>|null" }], "error":
null | "<reason>" }`, same exit codes. From a terminal, exit 2 is read by hand: the `cannot` line
names the repository state to fix (a commit on the branch, a configured remote, a readable
never or allow file).

## 4. Rule 20 and the pre-push hook

Rule 20, row for the hook specs table: tried right after rule 3: `push` or `send-pack` from any
caller, the main session and `--dry-run` included, when the push shares its line with any other
git invocation, or when `privacy-scan` reports a hit or an error for what it sends, with the
repository and config of the invocation; an opaque push is left to rule 19. The rule requires
`./privacy-scan.js` inside its test, not at the guard's top level, and wraps the require and the
scan in one try: any throw is this rule's deny, and no other rule or command is affected. Reason
prefix `personal data in the outgoing commits`. Three advice sentences:

- Shared line: `The push shares its line with another git command; the scan reads only commits
  that exist when it runs. Run the push on a line of its own.`
- Hits: `<n> hits, the first three: <where> (<class>: <match>); ... Fix: write ~, <repo>, a role
  or a placeholder and fold the edit into the unpushed commit (amend, or reset --soft and one new
  commit). A false positive is the owner's: ask the user, then an entry in privacy.allow (a
  non-personal literal) or ~/.claude/privacy-allow.txt (the owner's own value), from the main
  session. Nothing in a brief lifts this. ADR 0018.`
- Error or throw: `The scan could not read what this push sends (<reason>); no push leaves
  without it. Run node .claude/hooks/privacy-scan.js by hand and read its cannot line; fix the
  repository state (a commit on the branch, a configured remote, a readable never or allow file)
  or ask the user. ADR 0018.`

The scan result is cached per (repository, refs, remote) within one guard run.

`.claude/hooks/git/pre-push`: POSIX sh, executable bit set in git (`git update-index
--chmod=+x`); LF endings come from the existing `.gitattributes` (`* text=auto eol=lf`), no new
line there:

```
#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
top=$(git rev-parse --show-toplevel) || exit 2
exec node "$top/.claude/hooks/privacy-scan.js" --pre-push "$1" "$2"
```

Node absent: the hook fails and the push is refused, as decant step 1 already requires Node.
Policy: `git.hooksPath` in Hatchery's own `policy.json` becomes `.claude/hooks/git`; the base
default stays `null`. Per clone, once and in this order: the policy key, then `git config
core.hooksPath .claude/hooks/git` (decant and adopt after their policy step; existing clones by
the coordinator). Adopt first looks: `git config --get core.hooksPath` printing anything, or a file
under `.git/hooks/` not ending `.sample`, means the project already has hooks; adopt stops and
asks the owner to chain (the owner's hook runs ours, or ours is listed in theirs) or to decline
the pre-push hook (`git.hooksPath` stays `null`, the config line is skipped, the ADR 0018 pre-push
layer is the pusher's check 5 alone, written into the adoption ADR). Adopt never overwrites a hook.

## 5. Surfaces added (rows for ADR 0013's table, by this record)

| Surface | Carried by | Readers | Record |
|---|---|---|---|
| `privacy.allow`: literal strings, case-insensitive, default `["user"]`, lifts any class; non-personal literals only, owner-edited from the main session; never patterns (a `*` or `/` in an entry is malformed) | `policy.json` | `privacy-scan.js`, rule 20, pusher check 5, the reviewer's item | section 2 |
| `privacy.identities`: `[{ "name": <non-empty string>, "email": <string with one @> }]`, default `[]`; the first entry is the repository identity decant and adopt set; a `users.noreply.github.com` address is recommended | `policy.json` | the scan; decant and adopt | ADR 0018 item 4 |
| The never list: `~/.claude/privacy-never.txt`, one literal per line, `#` comments, absent is empty | the owner's file | the scan | section 2, class `word` |
| The user allow list: `~/.claude/privacy-allow.txt`, same format; lifts `word`, `host`, `home` | the owner's file | the scan | section 2, lifts |
| The scan CLI: flags, `HIT` lines, the last line, exit codes, the `--json` object | `.claude/hooks/privacy-scan.js` | pusher check 5, `pre-push`, a project reproducing a refusal | section 3 |
| The pre-push hook path `.claude/hooks/git/pre-push` and the `core.hooksPath` value `.claude/hooks/git` | the hook file; each clone's config | git; rule 14 through `git.hooksPath` | section 4 |

Not promised: the wording of notices and advice; hit ordering; harness case ids.

## 6. Harness group `PV` (in `test-hooks.js`, scratch repositories only)

Sample data is built from pieces so no line of the harness matches the scan that runs on the push
carrying it; fixture addresses are at `example.invalid`. Each case: a scratch repository with a
bare `origin`, one pushed base commit, then outgoing commits; `HOOK_POLICY_FILE` a scratch policy
with `privacy.identities` holding the scratch identity and `privacy.allow` as the case needs;
`HOME` pointed at a scratch dir (the never and user allow files).
Cases: PV01 a home path in an added line, three slash spellings, each denied; PV02 the slug
spelling `<d>--Users-<seg>-` preceded by a double quote, denied; PV03 a placeholder segment
(`<name>`, `$USER`, `~`, `Public`), allowed; PV04 an email at a placeholder domain, allowed, and
one at another domain, denied; PV05 the repository `user.email` set to an address not in
`privacy.identities`: the commit is denied under `identity` and `email` (the ruled-out exemption);
PV06 `privacy.identities` empty: PV05's repository passes with the notice; PV07 a hit in the
second of three outgoing commits, removed by the third: denied (the net-diff defect); PV08 a hit
in a commit already on a second remote named `hatchery`: allowed; PV09 a `Claude-Session:` trailer
in a message: denied; PV10 a `claude.ai` URL with a path in an added line: denied, the bare host
allowed; PV11 the never file holding a word that an added line carries as a whole word: denied,
as part of a longer word: allowed; PV12 the OS user name of the running process as a bare word in
a message: denied, and in the user allow file: allowed; PV13 a `DESKTOP-<word>` marker: denied;
PV14 an annotated tag whose message carries an email: denied on `git push origin v1`; PV15 a
`.env` file added: denied under `secret`; PV16 a transcript-shaped JSON line: denied; PV17 `git
push --dry-run`: denied like a push; PV18 a push from the main session (no `agent_type`): denied;
PV19 a remote that is not configured: denied with the error sentence; PV20 the CLI with `--json`
on PV07's repository: exit 1, `commits` 3, one hit with its `commit` sha; PV21 `--text` on a
scratch file with two classes: exit 1, two `HIT` lines with `<file>:<n>`; PV22 the `pre-push`
script on PV01's repository with git's stdin lines: exit 1; on a clean one: exit 0; PV23
`privacy.allow` holding a glob, and `privacy.identities` missing `email`: both malformed policies
(the guard denies every git command, as for any malformed policy).
Added after the review: PV24 the slug `-home-<seg>-` preceded by whitespace, and `-Users-<seg>-`
at line start: each denied; PV25 negatives: `--home-dir=<seg>-x`, a letter before `<d>--Users-`,
and a sentence quoting the form with a literal `<seg>`: allowed; PV26 a hit in a commit on a
third remote (`backup`, neither the target nor `hatchery`): denied; PV27 `git add -A && git
commit -m <m> && git push` on a clean repository: denied with the shared-line sentence, and `git
push` alone on the next line: allowed; PV28 a listed identity's address at a non-noreply domain
in an added line: denied under `email`; the same address as committer: allowed; a listed
`users.noreply.github.com` address in an added line: allowed; PV29 the user allow file holding an
`email` literal: no lift (denied) and a notice; the tracked `privacy.allow` holding the same
literal: allowed; PV30 the never file replaced by a directory: `git push` denied with the error
sentence naming the file, `git status` on the same repository allowed (the lazy require and the
try); PV31 a full `AKIA` shape: denied; a bare `AKIA` prefix in prose and a `token = "<text>"`
phrase: allowed; PV32 `--pre-push` with a remote sha that is not all zeros: a hit at that commit
passes, a hit in the commit after it is denied. Added 2026-10-19 (the range asks the remote):
PV38 origin pointed at a fresh empty bare repository with the old `origin/main` kept: the guard,
the CLI and `--pre-push` each scan every commit; PV39 an up-to-date remote holding a hit: only the
new commit is scanned, by the guard, the CLI, and `--pre-push` for an update and for a new branch;
PV40 an unreachable remote: the guard denies with the error sentence, the CLI exits 2,
`--pre-push` scans every commit (exit 0 on a clean repository) with the notice; PV41a `hatchery`
replaced by an empty repository: its stale `hatchery/main` bounds nothing, the guard denies;
PV41b the CLI with `hatchery` unreachable: exit 1 on the hit, the notice on stderr; PV42 `--list`
prints PV38's every commit and PV39's new commit only, matching the scan's count; PV43
`push.default=matching` and a branch the remote has but no local tracking ref names: that
branch is sent and its hit denied; PV44 a remote tip not fetched here: the notice; PV45 a URL
whose password holds an `@` loses its userinfo. One case in the guard's parse group:
a line whose only `git` is an argument path ending `/git` (`cat <dir>/git`) is not a git
invocation.
Group `F` runs unchanged: F1 covers the new script's own requires; the guard's lazy require is
exercised by every PV case.

## 7. Coder brief (Opus)

Goal: the scan, rule 20, the pre-push hook, the keys, group `PV`; no skill or agent edits (the
tester's). Read: ADR 0018, this file, `.claude/hooks/git-guard.js` (rules, `RULES` order,
`isGit`, `shell-parse.js` output), `.claude/hooks/policy.js` (`DEFAULTS`, `SCHEMA`, `TYPES`),
`.claude/hooks/test-hooks.js` (the GV cases' scratch repositories, `scratchPolicy`), ADR 0006.
Port, read-only with `git -C ../broodforge show HEAD:<path>`, never `cd`: that guard's
`pushSources`, `pushRemote`, `gitContext`, the exported-environment tracking its rule 20 needs,
and its `PRIVACY` expression, then apply section 2's differences (no `user.email` read; the slug
forms; the lifts; `word`, `identity`, `session`, `transcript`, the full-shape `secret`). Also:
`isGit` is tested on command-position words only (the first word of a simple command, or the word
after `sudo`, `env`, `command`, `exec` and the Start-Process form), never on an argument. Files:
new `.claude/hooks/privacy-scan.js`, new `.claude/hooks/git/pre-push`; edit `git-guard.js`
(rule 20 with the lazy require, the try, the shared-line test, the three sentences),
`shell-parse.js` if the environment tracking needs it, `policy.js`, `policy.json` (the two keys
with Hatchery's values and `git.hooksPath`), `policy.examples/*.json` (a `$comment` line only
where the example should show the keys), `test-hooks.js`, the hook specs `0002-hook-specs.md` (row
20; a section for `pre-push`), the ADR 0006 key table (two rows, living). Interface: sections 2
to 5 exactly. Dependencies: none (group `F`). Verify: `node .claude/hooks/test-hooks.js` all pass
with `PV` in the groups line; `node .claude/hooks/privacy-scan.js` on this tree exits 0 at HEAD;
the OS user name of this machine as a scratch added line is denied (PV12); `node
.claude/hooks/policy.js` prints the two keys. Report: cases added, the transcript key check, any
place the contract was silent and what was chosen. Use the Write tool for files with apostrophes.
Attribution: Claude Opus 5.5.

## 8. Tester brief (Sonnet)

Goal: the protocol text of ADR 0018 items 4, 6, 7, 8, 9, 10, 11 and the record updates; no hook or
script edits (the coder's). Read: ADR 0018, this file, each file below. Files:
`.claude/agents/pusher.md` (checks 4 and 5 as item 6; check 6's range to section 1's);
`.claude/agents/reviewer.md` (two rule lines: any class of ADR 0018 in the diff is blocking; a
change to `privacy.allow` is blocking until the entry is shown non-personal);
`.claude/skills/brief/SKILL.md` (two rule lines: item 9; no brief asks an agent to edit
`privacy.allow`, the never list or the user allow list); `.claude/skills/finding/SKILL.md` and
`template.md` (the paste rule); `.claude/skills/commit/SKILL.md` (no `Claude-Session:`; the fix
path of item 8 under Pushing); `.claude/skills/decant/SKILL.md` (the identity question in the
owner's words: "Which name and email go on this project's commits?", with the noreply address
recommended and both listed during a transition; `git fetch --prune hatchery` right after the
remote step and before any other step; the policy keys and `git.hooksPath`; the repo-local
identity config; the `core.hooksPath` line after the policy step; in the verify step `git config
core.hooksPath` prints `.claude/hooks/git`); `.claude/skills/adopt-hatchery/SKILL.md` (the same
identity question and keys in step 3; before the `core.hooksPath` line, the existing-hooks check
of section 4 with its stop-and-ask, the chain or decline outcome recorded in step 10's ADR);
`.claude/rules/docs.md` (section "Personal data", item 9); `.claude/agreement/git.md` (two
lines: every push is scanned commit by commit, on a line of its own, ADR 0018; `privacy.allow`,
the never list and the user allow list are the owner's, edited from the main session after
asking the user); `docs/reference/claude-code-on-windows.md` (item 11, `verified:` bumped); ADR
0013's header (a `Superseded in part by 0018` line naming the rows of section 5);
`docs/design/README.md` (the 0013 entry). Verify: the pusher's check 7 lines and `node
.claude/hooks/policy.js --claude-check` print no `FAIL`; `grep -rn "Claude-Session" .claude`
empty; no edited file names a sibling project. Report: files changed, lines added, any sentence
the ADR left open. Attribution: Claude Sonnet 5.5.

## 9. Migration, one line per sibling

- The downstream project with its own rule 20 and check 5a: at its next update the base's rule,
  check and scan replace them; it moves `git.push.privacyAllow` to `privacy.allow`, sets
  `privacy.identities` and `git.hooksPath`, fetches `hatchery` with `--prune`, runs the
  `core.hooksPath` line; its finding and operator entry stay its own.
- The build-phase sibling on master: takes the update with ADRs 0015 to 0018, sets the two keys
  and the hooks path, fetches `hatchery`, runs the config line; its branches carry the 28
  commits, so the owner's rewrite reaches it.
- The two adopted projects: the same steps at their next update, after the patch-update ideas in
  the handoff are fixed; each runs section 4's existing-hooks check first.
