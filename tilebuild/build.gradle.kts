import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

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

/**
 * Writes build/reports/licenses.json listing the license of every runtime dependency, read from its
 * POM (following parent POMs when the license is inherited), and prints a summary grouped by license.
 * Anything whose license is missing or matches GPL/LGPL/AGPL is listed under "flagged". This is the
 * evidence for the project rule that dependencies must be permissively licensed; T7 turns the rule
 * into a check wired into `check`.
 */
val licenseReport by tasks.registering {
    group = "verification"
    description = "Lists runtime dependency licenses from POM metadata (build/reports/licenses.json)."
    notCompatibleWithConfigurationCache("resolves POM files at execution time")
    val reportFile = layout.buildDirectory.file("reports/licenses.json")
    outputs.file(reportFile)
    outputs.upToDateWhen { false }
    doLast {
        val components = configurations.getByName("runtimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .sortedBy { "${it.group}:${it.module}" }

        val pomCache = HashMap<String, File?>()
        fun pom(group: String, artifact: String, version: String): File? = pomCache.getOrPut("$group:$artifact:$version") {
            runCatching {
                configurations.detachedConfiguration(dependencies.create("$group:$artifact:$version@pom"))
                    .apply { isTransitive = false }
                    .resolve().firstOrNull()
            }.getOrNull()
        }

        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
        fun Element.children(name: String): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }
        fun Element.text(name: String): String? = children(name).firstOrNull()?.textContent?.trim()

        /** License names declared in this POM or, if none, in the nearest parent POM that declares some. */
        fun licensesOf(group: String, artifact: String, version: String, depth: Int = 0): List<String> {
            val file = pom(group, artifact, version) ?: return emptyList()
            val root = builder.parse(file).documentElement
            val licenses = root.children("licenses").flatMap { it.children("license") }.mapNotNull { it.text("name") }
            if (licenses.isNotEmpty() || depth > 10) return licenses
            val parent = root.children("parent").firstOrNull() ?: return emptyList()
            val pg = parent.text("groupId") ?: return emptyList()
            val pa = parent.text("artifactId") ?: return emptyList()
            val pv = parent.text("version") ?: return emptyList()
            return licensesOf(pg, pa, pv, depth + 1)
        }

        val disallowed = Regex("(?i)\\b(A?L?GPL|GNU)\\b")
        val rows = components.map { id ->
            val licenses = licensesOf(id.group, id.module, id.version)
            mapOf(
                "artifact" to "${id.group}:${id.module}:${id.version}",
                "licenses" to licenses,
                "flagged" to (licenses.isEmpty() || licenses.any { disallowed.containsMatchIn(it) }),
            )
        }
        val byLicense = rows.flatMap { row ->
            @Suppress("UNCHECKED_CAST")
            (row["licenses"] as List<String>).ifEmpty { listOf("(none in POM)") }.map { it to row["artifact"] as String }
        }.groupBy({ it.first }, { it.second }).toSortedMap()
        val report = mapOf(
            "module" to project.path,
            "configuration" to "runtimeClasspath",
            "artifactCount" to rows.size,
            "flagged" to rows.filter { it["flagged"] == true },
            "byLicense" to byLicense,
            "artifacts" to rows,
        )
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)))

        println("Runtime dependencies of ${project.path}: ${rows.size} artifacts")
        byLicense.forEach { (license, artifacts) -> println("  ${artifacts.size.toString().padStart(3)}  $license") }
        val flagged = rows.filter { it["flagged"] == true }
        println(if (flagged.isEmpty()) "No flagged licenses." else "FLAGGED (${flagged.size}):")
        flagged.forEach { println("  ${it["artifact"]}  ${it["licenses"]}") }
        println("Full report: $out")
    }
}
