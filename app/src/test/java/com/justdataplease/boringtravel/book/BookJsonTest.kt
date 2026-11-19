package com.justdataplease.boringtravel.book

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BookJsonTest {
    private fun sample() = BookDoc(
        paper = BookPaper.Lines,
        padTo4 = false,
        hidden = setOf("e9"),
        pages = listOf(
            BookPage("c", Template.Cover.id, slots = listOf(Slot("m1", Crop(1.5, 0.3, 0.62), tilt = -2.5, caption = "Halki")), title = "Cyclades", subtitle = "Paros → Naxos"),
            BookPage(
                "t", Template.PhotoText.id, slots = listOf(Slot("m2")), entryId = "e1", day = LocalDate.of(2027, 6, 19),
                paras = listOf(Para(ParaKind.TITLE, "The bus", "e1"), Para(ParaKind.P, "Forty <b>late</b>", "e1"), Para(ParaKind.P, "rest", "e1", cont = true)),
            ),
            BookPage("x", Template.Text.id, start = false, paras = listOf(Para(ParaKind.DONE, "Postcards", "e1"))),
        ),
    )

    @Test fun roundTrip() {
        val doc = sample()
        val back = BookJson.decode(BookJson.encode(doc))
        assertEquals(doc, back)
    }

    @Test fun unknownFieldsTemplatesAndKindsAreKept() {
        val json = """{"v":2,"paper":"dots","pad4":true,"future":{"a":1},"pages":[
            {"id":"a","t":"hologram","glow":true,"photos":[{"m":"m1","sparkle":3}],"paras":[{"k":"poem","h":"Roses","rhyme":"aabb"}]}]}"""
        val doc = BookJson.decode(json)
        val p = doc.pages.single()
        assertEquals(null, p.template)
        assertEquals("hologram", p.templateId)
        assertEquals("poem", p.paras.single().kind)
        val out = Json.parseToJsonElement(BookJson.encode(doc)).jsonObject
        assertEquals("1", out["future"]!!.jsonObject["a"]!!.jsonPrimitive.content)
        val page = out["pages"]!!.jsonArray[0].jsonObject
        assertEquals("hologram", page["t"]!!.jsonPrimitive.content)
        assertEquals("true", page["glow"]!!.jsonPrimitive.content)
        assertEquals("3", (page["photos"]!!.jsonArray[0] as JsonObject)["sparkle"]!!.jsonPrimitive.content)
        val para = page["paras"]!!.jsonArray[0].jsonObject
        assertEquals("poem", para["k"]!!.jsonPrimitive.content)
        assertEquals("aabb", para["rhyme"]!!.jsonPrimitive.content)
        // Written by this version.
        assertEquals(BookDoc.VERSION.toString(), out["v"]!!.jsonPrimitive.content)
    }

    @Test fun damagedOrEmptyReadsAsAnEmptyBook() {
        assertEquals(BookDoc(), BookJson.decode(null))
        assertEquals(BookDoc(), BookJson.decode(""))
        assertEquals(BookDoc(), BookJson.decode("{not json"))
        assertEquals(BookDoc(), BookJson.decode("[1,2]"))
        // Pages without an id are dropped; the rest stay.
        assertEquals(1, BookJson.decode("""{"pages":[{"t":"text"},{"id":"ok","t":"notes"}]}""").pages.size)
    }

    @Test fun cropAndTiltAreClampedAndTextIsCleaned() {
        val doc = BookJson.decode("""{"pages":[{"id":"a","t":"photo","photos":[{"m":"m","z":40,"x":-3,"y":"no","tilt":99}],
            "paras":[{"k":"p","h":"<script>alert(1)</script>hi <b>there</b><img src=x>"}]}]}""")
        val s = doc.pages.single().slots.single()
        assertEquals(CropMath.MAX_ZOOM, s.crop.zoom, 0.0)
        assertEquals(0.0, s.crop.cx, 0.0)
        assertEquals(0.5, s.crop.cy, 0.0)
        assertEquals(BookJson.MAX_TILT, s.tilt!!, 0.0)
        val html = doc.pages.single().paras.single().html
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("<b>there</b>"))
    }

    @Test fun defaultsAreLeftOutOfTheJson() {
        val out = BookJson.encode(BookDoc(pages = listOf(BookPage("a", Template.Photo.id, slots = listOf(Slot("m"))))))
        assertFalse(out.contains("\"z\""))
        assertFalse(out.contains("\"start\""))
        assertFalse(out.contains("\"hidden\""))
        assertTrue(out.contains("\"v\":2"))
    }

    private fun freePage() = BookPage(
        "f", Template.Photo.id, free = true, slots = listOf(Slot("spare")),
        bg = PageBg(PageBg.PHOTO, photo = Slot("bgm", Crop(1.2, 0.4, 0.5)), dim = 0.35),
        els = listOf(
            FreeEl("t0", FreeKind.PHOTO, Box(12.5, 20.0, 90.0, 64.25), -2.5, derived = true, photo = Slot("m1", Crop(1.5, 0.3, 0.62), caption = "Halki"),
                style = "print", pad = Pad(2.3, 2.3, 2.3, 8.0), tapes = listOf(Tape(45.0, 1.3, 16.5, 5.2, -4.0)), band = true),
            FreeEl("t1", FreeKind.TEXT, Box(12.5, 90.0, 80.0, 10.0), derived = true, bind = "cap:t0", font = "display", pt = 12.5, lh = 1.6, role = "cap"),
            FreeEl("w", FreeKind.TEXT, Box(10.0, 150.0, 100.0, 22.0), 12.0, text = "Two\nlines", font = "Lora", pt = 24.0, lh = 1.4, align = "center",
                bold = true, italic = true, upper = true, mark = true, color = "#1E2A5A"),
            FreeEl("fl", FreeKind.FLOW, Box(15.0, 100.0, 118.0, 80.0), derived = true, cols = 2),
            FreeEl("s", FreeKind.STAMP, Box(100.0, 10.0, 30.0, 30.0), -9.0, style = "day", words = listOf("NAXOS", "4", "19 JUN", "DAY")),
            FreeEl("r", FreeKind.ROUTE, Box(15.0, 120.0, 100.0, 60.0), src = "day", labels = true, markers = true, scaleBar = true),
            FreeEl("g", FreeKind.FLAGS, Box(15.0, 40.0, 30.0, 6.0), codes = listOf("GR", "IT"), flagH = 5.0, style = "sticker", align = "right"),
            FreeEl("ru", FreeKind.RULE, Box(15.0, 185.0, 118.0, 0.0)),
            FreeEl("li", FreeKind.LINES, Box(15.0, 30.0, 118.0, 150.0), style = "lines", pitch = 7.0),
            FreeEl("tp", FreeKind.TAPE, Box(40.0, 15.0, 34.0, 9.0), -6.0, color = "#F2DE6B"),
            FreeEl("co", FreeKind.CORNERS, Box(20.0, 20.0, 60.0, 40.0), color = "#2B2B2B"),
            FreeEl("sh", FreeKind.SHAPE, Box(20.0, 20.0, 40.0, 40.0), 15.0, style = "oval", fill = "#F7E36A", color = "#1E2A5A", stroke = 0.5),
            FreeEl("ln", FreeKind.LINE, Box(20.0, 190.0, 80.0, 4.0), color = "#C4423A", stroke = 0.6),
        ),
    )

    @Test fun freePagesRoundTripWithEveryKindOfElementAndABackground() {
        val doc = BookDoc(pages = listOf(freePage(), BookPage("plain", Template.Text.id, bg = PageBg(PageBg.COLOR, color = "#F4EFE6"))))
        assertEquals(doc, BookJson.decode(BookJson.encode(doc)))
        val out = Json.parseToJsonElement(BookJson.encode(doc)).jsonObject
        assertEquals("2", out["v"]!!.jsonPrimitive.content)
        val el = out["pages"]!!.jsonArray[0].jsonObject["els"]!!.jsonArray[0].jsonObject
        // Positions in mm, the turn in degrees: what the page's HTML uses.
        assertEquals("12.5", el["x"]!!.jsonPrimitive.content)
        assertEquals("64.25", el["h"]!!.jsonPrimitive.content)
        assertEquals("-2.5", el["r"]!!.jsonPrimitive.content)
    }

    @Test fun version1ReadsAsItIsAndIsWrittenAsVersion2() {
        val v1 = """{"v":1,"paper":"lines","pad4":false,"mystery":[1,2],"pages":[
            {"id":"a","t":"photo-2","photos":[{"m":"m1","z":1.5},{"m":"m2"}],"future":"yes"},
            {"id":"b","t":"text","start":false,"paras":[{"k":"p","h":"Hello"}]}]}"""
        val doc = BookJson.decode(v1)
        assertEquals(BookPaper.Lines, doc.paper)
        assertEquals(2, doc.pages.size)
        assertFalse(doc.pages[0].free)
        assertTrue(doc.pages.all { it.els.isEmpty() && it.bg == null })
        assertEquals(listOf("m1", "m2"), doc.pages[0].slots.map { it.mediaId })
        assertFalse(doc.pages[1].start)
        val out = Json.parseToJsonElement(BookJson.encode(doc)).jsonObject
        assertEquals("2", out["v"]!!.jsonPrimitive.content)
        assertEquals(2, out["mystery"]!!.jsonArray.size)
        assertEquals("yes", out["pages"]!!.jsonArray[0].jsonObject["future"]!!.jsonPrimitive.content)
        // Migrating is only a version bump; a document without a version is version 1.
        val m = BookJson.migrate(Json.parseToJsonElement("""{"pages":[]}""").jsonObject)
        assertEquals("2", m["v"]!!.jsonPrimitive.content)
        val newer = Json.parseToJsonElement("""{"v":3,"pages":[]}""").jsonObject
        assertEquals(newer, BookJson.migrate(newer))
    }

    @Test fun unknownElementFieldsAndKindsAreKeptAndBadValuesDropped() {
        val json = """{"v":2,"pages":[{"id":"a","t":"photo","free":true,
            "bg":{"k":"color","c":"#ABCDEF","glitter":true},
            "els":[
              {"id":"x","k":"sticker3d","x":1,"y":2,"w":3,"h":4,"depth":9},
              {"id":"y","k":"text","x":1,"y":2,"w":3,"h":4,"txt":"Hi","c":"red;background:url(x)","f":"Comic","role":"t\"><script>","al":"justify","glow":1},
              {"id":"bad id!","k":"text","x":1,"y":2,"w":3,"h":4},
              {"id":"z","k":"shape"}]}]}"""
        val p = BookJson.decode(json).pages.single()
        assertTrue(p.free)
        assertEquals(listOf("x", "y"), p.els.map { it.id })
        assertEquals("sticker3d", p.els[0].kind)
        val y = p.els[1]
        assertNull(y.color)
        assertNull(y.font)
        assertNull(y.role)
        assertEquals("left", y.align)
        assertEquals("#ABCDEF", p.bg?.color)
        val out = Json.parseToJsonElement(BookJson.encode(BookDoc(pages = listOf(p)))).jsonObject
        val page = out["pages"]!!.jsonArray[0].jsonObject
        assertEquals("9", page["els"]!!.jsonArray[0].jsonObject["depth"]!!.jsonPrimitive.content)
        assertEquals("1", page["els"]!!.jsonArray[1].jsonObject["glow"]!!.jsonPrimitive.content)
        assertEquals("true", page["bg"]!!.jsonObject["glitter"]!!.jsonPrimitive.content)
        // An element of an unknown kind is kept but draws nothing.
        val l = BookLayout(BookSpec(), BookFixtures.facts(), TextFit.Estimate, BookFixtures.ids())
        assertTrue(l.pages.plan(p, 3).ids.none { it == "x" })
    }

    @Test fun hiddenElementsAndBackgroundsRoundTripAndOldBooksReadAsShown() {
        // A version 2 book saved before hiding existed: everything shows, and nothing new is written.
        val old = """{"v":2,"paper":"dots","pad4":true,"pages":[{"id":"a","t":"photo","free":true,"bg":{"k":"color","c":"#F4EFE6"},"els":[
            {"id":"t0","k":"photo","x":1,"y":2,"w":30,"h":20,"d":true,"photo":{"m":"m1"},"st":"print"},
            {"id":"n1","k":"text","x":1,"y":30,"w":40,"h":8,"txt":"Naxos","f":"display","pt":24}]}]}"""
        val doc = BookJson.decode(old)
        val p = doc.pages.single()
        assertTrue(p.els.none { it.hidden })
        assertFalse(p.bg!!.hidden)
        assertFalse(BookJson.encode(doc).contains("\"hide\""))

        val hid = doc.copy(pages = listOf(p.copy(els = p.els.map { if (it.id == "n1") it.copy(hidden = true) else it }, bg = p.bg.copy(hidden = true))))
        val json = BookJson.encode(hid)
        assertEquals(2, Regex("\"hide\":true").findAll(json).count())
        val back = BookJson.decode(json)
        assertEquals(hid, back)
        assertTrue(back.pages.single().el("n1")!!.hidden)
        assertFalse(back.pages.single().el("t0")!!.hidden)
        assertTrue(back.pages.single().bg!!.hidden)
    }
}
