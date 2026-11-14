---
date: 2026-11-14
kind: feature
related: [2026-11-14-sweep-frames-carry-the-place-they-show.md]
---

# A picture of the map that the app shares could carry its place

From the owner's answer to the sweep idea, 2026-11-14: "Does the camera app or phone global have a setting for
geotagging? But it should be available. Unsure if you tag current location or viewed location though." The app
shares files today as GPX (`androidApp/.../app/SaveFile.kt`); it shares no picture of the map. If it comes to (a
view, a trip's card, what can be seen from a point), the picture could carry a place in its metadata, as a photo
does.

What Android offers (the coordinator, from memory; a survey under ADR 0039 before building): no phone-wide switch
for geotagging. Each camera app has its own setting for location tags, and the system's part is that app's
location permission; since Android 10 an app also needs a separate permission to read the place out of other apps'
photos. So the switch would be the app's own.

Recommendation (the coordinator): the viewed place, not the position. The picture shows the map's centre, which a
viewer can read off it anyway, so the tag adds little the pixels do not say; the position is where the user
stands, the more private fact and not what the picture shows. Off by default and asked at the share ("Include the
place"), never a setting that tags silently.

Question: do you want map pictures to be shareable with a place attached at all, and if so the viewed place
(recommended: off by default, a switch at the share) or the position?
Answer:
