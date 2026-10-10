package dystopo.licenses

/** How a license (or an artifact's set of licenses) fits the project's licensing rule. Best first. */
enum class LicenseCategory { PERMISSIVE, LGPL, UNKNOWN, GPL }

/** A license as declared in a POM (or an override): its name and URL, either of which may be blank. */
data class DeclaredLicense(val name: String, val url: String = "") {
    val text: String get() = listOf(name, url).filter { it.isNotBlank() }.joinToString(" ")
}

/** A license classified against the policy. [id] is the allowlist id for permissive licenses. */
data class ClassifiedLicense(val declared: DeclaredLicense, val category: LicenseCategory, val id: String?)

/**
 * The license policy, independent of Gradle so it can be unit-tested.
 *
 * A single license is checked against the allowlist first (so "CDDL + GPLv2 with classpath exception" is
 * permissive), then LGPL, then the GPL family (GPL, AGPL, any version, "GNU", "General Public License",
 * "Affero"). Anything else is unknown. An artifact that declares several licenses is dual-licensed and
 * we may pick the best option, so its category is the best of its licenses; with no licenses it's unknown.
 */
class LicensePolicy(private val allowed: List<Pair<String, Regex>>) {

    fun classify(license: DeclaredLicense): ClassifiedLicense {
        val text = license.text
        allowed.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.let { (id, _) ->
            return ClassifiedLicense(license, LicenseCategory.PERMISSIVE, id)
        }
        val category = when {
            LGPL.containsMatchIn(text) -> LicenseCategory.LGPL
            GPL.containsMatchIn(text) -> LicenseCategory.GPL
            else -> LicenseCategory.UNKNOWN
        }
        return ClassifiedLicense(license, category, null)
    }

    fun categoryOf(licenses: List<ClassifiedLicense>): LicenseCategory =
        licenses.minOfOrNull { it.category } ?: LicenseCategory.UNKNOWN

    companion object {
        val LGPL = Regex("(?i)LGPL|lesser (general )?public|library general public")
        val GPL = Regex("(?i)GPL|general public licen[cs]e|affero|\\bGNU\\b")

        /**
         * Parses allowed-licenses.txt: one `Id | regex` per line, `#` comments. Regexes are matched
         * case-insensitively anywhere in "name url" of a declared license.
         */
        fun parseAllowed(lines: List<String>): List<Pair<String, Regex>> = contentLines(lines).map { line ->
            val bar = line.indexOf('|')
            require(bar > 0) { "allowed-licenses.txt: expected 'Id | regex', got: $line" }
            line.substring(0, bar).trim() to Regex(line.substring(bar + 1).trim(), RegexOption.IGNORE_CASE)
        }

        /** Parses artifact-overrides.txt: one `group:name = License` per line, `#` comments. */
        fun parseOverrides(lines: List<String>): Map<String, String> = contentLines(lines).associate { line ->
            val eq = line.indexOf('=')
            require(eq > 0) { "artifact-overrides.txt: expected 'group:name = License', got: $line" }
            line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }

        /** Non-blank lines with `#` comments removed. */
        fun contentLines(lines: List<String>): List<String> =
            lines.map { it.removePrefix("﻿").substringBefore('#').trim() }.filter { it.isNotEmpty() }
    }
}
