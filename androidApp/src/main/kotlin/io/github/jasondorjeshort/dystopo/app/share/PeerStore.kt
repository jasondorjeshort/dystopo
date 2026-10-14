package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.packs.PackCatalog
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.File

/**
 * T90: the phones this phone uses as map sources, kept across restarts ([PeerSources] reads it once per process and
 * writes it on every change). One JSON file in the app's private files (`files/share/peers.json`; the app has
 * `allowBackup="false"`, so it never leaves the phone):
 * ```
 * {"version": 1, "codes": ["7K3MQW2D9XHPT4CA"], "via": {"10.0.2.16:8841": "10.0.2.2:8842"},
 *  "peers": [{"addr": "10.0.2.16:8841", "code": "7K3MQW2D9XHPT4CA", "name": "Dystopo Pixel 6 3f2a", "device": "3f2a91c0",
 *             "nsdName": "...", "auto": false, "addedAt": 1791..., "helloAt": 1791..., "lastSeenAt": 1791..., "catalogAt": 1791...,
 *             "catalog": {<the peer's catalog, PackCatalog.json()>}, "areas": [{"id": ..., "name": ..., ...}]}]}
 * ```
 * The address and the share code are all the sealed link needs ([io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink]
 * derives each frame's key from the code and the frame's own salt; nothing else about a link is kept between
 * requests). The catalog is kept so the list shows the peer's packs before it answers again. A file that does not parse
 * is ignored (the phone starts with no peers), never a crash.
 */
internal object PeerStore {
    const val VERSION = 1

    class Saved(val peers: List<PeerSources.Peer>, val codes: List<String>, val via: Map<String, String>)

    fun encode(peers: Collection<PeerSources.Peer>, codes: Collection<String>, via: Map<String, String>): String =
        MiniJson.encode(linkedMapOf("version" to VERSION, "codes" to codes.toList(), "via" to via.toSortedMap(), "peers" to peers.map(::peerJson)))

    fun decode(text: String): Saved {
        val doc = MiniJson.parseObject(text)
        val peers = (doc["peers"] as? List<*>).orEmpty().mapNotNull { e ->
            @Suppress("UNCHECKED_CAST")
            runCatching { peer(e as Map<String, Any?>) }.getOrNull()
        }
        val codes = (doc["codes"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.let { c -> runCatching { ShareClient.requireCode(c) }.getOrNull() } }
        val via = (doc["via"] as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }.toMap()
        return Saved(peers, codes, via)
    }

    fun read(file: File): Saved? = if (!file.isFile) null else runCatching { decode(file.readText()) }.getOrNull()

    /** Writes [text] to [file] through a temporary file and a rename, so a crash mid-write leaves the old file. */
    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("could not write $file")
        }
    }

    private fun peerJson(p: PeerSources.Peer): Map<String, Any?> = linkedMapOf(
        "addr" to p.addr, "code" to p.code, "name" to p.name, "device" to p.device, "nsdName" to p.nsdName, "auto" to p.auto,
        "addedAt" to p.addedAt, "helloAt" to p.helloAt, "lastSeenAt" to p.lastSeenAt, "catalogAt" to p.catalogAt,
        "catalog" to p.catalog?.json(), "areas" to p.areas,
    )

    private fun peer(m: Map<String, Any?>): PeerSources.Peer {
        val addr = m["addr"] as? String ?: throw IllegalArgumentException("no addr")
        val code = ShareClient.requireCode(m["code"] as? String)
        fun long(k: String) = (m[k] as? Number)?.toLong()
        return PeerSources.Peer(addr, code).apply {
            name = m["name"] as? String
            device = m["device"] as? String
            nsdName = m["nsdName"] as? String
            auto = m["auto"] == true
            long("addedAt")?.let { addedAt = it }
            helloAt = long("helloAt")
            lastSeenAt = long("lastSeenAt")
            catalogAt = long("catalogAt")
            @Suppress("UNCHECKED_CAST")
            catalog = (m["catalog"] as? Map<String, Any?>)?.let { PackCatalog.parse(it) }
            @Suppress("UNCHECKED_CAST")
            areas = (m["areas"] as? List<Map<String, Any?>>).orEmpty()
            restored = true
        }
    }
}
