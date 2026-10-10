// Dystopo for Android (phase 1 shell, T10): MapLibre over a local region pack, with contour tiles
// generated on the device by the shared module, and a loopback tile server that also carries the
// debug endpoints in debug builds. Only included when an Android SDK is installed (settings.gradle.kts).
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

    buildTypes {
        debug {
            buildConfigField("boolean", "DEBUG_ENDPOINTS", "true")
        }
        release {
            buildConfigField("boolean", "DEBUG_ENDPOINTS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release keystore yet; sign with the debug key so `assembleRelease` gives an installable APK
            // for footprint measurements. Not for distribution.
            signingConfig = signingConfigs.getByName("debug")
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
