---
date: 2026-11-26
kind: chore
related: [2026-11-22-a-lint-for-code-after-a-task-comment.md]
---

# A commit hook refuses conflict markers in tracked files

Source: coordinator (claude-fable-5-2), a triage; offered and accepted by the owner; not sanity-checked.

On 2026-11-26 a task branch was pushed with merge-conflict residue in two tracked docs (the line
`||||||| parent of ...` in `docs/findings/README.md` and `docs/reference/android-debug-routes.md`, and one entry
doubled). Nothing refused it: the privacy hooks read for places and names, and `check` does not read docs. It cost a
verifier's FIX round and a fix-up commit (T389 of wave 49).

The idea: `tools/git-hooks/pre-commit` refuses a staged text file that holds a line starting with a conflict marker
(seven `<`, `|` or `>` followed by a space, and a bare line of seven `=` between two of them), naming the file and the
line; fixtures that must hold such lines are listed by path. One pattern, no build.

Lever: the slip already reached a pushed branch, and four agents at once (ADR 0070) rebase more often.

## Open

- Whether the check belongs in the project's hook or in the base's push scan (then it is a report for Hatchery).
- Binary files and files that quote a marker on purpose (hook tests, this file's own kind of text).
