# 0031. Interface changes in alpha: build from the idea, show it, adjust afterwards

Date: 2026-10-14
Status: accepted (the owner's decision)

Record: the owner, 2026-10-13 (`docs/ideas/2026-10-13-alpha-interface-freedom.md`: "changing the
interface is fine while we are in alpha ... Do not hold back a better layout to keep the old one
stable") and 2026-10-14, planning wave 14 ("We can just roll wave 14. Ideas changing what users see
are completely fine (that's the point of the ideas)"); the wave 13 paragraph of `docs/TASKS.md`, where
T95's and T96's new wording was kept as built and polish deferred to the next wave.

## Context

`CLAUDE.md` › Decisions said that what humans see and touch (the front end, flows, wording, visuals)
needs the owner's sign-off before it is built: flag it and do not guess. The rule fitted a project
whose interface changes were surprises. By wave 13 the ideas folder held a dozen interface ideas the
owner had written or brainstormed (the recentre button's modes, a scale bar, settings tabs, turn
directions, two slope shadings), each waiting for a sign-off that, when asked for, was given in bulk.
Meanwhile new wording that shipped inside tasks (T95's Offline offer, T96's "Wrong code") was accepted
after the fact and polished later. The owner has now said twice that in alpha the interface is free
to change. The alpha milestone is ADR 0024's: alphas on GitHub Releases now, beta through Google Play
later.

## Decision

1. **Build from the idea.** While the app is in alpha (until the first beta through Google Play, ADR
   0024), an interface change that an idea in `docs/ideas/` or a task entry in `docs/TASKS.md`
   describes is built without asking first. The implementer chooses layout, icons and wording within
   the idea's text and its decided brainstorm lines; the PR shows the result in screenshots that the
   implementer and the verifier have viewed, and lists every new user-visible string.
2. **Sign-off moves to afterwards.** The owner sees the built result (the alpha on their phone, the
   PR's screenshots) and asks for changes; the coordinator records them as follow-ups for the next
   wave. Nothing is reverted meanwhile unless the owner says so.
3. **Still flagged before building:** a change no idea or task entry describes that removes a flow
   users have (a button, a screen, a setting); the app's identity (name, icon, package); anything that
   sends data somewhere new; and every data format. Interface freedom never covers stored data: the
   track database rule (`.claude/rules/trackdb.md`), the units preference (ADR 0022) and the area
   manifest (ADR 0027, 0030) migrate as before.
4. **What stays:** agent-testability (a new control gets its debug route and its screenshot in a
   tour), the privacy rules, accessibility at font scale 2.0, and the owner's rule that user-visible
   text names no other mapping software (2026-10-13; `CLAUDE.md` › Rules).
5. **Expiry.** At the first beta this ADR is superseded by one that says how interface changes are
   reviewed with testers.

## Consequences

- Waves plan interface work from the ideas folder without a sign-off round; the owner's time per wave
  goes to looking at results, not at proposals. `CLAUDE.md` › Decisions changes in the same commit.
- A result the owner dislikes costs a second task; the PR's screenshots and string list keep that
  cheap to specify. Two ideas that change the same screen in one wave need disjoint files, resolved in
  the wave paragraph as for any other task.
- The `brief` skill's rule that a brief never authorises what the owner has not still holds for
  actions (publishing, deleting, spending); interface choices within an idea are authorised by this
  ADR.
- Rejected: keeping prior sign-off and batching questions to the owner at wave planning (what wave 13
  did; the owner called the ideas themselves the sign-off); dropping sign-off entirely (data formats
  and removed flows stay flagged because they are not undoable by a later task).
