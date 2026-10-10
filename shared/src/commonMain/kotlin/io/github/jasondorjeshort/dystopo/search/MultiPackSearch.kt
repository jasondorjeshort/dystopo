package io.github.jasondorjeshort.dystopo.search

/** A hit and the pack whose index found it. */
data class PackHit(val pack: String, val hit: SearchHit) {
    fun json(): Map<String, Any?> = hit.json() + ("pack" to pack)
}

/**
 * Search across several packs' indexes at once (T41): each index is queried with the same centre, the
 * hits are merged by score, and a place two overlapping packs both hold (same folded name and kind,
 * within [DUPLICATE_KM]) is listed once, from the pack that scored it higher. The one-typo fallback
 * stays a fallback across packs too: when any pack matches the words as typed, fuzzy hits from other
 * packs are dropped (a pack where the query matches nothing must not flood the list with near misses).
 */
object MultiPackSearch {
    const val DUPLICATE_KM = 1.0

    fun search(
        indexes: List<Pair<String, SearchIndex>>,
        query: String,
        lat: Double? = null,
        lon: Double? = null,
        limit: Int = 20,
        kinds: Set<PlaceKind>? = null,
    ): List<PackHit> {
        if (limit <= 0) return emptyList()
        // Ask each index for the full limit: duplicates and dropped fuzzy hits may thin the merged list.
        val all = indexes.flatMap { (pack, idx) -> idx.search(query, lat, lon, limit, kinds).map { PackHit(pack, it) } }
        val exactSomewhere = all.any { it.hit.match != "fuzzy" }
        val ranked = all.filter { !exactSomewhere || it.hit.match != "fuzzy" }
            .sortedWith(compareByDescending<PackHit> { it.hit.score }.thenBy { it.hit.entry.name }.thenBy { it.pack })
        val kept = ArrayList<PackHit>()
        for (h in ranked) {
            val e = h.hit.entry
            val key = TextFold.normalized(e.name)
            val dup = kept.any { k ->
                val o = k.hit.entry
                o.kind == e.kind && TextFold.normalized(o.name) == key &&
                    SearchIndex.distanceKm(o.lat, o.lon, e.lat, e.lon) <= DUPLICATE_KM
            }
            if (!dup) kept += h
            if (kept.size >= limit) break
        }
        return kept
    }
}
