---
date: 2026-11-18
kind: chore
---

# Require Java 25 on the JVM side and use what it brings

The owner wants the project on Java 25: the JDK it requires, the toolchain the JVM modules build
with, and code that uses the newer features, virtual threads first. Today every JVM module sets
`jvmToolchain(21)` (`devtool/build.gradle.kts`, `server/build.gradle.kts`,
`serverlib/build.gradle.kts`, `tilebuild/build.gradle.kts`, `shared/build.gradle.kts`), and
`CLAUDE.md` and `docs/reference/machines.md` say JDK 21 is required.

Where it applies: the JVM side (devtool, the servers, tilebuild, the shared core's JVM target).
Android runs on ART, not a JDK: `androidApp` and the shared Android target stay on their
`JVM_17` bytecode target whatever the build JDK is.

Candidates for the features, to be confirmed in the code:
- Virtual threads for the servers' request handling and for the fetches that wait on the network
  (`serverlib`, the live server's tile and DEM fetches). Virtual threads are final since Java 21;
  from Java 24 a thread blocked inside `synchronized` no longer pins its carrier (JEP 491), which
  is what makes them safe to use widely.
- Scoped values (final in 25) in place of thread locals where a request carries context.
- Structured concurrency where a request fans out to several sources (still a preview in 25).

## Open

- Whether the Kotlin, Gradle and Android Gradle Plugin versions in use accept a JDK 25 toolchain,
  or which upgrades that needs; the same for Planetiler in `tilebuild` and the release workflow's
  JDK in `.github/workflows/`.
- Whether to run the build on JDK 25 while some modules keep a lower bytecode target, or move
  every JVM module's target to 25.
- How the servers' threading is done today, and where a virtual-thread executor is a measured gain
  (the live server under the demo check's load) rather than a rewrite for its own sake.
- Both PCs' JDK installs and `docs/reference/machines.md`: this PC has Adoptium 21 only; the doc
  says the other has jdk-25 as its default `java`.

Question: should the JVM modules (devtool, server, serverlib, tilebuild, the shared core's JVM target) move their
bytecode target to 25, so anyone running the standalone server or the Docker image needs Java 25, or should the build
run on JDK 25 with the targets left at 21? Recommended: target 25 everywhere on the JVM side, since scoped values
(final in 25) and the unpinned virtual threads (JEP 491, Java 24) are what you asked for and a target of 21 cannot use
the first; Android stays at `JVM_17` either way. Build it in two passes: first the toolchain (`jvmToolchain(21)` in
the five build files, the Gradle wrapper, which is 8.14.3 and needs a release that runs on 25, the Kotlin and Android
Gradle Plugin versions that accept it, Planetiler, the release workflow's JDK and the Dockerfile, JDK 25 installed on
this PC, `machines.md` and the CLAUDE.md line), then virtual threads in `serverlib` where `demo-check.py`'s load
shows a gain. Installing JDK 25 on this PC is a machine change: say whether an agent may do it.

Answer: (the owner, 2026-11-20) "I meant yes to both Java answers": target 25 on every JVM module, built in the two
passes above, and an agent may install JDK 25 on this PC.
