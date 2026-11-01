---
date: 2026-11-01
status: open
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

The next wave's sweep task: `emulator-stop.js` leaves the emulator and the lock alone when the hook
input's `background_tasks` is non-empty (or `stop_hook_active` says the agent will resume), and stops
it on the final stop only; a hook test case feeds both shapes; the sweep skill says a sweep agent
polls `steps.tsv` with bounded foreground calls rather than yielding. Until then a sweep agent is
briefed to never end its turn while its emulator runs.
