---
date: 2026-11-01
status: resolved (T201)
area: tooling / emulator lock, hooks, sweep
---

# T190's stop hook kills the emulator of an agent that yields its turn with work still running

## Question

The post-wave sweep of wave 24 (the first sweep after T190's lock and close-on-done merged, e1863320)
lost its emulator three times: `device-boot` and then every device part reported "emulator did not
boot" or "emulator exited (code 0) before booting", a graceful console kill about 80 s after the boot.
Why, when nothing else ran on the host and no cap was reached?

## What was tried

The sweep agent (Sonnet) ran `tools/sweep.ps1 -NoDaemon -EmuMemory 3072` from the main checkout on
7e7c3dd7. Its tool call on the sweep timed out at the harness maximum, and it waited for the sweep's
`steps.tsv` by ending its turn while the sweep ran on as background work of its own. The lock folder's
`stop.log` shows the `SubagentStop` hook (`.claude/hooks/emulator-stop.js`) firing at 07:36, 07:44 and
07:54 UTC with "agent <id> ... stopped emulator-5640 ... by console kill; lock removed", once per yield.
The sweep's two first device runs died this way (the second mid `device-tour`). A third run, in which
the agent polled with bounded foreground tool calls and never ended its turn, ran every device part
green.

## Result

The harness fires `SubagentStop` whenever a subagent stops with background work of its own still
running (its notification reads "waiting on its own background work"), not only when the agent ends
for good. T190's hook treats every `SubagentStop` as the end of the agent and stops the emulator the
agent's claims name, so an agent that legitimately runs a long process in the background (the sweep,
a long replay, an `android-ui.py` part) loses its emulator the first time it yields. The hook's input
carries `background_tasks` (T190's dry run listed the field) and `stop_hook_active`, so the hook can
tell the two cases apart; it does not read them today (`emulator-stop.js` has no reference to either).
The wave 24 briefs told every agent to wait in the foreground, which is why only the sweep hit it: its
single command outruns the tool's maximum timeout.

## Next

Done in T201 (wave 25). The critic's correction: `stop_hook_active` is the harness's loop guard (true when
the agent already continued because a Stop hook asked it to), not "the agent will resume"; the hook reads
`background_tasks` only. The input shape, read from the installed Claude Code's hook schema on 2026-11-01
(the `SubagentStop` input: `hook_event_name`, `session_id`, `transcript_path`, `cwd`, `stop_hook_active`,
`agent_id`, `agent_transcript_path`, `agent_type`, optional `last_assistant_message`, optional
`background_tasks` and `session_crons`; a `background_tasks` entry has `id`, `type` ('shell', 'subagent',
'monitor', 'workflow'), `status`, `description`, and `command` / `agent_type` / `server` where they apply;
an empty array when nothing is in flight; the field's description: it lets hooks tell "session is done"
from "paused waiting for background work to wake it"). `emulator-stop.js` leaves the emulators, locks and
claims alone while the array is non-empty and stops on the stop that has it empty or absent
(`test-hooks.js` ES09 and ES10 against the harness's fake qemu copy). The sweep skill says a sweep agent
polls `steps.tsv` with bounded foreground calls and never yields while its emulator runs.

## Remaining leaks

- An agent that yields with background work and is then killed (the owner stops it, the session ends) never
  has its final stop with an empty array, so its emulator and lock stay until the cap refusal names them
  (`android-emu.ps1 -Stop` by serial, or the dead-lock reclaim once the PID is gone).
- `background_tasks` is session-wide ("registered in this session"), not per agent: while any sibling
  agent's work is in flight, an ending agent's emulator is left until a later stop sees the array empty.
  That errs toward a lingering emulator, never a killed one; the cap and the next stop bound it.
