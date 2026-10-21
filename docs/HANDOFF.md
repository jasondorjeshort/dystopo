# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-21, at commit bc2036d1, by main session
Resume: this file, `docs/reference/machines.md`, the `wave` and `release` skills, `docs/TASKS.md` › Wave 17 and its Later list (wave 18 candidates); ADR 0038 (the base line, the identity rule); the `handoff` skill.

## Now

- Wave 17 is closed: T120 to T125 merged (PRs #7 to #12), logged; the post-wave sweep green on the
  app (`out/sweep-w17`, `-w17b`; the `update` part's `wrong-checksum` step is a sweep fixture
  defect, on the board; its real-API step stays red while the repository is private). The tag
  `v0.1.10-alpha` is on bc2036d1 (versionCode 461); an agent builds the release locally (Actions
  off), drafts it and verifies from the draft; the owner publishes it (one `gh release edit` line).
- The owner's identity rule landed (every pushed commit under the owner's identity; history and the
  v0.1.9 tag rewritten; trailers allowed); Hatchery took it as ADR 0020 and decided ADR 0021
  "guardrails bound damage, not who may act" (PRs #2, #3 merged there; its tasks T-a to T-g not
  started). The classifier refuses merges and publishing from a session: the owner types them.
- Leftovers: `dystopo-wt/T122` (locked by an unidentified process), `T122-rebase`, stray `T123-verify*`
  files, AVDs `dystopo35-T122/T123/T124`, branch `task/T122`; the viewer runs on 8765, 8800 is down.

## Next

1. Publish v0.1.10-alpha from the verified draft (owner's line), log it, README status line.
2. Hatchery: ADR 0021's follow-ups T-a to T-g as a wave from a Hatchery-rooted session (agents may
   commit and push its work branches now); then Dystopo's second base update and a `CLAUDE.md`
   rules pass (light lane, the guard's reversible line, identity source, `usage.mode: block`).
3. Plan wave 18 from the Later list and the owner's `Answer:` lines; critic pass before launch.
4. A chore for the leftovers above once the T122 holder is gone (`gradlew --stop` is the owner's).

## Waiting on user

- The publish line for v0.1.10-alpha; the eight `Answer:` lines in `docs/ideas/`; the T122 folder
  (`gradlew --stop`), `git branch -D triage-saved` and the `refs/original/*` cleanup; whether the two
  older Claude-authored commits get rewritten; T122's strings (nav card 1.5 cap, three help texts).
- The repository is private: no phone's updater sees the releases and Actions does not run.
- Real-phone checks; Google developer registration; MapLibre's compass (T100); T104's palette and captions; the wave 14 to 17 strings and help texts (ADR 0031); the 5 s timeout grace.

## Open findings

- `docs/findings/2026-10-21-direct-view-out-of-memory-after-a-tour.md`
