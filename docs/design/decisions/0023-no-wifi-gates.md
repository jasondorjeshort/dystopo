# 0023. No Wi-Fi gates: data use is steered by visible limits, never by network type

Date: 2026-10-11
Status: accepted (owner's rule)

Record: `docs/PLANNING.md` › Design goals, principle 3 (the owner's sentence of 2026-10-11); T83 in
`docs/TASKS.md` and its log entry; `docs/reference/android-online.md` (direct sources, updates).

## Context

T80 let the phone read the elevation sources itself. A cold z12 view then cost 66 to 103 MB (T85
cut it to about a third), so T80 made direct reading Wi-Fi only by default (`directOnMobileData`
off) with a "needs Wi-Fi" message on mobile data. On 2026-10-11 the owner ruled that out: a hiker on
mobile data at a trailhead must get a map, and a check that silently fetches nothing on one network
type looks like a broken app.

## Decision

- Nothing in the app is limited to Wi-Fi. Everything except LAN discovery (NSD, the share screen's
  discovery) works over mobile networks.
- Data use is steered by limits the user can see and raise: the direct-sources session limit
  (`directSessionMb`, default 300 MB; choices 100 MB, 300 MB, 1 GB in Settings › Sources; reaching it
  shows "Online data limit reached for this session" on the map and the card), the cache budgets
  (Storage screen, T74), the 512 MB source cache trim.
- `directOnMobileData` stays as a setting and defaults to on (T83). A user who turns it off sees
  "Online map paused: mobile data is turned off for it in Settings › Sources" on mobile data; the
  online indicator says Paused. A configured server is unaffected by it.
- The in-app update check and download run on any network (T83) and never count against the
  direct-sources session limit.

## Consequences

- A new feature that downloads must come with a visible limit or budget, not a network-type check.
- Tests and sweeps include the mobile-data path: `tools/android-firstrun.py --direct` has a
  `mobile-data` step (wifi off, data on; skipped on an emulator image without a cellular network).
- Open: the first real mobile-data test is the owner's phone (Waiting on user in the handoff).
- Supersede only on the owner's word.
