# 0027. The wave lead is withdrawn: its agent, script and finish line go, the policy keys stay

Date: 2026-11-24
Status: accepted

Record: no proposal. ADR 0025, which had its own proposal and critic review, fixed the test, the thresholds and the
exit in its items 11 and 12; this record applies them and settles what item 12 left open. Evidence: the findings
`../../findings/2026-11-24-wave-lead-pilot.md` and `../../findings/2026-11-23-nested-agent-hook-probe.md`. Supersedes
in part [ADR 0025](0025-wave-lead.md) with its [sibling](0025-lead-contract.md); item 2 says what stands. It withdraws
what 0025 superseded in ADRs 0006, 0007 and 0022, which read again as they did before it.

## Context

ADR 0025 item 11 set a pilot of six led changes with fixed thresholds and the rule "K1, K2 or K3 fails: withdrawn".
The pilot finding reports K1 at 0 of 6 on every reading of the coordinator's cost (5 needed), K2 failing on most
readings, K3 holding, K4 turning on how a deny is counted. The coordinator reported it and decided nothing. "Withdraw
the lead." (the owner, 2026-11-24). Ruled out in the brief: keeping the lead on a new test; changing the keys' meaning.

Item 12 says a withdrawal "removes the agent, the hook entries, the lead's sentences and Hatchery's `finish`" and
keeps the keys, inert at `null`. It does not name the code. Verified in the tree on 2026-11-24:
- Hatchery's `policy.json` never set a `finish`; `.lead/` is an empty directory; no led change is open. ADR 0025's
  build steps 1 to 3 are pushed, but no sibling checkout beside Hatchery carries `lead.js`: none took it by update.
- `usage-gate.js` and `.claude/statusline.js` were last changed by `7572cd3` (step 2) and by nothing since.
- Harness groups `H` and `HS` run whatever commands `.claude/settings.json` holds and fail on one whose script is
  missing; `WL` and `UF` run on scratch trees. So the hook entries can go before the script, never after it. With
  them gone `installed()` is false, and the gate already gives no caller a finish line.
- `brainstorm`, `handoff` and `triage` say "a wave"; the one definition of it sits in the lead's paragraph.

## Decision

1. **Withdrawn.** There is no `wave-lead`, no lead directory and no finish line. A change that gets a review is
   relayed by the coordinator with reports by path (`brief` skill, "Reports by path"), as it was before the lead.
2. **What of ADR 0025 stands.** The keys `usage.fiveHour.finish`, `usage.sevenDay.finish` and `usage.finishAgents`,
   promised (ADR 0013): name, type, base value (`null`, `null`, `["wave-lead"]`) and validation as built. The schema
   check: `policy.js --schema`, `policy.schema.json` and group `PS`, none edited. Item 2, as the record of what the
   owner said. Never the lead's, and untouched: a fix goes back "by the review file's path, never rewritten", review
   ids are `<round>-<n>`, the reviewer writes its review file. All else falls: items 1 and 3 to 11, build step 5, the
   sibling's sections 3 to 7 (kept as the record of what was built), and the Deferred items but the last (sweeps).
3. **The code goes too.** Item 12 left a choice: leave `lead.js`, group `WL`, the gate's finish-line branch and the
   status line's ` finish <n>` in place, or remove them. They are removed.
   - Left in place, a set `finish` does nothing in a tree without the hook entries, yet its status line prints
     ` finish 75` beside the meter; in a tree that hand-merges the two entries and defines an agent on
     `usage.finishAgents` it works as built. The lead would be withdrawn by convention only, back without a record
     wherever two lines were merged, on a script whose known defects (item 7) nobody mends, carried by every tree.
   - Removed, a set `finish` or `finishAgents` is loaded, validated (a `finish` below the meter's `hold`, a shared
     `usage.finish` and `finishAgents: "any"` stay malformed) and printed by `policy.js`, and read by nothing else:
     the gate gives every caller the HOLD deny byte for byte, and the status line shows no finish.
   - Item 12's list is what stops a lead running, not the limit of what may go: it says `lead.js`'s commands and
     output, `.lead/`, the prompts and the deny texts are not promised. No key is removed, renamed or retyped and
     `PS1` passes unedited. One meaning changes, which ADR 0013 counts as incompatible: a set `finish` lets no lead
     pass HOLD. That is the exit item 12 announced; no file seen sets one; the Migration table covers it. Git keeps the script.
4. **Step 1, text: a `tester`, one reviewed commit, relayed by path.** First `node .claude/hooks/lead.js status` must
   exit 0 and `rmdir .lead` succeed (a directory with content is a stop, reported). Then, per file:
   - Goes whole: `.claude/agents/wave-lead.md`; the section "Led changes" of `.claude/skills/brief/SKILL.md`, heading
     included; the `wave-lead` row of `.claude/agreement/model-routing.md`; in `.gitignore` the line `.lead/`, its
     comment and the blank line above; in `.claude/skills/handoff/SKILL.md`, step 5, " (none of an open led
     change's)"; in `.claude/agreement/git.md`, "Between HOLD and a finish line" to "(ADR 0025)."
   - `.claude/settings.json`: the two groups whose command runs `lead.js` with `--hook` go, the PreToolUse one
     (matcher `Agent|SendMessage|Bash|PowerShell|Write|Edit`) and the PostToolUse one (matcher `Agent`).
   - `.claude/agreement/delegation.md`: the second paragraph ("A change that gets a review may go to a `wave-lead`"
     to the end of the file) becomes "A wave is the batch of changes the coordinator launches together."
   - `.claude/agreement/one-review.md`: the second paragraph ("A change a `wave-lead` runs" to "(ADR 0025).") goes.
     The first stays whole, "by the review file's path, never rewritten" included.
   - `.claude/agreement/usage-gating.md`: three sentences go wherever they stand, replaced by nothing (each row
     names the `handoff` skill, whose step 5 names the `commit` skill's wrap-up commit). In the intro, "A `wave-lead`
     (`usage.finishAgents`) alone" to "and a deny line wins."; in the two HOLD rows, "A lead in flight" to "sets
     one."; in those and the STOP row, "The wrap-up commit comes first, by pathspec" to "none of the change's."
   - `.claude/skills/commit/SKILL.md`: the wrap-up commit keeps its pathspec form, right for any agent still in
     flight at HOLD. "what a lead's runner has staged" becomes "what an agent still in flight has staged", and
     "; while a led change is open the paths are the handoff and queue files only" goes.
   - `.claude/agents/reviewer.md`: ", in the scratchpad or as `<d>/review-<k>.md` in the ignored lead directory
     `.lead/`," becomes ", in the scratchpad,"; in the sentence after it "any other file" becomes "any file".
   - `.claude/agents/pusher.md`: check 1 is, whole, "`git status --porcelain` is empty. Uncommitted changes mean stop."
   - `README.md`: "tester, pusher and wave-lead on Sonnet" becomes "tester and pusher on Sonnet"; "a guard that
     binds a wave lead to the steps its script names, and a post-edit check" becomes "and a post-edit check".
   - Records: under the 0025 paragraph in the header of ADR 0007 and of ADR 0022, one line: `Withdrawn by
     [0027](0027-wave-lead-withdrawn.md), 2026-11-24: <what>.` ("no check per fix round"; "the HOLD row as before").
   - Verify: `grep -rniE "\bleads?\b|\bled\b|finish line|finishAgents|ADR 0025" .claude/agents .claude/agreement
     .claude/skills .claude/settings.json README.md .gitignore` prints nothing; `node .claude/hooks/policy.js
     --claude-check` ends `0 FAIL`; the test command passes, `WL` and `UF` still in it.
5. **Step 2, code: a `coder`, one reviewed commit after step 1, relayed by path.**
   - `.claude/hooks/lead.js`: deleted. `.claude/hooks/usage-gate.js` and `.claude/statusline.js`: each becomes byte
     for byte what it was before step 2 of ADR 0025, so `git diff 7572cd3^ -- .claude/hooks/usage-gate.js
     .claude/statusline.js` prints nothing; the gate then reads no `agent_id` or `agent_type` and requires no `./lead`.
   - `.claude/hooks/policy.js`: no code changes: `SCHEMA`, `TYPES`, `DEFAULTS`, the `finish is below hold` error and
     `meterThresholds` stay. Its two comments on `finish` say the keys are read by nothing (ADR 0027).
   - `.claude/hooks/test-hooks.js`: group `WL` goes whole, with its helpers and the header's words for it. `UF` keeps
     three cases under `finish-75.json`, its scratch tree with the lead's entries gone: a call carrying `agent_id:
     "a1"` and `agent_type: "wave-lead"`, `Agent` and `SendMessage`, at 5h 55 and 74, gets the deny the same call
     without the two fields gets, pool advice included, and no `past HOLD` on stderr; the three malformed forms as
     now (UF10); the coordinator as now (UF14). `S56`: under `finish-75.json`, with a deny of 90, and with `finish`
     at the hold, the line shows `(hold 50)` or `(hold 50 stop 90)`, never `finish`. `PS1` to `PS3` are not edited.
   - ADR 0006, its living tables. Meaning of the `finish` row: "Read by nothing since ADR 0027: no caller passes
     HOLD by it. Still validated: a value below the meter's effective `hold` is malformed, and there is no shared
     `usage.finish`. Leave it `null`." Of the `usage.finishAgents` row: "Read by nothing since ADR 0027. Still
     validated; the base value stays, a changed default being an incompatible change (ADR 0013)." Fail modes: the
     `usage-gate` row's last two cells lose their finish-line clauses; the `lead.js --hook` row goes.
   - Hook specs, section 3 (`0002-hook-specs-usage.md`): "The caller is looked at only for a finish line" to "a
     listed type the hook does not bind included." becomes "The caller is not looked at: `agent_id` and `agent_type`
     are ignored, and the keys `finish` and `finishAgents` are read by nothing (ADR 0027)."; ", with ` finish <n>`
     after the hold" to "(`(hold 50 finish 75 stop 90)`)" goes; the paragraph "Finish line (ADR 0025; keys in ADR
     0006)." goes; the harness list follows this item. `0002-hook-specs.md`: "the finish line" to "(ADR 0025), " goes.
   - Records: step 1's line under the 0025 paragraph of ADR 0006 ("`usage.hold` stops every caller alike; the keys
     stay, read by nothing") and of ADR 0013 ("the keys and the schema check stand; the cache's reader `lead.js` is gone").
   - Verify: the test command passes with no `WL` on its `groups:` line; `git grep -nE
     "lead\.js|\.lead/|leadType|Lead guard" -- .claude README.md .gitignore` prints nothing.
6. **What falls away.** Build step 5, and with it P3: nothing waits for a session whose 5-hour meter reads, and no
   `finish` is set or lowered for a run. The probe finding can close as resolved, P3 moot and P4 seen; its platform
   facts (its Result tables) hold without the lead and are worth a reference entry when it closes. The pilot finding
   closes as resolved by this record. Both are the coordinator's to close (`finding` skill), outside steps 1 and 2.
7. **The idea files** (none is deleted here; only the owner drops an idea). Moot whole:
   `2026-11-23-lead-refused-cannot-tell-a-failed-call-from-a-gate-refusal.md` and
   `2026-11-24-lead-steps-files-list-takes-the-lead-directorys-own-files.md`. Moot as bugs, each with one thing to
   keep first: `2026-11-24-lead-wait-is-moved-to-the-background.md` (the harness moved a Bash call that ran past
   about three minutes to the background and returned no output; the test command runs as long) and
   `2026-11-24-a-staged-deletion-breaks-the-leads-commit-brief.md` (one missing path fails a whole `git add`, and a
   runner that goes on commits only what was staged; the `commit` skill's brief has the same shape). Not moot:
   `2026-11-23-hook-exit-codes-under-windows-powershell.md`, about every hook that refuses by exit code, less its
   last paragraph on `installed()`.

Migration (no key changes type, base value or validation; the meaning of a set `finish` lapses, as item 12 announced):

| Tree | Arrives by update | The project edits | When | Check |
|---|---|---|---|---|
| Hatchery | n/a | steps 1 and 2; no policy edit, no `finish` having been set | with the build | `.claude/hooks/lead.js` and `.lead/` are absent; the test command passes, `PS` unedited; `node .claude/hooks/policy.js` prints both `finish` keys as `null` |
| A tree that took ADR 0025 by update (none seen) | the deletion of `lead.js` and `wave-lead.md`; the gate, status line, harness, agreement, skill and agent text of steps 1 and 2 | Before the update, while the script is still there: every change `lead.js status` lists is finished and closed (`lead.js close`; a landed change never closed still lists, with exit 0), then `rmdir .lead` succeeds (content is a stop: read it, then remove it by hand). In the update's sitting, before other work: the two hand-merged `lead.js --hook` groups out of `.claude/settings.json` (left in, every shell call, write, launch and send logs a non-blocking hook error for a script that is gone). Only then its own: the `.lead/` line of `.gitignore`. A `finish` it set may stay or go; it does nothing | its next update | `.lead/` is absent and `git status --short` does not name it; groups `H` and `HS` pass; `grep -c "lead.js" .claude/settings.json` prints 0; `node .claude/hooks/policy.js` prints no error |
| A tree on a base line before ADR 0025 (every sibling checkout seen) | `policy.js` with the three keys and `--schema`, `policy.schema.json`, group `PS`; no script, agent, hook entry or sentence of the lead | nothing; the hook entries ADR 0025's table told it to hand-merge are not merged, and a `finish` written now is read by nothing | its next update | `node .claude/hooks/policy.js` prints no error; the test command passes `PS` and has no `WL` |
| A tree that never updates | nothing | nothing | n/a | its policy loads as before |

Deferred, each with its trigger and each superseding this record: a lead again, as a new proposal that states its
measure before its first change (Claude Code enforces a subagent's launch list or model pin, so the guard the pilot's
denies came from is not needed; or a coordinator turn stops re-reading its context); dropping the keys, with a
Migration table (a schema change they block).

## Consequences

- Easier: one hook fewer on every launch, send, shell call and write; a test command without group `WL`; fewer
  agreement lines loaded each session; HOLD stops every caller alike, by the gate its older cases describe.
- Harder and costs: the coordinator again pays a turn per brief and per report of a reviewed change, which the
  pilot's model priced below what the lead cost it, and reads each fix from the response file with no fresh reviewer.
  Three keys are kept for good that nothing reads, one with a base value naming an agent type that no longer exists.
- What the owner sees: nothing changes on the status line, no tree having set a `finish`; a tree that sets one now
  gets no ` finish <n>`. Reversible with a word.
- For the coordinator: ask the owner whether the four idea files item 7 calls moot are dropped, the two facts kept
  first; close the two findings; take step 5, P3, "Led changes" and "never led" out of the handoff.

Predictions (the trigger scan reads these; superseding this ADR records each outcome first):
- Prediction, check when the next sibling update runs: the sibling edits nothing in its policy file to take it, and its groups `H` and `PS` pass (carried from ADR 0025).
- Prediction, check by 2027-05-24: no tree's `policy.json` sets a `finish` or `finishAgents`, and no idea or finding asks for the lead's script back.
- Prediction, check when Claude Code enforces a subagent's launch list or model pin: reviewed changes are still relayed by path, and a lead comes back only as a proposal with its measure stated first.
