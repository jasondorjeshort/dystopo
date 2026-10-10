// Dystopo for Android (phase 1 shell, T10): MapLibre over a local region pack, with contour tiles
// generated on the device by the shared module, and a loopback tile server that also carries the
// debug endpoints in debug builds. Only included when an Android SDK is installed (settings.gradle.kts).
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
        versionCode = 1
        versionName = "0.1.0"
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
    testImplementation(kotlin("test"))
}
