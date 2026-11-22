---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# Firefighting and search-and-rescue aircraft picked out by type or operator

What: mark air tankers, scoopers, helicopters on a rescue or fire task and medical flights with their own symbol and a
card line ("Air tanker, fire suppression"), with an optional toggle to show only those. For a hiker near a fire or a
rescue the question is "is that aircraft for this".

Why it suits Dystopo: an outdoor map is read in exactly the places where these aircraft work. The feed already carries
type (`t`), description (`desc`) and a flags field (`dbFlags`) from the readsb database, so a first version needs no
new call.

Data: the adsb.lol feed fields (ODbL, per the finding). Operator is the weaker half: the feed gives little beyond those
fields, and the finding left the FAA registry (public domain, US only) as an optional operator source. Many
public-safety aircraft turn transponders off or are filtered from public feeds, so the layer can never show them all.

## Open
- A list of type codes to treat as firefighting and its upkeep.
- Wording so an absent symbol is not read as an absent aircraft (a safety matter).
- Whether the FAA registry join is worth a shipped file.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.

2026-11-21, the owner, by multiple choice, asked whether to mark firefighting, rescue and medical aircraft by type
code, knowing many public-safety aircraft are filtered from public feeds, so an absent mark can be read as an absent
aircraft (the wording around absence being a safety rule, the owner's to confirm): the small first version
(recommended): a card line for air tankers and scoopers from a short type list, no show-only filter, help saying the
list is partial. Helicopters and medical flights stay unmarked until an operator source (the FAA registry) is weighed.

Plan: one small pass, `shared` and the card. A short list of ICAO type designators that are firefighting aircraft by
design (the scoopers and the purpose-built tankers; a converted airliner or a crop duster shares its designator with
ordinary flights and stays out), each checked against the published designator list and named in the report with
where it was checked (ADR 0044; the project's own list, nothing copied), in `shared/.../aircraft/` beside
`AircraftKind`. `AircraftText.card` gains one line under the type line ("Firefighting aircraft, by its type") for a
report whose `type` is listed; the Flights help gains a sentence that the mark comes from the type alone, that the
list is partial and that many public-safety aircraft never appear in the feed. `GET /aircraft` reports
`firefighting` per aircraft. Verify: `AircraftTest` with a fixture of each listed type and of a look-alike that must
not match; a replay (`POST /aircraft/replay`) carrying one, its card viewed on the emulator. The line and the help
sentence in the report for the owner to confirm (ADR 0031; the wording about absence is the owner's). Out of scope:
a symbol of its own on the map, a filter, helicopters and medical flights, any operator or registry source.
