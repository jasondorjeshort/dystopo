# 0022. Units are a user preference; region only seeds the default

Date: 2026-10-10
Status: accepted (owner's decision)

Record: `docs/PLANNING.md` › Units (decided 2026-10-10); T34 (by-region default in the viewer), T33
(the phone's persisted preference); the Android settings in `docs/reference/android-debug-routes.md`.

## Context

The first viewer chose feet or metres from where the map was looking (feet inside the US). A user
who travels, or a US hiker reading a Canadian map, then saw units change under them, and contour
intervals, labels and track statistics changed meaning between two views. The owner decided on
2026-10-10 that units belong to the user, not to the place.

## Decision

- Feet versus metres is one stored preference per user and device. Travelling keeps your units.
- Region only seeds it, once: the browser demo picks feet inside the US and metres elsewhere the first
  time the page is opened, then keeps what the user chose (localStorage; `?units=ft|m` overrides one
  load without storing). The phone starts unset on install and seeds once from the first pack's
  centre or the first GPS fix; after that only the user changes it (`POST /settings?units=ft|m|unset`
  on debug builds, the Units row in Settings).
- Everything that shows an elevation or a distance follows the one setting: contour tiles and their
  intervals and labels, peak elevations in the style, track statistics, the elevation profile, search
  results, route cards. Contour tiles are cached per unit, so a change regenerates them.
- The setting is synced like other `app.*` settings through the operation log, so a user's devices
  agree.
- Unknown `units=` values are a 400 on the server and the phone (T74), never a silent fallback.

## Consequences

- No code may choose units from a location after the seed; a test or review that finds one treats it
  as a defect.
- Every new feature that shows a length or a height reads the setting; the style and the contour cache
  key carry it.
- Supersede only on the owner's word: what humans see is the owner's decision (`CLAUDE.md`, Rules).
