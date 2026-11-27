package io.github.jasondorjeshort.dystopo.app.tracks

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T380: the kept folder's names, its plan and its passes against a fake documents provider that behaves as the phone's
 * does: a name already in a folder becomes "name (1).gpx" on a create and on a rename. Synthetic items only.
 */
class KeptFolderTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val me = "device-me"
    private val other = "device-other"

    /** 2026-07-04 09:30 at Paradise's zone (PDT, UTC-7). */
    private val start = 1783182600000L

    /** A folder: uri -> (parent, name, bytes); "root" and its "routes" folder. */
    private class FakeDocs : KeptDocs {
        class Doc(val parent: String, var name: String, var bytes: ByteArray)
        val docs = LinkedHashMap<String, Doc>()
        var up = true
        var next = 0
        var routesMade = 0

        fun names(parent: String = "root"): List<String> = docs.values.filter { it.parent == parent }.map { it.name }.sorted()
        fun byName(name: String, parent: String = "root"): Doc? = docs.values.firstOrNull { it.parent == parent && it.name == name }
        fun foreign(name: String, text: String, parent: String = "root") { docs["foreign-${next++}"] = Doc(parent, name, text.toByteArray()) }

        private fun unique(parent: String, name: String, except: String? = null): String {
            val taken = docs.filter { it.value.parent == parent && it.key != except }.values.map { it.name.lowercase() }.toSet()
            if (name.lowercase() !in taken) return name
            var k = 1
            while (true) {
                val n = name.removeSuffix(".gpx") + " ($k).gpx"
                if (n.lowercase() !in taken) return n
                k++
            }
        }

        override fun reachable() = up
        override fun folderOf(sub: String): String {
            check(up) { "gone" }
            if (sub.isEmpty()) return "root"
            if (docs.values.none { it.parent == "root" && it.name == sub }) { docs[sub] = Doc("root", sub, ByteArray(0)); routesMade++ }
            return sub
        }
        override fun create(parent: String, name: String, bytes: ByteArray): Pair<String, String> {
            check(up) { "gone" }
            val uri = "doc-${next++}"
            docs[uri] = Doc(parent, unique(parent, name), bytes)
            return uri to docs[uri]!!.name
        }
        override fun read(uri: String): ByteArray? { check(up) { "gone" }; return docs[uri]?.bytes }
        override fun write(uri: String, bytes: ByteArray) { check(up) { "gone" }; checkNotNull(docs[uri]) { "no file" }.bytes = bytes }
        override fun rename(uri: String, name: String): Pair<String, String>? {
            check(up) { "gone" }
            val d = docs[uri] ?: return null
            d.name = unique(d.parent, name, except = uri)
            return uri to d.name
        }
        override fun delete(uri: String): Boolean { check(up) { "gone" }; return docs.remove(uri) != null }
    }

    private fun track(id: String, name: String, at: Long = start, version: String = "v1", recording: Boolean = false, hidden: Boolean = false) =
        KeptItem("track", id, name, at, version, recording, hidden)

    private fun route(id: String, name: String, at: Long = start, version: String = "v1") = KeptItem("route", id, name, at, version)

    private fun gpxOf(it: KeptItem) = "<gpx>${it.kind} ${it.name} ${it.version}</gpx>".toByteArray()

    private class World(val docs: FakeDocs = FakeDocs()) {
        val ledger = ArrayList<LedgerEntry>()
        val counts = KeptCounts()
        val tombstones = HashMap<String, String>() // "kind:id" -> deleting device
    }

    private fun pass(w: World, items: List<KeptItem>?): KeptPass {
        val steps = keptPlan(items, w.ledger, { k, id -> w.tombstones["$k:$id"] }, me, zone)
        return keptApply(steps, w.ledger, w.docs, ::gpxOf, w.counts)
    }

    private fun plan(w: World, items: List<KeptItem>?) = keptPlan(items, w.ledger, { k, id -> w.tombstones["$k:$id"] }, me, zone)

    @Test
    fun namesAreDateFirstInThePhonesZoneWithRefusedCharactersReplaced() {
        assertEquals("2026-07-04 0930", keptStamp(start, zone))
        assertEquals("2026-07-04 1630", keptStamp(start, ZoneId.of("UTC")))
        assertNull(keptStamp(0, zone))
        assertEquals("2026-07-04 0930 Paradise loop.gpx", keptFileName("track", start, "Paradise loop", zone))
        assertEquals("2026-07-04 0930 Paradise - Camp Muir.gpx", keptFileName("track", start, "Paradise / Camp Muir", zone))
        assertEquals("2026-07-04 0930 a-b-c.gpx", keptFileName("track", start, "a:b*c?", zone))
        assertEquals("2026-07-04 0930 Track.gpx", keptFileName("track", start, "  ", zone))
        assertEquals("2026-07-04 0930 Route.gpx", keptFileName("route", start, "", zone))
        assertEquals("Route.gpx", keptFileName("route", 0, "", zone))
        assertEquals("2026-07-04 0930 Walk 7ceb89.gpx", keptFileName("track", start, "Walk", zone, "7ceb89d8-1111-2222-3333-444455556666"))
        assertEquals("abc123", shortId("ab-c1-23zz"))
        val long = keptFileName("track", start, "x".repeat(200), zone)
        assertEquals("2026-07-04 0930 ".length + 60 + ".gpx".length, long.length)
    }

    @Test
    fun aCollisionTakesTheShortIdOnlyForTheSecondAndStaysStable() {
        val w = World()
        val a = track("aaaaaaaa-1", "Walk")
        val b = track("bbbbbbbb-2", "Walk")
        assertEquals(KeptPass(2, 0, false), pass(w, listOf(a, b)))
        // the first by start (by id on a tie) keeps the plain name; the other takes its short id
        assertEquals(setOf("2026-07-04 0930 Walk.gpx", "2026-07-04 0930 Walk bbbbbb.gpx"), w.docs.names().toSet())
        assertTrue(plan(w, listOf(a, b)).isEmpty(), "a second pass with nothing changed does nothing")
        assertEquals(2, w.counts.writes)
    }

    @Test
    fun aFileTheAppDidNotWriteKeepsItsNameAndItsBytes() {
        val w = World()
        w.docs.foreign("2026-07-04 0930 Walk.gpx", "someone else's")
        pass(w, listOf(track("aaaaaaaa-1", "Walk")))
        assertEquals("someone else's", String(w.docs.byName("2026-07-04 0930 Walk.gpx")!!.bytes))
        assertEquals(setOf("2026-07-04 0930 Walk.gpx", "2026-07-04 0930 Walk aaaaaa.gpx"), w.docs.names().toSet())
        assertEquals("2026-07-04 0930 Walk.gpx", w.ledger.single().asked)
        assertEquals("2026-07-04 0930 Walk aaaaaa.gpx", w.ledger.single().name)
        assertTrue(plan(w, listOf(track("aaaaaaaa-1", "Walk"))).isEmpty(), "the name the folder gave is not fought every pass")
    }

    @Test
    fun aRenameMovesOnlyTheFileTheAppWroteAndLeavesNoDuplicate() {
        val w = World()
        pass(w, listOf(track("aaaaaaaa-1", "Walk")))
        w.docs.foreign("2026-07-04 0930 Summit.gpx", "a foreign summit")
        val renamed = track("aaaaaaaa-1", "Summit", version = "v2")
        val steps = plan(w, listOf(renamed))
        assertEquals(1, steps.size)
        assertEquals("2026-07-04 0930 Summit.gpx", (steps[0] as KeptStep.Update).rename)
        pass(w, listOf(renamed))
        assertEquals(setOf("2026-07-04 0930 Summit.gpx", "2026-07-04 0930 Summit aaaaaa.gpx"), w.docs.names().toSet(), "moved, not copied")
        assertEquals("a foreign summit", String(w.docs.byName("2026-07-04 0930 Summit.gpx")!!.bytes))
        assertEquals("<gpx>track Summit v2</gpx>", String(w.docs.byName("2026-07-04 0930 Summit aaaaaa.gpx")!!.bytes))
        assertEquals(1, w.counts.renames)
        assertEquals(1, w.ledger.size)
    }

    @Test
    fun aDeleteByTheUserRemovesTheFileTheAppWrote() {
        val w = World()
        pass(w, listOf(track("aaaaaaaa-1", "Walk"), track("cccccccc-3", "Other", at = start + 3600_000)))
        w.docs.foreign("notes.txt", "mine")
        w.tombstones["track:aaaaaaaa-1"] = me
        val steps = plan(w, listOf(track("cccccccc-3", "Other", at = start + 3600_000)))
        assertEquals(listOf("user"), steps.map { (it as KeptStep.Remove).by })
        pass(w, listOf(track("cccccccc-3", "Other", at = start + 3600_000)))
        assertEquals(setOf("2026-07-04 1030 Other.gpx", "notes.txt"), w.docs.names().toSet())
        assertEquals(1, w.counts.deletedByUser)
        assertEquals(1, w.ledger.size)
    }

    @Test
    fun aDeleteFromAPeerRemovesTheFileToo() {
        // The same user's delete on another device: the delete question says it reaches the other devices, and the folder follows it.
        val w = World()
        pass(w, listOf(route("rrrrrrrr-1", "Skyline")))
        assertEquals(listOf("2026-07-04 0930 Skyline.gpx"), w.docs.names(ROUTES_DIR))
        w.tombstones["route:rrrrrrrr-1"] = other
        pass(w, emptyList())
        assertEquals(emptyList(), w.docs.names(ROUTES_DIR))
        assertEquals(1, w.counts.deletedByPeer)
        assertEquals(0, w.counts.deletedByUser)
    }

    @Test
    fun aStoreThatIsEmptyOrUnreadableRemovesNothing() {
        val w = World()
        pass(w, listOf(track("aaaaaaaa-1", "Walk"), route("rrrrrrrr-1", "Skyline")))
        val before = w.docs.docs.mapValues { String(it.value.bytes) }
        assertTrue(plan(w, emptyList()).isEmpty(), "an empty store with no delete in it plans nothing")
        assertEquals(KeptPass(0, 0, false), pass(w, emptyList()))
        w.tombstones["track:aaaaaaaa-1"] = me
        assertTrue(plan(w, null).isEmpty(), "an unreadable store plans nothing, not even a delete it holds")
        pass(w, null)
        assertEquals(before, w.docs.docs.mapValues { String(it.value.bytes) })
        assertEquals(2, w.ledger.size)
    }

    @Test
    fun aHiddenItemKeepsItsFileAndIsWrittenLikeAnyOther() {
        val w = World()
        pass(w, listOf(track("aaaaaaaa-1", "Walk"), track("dddddddd-4", "Hidden one", at = start + 60_000, hidden = true)))
        assertEquals(setOf("2026-07-04 0930 Walk.gpx", "2026-07-04 0931 Hidden one.gpx"), w.docs.names().toSet())
        assertTrue(plan(w, listOf(track("aaaaaaaa-1", "Walk", hidden = true), track("dddddddd-4", "Hidden one", at = start + 60_000, hidden = true))).isEmpty())
        assertEquals(2, w.docs.names().size)
    }

    @Test
    fun aRecordingIsWrittenWhenItEnds() {
        val w = World()
        pass(w, listOf(track("aaaaaaaa-1", "Walk", recording = true)))
        assertTrue(w.docs.names().isEmpty())
        pass(w, listOf(track("aaaaaaaa-1", "Walk", version = "v2")))
        assertEquals(listOf("2026-07-04 0930 Walk.gpx"), w.docs.names())
    }

    @Test
    fun anEditedFileIsOverwrittenByARewriteAndKeptByADelete() {
        val w = World()
        pass(w, listOf(track("aaaaaaaa-1", "Walk"), track("bbbbbbbb-2", "Ridge", at = start + 120_000)))
        w.docs.byName("2026-07-04 0930 Walk.gpx")!!.bytes = "edited elsewhere".toByteArray()
        w.docs.byName("2026-07-04 0932 Ridge.gpx")!!.bytes = "edited too".toByteArray()
        pass(w, listOf(track("aaaaaaaa-1", "Walk", version = "v2"), track("bbbbbbbb-2", "Ridge", at = start + 120_000)))
        assertEquals("<gpx>track Walk v2</gpx>", String(w.docs.byName("2026-07-04 0930 Walk.gpx")!!.bytes))
        assertEquals(1, w.counts.overwrittenEdits)
        w.tombstones["track:bbbbbbbb-2"] = me
        pass(w, listOf(track("aaaaaaaa-1", "Walk", version = "v2")))
        assertEquals("edited too", String(w.docs.byName("2026-07-04 0932 Ridge.gpx")!!.bytes), "a delete leaves a file someone else changed")
        assertEquals(1, w.counts.keptEdited)
        assertEquals(0, w.counts.deletedByUser)
        assertEquals(listOf("aaaaaaaa-1"), w.ledger.map { it.id })
    }

    @Test
    fun aFileRemovedByHandIsWrittenAgainAtTheNextChange() {
        val w = World()
        pass(w, listOf(track("aaaaaaaa-1", "Walk")))
        w.docs.docs.clear()
        pass(w, listOf(track("aaaaaaaa-1", "Walk", version = "v2")))
        assertEquals(listOf("2026-07-04 0930 Walk.gpx"), w.docs.names())
        assertEquals(1, w.counts.recreated)
    }

    @Test
    fun aLostFolderWritesNothingAndTheChangesWaitUntilItIsBack() {
        val w = World()
        pass(w, listOf(track("aaaaaaaa-1", "Walk")))
        w.docs.up = false
        val items = listOf(track("aaaaaaaa-1", "Summit", version = "v2"), route("rrrrrrrr-1", "Skyline"))
        assertEquals(KeptPass(0, 2, true), pass(w, items))
        assertEquals("2026-07-04 0930 Walk.gpx", w.ledger.single().asked, "the ledger stays as it was")
        assertEquals(2, plan(w, items).size, "the changes are still there to write")
        w.docs.up = true
        assertEquals(KeptPass(2, 0, false), pass(w, items))
        assertEquals(listOf("2026-07-04 0930 Summit.gpx"), w.docs.names().filter { it.endsWith(".gpx") })
        assertEquals(listOf("2026-07-04 0930 Skyline.gpx"), w.docs.names(ROUTES_DIR))
    }

    @Test
    fun aFolderLostMidPassStopsThere() {
        val w = World()
        val docs = object : KeptDocs by w.docs {
            var creates = 0
            override fun create(parent: String, name: String, bytes: ByteArray): Pair<String, String> {
                if (++creates == 2) w.docs.up = false
                return w.docs.create(parent, name, bytes)
            }
        }
        val steps = plan(w, listOf(track("aaaaaaaa-1", "A"), track("bbbbbbbb-2", "B", at = start + 60_000), track("cccccccc-3", "C", at = start + 120_000)))
        val r = keptApply(steps, w.ledger, docs, ::gpxOf, w.counts)
        assertTrue(r.lost)
        assertEquals(1, r.done)
        assertEquals(2, r.left)
    }

    @Test
    fun stopKeepingForgetsTheFolderAndKeepsTheLedgerForTheSameFolder() {
        val s = KeptState()
        assertNull(s.keep("tree:a", "Tracks"))
        s.entries += LedgerEntry("track", "1", "doc-1", "x.gpx", "x.gpx", "h", "v")
        assertEquals("tree:a", s.stop())
        assertNull(s.tree)
        assertEquals("tree:a", s.stoppedTree)
        assertEquals(1, s.entries.size, "the files and what the app knows of them stay")
        assertNull(s.keep("tree:a", "Tracks"))
        assertEquals(1, s.entries.size, "the same folder picked again goes on with its ledger")
        assertEquals("tree:a", s.keep("tree:b", "Other"), "another folder: the old one's permission is released")
        assertTrue(s.entries.isEmpty())
        s.lost = true
        s.keep("tree:b", "Other")
        assertFalse(s.lost, "a folder picked again ends the lost state")
    }

    @Test
    fun theStateRoundTripsThroughItsFile() {
        val s = KeptState()
        s.keep("content://t/tree/primary%3ADocuments%2FKept", "Kept")
        s.routesDir = "content://t/doc/routes"
        s.entries += LedgerEntry("route", "r1", "content://t/doc/1", "a.gpx", "a (1).gpx", "abc", "v|1")
        s.counts.writes = 3
        s.counts.deletedByPeer = 1
        s.lost = true
        val back = KeptState.of(MiniJson.parseObject(MiniJson.encode(s.json())))
        assertEquals(s.json(), back.json())
    }

    @Test
    fun theWords() {
        assertEquals("3 tracks and 1 route will be written to Kept.", keptCount(3, 1, "Kept"))
        assertEquals("1 track will be written to Kept.", keptCount(1, 0, "Kept"))
        assertEquals("Nothing to write yet; new recordings will be written to Kept.", keptCount(0, 0, "Kept"))
        assertEquals("2 changes will be written to Kept.", keptCount(5, 1, "Kept", again = true, changes = 2))
        assertEquals("Kept in Kept · 1 file", keptLine("Kept", 1, 0))
        assertEquals("Kept in Kept · 12 files · 2 to write", keptLine("Kept", 12, 2))
        assertEquals("The folder Kept can't be reached, so nothing is written there. 1 change waits until you pick it again.", keptLost("Kept", 1))
        assertEquals("The folder Kept can't be reached, so nothing is written there. 3 changes wait until you pick it again.", keptLost("Kept", 3))
        assertTrue(keptLost("Kept", 0).endsWith("Nothing is written until you pick it again."))
        assertEquals("Stopped keeping Kept. Its files stay there.", keptStopped("Kept"))
        assertEquals("Wrote 1 GPX file to Kept", keptWrote(1, "Kept"))
        assertTrue("overwritten" in KEPT_SETUP && "other apps" in KEPT_SETUP.lowercase() && "each new recording" in KEPT_SETUP)
    }
}
