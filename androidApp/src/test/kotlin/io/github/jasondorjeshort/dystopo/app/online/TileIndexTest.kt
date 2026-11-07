package io.github.jasondorjeshort.dystopo.app.online

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T242: the compact index under [TileCache]: packed keys and the string fallback, the hash table, the buckets, its heap. */
class TileIndexTest {
    @Test
    fun tileKeysPackAndComeBackTheSame() {
        val ix = TileIndex()
        val keys = listOf(
            "10.0.2.2_8770/dem@d1/15/5000/11000.png",
            "10.0.2.2_8770/osm@o1/14/2500/5500.pbf",
            "direct/dem/12/0/0.png",
            "v2-ft-512-aaaa/12/661/1443.pbf", // the contour cache's keys: a one-segment prefix
            "srv/contours-v9-ft-512@d1/16/16777215/16777215.pbf", // the largest coordinates that pack
        )
        for ((i, k) in keys.withIndex()) {
            val s = ix.add(k, 100 + i, 10, false)
            assertEquals(k, ix.keyOf(s))
            assertTrue(ix.groupOf(s) >= 0, "$k packs")
            assertEquals(s, ix.find(k))
        }
        assertEquals(0, ix.stringKeys)
        assertEquals(keys.size, ix.count)
    }

    @Test
    fun otherKeysKeepTheirString() {
        val ix = TileIndex()
        val keys = listOf(
            "srv/glyphs@a1/Noto Sans Regular/0-255.pbf", // no z/x/y
            "srv/contours-v9-ft-512@d1/12/661/1443.halo-12-661-1442.pbf", // a halo variant: two dots
            "a", "s1/dem/1", // bare test keys
            "s/dem/12/01/2.png", // a leading zero would not come back the same
            "s/dem/32/1/2.png", // past z 31
            "s/dem/25/16777216/2.png", // past 24 bits
            "s/dem/12/1/2", // no extension
            "s/dem/12/1/2.", // an empty one
        )
        for (k in keys) {
            val s = ix.add(k, 1, 10, false)
            assertEquals(-1, ix.groupOf(s), k)
            assertEquals(k, ix.keyOf(s))
            assertEquals(s, ix.find(k))
        }
        assertEquals(keys.size, ix.stringKeys)
        assertEquals(TileIndex.NONE, ix.find("s/dem/12/1/2.png"), "a packable key not held")
        assertEquals(TileIndex.NONE, ix.find("other/dem/12/1/2.png"), "a group never seen")
    }

    @Test
    fun theTableAgreesWithAHashMapThroughAddsAndRemoves() {
        val ix = TileIndex()
        val model = HashMap<String, Int>()
        val rnd = Random(7)
        repeat(60_000) {
            val k = if (rnd.nextInt(50) == 0) "srv/glyphs@a/F/${rnd.nextInt(200)}.pbf" else "srv/dem@d${rnd.nextInt(3)}/14/${rnd.nextInt(300)}/${rnd.nextInt(300)}.png"
            val s = ix.find(k)
            assertEquals(model.containsKey(k), s != TileIndex.NONE, k)
            if (s == TileIndex.NONE) {
                model[k] = ix.add(k, rnd.nextInt(1, 1000), rnd.nextInt(100), rnd.nextBoolean())
            } else if (rnd.nextBoolean()) {
                ix.remove(s)
                model.remove(k)
            }
        }
        assertEquals(model.size, ix.count)
        for ((k, s) in model) {
            assertEquals(s, ix.find(k))
            assertEquals(k, ix.keyOf(s))
        }
    }

    @Test
    fun bucketsGiveTheOldestFirstAndATouchMovesToTheTail() {
        val ix = TileIndex()
        val a = ix.add("s/dem/1/0/0.png", 1, 20, false)
        val b = ix.add("s/dem/1/0/1.png", 1, 10, false)
        val c = ix.add("s/dem/1/1/0.png", 1, 20, false)
        val p = ix.add("s/dem/1/1/1.png", 1, 5, true) // pinned: in no bucket
        fun order() = ArrayList<Int>().also { out -> ix.forEachOldest { out += it; true } }
        assertEquals(listOf(b, a, c), order())
        ix.touch(a, 20) // the same minute: to the tail of its bucket
        assertEquals(listOf(b, c, a), order())
        ix.touch(b, 30)
        assertEquals(listOf(c, a, b), order())
        ix.touch(b, 25) // a minute never goes back
        assertEquals(30, ix.minute(b))
        ix.setPinned(p, false) // joins its own minute's bucket, the oldest
        assertEquals(listOf(p, c, a, b), order())
        ix.setPinned(a, true)
        ix.remove(c)
        assertEquals(listOf(p, b), order())
        assertTrue(ix.isPinned(a))
        assertFalse(ix.isUsed(c))
        assertEquals(2, ix.bucketCount)
    }

    /**
     * The heap a million entries take (Done-when: under 48 B an entry), measured on this JVM as the heap in use before and
     * after (after collections), with the index's own estimate beside it. Keys as a downloaded area's: four groups, rows
     * of tiles, a thousand touch minutes.
     */
    @Test
    fun aMillionEntriesTakeUnder48BytesEach() {
        val n = 1_000_000
        val rt = Runtime.getRuntime()
        fun used(): Long {
            repeat(3) { System.gc(); Thread.sleep(50) }
            return rt.totalMemory() - rt.freeMemory()
        }
        val before = used()
        val ix = TileIndex()
        val routes = listOf("10.0.2.2_8770/dem@d1", "10.0.2.2_8770/osm@o1", "10.0.2.2_8770/contours-v9-ft-512@d1", "direct/dem")
        for (i in 0 until n) {
            val r = routes[i % routes.size]
            ix.add("$r/15/${5000 + (i / 1000)}/${11000 + (i % 1000)}.${if (r.contains("osm") || r.contains("contours")) "pbf" else "png"}", 30_000 + i % 5000, 29_000_000 + i % 1000, i % 3 == 0)
        }
        val after = used()
        val measured = (after - before).toDouble() / n
        val estimate = ix.heapBytes().toDouble() / n
        println("T242 index heap: $n entries, measured ${"%.1f".format(measured)} B an entry (${(after - before) / 1_000_000} MB), estimated ${"%.1f".format(estimate)} B, ${ix.groups} groups, ${ix.bucketCount} buckets")
        assertEquals(n, ix.count)
        assertTrue(estimate < 48, "estimated $estimate B an entry")
        assertTrue(measured < 48, "measured $measured B an entry")
    }
}
