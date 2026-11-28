---
date: 2026-11-28
kind: feature
related: [2026-11-24-sort-the-list-view.md]
---

# Type or paste a coordinate into search

Source: coordinator (claude-fable-5-2), a wave close; offered and accepted by the owner; not sanity-checked.

Wave 49 gave the app a Coordinate format setting (decimal degrees, degrees and minutes, degrees minutes seconds,
UTM), and the glance view, the long-press card and the waypoint panel write positions in it. Nothing reads one:
`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/geo/CoordinateFormat.kt` only formats, and
`androidApp/.../app/search/PlaceSearch.kt` looks a typed text up among place names alone.

The idea: the search box recognises a position in any of the four formats, whatever the setting says, and offers
it as the first result ("Position", with the text as the app would write it); a tap moves the map there and puts up
the long-press card, so Route to here and Waypoint here follow. A position from a guidebook, a message or a radio
call can then be found without panning.

Lever: the missing half of a feature just shipped; the parser is pure Kotlin in `shared` beside the formatter, so
a test can round-trip every format.

## Open

- How lenient the reader is: a comma as the decimal mark, missing hemisphere letters, a UTM text with spaces or
  labels (the privacy scanner's follow-ups of T388 list the same shapes).
- Whether pasting a position anywhere else (a share into the app, a `geo:` link) uses the same reader.
