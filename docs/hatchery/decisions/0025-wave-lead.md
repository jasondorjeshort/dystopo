# 0025. A wave lead runs one change: a script names each step, a hook refuses the rest

Date: 2026-11-23
Status: accepted, superseded in part by 0027
Superseded in part by [0027](0027-wave-lead-withdrawn.md), 2026-11-24: the lead is withdrawn; the keys and group `PS` stand.

Record: proposal `../../design/proposals/0025-wave-lead.md` (option B as revised) and
`../../design/proposals/0025-critic-review.md` (two blocking, eight serious, five minor; all accepted, nine with
a change). Evidence: the findings of 2026-11-18 (agent-to-agent messaging) and 2026-11-23 (the headless probe)
under `../../findings/`. Exact texts, the script's contract, the harness cases: [the sibling](0025-lead-contract.md).
Supersedes in part ADR 0006 with the hook specs, section 3 (the gate looks at the caller), ADR 0007 (a led
change's fix round is checked) and ADR 0022 (its HOLD row gains the lead's sentence); amends ADR 0013 by
additions and lands the policy half of its Deferred compatibility check. The owner decided what the gate spends
and the order of work (item 2); the rest is technical and the coordinator's.

## Context

Every change costs the coordinator a turn per brief and per report (writer, reviewer, each fix round, runner),
and each turn re-reads the most expensive context in the session. The finding of 2026-11-18 rated relay by file
path high confidence and a bounded writer-reviewer loop medium. Option A (reports by path, the coordinator
still relaying) was built on 2026-11-23 as its own change; this record decides option B, a `wave-lead`
subagent, and what A left open. Ruled out earlier: agent teams, peer messaging, a lead that pushes.

Verified by the critic, hooks fed payloads: the gate as built refuses every caller from HOLD; a policy file
with a key its `policy.js` does not know makes `git-guard` deny every git command; the guard lets a
`wave-lead` write the tree by shell. By the headless probe (Claude Code 2.1.330): `agent_id` and `agent_type`
are in the hook payload at depth 1 and 2 and absent in the main session; an Agent call's `model` overrides
the definition's pin; `Agent(<type>)` in a subagent's `tools:` restricts nothing; a resumed agent keeps its
context, and its reply reaches the resumer only while it is still working. Not observed (item 9): any of that
interactively, what stopping a lead does to its agents, meters under an idle coordinator, workflow hooks.

## Decision

1. **The lead.** `.claude/agents/wave-lead.md`: Sonnet; tools `Bash, Agent, SendMessage`; `maxTurns: 100`. It
   runs one change (a wave is the batch a coordinator launches together) and decides nothing: it runs `node
   .claude/hooks/lead.js next <d>`, makes exactly the call printed, runs `lead.js wait <d>`, and repeats until
   the script prints `verdict`. Without a Read tool it cannot paraphrase a review. One lead per work tree.
2. **The owner's decisions.** A grant lifts the finish line with HOLD: "Yes, per meter" (the owner,
   2026-11-23). The weekly finish line: "At HOLD, 97, until measured" (the owner, 2026-11-23). Other projects:
   "Off by default" (the owner, 2026-11-23). Who may launch past HOLD: "Only the wave lead" (the owner,
   2026-11-23). Hatchery's 5-hour line: "75, halfway" (the owner, 2026-11-23). Order: "A now, B after its
   fixes" (the owner, 2026-11-23). The probe: "An agent, headless" (the owner, 2026-11-23).
3. **The gate.** Two additive policy keys: a per-meter `finish` (`null`, meaning the meter's `hold`) and
   `usage.finishAgents` (`["wave-lead"]`). A caller with a non-empty `agent_id` whose `agent_type` is on the
   list, in a tree where the lead hook is installed, passes a meter's HOLD while its reading is below a
   `finish` set above that `hold`. A deny line still wins; a grant lifts HOLD and finish alike, per meter. The
   finish-line deny names its lifts (the reset time, the owner's grant). With nothing set, every caller gets
   today's text byte for byte. Hatchery sets `fiveHour.finish: 75` and no weekly finish.
4. **The script and the hook.** `.claude/hooks/lead.js` holds the loop: `step(dir)` derives the one permitted
   step from the files of the lead directory and the repository, never from what an agent returned. A
   PreToolUse hook runs the same function for every type on `usage.finishAgents`: an `Agent` call must carry
   the step's type and prompt (one-line templates holding paths), no `model`, and is permitted once;
   `SendMessage` goes only to the recorded writer; `Bash` runs only the script's own four commands; `Write`
   and `Edit` never pass. A refused call is a stop. The hook logs each permit with the meter readings and
   records the writer's agent id. It is silent for everyone else and refuses a lead's call on any error of
   its own; where it is not installed or cannot load, the gate gives no finish line and `init` refuses.
   Harness group `WL` drives every rule on fixtures; the CLI is the debug interface.
5. **Order of a change.** Writer; the `runner` commits (the writer drafts the message and lists its files); a
   `reviewer` reads the unpushed commit and its message; while blocking or serious findings are open and
   rounds remain, the writer is resumed with the review's path and answers each id `fixed` or `declined:
   <reason>`, the runner amends (a follow-up commit when the commit is no longer HEAD or is pushed), and a
   fresh reviewer checks the fix. At most two fix rounds; a brief may lower the cap, never raise it. Stops: a
   blocked writer, a decline, ids open at the cap, a verdict of rethink, an unexplained dirty path, a refusal,
   no output within 60 minutes. A stop at a review or a check leaves a clean tree; one at a commit step, in a
   fix or at a response can leave the change's files uncommitted: its commit step takes them (after a response,
   a commit the coordinator briefs), never a WIP commit. The pusher refuses while `lead.js status` exits 1.
6. **Review.** A led change gets the same one review. The check after a fix is an addition and is called one:
   it stands in for the coordinator's reading of the fix, reads `git diff <a> <b>` first, then the earlier
   blocking and serious findings, lists those still open and new defects in the diff only, and never sees the
   writer's response. A fix the coordinator relays itself (A) needs no check: it reads the response file. Under
   ADR 0021 a lead is for the full lane; the check is not that lane's verifier, who reads the whole range.
7. **The record.** `<repo>/.lead/<slug>/`, git-ignored (`init` checks, and prints the line to add), holds the
   two briefs, the reports, the commit message and `lead.json` (state, agent ids, permits). It survives the
   session, so a stopped change resumes at the first missing output, under a new lead or by hand. A's relay
   keeps its scratchpad directory, any name, unless started with `lead.js init`. The commits are the durable
   record; the coordinator closes the directory once it has filed what ADR 0026 item 9 still files.
8. **The coordinator's part.** It writes `brief-writer.md` and `brief-reviewer.md`, launches the lead with one
   line, and reads `lead.js verdict <d>`, whose `Next:` line says who lifts each stop and how. It alone
   overrides a finding, widens scope, files ideas and asks for the push. At HOLD the handoff is committed
   first, by pathspec (ADR 0022 stands; an index-lock error is retried once); while a change is open the
   coordinator commits only what it wrote, the handoff and queue files.
9. **Platform behaviour.** Shown not to matter: foreground or background launches, and where a resumed
   writer's reply goes (the loop polls for files); a fork, a teammate or `--agent` (no `agent_id`, or a type
   off the list); the lead's final text (the coordinator reads `lead.js verdict`); hooks in workflow agents
   (no workflow here). Named preconditions, observed in step 4; step 6 waits for P1 and P4. P1: in an
   interactive session a lead's `Agent` and `SendMessage` calls reach the hook as `agent_type: wave-lead`, a
   send's `recipient` equals its `to` and its `content` is its `message` or a shortened preview of it, and each
   step's file appears. P2: the lead's Agent PostToolUse carries `tool_response.agentId` there; without it fix
   rounds run on a fresh writer (Deferred). P3: the 5-hour reading in the permits rises during a lead's run while
   the coordinator is idle; step 5 waits for it, and without it no finish is set and the owner is asked whether a
   change started below HOLD may run on unmetered. P4: whether the agents a lead launched go on once it ends or is
   stopped, and whether the coordinator can see and stop them by the ids in `lead.json`; read from the
   child's transcript after a lead is stopped mid-step. Nothing rests on the answer: `lead.js stop` stops
   only the lead's next call, no step is re-issued within the hour unless the coordinator says its agent is
   not running (`resume --now`), and the coordinator commits none of an open change's paths.
10. **Build order**, each step one reviewed commit, relayed by path (A) unless a lead is named:
    1. `coder`: `lead.js`, group `WL`, `.lead/` in `.gitignore`. No hook entry yet.
    2. `coder`: the keys, the gate, the status line, `--schema`, `policy.schema.json`, cases `UF` and group
       `PS`, ADR 0006's key table and fail-mode rows, hook specs section 3.
    3. `tester`: the hook entries in `.claude/settings.json`, `wave-lead.md`, the sibling's section 7 (five
       agreement files, the `brief`, `commit` and `handoff` skills, `reviewer.md`, `pusher.md`), `README.md`.
    4. `wave-lead`: the shakedown, one small change that gets a review, in an interactive session, and one
       lead stopped mid-step; before `close` a `tester` records P1 to P4 in the probe finding.
    5. `tester`, after P3: `"fiveHour": { "finish": 75 }` in Hatchery's `policy.json`; then one led change run
       into the finish line on purpose, `hold` and `finish` lowered for that run on the owner's word.
    6. `wave-lead`: the next five changes that qualify; then a `tester` writes the pilot finding.
11. **The pilot.** Six led changes, the shakedown included, none editing the lead's own bounds (`lead.js`,
    `usage-gate.js`, `policy.js`, `git-guard.js`, `.claude/settings.json`, `wave-lead.md`); such a change, ADR
    0021's T-b, T-e and T-f among them, is relayed by A. Per change, from the transcripts' per-turn `usage`,
    price-weighted: `B`, the coordinator's cost for the turns that touched the change; `L`, the lead's own
    cost; `A`, twice the lead's permit count times the mean cost of a coordinator turn in that span (the relay
    it replaced). K1: `B` at most half of `A` in five of six. K2: `B + L` below `A` in four of six. K3: at
    most one change where the loop closed a finding the coordinator, reading the files afterwards, would have
    declined or deferred. K4: at most one change stopped by a lead-guard deny or `maxTurns`. All four hold:
    the lead stays. K1, K2 or K3 fails: withdrawn. K4 alone fails: the model becomes `opus`, three more are
    judged on K2 and K4, and a second failure withdraws. The finding reports the rounds each change used.
12. **Surfaces and exit (ADR 0013).** The keys are additions with preserving defaults: no file written before
    changes meaning. They are promised from now on and stay, inert at `null`, if the lead is withdrawn, which
    removes the agent, the hook entries, the lead's sentences and Hatchery's `finish`. The Deferred
    compatibility check lands for the policy schema as harness group `PS` over a recorded `policy.schema.json`.
    Not promised: `lead.js`'s commands and output, `.lead/`, the prompts, the deny texts.

Migration (the policy schema; additions, so no tree must edit anything to keep its behaviour):

| Tree | Arrives by update | The project edits | When | Check |
|---|---|---|---|---|
| Hatchery | n/a | `.gitignore` (step 1); `policy.schema.json` generated (step 2); `fiveHour.finish: 75` (step 5) | with the build | `node .claude/hooks/policy.js` prints the finish; `PS1` passes; `lead.js status` exits 0 |
| dystopo, broodforge, ER-Workspace (both), Exiled Reign, regenesis, droplet-management, any other tree with a base line | `lead.js`, the gate, `policy.js`, the schema file, the agent, the agreement and skill text; `.claude/settings.json` is mixed, so the hook entries are hand-merged | Nothing to keep HOLD stopping everyone. To use a lead: the `.gitignore` line `init` prints. To let it pass HOLD: a `finish`, its owner's spend decision, written only after the update (an older `policy.js` reads the key as malformed and the guard then denies every git command) and the hook entries (without them the gate gives no finish line) | its next update | `node .claude/hooks/policy.js` prints no error; the test command passes `PS` and `WL`; `lead.js init` refuses until the hook entries are merged |
| A tree that never updates | nothing | nothing; it must not copy the keys by hand | n/a | its policy loads as before |

Deferred, each with its trigger: a weekly `finish` above HOLD (the pilot finding gives the largest 7-day delta
of one step; the owner then sets `sevenDay.finish` or leaves it); agent ids recorded by `SubagentStart` (P2
fails); a lead in a task worktree or a PR flow (a project with one asks); a workflow script for sweeps (its
proposal runs the hook probe inside a workflow first).

## Consequences

- Easier: a reviewed change costs the coordinator two briefs, one launch and one verdict; a stopped change
  keeps its state on disk and says who lifts it; the loop, the cap and the launch list are code with cases.
- Harder and costs: one more script, with a hook on every launch, send, shell call and write (it exits at
  once for all but a lead); three policy keys kept for good; a check per fix round that relayed changes do
  not pay; a commit left unpushed with known findings when a change stops, held back by the pusher's check.
- Under a grant no finish line protects the last agent from the plan cap unless a `deny` is set; under ADR
  0021's `usage.mode: "warn"` the coordinator writes that grant itself, so the line then stops only a lead
  nobody is watching. The status line gains ` finish <n>`: wording the owner sees, reversible with a word.
- Supersede when Claude Code enforces a subagent's launch list or model pin, or resumes one across sessions.

Predictions (the trigger scan reads these; superseding this ADR records each outcome first):
- Prediction, check by 2027-02-28: six led changes have closed in Hatchery, and the pilot finding says stay. Outcome 2026-11-24: failed, six closed and the pilot finding reports K1 at 0 of 6, which item 11 answers with withdrawal (ADR 0027).
- Prediction, check by 2027-05-23: no tree reports a HOLD that failed to stop a type off `usage.finishAgents`. Outcome 2026-11-24: moot, no tree set a `finish`, so no finish line ever ran, and ADR 0027 removes it.
- Prediction, check when the next sibling update runs: the sibling edits nothing in its policy file to take it. Outcome 2026-11-24: moot here, no sibling had updated; the keys stay, and ADR 0027 carries the prediction on.
