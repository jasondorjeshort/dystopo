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
 * A single license string that matches the LGPL or GPL-family patterns (GPL, AGPL, any version, "GNU",
 * "General Public License", "Affero") is LGPL or GPL, even if it also matches an allowlist entry: a
 * string like "GPL ISC" or "LGPL, Apache" names a copyleft license, and one string can't tell us which
 * option applies (declare the options as separate POM licenses, or add an override). The one exception
 * is [gplExceptionIds], allowlist entries that describe a permissive GPL variant (the classpath
 * exception), so "CDDL + GPLv2 with classpath exception" is permissive. A string with no copyleft match
 * is permissive if it matches the allowlist, else unknown.
 *
 * An artifact that declares several licenses is dual-licensed and we may pick the best option, so its
 * category is the best of its licenses; with no licenses it's unknown. [choiceNote] describes such a
 * choice so the build output can show it.
 */
class LicensePolicy(
    private val allowed: List<Pair<String, Regex>>,
    private val gplExceptionIds: Set<String> = setOf(CLASSPATH_EXCEPTION_ID),
) {

    fun classify(license: DeclaredLicense): ClassifiedLicense {
        val text = license.text
        val copyleft = when {
            LGPL.containsMatchIn(text) -> LicenseCategory.LGPL
            GPL.containsMatchIn(text) -> LicenseCategory.GPL
            else -> null
        }
        val candidates = allowed.filter { (_, regex) -> regex.containsMatchIn(text) }
        val permissive = if (copyleft == null) candidates.firstOrNull() else candidates.firstOrNull { it.first in gplExceptionIds }
        if (permissive != null) return ClassifiedLicense(license, LicenseCategory.PERMISSIVE, permissive.first)
        return ClassifiedLicense(license, copyleft ?: LicenseCategory.UNKNOWN, null)
    }

    fun categoryOf(licenses: List<ClassifiedLicense>): LicenseCategory =
        licenses.minOfOrNull { it.category } ?: LicenseCategory.UNKNOWN

    /**
     * For an artifact that declares licenses of different categories (e.g. "LGPL-2.1" and "Apache-2.0"),
     * a one-line note naming the option the check relied on and the ones it passed over; null otherwise.
     */
    fun choiceNote(licenses: List<ClassifiedLicense>): String? {
        if (licenses.map { it.category }.distinct().size < 2) return null
        val best = licenses.minBy { it.category }
        val others = licenses.filter { it !== best }
        fun label(l: ClassifiedLicense) = "${l.id ?: l.declared.name.ifBlank { l.declared.url }} [${l.category.name.lowercase()}]"
        return "multiple licenses, best chosen: ${label(best)} over ${others.joinToString(", ") { label(it) }}"
    }

    companion object {
        /** The allowlist id for GPL with the classpath exception, which may match alongside the GPL patterns. */
        const val CLASSPATH_EXCEPTION_ID = "GPL-2.0-with-classpath-exception"

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
