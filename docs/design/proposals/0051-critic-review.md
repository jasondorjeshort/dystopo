# Critic review of proposal 0051: the phone-to-phone transport between signed copies

Date: 2026-10-30
Status: review of the proposal as of commit 4db56b5 on `task/T186`
Reviewer: critic (Fable 5.1), read-only on the code in the T186 worktree; nothing run, no Gradle, no device.
Primary sources: the code the proposal names (`sync/crypto/SealedLink.kt`, `ShareCode.kt`, `Relay.kt`,
`EncryptedBundle.kt`, `serverlib/.../RelayRoutes.kt`, `app/share/ShareGate.kt`, `RelaySession.kt`, `ShareNsd.kt`,
`update/Signing.kt`, the manifest, `AppSettings.kt`), `docs/SERVER.md` › The relay, PLANNING › Sync, Threat model,
Peer sources on a trip, Licensing and Design goals, ADRs 0023, 0024, 0025, 0031, 0033, 0044, the four idea files,
the T186 entry, and about thirty pages fetched (Google's attestation and Play Integrity pages, the live revocation
list, the AOSP schema and the soft-keymaster source, the `android/keyattestation` build file, the GrapheneOS
attestation guide, the WebAuthn list, Apple's forum thread on Wi-Fi Aware, the Tailscale and probelab posts, the
licence pages of every library named).

Context given with the brief and taken as settled: the owner's words of 2026-10-29 ("yes, but only between copies
of the signed app"; the internet question); live positions and relayed notes are flagged flows; GPL and AGPL
anywhere, LGPL outside `tilebuild`, and Wi-Fi gates are ruled out and not re-argued here.

## Part 1. Claims checked

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | Two roots: the legacy RSA root (to 2042) and a new root signing chains from 2026-02-01; the list as JSON at `/attestation/root` | Confirmed, incomplete | Google's page lists the RSA root (decoded validity 2022-03 to 2042-03) and a second root (2025-07 to 2035-07, P-384 by inspection; the page never says "ECDSA") "that will begin signing attestation certificate chains on February 1, 2026", plus three *previously issued* roots (2016 to 2026-05-24, 2019 to 2034, 2021 to 2036). Factory chains on older phones present one of those; one of them expires on 2026-05-24. The verifier must bundle the whole published array and anchor by public key, not validate the device-presented root's dates (objection 15). |
| 2 | Revocation is a JSON list of serials at `/attestation/status`, refreshed per `Cache-Control` | Confirmed, with a format wrinkle | The docs say entries are keyed by "the certificate serial number in lowercase hex" with `status`, optional `expires`, `reason`, `comment`. The live file (fetched, 180 kB, over a thousand entries) keys most entries by *decimal* strings up to 20 digits and some by 30 to 32 hex characters, with `status` and `reason` only (`KEY_COMPROMISE`, a few `SOFTWARE_FLAW`). A parser that compares hex serials to the keys as published misses most of the list (objection 16). |
| 3 | Only the first occurrence of the extension may be trusted; the level must be TEE or StrongBox | Confirmed | Quoted on Google's page: "Only the first occurrence of the extension in the chain can be trusted"; hardware trust requires `attestationSecurityLevel` of `TrustedEnvironment` or `StrongBox`. |
| 4 | RKP optional on 15, mandatory for devices launching with 16; short-lived certificates; leaked factory keys do not affect RKP | Confirmed, and it cuts the other way too | Google: "For devices that launch with Android 16, the system supports only RKP"; RKP certificates have "a shorter expiration period" whose validity "must still be checked". An Android attestation engineer on the WebAuthn list (2023-04): "The certificates are short-lived (~30 days)" and "Each app on the device that uses key attestation will get assigned one of the key pairs". `KeyStoreException.ERROR_ATTESTATION_KEYS_UNAVAILABLE` (API 33): on devices relying solely on RKP, key generation with a challenge fails when "the pool of remotely-provisioned keys has been exhausted" or the device is not registered; transient in the first case. Attested key *generation* therefore needs Google's provisioning server to have run recently (objection 1). |
| 5 | The application id is supplied by the OS, so `SELF_SIGNED` systems could lie | Confirmed as a fact, misframed | The AOSP schema puts `attestationApplicationId` under `softwareEnforced`: the framework fills it on *every* device, stock included; what makes it trustworthy is verified boot. A locked GrapheneOS reports `SelfSigned` with its own `verifiedBootKey`; GrapheneOS publishes those keys as signed JSON (`attestation.json` with an `allowed_signers` file) and recommends accepting `Verified` or `SelfSigned` with the key pinned. Trusting that list is the same kind of trust as trusting a stock OS (objection 18). |
| 6 | Batch keys shared by many devices; no serial or IMEI without ID attestation | Confirmed from secondary and primary sources | Google's 2019 security blog: OEMs shipping 8.0 or higher "must install a batch attestation key provided by Google"; the batch size (at least 100,000 devices) appears in community and FIDO sources, not on a Google page. The AOSP page: serial, IMEI and MEID appear only through ID attestation (`CANNOT_ATTEST_IDS` otherwise). The chain does carry `verifiedBootKey`, `verifiedBootHash`, OS version and the three patch levels, and on pre-RKP phones the batch intermediate's serial: a quasi-identifier the proposal's privacy row omits (objection 5). |
| 7 | Play Integrity: sideloads get `UNRECOGNIZED_VERSION` or `UNLICENSED`; decryption on Google's servers; 10,000 a day; the SDK is not permissively licensed | Mostly confirmed | Verdicts page: `UNRECOGNIZED_VERSION` is "The certificate or package name does not match Google Play records"; `UNLICENSED` names sideloading explicitly. Standard page: "you must decrypt the integrity token on Google's servers" (`decodeIntegrityToken`). No daily quota on the cited pages (not contradicted; not found). The terms page is a terms-of-service (Google APIs ToS, a tracking prohibition, quota at Google's discretion), not a software licence; the SDK is a Play services artifact. The rejection stands on offline, distribution and the closed SDK; the licence sentence should be reworded (objection 13). |
| 8 | `android/keyattestation` pulls Guava, protobuf-lite, Gson, a CBOR library and Bouncy Castle, JDK 21 | Confirmed | `build.gradle.kts`: `guava:33.5.0-jre`, `protobuf-javalite` and `protobuf-kotlin-lite` 4.28.3, `gson:2.11.0`, `co.nstant.in:cbor:0.9`, `bcpkix-jdk18on:1.78.1`, `kotlinx-coroutines-guava`, toolchain `JavaLanguageVersion.of(21)`, Apache-2.0, 112 commits, no tagged release visible. Its default verifier fetches the revocation list from the web on each call; roots come from a mirrored `roots.json`. |
| 9 | Emulators produce a software-level chain | Confirmed by source | AOSP `system/keymaster/contexts/soft_attestation_cert.cpp` embeds the EC chain `Android Keystore Software Attestation Root` → `... Intermediate` *with the private keys in the source file*. A debug policy that trusts that root trusts a key anyone holds; the proposal's rule that release builds carry no debug policy is load-bearing, not a nicety. |
| 10 | Hole punching: Tailscale "over 90%", libp2p about 70%, 82.9% port-restricted, 39.7% symmetric | Partly confirmed | The Tailscale figure is the author's estimate ("I'd estimate you could get a direct connection over 90% of the time"), not a measurement of its users. DERP "our fallback of last resort": confirmed. probelab: 82.9% (3,042 of 3,669) and 39.7% (213 of 536, flagged as doubtful) confirmed; the 70% ± 7.1% is the IMC measurement paper's, and the post's own table sums to about 59%; host types unstated. The conclusion (a relay is needed anyway) survives; the attributions do not (objection 12). |
| 11 | The relay: in memory, 15 min after the last write, 4 sides, 120 requests a minute per address, open, slot an HKDF of the code | Confirmed | `Relay.Config` defaults 900 s, 16 MiB a blob, 256 MiB total, 10,000 slots, 4 sides, 120 a minute (token bucket, bursts to the cap, `Retry-After: 10`); `X-Forwarded-For` believed only from loopback or a private address; `/status` returns counts only. It sees addresses, sizes, timing and which sides share a slot (PLANNING › Threat model). Two things the proposal misses: `handle` refuses any body whose purpose is not `RELAY` with 400 `notAFrame`, so a new sealed-frame purpose for positions is refused by today's server (objection 14); and a 15-minute TTL equals the proposal's longest interval choice (objection 17). |
| 12 | Battery figures | Not established; the citations are weaker than stated | Radius Networks (Nexus 5, 5.0): the page states 268 mA and 454 mA, but its own data (13% of 2,300 mAh in 134 min; 25% in 152 min) gives about 134 and 227 mA; the ratio holds, the absolutes are doubled. arXiv 2005.05625 is "How Reliable is Smartphone-based Electronic Contact Tracing for COVID-19?"; its abstract carries no battery figures and the PDF could not be read here, so the 3/5/20 percent model is unverified. The 3% in 24 h is a vendor's claim reported in the press (the New Zealand app's developer), with forum reports of far higher drain. The LTE tail numbers are from a lecture summary (which says 1,080 mW, not "about 1 W"), the paper itself not read. The proposal's "what they settle is the order" is not settled by these (objection 11). |
| 13 | Licences: ice4j, jvm-libp2p, Bouncy Castle, the LoRa mesh firmware, Briar, Nearby Connections | Confirmed | ice4j Apache-2.0; jvm-libp2p "Dual-licensed under MIT and ASLv2"; Bouncy Castle "released under the MIT license"; the mesh firmware GPL-3.0 (its default position broadcast "Default of 0 is 15 minutes", smart broadcast on); Briar GPL; Nearby Connections is a Play services API. |
| 14 | Wi-Fi Aware on iOS 26 and Android interop | Confirmed rough | Apple's forum thread: Apple requires Wi-Fi Aware 4.0 pairing; among tested Android phones only one flagship reported `isAwarePairingSupported`, and even there pairing and the data path failed; several feedback reports open; no background behaviour discussed. |
| 15 | Hotspot clients reach each other | Supported, untested | Android's local-only hotspot API exists precisely "to enable applications on devices connected to the Wi-Fi hotspot to communicate with each other"; AOSP's soft AP has MAC allow and block lists and no client-isolation switch. A two-emulator run cannot test it; a two-phone run at stage 1b can. |
| 16 | Key attestation mandatory from 8.0 on devices with Google Play; minSdk 26 | Confirmed, with the gap named | Google's 2017 blog: mandatory "for all new devices that ship with Google Play". Devices launched on 7.x and upgraded, devices without Play certification, and (GrapheneOS's guide) "some low quality devices [that] shipped broken implementations" fail; Play Integrity still passes some of those. No share figure exists anywhere I could find. |
| 17 | The share screen's listener and code | Confirmed | `ShareGate`: the code is new per gate; five wrong codes from one address lock that address for ten minutes; fifty from all addresses together rotate the code (`GLOBAL_FAILURES = 50`). A group session that holds the LAN listener for hours inherits that rotation (objection 19). |
| 18 | No elliptic-curve code in `shared` | Confirmed | `grep` of commonMain finds none; PLANNING › Threat model says so ("elliptic-curve arithmetic we do not have yet"). Signing and verifying P-256 records is not on the proposal's cost list (objection 8). |

Also checked: the manifest already declares `FOREGROUND_SERVICE_LOCATION` and a `location` service (T159), so stage 1b's service type is in reach; `AppSettings.defaultServer()` is empty in release and the host's live viewer only on a debug emulator, so "no flow ever goes to a server the user has not configured" holds for release; `RelaySession.post` throws on any non-2xx and the session records the exception's text, so a 429 or 507 reaches the user as a generic error today.

## Part 2. The case against A5 as the gate, and for the best alternative

The owner's rule is "only between copies of the signed app". The proposal reads it as a hardware gate at every
receiver, and that reading is where the cost lands. Consider what the gate buys per flow:

- **Groups.** The 80-bit code already decides who is in. A modified build that holds the code can read and inject
  positions; attestation refuses it. But the threat the owner most plausibly means, a stranger or a script, never
  had the code. What the gate adds against a *member* who runs a modified build is protection against a person the
  user invited. Meanwhile the gate refuses a member whose stock phone has broken attestation, a revoked batch key, an
  unlockable bootloader left unlocked once, a custom OS, an old phone, an iPhone until App Attest lands, or a phone
  that has been offline past its RKP pool. That person is on the hike. They are refused silently (Part 3, R1).
- **Notes between strangers.** Here the gate is the "main lock", and it is weaker than the proposal says, because
  attestation proves the *binary*, not the *behaviour*, and it does not bound identities: the signed app on a locked
  phone can generate as many attested keys as it likes, each a fresh key id, so "a rate per key" and "mute this key"
  cost a spammer nothing (objection 3). What stops bulk spam is the rate per address and the phone's own cost of
  posting; what stops lies is the crowd's confirmations and the expiry; both work with or without attestation.
- **Breadcrumbs.** Sealed to contacts (A4). Attestation adds nothing a recipient needs.

So the strongest case against the recommendation: A5 pays most where it matters least (groups), is a partial lock
where the proposal calls it the main one (notes), binds the identity scheme to Google's provisioning service and
root rotation and to one release signer, and refuses legitimate users with no lift. **The best alternative keeps
A5 and changes its role:** every install still holds an attested key where the phone can make one, every record is
still signed, every receiver still verifies and *shows* the verdict ("verified phone" or the reason it is not), and
the policy decides per flow who may lift the gate: for a group, the group's creator on the Share screen ("Only
verified phones", on by default as the owner asked, visible and switchable while the group runs); for notes between
strangers, the server operator's policy (attested only, as the owner asked) and the phone's own list rule for the
trailhead exchange; for breadcrumbs, the contact's pin. That keeps the owner's default, gives every refusal a lift
and a message, keeps iOS and a future JVM peer in the same record format, and turns a scheme-wide failure (Google
revokes a batch, a root rotates, the release key is replaced) from a silent blackout into a badge that goes grey.
The owner may still choose the hard gate; then the proposal must say what a refused hiker sees and who can let
them in, which today is nobody (Part 3).

## Part 3. Refusals and who lifts them

| | Refuses | Lift, from where, while running | Message says so? |
|---|---|---|---|
| R1 | The release policy refuses a peer's chain (software level, unlocked or unverified boot, `SelfSigned`, unknown root, expired, revoked serial, another signer) | Nobody. The refusal is counted on the *receiver's* `GET /group`; the refused phone learns nothing; the owner can add an allowlist "later". | No message on either phone. **Objection 2.** |
| R2 | Attested key generation fails (`ERROR_ATTESTATION_KEYS_UNAVAILABLE`, no RKP pool, a phone never online) | The network, when it returns; nothing the user can do at the trailhead. | Not designed. **Objection 1.** |
| R3 | A chain past its validity (RKP leaf about 30 days; a member offline longer than the renewal) | Renewal, which needs the network (R2). | Not designed. **Objection 1.** |
| R4 | The 12-hour automatic end | The user, by renewing on the Share screen, if they notice. | The notification disappears; the group sees a dot age with no cause. **Objection 6.** |
| R5 | Relay 429 (120 a minute per address) and 507 (sides, slots, bytes) | The server operator in config; the phone backs off only if the group client is written to. | Today's client surfaces the exception text; the proposal does not say what the group screen shows. Objection 17. |
| R6 | The LAN gate rotates the code after fifty wrong frames in ten minutes | The sharer, by re-pairing everyone. | The screen shows a new code; the group's members see the link die. Objection 19. |
| R7 | Notes mailbox: size, signature, attestation, rate per key and per address, expiry | The server operator; the author of a refused note gets what status? | Not designed. Objection 4. |
| R8 | A newer frame purpose against an older server (400 `notAFrame`) or an older phone | Update both. | Needs ADR 0033 §3's sentence; not written. Objection 14. |

## Part 4. Objections

1. **Serious. Attested key generation and renewal are not offline; the proposal's "offline" claim covers
   verification only.** Evidence: claim 4. On RKP-only phones (every phone launching with 16, many since 13) a key
   with a challenge comes from a pool Google's server fills; an exhausted pool or a never-registered phone fails
   with `ERROR_ATTESTATION_KEYS_UNAVAILABLE`; RKP certificates live about 30 days and "must still be checked". The
   proposal then *adds* a 30-day renewal and a monthly pseudonym rotation. A fresh install at a trailhead with no
   signal cannot make its key; a party three weeks into a traverse crosses the renewal with no network; a verifier
   that checks validity refuses the member mid-trip. Resolve: generate once on first launch while online and keep
   the key for as long as the chain allows; pre-generate the successor whenever the phone is online and the chain is
   within two weeks of its end; a receiver accepts a chain whose leaf expired within a grace window (say 60 days)
   and marks it stale rather than refusing; the pseudonym rotation for public notes becomes a separate per-month
   *signing* key certified by the long-lived attested key (so rotation needs no provisioning). State plainly in the
   proposal which steps need Google's provisioning server and when.
2. **Serious. A refused legitimate phone has no lift and is never told.** Evidence: Part 3 R1; claims 5, 16; the
   GrapheneOS guide on broken OEM implementations and on Play Integrity passing devices key attestation refuses; the
   2024 to 2026 keybox leaks, where Google's revocations hit batches that legitimate phones share (press and forum
   reports, no official tally). Stage 1a has the phone verify *other* phones' chains; it never has the phone verify
   its own against the release policy and say "This phone cannot share its position with a group: <reason>" before
   the trip. Resolve: a self-check on the Share screen and in `GET /identity` with the reasons list; the verdict
   shown to the refused user with the lift (update, relock, or "this phone's maker shipped broken attestation");
   and the decision put to the owner with the numbers stage 1a gathers (how many of the testers' phones pass):
   hard gate, or the verified badge with a group-creator switch (Part 2). Until the owner answers, the ADR's
   Consequences line "cannot take part" is not enough.
3. **Serious. Attestation does not bound identities, so the anti-spam story for notes is hollow.** Evidence: a
   signed app on a locked phone may generate any number of attested keys; RKP assigns a pool key per app, not one
   key per app for life (claim 4); the proposal rate-limits "per key and per address" and lets users confirm,
   contradict and (the idea) mute by key id. Resolve: say what bounds a single phone: the rate per address at the
   mailbox, a per-tile cap per attestation intermediate serial (which *is* per device on RKP phones and per batch
   before), a cost per post (size, signature, a small proof of work), and the crowd rules; and drop "a rate per
   key" as a defence. The owner should hear that attestation keeps out scripts on servers, not a person with one
   phone and patience.
4. **Serious. Key continuity across rotation is undefined.** Evidence: "a member's key is pinned at join" and "the
   key and its chain are renewed every 30 days" with no successor link, no re-pin flow, no rule for mutes and
   confirmations across the boundary. A group whose trip spans the rotation day loses the member; a muted spammer
   is unmuted monthly by design. Resolve: a long-lived attested identity key that certifies short-lived signing keys
   (objection 1's structure); pins bind to the identity key; a rotation record signed by both keys travels with the
   first record under the new key; the mute follows the identity key.
5. **Serious. The published chain is more identifying than "the OS patch level".** Evidence: claim 6. The leaf
   carries `verifiedBootKey`, `verifiedBootHash`, OS version and three patch levels; a pre-RKP chain carries a
   batch intermediate whose serial is stable for the device's life and shared with a batch; a note author thus
   publishes a stable quasi-identifier that survives the monthly key rotation, together with where and when they
   were. Resolve: the payload table and the note's privacy sentence name it; public notes prefer RKP-era chains
   (per-app, per-month, no batch) and on pre-RKP phones the app says what the chain reveals before the first post;
   the server's tile feed strips chains it has already verified and serves a verdict plus key id to readers (the
   trailhead exchange still carries chains); consider whether readers need the chain at all when the server has
   verified, against the design goal that phone to phone is the offline tail.
6. **Serious. The 12-hour automatic end fails closed at the worst moment and tells nobody.** Evidence: Part 3 R4;
   the wave's theme is the long mountain day. A twelve-hour hike that runs late is exactly when a party wants the
   positions; the proposal ends them with no warning, no group-side signal and no lift other than noticing. Resolve:
   a notification action thirty minutes before ("Keep sharing for 12 more hours"); a final signed record that says
   "stopped sharing" so other members see an end, not an ageing dot; the limit a privacy rule still, but with the
   renewal in reach without opening the app; say in the ADR who chose 12 and why.
7. **Serious. One release signer and one bundled root list, with no rotation path.** Evidence: the policy names
   "the release signer" (one fingerprint) and a root list "updated when Google rotates"; ADR 0024 says the key never
   changes, which is a promise about the updater, not a guarantee against loss or compromise; claim 1 shows Google
   has issued five root certificates and will sign with a new key from 2026-02-01, and an alpha that stops updating
   keeps its old list forever. Resolve: the policy is a *list* of accepted signers and a list of roots, both data
   (versioned, with an origin line), both replaceable by an update and by a signed policy file the app can fetch;
   a second release signer registered from day one even if never used; the verifier anchors on root public keys
   and strips the device-presented root (objection 15).
8. **Serious. Hidden coupling to Android, and a missing primitive.** Evidence: the ADR draft's Decision 1 names the
   Android Keystore; iOS's analogue (App Attest, iOS 14 and up, no simulator) chains to Apple's root, binds to team
   and bundle id, has a counter and *no* verified-boot or bootloader fields, so the "release policy" cannot be one
   policy; a JVM peer (the server, the devtool) can hold no attested identity at all, against PLANNING's "the server
   is just an always-on peer" (acceptable for positions, but say it); and `shared` has no P-256 code (claim 18), so
   either a pure-Kotlin ECDSA verify (several hundred lines plus vectors, or Ed25519 if the platform keystores
   allowed it, which Android's does not for attested keys) or an `expect`/`actual` enters the cost list, and the
   three-member commonTest simulation needs a signer interface. Resolve: `DPOS` v1 and the identity record carry a
   platform and chain-format tag from the first byte; the policy is per platform with the fields it can check;
   `shared` gets the DER parser, the policy and a `Verifier` interface, with the JVM and Android actuals doing the
   curve arithmetic; the cost line says so.
9. **Serious. The release policy's acceptance path is not agent-drivable as written.** Evidence: Done-when for
   stage 1a tests refusals from mutated emulator chains and acceptance only on the owner's phone; a TEE chain
   fixture captured from that phone would commit its model's boot key, boot hash and patch levels (a device
   description, borderline under the privacy rule). Resolve: the policy takes its trust anchors as a parameter, so
   tests sign synthetic chains under a test root and exercise every accept and refuse branch on the JVM and on two
   emulators; the owner's phone becomes a confirmation, not the only acceptance test.
10. **Minor. The legacy BLE arithmetic is wrong.** 31 bytes less the flags record (3) and a 16-bit service-data
    header (4) leaves 24; the proposal's unsigned position is 24 bytes before the 16-byte recognition token T78
    designed and before any seal (a 16-byte tag). Nothing sealed fits a legacy advertisement; extended advertising
    or a GATT read is needed for every variant, which the proposal half says. Correct the row.
11. **Minor. The battery citations do not carry the sentence built on them.** Claim 12: the Radius absolutes are
    doubled against their own data, the arXiv paper's scan-mode percentages are unverified and the paper is about
    tracing reliability, the 3% is a vendor's claim, the LTE numbers are second hand. Keep the table, mark each
    row's standing, and replace "what they do settle is the order" with "nothing here settles the order; the stage
    1b measurement does".
12. **Minor. Hole-punching attributions.** Claim 10: Tailscale's figure is an estimate, probelab's 70% is another
    paper's and its own table sums near 59%; neither names phones. The conclusion stands; fix the sentences.
13. **Minor. Play Integrity's "licence".** Claim 7: the page is terms of service; the honest line is "a closed Play
    services SDK under Google's API terms, decrypted on Google's servers, with verdicts that assume Play
    distribution"; drop the quota number or cite its page.
14. **Minor. "Nothing new for the server except a larger side cap" is false.** Claim 11: `RelayRoutes` refuses any
    body whose purpose is not `RELAY`. The position frame needs either the `RELAY` purpose with an inner type or a
    server change that accepts the new purpose, and the older-server case needs ADR 0033 §3's readable refusal on
    the phone. Stage 1b's Owns line already includes `RelayRoutes.kt`; the proposal's text should match.
15. **Minor. Root handling.** Claim 1: bundle the whole published array (five roots), anchor on public keys, strip
    a self-signed root the device presents, and say what happens to a phone whose factory chain roots in the
    certificate that expires on 2026-05-24.
16. **Minor. Revocation-list parsing.** Claim 2: match serials as the live file keys them (decimal and hex), not
    only as the schema describes; the list is over a thousand entries and growing, so size the bundled copy and
    the fetch; say what age of cached list is "too old" to accept a stranger's note (the server has a fresh one;
    a phone may not).
17. **Minor. Relay limits against the group use.** Claim 11: the 15-minute TTL equals the longest interval choice,
    so a 15-minute poster's blob is gone before the next poll about half the time; raising the side cap makes the
    group's size visible to the server as "addresses sharing a slot"; behind a carrier NAT the 120-a-minute bucket
    is shared by every phone on that address; the current client surfaces 429 and 507 as exception text. Resolve:
    TTL at least twice the longest interval for the group slot; the group screen shows "server busy, retrying";
    the ADR's Consequences names the group size as something the relay learns.
18. **Minor. `SelfSigned` is framed as a special weakness.** Claim 5: the application id is software-enforced on
    every device; trusting a published GrapheneOS boot key (signed JSON, downgrade-protected by timestamp) is the
    same shape of trust as a stock OS under Google's root. Present "default: no" as the owner's choice with that
    evidence beside it, not as a security finding.
19. **Minor. The LAN gate's rotation rule and lockouts were designed for a screen open for minutes, not a group
    running for hours.** Claim 17: fifty wrong frames in ten minutes from any addresses rotate the code; one
    member with a stale code and a tight retry loop, or a joker on a hut's Wi-Fi, can cut the group's LAN path.
    Resolve: the group's listener takes its own gate instance with no global rotation (the relay path is unaffected
    either way), and the report says what a locked-out member sees.
20. **Minor. "Exit cost: low" for A5 is understated and the moving parts omit the runtime dependency.** Every pin,
    every note in circulation, the server's policy and the iOS plan hang on the scheme; the moving parts include
    `rkpd` and Google's provisioning backend at *key generation time*, not only the root and revocation lists. Say
    so; a reader of the ADR should see that the project cannot leave A5 without re-keying every install.
21. **Minor. No falsifiable prediction.** The proposal and the ADR draft carry a "what would change" list but no
    dated prediction. Add two that can fail: by the end of stage 1a, at least nine of ten tester phones (debug
    build, release policy relaxed for the signer only) pass the hardware policy; stage 1b's `group` scenario at a
    2-minute interval costs under a stated fraction of the `tracking` scenario on the owner's phone.
22. **Minor. The notes mailbox's z8 tile ("about 150 km across") is a latitude-dependent figure** (about 156 km at
    the equator, about 100 km at 50°); say "a z8 tile" and let the privacy sentence say "a region of roughly a
    hundred kilometres".

## Part 5. Pre-mortem

Ten years on, the regret is that the app's social layer became a Google-attested club. The gate did what it said:
it refused forks, F-Droid builds (signed with F-Droid's key), custom systems, old phones, phones whose batch keys
Google revoked after a leak, and, for two years, every iPhone; each refusal was silent, because the design counted
refusals on the receiver and never told the refused. Google retired factory keys, then rotated roots again, then
changed RKP's validity; each time, the alphas that had stopped updating went dark for each other, and the policy,
baked as one signer and one root list in every APK, could not be told otherwise. When the release key had to be
replaced, nothing in circulation could be trusted by anything new. The spam the gate was meant to stop came from
patient people with one phone each, generating fresh keys. The project then built what it should have built first:
signed records with a verdict shown, a lift per flow, and a policy that was data. The line that should have caught
it: "Exit cost: low; the key and chain are per install and per month" under A5, read beside "the release signing
key never changes" in the constraints, which together promised that nothing about the scheme would ever need to
move.

## Recommendation

Keep A5's machinery (the attested install key, the DER parser in `shared`, the chain check behind an interface,
the verdict on a debug route) and keep A4 for people, but promote the ADR only after the proposal (a) separates
the long-lived attested identity from short-lived signing keys and states which steps need Google's provisioning
server and what a phone offline past them does (objections 1, 4); (b) gives every refusal a message and a lift,
with the hard gate versus the verified badge put to the owner as a question with stage 1a's pass rate attached
(objection 2); (c) rewrites the anti-spam and privacy rows for notes to what attestation does and does not give
(objections 3, 5); (d) makes the policy data (a signer list, a root list, per-platform fields) and tags the
record format by platform (objections 7, 8); and (e) fixes the server sentence, the BLE arithmetic and the
citations (10 to 17). Stage 1a is right to build first and can start on that revised text; stage 1b should wait
for the owner's answer on the gate, since what a refused member sees is a flow the owner has not seen. The relay-
first, hole-punching-never transport answer is right and I have no objection to it; the internet answer to the
owner is right. Confidence: high that the objections are real and the fixes are text and small design changes
rather than a different direction; moderate on how many phones the hard gate would refuse, because nobody
publishes that number and stage 1a is the first chance to measure it.

Residual risks if every objection is taken: leaked keyboxes pass until revoked (accepted in the proposal);
batch-key phones remain linkable across months in public notes; the BLE stage still rests on a measurement nobody
has made on a current phone; and Google's provisioning service remains a dependency at key generation time that
no setting removes.
