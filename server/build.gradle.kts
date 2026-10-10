// The standalone Dystopo server (T52): what `devtool serve --live` does (tiles, live sources, pack
// builds, /out/, the sync peer, attribution) plus a config file, JSON logs, /healthz, /catalog, an
// optional bearer token, a CORS allowlist and graceful shutdown. See docs/SERVER.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":serverlib"))
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.jasondorjeshort.dystopo.server.MainKt"
    applicationName = "server"
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Djava.awt.headless=true")
}

// The distribution carries the browser page served at `/` (the same page `devtool serve --live` serves
// from tools/demo/index.html) under web/, where the default config looks for it.
distributions {
    main {
        contents {
            from(rootProject.file("tools/demo/index.html")) { into("web") }
            from(layout.projectDirectory.file("server.example.json")) { into("config") }
            // The image (server/Dockerfile, T58) builds from the repository root in two stages, so the
            // distribution no longer carries the Dockerfile.
        }
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    jvmArgs("-Djava.awt.headless=true")
}

tasks.named<Test>("test") {
    systemProperty("java.awt.headless", "true")
}
