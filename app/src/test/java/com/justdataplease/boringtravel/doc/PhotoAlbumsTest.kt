package com.justdataplease.boringtravel.doc

import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.ui.journal.EntryInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAlbumsTest {
    private fun album(layout: String, vararg ids: String) = Block.Album(layout, ids.toList())

    private fun blocksJson(doc: EntryDoc): List<JsonObject> =
        Json.parseToJsonElement(doc.toJson()).jsonObject["blocks"]!!.jsonArray.map { it.jsonObject }

    // ------------------------------------------------------------------------------------------- storage

    @Test fun layoutsSurviveSaveAndReopen() {
        val doc = EntryDoc("literata", listOf(
            Block.Paragraph("Same rocks twice."),
            album("4-big", "a", "b", "c", "d"),
            Block.Paragraph("Then Naousa."),
            album("2-side", "e", "f"),
        ))
        var again = doc
        repeat(3) { again = EntryDoc.parse(again.toJson()) }
        assertEquals(doc, again)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), again.mediaIds())
    }

    @Test fun layoutsNextToEachOtherStayApart() {
        val doc = EntryDoc(blocks = listOf(
            album("2-side", "a", "b"),
            album("2-side", "c", "d"),
            album("3-top", "e", "f", "g"),
            Block.Photo("h", frame = PhotoFrame.Rounded),
        ))
        assertEquals(doc, EntryDoc.parse(doc.toJson()))
    }

    @Test fun olderVersionsReadALayoutAsSinglePhotos() {
        val doc = EntryDoc(blocks = listOf(Block.Paragraph("x"), album("4-grid", "a", "b", "c", "d")))
        val objs = blocksJson(doc).drop(1)
        // Every photo is an ordinary photo block an older version shows (rounded, full width, like a new photo).
        assertEquals(listOf("a", "b", "c", "d"), objs.map { it["media"]!!.jsonPrimitive.content })
        assertTrue(objs.all { it["t"]!!.jsonPrimitive.content == "img" && it["frame"]!!.jsonPrimitive.content == "rounded" })
        assertEquals(listOf(0, 1, 2, 3), objs.map { it["spot"]!!.jsonPrimitive.content.toInt() })
        // An older version saves them back without the layout keys: still every photo, as single photos.
        val resaved = doc.toJson().replace(Regex(""","album":"[^"]*","spot":\d+"""), "")
        val back = EntryDoc.parse(resaved)
        assertEquals(listOf("a", "b", "c", "d"), back.mediaIds())
        assertTrue(back.blocks.drop(1).all { it is Block.Photo && it.frame == PhotoFrame.Rounded })
    }

    @Test fun oldEntriesLoadAndSaveUnchanged() {
        val old = """{"v":1,"font":"literata","blocks":[{"t":"p","html":"a"},{"t":"img","media":"m1","frame":"rounded"},""" +
            """{"t":"img","media":"m2","rot":-4.0,"w":0.7,"frame":"polaroid","cap":"Halki"},{"t":"img","media":"m3"}]}"""
        val doc = EntryDoc.parse(old)
        assertEquals(4, doc.blocks.size)
        assertTrue(doc.blocks.drop(1).all { it is Block.Photo })
        assertEquals(old, doc.toJson())
    }

    @Test fun aLoneMarkedPhotoIsASinglePhoto() {
        val doc = EntryDoc.parse("""{"blocks":[{"t":"img","media":"m1","frame":"rounded","album":"4-grid","spot":0},{"t":"p","html":"x"}]}""")
        assertEquals(Block.Photo("m1", frame = PhotoFrame.Rounded), doc.blocks[0])
        // Spots that don't follow on start a new layout; a marked block without a photo is an ordinary block.
        val split = EntryDoc.parse(
            """{"blocks":[{"t":"img","media":"a","album":"2-side","spot":0},{"t":"img","media":"b","album":"2-side","spot":1},""" +
                """{"t":"img","media":"c","album":"2-side","spot":3},{"t":"img","media":"d","album":"2-side","spot":4},""" +
                """{"t":"img","media":"","album":"2-side","spot":5}]}""",
        )
        assertEquals(listOf(album("2-side", "a", "b"), album("2-side", "c", "d"), Block.Photo("")), split.blocks)
    }

    @Test fun layoutPhotosKeepTheirFrames() {
        val plain = album("2-side", "a", "b")
        val polaroid = album("4-grid", "c", "d", "e", "f").withFrame(PhotoFrame.Polaroid)
        // Each photo can have its own frame.
        val mixed = album("3-top", "g", "h", "i").withFrame("h", PhotoFrame.Tape).withFrame("i", PhotoFrame.Rounded)
        val doc = EntryDoc(blocks = listOf(plain, Block.Paragraph("x"), polaroid, mixed))
        var again = doc
        repeat(3) { again = EntryDoc.parse(again.toJson()) }
        assertEquals(doc, again)
        val objs = blocksJson(doc)
        // A plain photo of a layout is written as every one was before layouts had frames.
        assertTrue(objs.take(2).all { it["frame"]!!.jsonPrimitive.content == "rounded" && "lframe" !in it })
        // A framed one says so, and a version without layout frames shows it in that frame as a single photo.
        assertTrue(objs.subList(3, 7).all { it["frame"]!!.jsonPrimitive.content == "polaroid" && it["lframe"]!!.jsonPrimitive.content == "polaroid" })
        assertEquals(listOf(null, "tape", "rounded"), objs.subList(7, 10).map { it["lframe"]?.jsonPrimitive?.content })
        assertEquals(listOf("rounded", "tape", "rounded"), objs.subList(7, 10).map { it["frame"]!!.jsonPrimitive.content })
        // The frame they all have, or none when they differ.
        assertEquals(PhotoFrame.None, plain.frame)
        assertEquals(PhotoFrame.Polaroid, polaroid.frame)
        assertNull(mixed.frame)
        assertEquals(PhotoFrame.None, mixed.frameOf("g"))
        assertEquals(PhotoFrame.Tape, mixed.frameOf("h"))
        // Back to plain leaves nothing behind.
        assertEquals(album("4-grid", "c", "d", "e", "f"), polaroid.withFrame(PhotoFrame.None))
        assertEquals(album("3-top", "g", "h", "i"), mixed.withFrame("h", PhotoFrame.None).withFrame("i", PhotoFrame.None))
        // A layout saved before frames, or with a frame this version doesn't know, is plain.
        val old = """{"blocks":[{"t":"img","media":"a","frame":"rounded","album":"2-side","spot":0},{"t":"img","media":"b","frame":"rounded","album":"2-side","spot":1}]}"""
        assertEquals(plain, EntryDoc.parse(old).blocks.single())
        assertEquals(plain, EntryDoc.parse(old.replace(""""spot":0""", """"spot":0,"lframe":"glitter"""")).blocks.single())
    }

    @Test fun aFrameGoesWithItsPhoto() {
        val b = album("4-grid", "a", "b", "c", "d").withFrame(PhotoFrame.Polaroid).withFrame("b", PhotoFrame.Tape)
        // A photo added is framed as most of the layout is, or as asked.
        assertEquals(PhotoFrame.Polaroid, PhotoAlbums.mainFrame(b))
        assertEquals(PhotoFrame.None, PhotoAlbums.mainFrame(album("2-side", "a", "b")))
        val added = PhotoAlbums.add(b, listOf("e")).album
        assertEquals(PhotoFrame.Polaroid, added.frameOf("e"))
        assertEquals(PhotoFrame.Tape, added.frameOf("b"))
        assertEquals(PhotoFrame.Rounded, PhotoAlbums.add(b, listOf("e"), PhotoFrame.Rounded).album.frameOf("e"))
        assertEquals(PhotoFrame.None, PhotoAlbums.add(album("2-side", "a", "b"), listOf("c")).album.frameOf("c"))
        // It stays with its photo when photos change places or the arrangement changes, and leaves with it.
        assertEquals(b.frames, PhotoAlbums.swap(b, 0, 1).frames)
        assertEquals(b.frames, PhotoAlbums.move(b, 1, 3).frames)
        assertEquals(b.frames, PhotoAlbums.withLayout(b, "4-mix").frames)
        assertEquals(mapOf("a" to PhotoFrame.Polaroid, "c" to PhotoFrame.Polaroid, "d" to PhotoFrame.Polaroid), PhotoAlbums.remove(b, 1).frames)
        // Out of a layout a photo keeps its frame; a plain one is rounded, as a new photo is.
        assertEquals(PhotoFrame.Polaroid, PhotoAlbums.singleFrame(PhotoFrame.Polaroid))
        assertEquals(PhotoFrame.Rounded, PhotoAlbums.singleFrame(PhotoFrame.None))
    }

    @Test fun anUnknownArrangementIsKeptAndDrawnAsTheUsualOne() {
        val newer = album("12-mosaic", "a", "b", "c", "d")
        val back = EntryDoc.parse(EntryDoc(blocks = listOf(newer)).toJson())
        assertEquals(newer, back.blocks.single())
        assertEquals("4-grid", PhotoAlbums.layoutOf(newer)!!.id)
        // A known arrangement with too few spots is drawn in one that holds every photo.
        assertEquals("6-grid", PhotoAlbums.layoutOf(album("4-grid", "a", "b", "c", "d", "e"))!!.id)
    }

    @Test fun listsAndSearchSeeTheLayoutsPhotos() {
        val doc = EntryDoc(blocks = listOf(Block.Paragraph("Rocks."), album("3-side", "a", "b", "c"), Block.Photo("d")))
        assertEquals("Rocks.", DocRender.plainText(doc))
        val info = EntryInfo(Entry(id = "e1", occurredAt = 0, zoneId = "UTC", body = doc.toJson(), createdAt = 0, updatedAt = 0))
        assertEquals(listOf("a", "b", "c", "d"), info.photoIds)
        assertEquals(album("3-side", "a", "b", "c"), info.firstAlbum)
        assertTrue(info.meta.endsWith("4 photos"))
        val single = EntryInfo(Entry(id = "e2", occurredAt = 0, zoneId = "UTC", body = EntryDoc(blocks = listOf(Block.Photo("x"), album("2-side", "a", "b"))).toJson(), createdAt = 0, updatedAt = 0))
        assertNull(single.firstAlbum)
    }

    // -------------------------------------------------------------------------------------- layout choice

    @Test fun pickedPhotosGoInLayoutsForTheirCount() {
        assertEquals(emptyList<Int>(), PhotoAlbums.pickGroups(0))
        assertEquals(listOf(1), PhotoAlbums.pickGroups(1))
        assertEquals(listOf(2), PhotoAlbums.pickGroups(2))
        assertEquals(listOf(3), PhotoAlbums.pickGroups(3))
        assertEquals(listOf(4), PhotoAlbums.pickGroups(4))
        assertEquals(listOf(4, 1), PhotoAlbums.pickGroups(5))
        assertEquals(listOf(6), PhotoAlbums.pickGroups(6))
        assertEquals(listOf(6, 1), PhotoAlbums.pickGroups(7))
        assertEquals(listOf(6, 2), PhotoAlbums.pickGroups(8))
        assertEquals(listOf(9), PhotoAlbums.pickGroups(9))
        assertEquals(listOf(9, 3), PhotoAlbums.pickGroups(12))
        assertEquals(listOf(9, 9, 2), PhotoAlbums.pickGroups(20))
        for (n in 1..40) assertEquals(n, PhotoAlbums.pickGroups(n).sum())
        // Each group gets the first arrangement for its count.
        assertEquals("2-side", PhotoAlbums.usual(2).id)
        assertEquals("3-top", PhotoAlbums.usual(3).id)
        assertEquals("4-grid", PhotoAlbums.usual(4).id)
        assertEquals("6-grid", PhotoAlbums.usual(6).id)
        assertEquals("9-grid", PhotoAlbums.usual(9).id)
        assertEquals("6-grid", PhotoAlbums.usual(5).id)
    }

    @Test fun theSheetOffersTheArrangementsThatHoldThePhotos() {
        assertEquals(listOf("4-grid", "4-big", "4-mix"), PhotoAlbums.choices(album("4-grid", "a", "b", "c", "d")).map { it.id })
        assertEquals(listOf("6-grid", "6-wide", "6-big"), PhotoAlbums.choices(album("6-grid", "a", "b", "c", "d", "e")).map { it.id })
        assertEquals(4, PhotoAlbums.choices(album("3-top", "a", "b", "c")).size)
        val four = album("4-grid", "a", "b", "c", "d")
        assertEquals("4-mix", PhotoAlbums.withLayout(four, "4-mix").layoutId)
        // Not one with fewer spots than photos, nor an unknown id.
        assertSame(four, PhotoAlbums.withLayout(four, "3-top"))
        assertSame(four, PhotoAlbums.withLayout(four, "nope"))
    }

    @Test fun gridsShowEmptySpotsAndSplitWhatNoArrangementHolds() {
        val five = PhotoAlbums.grids(album("6-big", "a", "b", "c", "d", "e")).single()
        assertEquals("6-big", five.layout.id)
        assertEquals(listOf("a", "b", "c", "d", "e", null), five.spots)
        val eleven = PhotoAlbums.grids(album("4-grid", *(1..11).map { "p$it" }.toTypedArray()))
        assertEquals(listOf(4, 4, 3), eleven.map { it.layout.count })
        assertEquals(listOf(0, 4, 8), eleven.map { it.first })
        assertEquals((1..11).map { "p$it" }, eleven.flatMap { it.spots })
        assertTrue(PhotoAlbums.grids(Block.Album("4-grid", emptyList())).isEmpty())
    }

    // ------------------------------------------------------------------------------------------- edits

    @Test fun addingFillsEmptySpotsThenGrows() {
        val fill = PhotoAlbums.add(album("6-big", "a", "b", "c", "d", "e"), listOf("f"))
        assertEquals(album("6-big", "a", "b", "c", "d", "e", "f"), fill.album)
        assertTrue(fill.rest.isEmpty())
        // Full: the next size's usual arrangement, photos after the others.
        val grow = PhotoAlbums.add(album("4-mix", "a", "b", "c", "d"), listOf("e"))
        assertEquals(album("6-grid", "a", "b", "c", "d", "e"), grow.album)
        assertEquals("9-grid", PhotoAlbums.add(album("4-grid", "a", "b", "c", "d"), listOf("e", "f", "g")).album.layoutId)
        // Nine at most; the rest are handed back.
        val full = PhotoAlbums.add(album("9-big", *(1..8).map { "p$it" }.toTypedArray()), listOf("x", "y", "z"))
        assertEquals("9-big", full.album.layoutId)
        assertEquals(9, full.album.mediaIds.size)
        assertEquals(listOf("y", "z"), full.rest)
        assertEquals(AlbumLayouts.max, full.album.mediaIds.size)
    }

    @Test fun removingAPhotoKeepsTheOrderAndFitsTheArrangement() {
        assertEquals(album("3-top", "a", "c", "d"), PhotoAlbums.remove(album("4-big", "a", "b", "c", "d"), 1))
        // No arrangement for exactly five: the current one keeps an empty spot.
        assertEquals(album("6-big", "a", "b", "c", "d", "e"), PhotoAlbums.remove(album("6-big", "a", "b", "c", "d", "e", "f"), 5))
        assertEquals(album("6-grid", "b", "c", "d", "e", "f", "g"), PhotoAlbums.remove(album("9-grid", "a", "b", "c", "d", "e", "f", "g"), 0))
        // Down to one: the editor makes it a single photo.
        assertEquals(listOf("b"), PhotoAlbums.remove(album("2-side", "a", "b"), 0).mediaIds)
        val same = album("2-side", "a", "b")
        assertSame(same, PhotoAlbums.remove(same, 2))
    }

    @Test fun reflowFitsALayoutLeftShort() {
        assertEquals("4-grid", PhotoAlbums.reflow(album("4-grid", "a", "b", "c", "d")).layoutId)
        assertEquals("3-top", PhotoAlbums.reflow(album("4-grid", "a", "b", "c")).layoutId)
        assertEquals("6-grid", PhotoAlbums.reflow(album("6-grid", "a", "b", "c", "d", "e")).layoutId)
        assertEquals("9-grid", PhotoAlbums.reflow(album("9-grid", "a", "b", "c", "d", "e", "f", "g", "h")).layoutId)
        assertEquals("1-full", PhotoAlbums.reflow(album("2-side", "a")).layoutId)
    }

    @Test fun swappingAndMovingPhotos() {
        val b = album("4-grid", "a", "b", "c", "d")
        assertEquals(listOf("d", "b", "c", "a"), PhotoAlbums.swap(b, 0, 3).mediaIds)
        assertEquals(listOf("b", "c", "a", "d"), PhotoAlbums.move(b, 0, 2).mediaIds)
        assertEquals(listOf("a", "d", "b", "c"), PhotoAlbums.move(b, 3, 1).mediaIds)
        assertSame(b, PhotoAlbums.swap(b, 1, 1))
        assertSame(b, PhotoAlbums.move(b, 0, 4))
        assertEquals("4-grid", PhotoAlbums.move(b, 0, 2).layoutId)
    }

    // ---------------------------------------------------------------------------------------------- HTML

    @Test fun htmlPlacesEachPhotoInItsSpot() {
        val doc = EntryDoc(blocks = listOf(album("4-big", "a", "b", "c", "missing")))
        val html = DocRender.html(doc) { id -> if (id == "missing") null else "file:///$id.jpg" }
        assertEquals(1, Regex("<figure class=\"photo-layout\"").findAll(html).count())
        assertEquals(3, Regex("<img ").findAll(html).count())
        assertTrue(html.contains("data-layout=\"4-big\""))
        // The big photo spans the whole width at the top; the small ones share the row below.
        assertTrue(html.contains("""<img src="file:///a.jpg" alt="" style="position:absolute;left:0%;top:0%;width:100%;"""))
        assertTrue(html.contains("padding-top:"))
        assertFalse(html.contains("missing"))
        // Nothing to show: no empty frame.
        assertEquals("", DocRender.html(EntryDoc(blocks = listOf(album("2-side", "x", "y")))) { null })
        // A layout framed all one way names the frame; framed each their own way, the photos do.
        val taped = DocRender.html(EntryDoc(blocks = listOf(album("2-side", "a", "b").withFrame(PhotoFrame.Tape)))) { "$it.jpg" }
        assertTrue(taped.contains("<figure class=\"photo-layout frame-tape\" data-layout=\"2-side\""))
        assertFalse(taped.contains("data-frame"))
        val mixed = DocRender.html(EntryDoc(blocks = listOf(album("2-side", "a", "b").withFrame("b", PhotoFrame.Polaroid)))) { "$it.jpg" }
        assertTrue(mixed.contains("<figure class=\"photo-layout\" data-layout=\"2-side\""))
        assertEquals(1, Regex("data-frame=\"polaroid\"").findAll(mixed).count())
    }

    @Test fun htmlStacksArrangementsWhenNoneHoldsAll() {
        val ids = (1..11).map { "p$it" }
        val html = DocRender.html(EntryDoc(blocks = listOf(Block.Album("9-grid", ids)))) { "$it.jpg" }
        assertEquals(2, Regex("class=\"photo-layout-grid\"").findAll(html).count())
        assertEquals(11, Regex("<img ").findAll(html).count())
        ids.forEach { assertEquals(it, 1, Regex("src=\"$it\\.jpg\"").findAll(html).count()) }
    }
}
