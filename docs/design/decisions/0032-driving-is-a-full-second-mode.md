# 0032. Hiking first; driving is a full second mode

Date: 2026-10-14
Status: accepted (the owner's decision)

Record: the owner, 2026-10-13 (`docs/ideas/2026-10-13-driving-is-in-scope.md`: "if driving can be
made first class, or second among equals after hiking, that is good. Direction: hike first, drive a
full second mode (not a trailhead helper)"); T73's drive mode (log entry 2026-10-11, "stated plainly in
the app as not a road navigator"); T77, ADR 0028 and 0029 (long drives); the turn-directions idea's
brainstorm (instructions in every mode).

## Context

T73 shipped drive mode as a way to the trailhead and said so in the app: `Router.kt` (the KDoc, the
plan's note "It is not a road navigator: no turn restrictions, traffic or seasonal closures", the
route name suffix) and `RemoteRouter.kt` repeat "not a road navigator". T77 and ADR 0028 and 0029 then
made drives of hundreds of kilometres plan in seconds on the phone and the server, with corridor
downloads and a drive-time sum from the router. The doctrine and the wording disagree, and the
turn-directions and route-to ideas both assume drive is a mode like the others.

## Decision

1. **Order of care:** hiking (and biking) first; driving a full second mode. Every routing, direction,
   waypoint, rerouting, corridor-download and navigation feature treats drive like the other modes,
   with drive-specific parameters where the mode differs (speeds, lead distances in miles rather than
   tens of metres, zoom ranges for corridors, the overlay levels of ADR 0029).
2. **Honest scope, no disclaimer by name.** The app says concretely what drive mode does not do:
   planned offline from the map packs' roads, without turn restrictions, traffic or seasonal
   closures. It does not call itself "not a road navigator". T102 rewrites the lines in `Router.kt`
   and `RemoteRouter.kt` and the route card's wording; the sentence lives in one place in `shared` so
   the phone, the browser and the devtool say the same.
3. **Tests carry a drive case.** A routing or navigation feature's tests include the Paradise to
   Sunrise golden drive (or the two-region drive of ADR 0029) beside the hike cases.
4. **Not decided here:** turn restrictions, one-way handling beyond what the profile already keeps,
   traffic and closures. Each is a proposal when wanted (OSM carries restrictions; whether the
   outdoors profile keeps them is the first question).
5. **Standing rules apply:** no Wi-Fi gates (ADR 0023); no other product names in user-visible text
   (`CLAUDE.md` › Rules); units follow the preference (ADR 0022).

## Consequences

- Drive features are product work, not debugging aids; the router's drive model (per-class speeds,
  the overlay) gets the same follow-up attention as the hike model.
- The disclaimer stays accurate while being shorter; users see what the mode is for rather than what
  it is not.
- Rejected: keeping drive a trailhead helper (the owner's direction is the opposite, and the long-drive
  work of ADR 0028 and 0029 already exceeds it); making drive equal first (the owner's order is hike
  first).
