---
date: 2026-11-15
status: open
area: routing / drive time and fuel
---

# A drive's speed profile puts least fuel's town savings at 15 to 28 percent, not 33 and 47: the turn fuel overcounts

## Question

T284's least-fuel route saves 33 % (Les Houches to Chamonix) and 47 % (Chamonix to Les Bossons) against the
least-time route by the table model, far above the published 3 to 10 % for a petrol car. T290 found about four
fifths of that saving is turn fuel. Is the saving a model error, and if part of it is real, what is its physical
cause? What should the card's fuel line and ADR 0063's turn fuel then do, and does the winding-roads idea earn a
build? T291 prices each finished route a second way, as a speed profile, and compares (no search changed).

## What the two models charge for a turn (in plain words)

- **The table model** (what the search and the card use today, ADR 0062 and 0063). A turn costs seconds: none
  under 30 degrees, at the through exit or at a junction of two roads; else rising with the angle, at 90 degrees 3 s
  on the kerb side (a right turn where traffic keeps right) and 7 s across traffic (a left turn), plus 1 s per step
  down in road class. Its fuel is the idle over those seconds **plus the energy to speed up again from 20 km/h to the
  next road's full speed**, every time. That second part is the "turn fuel": about 0.5 MJ onto a 55 km/h road, the
  same for a left and a right turn and for a 35-degree kink of under 2 s as for a right angle. Left and right differ
  only in the seconds of idle (7 s against 3 s at 90 degrees).
- **The profile** (`routing/SpeedProfile.kt`). A turn charges nothing by itself: it only caps the car's speed at that
  point (by angle: 35 km/h at 45 degrees, at 90 degrees 20 km/h on the kerb side and 15 across traffic, 8 for a
  U-turn). The car brakes before it and accelerates after it at comfortable limits, and the fuel is the energy it
  actually spends getting back up to speed, shared with any bend or turn nearby; a kink taken at 40 km/h costs little,
  and two turns 50 m apart are not each charged a full regain. Across traffic adds only a wait at idle (the table's
  across seconds less its kerb seconds, 4 s at 90 degrees; the coordinator's reading, unsourced; the time is given
  with and without it). Bends of the road itself cap the speed the same way (radius under a lateral limit).

## What was tried

`devtool route --mode drive --prefer fuel --profile --profile-limits low|first|high --breakdown --against time` on
the six public drives of `docs/reference/devtool-commands.md` (the `route` line), on the frozen `montblanc` pair of
wave 34 and the `rainier` packs, 18 runs; the same six under `time`, `fuel` and `quick` without `--profile` before and
after the change (every field equal but the random ids and timings), and the committed fuel-pass fixture. Limits
(`ProfileLimits`): lateral 2.0 m/s2 to 60 km/h falling to 1.5 at 100, acceleration 1.5 at rest falling to 0.8 at
100 km/h, braking 2.0, the turn speeds above; low and high are the ends of each range (lateral 1.5 and 2.5,
acceleration 1.0 and 2.0, braking 1.5 and 3.0, turn speeds times 14.5/20 and 34/20). The constants and their sources
are in the KDoc; the survey is `out/wave34-research/speed-profile-values.md` [no-check: gitignored]. The car starts
and ends at rest. Fuel is the table model's car: idle 6.2 kW over the whole time plus positive traction work
(resistance, kinetic change, grade) over efficiency 0.35; braking and coasting earn nothing back.

## Result

First limits; time in s, fuel in MJ. "Table" is today's model (the plan's time; fuel with turns), "profile" the new
one (time with the across-traffic wait / without; fuel with the class unsteadiness at 1 / kept).

| Drive | Route | Table time | Profile time | Table fuel | Profile fuel |
|---|---|---|---|---|---|
| Paradise to Sunrise | both | 5,173 | 5,366 / 5,351 | 201.2 | 197.3 / 202.8 |
| Paradise to Longmire | both | 1,225 | 1,343 / 1,339 | 19.5 | 20.7 / 21.0 |
| Longmire to Box Canyon | both | 1,851 | 1,977 / 1,977 | 69.1 | 73.2 / 74.7 |
| Les Houches to Entreves | both | 961 | 1,053 / 1,052 | 53.4 | 56.4 / 56.6 |
| Les Houches to Chamonix | time | 461 | 491 / 481 | 23.7 | 18.2 / 18.5 |
| | fuel | 481 | 498 / 492 | 15.9 | 14.7 / 15.3 |
| Chamonix to Les Bossons | time | 258 | 277 / 266 | 15.3 | 10.3 / 10.5 |
| | fuel | 273 | 278 / 267 | 8.1 | 7.4 / 7.7 |

Least fuel's saving against least time, percent (four drives: the same route, 0 by every model):

| Drive | Table | Table, edges only | Profile low / first / high | Profile, class kept, low / first / high |
|---|---|---|---|---|
| Les Houches to Chamonix | 32.7 | 11.2 | 17.2 / 19.3 / 21.3 | 15.2 / 17.2 / 19.1 |
| Chamonix to Les Bossons | 47.1 | 14.7 | 25.3 / 27.7 / 28.2 | 23.8 / 26.4 / 27.0 |

The time least fuel adds: the table 4.4 % and 5.8 %; the profile -0.5 to 2.8 % and -2.7 to 2.7 % (low to high),
so by the profile the expressway route is hardly quicker. T290's shares on the least-time routes: turns 34.5 % and
48.0 % of the table's fuel (7.4 % and 14.4 % of its time), every paid turn (17 and 15) at a junction of two unnamed
roads; on the four other drives every paid turn is unnamed on `montblanc` and named on `rainier`.

**What holds the profile down on the two town drives** (first limits; runs of points at their limit, counted, and
the time each cause adds; a turn's own corner drawn in the line counts as a bend, since bends are added first):

| Route | Bends | Turns | Edge speed | Starts, stops, speed changes | Bends add | Turns add (wait in) |
|---|---|---|---|---|---|---|
| Les Houches to Chamonix, time | 25 | 7 | 11 | 9.2 s | 44.3 s, 2.93 MJ | 10.8 s, 0.22 MJ |
| Les Houches to Chamonix, fuel | 18 | 4 | 12 | 7.4 s | 24.5 s, 1.95 MJ | 6.8 s, 0.12 MJ |
| Chamonix to Les Bossons, time | 16 | 6 | 9 | 8.9 s | 35.0 s, 2.24 MJ | 11.9 s, 0.24 MJ |
| Chamonix to Les Bossons, fuel | 12 | 5 | 8 | 6.7 s | 19.3 s, 1.03 MJ | 12.0 s, 0.14 MJ |

**Reading.** The table model's turn fuel on the least-time routes is 8.17 MJ and 7.33 MJ; the profile spends 3.15 MJ
and 2.48 MJ on every bend and turn together. The 33 and 47 % are therefore mostly a model error, as T290 suspected:
the full regain charged at each of 15 to 17 closely spaced paid turns, many of them gentle kinks between unnamed
roads of one class. Charging the regain from an angle-based turn speed instead of 20 km/h (the profile's own turn
speeds, applied per turn) only brings them to 27 and 39 %: the overcount is the per-turn regain itself, not its start
speed. What remains, 15 to 28 %, has a physical cause the survey's averages do not cover: the least-time routes are
11 % and 21 % longer (7,648 m against 6,860 m; 4,013 m against 3,323 m) and run 3.1 km and 1.8 km on an 85 km/h
expressway, whose speed-up and ramps cost kinetic fuel on a 4 to 8 km trip. The class factor kept takes 1 to 2
points off; the limits' range moves the saving by 3 to 4 points. These are short detours, not the long drives
the published 3 to 10 % averages describe, so a figure in the high teens here is believable, 33 and 47 are not.

**The grade.** The task read the grade as each edge row's rise netted over its length. On the committed fuel-pass
fixture the pass road is one row of 400 m up and 400 m down, which netting reads as level: the profile then gave
least fuel 53 % *more* fuel than least time. The built profile spreads each row's climb and descent over its length,
each floored on its own (the table model's own reading of a row), and gives 5.4 % less, beside the table's 8.8 %;
`fuelNetGradeJ` keeps the netted figure (on the two town drives netting moves the saving by 1 to 4 points). On the
four same-route drives the profile's fuel is within about 6 % of the table's, so the descent and class terms agree.

**Winding roads.** The profile adds 3.7 % (Paradise to Sunrise), 9.6 % (Paradise to Longmire), 6.8 % (Longmire to
Box Canyon) and 9.6 % (Les Houches to Entreves) to the table's time at the first limits (2 to 18 % across the range),
nearly all of it bends (192 s of 193 on Paradise to Sunrise). Three public drives move by more than the
winding-roads idea's 5 %, so its Plan's measurement is met; at its own lateral 2.5 m/s2 (the high set) two still do
(Paradise to Longmire 5.4 %, Les Houches to Entreves 6.7 %).
Whether the times are closer to real driving times was not checked (no published times were read).

**Through overlay cells** (Paradise to Sunrise, `--overlay-min-km 3`) a shortcut row is driven at its mean speed
with its inner turns unseen: 5,359 s against 5,366 s, fuel 1 % lower (a shortcut's climb and descent are one row).

## Next

- The coordinator: ADR 0063 Decision 4 (a turn's fuel) amended, as the wave's gate says. This finding's reading:
  drop the per-turn regain from 20 km/h; an angle-based start speed is not enough. Two candidates for the search's
  turn fuel, to be chosen by matching the profile's route choice and saving on these drives and the fixture: idle
  over the seconds only (the edges-only saving, 11 and 15 %, under the profile's) or a regain with a cap per
  distance driven. Either way the card's figure should come from the profile of the two finished routes (both are
  planned already for the line), so the card's fuel line would read about "19 % less fuel than the least-time route,
  about as long" on Les Houches to Chamonix instead of "33 %, 1 min longer"; the phone and the search are out of
  this pass.
- The winding-roads idea: its measurement is met (three drives over 5 %); its build waits on the coordinator.
- Not checked: real driving times on the six drives; stops and signals (out of pass one); left against right turn
  speeds as published numbers (not established; the survey's own table).
