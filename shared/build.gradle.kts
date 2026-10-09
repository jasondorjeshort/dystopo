plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    // Android and iOS targets are added in later phases. Code in commonMain must stay
    // pure Kotlin (no java.* imports) so it compiles for all of them.
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
