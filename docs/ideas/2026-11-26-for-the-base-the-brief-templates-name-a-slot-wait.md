---
date: 2026-11-26
kind: chore
---

# For the base: the brief templates name the wait for a shared machine resource

Source: T375's implementer (claude-opus-5-5), a task of wave 49; the report the owner asked for in place of a carry-back
branch (the owner, 2026-11-26: "for pushbacks to hatchery we want to report them there then a hatchery agent does the
research and integration"); not sanity-checked by the base.

What changed here, in commit 9ff4fb8a on `task/T375` (its row: ADR 0038's departure table, the row for
`.claude/skills/brief/`): `.claude/skills/brief/implementer.md` (Machine facts) and `verifier.md` (step 1) each gained a
line naming this project's emulator boot with `-WaitForSlot 60`, which waits for a free slot at the machine's emulator
cap instead of refusing, and saying that a wait longer than ten minutes runs as a background command polled for its
exit (an agent's foreground command ends at ten minutes, so a longer foreground wait is cut off). The wait itself is
the project's (`tools/android-emu.ps1`: the oldest waiter first by a ticket keyed by the requester's owner name, so a
waiter cut off that asks again keeps its place).

What the base might take: not the emulator line, which is this project's, but its general form for the implementer and
verifier templates: when a brief hands an agent a resource several agents share on one machine (a device, a port range,
a licence seat), it names the command that waits for it with a time limit, and the templates say that any wait which
may pass the shell tool's ten-minute ceiling runs in the background and is polled. A base research pass could check
whether other projects' briefs hit the same ceiling, and whether the ten-minute figure belongs in the base's reference
on the agent harness rather than in each template.
