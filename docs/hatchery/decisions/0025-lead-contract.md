# 0025, sibling: the gate rule, the schema check, `lead.js`, the templates and the texts

Companion to [ADR 0025](0025-wave-lead.md): what its build steps (item 10) are built from. `<d>` is the lead
directory `.lead/<slug>`, from the work tree's root. Section 1's keys are promised (ADR 0013); no wording is.

## 1. Policy keys and the gate (step 2)

Rows for ADR 0006's living key table, added in the commit that adds the code:

| Key | Type | Base value | Meaning |
|---|---|---|---|
| `usage.fiveHour.finish` / `usage.sevenDay.finish` | percent or `null` | `null` | That meter's finish line: a caller on `usage.finishAgents` passes the meter's HOLD while its reading is below `finish`. `null` is the meter's effective `hold`, so nobody passes. A value below that `hold` is malformed. A `deny` line is judged first and wins. There is no shared `usage.finish`. |
| `usage.finishAgents` | agent names; `[]` allowed, `"any"` never (a new `TYPES` entry, `agents`) | `["wave-lead"]` | The agent types a finish line applies to. `lead.js --hook` binds every type on the list to one change's steps (section 3). |

`policy.js`: `meterThresholds` returns `{ hold, deny, finish }` per meter, `finish` resolved; `load()` throws
`usage finish is below hold for the <label> meter`. Hatchery's own `policy.json` gains `"fiveHour": {
"finish": 75 }` at step 5, not before. `usage-gate.js`, in place of "the caller is not looked at" (hook specs):
- Caller: a lead when the payload's `agent_id` is a non-empty string, its `agent_type` is a string on
  `usage.finishAgents`, and `lead.js`'s exported `installed(root)` holds (the script loads and
  `<root>/.claude/settings.json` parses with `disableAllHooks` not true and carries both entries, PreToolUse
  over every tool section 3 lists and PostToolUse over `Agent`, each in a group whose `matcher` is a plain `|`
  list naming those tools (a regex or `*` does not bind) and which holds a binding hook: type `command`, no key
  beyond `type`, `command`, `args`, `timeout`, `shell`, `statusMessage`, `shell` unset or `bash`, and either the
  command `node "${CLAUDE_PROJECT_DIR}/.claude/hooks/lead.js" --hook` (`node.exe`, `${CLAUDE_PROJECT_DIR:-.}` or
  `$CLAUDE_PROJECT_DIR` also bind; path double-quoted; nothing else on the line) or command `node` with args
  `["${CLAUDE_PROJECT_DIR}/.claude/hooks/lead.js", "--hook"]`; a relative or absolute path binds not; `root` is `CLAUDE_PROJECT_DIR`,
  else the hooks' grandparent). Otherwise the coordinator: a listed type the hook does not bind gets no finish line.
- HOLD, per tripped meter: lifted, as now, for every caller when `usage.overrideEnv` is `1` or an applying
  grant names it; so a grant lifts its meter's finish line too. On a lead's `Agent` or `SendMessage` call
  (other tools, `Workflow` too, as the coordinator's) a tripped meter not lifted also passes while below a
  `finish` above its `hold`. Every tripped meter lifted or passed: allow, grant lines on stderr as now and,
  if a finish line was used, `usage-gate: past HOLD (<detail>); <agent_type> may launch until <meter> <finish>%`.
- Otherwise deny, naming the tripped meters as now, less any a finish line passed. The coordinator always
  gets today's HOLD deny, byte for byte, pool advice included; so does a lead when no named meter is held at
  its finish line (at or above it, not lifted). Else the lead gets `<head> <detail>. <clause>[, <clause>]:
  Launch nothing further: run node .claude/hooks/lead.js refused <your directory> and hand back the verdict.
  Resets: <meter> <local time of its resets_at | unknown>[, ...]; the owner's override grant for the meter
  lifts this sooner (ADR 0006).` then the SendMessage note, the grant clauses and the closing sentence as now.
  `<clause>` is `<meter> at or above its finish line <finish>%` or `<meter> at or above <hold>%`; `Resets:`
  lists the meters so held; no pool command. Status line, finish above hold: `(hold 50 finish 75[ stop 90])`.

Harness, group `U`, fixture `finish-75.json` (`hold` 50, `fiveHour.finish` 75, `sevenDay.hold` 97, a pool
stub), `CLAUDE_PROJECT_DIR` a scratch tree with the hook entry; a lead payload has `agent_id: "a1",
agent_type: "wave-lead"`. UM09 to UM11 keep their expectations under the Hatchery fixture (no finish).
- UF01 lead `Agent` at 5h 55, UF02 lead `SendMessage` at 5h 74: allow, stderr `past HOLD`. UF03 lead at 5h
  75: the finish deny (`5h at or above its finish line 75%`, `lead.js refused`), the pool stub not spawned.
  Today's HOLD text at 5h 55: UF04 a `coder` with an `agent_id`; UF05 `wave-lead` with no `agent_id`, `""`
  or a number; UF09 `finishAgents: []`; UF13 a lead where `settings.json` lacks the entry, and where
  `lead.js` is absent; UF14 the coordinator. UF06 lead at 5h 55 and 7d 97: it names `7d at or above 97%`.
- UF07 lead at 5h 80 with a grant for `5h`: allow; for `7d` only: the finish deny with `The override in
  force lifts only 7d.` UF08 a `deny` of 60 wins at 5h 60. UF11 `sevenDay: { hold: 97, finish: 99 }`: allowed
  at 7d 98, denied at 99. UF12 stale data under `whenStale: "deny"`: denied. UF10 malformed: `finish` 40
  under `hold` 50; `finishAgents: "any"`; `usage.finish`. S: `5h 55% (hold 50 finish 75)`, none without one.

## 2. The policy-schema compatibility check (step 2; ADR 0013's Deferred item, policy half)

`node .claude/hooks/policy.js --schema` prints one JSON object, keys sorted: `"<dotted key>": { "type":
"<name in TYPES>", "default": <base value> }` per leaf of `SCHEMA`. `.claude/hooks/policy.schema.json` holds
`{ "$comment", "keys": <that object> }`, base-owned, regenerated in any commit that changes the schema. `PS`:
- PS1 every key in the file is in the output with the same type and (but for type `text`, the unpromised
  advice wording) default, else `FAIL PS1 schema: <key> removed | retyped | default changed; a promised key
  (ADR 0013); lift: an ADR with a Migration table, then regenerate policy.schema.json in that commit`; a key
  the file lacks fails as `not recorded`.
- PS2 every file in `policy.examples/` and the fixture `pre-0025.json` (each key of the schema as it stood
  before this ADR, with a value that is not its default) loads with no error and gives back each value. PS3
  under it the gate answers a lead payload as it does the same payload without `agent_id`, at 5h 45, 55, 99.

## 3. `.claude/hooks/lead.js` (step 1; the hook entries at step 3)

Node built-ins, CommonJS, SPDX header: a module (`step(dir)`, `installed(root)`), a CLI and a hook. It runs
`git` only to read. Directory `<d>`, slug `[a-z0-9-]{1,40}`, never tracked; besides the files section 4
names it holds `lead.json`: `{ slug, writer, cap, humans, fresh, status: "running"|"landed"|"stopped", stop:
null|{ reason, detail }, base: <HEAD at init>, branch, baseline: [<paths dirty at init>], files, commits:
[<sha>], agents: { writer: null|{ id, session } }, permits: [{ t, step, tool, type|to, head, five, seven,
read }], responses: [{ t, step, agentId, agentType, model, status }], denies: [{ t, tool, what }] }`. `five`,
`seven`, `read`: the readings and `updatedAt` of `~/.claude/usage-state/<CLAUDE_CODE_SESSION_ID>.json`, read
only, `null` when absent; `head`: HEAD then. `k` counts commits and reviews from 1; fix `k` answers review `k`.

`step(dir)` returns the one permitted step from the directory and the repository alone. Steps and outputs:
`writer`, `writer-1.md`; `commit-<k>`, a commit rule 4 records; `review-<k>` and `fix-<k>`, the files
`review-<k>.md` and `response-<k>.md`. The first rule that applies wins:
1. `status` is not `running`: `verdict`. A brief file is missing: stop `brief missing`. The branch is not
   `branch`, or `base` is not an ancestor of HEAD: stop `history moved`.
2. A step has a permit and no output: `wait`; then stop `no output`, 60 minutes after the permit, or at once
   when its response record says `completed`. Nothing is re-issued without the coordinator's `resume`.
3. No `writer-1.md`: `writer`. Its header says `blocked`: stop `writer blocked`. It does not parse, or
   `message.txt` lacks a `Co-Authored-By:` line: stop `writer output malformed`. Else `files` is its list.
4. No commit for `k`: the newest in `<ref>..HEAD` (`ref`: `base`, or a fix's commit permit `head`; the range
   finds an amend) touching `files`, all clean, is recorded. None: a `Files:` entry of the round is neither
   one dirty file nor in an earlier list: stop `writer output malformed`. Else `commit-<k>` stages `files`'
   dirty paths: `new`; a fix is `amend` when `commits[k-2]` is HEAD and on no remote branch, else `follow-up`.
5. Until `response-<k>.md` exists: `git status --porcelain` lists a path outside `baseline`, the handoff files
   and, unless in `files`, the queue directories (`docs/ideas/`, `docs/operator/`, `docs/findings/`): stop
   `dirty tree` while there is no `review-<k>.md`, `tree changed during review` once that file parses.
6. No `review-<k>.md`: `review-<k>` (above 1, the check of `commits[k-2]` against `commits[k-1]`). Its
   header does not parse: stop `review malformed`. Then rule 5's second stop. `Verdict: rethink`: stop
   `rethink`. No Blocking or Serious id: `landed`. `k - 1` equals `cap`: stop `open after cap`.
7. No `response-<k>.md`: `fix-<k>`, by message to `agents.writer.id` when recorded in this session and
   `fresh` is unset, else fresh. A listed id is declined: stop `declined`. One has no line: stop
   `unanswered`; either leaves the fix uncommitted. Else `files` gains its list and rule 4 runs for `k + 1`.

A step is permitted once. CLI (exit 0; 1 where stated; 2 for a usage error or a refusal, one stderr line):
- `init <slug> --writer coder|tester [--cap 0|1|2] [--humans]`: refuses, naming the lift, outside a work
  tree; when `git check-ignore -q .lead/x` fails (the `.gitignore` line to add); when `installed()` fails
  (the two `lead.js --hook` entries to merge into `.claude/settings.json`); when another directory is
  `running` (its `verdict` command); when the slug exists. Writes `lead.json`, `steps.md`; prints `<d>`.
- `next <d> [--take]`: prints one JSON object, `{ step, tool: "Agent", subagent_type, prompt }`, `{ step,
  tool: "SendMessage", to, message }`, `{ tool: "wait" }` or `{ tool: "verdict" }`; writes a commit step's
  brief; records `landed` or a stop when reached; `--take` records the permit, for a coordinator turning
  the crank by hand. `wait <d>`: polls every 5 seconds, at most 540 seconds (under the Bash tool's 600), for
  the pending step's output; prints `ready` or `waiting`. `refused <d>`: stop `refused`, its detail the last
  of `denies` when newer than the last permit, else the readings against the policy's lines. `verdict <d>`.
- The coordinator's: `stop <d> "<reason>"` (stop `stopped by the coordinator`; the hook then refuses the
  lead's launches and sends; agents already running are not stopped). `resume <d> [--now] [--fresh]` (a
  stopped change runs again, the unanswered step's permit cleared; refused for `declined`, `open after
  cap`, `rethink`, and while that permit is under 60 minutes old unless `--now`, by which the coordinator
  says the step's agent is not running; `--fresh` sets `fresh`). `status` (per directory `<slug>
  <status>[: <stop reason>] <last sha | -> <d>`; exit 1 when any is `running`, `stopped`, or `landed` with
  `humans`, set where a change touches what humans see). `close <d> [--override "<reason>" | --resolved
  "<how>"]` (a flag for `stopped`, and for `landed` with `humans`; refused while `running`; deletes `<d>`).

Hook, `node "${CLAUDE_PROJECT_DIR:-.}/.claude/hooks/lead.js" --hook`, in `.claude/settings.json`: PreToolUse, matcher
`Agent|SendMessage|Bash|PowerShell|Write|Edit`; PostToolUse, matcher `Agent`; no `onFailure`.
- Not a lead (a non-empty `agent_id` and an `agent_type` on `usage.finishAgents`; `["wave-lead"]` when
  `policy.js` lacks the key, as at step 1, or the policy does not load): exit 0 at once.
- A lead, PreToolUse. `Write`, `Edit`, `PowerShell`: deny. `Bash`: only with `run_in_background` not true
  and a command that is exactly `node .claude/hooks/lead.js next|wait|verdict|refused <d>`, `<d>` an
  existing lead directory of any status. `Agent`: only when exactly one directory under the tree's `.lead/`
  is `running`, `step(d)` there is an `Agent` step, `subagent_type` equals its type, `prompt` equals its
  prompt after trimming and collapsing whitespace, there is no key beyond `subagent_type`, `prompt`,
  `description` and `run_in_background` (so no `model`), and the step has no permit. `SendMessage`: likewise
  against the step's `to` and `message`; of the other keys the probe saw (`summary`, `type`, `recipient`,
  `recipient_kind`, `content`), `recipient`, where a string, must equal `to`; `content`, where a string, must equal
  `message` or be a preview the harness derives, a non-empty prefix of it followed by U+2026; any
  key beyond those denies. An allow appends the permit first. A deny appends to `denies` and is the JSON
  decision, `Lead guard: <what>. Do not retry: run lead.js refused and hand back the verdict.`, `<what>`
  being `no running change`, `not the permitted step (<step>)`, `key not allowed (<key>)`, `step already
  permitted`, `command not allowed` or `tool not allowed`. Any exception: exit 2, the reason.
- A lead, PostToolUse of `Agent`: appends the response record from `tool_response` (`agentId`, `agentType`,
  `resolvedModel`, `status`); a string `agentId` of `writer` or a fresh `fix-<k>` sets `agents.writer`; it
  never denies. Fail modes, a row for ADR 0006's table: policy absent or malformed, the base list; a crash
  judging a lead, exit 2; a script that cannot load is non-blocking, and the gate then gives no finish line.

Harness, group `WL`: a scratch repository per case, the CLI run and the hook fed payloads, no agent launched.
One case per rule and per stop of `step()`, per CLI refusal and exit code, and per hook clause; among them:
- WL04 `commit-1` stages exactly the header's files, and `review-1` names the commit's sha. WL06 `fix-1`
  goes by message to the recorded id, fresh when none is recorded, it is another session's, or `fresh` is
  set. WL07 `commit-2` is `amend` when the commit is HEAD and unpushed, else `follow-up`; an amended commit
  is found. WL09 an unlisted dirty file stops; a dirty handoff or idea file does not.
- WL11 `next --take` records a permit. WL12 a lead's `Agent`: the step's call passes once; a second, a `model`,
  another type, a prompt changed by a word are denied and logged; whitespace differences pass. WL13 a lead's
  `Bash`: the four commands pass; `git status`, a chained command, a trailing argument, a background run do
  not. WL14 a lead's `SendMessage` with every key the probe saw passes, as does a `content` shortened to a
  prefix and U+2026; a `content` that is no prefix, a bare U+2026, a short prefix without it, or a
  differing `recipient`, another `to`, an unknown key do not. WL17 (step 2) a type added to `usage.finishAgents` is bound.

## 4. Templates, the commit brief and `steps.md` (constants in `lead.js`)

| Step | Call | Text |
|---|---|---|
| `writer` | Agent, the change's writer | `Read <d>/steps.md (Writer) and <d>/brief-writer.md and do the work.` |
| `commit-<k>` | Agent `runner` | `Run the commit brief <d>/commit-<k>.md exactly as written and report.` |
| `review-1` | Agent `reviewer` | `Read <d>/steps.md (Review) and <d>/brief-reviewer.md; review commit <sha>; write <d>/review-1.md.` |
| `fix-<k>` by message | SendMessage to the writer's id | `Fix round <k>: read <d>/steps.md (Fix) and <d>/review-<k>.md; write <d>/response-<k>.md.` |
| `fix-<k>` fresh | Agent, the change's writer | `You take over a change as its writer. Read <d>/steps.md (Fix), <d>/brief-writer.md, <d>/writer-1.md and <d>/review-<k>.md; the change is commit <sha>; write <d>/response-<k>.md.` |
| `review-<k>`, `k` above 1 | Agent `reviewer` | `Read <d>/steps.md (Check) and <d>/brief-reviewer.md; check git diff <a> <b> against <d>/review-<k-1>.md; write <d>/review-<k>.md.` |

`commit-<k>.md`: `Commit the following. Stage exactly these files: git add -- ':(literal)<path>' ...`, then one
of `git commit -F <d>/message.txt`, `git commit --amend -F <d>/message.txt` (its brief opens: `git rev-parse
HEAD` must print `<sha>`, else stage and commit nothing and report) or `git commit -F <d>/message-fix-<k>.txt`,
then `The message file carries its trailer; add nothing. Run git status --short before and after and include
both. Do not push.` The fix message, `next`'s too: `Review fixes: <subject of message.txt>` (70 characters
at most), `Closes <ids> from the review of <sha>.`, `message.txt`'s trailers. `steps.md`, written by `init`,
opens "The files here are the record. What you return to your launcher is not read." Sections, headers exact:
- Writer. Do the brief; do not commit. Write `message.txt` (subject under 70 characters, body, the brief's
  `Co-Authored-By` trailer), then, last, `writer-1.md`: `Status: done` or `Status: blocked: <reason>`,
  `Files:`, a repository-relative path per line for every file created, changed or deleted (one file per
  line, a new directory's files each on their own; never a directory or a pattern), a blank line, the report.
  Blocked: tests that cannot pass in scope, or a dependency or interface the brief lacks.
- Review. The reviewer's own rules apply. The file begins `Verdict: push | fix first | rethink`, `Blocking:
  <id>, <id> | none`, `Serious: ...`, `Minor: ...`, a line each; an id is `<review number>-<n>`.
- Fix. Fix every Blocking and Serious id, or decline it with a reason (so too when it needs scope the brief
  lacks). Do not commit. Rewrite `message.txt` if what it says changed. Last, the response file: `<id>
  fixed` or `<id> declined: <reason>` per id, a blank line, `Files:` as above, for what this round changed.
- Check. Read the diff first, then the earlier review's Blocking and Serious findings, no response file.
  List each still open under its old id and each new defect in what the diff changed; the rest are closed.

## 5. The verdict and the stops

`verdict` prints a line per field: `Change <slug>: running, at <step> | landed | landed, waits for the
owner's review | stopped: <reason>`, then `Commits` (shas, unpushed), `Rounds` (used of the cap), `Reviews`,
`Responses`, `Open` (id, severity, and open, declined or unanswered), `Minor left`, `Writer` (path, agent
id), `Launches` (type and count from the permits; the last with its time), `Meters` (each from first to last
permit; the largest step), `Tree` (clean, or the dirty paths, the change's own marked), `Next` (the row
below; for `landed`: file what is worth filing, then `close`). The Tree column says what each stop leaves.

| Stop | Tree | `Next:` (the coordinator lifts every one) |
|---|---|---|
| (`running`, and no lead is turning it: it ended at `maxTurns`, was stopped, or its calls fail) | as left | `stop <d>`, then the last row. |
| `writer blocked` | dirty, no commit | Read `writer-1.md`; widen the brief or take the decision; `close --resolved`, then a new change or a WIP commit. |
| `refused` | clean, or the change's files uncommitted | After the reset or the owner's grant: `resume --now` (a refused call started nothing), then a new lead on this directory, or `next --take` by hand. The uncommitted files are never a WIP commit: the commit step takes them, run by a lead's runner or, at wrap-up, by the coordinator from `<d>/commit-<k>.md`. A brief whose amend check fails is replaced by `next <d> --take` (after `resume`), which then writes the follow-up form. |
| `declined`, `open after cap`, `unanswered`, `rethink` | clean; at `declined` and `unanswered` the round's fix, uncommitted | Read the review and the response; override (`close --override`), or relay the fix yourself (`brief` skill, Reports by path) and `close --resolved`; after `rethink` the design goes to the architect or a runner reverts the commit. The cap is not raised. At `declined` and `unanswered`, also: no commit step ran, so the commit is still the one the reviewer read and no check will read the round's fix; your reading of the response stands in, as for a fix you relay (ADR item 6). The paths `Tree:` marks as the change's are committed before `close`, never as WIP: on an override by a `runner` on your brief (`commit` skill), amending that commit with `<d>/message.txt` while it is HEAD and unpushed, else as a follow-up, and `close --override` is refused while one is dirty; on a relayed fix, in that fix's commit beside its own files. A wrap-up before then leaves them uncommitted and the change open. |
| `dirty tree`, `tree changed during review` | dirty | Find who wrote the listed paths; nothing is committed over them; `resume` once the tree is explained. |
| `no output`, `writer output malformed`, `review malformed`, `history moved`, `brief missing`, `stopped by the coordinator` | as left; a writer may still be at work | Know that the last permitted agent (the `Launches:` line) has ended, or stop it by its id, before `resume --now` (P4); else `resume` waits out the hour. Mend a malformed file by hand. Or `close --resolved`. Uncommitted files as for `refused`. |

## 6. `.claude/agents/wave-lead.md` (step 3)

Frontmatter: `name: wave-lead`; `model: sonnet`; `tools: Bash, Agent, SendMessage`; `maxTurns: 100`;
`description: Runs one reviewed change from a prepared lead directory; hands back one verdict.` Body:
```
You run one change. Your prompt names its lead directory, <d>. A script names every step; a hook refuses
any other call. Run each `lead.js` command alone and exactly as written, in the directory you start in:
no `cd`, nothing before or after it on the line (no `&&`, `;` or pipe). 1. Run `node .claude/hooks/lead.js next <d>`; it prints one JSON object.
2. `tool` is `Agent`: call Agent with exactly its `subagent_type` and `prompt`, and its `step` as
   `description`; never `model`. `tool` is `SendMessage`: send exactly its `to` and `message`. Then go to 3.
3. `tool` is `wait`, or you have just made a call: run `node .claude/hooks/lead.js wait <d>` with a Bash
   timeout of 600000 until it prints `ready`, then go to 1. Ignore what agents return: files are the record.
   If the harness moves `wait` to the background, end your turn without a report or the hand-back call, and
   run nothing: it resumes you when the command finishes; run `wait` again, and never read the task's output file.
4. `tool` is `verdict`: run `node .claude/hooks/lead.js verdict <d>` and hand its output back, unchanged,
   as your whole final report (the hand-back call where the session has one, else your last message).
A refused or failed call: run `node .claude/hooks/lead.js refused <d>`, then step 4. Never retry or reword.
```

## 7. Sentences for the agreement, three skills and two agents (step 3)

- `delegation.md`, appended: "A change that gets a review may go to a `wave-lead` instead of being relayed:
  the coordinator writes the writer's and the reviewer's briefs as files (`brief` skill, Led changes) and
  reads one verdict. A wave is the batch of changes launched together; a lead runs one change of it and
  decides nothing: `lead.js` names each step and a hook refuses any other. Scope, overrides, queue entries
  and the push stay the coordinator's. One lead per work tree; while its change is open the coordinator
  writes no tracked file there but the handoff and queue files."
- `one-review.md`: "routes fixes back to the writer" becomes "routes fixes back to the writer by the review
  file's path, never rewritten". Appended: "A change a `wave-lead` runs gets the same one review, of its
  unpushed commit. Each fix round is then checked, an addition and not the review continuing: a fresh
  reviewer reads what the fix changed and says which blocking and serious findings are still open, without
  seeing the writer's response. At most two fix rounds; a brief may lower that. The fix is amended into the
  commit while it is HEAD and unpushed, else it is a follow-up commit. A declined finding, one still open,
  or a verdict of rethink stops the change unpushed; the coordinator alone overrides a finding (ADR 0025)."
- `usage-gating.md`: the intro keeps "whoever calls, except a report to `main` or a message-less notify" and gains:
  "A `wave-lead` (`usage.finishAgents`) alone may go on past a meter's HOLD, only while it reads below a `finish`
  the policy sets above `hold` (ADR 0025); a grant lifting a meter's HOLD lifts its finish line, and a deny line
  wins." The HOLD rows gain "A lead in flight goes on to its meter's finish line where the policy sets one."; all
  rows "The wrap-up commit comes first, by pathspec (ADR 0025); while a led change is open (`lead.js status`) it
  names only the handoff and queue files and takes none of the change's."
- `git.md`, after the wrap-up sentence: "Between HOLD and a finish line a lead's `runner` still commits its
  change (ADR 0025)." `commit` skill, wrap-up: `git add docs/HANDOFF.md <WIP files>` then `git commit -m`
  becomes `git add -- <paths>` then `git commit -m "<subject>" ... -- <paths>` (the `-m` parts elided), with "Naming the paths keeps
  out what a lead's runner has staged; retry once, ten seconds after an `index.lock` error; while a led
  change is open the paths are the handoff and queue files only". `handoff` skill, step 5: "`git add
  docs/HANDOFF.md` plus any WIP files" becomes "the `commit` skill's wrap-up commit of `docs/HANDOFF.md`
  plus any WIP files (none of an open led change's)". `model-routing.md`, a row: ``| Running one reviewed
  change from a lead directory | `wave-lead` | Sonnet 5.5 | Makes the calls a script names. |``
- `reviewer.md`: "Write is for files outside the repository only" becomes "Write is for two kinds of file only: the
  review file at the exact path the brief names, in the scratchpad or as `<d>/review-<k>.md` in the ignored
  lead directory `.lead/`, and experiment files in the scratchpad. Never write any other file in the
  repository's tree, tracked, trackable or ignored, and never edit one." `pusher.md`, check 1 gains:
  "`node .claude/hooks/lead.js status` must exit 0 where the script exists; a led change it lists as
  running, stopped or waiting for the owner's review means stop, quoting every line it prints."
- `brief` skill, a section "Led changes": `init`; the two briefs from `template.md` (the writer's names the
  commit's trailer); the prompt `Run the change at <d>.`; reading `lead.js verdict <d>`, not the lead's
  message, and acting on its `Next:` line; a stopped change's files committed as that line says; follow-ups;
  `close`. Not for design work or a change that edits the lead's own bounds.
