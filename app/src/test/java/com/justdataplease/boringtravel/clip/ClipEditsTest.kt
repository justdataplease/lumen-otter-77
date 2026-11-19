package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scene editor's pure parts: stored edits, undo, photo order and dragging on the frame. */
class ClipEditsTest {
    private val edits = ClipEdits(
        scenes = mapOf(
            "label:item:a" to SceneOverride(text = "El Chaltén", hidden = false, y = 0.42f, font = ClipFont.Serif, back = TextBack.Glass, color = TextColor.Dark),
            "label:day:2026-06-16" to SceneOverride(name = NamePlace.Bottom, hidden = true),
            "photo:m1" to SceneOverride(show = true, hold = 5.0, x = 0.3f, y = 0.7f, size = PhotoSize.L, tilt = -4f, caption = "Fitz Roy at dawn"),
            "photo:m2" to SceneOverride(show = false, photoRow = PhotoRow.Top, photoSide = PhotoSide.Right),
            ClipDesign.OPENING to SceneOverride(text = "Patagonia", dates = false),
            ClipDesign.END to SceneOverride(text = "Thanks for watching"),
        ),
        order = mapOf("label:item:a" to listOf("m2", "m1")),
    )

    // --- Stored edits ---------------------------------------------------------------------------------------------

    @Test fun editsSurviveTheRoundTrip() {
        val all = mapOf(ClipSource.Planned.name to edits, ClipSource.Stops.name to ClipEdits(order = mapOf("label:day:x" to listOf("p"))))
        assertEquals(all, ClipEditsJson.decode(ClipEditsJson.encode(all)))
        // Nothing to keep, nothing written for that source.
        assertEquals(emptyMap<String, ClipEdits>(), ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Planned" to ClipEdits()))))
    }

    @Test fun unknownKeysAndBadValuesAreIgnored() {
        val text = """
            {"v":7,"future":{"x":1},"sources":{
              "Planned":{"scenes":{
                 "label:item:a":{"text":"Naxos","font":"comic","y":3.5,"hidden":"yes","wobble":true},
                 "photo:m1":{"hold":99,"tilt":-4,"size":"xl","show":true,"x":0.5},
                 "photo:m2":"not an object",
                 "":{"text":"no key"},
                 "label:item:b":{"glitter":1}
              },"order":{"label:item:a":["m1",3,"m2","m1"],"label:item:b":[]},"later":[1,2]},
              "Stops":"broken",
              "Recorded":{"scenes":{}}
            }}
        """.trimIndent()
        val read = ClipEditsJson.decode(text)
        assertEquals(setOf("Planned"), read.keys)
        val e = read.getValue("Planned")
        assertEquals(SceneOverride(text = "Naxos"), e.scene("label:item:a"))
        assertEquals(SceneOverride(tilt = -4f, show = true, x = 0.5f), e.scene("photo:m1"))
        // Entries with nothing usable are left out.
        assertFalse(e.scenes.containsKey("label:item:b"))
        assertFalse(e.scenes.containsKey("photo:m2"))
        assertEquals(mapOf("label:item:a" to listOf("m1", "m2")), e.order)
        // Garbage of any kind reads as no edits.
        for (bad in listOf(null, "", "[]", "{", "{\"sources\":3}", "nonsense")) assertTrue(ClipEditsJson.decode(bad).isEmpty())
    }

    @Test fun anEmptyOverrideIsDroppedAndResetClearsAPlacesOrder() {
        val e = edits.change("photo:m2") { SceneOverride() }
        assertFalse(e.scenes.containsKey("photo:m2"))
        val reset = edits.reset("label:item:a")
        assertFalse(reset.scenes.containsKey("label:item:a"))
        assertTrue(reset.order.isEmpty())
        assertEquals(edits.scenes - "label:item:a", reset.scenes)
        assertTrue(ClipEdits().isEmpty)
        assertEquals(listOf("b", "a"), ClipEdits().withOrder("p", listOf("b", "a", "b")).order["p"])
        assertTrue(ClipEdits().withOrder("p", listOf("b")).withOrder("p", emptyList()).isEmpty)
    }

    @Test fun theDesignReadsItsOverrides() {
        val d = ClipDesign(name = NamePlace.Top, font = ClipFont.Theme).withEdits(edits)
        val auto = ClipLabel("Day 3", "Laguna Capri and Mirador Fitz Roy")
        assertEquals(ClipLabel("Day 3", "El Chaltén"), d.label("label:item:a", auto))
        assertNull(d.label("label:day:2026-06-16", auto))
        assertEquals(auto, d.label("label:other", auto))
        assertEquals(ClipFont.Serif, d.labelFont("label:item:a"))
        assertEquals(ClipFont.Theme, d.labelFont("label:other"))
        assertEquals(TextBack.Glass, d.labelBack("label:item:a"))
        assertEquals(TextColor.Dark, d.labelColor("label:item:a"))
        // A name the style hides shows when the scene says so; one it shows hides when the scene says so.
        val off = ClipDesign(name = NamePlace.Off).withEdits(edits)
        assertTrue(off.labelVisible("label:item:a"))
        assertFalse(off.labelVisible("label:other"))
        assertTrue(off.labelVisible(null).not())
        assertEquals(NamePlace.Top, off.labelPlace("label:item:a"))
        assertTrue(ClipDesign(name = NamePlace.Off, overrides = mapOf("label:x" to SceneOverride(name = NamePlace.Bottom))).labelVisible("label:x"))
        // The opening: hidden by the style, shown by the scene, and the other way round.
        assertFalse(ClipDesign(opening = Opening.None).openingVisible)
        assertTrue(ClipDesign(opening = Opening.None, overrides = mapOf(ClipDesign.OPENING to SceneOverride(hidden = false))).openingVisible)
        assertFalse(ClipDesign(overrides = mapOf(ClipDesign.OPENING to SceneOverride(hidden = true))).openingVisible)
        // Photo edits for the plan.
        val p = d.photoEdits()
        assertEquals(setOf("m2"), p.excluded)
        assertEquals(setOf("m1"), p.included)
        assertEquals(mapOf("m1" to 5.0), p.holds)
        assertEquals(edits.order, p.order)
        assertEquals(p, d.applyTo(ClipOptions()).photoEdits)
        // The globals ignore the edits; the edits come back out whole.
        assertEquals(ClipDesign(), d.globals)
        assertEquals(edits, d.edits)
    }

    // --- Undo -----------------------------------------------------------------------------------------------------

    @Test fun undoAndRedoStepThroughTheEdits() {
        var h = EditHistory(0)
        assertFalse(h.canUndo)
        h = h.edit(1).edit(2).edit(3)
        assertEquals(3, h.present)
        h = h.undo().undo()
        assertEquals(1, h.present)
        assertTrue(h.canRedo)
        h = h.redo()
        assertEquals(2, h.present)
        // A new edit drops what could be redone.
        h = h.edit(9)
        assertFalse(h.canRedo)
        assertEquals(listOf(0, 1, 2), h.past)
        // Nothing changes: no step. Past the ends: nothing happens.
        assertEquals(h, h.edit(9))
        assertEquals(0, EditHistory(0).undo().present)
        assertEquals(0, EditHistory(0).redo().present)
    }

    @Test fun typingInOneFieldIsOneStep() {
        var h = EditHistory("")
        for (s in listOf("N", "Na", "Nax", "Naxo", "Naxos")) h = h.edit(s, merge = "text:label:a")
        assertEquals("Naxos", h.present)
        assertEquals(listOf(""), h.past)
        // Another field, or an undo in between, starts a new step.
        h = h.edit("Naxos!", merge = "caption:photo:1")
        assertEquals(listOf("", "Naxos"), h.past)
        h = h.undo().edit("Paros", merge = "caption:photo:1")
        assertEquals(listOf("", "Naxos"), h.past)
        assertEquals("Paros", h.present)
    }

    @Test fun theHistoryKeepsItsLastSteps() {
        var h = EditHistory(0, limit = 5)
        for (i in 1..20) h = h.edit(i)
        assertEquals(listOf(15, 16, 17, 18, 19), h.past)
    }

    // --- Order ----------------------------------------------------------------------------------------------------

    @Test fun aPlacesOrderFillsItsSlots() {
        assertEquals(listOf("a", "b", "c"), ClipOrder.merge(listOf("a", "b", "c"), null))
        assertEquals(listOf("c", "a", "b"), ClipOrder.merge(listOf("a", "b", "c"), listOf("c", "a", "b")))
        // Photos the order doesn't know (new ones) keep their own slots; ids gone from the trip are skipped.
        assertEquals(listOf("c", "new", "a"), ClipOrder.merge(listOf("a", "new", "c"), listOf("gone", "c", "a")))
        // Only some photos are in the clip: they come in the wanted order.
        assertEquals(listOf("c", "a"), ClipOrder.merge(listOf("a", "c"), listOf("c", "b", "a")))
    }

    @Test fun eachPlaceIsArrangedOnItsOwn() {
        val ids = listOf("a1", "a2", "x", "b1", "b2", "b3")
        val groups = listOf("A", "A", null, "B", "B", "B")
        val order = mapOf("A" to listOf("a2", "a1"), "B" to listOf("b3", "b1", "b2"), "C" to listOf("x"))
        assertEquals(listOf("a2", "a1", "x", "b3", "b1", "b2"), ClipOrder.arrange(ids, groups, order))
        assertEquals(ids, ClipOrder.arrange(ids, groups, emptyMap()))
    }

    @Test fun movingAPhoto() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), ClipOrder.move(ids, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), ClipOrder.move(ids, 3, 0))
        assertEquals(ids, ClipOrder.move(ids, 1, 1))
        assertEquals(listOf("a", "b", "d", "c"), ClipOrder.move(ids, 2, 99))
    }

    // --- Dragging on the frame ------------------------------------------------------------------------------------

    @Test fun aDragOnTheFrameBecomesAShareOfIt() {
        // 300 px tall view: dragging 60 px down from the middle lands at 70 %.
        assertEquals(0.7f, SceneDrag.move(0.5f, 60f, 300f), 1e-6f)
        assertEquals(0.3f, SceneDrag.move(0.5f, -60f, 300f), 1e-6f)
        // Never past where the renderer can draw it.
        assertEquals(0.8f, SceneDrag.move(0.5f, 600f, 300f, 0.2f..0.8f), 1e-6f)
        assertEquals(0.2f, SceneDrag.move(0.5f, -600f, 300f, 0.2f..0.8f), 1e-6f)
        assertEquals(0.5f, SceneDrag.move(0.5f, 10f, 0f), 1e-6f)
        assertEquals(0.123f, SceneDrag.rounded(0.12345f), 1e-6f)
    }

    @Test fun storedPositionsReachTheEdgesOfTheFrame() {
        // Owner, 2026-11-09: "position does not go to bottom": a name or a card placed by hand goes anywhere in the
        // frame, 8 dp from its edges (a story's bands for the apps' buttons only hold the default spots).
        val story = ClipLayout(1080, 1920, story = true)
        val edge = 8f * story.u
        val blockH = 150f
        val range = story.blockRangeY(blockH)
        assertEquals((edge + blockH / 2) / 1920f, range.start, 1e-6f)
        assertEquals((1920 - edge - blockH / 2) / 1920f, range.endInclusive, 1e-6f)
        // The middle sits in the middle; the very top and bottom stop just short of the edges.
        assertEquals(960f - blockH / 2, story.blockTopAt(0.5f, blockH), 1e-3f)
        assertEquals(edge, story.blockTopAt(0f, blockH), 1e-3f)
        assertEquals(1920 - edge - blockH, story.blockTopAt(1f, blockH), 1e-3f)
        assertTrue(1920 - edge - blockH > 1920 - story.safeBottom - blockH)
        // Across too: a block 400 px wide at the far left and right.
        assertEquals(edge, story.blockLeftAt(0f, 400f), 1e-3f)
        assertEquals(1080 - edge - 400f, story.blockLeftAt(1f, 400f), 1e-3f)
        // A card placed by hand stays whole inside the frame.
        val w = 400f
        val h = 500f
        for (x in listOf(0f, 0.5f, 1f)) for (y in listOf(0f, 0.5f, 1f)) {
            val (cx, cy) = story.cardAt(x, y, w, h)
            assertTrue(cx - w / 2 >= edge - 1e-3f && cx + w / 2 <= 1080 - edge + 1e-3f)
            assertTrue(cy - h / 2 >= edge - 1e-3f && cy + h / 2 <= 1920 - edge + 1e-3f)
        }
        // At the bottom it reaches well past the story's band.
        assertTrue(story.cardAt(0.5f, 1f, w, h).second + h / 2 > 1920 - story.safeBottom)
        // Film's bars stay clear.
        val film = ClipLayout(1080, 1920, story = true, bars = true)
        assertTrue(film.blockTopAt(1f, blockH) + blockH <= 1920 - film.bar)
        assertEquals(540f to 960f, story.cardAt(0.5f, 0.5f, w, h))
        // A drag that starts at a card's centre and moves by a frame share lands exactly there (view 1080 → 360 px).
        val start = story.cardAt(0.5f, 0.5f, w, h).first / 1080f
        val moved = SceneDrag.move(start, 36f, 360f, story.cardRangeX(w))
        assertEquals(0.6f, moved, 1e-6f)
        assertEquals(0.6f * 1080f, story.cardAt(moved, 0.5f, w, h).first, 1e-3f)
        // A name block in the upper half keeps cards below it; in the lower half, above it.
        val name = FrameBox(300f, 500f, 780f, 650f)
        val (_, top) = story.cardCentre(PhotoRow.Top, PhotoSide.Left, 0, w, h, name)
        assertTrue(top - h / 2 >= name.bottom)
        val low = FrameBox(300f, 1300f, 780f, 1450f)
        val (_, bottom) = story.cardCentre(PhotoRow.Bottom, PhotoSide.Left, 0, w, h, low)
        assertTrue(bottom + h / 2 <= low.top)
    }
}
