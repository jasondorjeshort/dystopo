# 0051. Phone-to-phone records between attested installs, over the LAN link and the relay

Date: 2026-10-30
Status: accepted (the technical decision after the critic; stage 1b's launch waits on the owner's two answers below)

Record: proposal `../proposals/0051-phone-to-phone-transport.md` (T186, wave 23) and
`../proposals/0051-critic-review.md` (0 blocking, 9 serious, 13 minor; all answered in the proposal, one
part of one objection rejected). Prior art: the proposal's survey (vendor servers and satellite services
for group positions, crowd-confirmed reports with expiry, store-and-forward mailboxes, rotating Bluetooth
identifiers, peer attestation in open source, relays kept beside every NAT traversal).

## Context

The owner allowed group positions and trail condition notes "only between copies of the signed app" and
asked whether peer to peer across the internet works with only a minimal central server (2026-10-29,
`docs/ideas/2026-10-29-group-positions-without-signal.md`, `2026-10-29-trail-conditions-phone-to-phone.md`).
The app is open source and ships from GitHub releases, so no secret in the APK can prove a build. The
sealed LAN link and the server relay already move ChaCha20-Poly1305 frames under an 80-bit share code
(T54, T72; PLANNING › Sync, Threat model); the relay refuses any frame purpose but `RELAY`. `shared` has
no elliptic-curve code. Verified in the proposal and by the critic against primary sources: Google's key
attestation page (the first-occurrence rule, five published roots, the revocation list keyed by decimal
and hex serials), the RKP pool and its `ERROR_ATTESTATION_KEYS_UNAVAILABLE`, Play Integrity's sideload
verdicts and server-side decryption, the hole-punching measurements (no cellular figure exists).

## Decision

- **Identity (stage 1a).** Every install holds a hardware-attested EC P-256 **identity key** (Android
  Keystore, StrongBox when present, else TEE), made once while online, that certifies **30-day signing
  keys** in a certification record; records are signed by a signing key. A rotation record signed by the
  old and new identity keys carries pins and mutes to a successor, pre-generated when online within 14
  days of the leaf's end. Verification is offline; generation needs Google's provisioning service on RKP
  phones. Records and the identity record carry a platform tag; the policy is per platform.
- **Policy as data.** A signer list (the release signer and a second release signer registered from the
  first build) and a root list (all five published roots, anchored on public keys, the presented root
  stripped, root dates ignored) in `shared`'s resources, versioned with origin lines, replaceable by an
  update and by a policy file signed by a listed signer. A chain passes with TEE or StrongBox, verified
  boot, a locked bootloader, its first key-description extension only, and no serial on the cached
  revocation list (30 days at most for a stranger's note, any age for a group member, shown); a leaf
  expired within 60 days is *stale*, not refused. Debug builds alone add the debug signer and a test
  root; a test asserts release lists hold neither. Every verdict is shown with reasons and a lift; the
  phone checks itself (Share screen, `GET /identity`).
- **Code shape.** `shared` commonMain: the DER parser, the records, the policy (trust anchors as a
  parameter) and a `Verifier` interface; `jvmCommonMain`: its `java.security` actual. Our own parser,
  with `android/keyattestation` (Apache-2.0) as a test-only oracle at most. Stage 1a's task text, Owns
  line and Done-when are the proposal's "Stage 1a"; **it is buildable now.**
- **Groups and transport (stage 1b).** A share code is a group; a member's identity key is pinned at
  join; positions (`DPOS` v1, pressure fields reserved) are signed and sealed under the code with a new
  frame purpose `GROUP`, over the LAN link when phones share a network and over the relay when online,
  every 1, 2, 5 or 10 minutes (the one setting; its default fixed by a benchmark survey in the task). The
  group's LAN listener has its own gate without global code rotation. Stage 1b's task text is the
  proposal's "Stage 1b".
- **Stage 1b launches only after the owner answers two questions** in `docs/TASKS.md` › Owner actions
  pending: (1) a hard gate, or a "verified" badge with the group creator's "Only verified phones" switch,
  on by default (recommended: the badge with the switch, decided with stage 1a's pass rate); (2) whether
  custom systems with a published, signed boot key list (GrapheneOS) count as signed copies (recommended:
  yes, by allowlist, as a policy-list entry).
- **Server.** The relay gains, in `serverlib/.../RelayRoutes.kt`: acceptance of `GROUP` frames, their own
  lifetime (30 minutes by default, at least twice the longest interval) and side cap (8). Later stages: a
  mailbox per z8 tile for signed public notes (size, signature, attestation, a proof of work, a rate per
  address, a cap per attestation intermediate serial; chains stripped from the feed; no account, no
  merge; several servers may hold the same mailboxes), and longer-lived sealed breadcrumbs.
- **Privacy rules, not settings.** Positions off by default, on per trip, a notification with Stop, an end
  after 12 hours renewable from the notification's "Keep sharing for 12 more hours" 30 minutes before,
  and a final "stopped sharing" record. Notes say what they and their chain reveal; their signing key
  rotates monthly. Breadcrumbs are sealed to contacts only. No flow goes to a server the user did not
  configure.
- **Formats (ADR 0033 listing).** New: `DPOS` v1; the identity, certification and rotation records; the
  sealed-frame purpose `GROUP` (`EncryptedBundle.Purpose`) and the group slot label (`ShareCode`); the
  relay's acceptance of `GROUP`. An older server answers 400 to a `GROUP` frame and the phone says "The
  server runs an older Dystopo; group positions need it updated"; an older record version reads "This
  phone runs an older Dystopo; update both" (§3). No user data migrates; nothing existing changes shape.
- **Later stages, each with its trigger.** Stage 2, notes as signed sync ops with expiry between own and
  paired devices: after stage 1a. Stage 3, notes between strangers (tile mailbox, trailhead exchange):
  after stage 2 and the owner's go on open relay. Stage 4, BLE: after a battery measurement on a real
  phone. Stage 5, breadcrumbs and pressure: after the owner answers those ideas' Questions and, for
  pressure, after the storm warning has run on real hikes. Wi-Fi Aware: a spike when bulk data must move
  between phones. A LoRa radio: its own proposal.
- **Rejected, for the record:** Play Integrity (offline impossible, a closed Play services SDK, verdicts
  that assume Play distribution); the signer fingerprint as proof (a claim; kept as a version check in
  the hello); a web of trust between strangers (does not scale; kept for contacts); hole punching (both
  phones online at once, carrier NATs need a relay anyway, nothing saved on small payloads); Wi-Fi Direct
  (user prompts); a `RELAY`-purpose group frame with an inner type (loses key separation); RKP-era chains
  only for public notes (refuses most current phones; a supersede trigger below).

## Consequences

- The owner's internet question is answered: yes with a minimal server that stores sealed or signed
  blobs under no account, not as direct connections; phone to phone stays the offline path.
- Phones without working hardware attestation, emulators in release mode and rooted phones fail the
  policy (refused or badged per answer 1). A forged chain from a leaked factory key passes until Google
  revokes it, and a revocation hits legitimate phones of the same batch. Attestation keeps out scripts
  and modified builds, not a person with one phone.
- Key generation depends on Google's provisioning service and `rkpd` on RKP phones; a fresh install with
  no signal cannot set up an identity. The root and signer lists must be kept current; leaving the scheme
  means re-keying every install.
- The relay learns addresses, sizes, timing and each group's size; behind a carrier NAT phones share its
  rate bucket.
- Owner actions: the two answers; the second release signer's key, generated and kept offline by the
  owner (the task commits only its fingerprint); running stage 1a's self-check on the owner's and testers'
  phones and stage 1b's battery scenario on the owner's phone.
- What would supersede this: Play-only distribution (server-side Play checks become possible); a pass rate
  under nine in ten (the hard gate goes); BLE measured far cheaper than the relay (BLE first); leaked-key
  forgeries common on non-RKP phones (RKP-era chains only for notes).

Predictions (the trigger scan reads these):
- Prediction, check when stage 1a merges, by 2027-01-31: at least nine of ten tester phones running the
  debug build pass the hardware policy with only the signer check relaxed.
- Prediction, check when stage 1b merges: its `group` scenario at a 2-minute interval costs under a quarter
  of the `tracking` scenario's energy on the owner's phone.
- Prediction, check by 2027-01-31: the owner has answered both questions on the board, and stage 1b has
  either launched or a dated line here says why it still waits.
