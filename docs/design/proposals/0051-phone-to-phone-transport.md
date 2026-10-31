# 0051. The phone-to-phone transport between signed copies

Date: 2026-10-30
Status: under review

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
are flagged for the owner (CLAUDE.md › Decisions, ADR 0031 Decision 3, ADR 0033 §4), and the stage 1b task
below is buildable only after the owner answers the questions under "Owner questions".

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing
  (`docs/PLANNING.md` › Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL); offline
  first; every feature agent-drivable (devtool command, debug route); agents write all code; the design
  goals (ADR 0025).
- The app is open source and ships as APKs from GitHub releases (not from Play today; Play is an open owner
  decision): any secret inside the APK can be copied out, so "signed app only" cannot rest on a shared secret.
- The release signing key is meant never to change (ADR 0024); its SHA-256 fingerprint starts `47313810`
  (`update/Signing.kt` already reads the running app's signers). That is a promise about the updater, not a
  guarantee against loss, so the attestation policy must not assume one signer forever (objection 7).
- No Wi-Fi gates (ADR 0023): nothing is limited to Wi-Fi; LAN discovery is the one exception ADR 0023 names.
- The app rests in the background (T150, `docs/reference/android-app.md` › The app in the background):
  anything that listens after `onStop` needs a foreground service with a visible notification.
- The existing machinery (T54, T72, T78, T90, T96): the sealed LAN link (`sync/crypto/SealedLink.kt`,
  `share/ShareGate.kt`, ChaCha20-Poly1305 under HKDF of an 80-bit share code, a fresh salt per frame), NSD
  discovery while the Share screen is open (`share/ShareNsd.kt`), and the server relay
  (`serverlib/.../RelayRoutes.kt`: one ciphertext blob per side under a slot derived from the code, in
  memory, 15 minutes after the last write, at most 4 sides a slot, 120 requests a minute per address in a
  token bucket, open without a token, and any frame whose purpose is not `RELAY` refused with 400
  `notAFrame`). Threat model: `docs/PLANNING.md` › Sync, Threat model.
- `shared` has no elliptic-curve code (PLANNING › Threat model: "elliptic-curve arithmetic we do not have yet").
- No other product's name in user-visible text; safety wording says plainly that this is not an emergency
  beacon.
- ADR 0044: a behaviour users split on becomes a setting with the researched default, except safety and
  privacy rules.

## Prior art

From four read-only research passes on 2026-10-30 (technical variant, ADR 0044, for transport and
attestation; a short app survey for group positions and condition reports), corrected where the critic
checked the sources (`0051-critic-review.md`, Part 1). Several items could not be established; they are
listed under Claims to verify rather than guessed.

- **Group positions in outdoor apps go through a vendor server or a satellite service.** Garmin's MapShare
  sends inReach positions over the Iridium network to a Garmin web page, switched on per trip on the device
  (https://www.garmin.com/en-US/blog/?p=31761, https://trackleaders.com/mapshare-howto.php). No live group
  sharing was found in Gaia GPS (third-party forum: https://backcountrypost.com/threads/gaia-gps-sharing-tracks-and-waypoints.9556).
  The one off-grid peer-to-peer example is a LoRa mesh with pocket radios, whose default position broadcast is
  every 15 minutes (https://azmsh.net/recommended_configuration_settings.html; the critic confirmed it at the
  source); its firmware and protocol definitions are GPL-3.0, so no code or generated file of it may enter
  the app.
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
- **NAT traversal is never relied on alone.** Tailscale's author estimates that a basic implementation gets a
  direct connection "over 90% of the time" (an estimate, not a measurement of users) and keeps a relay (DERP)
  as "our fallback of last resort"; one hard NAT on the path breaks simple punching
  (https://tailscale.com/blog/how-nat-traversal-works). The IMC measurement of libp2p's hole punching in the
  public IPFS network, over 4.4 million attempts, reports about 70% ± 7.1% success
  (https://arxiv.org/html/2510.27500v1); probelab's own post reports 82.9% (3,042 of 3,669) behind
  port-restricted cone NATs and 39.7% (213 of 536, flagged as doubtful) behind symmetric ones, and its table
  sums to about 59% overall (https://probelab.io/blog/can-libp2p-punch-through-nats/). Neither names phones
  or carrier NATs; no cellular-specific rate was found.

## Part 1: proving the peer is the signed app

The question is narrower than it sounds. A peer that runs the unmodified release app on a phone with a
locked, verified system can still lie about its position (a mock location) and a person can still write a
false note; attestation keeps out scripts on servers, modified builds and clients that are not phones, and
lets a phone hold a stranger's note to the same standard as its own. It does **not** keep out a person with
one phone and patience (objection 3, below). For a group that already shares an 80-bit code it is a second
lock on a door that is already locked; for notes between strangers it is one of several locks.

### A1. Nothing beyond today's share code

The share code is the group: whoever has it is in. What it meets: everything except the owner's condition.
Cost: none. Risk: a copied code lets any client in, modified or not; for public notes there is no gate at
all. Exit cost: none. Moving parts: none.

### A2. Play Integrity with a server check

The app asks Google Play for a signed verdict about the app and the device; the verdict must be decrypted on
Google's servers through the developer's backend (`decodeIntegrityToken`,
https://developer.android.com/google/play/integrity/standard). A sideloaded copy gets `UNRECOGNIZED_VERSION`
("the certificate or package name does not match Google Play records") or `UNLICENSED`
(https://developer.android.com/google/play/integrity/verdicts): the verdicts assume Play distribution, which
the project does not have. It needs a Google Cloud project, Google Play services on the phone, a project
server holding Google credentials for every check, and a network at the moment of the check; a second phone
in the backcountry cannot verify a token at all. The SDK is a closed Play services library used under
Google's API terms of service (https://developer.android.com/google/play/integrity/terms), not open-source
software; F-Droid rejects apps that require Play services (https://f-droid.org/docs/Inclusion_Policy/).
SafetyNet, its predecessor, was shut down in January 2025
(https://developer.android.com/training/safetynet/attestation). Fails offline first, the open-source
dependency rule and the distribution the project has. Exit cost: the server and the Google account. Moving
parts: Google's service, a credentialed backend, a quota at Google's discretion.

### A3. The signing certificate exchanged and pinned

Each phone tells the other its signer's fingerprint (it can read its own, `update/Signing.kt`), and peers
accept only a release fingerprint. This is a claim: any program can send the 32 bytes. Useful as a cheap
version and build check in the hello message, worthless as proof. Cost: tiny. Exit cost: none.

### A4. A key pair per install with a web of trust between contacts

Each install makes a key pair; people exchange public keys when they meet (the share screen's QR code) and
vouch for each other. This authenticates *people the user chose*, which is exactly right for a group and for
breadcrumbs sealed to contacts, but it does not prove that a key lives in the signed app, and a web of trust
between strangers does not scale to trail notes (the PGP experience). Cost: a signature scheme and a key
store. Exit cost: low (keys are per install). Moving parts: key exchange in the pairing flow.

### A5. A hardware-attested identity key per install (Android key attestation)

**What the hardware gives.** The app generates a key in the phone's secure hardware (Android Keystore, EC
P-256, StrongBox where present) with an attestation challenge. The key never leaves the hardware. The
hardware returns a certificate chain to a Google root whose first key-description extension names the
package, the signing certificates' SHA-256 digests, the security level (software, TEE or StrongBox), the
verified-boot state, the boot key and hash, whether the bootloader is locked, the OS version and its patch
levels. Only the first occurrence of the extension in the chain may be trusted, and the level must be TEE or
StrongBox (https://developer.android.com/privacy-and-security/security-key-attestation; schema
https://source.android.com/docs/security/features/keystore/attestation#schema). The application id is
filled in by the Android framework (it is under `softwareEnforced` on every device, stock included); what
makes it trustworthy is verified boot of a known system.

**Structure (objections 1 and 4).** Two levels of key, so that rotation never needs Google:
- The **identity key** is the attested hardware key. It is generated once, on first use while the phone is
  online, and kept as long as the phone keeps it. Pins (a group member, a contact, a mute) bind to its
  public key, never to a signing key.
- **Signing keys** are short-lived (30 days) EC P-256 keys in the Keystore, each certified by the identity
  key in a small *certification record* (signing public key, valid from and to, a platform tag, signed by
  the identity key). Records are signed by a signing key. The monthly pseudonym for public notes is a
  signing key; making one needs no provisioning.
- A **rotation record** (the new identity key, signed by the old identity key and by the new one) travels
  with the first record under a new identity key, so pins and mutes follow an identity across a re-key
  (a factory reset loses the old key, so no rotation record exists then: the member re-joins with the code).

**Which steps need Google, and when (objection 1).** Verification never does: any phone or server checks
the chain offline against bundled roots. Key *generation* may: on phones that rely on Remote Key Provisioning
(optional on Android 15, the only mode on devices launching with Android 16), the attestation key comes from
a pool that Google's provisioning service fills through the platform's `rkpd`; an empty pool or an
unregistered phone fails with `ERROR_ATTESTATION_KEYS_UNAVAILABLE` (API 33), transiently in the first case,
and RKP certificates are short-lived (about 30 days per an Android attestation engineer, critic's claim 4)
and must still be checked for validity. So: the identity key is made while online; when the phone is online
and the identity chain is within 14 days of its leaf's end, the app pre-generates the successor and writes
the rotation record; a receiver accepts a chain whose leaf expired within a **grace window of 60 days** and
marks the member *stale* instead of refusing. A fresh install with no signal at a trailhead cannot make its
identity key; it can still use the share code (A1) where the group allows unverified phones (Owner
questions), and it says so.

**Roots and revocation (objections 7, 15, 16).** Google publishes five root certificates at
`https://android.googleapis.com/attestation/root`: the RSA root valid to 2042, a new root (P-384 by
inspection) that signs chains from 2026-02-01 and is valid to 2035, and three previously issued roots
(2016 to 2026-05-24, 2019 to 2034, 2021 to 2036). The verifier bundles the whole published array, anchors on
the roots' **public keys** (not on the device-presented root certificate, which it strips), and ignores the
root's own validity dates, so a phone whose factory chain ends in the root expiring on 2026-05-24 keeps
verifying while Google lists that key. Revocation is a JSON object at
`https://android.googleapis.com/attestation/status` whose documented keys are lowercase hex serials but
whose live file (over a thousand entries, about 180 kB) keys most entries by decimal strings and some by
hex; the parser normalises both to one integer form before matching, and checks every certificate in the
chain. A phone holds a cached copy with its age; the server always has a fresh one. Accepting a stranger's
note on a phone requires a cached list younger than 30 days; a group member is accepted with any age, the age
shown on `GET /identity`.

**The policy is data (objection 7).** The accepted signers and the trusted roots are two versioned lists in
`shared`'s resources, each entry with an origin line, replaceable by an app update and by a policy file the
app can fetch, signed by any key in the signer list. A second release signer is generated and registered in
the list from the first build (kept offline by the owner, never used unless the first is lost). Debug builds
add the debug signer and a test root, by build type; release builds contain neither, and a test asserts it,
because the emulator's software attestation root ships with its private key in AOSP's source
(`soft_attestation_cert.cpp`, critic's claim 9).

**Platforms (objection 8).** The identity record and `DPOS` carry a platform and chain-format tag from their
first byte (`android-key-attestation-v1` now; App Attest for iOS later, which chains to Apple's root, binds to
team and bundle id, has a counter and no boot-state fields, so its policy checks different fields). The
policy is per platform. `shared` gets the DER parser, the per-platform policy and a `Verifier` interface
(P-256 ECDSA verify, X.509 chain signatures); the JVM and Android actual, in `jvmCommonMain`, uses
`java.security`. A JVM peer (the server, the devtool) holds no attested identity: it verifies, it does not
take part as a member, which is acceptable for positions and notes (the server is a mailbox here, not a
member).

**What bounds one phone (objection 3).** Attestation does not bound identities: the signed app on a locked
phone can make any number of attested keys, and RKP hands each app pool keys, not one key for life. So "a
rate per key" is no defence and is dropped. What bounds one phone: the rate per address at the mailbox; a
per-tile cap per attestation intermediate serial (per device on RKP phones, per batch before); a small cost
per post (a proof of work of about a second on a phone); and the crowd rules (expiry, "still so" and "not any
more", a mute that follows the identity key). The owner should read attestation as "keeps out scripts and
modified builds", not "one person, one voice".

**What it cannot stop, and who it refuses.** A forged chain made with a leaked factory attestation key passes
until Google revokes the key (community tools do this; RKP phones are not exposed:
https://github.com/android/keyattestation, https://github.com/MobInfinity/TrickyStore). Revocations hit whole
batches that legitimate phones share. Attestation became mandatory for new devices with Google Play from
Android 8.0 (our minSdk 26); phones launched on 7.x and upgraded, phones without Play certification and some
with broken implementations fail. Emulators produce a software-level chain. Custom systems with their own boot
key (GrapheneOS) report `SELF_SIGNED` with that key; GrapheneOS publishes its keys as signed JSON, and trusting
such a list is the same shape of trust as trusting a stock system under Google's root (whether to is an owner
question). Nobody publishes the share of phones that pass; stage 1a measures it on the testers' phones. Every
refusal carries reasons and a lift (below, "Refusals and lifts").

**Privacy (objection 5).** The chain carries no serial or IMEI (those need ID attestation, which an ordinary
app cannot request), and on pre-RKP phones the batch attestation key is shared by many devices (Google's 2019
blog on batch keys; the batch size of at least 100,000 is from community and FIDO sources). But the leaf
carries the boot key, the boot hash, the OS version and three patch levels, and a pre-RKP chain carries a batch
intermediate whose serial is stable for the phone's life: a quasi-identifier that survives the monthly signing
key. Hence the payload table's note row and the rule that the server strips chains from its public feed.

**Cost:** a DER parser for one extension and the certification and rotation records (about 400 lines), a
`Verifier` interface with a `jvmCommonMain` actual, the two policy lists and the revocation fetch and cache,
the identity and signing key management on the phone. **Exit cost: high.** Every pin, every note in
circulation, the server's policy and the iOS plan hang on the scheme; leaving A5 means re-keying every install
and dropping every verified pin. **Moving parts:** the root and signer lists (updated when Google rotates or a
signer is added), the revocation list cache, the policy per build type and per platform, and at key generation
time `rkpd` and Google's provisioning backend. Who else: Auditor between phones, banking and enterprise apps
toward servers.

**A dependency or our own parser.** `android/keyattestation` is the reference, but its build pulls Guava,
protobuf-lite, Gson, a CBOR library and Bouncy Castle and targets JDK 21 (its `build.gradle.kts`), and its
default verifier fetches the revocation list on every call: too much for the app for one extension. The
proposal is a hand-written parser in `shared` with the library as a test-only oracle on the JVM. The dependency
checklist (`.claude/skills/proposal/dependency.md`) for the parser, as reimplementing a format: (1) it reads one
ASN.1 extension; the library cannot be copied in under a page; (2) our code; the oracle is Apache-2.0, test scope
only; (3) the oracle is active (CI, 112 commits, no tagged release); (4) Google's Android team; (5) none in the
app, the oracle's closure in tests only; (6) yes, on the JVM; (7) untrusted input from strangers: bounded
lengths, one exception type, fuzzed in tests; (8) the coordinator, through the lists' origin lines and the
oracle's release notes; (9) swap in the library on the server side if the parser falls behind; (10) a refused
peer is counted with its reason on a debug route, so a parser bug shows as refusals, never as silent
acceptance.

### Refusals and lifts

| Refusal | What the refused phone sees | Lift |
|---|---|---|
| This phone's own chain fails the release policy (self-check on the Share screen and `GET /identity`) | "This phone cannot prove it runs the signed app: <reason>" with the lift for that reason (update the app; relock the bootloader; "this phone's maker shipped attestation that does not work") | the group's creator, if the owner chooses the switch (Owner questions); else none |
| Identity key cannot be made (no network, empty RKP pool) | "Connect to the internet once to set up sharing" | the network |
| A member's leaf expired | the member is shown *stale* for 60 days, then refused with "update when online" | the network, within the grace window |
| A peer's chain fails at a receiver | the receiver lists it as "unverified phone" with the reason, and the refused phone learns it through the hello reply | as the first row |
| The 12-hour end | 30 minutes before: a notification action "Keep sharing for 12 more hours"; at the end a final signed "stopped sharing" record, so others see an end | the user, from the notification |
| Relay 429 or 507 | "Server busy, retrying" on the group card, with backoff | the server operator |
| An older server refuses the group frame (400) or an older phone the record version | ADR 0033 §3: "The server runs an older Dystopo; group positions need it updated" or "This phone runs an older Dystopo; update both" | update |
| Notes mailbox refuses a note (size, signature, attestation, rate, proof of work) | the note stays on the phone marked "not accepted by the server: <reason>" | the server operator, or time for the rate |

## Part 2: transports

| | Range and needs | Background | Payload | Availability | Licence, parts | iOS later |
|---|---|---|---|---|---|---|
| T1 LAN link (today) | one Wi-Fi network: a hut, a camp, one phone's hotspot | listener needs a foreground service | any size, sealed | every phone | ours, exists | open protocol |
| T2a Wi-Fi Aware | tens of metres, no access point | not documented; sessions "stay synchronised to the cluster and cost battery" | any size after a data path | only phones with `FEATURE_WIFI_AWARE`; share not established; off while a hotspot runs on some | platform API, API 26, `NEARBY_WIFI_DEVICES` from 33 | iOS 26 added Wi-Fi Aware; interop with Android failed in 2025 tests |
| T2b Wi-Fi Direct | tens of metres | user prompts, one group owner | any size | most phones | platform API | no |
| T3 BLE advertising and GATT | tens of metres outdoors (not established) | scans keep running with the process alive; low-power mode enforced in the background | 31 bytes legacy (24 after flags and a service-data header), up to 1650 extended; GATT for more | almost every phone | platform API; `BLUETOOTH_SCAN` (`neverForLocation`), `ADVERTISE`, `CONNECT` from API 31 | background advertising restricted (overflow area) |
| T4 internet, direct (hole punching) | anywhere with signal, both online at once | a socket needs a foreground service | any | fails behind symmetric and carrier NATs; a relay is needed anyway | ice4j Apache-2.0, jvm-libp2p MIT or Apache-2.0, libnice LGPL (excluded) | yes |
| T5 relay mailbox (today's relay, extended) | anywhere with signal, not at the same moment | one request per interval | small sealed or signed blobs | every phone | ours, exists; needs a server change for a new frame purpose | yes |

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
services (https://developers.google.com/nearby/connections), so the open-source dependency rule excludes it;
the open alternatives found are GPL (Briar, https://briarproject.org/copyright).

- **T1** costs nothing new and works today wherever phones share a network; its weakness for positions is
  plain: a group on one network is a group within shouting distance. It is the right test bed and the right
  path at a hut, a trailhead or a car park, and the right place to exchange notes between strangers' phones.
  Android's local-only hotspot exists for clients to talk to each other and its soft AP has no
  client-isolation switch (critic's claim 15); a two-phone run at stage 1b confirms it. A group session holds
  the listener for hours, so it takes its own gate instance without the Share screen's global rotation (fifty
  wrong frames in ten minutes rotate today's code), and a locked-out member sees "this phone's code was refused
  too often; wait ten minutes".
- **T2a** is the only radio path with real bandwidth and no access point, but it is missing on many phones,
  undocumented in the background, and costly while a session is open; worth a spike when bulk data (packs)
  must move between phones, not for positions.
- **T2b** prompts the user and elects a group owner; rejected for anything automatic.
- **T3** is the only path that works between strangers walking past each other with no signal, and the only
  one that relays along a strung-out line of hikers. A legacy advertisement leaves 24 bytes after the flags
  record (3) and a 16-bit service-data header (4); an unsigned position alone (key id 8, time 4, lat and lon 8,
  accuracy 2, sequence 2) takes all 24, before T78's 16-byte recognition token and before any seal's 16-byte
  tag. Nothing sealed fits a legacy advertisement: every variant needs extended advertising or a GATT read. It
  needs real phones to test (the emulator's Bluetooth between two instances is a claim to verify).
- **T4** answers "direct" literally but buys little here: both phones must be online at the same moment, a
  phone in the background holds no socket without a foreground service, a cellular pair falls back to a relay
  often (no cellular figure; 39.7% success behind symmetric NATs in probelab's data), and the payloads are
  hundreds of bytes. The rendezvous server it needs holds addresses and timing, the same metadata the relay
  holds.
- **T5** exists, already carries sealed frames under a slot the server cannot reverse, and posting a blob
  returns the other sides' blobs in the same request, so one request per interval is both send and receive.
  For positions it needs three server changes, all in `RelayRoutes.kt`: accept a new sealed-frame purpose
  `GROUP` (today any purpose but `RELAY` is a 400; keeping `RELAY` with an inner type would avoid the change
  but give group frames the same derived key as sync bundles, losing the purpose separation `ShareCode.key`
  was built for), a lifetime for `GROUP` blobs of at least twice the longest interval (30 minutes by default),
  and a larger side cap for `GROUP` slots (8). The phone derives the group slot with its own label, so a
  group's positions never share a slot with a sync under the same code. For notes it needs a second, longer-lived
  kind of mailbox (Part 4).

A pocket LoRa radio (the group idea's third transport) stays a later proposal of its own: the popular
firmware is GPL, so only a radio with a permissively documented protocol could be supported.

## Part 3: payloads and what each reveals

| Payload | Size | Audience | Protection | A carrier (relay, LAN eavesdropper, BLE relay phone) sees |
|---|---|---|---|---|
| Position (group) | about 40 bytes, a 64-byte signature, the certification record once per signing key, every 1 to 10 min | the group (share code) | signed by the signing key, sealed under the code (purpose `GROUP`) | a slot derived from the code, a random side id, sizes, timing, addresses, and the group's size as the number of sides; over BLE a token that rotates every 10 min |
| Pressure (optional fields of a position) | 8 bytes: hPa, GNSS height, sigma | the group | inside the sealed position | nothing more than the position's frame |
| Identity record (once per member per group) | the chain, 2 to 4 KB | the group | sealed under the code | as a position |
| Condition note | 200 to 500 bytes, plus the author's chain once per author | everyone with the app | signed, public by design | the note (place, time, type, text), the monthly signing key's id; at the trailhead exchange also the chain: boot key, boot hash, OS version, patch levels and, on pre-RKP phones, a batch intermediate serial stable for the phone's life. The server verifies chains and strips them from its tile feed, serving the verdict and key id instead |
| Note confirmation ("still so", "not any more") | about 80 bytes | everyone | signed | the confirming key id, the note by reference, time |
| "Stopped sharing" record | about 40 bytes | the group | signed and sealed | as a position |
| Breadcrumb (later) | the last few fixes, about 200 bytes | the user's contacts | signed, sealed to each contact's public key (ECDH) | an opaque blob, a recipient tag that rotates (derived from a secret shared with the contact), an expiry, size |

Rules per flow (privacy, not settings, ADR 0044): positions are off by default, switched on per trip on the
Share screen, shown by a notification with Stop for as long as they run, and end after 12 hours unless renewed
from the notification's "Keep sharing for 12 more hours" action, offered 30 minutes before the end; the end
sends a "stopped sharing" record. Twelve hours is this proposal's choice (one long mountain day; the owner may
set it otherwise). A note says when written that it reveals where and when its author was; on a pre-RKP phone
the first note also says that its chain identifies the phone's model and batch to the phones it meets; its
signing key rotates monthly. Breadcrumbs are opt-in per trip and sealed to contacts only, never readable by a
carrier. No flow ever goes to a server the user has not configured (release builds have no default server).

## Part 4: the server's minimum role

- **Positions and breadcrumbs:** the server stores ciphertext under slots it cannot reverse and hands it
  back, the relay of T72 with the three `GROUP` changes of Part 2. It never sees the code, a position, a
  breadcrumb, a device id or who is who; it sees client addresses, sizes, timing, that some addresses share a
  slot, and so the group's size (PLANNING › Threat model, which applies otherwise unchanged). Behind a carrier
  NAT every phone on one address shares the 120-a-minute bucket. Breadcrumbs need a longer lifetime (hours to
  days) and therefore disk, a later change.
- **Notes:** a mailbox per z8 map tile (a region of roughly a hundred kilometres, so a query reveals only a
  region) that accepts a signed note, checks its size, its signature, its author's attestation chain (the
  server verifies like a phone, with a fresh revocation list), its proof of work, a rate per address and a cap
  per attestation intermediate serial per tile, stores it until it expires, and answers "notes for this tile
  since cursor" with the chains stripped. It merges nothing, keeps no account, and sees only what is public by
  design plus addresses. Several servers can hold the same mailboxes; a phone posts to and reads from the
  servers it is configured with, so no single server is required, and the phone-to-phone exchange at a
  trailhead is the offline tail of the same feed.
- **What no server ever does:** decrypt a position or a breadcrumb, hold a share code, link a key id to a
  person, or require an account.

## Part 5: the battery cost of listening

| Path | Published figure | Standing |
|---|---|---|
| BLE scanning | Nexus 5, Android 5.0, 2014: the page states 268 mA (low power) and 454 mA (low latency) for the whole phone, but its own data (13% of 2,300 mAh in 134 min; 25% in 152 min) gives about 134 and 227 mA (https://developer.radiusnetworks.com/2014/10/28/android-5.0-scanning.html) | the ratio holds, the absolutes do not; one old phone |
| BLE scanning, model | 3%, 5%, 20% less battery life by mode, attributed to https://arxiv.org/pdf/2005.05625 | unverified: the paper is about contact-tracing reliability and its abstract has no battery figures |
| BLE scanning, Exposure Notifications | about 3% in 24 h on an iPhone with 4 s scans every 5 min | a vendor's claim reported in the press; forum reports far higher |
| BLE advertising | not established | |
| Wi-Fi Aware | no phone measurement found; Android says open sessions cost battery and should be closed promptly | |
| LAN socket and NSD | not established; a hotspot host costs more (not established) | |
| Cellular polling | LTE tail 1,080 mW for about 11.6 s after each request, from a lecture summary of Huang et al., MobiSys 2012 (https://experts.umn.edu/en/publications/a-close-examination-of-performance-and-power-characteristics-of-4/); about 3 mWh a request, so about 180 mWh an hour at one a minute and 12 mWh an hour at one every 15 minutes | second hand, 2012 LTE, the arithmetic an inference; 5G tails and coverage search not established |

Nothing here settles the order of the transports' costs; the stage 1b measurement does. Stage 1b measures on
the owner's phone with `tools/android-battery.py` (a `group` scenario against the existing `tracking`
scenario), and the BLE stage measures before it ships.

## Recommendation

**Attestation: A5 for every flow, with A4 on top for people, and every verdict shown.** Each install holds a
hardware-attested identity key that certifies short-lived signing keys; a phone verifies every record's
chain against the release policy (a signer list, a root list anchored on public keys, TEE or StrongBox,
verified boot, locked bootloader, the cached revocation list, a 60-day grace window for an expired leaf) and
shows the verdict: "verified phone", "stale", or "unverified phone: <reason>". Whether "unverified" means
refused or admitted with the badge is the owner's (Owner questions); for notes between strangers the server's
policy is attested only, as the owner asked. For groups the share code stays the membership secret (A1 kept as
the envelope), and a member's identity key is pinned at join; for breadcrumbs the contacts are the people
whose pinned identity keys the user keeps (A4). A2 is rejected (offline, the open-source rule, distribution),
A3 is kept as a cheap version check in the hello message only.

**Transport: T1 and T5 first, T3 later, T2a as a spike for bulk, T4 not built.** Stage 1 carries positions
over today's LAN link and the relay, both sealed under the share code; the server gains the `GROUP` purpose,
its lifetime and its side cap. BLE (T3) is the stage that makes the no-signal case work and needs real phones
and a battery measurement first.

**Stages.**
1. Stage 1a: the attested identity key, signing keys, the verifier and the self-check (no data leaves the
   phone except the revocation and policy fetches). Stage 1b: group positions over the share code on the LAN
   link and the relay, with pressure fields reserved in the record but not filled.
2. Stage 2: condition notes as sync ops with expiry by type, signed by the install's signing key, synced
   between the user's own devices and exchanged with paired phones over today's sync (no new transport; worth
   having alone; the idea's "Report a condition" action and the road types).
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

**Predictions (falsifiable).** By the end of stage 1a, at least nine of ten tester phones running the debug
build pass the hardware policy with only the signer check relaxed (if fewer pass, the hard gate is wrong and
the badge is the only workable answer). Stage 1b's `group` scenario at a 2-minute interval costs under a
quarter of the `tracking` scenario's energy on the owner's phone (if more, the interval's default moves to 5
minutes before release).

## Owner questions

These two stay open; stage 1a can be built before they are answered, stage 1b cannot.

**1. Hard gate, or a "verified" badge with a group-creator switch?**
- *Hard gate* (the owner's words read literally): a phone that fails the release policy is refused from
  groups and notes; it sees why, and nobody can let it in. Simple, and exactly "only signed copies". It
  refuses real hikers: phones whose batch key Google revoked after a leak, phones with broken attestation,
  custom systems, old phones, iPhones until App Attest is built, a fresh install never online.
- *Badge with a switch*: every phone still verifies and shows each member's verdict; the group's creator has
  "Only verified phones" on the Share screen, on by default as the owner asked, visible to every member and
  switchable while the group runs; notes between strangers stay attested only at the server. Every refusal
  gets a lift, and a scheme-wide failure (a revoked batch, a root rotation, a lost signer) turns a badge grey
  instead of silencing groups.
- *Recommendation:* the badge with the switch, default on; the stage 1a pass rate is reported with it.

**2. Do custom systems with a published boot key (GrapheneOS) count as signed copies?**
- *No (the draft's default):* only `VERIFIED` boot under a Google root counts. The application id on such a
  system is filled in by that system, so trusting it means trusting its developers' key.
- *Yes, by allowlist:* accept `SELF_SIGNED` with a locked bootloader when the boot key is in a published,
  signed list (GrapheneOS publishes its keys as signed JSON). On a stock phone the application id is equally
  filled in by the framework, trusted because Google's verified boot vouches for the system; the allowlist is
  the same shape of trust with another vouching key.
- *Recommendation:* yes, by allowlist, starting with GrapheneOS's published keys, as a policy-list entry.

### Stage 1a. The attested install identity (task text for the board)

**Goal:** a per-install identity key in the Android Keystore with key attestation that certifies short-lived
signing keys, a verifier for another phone's chain, and this phone's self-check, as proposal 0051 Part 1 (A5)
describes; nothing sent anywhere except GETs of Google's public revocation list and of the project's policy
file when online. (1) `shared` commonMain: a DER reader and the key-description extension parser (OID
1.3.6.1.4.1.11129.2.1.17: attestation version, security level, challenge, the application id's packages and
signature digests, root of trust with boot key, locked flag, boot state and boot hash, OS version and patch
levels), first occurrence only, bounded lengths, one exception type; the identity, certification and rotation
records with a platform tag; a per-platform policy over the signer and root lists, with trust anchors as a
parameter, returning accept, stale or the list of reasons with a lift per reason; a `Verifier` interface. (2)
`jvmCommonMain`: the `Verifier` actual with `java.security` (P-256 ECDSA, chain signatures, anchors by public
key, the presented root stripped). (3) Resources: the root list (all five published roots) and the signer list
(the release signer, the second signer, with origin lines); the revocation list parsed with decimal and hex
keys, cached with its age. (4) The phone: the identity key made on first use while online (StrongBox when
present, else TEE), the successor pre-generated within 14 days of the leaf's end, monthly signing keys,
`ERROR_ATTESTATION_KEYS_UNAVAILABLE` reported, and debug routes: `GET /identity` (key ids, created, level,
chain length, the self-check against the release policy with reasons and lifts, the lists' versions, the
revocation list's age), `POST /identity/verify` (a chain or record in, the verdict out), `POST /identity/rotate`,
and in debug builds `POST /identity/test-chain` (a synthetic chain under the test root, for the accept paths).
(5) `devtool attest --chain <file> [--anchors test|release]` prints the verdict as JSON.
**Owns:** new `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/identity/**`, new
`shared/src/jvmCommonMain/kotlin/io/github/jasondorjeshort/dystopo/identity/**`, new
`shared/src/commonMain/resources/attestation/roots.json` and `signers.json`, new
`shared/src/commonTest/.../identity/**` and `shared/src/jvmTest/.../identity/**` (synthetic chains signed under a
test root by test code covering every accept and refuse branch; a software chain captured from the task's
emulator; the `android/keyattestation` library as a test-only oracle if the licence check accepts its closure in
test scope; no chain from a real phone is committed), new
`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/identity/**` (`InstallIdentity.kt`,
`Revocation.kt`, `IdentityRoutes.kt` with its KDoc), its dispatch line in `androidApp/.../app/Routes.kt`, new
`androidApp/src/test/.../identity/**`, new `devtool/src/main/kotlin/io/github/jasondorjeshort/dystopo/devtool/AttestCommand.kt`
and its dispatch line in `devtool/.../Main.kt`, the identity lines of `docs/reference/android-debug-routes.md`
and `docs/reference/devtool-commands.md`. The second signer's key is generated by the owner (an owner action);
the task commits only its fingerprint. [no-check: files this task creates]
**Done when:** `check` green; JVM tests under the test root accept a TEE chain, a StrongBox chain and a chain
whose leaf expired within the grace window (as stale), and refuse, each with its reason and lift: another
package, a signer not in the list, a software level under the release policy, an unlocked bootloader, an
unverified boot, an unknown root key, a leaf past the grace window, a serial revoked under a decimal key and
one under a hex key, an extension found only in a later certificate; a rotation record moves a pin; a fuzzed
extension throws the one exception and never accepts; a test asserts the release build's lists hold neither the
debug signer nor the test root; on two emulators `POST /identity/test-chain` and `POST /identity/verify`
exercise accept and refuse across phones, `GET /identity` shows the software chain refused by the release policy
with `securityLevel` and its lift, and `devtool attest` agrees; on the owner's phone and the testers' (a debug
build, owner action) `GET /identity` reports level, boot state and lock, and the pass count goes in the report
(the first prediction).

### Stage 1b. Group positions over the share code (after 1a and the owner's answers)

**Goal:** proposal 0051's stage 1b: a group is a share code; while "Share my position with this group" is on
(Share screen, per trip, a notification with Stop and, 30 minutes before the 12-hour end, "Keep sharing for 12
more hours"), the phone sends its position every interval as a signed record sealed under the code (purpose
`GROUP`) over the LAN link when the other phones are on the same network and over the relay when it is online,
and draws each member as a dot with its age and verdict; the end sends a "stopped sharing" record. A member's
identity key is pinned when it first appears; records from an unpinned key, an older sequence or a policy
refusal (per the owner's answer: dropped, or shown as unverified) are counted. The record (`DPOS` version 1,
platform-tagged) reserves the pressure fields unfilled. The group slot is derived with its own label. The
group's LAN listener has its own gate without global rotation. Relay 429 and 507 show "Server busy, retrying"
with backoff; an older server's 400 and an older phone's record version show ADR 0033 §3's sentences.
**Owns:** new `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/group/**` (`PositionRecord.kt`,
`GroupRoster.kt`) and `shared/src/commonTest/.../group/**` (codec, refusals, ages, a three-member simulation
over a lossy network with a test `Verifier`); `shared/.../sync/EncryptedBundle.kt` (the `GROUP` purpose) and
`shared/.../sync/crypto/ShareCode.kt` (the group slot) with their tests; new
`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/group/**` (`GroupSession.kt`, `GroupService.kt`
with foreground-service type `location`, `GroupGate.kt`, `GroupOverlay.kt`, `GroupRoutes.kt` with its KDoc) and
`androidApp/src/test/.../group/**`; `androidApp/src/main/AndroidManifest.xml` (the service);
`share/ShareSession.kt` (the listener held while a group runs, with the group's gate), `share/ShareScreen.kt`
(the switch, the member list with verdicts, this phone's self-check line); the settings registry files for the
interval key (the registry rule); `serverlib/.../RelayRoutes.kt` (accept `GROUP` frames, their lifetime and side
cap) with its test and the relay lines of `docs/SERVER.md`; a `group` scenario in `tools/android-battery.py`; the
group lines of `docs/reference/android-debug-routes.md` and `android-app.md`. [no-check: files this task creates]
**Done when:** `check` green; the interval's default fixed by a benchmark survey (ADR 0039) cited in the report;
two emulators see each other's dot within one interval on the LAN path, and through the relay with the LAN path
cut; a third emulator without the code sees nothing and the relay holds nothing it can open; a refused chain
behaves as the owner chose and is counted on `GET /group`; Stop, the 12-hour end and "Keep sharing" do what
Part 3 says, and the others see "stopped sharing"; an older server (the relay without `GROUP`, in a test) gives
the readable refusal; the battery scenario runs on the emulator for its mechanics, the phone numbers an owner
action (the second prediction); screenshots and strings in the report.

### Settings this design adds (ADR 0044)

- The position interval (1, 2, 5, 10 minutes): products split (a mesh radio's 15 minutes, continuous
  sharing in phone apps), and the cost differs by about tenfold. 15 minutes is left out so the relay's group
  lifetime stays at least twice the interval.
- Not settings: on per trip, the notification, the 12-hour end and its renewal action, sealing and signing,
  the release policy (safety and privacy rules); the transports (both are used when they work); the group
  creator's "Only verified phones" is a per-group choice on the Share screen, not an app setting, if the owner
  chooses the badge.

### ADR draft (number 0051)

```
# 0051. Phone-to-phone records between attested installs

Date: <when promoted>
Status: accepted (stage 1b waits on the owner's answers: the gate or the badge, and custom systems)

## Context
The owner allowed group positions and trail notes "only between copies of the signed app" and asked
whether peer to peer across the internet works with only a minimal central server (2026-10-29).
Proposal 0051 compared attestation (nothing, Play Integrity, a pinned signer claim, a web of trust,
hardware key attestation), transports (the LAN link, Wi-Fi Aware and Direct, BLE, hole punching,
the relay) and the server's role; its critic raised 9 serious and 13 minor objections, all answered.

## Decision
1. Identity: every install holds a hardware-attested identity key, made once while online, that
   certifies 30-day signing keys; a rotation record carries pins to a successor. Records carry a
   platform tag; the policy is per platform.
2. Policy as data: a signer list (the release signer and a second registered signer) and a root list
   anchored on public keys, versioned, replaceable by an update or a signed policy file. A record is
   verified against TEE or StrongBox, verified boot, a locked bootloader and the cached revocation
   list; an expired leaf is stale for 60 days. Debug builds alone add the debug signer and a test root.
   Every verdict is shown with its reasons and a lift. Play Integrity is not used.
3. Groups: a share code is a group; a member's identity key is pinned at join; positions are signed
   and sealed under the code (purpose GROUP).
4. Transports: the LAN link and the server relay first; BLE later after a battery measurement;
   Wi-Fi Aware only as a spike for bulk data; no hole punching.
5. Server: stores sealed blobs it cannot read (GROUP frames: own lifetime, side cap 8; later
   breadcrumbs) and, later, signed public notes per z8 tile until they expire, checking size,
   signature, attestation, proof of work, a rate per address and a cap per attestation intermediate;
   chains stripped from the public feed; no account, no merge; several servers may hold the same
   mailboxes.
6. Privacy rules (not settings): positions off by default, on per trip, a notification with Stop,
   an end after 12 hours (the proposal's choice: one long mountain day) renewable from the
   notification 30 minutes before, and a "stopped sharing" record; notes say what they and their
   chain reveal, their signing key rotates monthly; breadcrumbs are sealed to contacts only.
7. Formats: DPOS v1 (pressure reserved), the identity, certification and rotation records, the GROUP
   frame purpose and slot label; versioned and refused plainly across versions (ADR 0033 §3).

## Consequences
Phones without working hardware attestation, emulators in release mode and rooted phones fail the
policy (refused or badged per the owner's answer). Key generation depends on Google's provisioning
service on RKP phones; verification does not. A forged chain from a leaked factory key passes until
Google revokes it, and a revocation hits legitimate phones of the same batch. The relay learns
addresses, sizes, timing and each group's size. Leaving the scheme means re-keying every install.
Attestation keeps out scripts and modified builds, not a person with one phone.
```

## What would change this recommendation

- The project ships on Play only and drops sideloading: A2 becomes possible for server checks, still not for
  phone-to-phone offline checks.
- Stage 1a's pass rate falls under nine in ten: the hard gate is off the table.
- A measurement on the owner's phone shows BLE low-power scanning costs well under one cellular request a
  minute: BLE moves ahead of the relay for positions.
- Leaked-key forgeries become common on non-RKP phones: the server accepts notes only from RKP-era chains.
- The owner declines notes between strangers: stage 3 and the tile mailbox drop; stage 2 stands alone.

## Claims to verify

- The key-description fields and the first-occurrence rule, the five roots and the 2026-02-01 start, the
  revocation list's URL and its decimal and hex keys:
  https://developer.android.com/privacy-and-security/security-key-attestation,
  https://source.android.com/docs/security/features/keystore/attestation#schema, the live list (critic's
  claims 1 to 3).
- RKP: the pool, `ERROR_ATTESTATION_KEYS_UNAVAILABLE`, the about-30-day certificates (critic's claim 4, from
  Google's page, the API reference and a WebAuthn list post).
- Batch keys and the absence of device identifiers without ID attestation (critic's claim 6).
- Play Integrity: verdicts for sideloaded copies and Google-side decryption (critic's claim 7).
- `android/keyattestation`'s dependencies and JDK 21 toolchain (critic's claim 8).
- Not established: BLE outdoor range between phones, BLE advertising cost, Wi-Fi Aware's share of phones and its
  background behaviour, the current AOSP scan windows (shortened in 2022), the Android emulator's Bluetooth
  between two instances, the 5G tail, the LTE tail in the paper itself, the share of phones that pass the
  hardware policy (stage 1a measures it).

## Critic's objections and responses

From `0051-critic-review.md` (0 blocking, 9 serious, 13 minor). Resolution counts: 21 accepted or accepted with
change, 1 rejected in part (none rejected outright); the two questions the critic put to the owner are under
Owner questions.

1. *Serious. Key generation and renewal are not offline.* Accepted with change: Part 1 A5 "Structure" and
   "Which steps need Google" (identity key made once online, successor pre-generated within 14 days of the
   leaf's end, a 60-day grace window as stale, monthly signing keys certified by the identity key, so rotation
   needs no provisioning).
2. *Serious. A refused legitimate phone has no lift and is never told.* Accepted: the self-check on the Share
   screen and `GET /identity` with reasons and lifts, the "Refusals and lifts" table, the hard gate versus the
   badge as Owner question 1 with stage 1a's pass rate attached.
3. *Serious. Attestation does not bound identities.* Accepted: "a rate per key" dropped; the rate per address,
   a cap per attestation intermediate serial per tile, a proof of work and the crowd rules; the owner told what
   attestation gives (A5 "What bounds one phone", the ADR's Consequences).
4. *Serious. Key continuity across rotation is undefined.* Accepted: pins and mutes bind to the identity key;
   the rotation record signed by both keys; a factory reset means re-joining.
5. *Serious. The chain is more identifying than the patch level.* Accepted: A5 "Privacy", the payload table's
   note row names the fields, the server strips chains from its feed, a pre-RKP phone says what its chain
   reveals before the first note. Not taken: preferring RKP-era chains for every public note, since it would
   refuse most current phones; the server may move to it (What would change).
6. *Serious. The 12-hour end fails closed and tells nobody.* Accepted: the "Keep sharing for 12 more hours"
   action 30 minutes before, the "stopped sharing" record, and the ADR says who chose 12 and why.
7. *Serious. One signer and one root list, no rotation path.* Accepted: signer and root lists as versioned data
   anchored on root public keys, replaceable by an update and a signed policy file, a second signer registered
   from day one (its key an owner action).
8. *Serious. Hidden coupling to Android and a missing P-256 primitive.* Accepted: the platform tag in the
   identity record and `DPOS`, a per-platform policy, the `Verifier` interface with a `jvmCommonMain` actual on
   the cost list, and the statement that a JVM peer verifies but holds no attested identity.
9. *Serious. The acceptance path is not agent-drivable.* Accepted: trust anchors are a policy parameter;
   synthetic chains under a test root drive every branch on the JVM and, through a debug-only route, on two
   emulators; the owner's phone confirms; no real-phone chain is committed.
10. *Minor. The legacy BLE arithmetic.* Accepted: corrected in the table and T3 (24 bytes usable; nothing
    sealed fits).
11. *Minor. The battery citations.* Accepted: each row's standing marked, the Radius absolutes corrected
    against its own data, the arXiv model marked unverified, the closing sentence replaced.
12. *Minor. Hole-punching attributions.* Accepted: Tailscale's figure an estimate, the 70% the IMC paper's,
    probelab's table near 59%.
13. *Minor. Play Integrity's "licence".* Accepted: reworded to a closed Play services SDK under Google's API
    terms; the quota number dropped.
14. *Minor. "Nothing new for the server" is false.* Accepted: T5 and Part 4 name the `GROUP` purpose, its
    lifetime and side cap in `RelayRoutes.kt`, and ADR 0033 §3's refusal sentence; the inner-type alternative
    recorded and rejected for losing key separation.
15. *Minor. Root handling.* Accepted: all five roots bundled, anchored on public keys, the presented root
    stripped, the 2026-05-24 expiry handled by ignoring root dates while Google lists the key.
16. *Minor. Revocation-list parsing.* Accepted: decimal and hex keys normalised; 30 days as the oldest cached
    list for a stranger's note; any age for a group member, shown.
17. *Minor. Relay limits against the group use.* Accepted with change: a `GROUP` lifetime of 30 minutes and
    the 15-minute interval dropped (rather than a longer lifetime for everything), "Server busy, retrying", the
    group's size and the shared bucket named in Part 4 and the ADR.
18. *Minor. `SelfSigned` framed as a special weakness.* Accepted: reframed in A5 and put to the owner as
    question 2 with the evidence beside each position.
19. *Minor. The LAN gate's rotation for hour-long groups.* Accepted: the group's own gate without global
    rotation, and the locked-out member's sentence.
20. *Minor. "Exit cost: low" understated; `rkpd` missing.* Accepted: exit cost high, re-keying every install;
    `rkpd` and Google's provisioning backend in the moving parts.
21. *Minor. No falsifiable prediction.* Accepted: two predictions under the recommendation, tied to stage 1a's
    pass rate and stage 1b's battery scenario.
22. *Minor. The z8 width.* Accepted: "a z8 tile, a region of roughly a hundred kilometres".

Count: objection 5 is accepted with one part rejected (RKP-era chains only), the other 21 accepted or accepted
with change.
