# 0017, sibling: the listing script, the marker line and the hook reading

Companion to [ADR 0017](0017-queue-directories-list-on-demand.md). The contract a coder builds
`.claude/hooks/queue-list.js` from and the harness checks; the parts ADR 0013 promises are marked
"promised". Where the code and this file differ, one of them has a bug; the ADR says which parts may
not change without a proposal. Sibling names are allowed here: a decision is a closed record.

## 1. Command line (promised: the path, the three mode words, the four flags named, the exit meanings)

```
node .claude/hooks/queue-list.js <ideas|operator|findings> [--status <v>] [--untriaged] [--json | --markdown] [--check]
```

- Root is the working directory: the command runs from the repository root, where the relative
  script path resolves. The hook calls the export with the root it already has from the tool input.
- `<mode>` names `docs/<mode>/` under the root. Files read: every `*.md` directly in that directory
  except `README.md`, sorted by name in byte order (the date prefix makes that age order);
  subdirectories and other extensions are ignored.
- `--status <v>`: `findings` only. `--untriaged`: `ideas` only. `--json` and `--markdown` are
  exclusive. `--check` combines with anything.
- Usage error, exit 2, nothing on stdout, one stderr line starting `queue-list: usage:`: no mode,
  an unknown mode or flag, a flag on the wrong mode, both output flags, `--status` without a value.
  Directory absent: exit 2, stderr `queue-list: docs/<mode>/ does not exist`. A file that cannot be
  read: stderr `queue-list: <file>: unreadable (<error>)`, the other files still listed, exit 2.
- Exit 0: the listing printed, `?` rows included, `--check` absent. Exit 1: `--check` present and
  at least one file read has a problem (section 2), whether or not a filter hid its row; the listing
  still prints. Nothing else exits 1.

## 2. Reading one file: `read(mode, absFile)`

The one reading, exported for the hook (`module.exports = { read, MODES, MARKER }`), so the two never
disagree. Returns `{ file, title, ...columns, problems }`: `file` is the basename; `problems` is an
array of `{ field, value, allowed }`, `value` the offending string or `null` when the field is
missing, `allowed` the comma-separated set as printed. A problem puts `?` in its field. The export
shape is an internal seam between two base-owned files, not promised.

Frontmatter (every mode): a first line `---` and the next `---` within the first 20 lines (the
rule `hasFrontmatter` in `post-edit.js` uses). A key is the text before the first `:` of a line
between the markers; its value is the rest, trimmed. No such block: no keys.

Title (every mode): the text after `# ` of the first line starting `# ` outside the frontmatter;
else the first non-blank line outside it, whole; else `?` with the problem
`{ field: "title", value: null, allowed: "a # heading or a first line" }`.

`findings`: `status` is the first whitespace-delimited word of the `status:` value (promised:
regenesis writes `resolved (ADR 0011, 2026-10-14)`, which reads `resolved`); allowed `open`,
`resolved`, `superseded`; anything else, or no key, is `?` with `{ field: "status", ... }`.

`ideas`: `kind` is the whole trimmed `kind:` value; allowed `bug`, `feature`, `question`, `chore`;
else `?` with `{ field: "kind", ... }`. `block` is read as the `triage` skill reads it today: the
last line outside a fenced code block that starts with `Plan:`, `Question:` or `Waits:` opens the
block, which runs to the end of the file; `Plan:` is `plan`, `Waits:` is `waits`; `Question:` is
`answered` when a later line starts with `Answer:` and carries non-blank text after the colon or a
non-blank line below it, else `question` (no `Answer:` line at all is a blank answer); no opener
is `untriaged`. When the frontmatter carries any key outside `date`, `kind`, `related` (the
promised grammar, ADR 0007), `block` is `?` with
`{ field: "frontmatter", value: "<the first foreign key>", allowed: "date, kind, related" }`,
whatever the file ends in: the file speaks a grammar this reading does not know, so its triage
state is not confidently anything. `kind` is still read.

`operator`: title only.

## 3. Output

- Text (default, not promised): one line per file, tab-separated, the `--json` fields in order,
  title last, no header. Rows with `?` print.
- `--json` (promised: the array, the field names, the `?` literal; indentation not promised):
  `JSON.stringify` of an array in file order, one object per row, fields `findings`: `file`,
  `status`, `title`; `ideas`: `file`, `kind`, `block`, `title`; `operator`: `file`, `title`. A `?`
  is the string `"?"`. Agents parse this form, never the text.
- `--markdown` (not promised; the exit path): a header row then `| \`<file>\` | <columns> | <title> |`
  per row, the same columns as the text form, so a tree that re-adds a table pastes it under
  `## Index` and rewrites the one-line text it wants.
- Filters change stdout only. `--status <v>` keeps rows whose `status` column is `<v>` or whose
  whole `status:` value is `<v>` (so `--status open` and `--status "resolved (ADR 0011, 2026-10-14)"`
  both work). `--untriaged` keeps rows whose `block` is `untriaged`, never a `?`.
- Stderr (wording not promised): for every problem in every file read, filters notwithstanding,
  one line `queue-list: <file>: <field> is "<value>"; allowed: <set>` or
  `queue-list: <file>: <field> missing; allowed: <set>`. A reader under `--untriaged` still sees
  which files were not classified.

## 4. The marker line (promised) and what the hook does with it

The marker is one whole line of `docs/<dir>/README.md`, written verbatim with the directory's own
name as the mode word, trailing whitespace allowed and nothing else on the line:

```
Listed on demand: node .claude/hooks/queue-list.js <ideas|operator|findings>
```

`MARKER` in the script is the regular expression the hook uses, multiline:
`^Listed on demand: node \.claude/hooks/queue-list\.js (ideas|operator|findings)[ \t]*$`. A README
may say anything else around it; the line itself is fixed. The hook honours it only in
`docs/<dir>/README.md` one level below `docs/` and only when the captured word equals `<dir>`:
the three directories the owner named, nowhere else.

`post-edit.js`, replacing the index check for these files only (the walk-up for every other path,
the stream check, the caps, the frontmatter and anchor checks are untouched):

1. Edited file `docs/<dir>/<file>.md` (three path segments; basename not `README.md` or
   `HANDOFF.md`): read `docs/<dir>/README.md`. No valid marker (absent, mode word not `<dir>`,
   README missing): the index check exactly as today, no other message. Valid marker: skip the
   index check, call `read(<dir>, abs)`, and for each problem push
   `post-edit: <rel> would list as ? (<field> is "<value>"; allowed: <set>). See docs/<dir>/README.md.`
   (`<field> missing` for a `null` value). The findings frontmatter-keys warning and the caps
   still apply before it. The script is loaded lazily and guarded as `imports.js` is; when the
   marker is valid but the script does not load, the index check is skipped and one line says so:
   `post-edit: queue-list.js did not load; <rel> was not checked.`
2. Edited `docs/<dir>/README.md` (three segments, basename `README.md`): a line starting
   `Listed on demand:` that is not the valid marker for `<dir>` warns
   `post-edit: docs/<dir>/README.md carries a Listed on demand: line that is not "Listed on demand: node .claude/hooks/queue-list.js <dir>"; the directory counts as indexed.`
   Two valid markers in one README are harmless (the first is read). A `Listed on demand:` line in
   any deeper README is not read and not warned about; the marker means nothing there.
3. The hook never runs the script as a process: it requires the export. One file, one reading.

The hook specs (`0002-hook-specs.md`, section 2, the message table) are edited by the coder in the
same commit as the code: the index-check row gains "unless `docs/<dir>/README.md` carries the
marker line of ADR 0017 (`0017-queue-list.md`, section 4)", and three rows are added for the
messages of items 1 and 2 above (the `?` warning, the did-not-load line, the malformed marker).

## 5. Harness: group `Q` in `test-hooks.js`

A scratch tree like the `P` cases, the script run with `child_process.spawnSync(process.execPath,
[script, ...args], { cwd: scratchRoot })`, the hook cases through `--input` as every post-edit
case is. Fixtures: `docs/findings/` with `status: open`, `status: resolved (ADR 0011, 2026-10-14)`,
`status: superseded`, `status: done`, and one without frontmatter; `docs/ideas/` with one file per
block kind (`plan`, `question`, `answered`, `waits`, `untriaged`), one with `kind: idea`, one with
`status: new` beside a valid `kind:`, one empty; `docs/operator/` with an H1 file, a first-line
file and an empty file; READMEs with a valid marker, with none, with a marker for the wrong
directory, and with a `Listed on demand:` line of the wrong shape. Cases:

- Q01 `findings` text: three columns, byte order, `resolved (...)` reads `resolved`, `done` is `?`,
  the missing-frontmatter file is `?` with `status missing`, exit 0, one stderr line per problem.
- Q02 `findings --status open` and `--status "resolved (ADR 0011, 2026-10-14)"`: the column match
  and the whole-value match each keep exactly their file; stderr lines still print for the `?` files.
- Q03 `findings --json`: an array, fields `file`, `status`, `title`, the `?` literal, parseable.
- Q04 `ideas` text: `plan`, `question`, `answered`, `waits`, `untriaged`; `kind: idea` reads `?`;
  the `status: new` file reads its kind and `?` in `block` with the `frontmatter` stderr line; the
  empty file is `?` in title and kind.
- Q05 `ideas --untriaged`: the untriaged file only, never the `?` files; stderr unchanged.
- Q06 `--check`: exit 1 on each directory with a problem, exit 0 on a clean copy; stdout printed in
  both.
- Q07 `operator`: the H1 title, the first-line title, `?` for the empty file.
- Q08 `--markdown`: a header row and one `| \`<file>\` |` row per file.
- Q09 usage errors, each exit 2 with nothing on stdout: no mode, `tasks`, `--untriaged findings`,
  `--status open ideas`, `--json --markdown`, `--status` without a value, a mode whose directory
  is absent.
- Q10 hook: an edit to a file under a README with the valid marker gets no "is not listed" line
  and gets the `?` warning for a bad field; a clean file gets nothing from this check.
- Q11 hook: an edit under a README without the marker gets the index warning as today (the `P`
  fixtures keep their expected output, which the existing cases already assert).
- Q12 hook: an edit to a README with a wrong-directory marker, and one with a malformed
  `Listed on demand:` line, each gets the malformed-marker warning; a file edited under that README
  gets the index warning (the directory counts as indexed).
- Q13 hook: the marker valid, the script unloadable (the case points the hook at a scratch copy
  that throws): the did-not-load line, no index warning.

`--only Q` selects them. Group `F` (fitness) covers the new file through `deps.paths` with no edit
to `fitness.json`; the harness count in the hook specs header is not kept current by hand.
