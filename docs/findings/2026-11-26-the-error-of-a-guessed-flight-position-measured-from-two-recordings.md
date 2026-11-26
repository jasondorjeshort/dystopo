---
date: 2026-11-26
status: open
area: flights / polling and extrapolation
---

# The error of a guessed flight position, measured from two recordings

## Question

`2026-11-23-flights-guessed-between-polls-at-slow-refresh.md` reasoned how far a straight-line guess of an
aircraft's position drifts from the truth at 30 s, 2, 5 and 10 minutes, from geometry alone, and asked for a
measurement before any guessed state is built. How large is the error in recorded traffic?

## What was tried

T365 (wave 48, merged 657fd4fa): `devtool aircraft --record` saved every answer of the public flight feed for two
hours at 30 s around two areas of `areas.json`, 54 nm radius (100 km): `rainier` on 2026-11-25, 08:07 to 10:05 UTC
(236 polls, 234 answered; local night: 45 aircraft, 2775 fixes, 38 of the 45 seen on the ground), and `mont-blanc`
on 2026-11-26, 05:20 to 07:20 UTC (240 polls, all answered; local early morning: 135 aircraft, 3042 fixes). 480
requests in all with a 4-poll proof, inside the tool's own limit of 240 a UTC day. `devtool aircraft --report`
applies the tracker's own `moveUp` (its cap lifted to the horizon) from each fix, compares with the real fix nearest
30, 120, 300 and 600 s later (within 15 s), and bins by the phase at the start of the guess: ground; climb or
descent (500 ft/min or more); terminal (level, below 10,000 ft or under 200 kt); cruise. `--every k` feeds the
tracker every k-th answer. The verifier reproduced every figure from the recordings and checked two aircraft by
hand from the raw polls. The recordings are not committed.

## Result

Error of the guess in km, median / 90th percentile / 99th, with aircraft and fixes behind each cell. `*` marks a
thin cell (under 10 aircraft or 50 fixes).

Mont Blanc, morning:

| Phase | 30 s | 2 min | 5 min | 10 min |
|---|---|---|---|---|
| Cruise | 0.04 / 0.16 / 0.79 (89, 1347) | 0.18 / 1.7 / 6.9 (83, 1135) | 0.88 / 9.5 / 31 (67, 749) | 4.0 / 26 / 62 (47, 278) |
| Climb or descent | 0.08 / 0.81 / 2.1 (68, 1090) | 1.3 / 9.6 / 19 (67, 954) | 10 / 41 / 67 (59, 739) | 51 / 105 / 149 (37, 355) |
| Terminal | 0.16 / 1.9 / 2.8 (24, 110) | 1.9 / 12 / 19 (24, 107) | 8.6 / 45 / 66 (21, 86) | 28 / 103 / 107 (11, 37)* |
| Ground | 0.08 / 0.25 / 2.2 (37, 196) | 0.22 / 4.9 / 11 (36, 159) | 0.61 / 19 / 30 (27, 153) | 13 / 48 / 84 (22, 145) |

Rainier, night:

| Phase | 30 s | 2 min | 5 min | 10 min |
|---|---|---|---|---|
| Cruise | 0.04 / 0.21 / 1.9 (10, 84) | 0.19 / 3.8 / 13 (9, 71)* | 2.2 / 20 / 36 (7, 50)* | 16 / 73 / 89 (7, 27)* |
| Climb or descent | 0.08 / 0.79 / 1.9 (28, 621) | 1.3 / 11 / 18 (28, 572) | 13 / 41 / 60 (27, 472) | 49 / 107 / 131 (19, 294) |
| Terminal | 0.10 / 0.80 / 1.9 (21, 83) | 1.4 / 7.1 / 16 (21, 83) | 7.7 / 29 / 53 (21, 81) | 29 / 84 / 103 (14, 53) |
| Ground | 0.00 / 0.16 / 0.60 (38, 1599) | 0.01 / 0.53 / 7.1 (38, 1513) | 0.01 / 1.1 / 21 (33, 1424) | 0.01 / 7.7 / 70 (25, 1288) |

As an angle from an observer (the angle whose tangent is error over range), the 90th percentile at Mont Blanc:
cruise at 2 minutes 9.7 degrees at 10 km and 2.0 at 50 km; cruise at 5 minutes 43 and 11; climb or descent at
2 minutes 44 and 11.

What the tracker would have drawn at a slower refresh (every k-th answer), Mont Blanc, median / 90th percentile in
km over all phases, with the share of real fixes it drew nothing for: 60 s 0.10 / 1.3 (8 %); 120 s 0.17 / 2.4
(11 %); 300 s 0.47 / 9.5 (22 %). Cruise alone stays near 0.1 / 0.7 km to 120 s and reaches 0.2 / 2.4 at 300 s;
climbing traffic reaches 0.4 / 4.0 km at 120 s and 1.9 / 15 at 300 s. The misses are aircraft newly in range, not
drawn until the next answer that is fed.

Fields, airborne entries: `track` is sent for nearly all (100 % and 99.7 %); `true_heading`, `track_rate` and
`roll` for 97.5 %, 88.9 % and 94.6 % around Mont Blanc and for 2.9 %, 0 and 0 around Rainier, so whether a turn
rate is sent depends on where the aircraft is.

Against the earlier finding's table: the medians sit at or under its "steady" rows (cruise at 2 minutes 0.18 km
measured, 0.31 reasoned; climb or descent 1.3 against 2.0), and the 90th percentiles sit near its "30 degree turn"
rows, so its one-number answers are too kind to the tail. Its 2 minutes holds for cruise traffic on the map and far
in the sky views, not for climbing, descending or terminal traffic (7 to 12 km at the 90th percentile). Its
5 minutes for cruise on the map holds at the median only (0.9 km; 9.5 km at the 90th percentile). Its 10 minutes for
nothing stands.

Limits: one two-hour sample per place on two days; the Rainier sample is at night, its cruise cells rest on 5 to 10
aircraft and prove little alone; a fix's phase is the one at the start of the guess, so an aircraft that takes off
counts as a large ground error; altitude is not compared; the truth is the feed's own position, multilateration
included (7 % of airborne entries at Rainier, 1 % at Mont Blanc).

## Next

1. The guessed state (the earlier finding's recommendations) can take its horizons from these figures: by phase, not
   one number; a guess horizon near 2 minutes for cruise and well under it for climbing and terminal traffic.
2. A daytime recording around `rainier` if the table must stand on two full samples (240 requests of one UTC day;
   no flights step on an emulator during it).
3. A turning guess cannot rely on `track_rate` and `roll`: they are absent in one of the two regions.
