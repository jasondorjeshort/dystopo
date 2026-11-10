---
date: 2026-11-08
kind: feature
---

# One registry of every user-visible string, a per-release diff for the owner, and the step that makes translation trivial

The owner, 2026-11-08: "Sounds cool. At some point we'll want to allow trivial translation for localization so we
should fold this into an intl step and do the full research for it."

Today user-visible strings are literals in Compose code, the web page's HTML and scripts, and the server panel.
T237's `RetiredNounsTest` reads the literals for retired nouns, so a reader over them exists; ADR 0031 shows new
strings in merge bodies; but implementers still coin wordings nobody reviewed (wave 30's Storage headings, "Download
for offline"), and nothing could translate the app today: Android's `strings.xml` is unused, the web page has no
message catalogue, the units and clock already have their own rules (ADR 0022, T218).

## Open

- The research first (ADR 0044, technical variant): how Compose apps and small web pages do localisation today
  (Android resources with Compose's `stringResource`, or a Kotlin Multiplatform catalogue shared with the web page and
  a later iOS app), plurals and placeholders, right-to-left, and how open-source projects take community
  translations (Weblate and the like) without a server of their own (ADR 0059).
- One registry: every string a person reads, by key, with its English text and a note on where it shows; a test like
  `RetiredNounsTest` that fails `check` on a new literal outside it (the lexer already tells code from text); the
  web page's and the server panel's strings in the same catalogue or a mirrored one.
- A per-release diff of the registry for the owner (added, changed, removed strings), in the release notes' folder,
  so wordings get one reading before testers see them.
- The intl step itself: the English catalogue as the source, a second language as the proof (the owner's choice),
  and the rules that stay code (units by region, the clock by the phone's setting).

Plan: one no-Gradle pass, the `proposal` skill (a format other clients read, so a major choice): proposal 0062 on
the string catalogue and localisation, the technical research first (ADR 0044: Android resources with
`stringResource` against a Kotlin Multiplatform catalogue in `shared` that the web page and a later iOS app read;
plurals, placeholders, right-to-left; community translation without a server of our own, ADR 0059), options at equal
depth with exit costs, critic, revision. It also specifies the registry test (the `RetiredNounsTest` lexer failing
`check` on a literal outside the catalogue, with an allow file), the per-release diff's place in the `release`
skill, and the migration order by screen. Owns `docs/design/proposals/0062-*.md` and its critic review and the
proposals README line. Done when: as T244's (critic filed, every objection answered, Prior art with sources, the
reversal named); the second language stays the owner's choice, asked in the proposal. Out of scope: any code.
