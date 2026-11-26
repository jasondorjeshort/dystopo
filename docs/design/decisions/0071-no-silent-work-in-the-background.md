# 0071. No silent work in the background

Date: 2026-11-26
Status: accepted

Record: the owner's words of 2026-11-25 in the phone session, written down in
`docs/ideas/2026-11-25-no-silent-background-work.md` [no-check: deleted when T387 lands] and the finding
`docs/findings/2026-11-25-tile-cache-index-walk-loops-in-the-background.md`; made a record here at the word of wave 49's
critic (2026-11-26), because four tasks of that wave build to the rule and the idea that holds it is deleted when they land.

## Context

On the owner's phone (v0.1.43-alpha) Android ended the app 14 times in two days for "excessive cpu" while it was in
the background, and its own estimate put the app at about 5 % of a day's drain with 18 seconds on screen. The cause
was the online tile cache's index walk: it starts with the process, whatever started the process, runs longer than
Android lets a cached process work, is killed, and starts from nothing at the next start (the finding has the reads).
Nothing on the phone said that the app was working.

The owner, 2026-11-25: "We can enable an exception but mostly we don't want to silently be in the background doing
work. If we're doing work we want to be up front about it, possibly with an ongoing notification or etc."

What was read in the code on 2026-11-26 (wave 49's premise read): `DystopoApp.onCreate` starts every launch stage
whether or not a screen will open; the walk also ran on after the user pressed Home; a second cache (`ContourCache`)
walks at every start; a recording and an area download already run under a notification of their own.

## Decision

1. **The app works only where the user can see that it does.** Work runs while an activity of the app is on screen,
   or under an ongoing notification that names it (a recording's, an area download's). Anything else waits.
2. **A start without a screen runs only what that start needs.** A process started by a preload, a job or a
   broadcast does not walk a cache, sample memory, check for an update, resume pending map work or copy assets; each
   such stage is deferred until an activity starts. A recording that resumes does so under its notification.
3. **Leaving the screen pauses the work that has no notification.** Work begun on screen does not run on after Home;
   it resumes when the app is back, or goes on only while a notified job is up.
4. **Work that is paused is not lost.** A long job keeps its progress, so a pause or a kill costs what was not yet
   written, not the whole job.
5. **No notification is added to make work legal.** A new ongoing notification is a new thing the user sees and is
   the owner's to choose (ADR 0031); where none exists, the work waits for the screen.
6. **A waiter never turns a pause into a fault.** Code that waits on paused work does not time out, hold the main
   thread or hold a lock across the return to the screen.
7. **Every new feature states where its work runs.** A task that adds work outside the screen names the notification
   it runs under in its Goal, and its verifier checks a start without a screen.

The measure is the background battery use on a real phone: no `excessive cpu` exit in `dumpsys activity exit-info`
and the app near zero in `dumpsys batterystats` over a day left in the background.

Built by wave 49: T379 (the tile cache's walk and `ContourCache`), T390 (the walk's progress and a journal, so a
kill after one change does not cost a whole walk), T387 (every other launch stage), and bound on T377 (a search
index is built on use, never at a start) and T380 (a kept folder is written on screen or under a recording's
notification).

Out of scope: a low-battery mode and a measured battery budget (the battery idea of 2026-10-13); what the app may
fetch from the network while on screen. Not a Wi-Fi gate (ADR 0023): the rule is about being seen, never about the
kind of network.

Deferred: a notification of the app's own for long maintenance (a very large store's first walk), revived if the
day-long check on a phone shows that the walk cannot finish within the time the app spends on screen.

## Consequences

The first walk of a large store takes several sessions on screen instead of one stretch in the background, and what
needs the whole index (a removal, the cache budget's first run, a detail change) may wait for it; the app says so
where the user would otherwise see nothing happen. Start-up does less at a start without a screen and the same or
slightly later work at a start with one, so the start-up budgets are measured again by the tasks that move a stage.
Every later feature with background work carries a notification or waits, which is a constraint on designs such as
the automatic refresh of areas (ADR 0060) and group positions (ADR 0051): each already plans a notified job.

We would supersede this if Android offered the app a way to do short maintenance unseen within a stated budget and
the owner preferred it to waiting.

- Prediction, check when T379, T390 and T387 have merged and the owner has left a release with them in the background
  for a day: `dumpsys activity exit-info` lists no `excessive cpu` exit for that day, and the app's estimated drain
  in `dumpsys batterystats` is under 1 % of the battery.
- Prediction, check at wave 49's close: no task of the wave added an ongoing notification.
