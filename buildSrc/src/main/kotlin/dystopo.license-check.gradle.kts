// Registers `licenseCheck` in a module and makes the module's `check` depend on it.
// Config lives in gradle/licenses/ at the repo root; see LicenseCheckTask for the rules.

val licenseDir = rootProject.layout.projectDirectory.dir("gradle/licenses")

val licenseCheck = tasks.register<dystopo.licenses.LicenseCheckTask>("licenseCheck") {
    group = "verification"
    description = "Fails on GPL/AGPL, unknown, or (outside tolerated modules) LGPL runtime dependency licenses."
    allowedLicenses.set(licenseDir.file("allowed-licenses.txt"))
    lgplToleratedModules.set(licenseDir.file("lgpl-tolerated-modules.txt"))
    artifactOverrides.set(licenseDir.file("artifact-overrides.txt"))
    reportFile.set(layout.buildDirectory.file("reports/licenses/licenses.json"))
}

pluginManager.withPlugin("base") {
    tasks.named("check") { dependsOn(licenseCheck) }
}
