# 0028, sibling: the exact edits, who applies them and how they are checked

[length: the exact old and new text of one pass's edits to six files, read start to finish]

Companion to [ADR 0028](0028-brainstorm-after-update.md). A `tester` applies every edit below with
the Edit tool, in this order, and decides nothing: each `Old` block is in the named file at commit
5d5d113 verbatim and once (leading spaces included), and is replaced by its `New` block. Nothing
else in a file changes. The `runner` commits the six files as one commit, a `reviewer` reads it
once (the rule file puts it in the full lane), and the `pusher` pushes on the owner's request.
Section 7 is the check. The `New` blocks of B7, U3, U4, H1, T1, T2, T3 and C1 are as revised after
the review (ADR 0028, Context); T0 is the loop fix the tester made in the same pass.

## 1. `.claude/skills/brainstorm/SKILL.md`

B1, the description (frontmatter, one line; replace the fragment only). Old:

```
Use after a triage, at a wave close or a release, when work sparks an idea worth keeping,
```

New:

```
Use after a triage, at a wave close or a release, after every update from the base (two groups, for the base and for this project's workflow), when work sparks an idea worth keeping,
```

B2, the opening paragraph. Old:

```
An offer is at most nine ideas put to the owner after a triage, at a wave close or a release, when
work sparks one, or when the owner asks. Nothing is written unasked: an idea nobody asked for and
```

New:

```
An offer is at most nine ideas put to the owner after a triage, at a wave close or a release, after
every update from the base (the section "After an update from the base"), when work sparks one, or
when the owner asks. Nothing is written unasked: an idea nobody asked for and
```

B3, the same paragraph's last two lines. Old:

```
queue they already read. Decision and reasons: ADR 0026 and its sibling file `0026-brainstorm-contract.md`, in
`docs/hatchery/decisions/`.
```

New:

```
queue they already read. Decision and reasons: ADR 0026 and its sibling file `0026-brainstorm-contract.md`, and
ADR 0028 for the offer after an update, in `docs/hatchery/decisions/`.
```

B4, who reports candidates. Old:

```
at any usage level. A subagent that ran a triage reports up to nine candidates, one line and an
anchor each, and the coordinator makes the offer.
```

New:

```
at any usage level. A subagent that ran a triage or an update from the base reports up to nine
candidates, one line and an anchor each, and the coordinator makes the offer.
```

B5, Procedure step 4. Old:

```
   asks for in so many words is always made and does not count. The offer is made at HOLD too. At
```

New:

```
   asks for in so many words, and the offer after an update from the base, are always made and do
   not count. The offer is made at HOLD too. At
```

B6, Formats, the Source line's trigger words. Old:

```
  owner, <context>`. The trigger is named in public terms: a triage, a wave close, a release, a spark.
```

New:

```
  owner, <context>`. The trigger is named in public terms: a triage, a wave close, a release, an update, a spark.
```

B7, a new section before the README check. Old (the heading line alone):

```
## README check
```

New:

````
## After an update from the base

In a tree with a base line (`^Hatchery base: ` in `docs/design/decisions/`), every update that
reaches its commit (`update-from-hatchery` skill, step 10) ends in an offer. Hatchery itself has no
base line and never runs the update, so this section never applies there. An update that stopped
before its commit makes no offer. The procedure above holds, with these differences:

1. Candidates. Whoever ran the update lists up to nine, one line and an anchor each, each marked
   `base` or `project`; a subagent puts them in its report (the update skill, Report). The
   coordinator adds its own from that report and the incoming list. The anchor decides the group: a
   base-owned or mixed path (`.claude/hooks/ownership.json`) is for the base; a project-owned or
   base-excluded path (the project's `CLAUDE.md`, `.claude/project/`, its policy file, its own
   skills, its docs) is for this project's workflow. What the product should do is neither and
   waits for the next triage or wave close.
2. When and who. The coordinator makes the offer in the turn in which it reports the update to the
   owner, at HOLD too, never at STOP. It does not count as the session's one prompted offer, and a
   session that already made one still makes this one. With no candidate the offer is its header
   alone, and its record says `offered 0`.
3. Shape. One offer, at most nine lines in all, under two headings in this order: `For the base
   (kept here until the base files them):` and `For this project's workflow:`. A group with no line
   prints `none` under its heading. When more than nine pass the cheap check, each group keeps at
   least four lines if it has them. Within a group near-term lines come first; `Moonshot.` still
   closes a moonshot's Source line. The header's second sentence counts by group:

   ```
   Ideas: 12 open, 1 resting, 0 offered and unanswered (at the session's start); bar step 1: an
   anchor in the tree. After the update 6939e79..5d5d113: 3 for the base, 2 for this project's
   workflow; 1 entry on an existing file; held back: 1, ask to see them.
   ```

4. The bar applies to the project's group as it stands. A line for the base shows steps 1 and 2
   only (an anchor; no existing file says it), whatever the open count: it leaves this queue when
   the base files it.
5. What an answer writes: as Procedure step 5, and the Source line and the record name the trigger
   `an update`. A file recorded from the base's group takes a title that starts `For the base: `
   (`# For the base: <the claim>`) and no block; the file of a line left unanswered takes the same
   title above its offer Question. A fold or an entry is written as ever.
6. Closing line, whenever the tree holds a live file for the base (below): `For the base: <n> kept
   here. Hatchery's triage lists them when this checkout sits beside Hatchery's; elsewhere, tell
   Hatchery's session this directory or its URL.`

The title prefix `For the base: ` marks an idea this project leaves to the base: its anchor is a
base-owned or mixed path and the project will not build it. Any offer or filing in a tree with a
base line may write it; a defect seen in a base file and filed as before takes it too, unless the
project is fixing it itself. Such a file is live while it has the prefix and no block (in the
listing, `title` carries the prefix and `block` is `untriaged`). A live file is not triage's and no
pass builds it. Hatchery's triage lists the live files and Hatchery's coordinator files each as its
own idea (`carry-back` skill, opening paragraph); the next update here finds that commit and
deletes the file, unless a decision record here still names it (`update-from-hatchery` skill, step
8). A project that decides to build one itself
removes the prefix: the file is then an ordinary idea, and the built change goes back by the
`carry-back` skill (Send).

## README check
````

B8, Bounds, the last bullet. Old:

```
- No agent is launched to brainstorm. At STOP nothing is offered or written.
```

New:

```
- No agent is launched to brainstorm. At STOP nothing is offered or written.
- A file titled `For the base: ` is deleted only by the update that finds the base's filing commit,
  or on the owner's word.
```

## 2. `.claude/skills/update-from-hatchery/SKILL.md`

U1, the description (frontmatter, one line; replace the fragment only). Old:

```
resolving conflicts by the ownership map and the departure table. Use when asked
```

New:

```
resolving conflicts by the ownership map and the departure table. Ends with the brainstorm offer (the `brainstorm` skill). Use when asked
```

U2, step 6. Old:

```
   - `unclassified`: did not arrive; file a carry-back idea for Hatchery to classify.
```

New:

```
   - `unclassified`: did not arrive; file an idea titled `For the base: ` (`brainstorm` skill) for
     Hatchery to classify.
```

U3, step 8, its last line. Old:

```
   would drop out of every later diff unseen. On a first update the ADR is step 7's.
```

New:

````
   would drop out of every later diff unseen. On a first update the ADR is step 7's.
   Then the ideas this project leaves to the base (`brainstorm` skill, "After an update from the
   base"), in Bash from the root, with the sha written out:

   ```
   repo=$(basename -s .git "$(git remote get-url origin 2>/dev/null || pwd)"); E=$(mktemp)
   F=$(git log <sha> --format='%x01%h%n%B' | awk 'index($0, "\001") == 1 { h = substr($0, 2); next } /^Base-idea: / { sub(/[ \t\r]+$/, ""); print h "\t" $0 }')
   j=$(node .claude/hooks/queue-list.js ideas --json 2>"$E") || echo "base-idea: listing failed: $(head -1 "$E")"; rm -f "$E"
   [ -n "$j" ] && printf '%s' "$j" | node -e '
   let rows; try { rows = JSON.parse(require("fs").readFileSync(0, "utf8")); } catch (e) { console.log("failed -"); rows = []; }
   for (const r of rows) if (String(r.title).startsWith("For the base: ")) console.log(r.block + " " + r.file);
   ' | while read -r b f; do
     [ "$b" = failed ] && { echo "base-idea: listing failed: not JSON"; continue; }
     hit=$(printf '%s\n' "$F" | L="Base-idea: $repo docs/ideas/$f" awk -F'\t' '($2 "") == ENVIRON["L"] { print $1; exit }')
     n=$(grep -rlF -- "$f" docs/design/decisions/ | head -1)
     echo "base-idea: $f: ${hit:+filed }${hit:-kept} ($b${n:+, named in $n})"
   done
   ```

   `git rm` each file whose line ends `filed <sha7> (untriaged)`, and no other: the base holds it
   as its own idea, and the deletion rides in step 10's commit. `filed` is the whole `Base-idea:`
   line found as written in a commit of the base's history up to `<sha>`, compared as a string and
   never as a pattern. A line that ends `named in <path>)` is kept while that record names the file
   (a departure row's `Carry-back` cell, ADR 0019, would otherwise point at nothing); it goes at
   the first update after the record stops naming it. Every line that is not a deletion goes in
   the report as printed; `listing failed` or a block printed `?` means a file was not read, and
   nothing is deleted on its account.
````

U4, a new step after step 10. Old:

```
   goes through the `commit` skill's Pushing section.
```

New:

```
   goes through the `commit` skill's Pushing section.
11. The brainstorm offer (`brainstorm` skill, "After an update from the base"), once step 10's
   commit exists: every update ends in one, at HOLD too and never at STOP, and it does not count as
   the session's prompted offer.
   Whoever ran steps 1 to 10 lists up to nine candidates, one line and an anchor each, each marked
   `base` (a friction or a gap in a base-owned file that this update showed) or `project` (what the
   incoming changes suggest for the project's own rules, policy, skills and docs); a subagent puts
   them in its report, since only the coordinator can ask the owner. A defect is not a candidate:
   it is filed as before. An update that stopped before step 10 makes no offer.
```

U5, Report, its last bullet. Old:

```
- What the project should act on: new skills, changed hooks or agreement text, new ADRs to read.
```

New:

```
- What the project should act on: new skills, changed hooks or agreement text, new ADRs to read.
- The ideas left to the base: step 8's lines, and which files were deleted.
- Defects this update showed in a base-owned file, one line and an anchor each: the coordinator
  files each as before, without an offer, titled `For the base: ` unless the project is fixing it
  itself.
- Up to nine candidates for the offer, one line and an anchor each, marked `base` or `project`
  (step 11). The coordinator makes the offer in the turn in which it reports this update to the
  owner.
```

## 3. `.claude/skills/handoff/SKILL.md`

H1, step 5, its first three lines. Old:

```
5. If a triage, a wave close or a release happened this session and no offer was made, make it now
   (`brainstorm` skill) unless at STOP. At HOLD its files ride in this commit and its record is one
   line of this commit's body; so is the record of an offer whose every line was a no.
```

New:

```
5. Unless at STOP: if a triage, a wave close or a release happened this session and no offer was
   made, make it now (`brainstorm` skill); and if an update from the base was committed this
   session and its own offer was not made, make that one too, whatever else was offered. At HOLD an
   offer's files ride in this commit and its record is one line of this commit's body; so is the
   record of an offer whose every line was a no, or that had no line.
```

## 4. `.claude/skills/triage/SKILL.md`

T0, step 2, the first line of the carry-back loop: it split a sibling directory whose name holds a
space, so that checkout's table was never read. Old:

```
   out=$(for f in $(grep -rlE "^Hatchery base: [0-9a-f]{7,40}" ../*/docs/design/decisions/); do
```

New:

```
   out=$(grep -rlE "^Hatchery base: [0-9a-f]{7,40}" ../*/docs/design/decisions/ | while IFS= read -r f; do
```

T1, when triage is due. Old:

```
no block (files this session's brainstorm offer recorded do not make it due). The format of a file
```

New:

```
no block (files this session's brainstorm offer recorded do not make it due, nor, in a tree with a
base line, files titled `For the base: `). The format of a file
```

T2, step 2, its closing paragraph. Old:

```
   The report carries every line and the count; the coordinator puts each pending one in the
   handoff's Next as a landing brief (`carry-back` skill, Land). A sibling not checked out beside
   Hatchery is not seen.
```

New:

````
   The report carries every line and the count; the coordinator puts each pending one in the
   handoff's Next as a landing brief (`carry-back` skill, Land). A sibling not checked out beside
   Hatchery is not seen.

   Then the ideas the siblings leave to the base (ADR 0028): the live files titled `For the base: `
   in the same checkouts, read with this tree's reader. `X` takes one directory per line when the
   brief names a sibling that is not beside this one: a checkout elsewhere on the machine, or
   `git clone --depth 1 <url> <scratchpad>/siblings/<name>` of a URL the owner gave.

   ```
   R=$(pwd); X=""; E=$(mktemp)
   F=$(git log main --format='%x01%h%n%B' | awk 'index($0, "\001") == 1 { h = substr($0, 2); next } /^Base-idea: / { sub(/[ \t\r]+$/, ""); print h "\t" $0 }')
   out=$({ grep -rlE "^Hatchery base: [0-9a-f]{7,40}" ../*/docs/design/decisions/ | sed -E 's#/docs/design/decisions/.*$##'; printf '%s\n' "$X"; } | sort -u | while IFS= read -r d; do
     [ -d "$d/docs/ideas" ] || continue
     u=$(git -C "$d" remote get-url origin 2>/dev/null); repo=$(basename -s .git "${u:-$(cd "$d" && pwd)}")
     j=$(cd "$d" && node "$R/.claude/hooks/queue-list.js" ideas --json 2>"$E") || echo "base-idea: $repo: listing failed: $(head -1 "$E") ($d)"
     [ -n "$j" ] || continue
     printf '%s' "$j" | node -e '
     let rows; try { rows = JSON.parse(require("fs").readFileSync(0, "utf8")); } catch (e) { console.log("failed -"); rows = []; }
     for (const r of rows) if (String(r.title).startsWith("For the base: ") && (r.block === "untriaged" || r.block === "?")) console.log((r.block === "?" ? "unreadable " : "live ") + r.file);
     ' | while read -r s f; do
       case "$s" in
         failed) echo "base-idea: $repo: listing failed: not JSON ($d)" ;;
         unreadable) echo "base-idea: $repo $f: unreadable ($d)" ;;
         *) hit=$(printf '%s\n' "$F" | L="Base-idea: $repo docs/ideas/$f" awk -F'\t' '($2 "") == ENVIRON["L"] { print $1; exit }')
            echo "base-idea: $repo $f: ${hit:+filed }${hit:-pending} ($d)" ;;
       esac
     done
   done | awk '{ k = $0; sub(/ \(.*$/, "", k); if (!s[k]++) print }'); rm -f "$E"
   [ -n "$out" ] && echo "$out"; echo "base-idea: $(printf '%s\n' "$out" | grep -c ': pending (') pending, $(printf '%s\n' "$out" | grep -cE ': (unreadable \(|listing failed: )') not read"
   ```

   The report carries every line and both counts; the coordinator files each pending one
   (`carry-back` skill, opening paragraph). `filed` is the whole `Base-idea:` line found as written
   in a commit on `main`, compared as a string and never as a pattern. A line `unreadable` (the
   reader gave the file's `block` as `?`) or `listing failed` (the reader's first error line
   follows) is a file or a queue this pass did not read: report it, and never read `0 pending` as
   nothing to file while the second count is not 0. The reader's warnings about a sibling's other
   files are that sibling's and are not printed. Two clones of one repository list a file once.
   Without the owner's word a sibling that is not beside this one is not seen.
````

T3, step 4. Old:

```
   `answered`, for the coordinator. The report carries the placement warnings stderr prints.
```

New:

```
   `answered`, for the coordinator. In a tree with a base line
   (`grep -rqE "^Hatchery base: [0-9a-f]{7,40}" docs/design/decisions/`), untriaged rows whose
   `title` starts `For the base: ` are not triage's either: they wait for the base (`brainstorm`
   skill, "After an update from the base"); the report counts them. In Hatchery such a file is read
   like any other. The report carries the placement warnings stderr prints.
```

T4, step 10 (replace the fragment only). Old:

```
step 2's lines and its pending count,
```

New:

```
step 2's lines and its two pending counts,
```

T5, step 10 (replace the fragment only). Old:

```
anything you could not anchor, how many ideas the brainstorm offered,
```

New:

```
anything you could not anchor, how many untriaged files are left to the base (step 4), how many ideas the brainstorm offered,
```

## 5. `.claude/skills/carry-back/SKILL.md`

C1, the opening paragraph's last line. Old:

```
coordinator files it as an idea in Hatchery's `docs/ideas/` from the sibling's text.
```

New:

```
coordinator files it as an idea in Hatchery's `docs/ideas/` from the sibling's text. In the sibling
that text is an idea titled `For the base: ` (`brainstorm` skill); Hatchery's `triage` lists the
pending ones (step 2). Filing one: write it against the base files it names, under a title without
the prefix and naming no sibling, or add it as an entry to the Hatchery idea that already says it;
either way the commit body carries one line per sibling file, `Base-idea: <repo> docs/ideas/<file>`,
on a line of its own, with `<repo>` and `<file>` exactly as the triage line prints them. That line
is how triage reads the file as filed and how the sibling's next update knows to delete its copy:
both compare the whole line as a string, so a changed character is a file never filed.
```

## 6. `.claude/rules/docs.md`

R1, Queues, its last bullet. Old:

```
- Where a project's `docs/ideas/README.md` says otherwise, this section is the record (ADR 0026).
```

New:

```
- In a tree with a base line, an idea whose title starts `For the base: ` is left to the base: while
  it has no block it is not triage's and no pass builds it; Hatchery's triage lists it, and the
  update that finds the base's `Base-idea:` commit deletes it. Removing the prefix makes it the
  project's own again (`brainstorm` skill, "After an update from the base").
- Where a project's `docs/ideas/README.md` says otherwise, this section is the record (ADRs 0026
  and 0028).
```

## 7. The check

Run by the tester before its report, each from the repository root in Bash:

- `node .claude/hooks/test-hooks.js` passes (three to five minutes; wait ten before calling it hung).
- The lines of pusher check 7 (`.claude/agents/pusher.md`) print no `FAIL`: the `--cap-check` run
  over the docs and `node .claude/hooks/policy.js --claude-check`.
- `head -1` of each of the five skill files prints `---`, and `wc -l` of each is under 500.
- `grep -l "Base-idea: " .claude/skills/*/SKILL.md` prints the carry-back, triage and
  update-from-hatchery skills and no other; `grep -c "For the base: " .claude/rules/docs.md` is 1;
  `grep -c "^11\. The brainstorm offer" .claude/skills/update-from-hatchery/SKILL.md` is 1;
  `grep -c "^## After an update from the base" .claude/skills/brainstorm/SKILL.md` is 1;
  `grep -c "an update, a spark" .claude/skills/brainstorm/SKILL.md` is 1.
- The block of T2, pasted from the edited skill: its last line is `base-idea: 0 pending, 0 not
  read` and no error is printed (no sibling holds a prefixed file yet).
- The same block against a fixture: a scratchpad directory whose `docs/ideas/` holds three idea
  files with valid frontmatter (a title starting `For the base: ` and no block; the same title
  ending in an offer Question with a blank `Answer:`; an unprefixed title), `X` set to that
  directory. It prints one `pending` line, for the first file, and `base-idea: 1 pending, 0 not
  read`.
- The block of U3 with `5d5d113` for `<sha>`: it prints nothing (Hatchery holds no prefixed title).

The matching, which guards a deletion, has a fixture of its own, built for the review's round two
and run on 2026-11-25 with the blocks extracted from the skills as they stand. A Hatchery stand-in
whose one filing message carries `Base-idea:` lines, and beside it: a project with files that are
filed, filed only for another repository, filed with a trailing space on the line, quoted indented
in the message, named `e[1]`, named `v1.2` where only `v1-2` was filed, carrying a foreign
frontmatter key, ending in an offer Question, and named in a departure cell; a second clone of it;
a directory with a space; a repository named `my.site` where `my-site` was filed; one with no
origin; one passed through `X`. Expected, and observed:

- T2: `filed` for the filed file, the trailing-space one, `e[1]` and the one a cell names;
  `pending` for `v1.2`, for `my.site`, for the other repository's name and for the indented quote;
  `unreadable` for the foreign key; the clone's files once; `base-idea: 6 pending, 1 not read`.
- U3 in the project: the same four `filed`, of which `git rm` takes three; the fourth ends
  `(untriaged, named in docs/design/decisions/0001-adopt-hatchery.md)` and stays; the offer
  Question prints `filed <sha7> (question)` and stays; `v1.2` and the rest print `kept`. In the
  `my.site` project: `kept (untriaged)`.
- A reader that exits 2 after a partial listing, prints something that is not JSON, or dies: one
  `listing failed: <reason>` line per sibling, counted under `not read`, and the rows it did print
  are still classified.

That run answered `git log` from message files through a stub, because neither the architect nor
the reviewer may commit; the dump and the string comparison were checked against real history on
an existing `Carry-back:` body line (whole: found; cut short, a dot for a dash, brackets added:
not found, and no regex error). The same fixture with real commits is a script a `runner` runs
once before this change is pushed; it prints `PASS` for each of the two blocks' three outputs.
