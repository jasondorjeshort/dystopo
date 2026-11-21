---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-follow-ups-home-site-route-table-and-feed-emails.md]
---

# Flight refresh choices of 2 and 3 minutes, and a limit line that says how long the day lasts

The flights layer polls adsb.lol every 20 s by default (`AircraftRules.REFRESH_DEFAULT_S`), choices 10, 20, 30 and
60 s (`REFRESH_CHOICES_S`, the setting `AppSettings.AIRCRAFT_REFRESH_S`). The feed allows 500 requests per IP per UTC
day (`AircraftRules.DAILY_REQUESTS`, counted by `AircraftQuota`). At 20 s that is under 3 hours of use; even 60 s is
8 h 20 min, so no choice lasts a day, and when the limit hits the layer stops until UTC midnight.

| Interval | Lasts |
|---|---|
| 10 s | 1 h 23 min |
| 20 s | 2 h 47 min |
| 60 s | 8 h 20 min |
| 120 s | 16 h 40 min |
| 180 s | 25 h |

The owner's call: add **2 minutes and 3 minutes** as options. Between polls the aircraft are moved along their track
by `AircraftTracker.moveUp`, capped at `MOVE_CAP_S` (30 s), so a long interval needs the cap and the stale and drop
times (`STALE_S` 30 s, `DROP_S` 60 s) to follow the interval, or every aircraft is hollow and gone between polls.

## Open

- The aircraft rules at 120 and 180 s: the stale and drop thresholds and the movement cap scale with the interval
  (for example stale after 1.5 intervals, dropped after 2.5) instead of the fixed 30, 60 and 30 s; the trail and
  `HISTORY_MAX` (720 points, written for 20 s) with them.
- "Stopped" after `STOPPED_INTERVALS` (3) failed intervals and at least 60 s: at 180 s that is 9 minutes of silence.
- The owner also leans to an interval that adjusts itself (the day's remaining requests over the time left, with a
  floor of 10 s), or an Automatic choice beside the fixed ones; not decided here.

Plan: the two choices added to `REFRESH_CHOICES_S` (10, 20, 30, 60, 120, 180; the default stays 20) with the setting's
row and help text (`settings/SettingsPages.kt`, `help/Help.kt`) naming how long each lasts. The stale, drop and
movement-cap rules take the interval (`AircraftRules` functions of `refreshS`, passed through `AircraftTracker.view`
and `onFetch`), tested in `AircraftTest` at 20, 120 and 180 s (a position 100 s old is neither hollow nor dropped at
180 s). The "requests left" line (`AircraftPoller`, "about N requests left today") also gives the hours left at the
current interval ("about 4 h at this rate"). Debug: `GET /aircraft` reports `refreshS` and `hoursLeft` (the KDoc of
`AircraftRoutes.kt`); `POST /settings` accepts 120 and 180. Verify: unit tests, the emulator's flight frames with a
180 s setting through a replayed feed (`rainier-replay.json`), `android-ui.py`'s flights step. New strings in the
report (ADR 0031). Out of scope: Automatic.
