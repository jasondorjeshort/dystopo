# 0024. Publishing: an alpha after every green sweep on GitHub Releases; beta on Google Play

Date: 2026-10-10 (publishing), 2026-10-11 (universal APK only, beta path)
Status: accepted (owner's policy)

Record: the "Publishing (owner's policy, 2026-10-10)" paragraph that was in `CLAUDE.md` (now the
`release` skill, `.claude/skills/release/SKILL.md`); `docs/TASKS.md` › Owner actions (releases, beta
path); the release entries in the `docs/PLANNING.md` log (v0.1.2-alpha to v0.1.4-alpha); T55 (release
build), T81 (install from the draft), T83 (in-app updates).

## Context

The owner wants regular public builds without being asked each time, a phone that updates itself
from them, and, past alpha, installs without the "unknown publisher" warning. Sideloaded APKs from
GitHub need a Play Protect "More details › Install anyway" tap on first install; Google's developer
verification for sideloaded apps and the Play Console share one identity check.

## Decision

### Alpha (now)

- **Cadence.** The coordinator publishes an alpha from `main` at the end of every wave once the
  post-wave sweep is green, without asking the owner again. Tag `v0.<minor>.<patch>-alpha`; the
  patch bumps per wave, the minor when the owner calls a milestone.
- **Channel.** GitHub Releases only, as a pre-release. Alpha releases ship only the universal APK
  (owner, 2026-10-11); per-ABI files return at beta or once a server picks the ABI. Phones update
  through the in-app updater (T83), which checks `version.json` and `SHA256SUMS.txt` and the signing
  certificate.
- **Version strings are derived, never typed.** The version name is the tag and the version code the
  commit count (`release.yml`, or the same Gradle properties in a local build), so every build updates
  over the last.
- **Verified from the draft itself.** The workflow builds a draft; an agent downloads the APK from the
  draft (never a local build) and checks the certificate (`CN=Dystopo release`, fingerprint
  `47:31:38:10...`), the version and the checksums, then installs it from the draft on a fresh
  emulator (upgrade over the last published release, the feature pass, the string scan) and, when a
  phone is connected over USB, on the phone. Only then is the draft published. Notes are written by
  hand from the CHANGELOG section and scanned with `PrivacyCheck`.
- **One key forever.** The release key (RSA 4096, made 2026-10-10) lives only on the owner's
  machine and in the `RELEASE_*` Actions secrets; it never changes, because a phone only updates an
  app signed by the same key.
- **Fallback.** When Actions cannot run (2026-10-11, billing), an agent builds the same release
  locally from a detached worktree at the tag and uploads it as a draft; verification is unchanged.

The steps are the `release` skill.

### Beta (decided 2026-10-11)

- Google Play, starting with closed testing, under one developer registration the owner makes once
  (a Play Console account, a one-time fee per developer; its identity check also covers Google's
  developer verification for sideloaded installs). Register the package
  `io.github.jasondorjeshort.dystopo` and the release certificate under it.
- Our own key stays the upload and app-signing key under Play App Signing; automatic updates come
  from Play; the unknown-publisher warning disappears as a side effect.
- Sideloading from GitHub is a short-lived alpha arrangement, not the long-term channel.

## Consequences

- Every wave ends with a public build, so a defect the sweep misses reaches alpha users within a wave;
  the install-from-draft step and the updater's checks are the guard.
- The registration is an owner action on the board; until it is done, alphas stay on GitHub.
- A superseded draft is deleted together with its tag; the release is recorded in the planning log
  and the README status line.
- Supersede when the owner changes the channel or the cadence (for example at beta).
