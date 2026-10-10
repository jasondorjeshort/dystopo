package dystopo.licenses

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the licenses of every artifact on the module's runtime classpath(s) (`runtimeClasspath`, or
 * `jvmRuntimeClasspath` etc. in multiplatform modules; never test classpaths).
 *
 * Licenses come from the artifact's POM, or the nearest parent (or relocation target) POM that declares any, unless
 * artifact-overrides.txt names the artifact. The build fails when an artifact is GPL/AGPL (with no
 * permissive alternative), has no recognizable license, or is LGPL in a module that isn't listed in
 * lgpl-tolerated-modules.txt. The failure lists each offender with its license, module, configuration
 * and dependency path. A JSON report of all artifacts is written either way.
 *
 * POMs are fetched through Gradle's artifact resolution, so they come from the dependency cache;
 * no network is needed beyond normal dependency resolution.
 */
abstract class LicenseCheckTask : DefaultTask() {
    @get:InputFile abstract val allowedLicenses: RegularFileProperty
    @get:InputFile abstract val lgplToleratedModules: RegularFileProperty
    @get:InputFile abstract val artifactOverrides: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    // Captured at configuration time: Task.project must not be used while the task executes.
    private val modulePath: String = project.path
    private val rootDir: File = project.rootDir
    private val configurationContainer: ConfigurationContainer = project.configurations
    private val dependencyHandler: DependencyHandler = project.dependencies

    init {
        notCompatibleWithConfigurationCache("resolves the dependency graph and POM files at execution time")
        // The result depends on the resolved graph, which isn't modelled as an input; it's cheap, so always run.
        outputs.upToDateWhen { false }
    }

    private class Coord(val group: String, val name: String, val version: String) {
        val key get() = "$group:$name:$version"
        val module get() = "$group:$name"
    }

    /** [inheritFrom] is the relocation target if the POM is a relocation stub, else the parent POM. */
    private class Pom(val licenses: List<DeclaredLicense>, val inheritFrom: Coord?)

    private class Row(
        val coord: Coord,
        val configuration: String,
        val path: List<String>,
        val source: String,
        val licenses: List<ClassifiedLicense>,
        val category: LicenseCategory,
        var problem: String? = null,
    )

    @TaskAction
    fun check() {
        val policy = LicensePolicy(LicensePolicy.parseAllowed(allowedLicenses.get().asFile.readLines()))
        val overrides = LicensePolicy.parseOverrides(artifactOverrides.get().asFile.readLines())
        val tolerated = LicensePolicy.contentLines(lgplToleratedModules.get().asFile.readLines()).toSet()
        val module = modulePath
        val lgplTolerated = module in tolerated

        // Every external component on the runtime classpath(s), with the shortest dependency path to it.
        val found = LinkedHashMap<String, Triple<Coord, String, List<String>>>()
        val configurations = runtimeClasspaths()
        for (configuration in configurations) {
            for ((coord, path) in componentsWithPaths(configuration)) {
                found.putIfAbsent(coord.key, Triple(coord, configuration.name, path))
            }
        }

        val poms = loadPoms(found.values.filter { overrides[it.first.module] == null }.map { it.first })

        val rows = found.values.sortedBy { it.first.key }.map { (coord, configuration, path) ->
            val override = overrides[coord.module]
            val (source, declared) = if (override != null) {
                "override" to listOf(DeclaredLicense(override))
            } else {
                "pom" to inheritedLicenses(coord, poms)
            }
            val classified = declared.map { policy.classify(it) }
            Row(coord, configuration, path, source, classified, policy.categoryOf(classified))
        }
        for (row in rows) {
            row.problem = when (row.category) {
                LicenseCategory.PERMISSIVE -> null
                LicenseCategory.LGPL -> if (lgplTolerated) null else "LGPL is only allowed in ${tolerated.sorted()}"
                LicenseCategory.UNKNOWN -> if (row.licenses.isEmpty()) "no license in POM or parent POMs"
                    else "license not recognized (add it to allowed-licenses.txt or an override if it is permissive)"
                LicenseCategory.GPL -> "GPL/AGPL is not allowed in any module"
            }
        }

        val reportOut = reportFile.get().asFile
        reportOut.parentFile.mkdirs()
        val report = reportOut.relativeTo(rootDir).invariantSeparatorsPath
        reportOut.writeText(toJson(module, configurations.map { it.name }, lgplTolerated, rows) + "\n")

        val counts = rows.groupingBy { it.category }.eachCount()
        logger.lifecycle(
            "$module: ${rows.size} runtime artifacts, " +
                LicenseCategory.values().joinToString(", ") { "${counts[it] ?: 0} ${it.name.lowercase()}" } +
                (if (lgplTolerated) " (LGPL tolerated)" else "") + "; report: $report",
        )

        val offenders = rows.filter { it.problem != null }
        if (offenders.isNotEmpty()) {
            val message = buildString {
                appendLine("License check failed for $module: ${offenders.size} disallowed artifact(s).")
                for (row in offenders) {
                    appendLine("  ${row.coord.key}")
                    appendLine("    module:   $module (${row.configuration})")
                    val licenses = row.licenses.joinToString("; ") { it.declared.text }.ifEmpty { "(none)" }
                    appendLine("    license:  [${row.category}] $licenses  (from ${row.source})")
                    appendLine("    problem:  ${row.problem}")
                    appendLine("    path:     ${row.path.joinToString(" -> ")}")
                }
                appendLine("Policy: docs/PLANNING.md > Licensing; config: gradle/licenses/. Report: $report")
            }
            throw GradleException(message)
        }
    }

    private fun runtimeClasspaths(): List<Configuration> = configurationContainer
        .filter { it.isCanBeResolved }
        .filter { it.name == "runtimeClasspath" || it.name.endsWith("RuntimeClasspath") }
        .filter { !it.name.contains("test", ignoreCase = true) }
        .sortedBy { it.name }

    /** Breadth-first walk of the resolved graph: each external component with its shortest path from the root. */
    private fun componentsWithPaths(configuration: Configuration): List<Pair<Coord, List<String>>> {
        val root = configuration.incoming.resolutionResult.root
        val pathTo = LinkedHashMap<ResolvedComponentResult, List<String>>()
        pathTo[root] = listOf(label(root))
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            for (dependency in component.dependencies) {
                val selected = (dependency as? ResolvedDependencyResult)?.selected ?: continue
                if (selected !in pathTo) {
                    pathTo[selected] = pathTo.getValue(component) + label(selected)
                    queue.addLast(selected)
                }
            }
        }
        return pathTo.mapNotNull { (component, path) ->
            val id = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
            Coord(id.group, id.module, id.version) to path
        }
    }

    private fun label(component: ResolvedComponentResult): String = when (val id = component.id) {
        is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
        is ProjectComponentIdentifier -> id.projectPath
        else -> id.displayName
    }

    /** Loads POMs for [coords] and, level by level, the parent (or relocation) POMs they reference. */
    private fun loadPoms(coords: List<Coord>): Map<String, Pom?> {
        val poms = HashMap<String, Pom?>()
        var pending = coords.distinctBy { it.key }
        var depth = 0
        while (pending.isNotEmpty() && depth++ < 20) {
            val files = resolvePomFiles(pending)
            for (coord in pending) poms[coord.key] = files[coord.key]?.let { parsePom(coord, it) }
            pending = poms.values.mapNotNull { it?.inheritFrom }.filter { it.key !in poms }.distinctBy { it.key }
        }
        return poms
    }

    private fun resolvePomFiles(coords: List<Coord>): Map<String, File> {
        val query = dependencyHandler.createArtifactResolutionQuery()
        coords.forEach { query.forModule(it.group, it.name, it.version) }
        val result = query.withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
        val files = HashMap<String, File>()
        for (component in result.resolvedComponents) {
            val id = component.id as? ModuleComponentIdentifier ?: continue
            val file = component.getArtifacts(MavenPomArtifact::class.java)
                .filterIsInstance<ResolvedArtifactResult>().firstOrNull()?.file ?: continue
            files["${id.group}:${id.module}:${id.version}"] = file
        }
        return files
    }

    private fun parsePom(coord: Coord, file: File): Pom? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val root = factory.newDocumentBuilder().parse(file).documentElement
        val licenses = root.children("licenses").flatMap { it.children("license") }.map {
            DeclaredLicense(it.text("name").orEmpty(), it.text("url").orEmpty())
        }.filter { it.text.isNotBlank() }
        val parent = root.children("parent").firstOrNull()?.let { p ->
            val g = p.text("groupId"); val a = p.text("artifactId"); val v = p.text("version")
            if (g != null && a != null && v != null && !v.contains("\${")) Coord(g, a, v) else null
        }
        // A relocation stub (e.g. mysql:mysql-connector-java -> com.mysql:mysql-connector-j) takes the target's license.
        val relocation = root.children("distributionManagement").flatMap { it.children("relocation") }.firstOrNull()?.let { r ->
            Coord(r.text("groupId") ?: coord.group, r.text("artifactId") ?: coord.name, r.text("version") ?: coord.version)
        }?.takeIf { it.key != coord.key && !it.version.contains("\${") }
        Pom(licenses, relocation ?: parent)
    }.getOrNull()

    /** Licenses from the artifact's POM or, if it declares none, the nearest parent (or relocation target) POM that does. */
    private fun inheritedLicenses(coord: Coord, poms: Map<String, Pom?>): List<DeclaredLicense> {
        var pom = poms[coord.key]
        var depth = 0
        while (pom != null && depth++ < 20) {
            if (pom.licenses.isNotEmpty()) return pom.licenses
            pom = pom.inheritFrom?.let { poms[it.key] }
        }
        return emptyList()
    }

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }

    private fun Element.text(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.ifEmpty { null }

    private fun toJson(module: String, configurations: List<String>, lgplTolerated: Boolean, rows: List<Row>): String {
        fun str(s: String) = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
        fun arr(items: List<String>, indent: String) =
            if (items.isEmpty()) "[]" else items.joinToString(",\n$indent  ", "[\n$indent  ", "\n$indent]")
        val byCategory = LicenseCategory.values().joinToString(", ") { c -> "${str(c.name)}: ${rows.count { it.category == c }}" }
        val artifacts = rows.map { row ->
            val licenses = row.licenses.map { l ->
                "{\"name\": ${str(l.declared.name)}, \"url\": ${str(l.declared.url)}, " +
                    "\"category\": ${str(l.category.name)}, \"id\": ${l.id?.let(::str) ?: "null"}}"
            }
            """{
      "artifact": ${str(row.coord.key)},
      "category": ${str(row.category.name)},
      "source": ${str(row.source)},
      "licenses": ${arr(licenses, "      ")},
      "configuration": ${str(row.configuration)},
      "path": ${arr(row.path.map(::str), "      ")},
      "problem": ${row.problem?.let(::str) ?: "null"}
    }"""
        }
        return """{
  "module": ${str(module)},
  "configurations": ${arr(configurations.map(::str), "  ")},
  "lgplTolerated": $lgplTolerated,
  "artifactCount": ${rows.size},
  "byCategory": {$byCategory},
  "problems": ${rows.count { it.problem != null }},
  "artifacts": ${arr(artifacts, "  ")}
}"""
    }
}
