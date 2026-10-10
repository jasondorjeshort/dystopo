rootProject.name = "dystopo"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

include(":shared")
include(":serverlib") // T52: server routes, live tiles, builds, elevation sources (devtool serve + server)
include(":devtool")
include(":server") // T52: the standalone, deployable server (config file, JSON logs, catalog, Dockerfile)
include(":tilebuild")

// Android (T10): the app module and shared's android target exist only when an Android SDK is installed
// (tools/android-sdk.ps1 writes sdk.dir to local.properties; ANDROID_HOME also works), so machines and
// agents without the SDK still build and `check` the JVM modules.
val androidSdkDir: File? = run {
    val props = java.util.Properties()
    val local = file("local.properties")
    if (local.isFile) local.inputStream().use { props.load(it) }
    listOfNotNull(props.getProperty("sdk.dir"), System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
        .map { File(it) }
        .firstOrNull { File(it, "platforms").isDirectory }
}
gradle.extensions.extraProperties["dystopoAndroidSdk"] = androidSdkDir != null
if (androidSdkDir != null) include(":androidApp")
