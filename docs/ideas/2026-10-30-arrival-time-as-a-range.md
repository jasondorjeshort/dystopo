---
date: 2026-10-30
kind: feature
---

# Arrival and turn-back times as a range, and "you did this trail in"

T164's `shared/.../routing/PaceFit.kt` fits a pace to the user's recorded tracks and has the scatter
of the fit, and the track store holds every past recording. Show the route card's time as a range
("4:10 to 4:50") from the fit's spread rather than one number, let T163's clock use the pessimistic
end for the turn-back, and when a planned route overlaps a recorded track, say so with the recorded
time ("you did this trail in 3:52 in June"). Open: how wide a range reads as useful rather than
vague (the benchmark pass, ADR 0039); matching a route to a past track needs the overlap measure
`NavPath` already has or the trail-corrections matcher.

2026-10-31 (T184, implementer's note, placed above the Question so the queue still lists it as open): the first pass is built: the route card's "about 4:30" over "4:10 to 4:50" from a whole-track statistic, "you did this trail in", and the setting Route times: Show as (Range or One number). The turn-back clock is unchanged; the Question below still waits for your answer.

Question: should the turn-around clock (`nav/TurnAround.kt`) take the slow end of the range, giving an earlier turn-back than
today? That is a safety behaviour you sign off. Recommendation: split the work. First pass: the route card's range and the
"you did this trail in" line, with the clock unchanged; its margin (`turnMarginMin`, default 30) already plays the pessimistic part.
Two findings from the code shape that pass. `PaceFit.spread` is the scatter of short pieces about the curve, which averages
out over a route's many pieces, so the range needs a whole-track statistic: each recorded track's real time against the fit's
prediction, measured with `devtool pace-fit` before the survey (ADR 0039) sets the width. And `NavPath` has projection, not an
overlap measure; the match goes through T164's `routing/TrackMatch.kt`. Second pass, on your answer: the clock reads the
slow end, and the margin's default drops to match.
Answer:
