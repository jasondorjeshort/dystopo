package io.github.jasondorjeshort.dystopo.packs

/** Where a pack's files are on a static host. */
data class PackSource(val name: String, val demUrl: String, val vectorUrl: String) {
    fun url(role: PackRole) = if (role == PackRole.DEM) demUrl else vectorUrl
}

/**
 * Download rules that don't depend on the HTTP stack: which URLs make up a pack, and how to treat a
 * server's answer to a resumed (ranged) request. The platform code does the I/O.
 */
object PackDownload {
    /**
     * The pack a URL names. The URL is either one of the pack's files (`.../rainier-dem.pmtiles`,
     * `.../rainier-outdoors.pmtiles`) or the common prefix (`.../rainier`); the other file is the
     * sibling with the same directory and query string (a static host serves both side by side).
     * [name] overrides the pack name taken from the URL. Null for anything else.
     */
    fun resolve(url: String, name: String? = null): PackSource? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val q = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
        val query = url.substring(q).substringBefore('#')
        val path = url.substring(0, q)
        val slash = path.lastIndexOf('/')
        if (slash <= schemeEnd + 2) return null // no path after the host
        val dir = path.substring(0, slash + 1)
        val last = path.substring(slash + 1)
        val stem = PackNames.parse(last)?.takeUnless { it.legacy }?.pack
            ?: last.takeIf { !it.endsWith(".pmtiles") && PackNames.isValidName(it) }
            ?: return null
        val packName = name ?: stem
        if (!PackNames.isValidName(packName)) return null
        return PackSource(
            name = packName,
            demUrl = dir + PackNames.fileName(stem, PackRole.DEM) + query,
            vectorUrl = dir + PackNames.fileName(stem, PackRole.VECTOR) + query,
        )
    }

    /** A parsed `Content-Range: bytes first-last/total` header (`total` null for `*`). */
    data class ContentRange(val first: Long, val last: Long, val total: Long?)

    fun parseContentRange(header: String?): ContentRange? {
        if (header == null) return null
        val m = Regex("^\\s*bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)\\s*$").find(header) ?: return null
        val first = m.groupValues[1].toLongOrNull() ?: return null
        val last = m.groupValues[2].toLongOrNull() ?: return null
        val total = m.groupValues[3].let { if (it == "*") null else it.toLongOrNull() ?: return null }
        if (last < first || (total != null && last >= total)) return null
        return ContentRange(first, last, total)
    }

    /** What to do with the response to a request for bytes [have]`-` of a file. */
    sealed class Plan {
        /** Append the body to the partial file; the whole file is [total] bytes (null: unknown). */
        data class Append(val total: Long?) : Plan()

        /** The server sent the whole file: truncate the partial file and write the body from 0. */
        data class Restart(val total: Long?) : Plan()

        /** The partial file is already complete. */
        data object Complete : Plan()

        /** The partial file is unusable (bigger than the file, or the server's range is off): delete it and ask again from 0. */
        data class Discard(val reason: String) : Plan()

        /** Not found (404/410): the file does not exist on the host. */
        data object Missing : Plan()

        data class Fail(val reason: String) : Plan()
    }

    /**
     * Decides from the status, `Content-Range` and `Content-Length` of a response to a request
     * that asked for bytes from [have] (0: the whole file, no Range header). [knownTotal] is the size
     * recorded when the download started, if any.
     */
    fun plan(have: Long, status: Int, contentRange: String?, contentLength: Long?, knownTotal: Long? = null): Plan {
        val length = contentLength?.takeIf { it >= 0 }
        return when (status) {
            200 -> {
                val total = length ?: knownTotal
                if (have > 0 && total != null && have == total && knownTotal == total) Plan.Complete else Plan.Restart(total)
            }
            206 -> {
                val r = parseContentRange(contentRange) ?: return Plan.Discard("206 without a usable Content-Range ($contentRange)")
                when {
                    r.first != have -> Plan.Discard("server resumed at ${r.first}, wanted $have")
                    knownTotal != null && r.total != null && r.total != knownTotal ->
                        Plan.Discard("file changed on the server: ${r.total} bytes, was $knownTotal")
                    else -> Plan.Append(r.total ?: knownTotal)
                }
            }
            416 -> {
                // Range not satisfiable: we asked past the end. Complete if the server's size is ours.
                val total = parseUnsatisfiedTotal(contentRange) ?: knownTotal
                if (total != null && total == have) Plan.Complete else Plan.Discard("range $have- not satisfiable (size ${total ?: "unknown"})")
            }
            404, 410 -> Plan.Missing
            else -> Plan.Fail("HTTP $status")
        }
    }

    /** The total of `Content-Range: bytes * /total`, sent with a 416. */
    fun parseUnsatisfiedTotal(header: String?): Long? {
        if (header == null) return null
        return Regex("^\\s*bytes\\s+\\*/(\\d+)\\s*$").find(header)?.groupValues?.get(1)?.toLongOrNull()
    }
}
