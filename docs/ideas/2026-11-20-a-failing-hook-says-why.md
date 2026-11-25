---
date: 2026-11-20
kind: bug
---

# A failing hook says why

On 2026-11-20 from 03:26, every Bash and PowerShell call, the coordinator's and a sweep agent's, failed with "PreToolUse hook error: [command hook 2]: failed; blocking (no stderr)" until the owner rebooted the machine; ripgrep (the search tool) failed too. Command hook 2 of the Bash|PowerShell matcher is `.claude/hooks/git-guard.js`, set `onFailure: block` in `.claude/settings.json`. Blocking on failure is right for a guard; failing silently is not: a crash looked the same as a refusal, and no session could tell why. The sweep's emulator had just peaked at 5.3 GB, so the machine may have run short of memory (process start failing), but the cause is unproven.

Wanted: git-guard catches its own errors and prints the reason to stderr (the exception, and where cheap the free physical memory and commit charge), so a blocked call says "guard crashed: <why>" rather than nothing; a test in `node .claude/hooks/test-hooks.js` for the crash path. If node itself cannot start, nothing in the script helps: then the handoff's note is the record, and the sweep's memory floor (`docs/ideas/2026-11-20-the-sweep-stops-below-a-memory-floor.md`) is the prevention.

The same day a chore saw git-guard refuse an awk line that contained backticks inside quotes ("unexpanded argument"), likely a false positive of the rule in `docs/reference/windows-and-shells.md`; check it with a hook test while here.

The owner, 2026-11-20: "Record them all. We'll triage and sanity check later."

Came up again: 2026-11-25, coordinator (claude-fable-5-2), during the update from the base: conflict markers in two scripts the guard loads made every call fail with "git-guard: internal error (Unexpected token '<<')", which named no file, so the broken script had to be guessed; the Plan's item (3) takes it in.

Plan: the first half is already in the tree: `git-guard.js` installs an `uncaughtException` handler before any
`require` and exits 2 with "git-guard: internal error (...)" on stderr, and `test-hooks.js` (line ~229) tests that
path. So the morning's "no stderr" means node never ran the script (process start failing, the memory guess), which
no hook code can report; the sweep's memory floor idea is the prevention. What is left for one pass: (1) a hook test
of an awk line with backticks inside single quotes in the same call as a git command; if the guard refuses it, teach
`shell-parse.js` that backticks inside single quotes are not a substitution (POSIX), keeping the refusal for double
quotes and heredocs, and correct `docs/reference/windows-and-shells.md`'s line; (2) the handler adds the free physical
memory (`os.freemem()`) to its message; (3) the message names the script that failed to load (the first
`.claude/hooks/` frame of the error's stack, or each `require` wrapped so a `SyntaxError` carries its path), with a
hook test that loads a copy of the hooks whose `policy.js` holds a conflict marker. Verify: `node .claude/hooks/test-hooks.js` green with the new cases. These are
base-owned hook files: a departure row in ADR 0038 and a carry-back, as the agreement says.
