package io.github.jasondorjeshort.dystopo.sources

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/** What role a data source plays. */
enum class SourceKind(val json: String) {
    DEM("dem"),
    GEOID("geoid"),

    /** Inputs to the OSM vector tile build (OSM itself, OSM-derived aux data, Natural Earth, the schema). */
    OSM("osm"),
    OVERLAY("overlay"),
    FONT("font");

    companion object {
        fun parse(s: String): SourceKind? = entries.firstOrNull { it.json == s }
    }
}

/** Where a source has data. [bbox] is west, south, east, north in degrees, when a box is meaningful. */
data class Coverage(val summary: String, val bbox: List<Double>?)

/** One entry of the data source registry (docs/PLANNING.md › Licensing, hard rule 4). */
data class DataSource(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val provider: String,
    val coverage: Coverage,
    val resolution: String,
    /** For DEMs and geoids: the vertical datum heights are given in. */
    val verticalDatum: String?,
    /** SPDX license id, or `LicenseRef-*` where SPDX has none. */
    val license: String,
    val licenseName: String,
    val licenseUrl: String,
    /** Required credit, plain text. */
    val attribution: String,
    /** Required credit, HTML (what MapLibre shows). */
    val attributionHtml: String,
    val allowedUses: String,
    /** Download URL or URL template (`{z}/{x}/{y}`, `{cell}`...). */
    val url: String,
    val updateCadence: String,
    val notes: String,
    /** The entry as parsed, for re-encoding (e.g. `devtool sources`). */
    val raw: Map<String, Any?>,
)

/**
 * The data source registry: every elevation, geoid and OSM source with its license and required
 * attribution. Outputs take their credits from here ([attribution], [attributionHtml]) rather than
 * hard-coding them. The built-in registry is `sources.json` in shared's commonMain resources.
 */
class SourceRegistry(val sources: List<DataSource>) {
    private val byId: Map<String, DataSource> = sources.associateBy { it.id }

    init {
        val problems = validate(sources)
        require(problems.isEmpty()) { "invalid source registry: ${problems.joinToString("; ")}" }
    }

    val ids: List<String> get() = sources.map { it.id }

    operator fun get(id: String): DataSource? = byId[id]

    operator fun contains(id: String): Boolean = id in byId

    fun getValue(id: String): DataSource =
        byId[id] ?: throw IllegalArgumentException("unknown data source '$id'; known: ${ids.joinToString()}")

    /** [ids] deduplicated, in registry order (a stable order regardless of how callers collected them). Unknown ids throw. */
    fun ordered(ids: Collection<String>): List<String> {
        ids.forEach { getValue(it) }
        val wanted = ids.toSet()
        return sources.map { it.id }.filter { it in wanted }
    }

    /**
     * Plain-text credit for a set of sources: each source's attribution once, in registry order,
     * joined with spaces. Sources that share identical text (OSM and OSM-derived data) appear once.
     */
    fun attribution(ids: Collection<String>): String = compose(ids) { it.attribution }

    /** HTML credit for a set of sources; same order and deduplication as [attribution]. */
    fun attributionHtml(ids: Collection<String>): String = compose(ids) { it.attributionHtml }

    private fun compose(ids: Collection<String>, text: (DataSource) -> String): String =
        ordered(ids).map { text(getValue(it)) }.distinct().joinToString(" ")

    companion object {
        /** The registry shipped with the app, parsed and validated once. */
        val builtIn: SourceRegistry by lazy { parse(SourcesJson.TEXT) }

        /** Parses registry JSON (`{"sources": [...]}`); throws [IllegalArgumentException] listing every problem found. */
        fun parse(json: String): SourceRegistry {
            val root = MiniJson.parseObject(json)
            val list = root["sources"] as? List<*> ?: throw IllegalArgumentException("registry has no \"sources\" array")
            val problems = ArrayList<String>()
            val sources = list.mapIndexedNotNull { i, item ->
                @Suppress("UNCHECKED_CAST")
                val m = item as? Map<String, Any?>
                if (m == null) {
                    problems += "sources[$i] is not an object"
                    return@mapIndexedNotNull null
                }
                val label = (m["id"] as? String)?.takeIf { it.isNotBlank() } ?: "sources[$i]"
                fun str(key: String): String {
                    val v = m[key]
                    if (v != null && v !is String) problems += "$label: \"$key\" is not a string"
                    return v as? String ?: ""
                }
                val kindText = str("kind")
                val kind = SourceKind.parse(kindText)
                if (kind == null) problems += "$label: unknown kind '$kindText' (expected ${SourceKind.entries.joinToString(" | ") { it.json }})"
                @Suppress("UNCHECKED_CAST")
                val cov = m["coverage"] as? Map<String, Any?>
                if (cov == null) problems += "$label: missing coverage"
                val bbox = (cov?.get("bbox") as? List<*>)?.map { (it as? Number)?.toDouble() ?: Double.NaN }
                DataSource(
                    id = str("id"),
                    name = str("name"),
                    kind = kind ?: SourceKind.OVERLAY,
                    provider = str("provider"),
                    coverage = Coverage((cov?.get("summary") as? String).orEmpty(), bbox),
                    resolution = str("resolution"),
                    verticalDatum = (m["verticalDatum"] as? String)?.takeIf { it.isNotBlank() },
                    license = str("license"),
                    licenseName = str("licenseName"),
                    licenseUrl = str("licenseUrl"),
                    attribution = str("attribution"),
                    attributionHtml = str("attributionHtml"),
                    allowedUses = str("allowedUses"),
                    url = str("url"),
                    updateCadence = str("updateCadence"),
                    notes = str("notes"),
                    raw = m,
                )
            }
            require(problems.isEmpty()) { "invalid source registry: ${problems.joinToString("; ")}" }
            return SourceRegistry(sources)
        }

        private val SPDX_ID = Regex("[A-Za-z0-9.+-]+")
        private val SPDX_OPERATORS = setOf("AND", "OR", "WITH")

        /**
         * True for an SPDX-style license id (`ODbL-1.0`, `LicenseRef-Foo`) or a license expression
         * combining ids with `AND`, `OR` and `WITH` (upper case, as SPDX requires) and parentheses,
         * e.g. `BSD-3-Clause AND CC-BY-4.0` or `(MIT OR Apache-2.0) AND CC0-1.0`.
         */
        fun isSpdxExpression(text: String): Boolean {
            val tokens = text.replace("(", " ( ").replace(")", " ) ").trim().split(Regex("\\s+"))
            var depth = 0
            var expectOperand = true
            for (t in tokens) {
                when {
                    t == "(" -> { if (!expectOperand) return false; depth++ }
                    t == ")" -> { if (expectOperand || depth == 0) return false; depth-- }
                    t in SPDX_OPERATORS -> { if (expectOperand) return false; expectOperand = true }
                    SPDX_ID.matches(t) && t.uppercase() !in SPDX_OPERATORS -> { if (!expectOperand) return false; expectOperand = false }
                    else -> return false
                }
            }
            return depth == 0 && !expectOperand
        }

        /** Every problem with [sources]; empty when the registry is valid. */
        fun validate(sources: List<DataSource>): List<String> {
            val problems = ArrayList<String>()
            sources.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { problems += "duplicate id '$it'" }
            for (s in sources) {
                val label = s.id.ifBlank { "(blank id)" }
                if (s.id.isBlank()) problems += "a source has a blank id"
                else if (!Regex("[a-z0-9][a-z0-9-]*").matches(s.id)) problems += "$label: id must be lowercase letters, digits and '-'"
                fun need(field: String, value: String) {
                    if (value.isBlank()) problems += "$label: empty $field"
                }
                need("name", s.name)
                need("provider", s.provider)
                need("coverage summary", s.coverage.summary)
                need("license", s.license)
                need("licenseName", s.licenseName)
                need("licenseUrl", s.licenseUrl)
                need("attribution", s.attribution)
                need("attributionHtml", s.attributionHtml)
                need("url", s.url)
                need("updateCadence", s.updateCadence)
                if (s.license.isNotBlank() && !isSpdxExpression(s.license)) {
                    problems += "$label: license '${s.license}' is not an SPDX-style id or expression"
                }
                if (s.kind == SourceKind.DEM && s.verticalDatum == null) problems += "$label: DEM without verticalDatum"
                s.coverage.bbox?.let { b ->
                    val ok = b.size == 4 && b.none { it.isNaN() } && b[0] < b[2] && b[1] < b[3] &&
                        b[0] >= -180 && b[2] <= 180 && b[1] >= -90 && b[3] <= 90
                    if (!ok) problems += "$label: bbox must be [west, south, east, north] in degrees"
                }
            }
            return problems
        }
    }
}
