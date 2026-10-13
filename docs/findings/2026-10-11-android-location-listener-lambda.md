---
date: 2026-10-11
status: resolved
area: android / location on API 26 to 29
promoted_to: .claude/rules/kotlin.md, androidApp LocationListenerTest
---

# A `LocationListener` lambda crashes Android 8 to 10 whenever GPS starts or stops

## Question

T81's device matrix ran the release build on an API 26 image for the first time. Why did the app
crash when location was switched on or off?

## What was tried

The release build on API 26 (`android-emu.ps1 -Api 26`, which also needs `-Feature
GLESDynamicVersion`), location toggled with the map open.

## Result

`AbstractMethodError`: the three location listeners were Kotlin lambdas, which implement only
`onLocationChanged`; `LocationListener`'s other three methods gained default implementations only in
API 30, and minSdk is 26, so every published build until then crashed there. Fixed in T81: a
`tracking.locationListener {}` helper implements all four methods, `LocationListenerTest` scans the
sources and fails on a lambda anywhere in the app, and the API 26 matrix row guards it. Log entry:
`docs/PLANNING.md`, T81 (2026-10-11).
