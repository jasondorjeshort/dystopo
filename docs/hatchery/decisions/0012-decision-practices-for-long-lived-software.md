# 0012. Decision practices for long-lived software

Date: 2026-10-16
Status: accepted

Record: proposal `../proposals/0012-decision-practices-for-long-lived-software.md` and
`../proposals/0012-critic-review.md`. Evidence:
`docs/findings/2026-10-16-decision-practices-for-long-lived-software.md`. Hatchery's own promised
surfaces and reference fitness check: ADR 0013. Amends the living table of ADR 0001 (one row).

## Context

The owner asked for a base that optimises for software still doing its job in ten years with
little upkeep. The loop reviews major choices in depth and every other change once, for defects;
nothing names the interface a pass must keep, counts dependencies, re-reads an ADR's triggers, or
checks a structural property. The 2026 studies (finding) locate the cost of agent-built code in
small, task-sized changes and find one significant code-level signal, drift in input validation
and error handling; they do not show structural causes. The practices here stand on SQLite's
support model, Go's compatibility promise (additions free, breaks forbidden), Ousterhout's
continuous design investment, Cox's dependency checklist, and Hatchery's own three compatibility
incidents (ADRs 0009 to 0011) on surfaces nobody had named. Constraints verified 2026-10-16:
`CLAUDE.md` is 134 lines, a downstream project with code is at the 150 cap, so base lines
displace project lines at its next update; `test-hooks.js` prints `FAIL <id> <label>` with exit
codes 0, 1, 2 and `--only`, `--verbose`; the hooks and status line require only Node built-ins.

## Decision

The practice: each project names its promised surfaces in one ADR with a compatibility promise
(additions free; an incompatible change is a major choice). A dependency is evaluated by a
checklist; a thing under a page may be copied in with its licence. Fitness checks run with the
test command, fail a push, and carry their own lift. ADR triggers, predictions and fitness lines
are scanned by a cheaper model; the architect runs only on a hit. Each file edit below is exact;
a `tester` makes them from this ADR alone, then runs `node .claude/hooks/test-hooks.js` and
`wc -l CLAUDE.md` (138).

1. `CLAUDE.md`, section Major decisions. Line 54 becomes `network protocol or API surface, build
   system, a dependency the whole project will lean on, or` and a new line follows it: `an
   incompatible change to a promised surface (ADR 0012).` After the paragraph ending "Flag those
   explicitly and do not guess." add a blank line and: `A change is measured by the software it
   leaves behind: ADR 0012 names the practices (promised` / `surfaces, the dependency checklist,
   fitness checks, the trigger scan) and the file each lives in.` Nothing else changes.
2. `.claude/skills/proposal/template.md`: Question reads "One paragraph: what is being decided and
   why now, and what the chosen option should look like in ten years." Each option ends with
   `Exit cost: what leaving in two years takes (what is rewritten, what data migrates).` and
   `Moving parts: what runs, is configured or is depended on that was not before.`
3. `.claude/skills/proposal/SKILL.md`, step 2: "Every option states its exit cost and moving
   parts. A dependency the project will lean on answers `dependency.md` in the proposal." Its
   frontmatter `description` trigger list gains "an incompatible change to a promised surface (ADR
   0012)".
4. `.claude/skills/proposal/brief-critic.md`, after the lift sentence: "Then a pre-mortem: it is
   ten years on and this choice is the one the project regrets; say in one paragraph what went
   wrong and which line of the proposal should have caught it. A prediction that cannot fail, an
   exit cost or moving-parts line that is boilerplate, is an objection."
5. `.claude/skills/proposal/dependency.md` (new, under 40 lines): the checklist, one question per
   line ("Unknown" is an answer except for the licence; a blank is not): what it does that cannot be copied in under a page; licence GPL-compatible (ADR 0001);
   last release, open issues, response time; maintainers; size of the transitive closure; its
   tests run here; behaviour on untrusted input; who watches upstream releases and advisories;
   how it would be replaced and what that costs; what the debug surface loses if it misbehaves.
   Rules: default evaluate; a small dependency answers in the brief, one the project leans on in
   the proposal; a thing under a page may be copied in and then keeps its own licence text and
   SPDX identifier, carries an origin line (URL, version or commit, date), lives under a path
   listed in ADR 0001's components table, and counts against the dependency budget;
   reimplementing a parser, a format, cryptography or date handling is not copying and answers
   the checklist like a dependency.
6. `.claude/skills/adr/template.md`, end of Consequences: a `Predictions` block of two or three
   lines `- Prediction, check by YYYY-MM-DD: <text>.` or `- Prediction, check when <trigger>:
   <text>.` Its intro gives the outcome form, recorded on the prediction's own line, which keeps
   its start: `... <text>. Outcome 2027-01-20: held | failed | moot, <one line>.` Recording it is
   the one edit a closed ADR takes. `.claude/skills/adr/SKILL.md`, Supersede step 1: "Before
   writing, record the outcome of each prediction on its line in the old ADR (the one edit a
   closed ADR takes); then write the new ADR as above." Step 2: "Do not edit the old body beyond
   those outcome lines; it is the record."
7. `.claude/skills/brief/template.md`, after Scope: `Interface: may add <X>; must not change
   <Y>; or unconstrained because <reason>.` and `Dependencies: none, or each new one with
   .claude/skills/proposal/dependency.md answered.` `.claude/skills/brief/SKILL.md`, coder note
   gains: "fill Interface and Dependencies; a surface or cross-module interface is the
   architect's to design, a module-internal one the coder's."
8. `.claude/agents/coder.md`, Rules: "A dependency, or a change to an interface the brief said
   must not change, that the pass turns out to need: stop and report it; do not add it."
9. `.claude/agents/reviewer.md`, Rules: "Serious by rule: a new dependency the brief did not
   name; a change to an interface the brief said must not change; an incompatible change to a
   surface in the project's surfaces ADR with no ADR for it; a changed input check, a new silent
   fallback or a swallowed error the brief did not name." Report gains two lines: net lines
   added against what the brief implied, and `Verified line by line: yes | no`, with the hunks
   it could not verify when no.
10. `.claude/agents/pusher.md`, check 8: "A `FAIL` line from a fitness check is reported verbatim
    as design work for the architect; do not retry or edit anything."
11. `.claude/skills/triage/SKILL.md`, new step 1 (the scan), the rest renumbered: read each
    `Deferred` item in `docs/design/decisions/` against the tree and list those whose trigger has
    fired; `grep -rn "^- Prediction, check" docs/design/decisions/ | grep -v " Outcome "` and
    list those past their date or whose trigger is met (a line carrying ` Outcome ` is closed);
    run the test command and list `FAIL` and `SKIP` lines from fitness checks. Each hit becomes
    one idea file (`kind: chore`, no `related:`, named for the ADR and the trigger, an existing
    one gets a dated line instead) ending in `Plan:` that names the architect and the deliverable
    (a superseding ADR, an amendment, or for a prediction the Outcome recorded on its line, and a
    finding if it failed). The report counts hits. The Bounds allow the scan's idea files and
    their index rows in `docs/ideas/README.md`; the `description` says it also runs this scan.
12. `.claude/skills/handoff/SKILL.md`, step 1 gains: `grep -rh "^- Prediction, check by"
    docs/design/decisions/ | grep -v " Outcome " | grep -oE "[0-9]{4}-[0-9]{2}-[0-9]{2}" | sort
    | head -1`; a date on or before today, or a `FAIL` or `SKIP`
    fitness line in the last test run, puts "triage (the scan)" in Next.
13. `.claude/skills/commit/SKILL.md`, step 3: "A serious reviewer finding the coordinator accepted
    without a fix is one body line, `Accepted finding: <one line>`."
14. `docs/design/README.md`, the `03-architecture.md` row gains ", the promised surfaces with
    their compatibility promise, and the fitness checks".
15. `docs/design/decisions/0001-licensing.md`, components table, new row: `| vendored copies
    (none in the base) | the upstream licence, kept | A file copied in under ADR 0012 keeps its
    own SPDX identifier and origin line; one row per file here. |`. Table only (the ADR calls
    it living); the commit message says so.
16. `docs/ideas/2026-10-16-scheduled-architect-design-pass.md`: the owner's `Question:` on a
    scheduled Fable pass; written with this ADR, indexed.

The fitness contract (a project's script; Hatchery's reference check is ADR 0013): one program,
run by the test command; exit 0 pass, 1 any fail, 2 usage error; `--only <check>` and
`--verbose`. Config in one JSON file the project names, holding each check's parameters and a
`skip` list of `{ "check", "reason", "date", "idea" }`. Output per check: `PASS <check>`,
`SKIP <check>: <reason> (<idea>)`, or `FAIL <check>: <detail>; from <config>; lift: skip entry
(coordinator, one push, named in the commit message) or ADR amendment (architect)`. A skip
entry needs all four fields and an idea file that exists, else the check fails; the idea's
`Plan:` names the architect, and the scan lists every skip until the amendment removes it. The
checks a project earns, in order: dependency budget (direct plus vendored at most the configured
number, each with a licence), module boundaries (declared import edges, no others), surface
compatibility (the recorded fingerprint of each promised surface is a subset of the current one,
or the surface's ADR changed). A failure is design work, never a patch to the check.

Out of scope and deferred: a `design-pass` skill (the owner's question, item 16); the surface
compatibility check in the base, trigger: the first surfaces ADR with a schema or help text to
fingerprint; a path-scoped rule for the practice, trigger: a project with code globs.

## Consequences

- Easier: a surface change is visible as such; a brief says what a pass may touch; a wrongful
  fitness block has a lift in the session; triggers are read by a program or a cheap model.
- Costs: two brief fields and two option lines per proposal, filled every time (boilerplate is
  the critic's job to catch); a config file and a script per project with code; one idea file
  per scan hit; the downstream project at the 150 cap trims four lines of its own at its next
  update, which is its editing, not the base's.
- Fable spend: only on a scan hit, a surfaces ADR, or an incompatible surface change.
- Supersede when a reviewer model catches unnamed dependencies and drift without the brief
  fields (drop the fields), or when the brief corpus shows "unconstrained" as the usual answer.

Predictions (the trigger scan reads these; an outcome is recorded on the line, item 6):
- Prediction, check when the first decanted project with code has ten reviewed commits: at least
  one reviewer finding cites the drift or unnamed-dependency rule, and fewer than half its briefs
  say "Interface: unconstrained".
- Prediction, check by 2027-01-16: Hatchery's dependency check has a skip entry only with an
  idea file behind it, and the budget of zero has not been raised.
- Prediction, check by 2027-04-16: no incompatible change to a surface named in ADR 0013 has
  landed without an ADR, by reading `git log` of the files that carry each surface.
