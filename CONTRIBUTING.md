# Contributing to Dystopo

Thanks for looking. Two things first:

- **Bug reports, ideas and questions are welcome** as GitHub issues. Screenshots help, but please use a public
  place (a national park, a famous peak) rather than where you live: see [Privacy](#privacy) below.
- **Code contributions are not accepted yet.** The project keeps the option of changing its licence later (for
  example to grant an app-store exception), which is only possible while it has a single copyright holder. Pull
  requests from outside will be welcome once there is a contribution policy (a CLA or a licence exception); until
  then, please open an issue instead. The reasoning is in [docs/PLANNING.md › Licensing](docs/PLANNING.md#licensing).

The rest of this file describes how the project is developed, so the history and the tooling make sense, and so
it is ready for contributors when the policy exists.

## How the work is organised

Most of Dystopo is written by AI coding agents directed by the maintainer. That shapes the workflow, and it works
the same for people:

- **One task, one branch, one working copy.** Work is broken into tasks in [docs/TASKS.md](docs/TASKS.md). Each
  has a goal, an **Owns:** line listing the files it may change, and **Done when:** criteria. Several tasks run at
  once, each in its own git worktree on a branch named `task/<id>`, and the Owns lines keep them from editing the
  same files. A change that needs a file another task owns waits for a later task or becomes a small, listed hook.
- **Debug before you push.** Whoever writes a change also runs it end to end, not just the unit tests: render the
  affected places and look at the PNGs, run the browser check for viewer changes, run the device tour for app
  changes. The pull request says exactly what was run and what was seen.
- **A second pass verifies.** Every pull request is checked by someone (or some agent) other than its author:
  rebase on `main`, run `./gradlew check`, repeat the key runs from the PR description, look at the images,
  and report merge, merge with notes, or fix.
- **Merges are squashed**, and the maintainer records each one in the Phase 0 log in
  [docs/PLANNING.md](docs/PLANNING.md) with what was measured and what is left. [CHANGELOG.md](CHANGELOG.md) is
  the one-line-per-task summary of that log.
- **Design decisions** live in [docs/PLANNING.md](docs/PLANNING.md) and, as records, in
  [docs/design/decisions/](docs/design/decisions/README.md); commands, routes and setup in
  [docs/reference/](docs/reference/README.md); the rules in [CLAUDE.md](CLAUDE.md) (written for agents,
  useful for people).

## Building and testing

JDK 21 is required; the Android pieces also need the Android SDK (`tools/android-sdk.ps1` installs one without
admin rights on Windows).

```
./gradlew check                     # unit tests, golden images, dependency licence check (no device needed)
./gradlew :devtool:run --args="render --area rainier"            # a rendered PNG and a JSON report in out/
./gradlew :devtool:run --args="tour --zooms 9,12"                # world tour: about 27 public places, contact sheet
python -I tools/demo-check.py --views-file tools/demo-tour.txt   # browser demo, headless (Playwright + Chrome)
```

**Tours** are the sanity sweeps everyone runs before pushing a change that could affect what a user sees:

- the **world tour** (`devtool tour`) renders every public area in [areas.json](areas.json), checks elevations
  against known values and writes `out/tour/contact-sheet.png`;
- the **browser tour** (`tools/demo-check.py`) opens the MapLibre demo page at a list of views, fails on console
  errors, failed requests or empty layers, and saves screenshots;
- the **device tour** (`tools/android-emu.ps1 -Build` boots an emulator and installs the app, then
  `python -I tools/android-check.py --views-file tools/android-tour.txt`) drives the phone app through its
  debug server and writes a contact sheet.

Always look at the images: several bugs so far were visible only there.

Rendering changes are guarded by golden images. If `check` fails on them and the change is intended, run
`./gradlew updateGoldens`, look at the new PNGs, and commit them with the change.

## Privacy

This is a public repository, and nothing in it may reveal where the developer (or you) lives or travels. That
covers code, tests, fixtures, screenshots, docs, commit messages and pull request descriptions, and indirect hints
too: nearby place names, lidar project names, elevation tile names.

- Use the public areas in [areas.json](areas.json) (Mount Rainier, Mont Blanc, Everest, the Matterhorn and others)
  or synthetic data. Recorded GPS tracks are never fixtures.
- Personal test areas go in `devtool.local.json`, which is gitignored and stays on your machine.
- Git hooks block commits and commit messages that mention anything listed there (area names, a
  `privateTerms` list, coordinates inside your areas, elevation tile names). Enable them once per clone with
  `git config core.hooksPath tools/git-hooks` (they need Java). **Never bypass them with `--no-verify`**; if one
  fires, change the content.
- The same checker scans the whole history on demand, printing counts only:
  `git log -p --all | java tools/PrivacyCheck.java scan`.
- Pull request text is not checked by the hooks: describe personal areas generically ("a Colorado mountain area").

## Licences

- Dystopo's code is GPL-3.0 ([LICENSE](LICENSE)). Write your own code; do not copy code from other projects.
- **Dependencies must be permissively licensed**: MIT, BSD, Apache-2.0, EPL/EDL and similar. No GPL or AGPL
  dependencies anywhere, since they would lock in the project's licence. LGPL is tolerated only in the build-side
  `tilebuild` module, never in anything shipped to users. `./gradlew check` enforces this (`licenseCheck`;
  configuration in `gradle/licenses/`). An artifact whose POM has no licence needs a line in
  `gradle/licenses/artifact-overrides.txt` with its real licence and where it was verified.
- After changing dependencies, regenerate [NOTICES.md](NOTICES.md): `./gradlew check`, then
  `python -I tools/notices.py` (`--check` tells you whether it is current).
- **Data sources** need their licence and attribution recorded in
  `shared/src/commonMain/resources/sources.json` before they are used; outputs compose their credits from it.

## Releases

Pushing a `v*` tag runs [.github/workflows/release.yml](.github/workflows/release.yml): per-ABI APKs (signed with
the release key from repository secrets, or debug-signed and labelled so when there is none), the server and
devtool distributions, NOTICES.md, and tour contact sheets, attached to a draft GitHub release that the maintainer
reviews and publishes.
