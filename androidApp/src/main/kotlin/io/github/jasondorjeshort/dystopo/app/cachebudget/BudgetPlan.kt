package io.github.jasondorjeshort.dystopo.app.cachebudget

import kotlin.math.roundToLong

/**
 * T112: the kinds one cache budget spans, as the Storage screen has them: tiles downloaded from a server or OpenFreeMap
 * (DEM, map, glyphs), the contour, shade and cliff tiles the phone made (from packs or from downloaded DEM tiles,
 * wherever they are kept), and the phone's own source downloads when no server is set. Pinned offline areas and packs
 * stay outside (their own budget, `pinnedCacheMb`, and the pack list). A [derived] kind can be made again on the phone
 * offline; the others cannot. [floorShare] of the total is the kind's floor: the budget never evicts a kind below it to
 * make room for another, so no kind starves.
 */
enum class CacheKind(val key: String, val label: String, val derived: Boolean, val floorShare: Double) {
    ONLINE("online", "Online tiles", false, 0.25),
    DERIVED("derived", "Contours and shading", true, 0.15),
    SOURCES("sources", "Source data (no server)", false, 0.05);

    companion object {
        fun of(key: String): CacheKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * T112: the budget's arithmetic, pure so it is unit-tested (`BudgetPlanTest`): presets, floors, the migration of the
 * per-kind caps, and the eviction order. The rule (ADR 0025 principle 3, measured by `devtool cache-replay`): while the
 * kinds together use more than the total, take derived data first (a contour or shade tile can be made again offline,
 * a downloaded tile cannot), from the derived kind furthest above its floor, then source data from the kind furthest
 * above its floor; within a kind the least recently used goes first, never below the kind's floor, never a tile in view
 * or ahead on the route (the stores skip those, [CacheGuard]).
 */
object BudgetPlan {
    const val MB = 1024L * 1024

    /** The Storage screen's choices: small by default, a large one for a dedicated hiker (half of a 256 GB phone). */
    enum class Preset(val key: String, val label: String, val mb: Long) {
        SMALL("small", "Small", 1024),
        MEDIUM("medium", "Medium", 8 * 1024),
        LARGE("large", "Large", 128 * 1024);

        companion object {
            fun of(key: String): Preset? = entries.firstOrNull { it.key == key }
        }
    }

    val DEFAULT_MB = Preset.SMALL.mb

    /** A total from 64 MB to 1 TB. */
    val RANGE_MB = 64L..1024L * 1024

    /** The shares of free space the screen offers for picking a custom total once (never a live limit). */
    val FREE_PERCENTS = listOf(10, 25, 50)

    fun presetOf(mb: Long): Preset? = Preset.entries.firstOrNull { it.mb == mb }

    fun floorBytes(kind: CacheKind, totalBytes: Long): Long = (totalBytes * kind.floorShare).toLong()

    /**
     * A total of [percent] of the space the caches could have now: what is free on the device plus what the budgeted
     * caches already hold, in MB rounded down to 64 MB, within [RANGE_MB]. Picked once: the number is stored, so other
     * apps filling the disk later do not move it.
     */
    fun percentOfFreeMb(percent: Int, freeBytes: Long, usedBytes: Long): Long {
        require(percent in 1..90) { "percent must be 1 to 90 (got $percent)" }
        val mb = ((freeBytes + usedBytes).toDouble() * percent / 100 / MB).roundToLong()
        return (mb / 64 * 64).coerceIn(RANGE_MB)
    }

    /**
     * The total for an install that has none stored yet (before T112 there were only the per-kind caps): the small
     * preset, or the two caps together when the user had raised them past it, so nobody's cache shrinks on upgrade.
     */
    fun migrate(onlineCapMb: Long, contourCapMb: Long): Long = maxOf(DEFAULT_MB, onlineCapMb + contourCapMb).coerceIn(RANGE_MB)

    /**
     * What a run frees below the total once it is passed (1% of it, at least 4 MB, at most a quarter of it), so the
     * budget runs once per few hundred tile writes at its limit rather than after every one.
     */
    fun slackBytes(totalBytes: Long): Long = maxOf(totalBytes / 100, 4 * MB).coerceAtMost(totalBytes / 4)

    /** One eviction step: free up to [bytes] of [kind]. */
    data class Step(val kind: CacheKind, val bytes: Long)

    /**
     * The next step that brings [used] (bytes per kind) down to [targetBytes] (the total less [slackBytes] in a run;
     * floors are shares of [totalBytes]), or null when the kinds fit or nothing above a floor may go. [exhausted]: kinds
     * that freed nothing last time (all that is left there is in view, ahead on the route or pinned).
     */
    fun next(totalBytes: Long, used: Map<CacheKind, Long>, exhausted: Set<CacheKind> = emptySet(), targetBytes: Long = totalBytes): Step? {
        val over = used.values.sum() - targetBytes
        if (over <= 0) return null
        fun above(k: CacheKind) = (used[k] ?: 0L) - floorBytes(k, totalBytes)
        // Derived data first, then source data; in each, the kind furthest above its floor.
        for (derived in listOf(true, false)) {
            val k = CacheKind.entries.filter { it.derived == derived && it !in exhausted && above(it) > 0 }.maxByOrNull { above(it) } ?: continue
            return Step(k, minOf(over, above(k)))
        }
        return null
    }
}
