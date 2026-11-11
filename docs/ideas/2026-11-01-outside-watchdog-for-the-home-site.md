---
date: 2026-11-01
kind: chore
---

# An outside watchdog for the home site: something not on the PC checks `/healthz`

T199's measurement: Task Scheduler's restart-on-failure does not restart a killed process, so the
installer added an every-minute trigger; `tools/site/status.ps1` tells the owner the site is up, but
only when run on the PC. If the site matters while the owner is away, the check has to come from
outside: a phone, a free-tier cron, or the nightly sweep's host, fetching `https://<host>/healthz`
every few minutes and sending one message when it fails twice. The server's `/healthz` is already the
right endpoint (200 serving, 503 shutting down, `docs/SERVER.md` › What to monitor).

## Open

- Where the check runs and how it notifies without a new service account on the owner's behalf.
- Whether the in-app updater's release check (T83, from every phone) could double as the probe
  for the owner's own phone: it already reaches out on a schedule.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): `/healthz` is the right probe (200 serving, 503 stopping, unauthenticated through the
proxy); `status.ps1` checks it from the PC only. The updater's daily check (`update/Updater.kt`) goes to
the GitHub releases API once a day and refuses other hosts, so it is not a probe.

Question: where should the check run and how should it reach you? The choices are an outside service
with an account (a free-tier uptime monitor that fetches `/healthz` every few minutes and mails or
pushes on two failures), your own phone, or the sweep host, which is the same PC. Recommendation: the
uptime monitor, which needs no code: you sign up and point it at the URL; a task then adds the setup
lines to `docs/reference/home-site.md` and `docs/SERVER.md` › What to monitor without naming the host.
Nothing from every user's phone (a new data flow for no gain).
Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
