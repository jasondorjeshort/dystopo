plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Android (T10): on the build classpath for every module; applied only when an SDK exists (settings.gradle.kts).
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Dependency license check (docs/PLANNING.md > Licensing, hard rule 3): every module gets `licenseCheck`
// (buildSrc/src/main/kotlin/dystopo.license-check.gradle.kts, config in gradle/licenses/), run by its
// own `check` and aggregated under the root `check`.
subprojects {
    apply(plugin = "dystopo.license-check")
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:licenseCheck" })
}

// T104: user-visible text names no other mapping software (CLAUDE.md > Rules): tools/user-text-check.py over the
// string literals of the app, devtool, server and shared sources, the app's string resources and the browser viewer;
// the names are in tools/user-text-names.txt. Needs Python 3 (`python` on Windows, `python3` elsewhere).
val userTextCheck = tasks.register<Exec>("userTextCheck") {
    group = "verification"
    description = "Fails when user-visible text names another mapping product (tools/user-text-names.txt)."
    val report = layout.buildDirectory.file("reports/user-text-check.json")
    inputs.files("tools/user-text-check.py", "tools/user-text-names.txt")
    inputs.files(fileTree(".") {
        include("androidApp/src/main/**/*.kt", "devtool/src/main/**/*.kt", "serverlib/src/main/**/*.kt", "server/src/main/**/*.kt")
        include("shared/src/*Main/**/*.kt", "androidApp/src/main/res/values*/*.xml", "tools/demo/**/*.html", "tools/demo/**/*.js")
        exclude("**/build/**")
    })
    outputs.file(report)
    val python = if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3"
    commandLine(python, "-I", "tools/user-text-check.py", "--out", report.get().asFile.path)
}

// T345: the tools' own tests (every tools/test_*.py in its own `python -I` process, then the hook tests under node),
// through tools/run-tool-tests.py: one JSON report, exit 1 on any failure, a named skip where a program is missing
// (Windows PowerShell, java, node). Up to date while the tools and hooks are unchanged (the hook tests alone take
// minutes). The inputs name file types, not all of tools/, so a local secret there is never read.
val toolsTest = tasks.register<Exec>("toolsTest") {
    group = "verification"
    description = "Runs every tools/test_*.py and .claude/hooks/test-hooks.js (tools/run-tool-tests.py)."
    val report = layout.buildDirectory.file("reports/tools-test.json")
    inputs.files(fileTree("tools") {
        include("**/*.py", "**/*.ps1", "**/*.sh", "**/*.json", "**/*.java", "**/*.txt", "**/*.js", "**/*.html")
        exclude("**/__pycache__/**")
    })
    inputs.files(fileTree(".claude/hooks") { exclude("**/__pycache__/**") })
    inputs.files(".claude/settings.json")
    outputs.file(report)
    val python = if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3"
    commandLine(python, "-I", "tools/run-tool-tests.py", "--out", report.get().asFile.path)
}

tasks.named("check") {
    dependsOn(userTextCheck)
    dependsOn(toolsTest)
}

tasks.register("licenseCheck") {
    group = "verification"
    description = "Runs licenseCheck in every module."
    dependsOn(subprojects.map { "${it.path}:licenseCheck" })
}
