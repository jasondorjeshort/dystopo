# 0039. Benchmark other topo and navigation apps before deciding what users see

Date: 2026-10-23
Status: accepted

Record: the owner's words in the wave 18 planning session (2026-10-23): "If in doubt have an agent
review best-practice among other topo or navigation apps to make decisions. While we're at it, make
this policy within this project."

## Context

ADR 0031 lets a task build what humans see and touch in alpha without asking, shown in the PR for the
owner to adjust afterwards. That settles who decides; it says nothing about where a default, a
wording or a flow comes from when the idea or task entry leaves it open. Until now each implementer
chose from its own judgement, and the owner's adjustments after the fact have mostly been toward what
the established apps do: T114 (route to here) was the first task that benchmarked the long-press
flow against the mainstream and outdoor apps before building, and it was accepted as built. The
owner's answers of 2026-10-23 to the ideas folder lean the same way (feet below a tenth of a mile
"as US trail signs and the mainstream map apps do"; a list of stops modelled on "the best system
I've seen"). Wave 18 opens three proposals about flows (stops and reroute, region download, trace
upload) and two tasks that set defaults users notice (spoken directions, an automatic route
corridor), so the rule is written before they start.

## Decision

- **When in doubt, benchmark first.** Whenever a task or a proposal must settle something users see
  or touch (a default, a threshold, a wording, a control, a flow, what happens on an edge such as
  off route or screen off) and the idea, the task entry or an ADR does not already settle it, a
  read-only benchmark agent surveys how the established topo and navigation apps do it before the
  choice is made. "In doubt" is read generously: an implementer who would otherwise pick a value
  from taste asks for the survey.
- **The survey is an agent's, not the implementer's own browsing.** The coordinator (or the
  implementer, for a question inside its task) launches it with the `brief` skill's `benchmark.md`:
  one question, the apps to look at, what to report. Sonnet-class with web search; the most capable
  model when the survey must weigh competing designs rather than report them. It reads public
  documentation, help pages, reviews and screenshots; it installs nothing, signs up for nothing and
  copies no code or artwork (`CLAUDE.md`, Rules: no code from other projects).
- **The report is cited, and departures are reasons.** A proposal carries the survey in its
  `## Prior art` section (the `proposal` skill's template); a task's PR body states what the survey
  found, what was taken and where the build departs from the common practice and why. The
  departure, not the convention, is what the owner is asked to look at.
- **Which apps.** The mainstream navigation apps, the outdoor and topo apps the owner has named in
  the ideas folder and the open-source ones; the brief names the set for the question. Product names
  are allowed in proposals, PR bodies, docs and the log, never in user-visible text (`CLAUDE.md`,
  Decisions, owner 2026-10-13).
- **What it does not change.** ADR 0031 still holds: the task builds without asking and the owner
  adjusts afterwards. The survey informs the choice; it does not make the convention binding, and it
  does not replace the critic on a proposal. A technical choice with no user-facing side (a format,
  an algorithm, a dependency) follows the `proposal` skill as before and needs no survey.
- **Cost bound.** One survey per question, read-only, and never launched for a choice the idea or
  the owner has already made.

## Consequences

- Fewer owner adjustments after a PR for defaults and flows that the established apps have already
  settled; the owner's attention goes to the departures.
- One more agent per open user-facing question: cheap (Sonnet-class, read-only, minutes), but it is
  tokens, so briefs ask one question and the rule is not applied to what is already decided.
- The proposal template gains a `## Prior art` section; the brief skill gains `benchmark.md`;
  `CLAUDE.md` › Decisions gains one line. These are this project's files, not the base's; Hatchery
  may take the rule if a second project wants it (a carry-back is not planned).
- Supersede when the surveys stop changing outcomes (the owner's adjustments return to the same
  rate as before), or when the owner prefers a different source of convention.

- Prediction, check when wave 18 closes: the owner adjusts fewer of wave 18's user-facing changes
  after merge than wave 17's (T122's three strings and the 1.5 cap were all owner adjustments).
- Prediction, check by 2027-01-23: every proposal written since this date has a filled `## Prior
  art` section, and no task PR has picked a user-facing default without citing a survey or an
  earlier decision.
