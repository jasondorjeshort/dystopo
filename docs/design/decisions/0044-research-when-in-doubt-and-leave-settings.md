# 0044. When in doubt, an agent researches best practice; leave a setting when useful

Date: 2026-10-25
Status: accepted

Record: the owner's words at the start of the session of 2026-10-25, as a standing order for new
waves: "if in doubt on decisions, launch an agent to review best practices and find the answer.
When useful, leave user settings to control behavior."

## Context

ADR 0039 made a benchmark survey the rule for user-facing choices in doubt (defaults, wordings,
controls, flows) and said a purely technical choice needs none. Since then every survey (T126's
spoken cues, T130's corridor indicator, T137's mute and notice, the three proposals' Prior art) has
been cited and built as the owner's adjustments predicted. The owner's order widens the rule in two
ways: it covers any decision in doubt, not only what users see, and it asks for a setting where one
helps instead of fixing a single behaviour. The app already carries the machinery a setting needs:
a key in `AppSettings` and `SettingsRoutes.KEYS` (`POST /settings`), sync through
`sync/SettingsSync.kt`, a row placed by `settings/SettingsLayout.kt` and a help text checked by
`HelpRegistryTest` (`docs/reference/android-app.md`). Units (ADR 0022), the corridor width (T130),
spoken directions (T126) and the cache budget (ADR 0034) are settings made this way.

## Decision

- **Any decision in doubt gets a research agent first.** ADR 0039 stands for user-facing questions
  (the benchmark of the established topo and navigation apps). A technical choice in doubt that no
  ADR, task entry or owner's answer settles (an algorithm, a threshold inside the code, a library,
  a file layout, a test or build practice) gets the same read-only agent with the technical variant
  of the `brief` skill's `benchmark.md`: published practice, standards and specifications, the
  documentation of the libraries in question and how well-regarded open-source projects solve it.
  This replaces ADR 0039's "a technical choice ... needs no survey"; the rest of ADR 0039 stands.
- **Who launches it.** The coordinator while planning a wave (before the critic, so the plan carries
  the answer) and for a choice that comes up between waves; an implementer for a question inside its
  task. Sonnet-class with web search; the most capable model when competing designs must be weighed.
  One question per agent; read-only; installs, signs up for and copies nothing.
- **"In doubt" is read generously, and its bound is ADR 0039's.** A choice that would otherwise be
  made from taste or habit is in doubt. Never for a choice an ADR, the task entry or the owner has
  made, and a major choice still goes through the `proposal` skill (the research fills its Prior art;
  it does not replace the critic).
- **Leave a setting when useful.** When the research shows reasonable apps or users split (by
  activity, region, device or taste), or the cost of the wrong default falls on some users and not
  others, the task builds the behaviour as a user setting: the researched default, the other choices
  the research found, its key synced like the others unless it is per device, a row in the fitting
  settings group, a help text, and the debug route that sets it (`CLAUDE.md`, Rules: agent-testable).
  The report says which choices became settings and why; the strings go to the owner as ADR 0031
  says.
- **When not to add a setting.** When the research finds one clear practice and no user group it
  hurts; when the choice is a safety or privacy rule (ADR 0023's no Wi-Fi gates, the confirmation
  before data leaves the phone) rather than a preference; or when a setting would only hide a
  defect. A setting nobody needs costs a row, a help text, sync and a test case each.
- **Where it shows.** The wave skill's planning step lists the open choices and their research, and
  the critic is asked about choices made without it; the implementer brief carries the rule; the
  proposal template's Prior art covers technical choices too.

## Consequences

- More research agents per wave: each is cheap (Sonnet-class, read-only, minutes), and the
  one-question bound and "never for a settled choice" keep the count down.
- Settings grow. Each one costs a row, a help text, a sync key and a test; the "when not" list and
  the report's reason per setting keep that in check, and the owner prunes from the reports.
- `CLAUDE.md` › Decisions, `.claude/skills/brief/` (`SKILL.md`, `benchmark.md`, `implementer.md`),
  the `proposal` skill and its template, and the `wave` skill's planning steps change; all are this
  project's files, so there is no carry-back. Hatchery may take the rule if a second project wants it.
- Supersede when the research stops changing outcomes, or when settings start to crowd the sheet
  (the owner asks to remove them faster than tasks add them).

- Prediction, check when wave 21 closes: every wave 20 and 21 report names the research behind each
  open choice (user-facing or technical) or the decision that settled it, and every setting added
  says why it is a setting.
- Prediction, check by 2027-01-25: the owner removes or merges fewer than one in four of the settings
  added under this ADR.
