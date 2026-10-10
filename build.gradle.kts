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

tasks.register("licenseCheck") {
    group = "verification"
    description = "Runs licenseCheck in every module."
    dependsOn(subprojects.map { "${it.path}:licenseCheck" })
}
