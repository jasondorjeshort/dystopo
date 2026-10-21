# 0013. Hatchery's promised surfaces and its reference fitness check

Date: 2026-10-16
Status: proposed (the owner declined to confirm, 2026-10-18), superseded in part by 0016, 0017 and 0018
Superseded in part by [0016](0016-base-owned-and-project-owned-files.md), 2026-10-17 (this table
is not living, so the rows are added by that record, item 7): the ownership map, the agreement
files with their import lines, the `Branch:` line, the departure table, the record paths under
`docs/hatchery/`; the idea-grammar row's record becomes ADR 0007 and `.claude/rules/docs.md`, its
`docs/ideas/README.md` being the project's copy; `update.exclude` is an addition under the policy
row. Every row below stands; the owner declined to confirm the list (2026-10-18).
Superseded in part by [0017](0017-queue-directories-list-on-demand.md), 2026-10-17 (rows added by
its item 5): the listing CLI `.claude/hooks/queue-list.js` (modes, flags, `--json` fields, exit
codes, the first word of a finding `status:`) and the README marker line; a finding's `status:`
leaves the not-promised list, its trigger having fired. Every row below stands.
Superseded in part by [0018](0018-personal-data-never-leaves-the-machine.md), 2026-10-17 (rows
added by its sibling, section 5): `privacy.allow` and `privacy.identities` under the policy row;
the never list `~/.claude/privacy-never.txt` and the user allow list `~/.claude/privacy-allow.txt`;
the scan CLI `.claude/hooks/privacy-scan.js` (flags, `HIT` lines, last line, exit codes, `--json`);
the pre-push hook path `.claude/hooks/git/pre-push` and the `core.hooksPath` value. Every row
below stands.

Record: no proposal; this ADR applies ADR 0012 to the base itself, as its critic review asked
(objection 6). `proposed` because three of the surfaces are files the owner writes by hand (the
policy file, grant files, idea blocks), which ADR 0012 says the owner confirms; the owner's answer
to "is this the list, and is anything missing that you edit and expect to keep working" turns the
status to `accepted` with no other edit. The reference check (below) is technical and does not
wait for the answer.
Amended 2026-10-18: the owner was asked whether the list is complete and declined to confirm it,
because best practices are changing too fast to say whether it will hold. The status stays
`proposed` and the promise is observed as if accepted. The question is not re-asked; it is
re-raised only when a trigger fires: a sibling update that breaks a hand-edited file, or a
best-practices revisit (`triage` skill) that changes the list.

## Context

Three repositories run the base's hooks and skills, two of them on older bases that share
`~/.claude` with this one, and the update skill reads one line of a project's own ADRs. ADRs
0009, 0010 and 0011 were each forced by a format or a file that something else depended on and
nobody had named: the base line's meaning, the update mode it implies, the usage cache that older
siblings still read. Verified 2026-10-16: `policy.js` treats an unknown key as a malformed file
and loads base defaults with a note on stderr (its header, line 12; the open idea
`2026-10-13-malformed-policy-falls-back-silently.md`), so a renamed or removed key silently
replaces a project's whole policy; the hooks and status line require only `fs`, `os`, `path`,
`child_process` and relative modules; ADR 0006 names `HOOK_POLICY_FILE` as the one test seam.

## Decision

### The promise

An addition (a new key with a default that preserves old behaviour, a new optional field, a new
block kind that older readers may ignore) is free and needs no ADR. An incompatible change (a
removal, a rename, a changed type or meaning, a changed format of existing output, a stricter
parse) is a major choice under `CLAUDE.md`: proposal, critic, ADR, and the ADR says how each
sibling repository migrates. There are no version numbers; the ADR that changed a surface is its
version, and `git log` of the carrying file is the history. A file or line written against an
older base loads under a newer one with the same meaning.

### The surfaces

| Surface | Carried by | Readers | Record |
|---|---|---|---|
| The policy file: every key, its type, default and meaning | `.claude/hooks/policy.json`, schema in `policy.js` | the three hooks, the status line, every project's own file | ADR 0006 key table, ADR 0011 |
| Grant files: the file shape and the directory | `~/.claude/usage-gate-overrides/*.json`, written by the owner | `usage-gate.js` | ADR 0006, The HOLD override |
| The idea file grammar: frontmatter keys and the three closing blocks | `docs/ideas/*.md` | `triage`, every drain in the three repositories | ADR 0007, `docs/ideas/README.md` |
| The base line | `^Hatchery base: [0-9a-f]{7,40}` in a project's `docs/design/decisions/` | `update-from-hatchery`, the `adr` skill | ADRs 0004, 0009, 0010 |
| The usage cache: directory, file name, shape, the prune bound (never a file under a day old), the old `usage-state.json` left alone | `~/.claude/usage-state/<session_id>.json` | `statusline.js`, `usage-gate.js`, of every base version on the machine | ADR 0011, hook specs section 3 |
| The hook seams: `HOOK_POLICY_FILE`; `test-hooks.js --input <file> <hook>` with `--home` and `--config-dir`; the exit codes and fail modes | `.claude/hooks/*.js`, `test-hooks.js` | a project reproducing a hook bug; the harness | ADR 0006 Test seams and Fail modes |
| The handoff paths: `@docs/HANDOFF.md` in `CLAUDE.md`; `docs/HANDOFF.md` and `docs/handoff/*.md` capped at 40 each and 120 together; the map's `@handoff/<file>.md` lines as the directory's index | `CLAUDE.md`, `post-edit.js`, `pusher.md` | every project in either shape | ADR 0014 |

Not promised: the status line's text; the wording of any deny; harness case ids and labels;
skill, agent and rule prose; `policy.examples/`; the `cswap list --json` shape (consumed, not
ours; ADR 0011); the findings, reference and operator frontmatter, trigger: the first program
that reads them. A promise on a surface a user touches by hand (the first three rows) is the
owner's; the rest are technical.

### The reference fitness check

In `.claude/hooks/test-hooks.js`, case group `F`, so the test command runs it and pusher check 8
fails a push on it. Config `.claude/hooks/fitness.json`, read only through the harness:

```
{ "deps": { "budget": 0, "paths": [".claude/hooks/*.js", ".claude/statusline.js"], "vendored": [] },
  "skip": [] }
```

Check `deps`: in every file the `paths` match, each `require('<m>')` literal whose `<m>` does
not start with `./` or `../`, with a `node:` prefix stripped, must satisfy `module.isBuiltin`
(not the `builtinModules` list, which omits prefix-only modules such as `node:test`); the number
of distinct other modules, plus the vendored files, is at most `budget`. `deps.vendored` is an
optional list of repository-relative files, ADR 0012's copies under a page: each must exist and
carry an `SPDX-License-Identifier`, each counts against the budget, each is scanned for its own
requires, and a duplicate entry is ignored. Detail on failure: `<file>:<line> requires <m>` per
offender, `<file> is vendored` per copy. The harness line is the contract's line with the case id
in front: `PASS F1 deps`, `SKIP F1 deps: <reason> (<idea>)`, or `FAIL F1 deps: <detail>; from
.claude/hooks/fitness.json <key>; lift: skip entry (coordinator, one push, named in the commit
message) or ADR amendment (architect)`, where `<key>` names the config key at fault
(`deps.budget`, `deps.paths`, `deps.vendored`, `skip`). A `skip` entry is `{ "check": "deps",
"reason", "date", "idea" }`: `date` matches `YYYY-MM-DD` and `idea` matches
`docs/ideas/YYYY-MM-DD-<slug>.md` and names a file that exists; anything else, or a missing
field, fails the check with `skip entry malformed` as the detail. A config error (unreadable,
not an object, a key of the wrong type, a path matching no file) fails the check in the same
line; it never exits 2, so the run goes on and reports the other cases. Cases, each isolated in
the scratch tree like the post-edit cases: F1 the real config and tree pass; F2 a scratch config
over a scratch tree whose one file requires `left-pad` fails, and the line carries `lift:`; F3
the same with a skip entry naming a scratch idea file that exists prints `SKIP` and passes; F4
the same skip with the idea path absent fails with `skip entry malformed`; F5 a vendored file
with its licence line fails a budget of 0; F6 a vendored file without an `SPDX-License-Identifier`
fails within budget. `--only F` selects them, `--only deps` runs F1 alone, `--verbose` lists what
was scanned. Built by a `coder` from this section; Node built-ins only, as the check itself
requires.

### Deferred

- A compatibility check for the surfaces above (a fingerprint of the policy schema and the idea
  grammar), trigger: the first incompatible change proposed to either, which then lands with it.
- A migration note format for sibling repositories, trigger: the first incompatible change.
- Re-raising the owner's question about the list (see the amendment of 2026-10-18), triggers: a
  sibling update that breaks a hand-edited file; a best-practices revisit that changes the list.
  Neither is visible to the `triage` scan, so whoever sees one raises it.

## Consequences

- An incompatible change to the policy schema, the grammar, the base line or the cache is a
  proposal from now on; a new key or field is an ordinary change. Nothing visible changes today.
- Costs: one config file and four harness cases to keep; a budget of zero that any future hook
  dependency must argue past by ADR, which is the point.
- The owner's answer is one word; until it comes the status is `proposed` and the promise is
  observed as if accepted, since every row restates what ADRs 0006 to 0011 already fixed.

Predictions (the trigger scan reads these; superseding this ADR records each outcome first):
- Prediction, check when the next sibling update runs: the sibling edits none of its policy
  file, grants or idea files to take the update.
- Prediction, check by 2027-04-16: every change to the policy schema since this ADR added a key
  with a preserving default, or has an ADR; none renamed or removed one without.
- Prediction, check by 2027-04-16: group `F` has never been skipped, or each skip has its idea
  file and an amendment that followed.
