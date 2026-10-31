package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.routing.GraphTileFile
import io.github.jasondorjeshort.dystopo.routing.GraphTiler

/**
 * T189 (ADR 0049's B): a file made from a pack's archives and shipped beside them, never one of them. Today the one
 * kind is the routing graph (`graph`: `<name>-outdoors-graph.idx`, the `DYSG` version 6 file every reader already
 * accepts beside a vector archive when its tile zoom and fingerprints match). It is listed in a catalog entry's
 * `derived` list (outside `files`, so [PackHash.combine] and the pack's identity never move when only the graph
 * changes) and, with the same fields, on an area manifest's `graph` item (`LocalItem`).
 *
 * JSON: the common keys at the top, each kind's match keys under `key` (no nulls for another kind's keys):
 * ```
 * {"kind": "graph", "file": "rainier-outdoors-graph.idx", "url": "/out/rainier-outdoors-graph.idx", "bytes": 512345,
 *  "sha256": "<64 hex>", "sidecar": "rainier-outdoors-graph.idx.sha256", "format": "DYSG", "version": 6,
 *  "key": {"tileZoom": 11, "fingerprint": "v1:...", "demFingerprint": "v1:..."}}
 * ```
 * A reader fetches only a kind it knows whose `format`, `version` and `key` it can compare ([ShippedGraph.mismatch]);
 * anything else is ignored, and the phone builds the graph itself (ADR 0033 §2: derived data is rebuilt, never refused).
 * `fingerprint` and `demFingerprint` are `RoutingGraphBuilder.fingerprint` of the two archive headers, a wire value
 * since T189 (`v1:`; a `v2:` makes every older shipped graph unmatched, which heals itself by a build).
 */
data class DerivedFile(
    val kind: String,
    val file: String,
    val url: String,
    val bytes: Long,
    /** 64 lowercase hex digits. */
    val sha256: String,
    val format: String?,
    val version: Int?,
    val key: Map<String, Any?> = emptyMap(),
    /** The `sha256sum` sidecar's file name on the server (`<file>.sha256`), when it has one. */
    val sidecar: String? = null,
) {
    val tileZoom: Int? get() = (key["tileZoom"] as? Number)?.toInt()
    val fingerprint: String? get() = key["fingerprint"] as? String
    val demFingerprint: String? get() = key["demFingerprint"] as? String

    fun json(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "kind" to kind, "file" to file, "url" to url, "bytes" to bytes, "sha256" to sha256,
    ).apply {
        if (sidecar != null) put("sidecar", sidecar)
        put("format", format)
        put("version", version)
        put("key", key)
    }

    companion object {
        const val KIND_GRAPH = "graph"
        const val FORMAT_GRAPH = "DYSG"

        /** A derived entry, or null when it lacks what any reader needs (kind, a plain file name, bytes, a SHA-256). Never throws. */
        fun parse(m: Map<String, Any?>): DerivedFile? {
            val kind = m["kind"] as? String ?: return null
            val file = (m["file"] as? String)?.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it && ".." !in it } ?: return null
            val sha = PackHash.normalize(m["sha256"] as? String) ?: return null
            val bytes = (m["bytes"] as? Number)?.toLong()?.takeIf { it >= 0 } ?: return null
            @Suppress("UNCHECKED_CAST")
            // Whole numbers as Int where they fit (JSON gives Long), so an entry equals itself across the wire.
            val key = (m["key"] as? Map<String, Any?>).orEmpty().mapValues { (_, v) -> if (v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v }
            return DerivedFile(
                kind, file, m["url"] as? String ?: "/out/$file", bytes, sha, m["format"] as? String, (m["version"] as? Number)?.toInt(), key,
                m["sidecar"] as? String,
            )
        }

        /** Every usable entry of a `derived` list ([parse]); entries that are not objects or are unusable are left out. */
        fun parseList(v: Any?): List<DerivedFile> = (v as? List<*>).orEmpty().mapNotNull {
            @Suppress("UNCHECKED_CAST")
            (it as? Map<String, Any?>)?.let(::parse)
        }

        /** The routing graph entry for a graph file of [bytes] and [sha256] built from archives with these fingerprints. */
        fun graph(
            file: String, url: String, bytes: Long, sha256: String, fingerprint: String, demFingerprint: String,
            version: Int = GraphTileFile.VERSION, tileZoom: Int = GraphTiler.ZOOM, sidecar: String? = "$file.sha256",
        ): DerivedFile = DerivedFile(
            KIND_GRAPH, file, url, bytes, PackHash.normalize(sha256) ?: throw IllegalArgumentException("bad sha256 '$sha256'"),
            FORMAT_GRAPH, version, linkedMapOf("tileZoom" to tileZoom, "fingerprint" to fingerprint, "demFingerprint" to demFingerprint), sidecar,
        )
    }
}

/**
 * T189: whether a shipped routing graph ([DerivedFile] of kind `graph`) fits the archives a phone holds. Pure, so the
 * phone's three fetch points and the server's catalog writer agree on one rule: the format and version this build
 * reads ([GraphTileFile.VERSION]), the tile zoom every reader requires ([GraphTiler.ZOOM]) and both fingerprints.
 */
object ShippedGraph {
    /** Why [d] cannot be used beside archives whose fingerprints are [fingerprint] and [demFingerprint] (empty: no DEM); null when it can. */
    fun mismatch(d: DerivedFile, fingerprint: String, demFingerprint: String): String? = when {
        d.kind != DerivedFile.KIND_GRAPH -> "not a graph (${d.kind})"
        d.format != DerivedFile.FORMAT_GRAPH -> "format ${d.format}, expected ${DerivedFile.FORMAT_GRAPH}"
        d.version != GraphTileFile.VERSION -> "routing graph version ${d.version}, expected ${GraphTileFile.VERSION}"
        d.tileZoom != GraphTiler.ZOOM -> "graph tile zoom ${d.tileZoom}, expected ${GraphTiler.ZOOM}"
        d.fingerprint != fingerprint -> "fingerprint mismatch (the graph was built from another vector archive)"
        (d.demFingerprint ?: "") != demFingerprint -> "elevation fingerprint mismatch (the graph was built with another DEM archive)"
        else -> null
    }

    /** The first graph entry of [derived] usable with these fingerprints, or null. */
    fun pick(derived: List<DerivedFile>, fingerprint: String, demFingerprint: String): DerivedFile? =
        derived.firstOrNull { mismatch(it, fingerprint, demFingerprint) == null }

    /** The first graph entry of [derived] this build can read at all (format, version, tile zoom), whatever its fingerprints. */
    fun readable(derived: List<DerivedFile>): DerivedFile? = derived.firstOrNull { d ->
        d.kind == DerivedFile.KIND_GRAPH && d.format == DerivedFile.FORMAT_GRAPH && d.version == GraphTileFile.VERSION && d.tileZoom == GraphTiler.ZOOM
    }
}
