package io.github.jasondorjeshort.dystopo.tilebuild

import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.pmtiles.ReadablePmtiles
import com.onthegomap.planetiler.util.Gzip
import java.io.File

/**
 * T274: the antimeridian seam check of the world overview. A `water` polygon ring that has an edge lying on the tile's
 * west edge (x = 0) in a tile of the west column, or on its east edge (x = extent) in a tile of the east column, closes
 * the water off along 180 instead of running into the buffer as every other tile edge does; the style outlines water
 * fills, so it draws as a line along the antimeridian. Read by `WorldOverviewSeamTest` over every z0 to z5 seam tile of
 * `tilebuild/world/world-outdoors.pmtiles`.
 */
object WorldSeam {
    const val EXTENT = 4096

    /** An edge of length [length] tile units on the antimeridian side of tile [z]/[x]/[y], at tile x [atX], from y [y0] to [y1]. */
    data class Edge(val z: Int, val x: Int, val y: Int, val atX: Int, val y0: Int, val y1: Int) {
        val length: Int get() = y1 - y0
        override fun toString() = "$z/$x/$y water edge on x=$atX from y $y0 to $y1"
    }

    /** Every seam edge of the `water` layer in [file]'s tiles z[minZoom] to z[maxZoom] of the west and east columns. */
    fun edges(file: File, minZoom: Int = 0, maxZoom: Int = 5): List<Edge> =
        (ReadablePmtiles.newReadFromFile(file.toPath()) as ReadablePmtiles).use { archive ->
            buildList {
                for (z in minZoom..maxZoom) {
                    val n = 1 shl z
                    for (y in 0 until n) for (x in setOf(0, n - 1)) {
                        val raw = archive.getTile(x, y, z) ?: continue
                        val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) Gzip.gunzip(raw) else raw
                        addAll(edges(z, x, y, bytes))
                    }
                }
            }
        }

    /** The seam edges of one decoded tile [z]/[x]/[y] ([mvt] uncompressed), longer than one tile unit. */
    fun edges(z: Int, x: Int, y: Int, mvt: ByteArray): List<Edge> {
        val n = 1 shl z
        val sides = buildList { if (x == 0) add(0); if (x == n - 1) add(EXTENT) }
        val out = mutableListOf<Edge>()
        for (feature in VectorTile.decode(mvt)) {
            if (feature.layer() != "water") continue
            for (ring in rings(feature.geometry().commands())) {
                for (i in ring.indices) {
                    val (ax, ay) = ring[i]
                    val (bx, by) = ring[(i + 1) % ring.size]
                    if (ax == bx && ax in sides && kotlin.math.abs(ay - by) > 1) {
                        out += Edge(z, x, y, ax, minOf(ay, by), maxOf(ay, by))
                    }
                }
            }
        }
        return out
    }

    /** The rings of an MVT polygon's command stream (MoveTo, LineTo, ClosePath), in tile units. */
    internal fun rings(commands: IntArray): List<List<Pair<Int, Int>>> {
        val rings = mutableListOf<List<Pair<Int, Int>>>()
        var current = mutableListOf<Pair<Int, Int>>()
        var x = 0
        var y = 0
        var i = 0
        fun zigzag(v: Int) = (v ushr 1) xor -(v and 1)
        while (i < commands.size) {
            val id = commands[i] and 7
            val count = commands[i] ushr 3
            i++
            when (id) {
                1, 2 -> repeat(count) {
                    x += zigzag(commands[i]); y += zigzag(commands[i + 1]); i += 2
                    if (id == 1 && current.isNotEmpty()) { rings += current; current = mutableListOf() }
                    current += x to y
                }
                7 -> { if (current.isNotEmpty()) rings += current; current = mutableListOf() }
                else -> error("unknown MVT command $id")
            }
        }
        if (current.isNotEmpty()) rings += current
        return rings
    }
}
