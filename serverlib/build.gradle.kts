// The server pieces shared by `devtool serve` and the standalone `server` (T52): the HTTP routes (live
// tiles, pack builds, static /out/, the sync peer, the pack catalog), the elevation sources they render
// from, and the small JVM helpers those need. The Kotlin package stays `...dystopo.devtool` (the files
// moved here from devtool unchanged), so devtool code and tests use them without new imports.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":shared"))
    api(libs.kotlinx.serialization.json)
    // T44: the sync peer's replica is a SqlTrackStore in a SQLite file (SQLDelight's JDBC driver and
    // sqlite-jdbc, both Apache-2.0).
    api(libs.sqldelight.sqlite.driver)
    testImplementation(kotlin("test"))
}

tasks.named<Test>("test") {
    systemProperty("java.awt.headless", "true")
}
