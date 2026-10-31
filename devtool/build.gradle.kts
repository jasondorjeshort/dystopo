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
    // T52: server routes, live tiles, builds and the elevation sources, shared with the standalone server.
    implementation(project(":serverlib"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    // T44: the server peer stores its replica of the sync log in a SQLite file (SqlTrackStore over JDBC;
    // SQLDelight's driver and sqlite-jdbc are both Apache-2.0), and sync-sim --store sql uses it.
    implementation(libs.sqldelight.sqlite.driver)
    // T167: the printed map sheet's PDF (`print`, `GET /print`). Apache-2.0; devtool only, never shared.
    implementation(libs.pdfbox)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.jasondorjeshort.dystopo.devtool.MainKt"
    applicationName = "devtool"
    // The installed launcher (build/install/devtool/bin) too: UTF-8 stdout even when redirected on Windows.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Run from the repo root so areas.json, devtool.local.json, cache/ and out/ resolve there.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    // Reports contain "©" (attribution); keep stdout UTF-8 regardless of the Windows console code page.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Golden-image tests (src/test/kotlin/.../GoldenImageTest.kt). Text rendering must not depend on a display.
tasks.named<Test>("test") {
    systemProperty("java.awt.headless", "true")
    inputs.files(rootProject.file("areas.json"), rootProject.file("tools/demo/index.html"), rootProject.file("tools/android-push-pack.ps1")) // read by TourChecksTest, MarkerTableTest, GlyphFontsTest
    systemProperty("dystopo.goldens.diffDir", layout.buildDirectory.dir("golden-diffs").get().asFile.path)
    // Print failure messages in full: golden failures list the actual/diff image paths.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
    }
}

// T183: the PDF rasteriser (src/test/kotlin/.../PdfRaster.kt), to view a sheet the phone made:
// ./gradlew :devtool:rasterisePdf --args="out/sheet.pdf out/sheet.png 150"  (paths from the repo root)
tasks.register<JavaExec>("rasterisePdf") {
    group = "verification"
    description = "Rasterises a PDF page to a PNG: <in.pdf> <out.png> [dpi=150] [page=0]."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.github.jasondorjeshort.dystopo.devtool.PdfRasterKt"
    workingDir = rootDir
    systemProperty("java.awt.headless", "true")
}

// Regenerates the approved golden images from the current renderer. View them before committing.
tasks.register<JavaExec>("updateGoldens") {
    group = "verification"
    description = "Regenerates devtool/src/test/resources/goldens/*.png from the current renderer."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.github.jasondorjeshort.dystopo.devtool.UpdateGoldensKt"
    systemProperty("java.awt.headless", "true")
    args(layout.projectDirectory.dir("src/test/resources/goldens").asFile.path)
}

