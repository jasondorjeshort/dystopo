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
