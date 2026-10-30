# 0051. The phone-to-phone transport between signed copies

Date: 2026-10-30
Status: draft

## Question

The owner answered two ideas on 2026-10-29: group positions "yes, but only between copies of the signed app",
and trail conditions "yes, between copies of the signed app only; we need a way to mark conditions too; the
same applies to road conditions, possibly automated someday. Is peer to peer across the internet with only a
minimal central server possible?" (`docs/ideas/2026-10-29-group-positions-without-signal.md`,
`2026-10-29-trail-conditions-phone-to-phone.md`). Two later ideas ride the same machinery: breadcrumbs
carried by passing phones (`2026-10-30-mesh-breadcrumbs-for-search-and-rescue.md`) and pressure readings
shared within a group (`2026-10-30-phones-as-a-barometer-network.md`), both with open Questions. This
proposal decides five things together, because each constrains the others: (1) how a phone proves to
another phone, or to a server, that it runs the signed release app; (2) which transports carry the
messages; (3) the payloads and what each one reveals to whoever carries it; (4) the server's minimum role
and what it never sees; (5) what listening costs in battery. It also answers the owner's internet question.
In ten years the answer should let a group, a passing stranger and a server move small signed and sealed
records over any path (a shared network, radio between phones, the internet), with one record format, one
identity per install and no account, and with iOS able to join through the same open formats.

Nothing here is built by this task. Live positions, relayed notes and breadcrumbs are new data flows: they
are flagged for the owner (CLAUDE.md › Decisions, ADR 0031 Decision 3, ADR 0033 §4), and the stage 1 tasks
below are buildable only after the owner says go on the specific flows listed under the recommendation.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing
  (`docs/PLANNING.md` › Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL); offline
  first; every feature agent-drivable (devtool command, debug route); agents write all code; the design
  goals (ADR 0025).
- The app is open source and ships as APKs from GitHub releases (not from Play today; Play is an open owner
  decision): any secret inside the APK can be copied out, so "signed app only" cannot rest on a shared secret.
- The release signing key never changes (ADR 0024); its SHA-256 fingerprint starts `47313810`
  (`update/Signing.kt` already reads the running app's signers).
- No Wi-Fi gates (ADR 0023): nothing is limited to Wi-Fi; LAN discovery is the one exception ADR 0023 names.
- The app rests in the background (T150, `docs/reference/android-app.md` › The app in the background):
  anything that listens after `onStop` needs a foreground service with a visible notification.
- The existing machinery (T54, T72, T78, T90, T96): the sealed LAN link (`sync/crypto/SealedLink.kt`,
  `share/ShareGate.kt`, ChaCha20-Poly1305 under HKDF of an 80-bit share code, a fresh salt per frame), NSD
  discovery while the Share screen is open (`share/ShareNsd.kt`), and the server relay
  (`serverlib/.../RelayRoutes.kt`: one ciphertext blob per side under a slot derived from the code, in
  memory, 15 minutes after the last write, at most 4 sides a slot, 120 requests a minute per address, open
  without a token). Threat model: `docs/PLANNING.md` › Sync, Threat model.
- No other product's name in user-visible text; safety wording says plainly that this is not an emergency
  beacon.
- ADR 0044: a behaviour users split on becomes a setting with the researched default, except safety and
  privacy rules.

## Prior art

From four read-only research passes on 2026-10-30 (technical variant, ADR 0044, for transport and
attestation; a short app survey for group positions and condition reports). Several items could not be
established; they are listed under Claims to verify rather than guessed.

- **Group positions in outdoor apps go through a vendor server or a satellite service.** Garmin's MapShare
  sends inReach positions over the Iridium network to a Garmin web page, switched on per trip on the device
  (https://www.garmin.com/en-US/blog/?p=31761, https://trackleaders.com/mapshare-howto.php). No live group
  sharing was found in Gaia GPS (third-party forum: https://backcountrypost.com/threads/gaia-gps-sharing-tracks-and-waypoints.9556).
  The one off-grid peer-to-peer example is a LoRa mesh with pocket radios, whose default position broadcast is
  reported as every 15 minutes (https://azmsh.net/recommended_configuration_settings.html); its firmware and
  protocol definitions are GPL (third-party README:
  https://components.espressif.com/components/espp/meshtastic/versions/1.3.7/readme), so no code or generated
  file of it may enter the app.
- **Condition reports expire and are confirmed by the crowd.** A road-hazard report in the best-known
  navigation app lasts about an hour by default, "still there" extends it and "not there" removes it
  (https://support.google.com/waze/answer/13739290, forum detail:
  https://www.waze.com/discuss/t/newbie-how-long-do-reported-hazards-remain-on-map/64592). A trail-report
  service lets the author set an expiry after which the trail reverts to the previous report
  (https://trailforks.com/about/features/trail_reporting). A hiking association judges freshness by the
  report's date (https://www.wta.org/news/magazine/features/the-wisdom-of-crowds).
- **Store-and-forward by strangers, end to end encrypted, is a published and analysed design.** Apple's
  offline finding has passing phones upload encrypted location reports keyed by rotating identifiers; the
  analysis found the design meets its privacy goals with two flaws, and that finders and owners reveal their
  identity to the server when uploading and downloading (Heinrich et al., PETS 2021:
  https://petsymposium.org/popets/2021/popets-2021-0045.php). The server there is a dumb mailbox, the same
  shape as the project's relay.
- **Rotating Bluetooth identifiers are the norm for privacy.** Exposure Notifications changed the broadcast
  identifier about every 15 minutes together with the Bluetooth address
  (https://www.blog.google/documents/62/Exposure_Notification_-_Bluetooth_Specification_v1.2.pdf), scanning
  a few seconds about every 5 minutes and advertising every 200 to 270 ms (https://arxiv.org/pdf/2006.08543).
  T78 already designed the same for this app: a 16-byte token, an HMAC of the share code and a 10-minute slot
  (`docs/PLANNING.md` › Peer sources on a trip).
- **Device attestation between peers exists in open source.** GrapheneOS's Auditor (MIT) pairs two phones
  by QR code and verifies the other's hardware key attestation locally, pinning it on first use
  (https://github.com/GrapheneOS/Auditor, https://attestation.app/about). Google's recommended verifier is
  `android/keyattestation` (Kotlin, Apache-2.0, https://github.com/android/keyattestation); its predecessor
  is archived as sample code (https://github.com/google/android-key-attestation). Messaging apps that care
  about security (Signal) do not attest clients at all: they authenticate people, not builds.
- **NAT traversal is never relied on alone.** Tailscale punches through "over 90% of the time" for its
  users but keeps a relay (DERP) as "our fallback of last resort"; one hard NAT on the path breaks simple
  punching (https://tailscale.com/blog/how-nat-traversal-works). libp2p's measurement over 4.4 million
  attempts in the public IPFS network found about 70% success, 82.9% behind port-restricted cone NATs and
  39.7% behind symmetric ones, on computers and servers rather than phones (https://arxiv.org/html/2510.27500v1,
  https://probelab.io/blog/can-libp2p-punch-through-nats/). No cellular-specific rate was found.

## Part 1: proving the peer is the signed app

The question is narrower than it sounds. A peer that runs the unmodified release app on a phone with a
locked, verified system can still lie about its position (a mock location) and a person can still write a
false note; attestation keeps out scripts, modified builds and bulk spam from machines that are not phones,
and lets a phone hold a stranger's note to the same standard as its own. For a group that already shares an
80-bit code it is a second lock on a door that is already locked; for notes between strangers it is the
main lock.

### A1. Nothing beyond today's share code

The share code is the group: whoever has it is in. What it meets: everything except the owner's condition.
Cost: none. Risk: a copied code lets any client in, modified or not; for public notes there is no gate at
all. Exit cost: none. Moving parts: none.

### A2. Play Integrity with a server check

The app asks Google Play for a signed verdict about the app and the device; the verdict is decrypted and
checked on Google's servers through the developer's backend (`decodeIntegrityToken`), 10,000 requests a day
by default (https://developer.android.com/google/play/integrity/standard,
https://developer.android.com/google/play/integrity/overview). A sideloaded copy gets `UNRECOGNIZED_VERSION`
or `UNLICENSED` (https://developer.android.com/google/play/integrity/verdicts): the verdicts assume Play
distribution, which the project does not have. It needs a Google Cloud project, Google Play services on the
phone, a project server holding Google credentials for every check, and a network at the moment of the check;
a second phone in the backcountry cannot verify a token at all. The SDK is a Play services library under
Google's terms (https://developer.android.com/google/play/integrity/terms), not a permissive open-source
licence; F-Droid rejects apps that require Play services (https://f-droid.org/docs/Inclusion_Policy/).
SafetyNet, its predecessor, was shut down in January 2025
(https://developer.android.com/training/safetynet/attestation). Fails offline first, the licence rule and
the distribution the project has. Exit cost: the server and the Google account. Moving parts: Google's
service, a credentialed backend, a quota.

### A3. The signing certificate exchanged and pinned

Each phone tells the other its signer's fingerprint (it can read its own, `update/Signing.kt`), and peers
accept only the release fingerprint. This is a claim: any program can send the 32 bytes. Useful as a cheap
version and build check in the hello message, worthless as proof. Cost: tiny. Exit cost: none.

### A4. A key pair per install with a web of trust between contacts

Each install makes a key pair; people exchange public keys when they meet (the share screen's QR code) and
vouch for each other. This authenticates *people the user chose*, which is exactly right for a group and for
breadcrumbs sealed to contacts, but it does not prove that a key lives in the signed app, and a web of trust
between strangers does not scale to trail notes (the PGP experience). Cost: a signature scheme and a key
store. Exit cost: low (keys are per install). Moving parts: key exchange in the pairing flow.

### A5. A hardware-attested key per install (Android key attestation)

The app generates its signing key in the phone's secure hardware (Android Keystore, EC P-256, StrongBox where
present) with an attestation challenge. The key never leaves the hardware. The hardware returns a certificate
chain to a Google root whose first key-description extension names the package, the signing certificates'
SHA-256 digests, the security level (software, TEE or StrongBox), the verified-boot state and whether the
bootloader is locked; only the first occurrence of the extension in the chain may be trusted, and the level
must be TEE or StrongBox (https://developer.android.com/privacy-and-security/security-key-attestation; schema
https://source.android.com/docs/security/features/keystore/attestation#schema). Any phone or server can
verify the chain **offline** against bundled roots: the legacy RSA root (valid to 2042) and the ECDSA P-384
"Key Attestation CA1" root that signs chains since 2026-02-01; the trusted list is published as JSON at
`https://android.googleapis.com/attestation/root`. Revocation is a JSON list of serials at
`https://android.googleapis.com/attestation/status`, refreshed per its `Cache-Control`, which an offline phone
can only hold as a cached copy (same page). Remote Key Provisioning (optional on Android 15, mandatory for
devices launching with Android 16) replaces factory keys with short-lived certificates, so leaked factory
keys stop mattering on new phones (same page).

What it meets: offline (a second phone verifies with no network), no Google service at verification time, no
Play distribution, permissive (the parser is our code or Apache-2.0, the platform does the crypto). What an
unsigned build can do: generate a chain, but it names the debug or another signer, so peers refuse it. What it
cannot stop: a forged chain made with a leaked factory attestation key (community tools do this; Google
revokes leaked keys once found, and RKP phones are not exposed:
https://github.com/android/keyattestation, https://github.com/MobInfinity/TrickyStore); phones without
hardware attestation (attestation is required from Android 8.0, our minSdk 26; older or uncertified devices
fail and are refused). Emulators produce a software-level chain and fail the release policy, so debug builds
need a policy release builds do not contain. A custom OS with its own boot key (GrapheneOS) reports
`SELF_SIGNED`: the package and signer fields are filled in by the OS, not the hardware, so accepting it means
trusting that OS's key, which a policy can do by allowlisting published boot keys (an owner choice, below).
Privacy: the chain carries the OS patch level and the app's package and version, no device serial or IMEI
(those need device-ID attestation, which an ordinary app cannot request); factory attestation keys are shared
by batches of devices (claim to verify).

Cost: a DER parser for one extension (about 300 lines), a chain check with `java.security.cert` in
`jvmCommonMain`, a bundled root list, a revocation fetch, a rotation rule (RKP certificates are short-lived,
so the key and its chain are renewed every 30 days, which also rotates the pseudonym). Exit cost: low; the
key and chain are per install and per month. Moving parts: the root list (updated when Google rotates), the
revocation list cache, a policy per build type. Who else: Auditor between phones, banking and enterprise apps
toward servers.

**A dependency or our own parser.** `android/keyattestation` is the reference, but its build pulls Guava,
protobuf-lite, Gson, a CBOR library and Bouncy Castle and targets JDK 21 (its `build.gradle.kts`), too much for
the app for one extension. The proposal is a hand-written parser in `shared` with the library as a test-only
oracle on the JVM. The dependency checklist (`.claude/skills/proposal/dependency.md`) for the parser, as
reimplementing a format: (1) it reads one ASN.1 extension; the library cannot be copied in under a page; (2)
our code; the oracle is Apache-2.0, test scope only; (3) the oracle is active (CI, 112 commits, no tagged
release); (4) Google's Android team; (5) none in the app, the oracle's closure in tests only; (6) yes, on the
JVM; (7) untrusted input from strangers: bounded lengths, one exception type, fuzzed in tests; (8) the
coordinator, through the root list's origin line and the release notes of the oracle; (9) swap in the library
on the server side if the parser falls behind; (10) a refused peer is counted with its reason on a debug
route, so a parser bug shows as refusals, never as silent acceptance.

## Part 2: transports

| | Range and needs | Background | Payload | Availability | Licence, parts | iOS later |
|---|---|---|---|---|---|---|
| T1 LAN link (today) | one Wi-Fi network: a hut, a camp, one phone's hotspot | listener needs a foreground service | any size, sealed | every phone | ours, exists | open protocol |
| T2a Wi-Fi Aware | tens of metres, no access point | not documented; sessions "stay synchronised to the cluster and cost battery" | any size after a data path | only phones with `FEATURE_WIFI_AWARE`; share not established; off while a hotspot runs on some | platform API, API 26, `NEARBY_WIFI_DEVICES` from 33 | iOS 26 added Wi-Fi Aware; interop with Android rough in 2025 |
| T2b Wi-Fi Direct | tens of metres | user prompts, one group owner | any size | most phones | platform API | no |
| T3 BLE advertising and GATT | tens of metres outdoors (not established) | scans keep running with the process alive; low-power mode enforced in the background | 31 bytes legacy, up to 1650 extended; GATT for more | almost every phone | platform API; `BLUETOOTH_SCAN` (`neverForLocation`), `ADVERTISE`, `CONNECT` from API 31 | background advertising restricted (overflow area) |
| T4 internet, direct (hole punching) | anywhere with signal, both online at once | a socket needs a foreground service | any | fails behind symmetric and carrier NATs; a relay is needed anyway | ice4j Apache-2.0, jvm-libp2p (licence to verify), libnice LGPL (excluded) | yes |
| T5 relay mailbox (today's relay, extended) | anywhere with signal, not at the same moment | one request per interval | small sealed or signed blobs | every phone | ours, exists | yes |

Sources: Wi-Fi Aware https://developer.android.com/develop/connectivity/wifi/wifi-aware and iOS
https://developer.apple.com/documentation/wifiaware, https://developer.apple.com/forums/thread/790195; BLE
permissions https://developer.android.com/develop/connectivity/bluetooth/bt-permissions, background
https://developer.android.com/develop/connectivity/bluetooth/ble/background, scan modes
https://developer.android.com/reference/android/bluetooth/le/ScanSettings, payload limits
(`MAX_LEGACY_ADVERTISING_DATA_BYTES` 31, `MAX_ADVERTISING_DATA_BYTES` 1650 in AOSP
https://code.yawk.at/android/android-9.0.0_r35/android/bluetooth/le/BluetoothLeAdvertiser.java), the 5 scan
starts per 30 s limit since Android 7 (https://punchthrough.com/android-ble-scan-errors/), iOS background
advertising
https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html;
NAT figures above. Google's Nearby Connections would do T2 and T3 in one API but is part of Google Play
services (https://developers.google.com/nearby/connections), so the licence rule excludes it; the open
alternatives found are GPL (Briar, https://briarproject.org/copyright).

- **T1** costs nothing new and works today wherever phones share a network; its weakness for positions is
  plain: a group on one network is a group within shouting distance. It is the right test bed and the right
  path at a hut, a trailhead or a car park, and the right place to exchange notes between strangers' phones.
- **T2a** is the only radio path with real bandwidth and no access point, but it is missing on many phones,
  undocumented in the background, and costly while a session is open; worth a spike when bulk data (packs)
  must move between phones, not for positions.
- **T2b** prompts the user and elects a group owner; rejected for anything automatic.
- **T3** is the only path that works between strangers walking past each other with no signal, and the only
  one that relays along a strung-out line of hikers. A position (key id 8, time 4, lat and lon 8, accuracy 2,
  sequence 2) fits a legacy advertisement only without a signature; a signed record needs extended
  advertising or a GATT read. T78's rotating token design carries over. It needs real phones to test (the
  emulator's Bluetooth support for two instances is a claim to verify).
- **T4** answers "direct" literally but buys little here: both phones must be online at the same moment, a
  phone in the background holds no socket without a foreground service, a cellular pair falls back to a relay
  often (no cellular figure; 39.7% success behind symmetric NATs on desktops), and the payloads are hundreds of
  bytes. The rendezvous server it needs holds addresses and timing, the same metadata the relay holds.
- **T5** exists, already carries sealed frames under a slot the server cannot reverse, and posting a blob
  returns the other sides' blobs in the same request, so one request per interval is both send and receive.
  For positions it needs a separate slot derivation (so a group's positions never share a slot with a sync
  under the same code) and more than 4 sides a slot. For notes it needs a second, longer-lived kind of mailbox
  (Part 4).

A pocket LoRa radio (the group idea's third transport) stays a later proposal of its own: the popular
firmware is GPL, so only a radio with a permissively documented protocol could be supported.

## Part 3: payloads and what each reveals

| Payload | Size | Audience | Protection | A carrier (relay, LAN eavesdropper, BLE relay phone) sees |
|---|---|---|---|---|
| Position (group) | about 40 bytes plus a 64-byte signature, every 1 to 15 min | the group (share code) | signed by the attested install key, sealed under the code (new frame purpose) | a slot derived from the code, a random side id, sizes, timing, addresses; over BLE a token that rotates every 10 min |
| Pressure (optional fields of a position) | 8 bytes: hPa, GNSS height, sigma | the group | inside the sealed position | nothing more than the position's frame |
| Condition note | 200 to 500 bytes, plus the author's chain once per author (2 to 4 KB) | everyone with the app | signed, public by design | the whole note: place, time, type, text, the author's monthly key id; the chain's OS patch level |
| Note confirmation ("still so", "not any more") | about 80 bytes | everyone | signed | the confirming key id, place by reference, time |
| Breadcrumb (later) | the last few fixes, about 200 bytes | the user's contacts | signed, sealed to each contact's public key (ECDH) | an opaque blob, a recipient tag that rotates (derived from a secret shared with the contact), an expiry, size |

Rules per flow (privacy, not settings, ADR 0044): positions are off by default, switched on per trip on the
Share screen, shown by a notification with a Stop action for as long as they run, and end on their own after
12 hours unless renewed; a note says when written that it reveals where and when its author was, and its key
id rotates monthly; breadcrumbs are opt-in per trip and sealed to contacts only, never readable by a carrier;
no flow ever goes to a server the user has not configured.

## Part 4: the server's minimum role

- **Positions and breadcrumbs:** the server stores ciphertext under slots it cannot reverse and hands it
  back, exactly the relay of T72. It never sees the code, a position, a breadcrumb, a device id or who is who;
  it sees client addresses, sizes, timing and that some addresses share a slot (PLANNING › Threat model, which
  applies unchanged). Positions need no server change beyond the side cap; breadcrumbs need a longer lifetime
  (hours to days) and therefore disk, a later change.
- **Notes:** a mailbox per map tile (z8, about 150 km across, so a query reveals only a region) that accepts a
  signed note, checks its size, its signature, its author's attestation chain (the server verifies like a
  phone, with a fresh revocation list) and a rate per key and per address, stores it until it expires, and
  answers "notes for this tile since cursor". It merges nothing, keeps no account, and sees only what is public
  by design plus addresses. Several servers can hold the same mailboxes; a phone posts to and reads from the
  servers it is configured with, so no single server is required, and the phone-to-phone exchange at a
  trailhead is the offline tail of the same feed.
- **What no server ever does:** decrypt a position or a breadcrumb, hold a share code, link a key id to a
  person, or require an account.

## Part 5: the battery cost of listening

| Path | Published figure | Comparable? |
|---|---|---|
| BLE scanning | Nexus 5, Android 5.0, 2014: whole phone about 268 mA in low-power mode against 454 mA in low-latency mode (https://developer.radiusnetworks.com/2014/10/28/android-5.0-scanning.html); a model: battery life down about 3% (low power), 5% (balanced), 20% (low latency) (https://arxiv.org/pdf/2005.05625); Exposure Notifications, 4 s scans every 5 min: about 3% in 24 h on an iPhone (the app's developer, reported in the press) | relative only; old phones and a model; AOSP shortened the scan windows in 2022 |
| BLE advertising | not established | |
| Wi-Fi Aware | no phone measurement found; Android says open sessions cost battery and should be closed promptly | |
| LAN socket and NSD | not established; a hotspot host costs more (not established) | |
| Cellular polling | LTE tail power about 1 W for about 11.6 s after each request (Huang et al., MobiSys 2012, https://experts.umn.edu/en/publications/a-close-examination-of-performance-and-power-characteristics-of-4/; numbers from a lecture summary to check against the paper); about 3 mWh a request, so about 180 mWh an hour at one a minute (about 1% an hour of a 17 Wh phone battery) and 12 mWh an hour at one every 15 minutes (inference) | 2012 LTE; 5G tails and coverage search not established |

The figures are too old and too indirect to choose an interval by; what they do settle is the order: one
request a minute over cellular is about the cost of continuous low-power BLE scanning, and a request every
5 to 15 minutes is small. Stage 1 measures on the owner's phone with `tools/android-battery.py` (a `group`
scenario), and the BLE stage measures before it ships.

## Recommendation

**Attestation: A5 for every flow, with A4 on top for people.** Each install holds a hardware-attested key,
renewed monthly; a phone accepts a position, a note or a breadcrumb only from a key whose chain passes the
release policy (package, release signer, TEE or StrongBox, verified boot, locked bootloader, a known root, not
on the cached revocation list). For groups the share code stays the membership secret (A1 kept as the
envelope), and a member's key is pinned at join; for breadcrumbs the contacts are the people whose pinned keys
the user keeps (A4). A2 is rejected (offline, licence, distribution), A3 is kept as a cheap version check in
the hello message only.

**Transport: T1 and T5 first, T3 later, T2a as a spike for bulk, T4 not built.** Stage 1 carries positions
over today's LAN link and today's relay, both sealed under the share code: nothing new for the server except
a larger side cap. BLE (T3) is the stage that makes the no-signal case work and needs real phones and a
battery measurement first.

**Stages.**
1. Stage 1a: the attested install key and its verifier (no data leaves the phone except the revocation list
   fetch). Stage 1b: group positions over the share code on the LAN link and the relay, with optional pressure
   fields reserved in the record but not filled.
2. Stage 2: condition notes as sync ops with expiry by type, signed by the install key, synced between the
   user's own devices and exchanged with paired phones over today's sync (no new transport; worth having alone;
   the idea's "Report a condition" action and the road types).
3. Stage 3: notes between strangers: the tile mailbox on the server and an open, attested notes exchange over
   the LAN at a trailhead (digest first, then what the other lacks).
4. Stage 4: BLE: rotating tokens, positions relayed along a group, a notes digest between passing phones; a
   battery measurement gates it.
5. Stage 5: breadcrumbs sealed to contacts and carried by passing phones; pressure filled in positions after
   the storm warning has run on real hikes (both ideas' Questions are still open for the owner).

**The owner's internet question.** Yes: phones can exchange positions and notes across the internet with
only a minimal central server, and the project already runs that server (the relay). No, not as direct
connections: hole punching needs both phones online at the same moment, fails often enough behind carrier
NATs that a relay is needed anyway, and saves nothing for messages of a few hundred bytes. The minimal server
stores sealed blobs (positions, breadcrumbs) it cannot read and signed public notes it can check but does not
merge, under no account; any number of such servers can coexist, and phone to phone remains the offline path.

**What the owner decides before stage 1b** (flagged, not guessed): the flow "my position, sealed, to the
configured server's relay, every interval, while a group runs"; whether `SELF_SIGNED` systems with a published
boot key (GrapheneOS) count as signed copies (default: no; an allowlist later); the auto-stop after 12 hours;
the strings in the task report. The interval's default (2 minutes here, a placeholder) gets a benchmark survey
(ADR 0039) in the stage 1b task before it is fixed.

### Stage 1a. The attested install key (task text for the board)

**Goal:** a per-install signing key in the Android Keystore with key attestation, and a verifier for another
phone's chain, as proposal 0051 Part 1 (A5) describes; nothing sent anywhere except a GET of Google's public
revocation list when online. (1) `shared` commonMain: a DER reader and the key-description extension parser
(OID 1.3.6.1.4.1.11129.2.1.17: attestation version, security level, challenge, the application id's packages
and signature digests, root of trust with boot key, locked flag and boot state, OS patch level), first
occurrence only, bounded lengths, one exception type; a policy (`release` and `debug`, the debug policy
reachable only from debug builds) returning accept or the list of reasons. (2) `jvmCommonMain`: the chain
check with `java.security.cert` (signatures, validity, a bundled root), shared by the phone, the devtool and
later the server. (3) The bundled root list from `https://android.googleapis.com/attestation/root` with an
origin line, and the revocation list fetched when online and cached with its age. (4) The phone: the key
generated on first use (StrongBox when present, else TEE), renewed every 30 days, a `sign` call, and debug
routes. (5) `devtool attest --chain <file>` prints the verdict as JSON.
**Owns:** new `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/identity/**`, new
`shared/src/jvmCommonMain/kotlin/io/github/jasondorjeshort/dystopo/identity/**`, new
`shared/src/commonMain/resources/attestation/roots.json`, new `shared/src/commonTest/.../identity/**` and
`shared/src/jvmTest/.../identity/**` (fixtures: a software chain captured from the task's emulator and mutated
copies; the `android/keyattestation` library as a test-only oracle if the licence check accepts its closure in
test scope), new `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/identity/**`
(`InstallKey.kt`, `Revocation.kt`, `IdentityRoutes.kt` with its KDoc), its dispatch line in
`androidApp/.../app/Routes.kt`, new `androidApp/src/test/.../identity/**`, new
`devtool/src/main/kotlin/io/github/jasondorjeshort/dystopo/devtool/AttestCommand.kt` and its dispatch line in
`devtool/.../Main.kt`, the identity lines of `docs/reference/android-debug-routes.md` and
`docs/reference/devtool-commands.md`. [no-check: files this task creates]
**Done when:** `check` green; unit tests refuse, each with its reason: another package, another signer, a
software level under the release policy, an unlocked bootloader, an unverified boot, an unknown root, an
expired certificate, a revoked serial, an extension found only in a later certificate; a fuzzed extension
throws the one exception and never accepts; on the emulator `GET /identity` shows a software-level chain
accepted by the debug policy and refused by the release policy with `securityLevel`, and `devtool attest` on
the exported chain agrees; on the owner's phone (a debug build, owner action) the chain reports TEE or
StrongBox, verified boot and a locked bootloader, refused by the release policy only for the debug signer.

### Stage 1b. Group positions over the share code (after 1a and the owner's go)

**Goal:** proposal 0051's stage 1b: a group is a share code; while "Share my position with this group" is on
(Share screen, per trip, a notification with Stop, an automatic end after 12 hours), the phone sends its
position every interval as a signed record sealed under the code over the LAN link when the other phones are
on the same network and over the relay when it is online, and draws each member as a dot with its age; a
member's attested key is pinned when it first appears; records from an unpinned, refused or older key are
dropped and counted. The record (`DPOS` version 1) reserves the pressure fields unfilled. The relay slot for a
group is derived with its own label, so it never meets a sync under the same code.
**Owns:** new `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/group/**` (`PositionRecord.kt`,
`GroupRoster.kt`) and `shared/src/commonTest/.../group/**` (codec, refusals, ages, a three-member simulation over
a lossy network); `shared/.../sync/EncryptedBundle.kt` (one purpose) and `shared/.../sync/crypto/ShareCode.kt`
(the group slot) with their tests; new `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/group/**`
(`GroupSession.kt`, `GroupService.kt` with foreground-service type `location`, `GroupOverlay.kt`,
`GroupRoutes.kt` with its KDoc) and `androidApp/src/test/.../group/**`; `androidApp/src/main/AndroidManifest.xml`
(the service); `share/ShareGate.kt` (one sealed route), `share/ShareSession.kt` (the listener held while a group
runs), `share/ShareScreen.kt` (the switch and the member list); the settings registry files for the interval key
(the registry rule); `serverlib/.../RelayRoutes.kt` (the side cap's default) and the relay lines of
`docs/SERVER.md`; a `group` scenario in `tools/android-battery.py`; the group lines of
`docs/reference/android-debug-routes.md` and `android-app.md`. [no-check: files this task creates]
**Done when:** `check` green; two emulators with the debug policy see each other's dot within one interval on
the LAN path, and through the relay with the LAN path cut; a third emulator without the code sees nothing and
the relay holds nothing it can open; a refused chain (release policy in a unit test, the debug signer on the
phone) is dropped and counted on `GET /group`; Stop and the 12-hour end stop the service, remove the
notification and leave the dots ageing; the battery scenario runs on the emulator for its mechanics, the phone
numbers an owner action; screenshots and strings in the report.

### Settings this design adds (ADR 0044)

- The position interval (1, 2, 5, 15 minutes): users and products split (a mesh radio's 15 minutes, a
  satellite tracker's few minutes, continuous sharing in phone apps), and the cost differs by about tenfold.
- Not settings: on per trip, the notification, the 12-hour end, sealing and signing, the release policy (safety
  and privacy rules); the transports (both are used when they work).

### ADR draft (number 0051)

```
# 0051. Phone-to-phone records between attested installs

Date: <when promoted>
Status: accepted (stage 1 waits on the owner's go for the position flow)

## Context
The owner allowed group positions and trail notes "only between copies of the signed app" and asked
whether peer to peer across the internet works with only a minimal central server (2026-10-29).
Proposal 0051 compared attestation (nothing, Play Integrity, a pinned signer claim, a web of trust,
hardware key attestation), transports (the LAN link, Wi-Fi Aware and Direct, BLE, hole punching,
the relay) and the server's role.

## Decision
1. Identity: every install holds a hardware-attested EC P-256 key in the Android Keystore, renewed
   every 30 days. A phone or server accepts a record only from a key whose attestation chain passes
   the release policy: package, release signer, TEE or StrongBox, verified boot, locked bootloader,
   a bundled Google root, not revoked in the cached list. Debug builds carry a debug policy; release
   builds do not. Play Integrity is not used.
2. Groups: a share code is a group; a member's key is pinned at join; positions are signed by the
   install key and sealed under the code.
3. Transports: the LAN link and the server relay first; BLE later after a battery measurement;
   Wi-Fi Aware only as a spike for bulk data; no hole punching.
4. Server: stores sealed blobs it cannot read (positions, later breadcrumbs) and, later, signed public
   notes per z8 tile until they expire, checking size, signature, attestation and rates; no account,
   no merge; several servers may hold the same mailboxes.
5. Privacy rules (not settings): positions off by default, on per trip, a notification with Stop while
   they run, an automatic end after 12 hours; notes say what they reveal and their key id rotates
   monthly; breadcrumbs are sealed to contacts only.
6. Formats: the position record `DPOS` v1 (pressure fields reserved), one new sealed-frame purpose, a
   group relay slot label; versioned and refused plainly across versions (ADR 0033 §3).

## Consequences
Phones without hardware attestation, emulators in release mode, rooted phones and custom systems
without an allowlisted boot key cannot take part. A forged chain from a leaked factory key passes
until Google revokes it. The relay learns addresses, sizes and timing of group members.
```

## What would change this recommendation

- The project ships on Play only and drops sideloading: A2 becomes possible for server checks, still not for
  phone-to-phone offline checks.
- A measurement on the owner's phone shows BLE low-power scanning costs well under one cellular request a
  minute: BLE moves ahead of the relay for positions.
- Leaked-key forgeries become common on non-RKP phones: the release policy requires RKP-era chains (or adds
  the server's fresh revocation check) for notes between strangers.
- The owner wants GrapheneOS and similar systems in: an allowlist of published boot keys enters the policy.
- The owner declines notes between strangers: stages 3 and the tile mailbox drop; stage 2 stands alone.

## Claims to verify

- The key-description fields and the first-occurrence rule, the two roots and the 2026-02-01 start, the
  revocation list URL and refresh: https://developer.android.com/privacy-and-security/security-key-attestation,
  https://source.android.com/docs/security/features/keystore/attestation#schema.
- The application id is supplied by the OS (so `SELF_SIGNED` systems could lie about it): the AOSP schema page.
- Factory attestation keys are shared by batches of devices and the chain carries no device identifier without
  ID attestation: the AOSP attestation page (not fetched in this pass).
- Play Integrity: verdicts for sideloaded copies, Google-side decryption, quota:
  https://developer.android.com/google/play/integrity/verdicts, `.../standard`, `.../overview`; the SDK's terms
  page for its licence (not fetched).
- `android/keyattestation`'s dependencies and JDK 21 toolchain: its `build.gradle.kts` on GitHub.
- An Android hotspot with no cellular data still lets its clients reach each other (no client isolation): not
  established.
- BLE outdoor range between phones, BLE advertising cost, Wi-Fi Aware's share of phones and its background
  behaviour, the current AOSP scan windows (shortened in 2022), the Android emulator's Bluetooth between two
  instances: not established.
- The LTE tail figures: the Huang et al. paper itself (the numbers here come from a lecture summary).
- Licences named but not checked at the source: jvm-libp2p, Bouncy Castle (MIT-style), ice4j (Apache-2.0, from
  an aggregator), the LoRa mesh firmware (GPL, from a third-party README).

## Critic's objections and responses

(none yet)
