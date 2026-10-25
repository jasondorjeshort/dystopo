Read-only survey of how the established topo and navigation apps handle one question (ADR 0039),
or, in the technical variant, of the best practice for one technical choice (ADR 0044).
You install nothing, sign up for nothing, run nothing in this repository, and copy no code, strings
or artwork. Public documentation, help pages, release notes, reviews and screenshots are your
sources; cite each one.

Question: <one question, as the user would meet it: "what does the app say and do when the hiker
leaves the route, and after how long", "what default width does an offline corridor around a route
get, and is it a setting">.

Why it is open: <the idea or task entry that leaves it open, and what has already been decided
around it: `docs/ideas/<file>`, `### Tnn` in `docs/TASKS.md`, the ADRs that bound it>.

Technical variant (replaces "Apps to look at"): Sources to look at: <published practice, standards
or specifications, the documentation of the libraries in question, well-regarded open-source
projects that solve it; name them>. Report the same way, per source instead of per app, and weigh
licences (`CLAUDE.md`, Rules: permissive only, no copied code) and this project's constraints
(offline first, `commonMain` pure Kotlin).

Apps to look at: <the set for this question: the mainstream navigation apps, the outdoor and topo
apps the owner has named in `docs/ideas/`, the open-source ones; name them>. Skip an app whose
behaviour you cannot establish from a public source; say so rather than guess.

What we have today: <the current behaviour and where it lives, so the report can say "same as",
"differs by">.

Report, under 60 lines, as a table per app (behaviour, default values, wording where it matters,
the source) followed by: the common practice (what most do), the outliers and why they differ when
a source says, and a recommendation for this project with its reasons, including whether the behaviour should
be a user setting (the apps or users split, or a wrong default hurts some users) with its default
and choices, or one behaviour (ADR 0044), marking anything that would
need a product name in user-visible text (`CLAUDE.md`, Decisions: not allowed) and anything that
would conflict with ADR 0023 (no Wi-Fi gates), ADR 0025 (the design goals) or ADR 0031 (built in
alpha, adjusted by the owner). The recommendation informs the choice; the implementer or the
proposal makes it and states where it departs from the common practice.
