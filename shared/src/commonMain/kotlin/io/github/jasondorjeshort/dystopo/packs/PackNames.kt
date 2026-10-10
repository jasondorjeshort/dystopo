package io.github.jasondorjeshort.dystopo.packs

/** The two archives a region pack can have. */
enum class PackRole(val label: String) {
    /** Terrarium DEM tiles (hillshade, contours). Required. */
    DEM("dem"),

    /** OSM vector tiles (the outdoors profile). Optional. */
    VECTOR("outdoors"),
}

/** What a file name says about the pack it belongs to. */
data class PackFileName(val pack: String, val role: PackRole, val legacy: Boolean = false)

/** The files of one pack found in a directory (names only; either may be missing). */
data class PackFileSet(val name: String, val dem: String?, val vector: String?)

/**
 * Pack file naming. A pack is `<name>-dem.pmtiles` plus an optional `<name>-outdoors.pmtiles`
 * (`<name>-osm.pmtiles` is accepted too). The pair pushed by `tools/android-push-pack.ps1` before
 * packs had names (`dem.pmtiles`, `osm.pmtiles`) is the pack [LEGACY_NAME].
 */
object PackNames {
    const val LEGACY_NAME = "default"
    const val LEGACY_DEM = "dem.pmtiles"
    const val LEGACY_VECTOR = "osm.pmtiles"
    private const val EXT = ".pmtiles"
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /** Pack names are file-name safe: letters, digits, '.', '_', '-', not starting with a symbol. */
    fun isValidName(name: String): Boolean = NAME.matches(name) && !name.contains("..")

    fun fileName(name: String, role: PackRole): String {
        require(isValidName(name)) { "invalid pack name '$name'" }
        return "$name-${role.label}$EXT"
    }

    fun parse(fileName: String): PackFileName? {
        when (fileName) {
            LEGACY_DEM -> return PackFileName(LEGACY_NAME, PackRole.DEM, legacy = true)
            LEGACY_VECTOR -> return PackFileName(LEGACY_NAME, PackRole.VECTOR, legacy = true)
        }
        if (!fileName.endsWith(EXT)) return null
        val stem = fileName.removeSuffix(EXT)
        val (name, role) = when {
            stem.endsWith("-dem") -> stem.removeSuffix("-dem") to PackRole.DEM
            stem.endsWith("-outdoors") -> stem.removeSuffix("-outdoors") to PackRole.VECTOR
            stem.endsWith("-osm") -> stem.removeSuffix("-osm") to PackRole.VECTOR
            else -> return null
        }
        return if (isValidName(name)) PackFileName(name, role) else null
    }

    /**
     * Groups the file names of a directory into packs, sorted by name. Unrelated files are ignored.
     * When both `-outdoors` and `-osm` exist, `-outdoors` wins. A pack without a DEM is still listed
     * (an incomplete pack the caller can report or delete).
     */
    fun group(fileNames: Collection<String>): List<PackFileSet> {
        val dem = HashMap<String, String>()
        val vector = HashMap<String, String>()
        for (f in fileNames.sorted()) {
            val p = parse(f) ?: continue
            when (p.role) {
                PackRole.DEM -> if (p.pack !in dem) dem[p.pack] = f
                PackRole.VECTOR -> {
                    val existing = vector[p.pack]
                    if (existing == null || f.endsWith("-outdoors$EXT")) vector[p.pack] = f
                }
            }
        }
        return (dem.keys + vector.keys).sorted().map { PackFileSet(it, dem[it], vector[it]) }
    }

    /**
     * The pack to show: [stored] when that pack exists and has a DEM, else the legacy pushed pack,
     * else the first pack with a DEM by name; null when there is none.
     */
    fun chooseActive(packs: List<PackFileSet>, stored: String?): String? {
        val usable = packs.filter { it.dem != null }
        usable.firstOrNull { it.name == stored }?.let { return it.name }
        usable.firstOrNull { it.name == LEGACY_NAME }?.let { return it.name }
        return usable.firstOrNull()?.name
    }
}
