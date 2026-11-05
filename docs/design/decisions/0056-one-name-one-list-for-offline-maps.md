# 0056. One name and one list for downloaded maps, and what the status chip says

Date: 2026-11-04
Status: accepted

Record: proposal `../proposals/0056-one-name-one-list-for-offline-maps.md` (the specification: the list's join and state words, the chip's tables, every string site by file, what stays as it is) and `../proposals/0056-critic-review.md`.

## Context

A user met five names for one thing (pack, region pack, offline area, region, keep offline) on two screens, and the Areas screen's list omitted every pack that arrived other than by an area download. T225 proposed the fix after the wave 28 surveys; nothing is built yet. The owner chose the noun on 2026-11-04 ("I'd say offline map - consistency trumps") and accepted the proposal's other items ("Rest makes sense. "pack" seems inconsistent."). This departs from the proposal on one point: it kept "pack" in `docs/SERVER.md` as the developer's word, and here the manual changes too.

## Decision

- **Noun: "offline map" everywhere a person reads about using the app or running the server (the proposal's V1):** the phone, the web page, the server's panel and `docs/SERVER.md`. "File" stays an ordinary word and is not a second noun. Code, routes, JSON keys and stored data keep "pack" (ADR 0033), as the proposal's "What stays as it is" lists; `docs/SERVER.md` names a route or key as it is written. B2 takes the manual.
- **One list, "Offline maps" (L1),** joining `PackManager`'s packs with the area records, so a map shows once however it arrived. The join, the state words and the add actions are as the proposal specifies.
- **The status chip has two parts in plain words, with a tap that explains (C1),** keeping ADR 0023's "Paused", "Connecting" and the red dot of an unreachable server.
- **Built as the proposal lists, accepted by the owner:** the chip's label "Offline maps"; a row's tap opens its details; "Server offline" when the phone has a network, with "Offline" still red without one; "Remove", "Keep on this phone" and "May be cleared"; "Build a map here" on the server's panel; the list opening full screen.
- **Build tasks:** B1a, B1b, then B3 and B2 together with B3 merging first, as the proposal's Recommendation specifies, each re-reading its sites after wave 28's merges. Deferred: the row model in `shared`, until a second client needs the list.

## Consequences

Every string site changes across several owners' files, and a `RetiredNounsTest` with an allow file then has to be kept. A user who handles map files by hand has no separate word for them; the proposal counts ten strings as the cost of reversing that. The Areas sheet's map above the list on opening goes. Reopen the noun if a flow arrives that exports a map to a file or sends one by hand, or if testers ask for the file by a name; the proposal's "What would change this recommendation" has the rest.

Predictions (the trigger scan reads these).
- Prediction, check at the first sweep after B2 ships: no screenshot of that sweep's `ui/` set shows a retired noun.
- Prediction, check when the owner has adjusted the build's strings (or by 2027-05-01): fewer than a third of the strings in the proposal's table were reworded again, and the noun was not reversed.
- Prediction, check by 2027-05-01: `retired-nouns-allow.txt` has fewer than sixty lines. If it passes that, the shape rule is wrong.
