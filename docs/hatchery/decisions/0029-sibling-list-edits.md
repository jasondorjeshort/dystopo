# 0029, sibling: the exact edits, who applies them and how they are checked

[length: the exact old and new text of one pass's edits to three files and its checks, read start to finish]

Companion to [ADR 0029](0029-machine-sibling-list.md). A `tester` applies the three edits below
with the Edit tool and decides nothing: each `Old` block is in the named file at commit 8dd17ef
verbatim and once (leading spaces included), and is replaced by its `New` block. Nothing else in a
file changes, and no hook, rule or agreement file is touched. The `runner` commits the three files
as one commit and runs section 4's real-commit check; a `reviewer` reads the commit once; the
`pusher` pushes on the owner's request. Nobody creates `~/.claude/hatchery-siblings.txt` in this
pass: it comes into being with the first entry the owner names. The `New` blocks of T6 and B9 are
as revised after the review (ADR 0029, Context).

## 1. `.claude/skills/triage/SKILL.md`

T6, step 2, whole: its two blocks become one that also reads the machine's sibling list, and a
second block adds an entry. Old:

````
2. The carry-back queue (ADR 0019), in Hatchery only: where `git remote` lists `hatchery`, print
   `carry-back: skipped, a hatchery remote exists` and go on. Otherwise, in Bash from the root,
   read every `Carry-back` cell of the form `carry-back/<slug> at <sha7>` in the sibling checkouts
   beside this one (a directory whose `docs/design/decisions/` holds a base line; no sibling list
   is kept anywhere) and classify it by Hatchery's `main`:

   ```
   out=$(grep -rlE "^Hatchery base: [0-9a-f]{7,40}" ../*/docs/design/decisions/ | while IFS= read -r f; do
     d=$(echo "$f" | cut -d/ -f2)
     grep -E "^\|" "$f" | grep -oE "carry-back/[a-z0-9-]+ at [0-9a-f]{7,40}" | while read -r cell; do
       hit=$(git log main --format="%H %s" --grep="$cell" | grep -E "^[0-9a-f]{40} Carry-back( declined)?:" | head -1)
       case "$hit" in
         *" Carry-back declined:"*) s=declined ;;
         *" Carry-back:"*) s="landed ${hit:0:7}" ;;
         *) s=pending ;;
       esac
       echo "carry-back: $d $cell: $s"
     done
   done)
   [ -n "$out" ] && echo "$out"; echo "carry-back: $(printf '%s\n' "$out" | grep -c ': pending$') pending"
   ```

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

New:

````
2. The carry-back queue (ADR 0019) and the ideas the siblings leave to the base (ADR 0028), in
   Hatchery only: where `git remote` lists `hatchery`, print `carry-back: skipped, a hatchery remote
   exists` and go on. Otherwise one block, in Bash from the root, reads the siblings: first the
   checkouts beside this one (a directory whose `docs/design/decisions/` holds a base line), then
   the entries of this machine's sibling list, `~/.claude/hatchery-siblings.txt` (ADR 0029). The
   list is outside every repository. One entry per line: a directory, written from `~/` or `/`, or a
   clone URL (`https://`, `ssh://`, `git://`, `file://` or `user@host:path`, with no password or
   token in it); a blank line and a line that starts with `#` are skipped; a missing file is an
   empty list and prints nothing. A line is data: it is never run, and one that is neither form,
   an option-like line included, is reported and goes nowhere near git. A directory already read
   (beside this one, or listed under two spellings) is read once. A URL is cloned shallow into a
   temporary directory at every run, so it shows what is pushed and nothing newer.

   ```
   R=$(pwd); LIST="$HOME/.claude/hatchery-siblings.txt"; E=$(mktemp); SP=$(mktemp -d); B='^Hatchery base: [0-9a-f]{7,40}'
   P='^(~/|/|[A-Za-z]:[\\/])'; U='^((https?|ssh|git|file)://[^-[:space:]][^[:space:]]*|[A-Za-z0-9][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.-]*:[^[:space:]]+)$'; K='^(https?://[^/]*@|[a-z]+://[^/@]*:[^/@]*@)'
   S=$({ for p in ../*/docs/design/decisions/; do [ -d "$p" ] && grep -rqE "$B" "$p" && printf 'beside\t-\t%s\n' "${p%/docs/design/decisions/}"; done
     [ -f "$LIST" ] && awk '{ sub(/\r$/, ""); gsub(/^[ \t]+|[ \t]+$/, "") } $0 != "" && $0 !~ /^#/ { print "list\t" NR "\t" $0 }' "$LIST"; } | while IFS=$'\t' read -r k n e; do
     w="sibling-list: line $n: $e: not read:"
     if [ "$k" = beside ]; then d=$e
     elif [[ $e =~ $K ]]; then printf 'bad\tsibling-list: line %s: not read: a URL with credentials in it; rewrite that line without them\n' "$n"; continue
     elif [[ $e =~ $P ]]; then d=$e; [[ $e == "~/"* ]] && d="$HOME/${e#\~/}"
     elif [[ $e =~ $U ]]; then
       [ -d "$SP" ] || { printf 'bad\t%s no temporary directory\n' "$w"; continue; }
       d="$SP/$n-$(basename -s .git -- "$e")"
       GIT_TERMINAL_PROMPT=0 GCM_INTERACTIVE=never git clone -q --depth 1 -- "$e" "$d" 2>"$E" || { printf 'bad\t%s %s\n' "$w" "$(head -1 "$E")"; continue; }
     else printf 'bad\t%s neither a URL nor a path from / or ~/\n' "$w"; continue
     fi
     r=$(cd "$d" 2>/dev/null && pwd -P) || { printf 'bad\t%s no such directory\n' "$w"; continue; }
     [ "$k" = beside ] || { [ -d "$d/docs/design/decisions" ] && grep -rqE "$B" "$d/docs/design/decisions/"; } || { printf 'bad\t%s no base line under docs/design/decisions, so not a sibling\n' "$w"; continue; }
     printf 'ok\t%s\t%s\t%s\n' "$e" "$d" "$r"
   done | awk -F'\t' '$1 != "ok" || !seen[$4]++')
   bad=$(printf '%s\n' "$S" | awk -F'\t' '$1 == "bad" { print $2 }'); m=$(printf '%s\n' "$S" | grep -c '^bad')
   [ -n "$bad" ] && echo "$bad"
   out=$(printf '%s\n' "$S" | while IFS=$'\t' read -r k e d r; do
     [ "$k" = ok ] || continue
     for f in "$d"/docs/design/decisions/*.md; do
       [ -f "$f" ] && grep -qE "$B" "$f" || continue
       grep -E "^\|" "$f" | grep -oE "carry-back/[a-z0-9-]+ at [0-9a-f]{7,40}" | while read -r cell; do
         hit=$(git log main --format="%H %s" --grep="$cell" | grep -E "^[0-9a-f]{40} Carry-back( declined)?:" | head -1)
         case "$hit" in
           *" Carry-back declined:"*) s=declined ;;
           *" Carry-back:"*) s="landed ${hit:0:7}" ;;
           *) s=pending ;;
         esac
         echo "carry-back: ${e#../} $cell: $s"
       done
     done
   done)
   [ -n "$out" ] && echo "$out"; echo "carry-back: $(printf '%s\n' "$out" | grep -c ': pending$') pending, $m not read"
   F=$(git log main --format='%x01%h%n%B' | awk 'index($0, "\001") == 1 { h = substr($0, 2); next } /^Base-idea: / { sub(/[ \t\r]+$/, ""); print h "\t" $0 }')
   out=$(printf '%s\n' "$S" | while IFS=$'\t' read -r k e d r; do
     [ "$k" = ok ] && [ -d "$d/docs/ideas" ] || continue
     u=$(git -C "$d" remote get-url origin 2>/dev/null); repo=$(basename -s .git -- "${u:-$r}")
     j=$(cd "$d" && node "$R/.claude/hooks/queue-list.js" ideas --json 2>"$E") || echo "base-idea: $repo: listing failed: $(head -1 "$E") ($e)"
     [ -n "$j" ] || continue
     printf '%s' "$j" | node -e '
     let rows; try { rows = JSON.parse(require("fs").readFileSync(0, "utf8")); } catch (e) { console.log("failed -"); rows = []; }
     for (const r of rows) if (String(r.title).startsWith("For the base: ") && (r.block === "untriaged" || r.block === "?")) console.log((r.block === "?" ? "unreadable " : "live ") + r.file);
     ' | while read -r s f; do
       case "$s" in
         failed) echo "base-idea: $repo: listing failed: not JSON ($e)" ;;
         unreadable) echo "base-idea: $repo $f: unreadable ($e)" ;;
         *) hit=$(printf '%s\n' "$F" | L="Base-idea: $repo docs/ideas/$f" awk -F'\t' '($2 "") == ENVIRON["L"] { print $1; exit }')
            echo "base-idea: $repo $f: ${hit:+filed }${hit:-pending} ($e)" ;;
       esac
     done
   done | awk '{ k = $0; sub(/ \(.*$/, "", k); if (!s[k]++) print }'); rm -f "$E"
   [ -n "$out" ] && echo "$out"; echo "base-idea: $(printf '%s\n' "$out" | grep -c ': pending (') pending, $((m + $(printf '%s\n' "$out" | grep -cE ': (unreadable \(|listing failed: )'))) not read"
   if printf '%s\n' "$out" | grep -qE ': pending \(((https?|ssh|git|file)://|[A-Za-z0-9][A-Za-z0-9._-]*@)'; then echo "sibling-list: clones kept in $SP (<line>-<repo> each) for the pending ideas above"; elif [ -d "$SP" ]; then rm -rf "$SP"; fi
   ```

   The report carries every line and the two count lines.
   - `sibling-list: line <n>: ...: not read: <reason>` is a list entry this pass did not read, by
     the list's line number: no such directory; no base line under `docs/design/decisions/` (a
     wrong directory, a wrong or empty repository, a sibling not migrated yet); a clone that failed
     (git's first error line follows); a line that is neither a URL nor a path; a URL with
     credentials in it, which is never printed. Each counts under `not read` on both count lines,
     so `0 pending` is never read as nothing to do while that count is not 0. The coordinator asks
     the owner whether the entry is corrected or removed; no pass removes one.
   - `sibling-list: clones kept in <dir>` comes last, only when a URL sibling has a pending idea:
     its checkout is `<dir>/<line>-<repo>`, which is where the coordinator reads the idea's text.
     Otherwise the block removes that directory itself. It is the one directory the block made
     with `mktemp -d` before it read the list, and nothing else is ever removed; a kept one stays
     until the machine clears its temporary files.
   - `carry-back:` lines classify every `Carry-back` cell of the form `carry-back/<slug> at <sha7>`
     by Hatchery's `main`; the coordinator puts each pending one in the handoff's Next as a landing
     brief (`carry-back` skill, Land). A repository reached through two checkouts (two clones, or
     a clone and its URL) prints its cells once for each.
   - `base-idea:` lines are the live files titled `For the base: ` (ADR 0028), read with this
     tree's reader; the coordinator files each pending one (`carry-back` skill, opening paragraph).
     `filed` is the whole `Base-idea:` line found as written in a commit on `main`, compared as a
     string and never as a pattern. A line `unreadable` (the reader gave the file's `block` as `?`)
     or `listing failed` (the reader's first error line follows) is a file or a queue this pass did
     not read, counted under `not read`. The reader's warnings about a sibling's other files are
     that sibling's and are not printed. Two clones of one repository list a file once.

   A sibling is named as it was found: its directory name or `../<dir>` beside this one, else its
   line of the list. A tracked file (the handoff) takes the `~/` form or `<repo>`, never a URL or a
   path with a user segment (`.claude/rules/docs.md`, Personal data).

   Only the coordinator adds or removes a list entry, and only from the owner's sentence naming
   that sibling by directory or URL, said once. A subagent never writes the file, and nobody adds
   an entry from a path it came across. A directory goes in from `~/` where it is under the home
   directory; a URL goes in without any `user:password@` or token part. The coordinator adds with
   this block, the entry written between the single quotes; it refuses what the block above would
   not read, and it never rewrites a line that is already there:

   ```
   e='<the directory, from ~/ or /, or the URL>'; L="$HOME/.claude/hatchery-siblings.txt"; ok=""
   P='^(~/|/|[A-Za-z]:[\\/])'; U='^((https?|ssh|git|file)://[^-[:space:]][^[:space:]]*|[A-Za-z0-9][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.-]*:[^[:space:]]+)$'; K='^(https?://[^/]*@|[a-z]+://[^/@]*:[^/@]*@)'
   [[ $e =~ $P ]] && ok=1; [[ $e =~ $U ]] && ok=1
   if [[ $e =~ $K ]]; then echo "sibling-list: refused: a URL with credentials in it; name it without them"; false
   elif [ -z "$ok" ]; then echo "sibling-list: $e: refused: neither a URL nor a path from / or ~/"; false
   elif [ -f "$L" ] && tr -d '\r' < "$L" | grep -qxF -- "$e"; then echo "sibling-list: $e: already listed"
   else
     [ -f "$L" ] || printf '%s\n' "# Sibling checkouts the base's triage reads besides those beside its own. One directory or clone URL per line." > "$L"
     [ -s "$L" ] && [ -n "$(tail -c1 "$L")" ] && printf '\n' >> "$L"
     printf '%s\n' "$e" >> "$L" && echo "sibling-list: $e: added" || { echo "sibling-list: $e: NOT added: the list could not be written (the error is above)"; false; }
   fi
   ```

   A project's coordinator adds its own checkout the same way (`brainstorm` skill, the closing line
   of the offer after an update). A sibling that is neither beside this one nor on the list is not
   seen.
````

## 2. `.claude/skills/brainstorm/SKILL.md`

B9, "After an update from the base", item 6, the closing line. Old:

```
6. Closing line, whenever the tree holds a live file for the base (below): `For the base: <n> kept
   here. Hatchery's triage lists them when this checkout sits beside Hatchery's; elsewhere, tell
   Hatchery's session this directory or its URL.`
```

New:

````
6. Closing line, whenever the tree holds a live file for the base (below). Hatchery's triage reads
   this checkout when it sits beside Hatchery's or is on the machine's sibling list,
   `~/.claude/hatchery-siblings.txt` (ADR 0029). The coordinator checks, in Bash from the root:

   ```
   r=$(pwd -P); case "$r" in "$HOME"/*) e="~${r#"$HOME"}" ;; *) e=$r ;; esac; L="$HOME/.claude/hatchery-siblings.txt"
   [ -f "$L" ] && tr -d '\r' < "$L" | grep -qxF -- "$e" && echo "sibling-list: $e: listed" || echo "sibling-list: $e: not listed"
   ```

   Listed: `For the base: <n> kept here; this checkout is on the machine's sibling list.` Not
   listed: `For the base: <n> kept here. Hatchery's triage reads this checkout if it sits beside
   Hatchery's. If it does not, say "list this checkout" once; if Hatchery is on another machine,
   give its session this repository's URL once.` On the owner's "list this checkout", and never
   without it, the coordinator (never a subagent) appends this checkout's line. The block ends the
   owner's last line first if it has no newline, never rewrites a line, and says `NOT added` when
   the write fails:

   ```
   r=$(pwd -P); case "$r" in "$HOME"/*) e="~${r#"$HOME"}" ;; *) e=$r ;; esac; L="$HOME/.claude/hatchery-siblings.txt"
   [ -f "$L" ] || printf '%s\n' "# Sibling checkouts the base's triage reads besides those beside its own. One directory or clone URL per line." > "$L"
   [ -s "$L" ] && [ -n "$(tail -c1 "$L")" ] && printf '\n' >> "$L"
   printf '%s\n' "$e" >> "$L" && echo "sibling-list: $e: added" || { echo "sibling-list: $e: NOT added: the list could not be written (the error is above)"; false; }
   ```

   The list is outside every repository; its lines are never copied into a tracked file.
````

## 3. `.claude/skills/carry-back/SKILL.md`

C2, Land step 1. Old:

```
   lists them); `U=$(git -C ../<dir> remote get-url origin)`. The brief names `<dir>`, `<slug>`,
```

New:

```
   lists them); `U=$(git -C ../<dir> remote get-url origin)`, or, for a sibling the triage line
   names by a line of the machine's sibling list (ADR 0029), that directory in place of `../<dir>`,
   or the listed URL itself. The brief names `<dir>`, `<slug>`,
```

## 4. The check

Run by the tester before its report, each from the repository root in Bash:

- `node .claude/hooks/test-hooks.js` passes (three to five minutes; wait ten before calling it hung).
- The lines of pusher check 7 (`.claude/agents/pusher.md`) print no `FAIL`.
- `head -1` of each of the three skill files prints `---`, and `wc -l` of each is under 500.
- `grep -c "hatchery-siblings.txt" .claude/skills/triage/SKILL.md` is 3 and the same count in the
  brainstorm skill is 3; `grep -c 'X=""' .claude/skills/triage/SKILL.md` is 0;
  `grep -c "git clone -q --depth 1 -- " .claude/skills/triage/SKILL.md` is 1;
  `grep -c "rm -rf" .claude/skills/triage/SKILL.md` is 1, on a line that names `"$SP"` and no
  other path.
- The first block of T6 pasted from the edited skill: with no list file on the machine it prints
  no `sibling-list:` line, the same `carry-back:` lines as the block it replaces with the count
  line ending `, 0 not read`, and `base-idea: 0 pending, 0 not read`.
- The second block of T6 and the two blocks of B9 are never run against the real home directory
  in this pass: each is pasted with `HOME` set to a scratch directory that holds a `.claude/`
  directory. B9 from a scratch directory under that home: `not listed`, `added`, `listed`, and the
  file holds one comment line and one `~/` line.

Each safety property was run in isolation on 2026-11-25, with the blocks exactly as T6 and B9 give
them and `HOME` always a scratch directory. Observed:

- A line is data. A list holding `-rf`, `--template=x@h:p`, `--upload-pack=touch <dir>/X@h:p`,
  `ssh://-oProxyCommand=touch <dir>/Y/x`, `ext::sh -c touch% <dir>/Z` and `~/$(touch <dir>/W)`:
  each prints `not read` (`neither a URL nor a path from / or ~/`, or `no such directory` for the
  last) and no marker file exists afterwards. Before the fix the third line ran its command.
- Nothing is read as nothing. A listed directory that exists with no base line, `/`, and a URL
  whose clone is an empty repository each print `not read: no base line under
  docs/design/decisions, so not a sibling` and count on both count lines.
- No secret is printed. `https://user:<password>@host/...` and `https://<token>@host/...` print
  `sibling-list: line <n>: not read: a URL with credentials in it; rewrite that line without
  them`, are counted, and are never passed to git; the add block refuses both without echoing
  them.
- Nothing but the block's own directory is removed. With a URL sibling that has a pending idea the
  run ends `sibling-list: clones kept in <dir> ...` and that directory holds the clones; with none
  pending, and with no list at all, the run leaves no temporary directory. A listed directory that
  is not a sibling still holds its file afterwards.
- The add blocks never rewrite a line. Onto a list whose last line has no newline, the entry lands
  on a line of its own and the owner's line is unchanged (`cat -A`); onto a list with a CRLF line,
  a blank and a URL, the lines are byte for byte as before with one line after them. With no
  `~/.claude/` directory the block prints the shell's error, then `NOT added`, and exits 1.
  Hatchery's block adds a `~/` path, an `https://`, an `ssh://` and a `user@host:path` entry,
  answers `already listed` for a repeat, and refuses, with exit 1, an option-like line, a relative
  path, both credential forms and its own unfilled placeholder.
- The rest as before: a `~/` directory with a space and a carriage return, the same directory by
  its absolute path, a sibling that is both beside and listed, a URL whose clone works, one whose
  clone fails, a missing directory, a relative path; no list file; a list of comments and blanks.
  The sibling beside and listed prints once under `../<dir>`, the directory listed twice once
  under `~/`, `carry-back:` and `base-idea:` lines as their commits say, and `15 not read` on both
  count lines for the fifteen bad lines of the fixture.

On the real tree the first block, pasted, passed the guard and printed the six `carry-back:` lines
the old block prints, `carry-back: 5 pending, 0 not read` and `base-idea: 0 pending, 0 not read`,
and left no temporary directory.

What that run could not do, because neither the architect nor the reviewer may commit: it answered
`git log` from message files and the working URL's clone from a directory, through a stub; the
`file://` clone of the empty repository and every refused line went through real git. The `runner`
therefore runs once, before the push, a script that rebuilds the fixture with real commits in the
stand-in and in a sibling reached by a `file://` URL, runs the first block as extracted from the
edited skill with real `git log` and a real shallow clone, the option-like, credential and
non-sibling lines included, and checks the output, the absence of marker files and the survival of
the non-sibling directory; its last line is `PASS sibling-list real-check` and it exits 0. Not
exercised by anyone until the first such entry: a clone over the network with this machine's
credentials, and what an ssh passphrase or host-key question does there.
