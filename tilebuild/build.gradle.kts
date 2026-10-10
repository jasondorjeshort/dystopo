plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

// Planetiler's GeoTools dependencies are only published to the OSGeo repository, and the OpenMapTiles
// profile only to JitPack. Both repositories are limited to the groups they are needed for, so they
// cannot shadow anything that Maven Central serves. These repositories apply to this module only.
repositories {
    mavenCentral()
    maven("https://repo.osgeo.org/repository/release/") {
        name = "osgeo"
        content {
            includeGroupByRegex("org\\.geotools(\\..*)?")
            includeGroupByRegex("org\\.eclipse\\.imagen(\\..*)?")
            includeGroupByRegex("it\\.geosolutions(\\..*)?")
            includeGroupByRegex("javax\\.media(\\..*)?")
            includeGroupByRegex("org\\.jaitools(\\..*)?")
        }
    }
    maven("https://jitpack.io") {
        name = "jitpack"
        content { includeGroup("com.github.openmaptiles") }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.planetiler.core)
    implementation(libs.planetiler.openmaptiles)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.jasondorjeshort.dystopo.tilebuild.MainKt"
    applicationName = "tilebuild"
}

// Run from the repo root so areas.json, cache/ and out/ resolve there. Planetiler keeps the OSM node
// map and feature store in RAM/mmap; a state-sized extract needs a few GB of heap.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    maxHeapSize = System.getenv("TILEBUILD_HEAP")?.takeIf { it.isNotBlank() } ?: "8g"
    // The report contains "©"; keep stdout UTF-8 regardless of the Windows console code page.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Runtime dependency licenses are checked by licenseCheck (applied to every module from the root build;
// see buildSrc/.../dystopo.license-check.gradle.kts). This module is listed in
// gradle/licenses/lgpl-tolerated-modules.txt because GeoTools (via Planetiler) is LGPL-2.1.
