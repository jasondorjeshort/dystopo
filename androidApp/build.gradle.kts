// Dystopo for Android (phase 1 shell, T10): MapLibre over a local region pack, with contour tiles
// generated on the device by the shared module, and a loopback tile server that also carries the
// debug endpoints in debug builds. Only included when an Android SDK is installed (settings.gradle.kts).
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jasondorjeshort.dystopo.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.jasondorjeshort.dystopo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        // The release workflow passes the tag: -PdystopoVersion=0.1.2-alpha -PdystopoVersionCode=<commit count>.
        // Local builds are "0.1.0-dev" with code 1; a phone updates only to a higher or equal code.
        versionCode = (project.findProperty("dystopoVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("dystopoVersion") as String?) ?: "0.1.0-dev"
        // Loopback port of the tile server (and, in debug builds, the debug endpoints).
        buildConfigField("int", "SERVER_PORT", "8686")
    }

    // T55: the release key comes from tools/release.properties (gitignored, like tools/release.keystore; the
    // keytool command and the file's four keys are in CLAUDE.md > Release). Without it, release builds are
    // signed with the debug key so `assembleRelease` still gives an installable APK on any machine.
    val releaseProps = rootProject.file("tools/release.properties").takeIf { it.isFile }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (releaseProps != null) {
            create("release") {
                storeFile = rootProject.file(releaseProps.getProperty("storeFile", "tools/release.keystore"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias", "dystopo")
                keyPassword = releaseProps.getProperty("keyPassword") ?: releaseProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEBUG_ENDPOINTS", "true")
        }
        release {
            buildConfigField("boolean", "DEBUG_ENDPOINTS", "false")
            // R8 in full mode (the AGP 8 default; gradle.properties does not turn it off). Keep rules and why: proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // T55: one APK per ABI (what a phone needs: MapLibre's native library is about 13 MB per ABI) plus a
    // universal APK with all four. `assembleRelease` writes androidApp-<abi>-release.apk and androidApp-universal-release.apk.
    // Splits cannot be set per build type, so they are on only when the build asks for a release task: debug
    // builds keep the single androidApp-debug.apk that android-emu.ps1 and CI install. (Building debug and
    // release in one Gradle invocation splits debug too.)
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }

    lint {
        // Lint runs in `check`; keep it to real errors so the JVM-first workflow stays fast and green.
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("PropertyEscape", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

// T80: label glyphs and the marker sprite sheet are bundled in the APK (assets glyphs/ and sprites/, copied into the
// files dir on first start by firstrun/BundledAssets.kt), so an install never lacks labels or icons. The glyph PBFs
// (Noto Sans, SIL OFL 1.1) come from the shared download cache, fetched once from protomaps' basemaps-assets as
// tools/android-push-pack.ps1 does; the sprite sheet is drawn by `devtool sprite`. The fonts must equal
// MapLibreStyle.FONTS. Every range of the Basic Multilingual Plane is bundled, MapLibreStyle.GLYPH_RANGES among
// them: MapLibre asks for whatever range a label's characters fall in, and a range the fonts lack is a valid empty
// glyph PBF (an empty 200 makes MapLibre log "Failed to load glyph range" and the map never counts as loaded).
// BundledAssetsTest checks that every bundled file is a non-empty, parseable glyph PBF.
val glyphFonts = listOf("Noto Sans Regular", "Noto Sans Italic", "Noto Sans Medium")
val glyphRanges = (0 until 65536 step 256).map { "$it-${it + 255}" }

/** A valid glyph PBF with no glyphs: `glyphs { stacks { name, range } }`. Mirrors firstrun/EmptyGlyphs.kt. */
fun emptyGlyphPbf(font: String, range: String): ByteArray {
    fun field(tag: Int, bytes: ByteArray) = byteArrayOf(tag.toByte()) + varint(bytes.size) + bytes
    val stack = field(0x0A, font.toByteArray()) + field(0x12, range.toByteArray())
    return field(0x0A, stack)
}
fun varint(v: Int): ByteArray {
    val out = ArrayList<Byte>()
    var x = v
    while (x >= 0x80) { out += ((x and 0x7F) or 0x80).toByte(); x = x ushr 7 }
    out += x.toByte()
    return out.toByteArray()
}
val mapAssetsDir = layout.buildDirectory.dir("generated/mapAssets")
val bundleMapAssets by tasks.registering {
    group = "build"
    description = "Collects the label glyphs and draws the sprite sheet into the APK's assets."
    dependsOn(":devtool:installDist")
    val out = mapAssetsDir
    val devtoolHome = rootProject.layout.projectDirectory.dir("devtool/build/install/devtool")
    val cacheRoot = (System.getenv("DYSTOPO_CACHE")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: rootProject.file("cache"))
    val repoRoot = rootProject.projectDir
    inputs.property("fonts", glyphFonts)
    inputs.property("ranges", glyphRanges)
    inputs.files(fileTree(rootProject.file("shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/style")))
    outputs.dir(out)
    doLast {
        val root = out.get().asFile
        root.deleteRecursively()
        val pool = Executors.newFixedThreadPool(16)
        try {
            glyphFonts.flatMap { font -> glyphRanges.map { range -> font to range } }.map { (font, range) ->
                pool.submit(Callable<Unit> {
                    val cached = File(cacheRoot, "glyphs/$font/$range.pbf")
                    if (!cached.isFile || cached.length() == 0L) {
                        cached.parentFile.mkdirs()
                        val conn = URI("https", "protomaps.github.io", "/basemaps-assets/fonts/$font/$range.pbf", null).toURL().openConnection() as HttpURLConnection
                        conn.connectTimeout = 20_000
                        conn.readTimeout = 60_000
                        val bytes = when (val status = conn.responseCode) {
                            200 -> conn.inputStream.use { it.readBytes() }
                            404 -> ByteArray(0)
                            else -> error("glyph range $font/$range: HTTP $status")
                        }
                        conn.disconnect()
                        val tmp = File(cached.parentFile, "$range.pbf.${Thread.currentThread().id}.part")
                        tmp.writeBytes(if (bytes.isEmpty()) emptyGlyphPbf(font, range) else bytes)
                        check(tmp.renameTo(cached) || cached.isFile) { "could not store $cached" }
                    }
                    cached.copyTo(File(root, "glyphs/$font/$range.pbf"), overwrite = true)
                    Unit
                })
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        val windows = System.getProperty("os.name").startsWith("Windows")
        val script = devtoolHome.file(if (windows) "bin/devtool.bat" else "bin/devtool").asFile
        val sprites = File(root, "sprites")
        val proc = ProcessBuilder(script.absolutePath, "sprite", "--out", sprites.absolutePath)
            .directory(repoRoot).redirectErrorStream(true)
            .apply { environment()["JAVA_HOME"] = System.getProperty("java.home") }
            .start()
        val log = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "devtool sprite failed: $log" }
        for (f in listOf("sprite.json", "sprite.png", "sprite@2x.json", "sprite@2x.png")) check(File(sprites, f).isFile) { "devtool sprite wrote no $f" }
    }
}
android.sourceSets.getByName("main").assets.srcDir(bundleMapAssets) // the task's output dir, with the task as its builder
tasks.withType<Test>().configureEach {
    dependsOn(bundleMapAssets)
    systemProperty("dystopo.mapAssets", mapAssetsDir.get().asFile.path) // BundledAssetsTest reads the bundled glyphs
}
// Lint reads the asset dirs too.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") || it.name.startsWith("generate") && it.name.endsWith("Assets") || it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(bundleMapAssets) }

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.maplibre.android)
    implementation(libs.sqldelight.android.driver)
    // T57: the QR scanner's camera frames and preview (CameraX, Apache-2.0); the QR decoding itself is ours
    // (share/QrDecoder.kt), not ML Kit. 1.4.x is the newest line that compiles against API 35.
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    testImplementation(kotlin("test"))
}
