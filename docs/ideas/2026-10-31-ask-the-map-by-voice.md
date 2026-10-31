---
date: 2026-10-31
kind: feature
related: [2026-10-13-spoken-directions-and-detail-levels.md]
---

# Ask the map a question by voice, hands in the straps

Everything a hiker wants mid-stride is now a number the app has: how far to water, when to turn back, how high am I,
how far to the top, what is that peak, is the pressure falling. Let the user ask with the phone in the pocket: a wake
word is out, but a headset button or a long press on the position button can start Android's own speech recogniser,
which works offline on recent phones with no dependency, and the app already speaks through NavSpeech. A fixed grammar
of a dozen questions answered by the readout, the clock, the water list and the viewshed; anything else gets "I can
answer distance, time, height, water, turn-back and peaks". No audio leaves the phone. Open: the recogniser's offline
model availability per phone (a Settings row that says whether it is there), the microphone permission (a new
permission, so the owner's word first), the trigger with the screen off.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Question: this adds the microphone permission (`RECORD_AUDIO`), which the app does not ask for today; do you want voice
questions at all, and if so with which trigger? Recommendation: yes, as one task once you say so, push to talk only
(a long press on the position button or a headset media button while navigating or recording; no wake word, nothing
listening in the background, so no new background data flow). Android's own `SpeechRecognizer` with
`EXTRA_PREFER_OFFLINE` needs no dependency; where the offline model is missing the Settings row says so and the
feature stays off rather than sending audio to the system's online recogniser (a privacy rule, so no setting under
ADR 0044). The answers come from what is already computed: the readout (`readout/Readout.kt`), the turn clock
(`nav/TurnAround.kt` `TurnClock.spoken`), the water list (`WaterText.spoken` in `app/routing/RouteWater.kt`), the
peaks in view (`dem/Viewshed.kt`) and the pressure trend, spoken through `app/nav/NavSpeech.kt`; the grammar is a
`shared` matcher over the recogniser's text with unit tests and a debug route that feeds it text, so the device tour
needs no audio. The screen-off trigger (the headset button through a media session) is the part to measure on a phone
first; the survey (ADR 0039) is how the established navigation apps start a voice query and what they answer offline.

Answer:
