---
date: 2026-11-20
kind: bug
---

# A failing hook says why

On 2026-11-20 from 03:26, every Bash and PowerShell call, the coordinator's and a sweep agent's, failed with "PreToolUse hook error: [command hook 2]: failed; blocking (no stderr)" until the owner rebooted the machine; ripgrep (the search tool) failed too. Command hook 2 of the Bash|PowerShell matcher is `.claude/hooks/git-guard.js`, set `onFailure: block` in `.claude/settings.json`. Blocking on failure is right for a guard; failing silently is not: a crash looked the same as a refusal, and no session could tell why. The sweep's emulator had just peaked at 5.3 GB, so the machine may have run short of memory (process start failing), but the cause is unproven.

Wanted: git-guard catches its own errors and prints the reason to stderr (the exception, and where cheap the free physical memory and commit charge), so a blocked call says "guard crashed: <why>" rather than nothing; a test in `node .claude/hooks/test-hooks.js` for the crash path. If node itself cannot start, nothing in the script helps: then the handoff's note is the record, and the sweep's memory floor (`docs/ideas/2026-11-20-the-sweep-stops-below-a-memory-floor.md`) is the prevention.

The same day a chore saw git-guard refuse an awk line that contained backticks inside quotes ("unexpanded argument"), likely a false positive of the rule in `docs/reference/windows-and-shells.md`; check it with a hook test while here.

The owner, 2026-11-20: "Record them all. We'll triage and sanity check later."
