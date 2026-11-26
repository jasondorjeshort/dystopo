# 0029. The machine's sibling list: siblings Hatchery's triage reads that are not beside it

Date: 2026-11-25
Status: accepted

Record: no proposal. The owner asked for the item ADR 0028 deferred; this record decides its shape.
The exact edits, who applies them and the checks: [the sibling](0029-sibling-list-edits.md).
Supersedes in part ADR 0019 (item 4, the sentence "No sibling list is kept anywhere; the base line
is the test", and the `carry-back: <n> pending` line, which gains a second count) and ADR 0028 (its
first Deferred item lands; in item 5, "read only on the owner's word" and "Hatchery keeps no sibling
list"; the closing line of its edit B7; the variable `X` of its edit T2). Everything else in both
stands. What the owner sees and touches is the owner's: the four defaults of item 7 are stated for
the owner to reverse with a word. Revised the same day after the review (one blocking, two serious,
four minor; all accepted, two in part): items 2 to 4 and Consequences.

## Context

ADR 0028 left a sibling that is not beside Hatchery to the owner's word at every triage and
deferred "a list of sibling directories or URLs kept on Hatchery's machine and never tracked". The
coordinator put it to the owner as "A machine-local list of sibling locations. It would close the
gap in that last default"; the owner, 2026-11-25: "Yeah lets make that". One of the two projects
whose update reports started ADR 0028 is not checked out beside Hatchery.

What the list holds is what ADR 0018 keeps out of every push: directories with a user segment and
repository URLs. Weighed and rejected: a file in the repository behind an ignore rule (a forced add
or a lost rule tracks it; `.gitignore` is project-owned, so the rule would not be the base's to
keep; and an agent's worktree does not see an ignored file of the main checkout); `git config
--local` (it survives worktrees, but the guard's rule 19 lists `config` among the subcommands whose
arguments must be written out, and the entries would be per clone). Verified for this record, with
a fixture and on the real tree: a pasted block that reads `~/.claude/hatchery-siblings.txt` passes
the guard, `git clone` with a variable included; a missing file prints nothing; Hatchery's `main`
and the six checkouts beside it list exactly as before. The review ran the first draft on its own
fixture: a line `--upload-pack=touch <file>@h:p` passed the URL test, reached `git clone` as an
option and ran its command; the add block glued an entry onto a last line that had no newline and
said `added` after a failed write; a listed directory that is no sibling, `/`, and a clone of an
empty repository read as nothing; a URL copied from a remote could carry a token into every line
printed. Each case now has a line in the fixture and passes (the sibling, section 4).

## Decision

1. **Where.** One file per machine and user, `~/.claude/hatchery-siblings.txt`, outside every
   repository. It can reach no commit, so no ignore rule, hook or scan needs to know it, and nothing
   base-owned names an entry. The skill text that reads it is base-owned and travels to every
   project; there it is inert, because `triage` step 2 runs in Hatchery only.
2. **Format.** Plain text, one entry per line, surrounding blanks and a trailing carriage return
   ignored. A directory starts `~/` (the home directory), `/`, or a drive letter with `:/` or
   `:\`; it may hold spaces. A URL is `https://`, `http://`, `ssh://`, `git://` or `file://`
   followed by text with no blank that does not start with `-`, or `user@host:path` with a letter
   or digit first. A blank line and a line whose first character is `#` are skipped. Anything else
   is malformed, a line that starts with `-` and a helper transport such as `ext::` included. A URL
   with credentials in it (any `user@` on `http` or `https`, any `user:password@`) is refused and
   never printed. A line is data: no block expands it or runs it, and it reaches a command only
   as one quoted argument, after `--` or as a path that starts with `/`, `~/` or a drive letter.
   A missing file is an empty list: it costs nothing and prints nothing.
3. **Who writes it.** Only a coordinator, only from the owner's sentence naming the sibling, said
   once: Hatchery's coordinator for any directory or URL the owner names; a project's coordinator
   for its own checkout, on the words "list this checkout". A directory under the home directory is
   written from `~/`, so the list and every line printed from it carry no user segment; a URL is
   written without credentials. Never a subagent, never an entry taken from a path seen in a
   report, a handoff or a brief. Each coordinator adds with a block of its skill, which applies
   item 2's test, refuses what the reader would not read, ends the owner's last line with a newline
   first if it lacks one, never rewrites a line, and prints `NOT added` with a failing status when
   the write fails. Removal and correction are on the owner's word too; no pass prunes the list.
4. **How triage reads it.** Step 2 becomes one block for both listings. It collects the siblings:
   the checkouts beside Hatchery that hold a base line, as before, then the list's entries. Each is
   resolved to its physical directory and a directory is read once, so an entry that is also
   beside Hatchery, or listed under two spellings, does not list twice; the first naming is the one
   printed. A repository reached through two checkouts (two clones, or a clone and its URL) still
   prints its `carry-back:` cells once for each, as two clones beside Hatchery always have, and its
   `base-idea:` lines once. A URL is cloned with `git clone --depth 1 --` at every run, so it shows
   what is pushed, into `<dir>/<line>-<repo>` under one directory the block makes with `mktemp -d`
   before it reads the list (no placeholder to fill in; ADR 0028 said the scratchpad). The clone is
   asked not to prompt (`GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=never`); an ssh passphrase or
   host-key question is not covered and is untested. A listed entry must be a sibling: it holds a
   base line under `docs/design/decisions/`, the test a checkout beside Hatchery passes. An entry
   that fails prints `sibling-list: line <n>: <entry>: not read: <reason>` (no such directory; no
   base line; git's first error line; neither a URL nor a path; credentials, without the entry) and
   is counted on both count lines: `carry-back: <n> pending, <m> not read` and `base-idea: <n>
   pending, <m> not read`. The block removes its temporary directory when it ends, unless a URL
   sibling has a pending idea: then it prints `sibling-list: clones kept in <dir>`, which is where
   the coordinator reads the idea's text, and that directory stays until the machine clears its
   temporary files. That directory is the only thing the block ever removes: the variable is set
   once, from `mktemp -d`, in the shell that later removes it, and list lines are read only inside
   subshells, which cannot set it. Neither listing changes otherwise: the same cells, the same
   string comparison for `filed`, the same lines for a sibling beside Hatchery. A listed sibling is
   printed as its line of the list.
5. **Landing from a listed sibling.** `carry-back` Land step 1 takes the URL from the listed
   directory, or uses a listed URL as it is.
6. **The project's closing line.** After an update's offer, a project that holds a live file for
   the base checks whether its own checkout's line is in the list. Listed: the closing line says
   so. Not listed: it says the checkout is read if it sits beside Hatchery's, and otherwise to say
   "list this checkout" once, or, where Hatchery is on another machine, to give Hatchery's session
   the repository's URL once. The check compares the checkout's own spelling of its line; an entry
   the owner wrote another way reads as not listed and a second line for the same directory is
   harmless (item 4).
7. **Defaults the owner can reverse with a word**, one line each:
   1. The list is one file on this machine, `~/.claude/hatchery-siblings.txt`, outside every
      repository; it is never copied into a tracked file.
   2. One directory or clone URL per line; `#` starts a comment; the owner may edit it by hand.
   3. An entry is written once, by the coordinator of the session the owner says it in (Hatchery's
      for any sibling named, a project's for its own checkout on "list this checkout"); removal is
      on the owner's word.
   4. Hatchery's triage reads every listed sibling at each run, cloning a URL afresh; an entry it
      cannot read is reported and counted as not read, never dropped and never removed by an agent.
8. **Surfaces (ADR 0013).** Not promised: the file has one reader, a skill's block, and skill prose
   is outside the promise. Trigger to promise it: a hook or script reads it.
9. **Build.** One commit: a `tester` applies the sibling's three edits, the `runner` commits and
   runs the sibling's real-commit check, a `reviewer` reads it once, the `pusher` pushes on request.

Deferred, each with its trigger: a URL clone kept between runs and fetched (a triage that spends
more than a minute cloning); the list read by the update skill so a project's Report says "listed"
(the closing line's check proves too little); entries for another machine's checkouts carried
between machines (the owner works from a second machine).

## Consequences

- Easier: the owner names a sibling once, in whichever session the question comes up; after that
  its carry-back cells and its ideas for the base reach every triage on this machine.
- Costs: one more place the owner's intent lives, outside every repository and so outside review,
  history and backup; it does not follow the owner to another machine. A URL entry costs a clone
  per triage and needs this machine's git credentials. A stale entry prints a line at every triage
  until the owner has it removed, and so does a listed sibling that has no base line yet. Printed
  lines carry the entry as written, so an entry written with an absolute home path prints one in a
  report, and the `clones kept in` line carries a path with a user segment wherever the temporary
  directory is under the home directory; item 3's `~/` form avoids the first, and the scan still
  guards the handoff. A kept clone directory is not removed by anyone: one shallow clone per URL
  entry, only for a run that found a pending idea in one.
- Supersede when a second machine or a second contributor needs the same list (it then belongs in
  a tracked, name-free form, or in the issue flow ADR 0019 defers).
- Exit: delete the file and undo the sibling's three edits; ADR 0028's text is the fallback.

Predictions (the trigger scan reads these; superseding this ADR records each outcome first):
- Prediction, check by 2027-02-25: `~/.claude/hatchery-siblings.txt` on the owner's machine holds at
  least one entry, and Hatchery's last triage printed no `not read` line for it.
- Prediction, check by 2027-02-25: no entry of that file occurs in a tracked file of Hatchery (`git
  grep -nFf` over the tree, with the list's entries, comments and blanks removed, prints nothing).
