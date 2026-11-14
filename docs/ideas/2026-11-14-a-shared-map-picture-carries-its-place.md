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

Waits: the coordinator, 2026-11-14, by the owner's leave ("The other decisions I leave to you, or get an
independent review to confirm"; a critic's change to the recommendation above). Trigger: an idea or a task that
shares a picture of the map; the app shares only GPX today, so there is nothing to attach a place to. For that
day: a survey first (ADR 0039; the Android facts above are from memory); "off by default, asked at the share"
stands as the provisional default; and the viewed place is not always the lesser fact, since while the map
follows the position its centre is the position, and a tag is read by a machine where pixels are read by a person.
