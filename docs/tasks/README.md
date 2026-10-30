# docs/tasks/ — closed waves

One record per closed wave: `wave-NN.md`, the wave's number in two digits. A record is the wave's
section of `docs/TASKS.md` moved verbatim, its `## Wave NN` heading demoted to the `# ` title, with
every task's Goal, Owns, Done-when and **Status:** line as they stood at the close.

The board (`docs/TASKS.md`) holds the open wave, Owner actions pending, Later and the follow-up
lists; `tools/merge-log.py` reads only the board, and a task stays there until its wave closes.
Records are closed: never edited after the move, exempt from the path-anchor check
(`edit.anchorExempt` in `.claude/hooks/policy.json`) and each under the 300-line cap. ADR 0050 took
the board off the cap's exempt list. The index of records is the table at the end of this file.

## Moving a closed wave (a Haiku chore)

The `wave` skill, Close the wave, step 5: after the last merge of a wave, a Haiku-class chore moves
it by the steps below. It works in the main checkout on `main`, in Git Bash, with `JAVA_HOME` set
and Python 3 as `python`; it edits files and commits nothing. It runs the commands as written,
stops at the first command that fails or prints `FAIL:`, and reports that line. After a failure the
coordinator relaunches the chore Sonnet-class; the working tree is restored with
`git checkout -- docs/TASKS.md docs/tasks/README.md` and the new `wave-NN.md` files deleted.

1. Start from a clean, current `main`:

   ```sh
   git checkout main && git pull --ff-only
   git status --porcelain        # must print nothing
   ```

2. Copy the script at the end of this section into a scratch file:

   ```sh
   S=$(mktemp -d)/move-waves.sh
   awk '/^```bash$/ { s = 1; next } /^```$/ { s = 0 } s' docs/tasks/README.md > "$S"
   ```

3. Run it with the first and last wave to move and, optionally, the first task whose follow-ups
   are lifted (default: the lowest task number in the last wave moved). At a close both waves are
   the one just closed, for example `bash "$S" 23 23`.

   It moves each wave's range, from its `## Wave NN` heading to the line before the next `## `
   heading, a following section whose heading says "after wave NN" included; lifts every
   `### Later` block inside a range into the board's `## Later`; lifts the follow-ups (each
   **Status:** line's text from "Follow-up" to its end) of every moved task numbered at or above
   the lift start into `## Later`, one line per task naming it and its record; puts `## Later`
   above the open wave when the board has none; and adds one index line per record.

   Then it checks, in order: no `### Tnn.` heading twice across the board and the records, and
   the same count as before; each record's title and body byte-equal to the board's old section
   at `HEAD` (less its `### Later` block); every non-blank line of the old board still present
   once, the only added lines `## Later` and the lifted follow-ups; the board under 300 lines;
   `git status` showing only `docs/TASKS.md`, this README and the new records;
   `java tools/PrivacyCheck.java scan` clean on every record (a hit is reported, not fixed);
   `python -I tools/test_merge_log.py` ok. It ends with an `OK:` line, the records and the
   lifted follow-ups.

4. Report the `OK:` line, the records and the lifted follow-ups verbatim. Do not commit.

The coordinator then reads the lifted follow-ups, strikes what is done or taken into a wave, and
commits the board, the records and the index together, pushed with `bash tools/push-verified.sh`.

```bash
#!/usr/bin/env bash
# Moves closed waves from docs/TASKS.md into docs/tasks/wave-NN.md (docs/tasks/README.md).
# Usage: bash move-waves.sh <first wave> <last wave> [<first task whose follow-ups are lifted>]
set -euo pipefail
export LC_ALL=C
shopt -s nullglob
cd "$(git rev-parse --show-toplevel)"
first=$1 last=$2
B=docs/TASKS.md R=docs/tasks T=$(mktemp -d)
fail() { echo "FAIL: $*"; exit 1; }
[ -z "$(git status --porcelain)" ] || fail "the working tree is not clean"
git show "HEAD:$B" > "$T/board0.md"
N0=$(cat "$B" "$R"/wave-*.md | grep -cE '^### T[0-9]+\.')
total=$(wc -l < "$T/board0.md")
: > "$T/drop.txt"; : > "$T/later-old.txt"; : > "$T/lifted.txt"; : > "$T/ranges.txt"

# 1. Each wave's range into its record, every ### Later block lifted out.
for nn in $(seq "$first" "$last"); do
  r=$(awk -v nn="$nn" -v total="$total" '
    /^## / { if (a && !b) { if ($0 ~ ("after wave " nn "([^0-9]|$)")) next; b = NR - 1 }
             if (!a && $0 ~ ("^## Wave " nn "( |$)")) a = NR }
    END { if (a) print a, (b ? b : total) }' "$T/board0.md")
  [ -n "$r" ] || { echo "note: no heading for wave $nn"; continue; }
  read -r a b <<< "$r"
  f=$R/wave-$(printf %02d "$nn").md
  [ ! -e "$f" ] || fail "$f exists"
  sed -n "${a},${b}p" "$T/board0.md" | awk -v later="$T/later-old.txt" '
    /^### Later$/ { inl = 1; next }
    inl && /^###? / { inl = 0 }
    inl { if ($0 != "") print >> later; next }
    { print }' | sed '1s/^## /# /' > "$f"
  seq "$a" "$b" >> "$T/drop.txt"
  echo "$nn $a $b $f" >> "$T/ranges.txt"
done
[ -s "$T/ranges.txt" ] || fail "no wave between $first and $last on the board"
records=$(cut -d' ' -f4 "$T/ranges.txt")

# 2. The follow-ups of every moved task numbered at or above the lift start, one line per task.
lastf=$(sed -n '$p' "$T/ranges.txt" | cut -d' ' -f4)
lift=${3:-$({ grep -oE '^### T[0-9]+\.' "$lastf" || true; } | tr -dc '0-9\n' | sort -n | sed -n 1p)}
[ -n "$lift" ] || fail "no task in $lastf to start the lift from"
while read -r nn a b f; do
  awk -v from="$lift" -v nn="$nn" -v f="$f" '
    /^### T[0-9]+\./ { t = substr($2, 2) + 0 }
    /^\*\*Status:/ && t >= from && (i = index($0, "Follow-up")) {
      print "- T" t ", wave " nn " (`" f "`): " substr($0, i) }' "$f" >> "$T/lifted.txt"
done < "$T/ranges.txt"

# 3. The board without the moved lines; ## Later gets the lifted blocks and follow-ups.
awk 'NR == FNR { drop[$1] = 1; next } !(FNR in drop)' "$T/drop.txt" "$T/board0.md" > "$T/board1.md"
cat "$T/later-old.txt" "$T/lifted.txt" > "$T/later-new.txt"
if [ ! -s "$T/later-new.txt" ]; then
  cp "$T/board1.md" "$B"
elif grep -qx '## Later' "$T/board1.md"; then
  L=$(awk '/^## Later$/ { s = 1; L = NR; next } s && /^## / { s = 0 } s && $0 != "" { L = NR } END { print L }' "$T/board1.md")
  sed "${L}r $T/later-new.txt" "$T/board1.md" > "$B"
else
  { echo '## Later'; cat "$T/later-new.txt"; echo; } > "$T/later-block.txt"
  W=$({ grep -nE '^## Wave ' "$T/board1.md" || true; } | sed -n 1p | cut -d: -f1)
  if [ -n "$W" ] && [ "$W" -gt 1 ]; then
    sed "$((W - 1))r $T/later-block.txt" "$T/board1.md" > "$B"
  else
    { cat "$T/board1.md"; [ -z "$(sed -n '$p' "$T/board1.md")" ] || echo; cat "$T/later-block.txt"; } > "$B"
  fi
fi

# 4. One index line per record, at the end of the README's table.
while read -r nn a b f; do
  ids=$({ grep -oE '^### T[0-9]+\.' "$f" || true; } | tr -dc '0-9\n' | sort -n)
  span="no tasks"; [ -z "$ids" ] || span="T$(echo "$ids" | sed -n 1p) to T$(echo "$ids" | sed -n '$p')"
  printf '| [%s](%s) | %s |\n' "${f#"$R"/}" "${f#"$R"/}" "$span" >> "$R/README.md"
done < "$T/ranges.txt"

# 5. The checks; the first failure stops the run.
dups=$(cat "$B" "$R"/wave-*.md | grep -oE '^### T[0-9]+\.' | sort | uniq -d)
[ -z "$dups" ] || fail "task headings twice: $dups"
N1=$(cat "$B" "$R"/wave-*.md | grep -cE '^### T[0-9]+\.')
[ "$N1" = "$N0" ] || fail "$N1 task headings, $N0 before"
while read -r nn a b f; do
  [ "$(sed -n "${a}p" "$T/board0.md" | sed 's/^## /# /')" = "$(sed -n 1p "$f")" ] || fail "$f: title differs"
  git show "HEAD:$B" | sed -n "$((a + 1)),${b}p" | awk '/^### Later$/ { s = 1; next } s && /^###? / { s = 0 } !s' \
    | diff - <(tail -n +2 "$f") > /dev/null || fail "$f: body differs from the old section"
done < "$T/ranges.txt"
grep -v '^$' "$T/board0.md" | sort > "$T/before.txt"
{ cat "$B"; for f in $records; do sed '1s/^# /## /' "$f"; done; } | { grep -v '^$' || true; } | sort > "$T/after.txt"
{ diff "$T/before.txt" "$T/after.txt" || true; } | { grep '^[<>]' || true; } > "$T/delta.txt"
{ echo '< ### Later'; echo '> ## Later'; sed 's/^/> /' "$T/lifted.txt"; } > "$T/allowed.txt"
bad=$({ grep -vxF -f "$T/allowed.txt" "$T/delta.txt" || true; } | sed -n 1,5p)
[ -z "$bad" ] || fail "lines lost, added or changed: $bad"
n=$(wc -l < "$B"); [ "$n" -lt 300 ] || fail "the board has $n lines"
extra=$(git status --porcelain | { grep -vE '^( M docs/TASKS\.md| M docs/tasks/README\.md|\?\? docs/tasks/wave-[0-9]{2}\.md)$' || true; })
[ -z "$extra" ] || fail "files outside the move changed: $extra"
J=${JAVA_HOME:+$JAVA_HOME/bin/}java
for f in $records; do
  "$J" tools/PrivacyCheck.java scan < "$f" > "$T/privacy.txt" 2>&1 || fail "$f: PrivacyCheck scan: $(cat "$T/privacy.txt")"
done
python -I tools/test_merge_log.py > "$T/merge-log.txt" 2>&1 || fail "tools/test_merge_log.py: $(tail -3 "$T/merge-log.txt")"

echo "OK: $(wc -l < "$T/ranges.txt") records, the board at $n lines, $N0 task headings"
cat "$T/ranges.txt"
echo "Follow-ups lifted into ## Later (the coordinator reads these before the commit):"
cat "$T/lifted.txt"
```

## The first move (once, step B of the idea that set this up)

The board then carries every wave since the first, so the first run differs from a close:
`bash "$S" 1 21 148`. Wave 2 has no heading of its own (the script notes it and goes on); the
`## One-off fixes after wave 20` section goes to the end of `wave-20.md`; the `### Later` block of
wave 20 becomes the board's `## Later` above wave 22; `## Browser demo follow-ups` stays on the
board. The lift starts at T148 because the wave 22 paragraph leaves the follow-ups of T148 to T157
in their Status lines; older waves' follow-ups were taken into a task or Later when the next wave
was planned. The coordinator's commit also deletes
`docs/ideas/2026-10-29-closed-waves-move-out-of-the-board.md`, and drops this section.

## Records

| Record | Tasks |
|---|---|
