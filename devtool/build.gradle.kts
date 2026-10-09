plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.jasondorjeshort.dystopo.devtool.MainKt"
    applicationName = "devtool"
}

// Run from the repo root so areas.json, devtool.local.json, cache/ and out/ resolve there.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
