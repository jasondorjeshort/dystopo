---
verified: 2026-11-26
source: observed in T298 (recorded hook inputs and past subagent transcripts of wave 34); code.claude.com hooks docs, read in finding 2026-11-16-research-for-pending-ideas; T376 (the subagents folder of every session on the PC and a PreToolUse probe's SendMessage keys, 2026-11-23)
---

# Per-agent cost: the hooks' lines and tools/agent-cost.py

Every subagent's launch and stop are recorded in one tab-separated file per session,
`~/.claude/usage-state/<session_id>.agents.tsv`, never in the repository (T298). The figure a wave's close uses for a
task is the token sum of its agents; the meter readings beside it are the check, since the meter is shared with
whatever else the account runs. The status line prunes only `.json` and `.tmp` files there, so these files stay
until deleted by hand.

## What each hook is handed

- `PreToolUse` on `Agent`: `session_id`, `tool_use_id`, and `tool_input` with `description`, `subagent_type`, `model`,
  `run_in_background` and the prompt. No meter reading: those reach only the status line, which writes
  `<session_id>.json` in the same folder.
- `SubagentStop`: `session_id` (the parent session's), `agent_id`, `agent_type`, `agent_transcript_path`,
  `last_assistant_message`, and `background_tasks` (non-empty when the agent yields rather than ends). No tokens,
  duration or model.
- Beside the transcript (`.../<session>/subagents/agent-<agent_id>.jsonl`) Claude Code writes
  `agent-<agent_id>.meta.json` with `toolUseId` (the launch's `tool_use_id`), `description`, `model` (the alias asked
  for) and `agentType`. That is the join.
- In the transcript every assistant line carries `message.id`, `message.model` and `message.usage`
  (`input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`, `output_tokens`). A request writes one
  line per content block (thinking, text, each tool call) and **repeats the same usage on each**, so lines are
  deduplicated by `message.id`: on a wave 34 transcript, 8 usage lines were 5 requests.
- `PostToolUse` on `Agent` is not used: its usage covers the final request only, and a background launch has none.

## The completion notice's figure

An agent's completion notice gives `subagent_tokens`, which is the **last request's** input + cache write + cache read +
output: the size of its final context, not a total. It equals the `last_context` column exactly for most agents of
wave 34 and within 0.4 % for the rest (cause not traced). The cumulative cost is the sum over requests,
which the hook computes: for T291's implementer, 348 thousand fresh tokens (input, cache write, output) and 15.8
million cache reads against a notice of 277 thousand. The hand reading of wave 32 ("implementers 262 to 499 thousand", the
idea of 2026-11-10) was read off notices, so it measured final contexts.

## The file

A header line, then one line per event; empty cells are unknown values. Columns:

| Column | Launch line (`usage-gate.js`) | Stop line (`agent-meter.js`) |
|---|---|---|
| `time` | UTC, to the second | the same |
| `event` | `launch` | `stop` |
| `tool_use_id` | from the input | from `meta.json`, empty when absent |
| `agent_id` | empty | from the input |
| `agent_type` | `subagent_type`, else `general-purpose` | from the input |
| `model` | the alias asked for (`opus`, `sonnet`) or empty | the resolved model of the transcript's last request |
| `description` | from the input | from `meta.json` |
| `five_hour`, `seven_day` | percent from `<session_id>.json`, empty when missing or past its reset | the same, at stop |
| `meter_age_s` | seconds since the status line wrote that file | the same |
| `requests`, `input`, `cache_write`, `cache_read`, `output` | empty | sums over the transcript's requests |
| `last_context` | empty | the last request's total: the notice's figure |
| `note` | `background` for a background launch | `yield`; `partial: N of M MB read`; `transcript unreadable (CODE)`; a skipped over-long line |

A `resume` line (T376) is written by `usage-gate.js` when it lets a `SendMessage` through at HOLD because its target
was launched in this session below both holds: the launch's `tool_use_id`, `agent_type`, `model` and `description`,
the target as `agent_id`, the readings at the resume, and in `note` the launch's time and readings
(`launched <time> at 5h N%, 7d N%`); the token columns stay empty. The gate finds the launch through the target's
`agent-<id>.meta.json` in the session's `subagents/` folder (the payload's `transcript_path` is
`<project>/<session>.jsonl`, its agents in `<project>/<session>/subagents/`) and the one `launch` line with that
`tool_use_id`; a resume addresses the agent by its id (`a` and 16 hex digits), never by a name. `tools/agent-cost.py`
reads launch and stop lines only: count the `resume` lines by hand for the work a wave did past the hold.

Only an allowed launch gets a line (a denied one exits before it). A continued (`SendMessage`) or yielding agent
writes several stop lines; the sums are cumulative, so the last one counts.

## Bounds

The gate's append runs after its verdict inside a `try`: a fault loses the line, never the launch, and never changes a
deny. `agent-meter.js` exits 0 with nothing on stdout on every path, reads the transcript in 1 MB chunks (never whole),
parses only lines holding `"usage"` and `"assistant"`, stops at 600 ms and says so in `note`, skips a line over 64 MB,
and ends itself after 2 s whatever stdin does; `settings.json` gives it 10 s. Measured: 84 MB in 0.37 s, 300 MB of
filler with a 70 MB line in 0.26 s, both with node's start. Test seam: `AGENT_METER_DEADLINE_MS`. Cases AM01 to AM12
in `node .claude/hooks/test-hooks.js --only AM`; one recorded input by hand:
`node .claude/hooks/test-hooks.js --input <file> agent-meter --home <scratch>`.

## Summing a task

```
python -I tools/agent-cost.py T298              # one cost line from every *.agents.tsv in ~/.claude/usage-state
python -I tools/agent-cost.py T298 --json       # the agents and totals
python -I tools/agent-cost.py T298 --dir <folder> | --file <tsv> ...
```

An agent belongs to a task when the task id stands as a word in its description (`T29` never matches `T298`), so a
brief's description names the task. A stop joins its launch by `tool_use_id`; one without (no `meta.json`) joins the
earliest unjoined launch of the same file and agent type before it, and the line says how many joined each way.
Tested by `python -I tools/test_agent_cost.py`.
