# 0018. Personal data never leaves the machine: one scan, three callers, roles in committed text

Date: 2026-10-17
Status: accepted, superseded in part by 0020 and 0021

Item 10, item 6's trailer sentence, item 4's noreply recommendation: [ADR 0020](0020-attribution-trailers-and-the-web-committer.md).
Item 4's identity rules (the list as the whole set, an empty list switching `identity` off, the
global identity never read) and item 6's pusher as the one who pushes: [ADR 0021](0021-guardrails-bound-damage.md).

Record: no proposal (not a listed major choice); reviewed independently on 2026-10-17 with
scratch-repository experiments, the nine findings and the identity claim folded in. Evidence:
`../../findings/2026-10-17-personal-data-leak-protocol-gap.md`. Contract, harness group and briefs:
[the sibling](0018-privacy-scan.md). Amends ADR 0006's key table and ADR 0013's surfaces (item 4).

## Context

A downstream project pushed a machine user name inside home paths five times (a hook test case, an
idea quoting a refused path, a handoff line). Its pusher compared only the net diff of the push, so
a fix on top made the range look clean, and nothing read what each commit carried. Hatchery has the
same shape: check 5 reads `git diff @{u}..HEAD` for secrets only, check 4 a subject and a trailer,
rules 1 to 19 the command line and never what a push sends; the brief and docs rules say nothing
about user names, emails or people in committed text; the finding skill invites pasted output; the
reviewer has no privacy item. Hatchery's own public history carries the owner's second account
(as the owner later confirmed), name and email, as author and committer on its first 28 commits
(the machine's global git identity, which no step set or checked), two first names in one finding,
and a `Claude-Session:` trailer with a session link in 25 messages, which no check matched.

The downstream fixes (a per-commit pusher check, a guard rule on every push) are sound in shape and
carry two defects a straight copy would bring: both exempt the repository's `git config user.email`,
the very identity that leaked, and the expression misses the project-folder spelling of a path
(`C--Users-<seg>-...`, as Claude Code writes under `~/.claude/projects/` and in every scratchpad
path), a bare user name, and a host name without a desktop marker.

The review added: `--not --remotes` lets a commit on any other remote escape and, when decant adds
the base remote without a fetch, puts the whole base history in range; a rule that scans before a
compound line (`git add && git commit && git push`) creates its commit misses that commit, the path
the leak took; loose `email` and `secret` expressions with no lift hit harness strings ten times
here; a personal value lifted by a tracked allow entry is published by the entry; adopt's
`core.hooksPath` line silently disables a project's own hooks; a top-level require of the scan
would block every git command on a crash. Each is answered in the item it concerns.

Ruled out: a check over the net diff; exempting the committer email; trusting the global identity;
a rule that spawns the scan off the push path. Verified 2026-10-17: nothing in this tree writes the
`Claude-Session:` trailer; the harness already builds scratch repositories with a bare `origin`;
`fitness.json` covers every `.claude/hooks/*.js`; `.gitattributes` already sets `eol=lf`.

## Decision

1. One scan, three callers. `.claude/hooks/privacy-scan.js` (CommonJS, Node built-ins only) is
   both a module and a CLI. The guard's rule 20 requires it lazily, inside its own test; pusher
   check 5 runs the CLI; `.claude/hooks/git/pre-push` runs it for pushes from a terminal or script.
2. What it reads. For every commit in `git rev-list <refs the push sends> --not
   --remotes=<target>`, plus every ref of the `hatchery` remote in the `--not` list when that
   remote exists (the base's history is published; no other remote is trusted, since a commit on a
   private remote is not): author and committer name and email, the whole message, every added
   line against each parent, every added file name; and each annotated tag sent, its message and
   tagger. Never the net diff. Decant fetches `hatchery` with `--prune` before anything else.
3. Classes, each with a one-word label the hit carries: `email`; `home` (a home path with a user
   segment, in the drive, root, cygdrive, mnt and project-folder-slug spellings); `host` (a
   desktop marker); `word` (the machine's own user name and host name, read from the OS at run
   time, and every literal in `~/.claude/privacy-never.txt`, as whole words, any case); `identity`
   (an author, committer or tagger not in `privacy.identities`); `session` (a `Claude-Session:`
   trailer, or a `claude.ai` URL with a path); `transcript` (a Claude Code transcript line);
   `secret` (full key shapes only: a provider prefix plus its fixed length, or a `BEGIN ...
   PRIVATE KEY` block; never a bare prefix or a `password=` phrase). Placeholder domains never
   hit: `example.com/.org/.net`, any domain ending `.invalid`, a listed `users.noreply.github.com`
   address; harness fixtures use `@example.invalid`. Patterns and exemptions: the sibling, section 2.
4. Lifts, three files of literals. `privacy.allow` (policy, tracked, default `["user"]`) lifts a
   hit of every class and is for non-personal literals only (a service account named after the
   project, a word the pattern mistakes): a personal value lifted there is published by the entry.
   `~/.claude/privacy-allow.txt` (user scope, never tracked) lifts `word`, `host` and `home` for
   the owner's own values; `~/.claude/privacy-never.txt` adds literals to `word`.
   `privacy.identities` (policy, `[{ "name", "email" }]`, default `[]`, which switches `identity`
   off with a notice) names who may author, commit or tag; a listed email is exempt from `email`
   in those three fields only, never in a message or an added line. The committer email is never
   exempt on its own. The recommended identity is a tracked `users.noreply.github.com` address;
   during a transition the owner lists both addresses. Decant and adopt ask the owner for the
   identity, write it into the policy, and set the repository's `user.name` and `user.email` from
   its first entry; the global identity is not read.
5. The guard. Rule 20, tried right after rule 3, on `push` and `send-pack` from every caller, the
   main session and `--dry-run` included; refs and remote as git picks them (the sibling, section
   1). A push sharing its line with any other git command is denied before the scan runs: the scan
   reads only commits that exist, so the push goes on a line of its own. The require and the scan
   run inside the rule; a range it cannot compute, or any throw, denies with the rule's own
   sentence naming the lift, and no other rule is touched. Fail mode as the guard's: closed.
6. The pusher. Check 4 becomes: each message has a subject, a `Co-Authored-By:` trailer, and no
   `Claude-Session:` trailer. Check 5 becomes: run the CLI over the outgoing range (exit 0 or
   stop), then read every outgoing commit, not the net diff, for a person's name other than a
   listed identity, a phone number, a street address, an account or subscription identifier, a
   recorded conversation. A hit the CLI finds that the guard let through is a hook bug: stop.
7. The pre-push hook. `.claude/hooks/git/pre-push` (POSIX sh, so Git for Windows runs it) calls
   the CLI with `--pre-push`; a nonzero exit refuses the push. The base's policy sets
   `git.hooksPath` to `.claude/hooks/git` (rule 14 then allows exactly that value); decant, adopt
   and every existing clone set the policy key first, then run `git config core.hooksPath
   .claude/hooks/git` once, from the main session. Adopt stops first at a project with its own
   `core.hooksPath` or a non-sample hook: the owner chooses to chain the hooks or to decline this
   one; no hook is replaced unasked. `--no-verify` from a terminal still bypasses it; the owner's act.
8. Lifting a refusal. A true hit is fixed by editing to `~`, `<repo>`, `../<dir>`, a role or a
   placeholder and folding the edit into the unpushed commit (`--amend` for the last; `reset
   --soft <last pushed sha>` and one new commit for several; rules 10 and 12 stay). The `identity`
   class: set the repository identity, `--amend --reset-author`. A false positive is the owner's:
   an entry in one of item 4's three lists, from the main session after asking the user, named in
   the commit message when it is the tracked one. Those files are owner-only by rule (the
   agreement and the brief skill); the reviewer reads any change to `privacy.allow` as blocking
   until the entry is shown non-personal. Nothing in a brief lifts a hit; the pusher has no lift;
   the guard has no grant. "Cannot compute" from a terminal: run the CLI by hand and read its
   `cannot` line; the fix is repository state, never a bypass.
9. Committed text. `.claude/rules/docs.md` gets a section "Personal data": tracked text (docs,
   messages, tests, fixtures, hook examples) never carries a home path with a user segment in any
   spelling, an email or a person's name other than a listed identity (roles instead: the owner,
   the coordinator, a downstream project's owner), a host name, a session link, a recorded
   conversation or a grant file's content; paths are written `~`, `<repo>`, `../<dir>` or
   `<scratchpad>`. The brief skill keeps absolute paths in briefs (not committed) and says a
   tracked file an agent writes follows that section. The finding skill's paste rule: output goes
   in as classes, counts and `file:line`, scrubbed; a value that would itself be a hit is never
   quoted, in a finding or a report. The reviewer's item: any class above in the diff is blocking.
10. The `Claude-Session:` trailer is dropped: the commit skill's message ends at the
    `Co-Authored-By` lines; the scan denies it wherever it comes from. The 25 are history, below.
11. The reference. `docs/reference/claude-code-on-windows.md` states that every absolute path,
    scratchpad and project-folder slug on a Windows PC carries the machine user name, as does a
    grant's `project` scope.

Out of scope, the owner's separate steps (an operator entry each): the history rewrite of the 28
root commits and the trailers, which starts at Hatchery, reaches every project that cloned it and
re-records each base line; and whether Hatchery goes private until it lands. This record prevents
a recurrence; it does not clean history.

Not covered, by design: pull-request titles and bodies and `gh repo create --push` (no PR flow in
the base; trigger: the first project that adopts one); a `--no-verify` terminal push; names of
people the never list does not hold (the pusher's read and the reviewer's item). Deferred: a
write-time warning from `post-edit.js` using `--text` (no new contract: the CLI accepts `--text
<file>` already); trigger: the first hit the scan reports in this tree after it ships.

Amended 2026-10-18 by ADR 0019: the PR trigger fired (PR 1 in Hatchery's repository, from a
sibling) and the decision is that the base has no PR flow: a carry-back is a branch in the
sibling's own repository, landed as a fresh Hatchery commit the scan reads on Hatchery's push.
Text that leaves the machine through `gh` (a PR closing comment; an issue or comment if the owner
later allows issues) is written to a file and passed through `--text` before the `gh` call, a
skill step and no hook, since `gh` is outside the guard.

## Consequences

- Every push from every caller is read commit by commit before it leaves, and the three readers
  agree because they are one script; a difference between the pusher and the guard is a bug.
- The tracked identity is a noreply address by recommendation, so the policy publishes nothing
  the commits do not; an owner who lists a real address publishes it in one more place, by choice
  at decant or adopt, and it is exempt only in that place (the added lines of
  `.claude/hooks/policy.json`) and where it already stands (author, committer, tagger).
- Costs: a hook script and a sh hook to keep; a harness group; a per-clone config step at decant
  and adopt; git runs on every push (one timeout per call); a push on a shared line is re-run
  alone. The `word` class can hit a common word when a user name is one; the user-scope allow
  file is the answer, the tracked one only for a non-person.
- A hit shows the matched text to the agent that was refused; its report may carry only the class
  and location (item 9). The paste rule is what keeps a refusal from becoming the next leak.
- The owner's history rewrite (identity and session links) runs `git filter-repo` with
  `--preserve-commit-hashes`: filter-repo translates a hash quoted in a message only when that commit
  is in the repository being rewritten, so a base message quoting a sibling commit would come out
  different in that sibling, and every shared base commit after it would get a different hash.
- Supersede when Claude Code passes hooks a parsed push plan, or when a project needs a PR scan.

Predictions (the trigger scan reads these):
- Prediction, check by 2027-04-17: no commit on any remote of this tree made after this ADR
  carries a hit of any class above; no refusal was lifted by an allow entry for a personal value.
- Prediction, check when the next sibling update runs: each sibling takes rule 20, check 5 and the
  keys by the migration line alone, with no edit to the scan.
