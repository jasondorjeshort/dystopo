# 0023. The 300-line doc cap becomes a threshold that demands a reason

Date: 2026-11-03
Status: accepted

Record: no proposal; the coordinator's research and the owner's wording, 2026-11-03. Supersedes ADR
0002 in part: the 300-line cap on `docs/**/*.md` (Decision, "File shapes and caps"; Consequences, the
"pusher refuses a push while any cap is broken" and "Two caps (300, 40)" bullets). The 40-line
handoff caps, the 120 total (ADR 0014) and the `CLAUDE.md` 40 and 190 (ADR 0016) stand unchanged.

## Context

ADR 0002 set 300 lines as a hard cap on every `docs/**/*.md`, enforced by pusher check 7 and warned
by the post-edit hook. The number was never argued: proposal 0002 asserts it in one line and the
critic's objection 6 was about where the constant lives, not its value. The caps with a stated reason
are the ones paid on every launch (the handoff's 40, `CLAUDE.md`'s 40 and 190); a file under `docs/`
loads only when an agent opens it, so its length costs only the reader who chose to read it.

What the cap did in practice, from the history: one cap-driven split, clean (the usage section of the
hook specs into its own sibling); no substance ever trimmed; one recorded complaint, that the next
edit to any section of a doc at the cap has to cut elsewhere first, and one sentence compressed to
make room. The two documents sitting just under the cap today are the hook specs and the privacy-scan
contract, whose length is the checks themselves: the exact commands, messages and harness cases an
implementer or a pusher runs. Cutting them would remove checks; splitting them would hand an agent
half a procedure.

Anthropic's own guidance for the surfaces it loads is soft: a `SKILL.md` body "under 500 lines for
optimal performance", splitting into separate files "when approaching this limit"; a `CLAUDE.md`
"target under 200 lines"; referenced files "one level deep" because Claude "might partially read
files when they're referenced from other referenced files" (agent-skills best practices,
platform.claude.com, read 2026-11-03). The owner's wording: a doc at 290 lines is not automatically
good if there is a clean split, and one at 400 is fine if there is not.

## Decision

1. For `docs/**/*.md`, 300 lines is a threshold, not a cap. At the threshold the writer asks what
   the doc is made of. The usual intruders are an inventory (a list the rule could generate instead)
   or the record of a settled deliberation (options weighed, history, dated rulings); both are cut,
   not moved. What may move: rationale, history and dated rulings go to `docs/reference/` or the ADR
   that chose the practice, leaving the procedure in place with one pointer; a phase only some runs
   use may move to a file the doc names, one level deep, never deeper. What never splits: one
   procedure's steps across files (an agent that reads half acts on half); a reference doc, unless
   it covers two subsystems. Staying long is right when the doc is one procedure read start to
   finish, or when the length is the exact commands and checks a pass needs.
2. A `docs/**/*.md` over 300 lines carries `[length: <reason>]` within its first 10 lines, on a
   line of its own under the title block by convention (after `Status:` in an ADR, after the
   frontmatter in a reference doc). The reason states which "staying long is right" case applies, in
   one clause. The marker is read as the literal text `[length:` followed by a non-empty reason and
   `]`, anywhere in the first 10 lines; `[length: ]` is no marker. Precedent: `[no-check: <reason>]`.
   The two markers are independent; one does not imply the other.
3. Enforcement moves from the number to the marker. The post-edit hook, on a `docs/**/*.md` over 300
   without the marker, warns with the question of item 1 and the marker syntax; with the marker, it
   says nothing. Pusher check 7 FAILs on a `docs/**/*.md` over 300 without the marker, and only then;
   it does not judge the reason. The reviewer judges it: a review of a change that adds or keeps the
   marker checks the stated reason against item 1 and reports a reason that does not hold as a
   serious finding. The handoff caps and the `CLAUDE.md` caps stay hard, marker or not.
4. `edit.capExempt` (ADR 0006) keeps its meaning: an exempt path is neither warned nor failed and
   needs no marker; `docs/design/proposals/` stays exempt by rule. Legacy docs stay on the exemption.
5. `.claude/skills/*/SKILL.md` over 500 lines: the post-edit hook warns, naming the Anthropic
   guidance and the one-level-deep rule for referenced files; nothing fails and no marker exists. A
   skill's other files are not counted.
6. The `--cap-check` output changes in one line. The per-file FAIL for a doc over the threshold reads
   `FAIL <path>: <n> lines, over 300 without [length: <reason>]`; the handoff lines keep
   `FAIL <path>: <n> lines, cap 40`; the sentinel `cap-check: <N> files, <M> over cap` is unchanged,
   so a pusher's "ends in a `cap-check:` line" test and the harness's parse of it hold. `N` counts the
   files checked (exempt ones not); a marked doc over 300 is checked and passes.
7. Rejected: a pure guideline with no marker (an agent deciding at push time weighs toward not
   splitting; the marker puts the judgement in the diff where the reviewer sees it); raising the
   hard cap to 500 (the same unargued number, one notch up).

Implementation touches: `.claude/rules/docs.md` (the Rule text below replaces "Size caps");
`.claude/agents/pusher.md` check 7 (threshold wording, the new FAIL text, "the fix is a trim, a split
or a stated reason"); `.claude/agents/reviewer.md` (the serious-by-rule list gains the marker check);
`.claude/hooks/policy.js` (`docCap` returns the threshold, `capCheck` reads the first 10 lines for the
marker); `.claude/hooks/post-edit.js` (the docs warning, the skill warning); `.claude/hooks/test-hooks.js`
(groups PX, PC and CAP: marked and unmarked docs over 300, a blank reason, the skill warn, the
unchanged sentinel); `0002-hook-specs.md` (the cap-check contract and the post-edit table row). The
hook specs sit just under 300 and may cross it with those rows: they are the exact checks a pass
needs, so they take the marker as its first use rather than a split.

## Rule text

Replacement for the "Size caps" section of `.claude/rules/docs.md`, heading included:

```
## Size thresholds and caps (pusher check 7; the post-edit hook warns)

- Any `docs/**/*.md`: 300 lines is the threshold where the writer asks what the doc is made of (ADR
  0023). Cut an inventory the rule could generate, and the record of a settled deliberation. Move
  rationale, history and dated rulings to `docs/reference/` or the ADR that chose the practice,
  leaving the procedure in place with one pointer; move a phase only some runs use to a file the doc
  names, one level deep. Never split one procedure's steps across files, and split a reference doc
  only when it covers two subsystems. Staying long is right when the doc is one procedure read start
  to finish, or when its length is the exact commands and checks a pass needs. A file over 300 then
  carries `[length: <reason>]` in its first 10 lines, naming which case applies; the post-edit hook
  warns about a file over 300 without it, the pusher refuses the push, and the reviewer checks that
  the reason holds. Exempt, needing no marker: `docs/design/proposals/` (a proposal carries its
  options, the critic's objections and the responses in one closed record), and a project's legacy
  docs named in `.claude/hooks/policy.json` `edit.capExempt`, which the post-edit hook and the pusher
  both read; the patterns' `*` crosses `/`, so name files when a folder's subfolders must stay
  checked. Split by topic, never by date, and never by "part 2".
- `docs/HANDOFF.md`, and each `docs/handoff/*.md` where that directory exists: at most 40 lines, at
  most 120 together, hard. Detail goes to an idea or a finding; the handoff links.
- `CLAUDE.md`: at most 40 lines, loading at most 190 (`node .claude/hooks/policy.js --claude-check`),
  hard; `edit.capExempt` naming `CLAUDE.md` lifts both. A new paragraph of facts goes to
  `docs/reference/` with a pointer.
- `.claude/skills/*/SKILL.md`: 500 lines is a threshold (Anthropic's guidance for skills); the
  post-edit hook warns, nothing fails. Split content into files the skill names, one level deep.
```

## Consequences

- A doc whose length is its checks grows without a cut elsewhere first; the cost is one reviewer
  judgement per marked doc, and the risk that reasons go stale as a doc grows for other causes. A
  reviewer who sees a marked doc gain an inventory reports it; nothing automatic does.
- The marker is a new token in tracked text. The path-anchor check ignores it; a reader of the
  first 10 lines of a long doc sees why it is long before reading it.
- Two surfaces change text: the `--cap-check` FAIL line and the post-edit message. Projects that
  parse the FAIL line (none known; the pusher reads only `FAIL` and the sentinel) adjust.
- The hook specs may become the first marked doc, in the same change that implements this.
- A `SKILL.md` warning on a path the docs rule does not load for means the message must carry its
  own guidance; the rule text is for the writer who opens `docs.md`.
- What would supersede this: marked docs accumulating reasons the reviewer waves through, which
  would argue for a hard number again, or a measured cost of long docs to the agents that read them.

Predictions (the trigger scan reads these):
- Prediction, check by 2027-02-01: no `docs/**/*.md` was split by "part 2" or across one
  procedure's steps, and every `[length:]` reason in the tree still names a case in item 1.
- Prediction, check when a reviewer rejects a `[length:]` reason: the rejection cites one of the
  cut-or-move tests in item 1; a rejection that cannot means the tests are incomplete and this ADR
  is amended.
