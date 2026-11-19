package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.SampleKyoto
import com.justdataplease.boringtravel.ui.clip.PreviewMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Looks and sizes (owner: "Looks and sizes"): the four shapes end to end (output size, camera framing, text bands,
 * the preview), Film's letterbox bars, the named looks and their stored choices.
 */
class ClipLooksTest {
    // --- Sizes ----------------------------------------------------------------------------------------------------

    @Test fun fourShapesInTheOrderSocialAppsUseThem() {
        assertEquals(listOf("9:16", "4:5", "1:1", "16:9"), ClipShape.entries.map { it.label })
        assertEquals(1080 to 1350, ClipShape.Portrait.width to ClipShape.Portrait.height)
        for (shape in ClipShape.entries) {
            assertEquals(shape, ClipShape.of(shape.id))
            // Even sizes for H.264, 1080 on the short side.
            assertEquals(0, shape.width % 2)
            assertEquals(0, shape.height % 2)
            assertEquals(1080, minOf(shape.width, shape.height))
        }
        assertNull(ClipShape.of("cinema"))
        assertEquals(540 to 675, PreviewMath.size(ClipShape.Portrait, PreviewMath.FULL_SHORT))
    }

    private val kyoto: ClipInput by lazy {
        val plan = SampleKyoto.plan(LocalDate.of(2027, 10, 3), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), ZoneId.of("Asia/Tokyo")).getValue(ClipSource.Planned)
    }

    @Test fun everyShapeFramesTheWholeRouteAndFollowsTheHead() {
        for (shape in ClipShape.entries) {
            val p = requireNotNull(ClipPlan.build(kyoto, ClipOptions(shape = shape), Locale.ENGLISH))
            // The overview shows every point of the route inside its spare room.
            val r = p.overview.rect(shape.aspect)
            for (i in p.xs.indices) {
                assertTrue("$shape x", p.xs[i] >= r.x0 + r.w * 0.11 && p.xs[i] <= r.x1 - r.w * 0.11)
                assertTrue("$shape y", p.ys[i] >= r.y0 + r.h * 0.14 && p.ys[i] <= r.y1 - r.h * 0.14)
            }
            for (f in 0 until p.frameCount) {
                val st = p.frame(f)
                val v = st.camera.rect(shape.aspect)
                val fx = (st.headX - v.x0) / v.w
                val fy = (st.headY - v.y0) / v.h
                assertTrue("$shape frame $f head $fx,$fy", fx in 0.05..0.95 && fy in 0.05..0.95)
            }
            // The camera's view has the shape's proportions; a taller frame shows more ground up and down.
            assertEquals(shape.aspect, p.overview.spanX / p.overview.spanY(shape.aspect), 1e-9)
        }
    }

    @Test fun textsAndCardsFitEachShape() {
        for (shape in ClipShape.entries) {
            val layout = ClipLayout(shape.width, shape.height, story = shape == ClipShape.Story)
            assertEquals(3f, layout.u, 1e-6f)
            val w = layout.cardWidth(shape, held = true, centred = false)
            assertTrue("$shape card ${w / shape.width}", w < shape.width * 0.8f)
            val h = w * 1.3f
            for (row in PhotoRow.entries) {
                val (_, y) = layout.cardCentre(row, PhotoSide.Centre, 0, w, h, NamePlace.Top, 90f)
                assertTrue("$shape $row", y - h / 2 >= -1f && y + h / 2 <= shape.height + 1f)
            }
        }
        // A feed post sits between the story and the square.
        val story = ClipLayout(1080, 1920, true).cardWidth(ClipShape.Story, held = false, centred = false)
        val post = ClipLayout(1080, 1350, false).cardWidth(ClipShape.Portrait, held = false, centred = false)
        val square = ClipLayout(1080, 1080, false).cardWidth(ClipShape.Square, held = false, centred = false)
        assertTrue(story > post && post > square)
    }

    @Test fun filmBarsCutAWideFrameToScopeAndStayInsideAStorysBands() {
        // 16:9 → a 2.39:1 picture.
        val wide = ClipLayout.filmBar(1920, 1080)
        assertEquals(1920 / 2.39, 1080.0 - 2 * wide, 0.5)
        // Upright and square frames: thin bands, inside a story's own clear bands.
        val story = ClipLayout.filmBar(1080, 1920)
        assertTrue(story / 1920 in 0.07f..0.08f)
        assertTrue(story < ClipLayout(1080, 1920, true).safeTop)
        assertEquals(1080 * 0.09f, ClipLayout.filmBar(1080, 1080), 0.5f)
        for (shape in ClipShape.entries) {
            val plain = ClipLayout(shape.width, shape.height, shape == ClipShape.Story)
            val barred = ClipLayout(shape.width, shape.height, shape == ClipShape.Story, bars = true)
            assertEquals(0f, plain.bar, 0f)
            assertTrue(barred.bar > 0f)
            // Texts keep clear of the bars.
            assertTrue("$shape top", barred.safeTop >= barred.bar + 9f * barred.u)
            assertTrue("$shape bottom", barred.safeBottom >= barred.bar + 9f * barred.u)
            assertTrue(barred.safeTop >= plain.safeTop && barred.safeBottom >= plain.safeBottom)
            val top = barred.blockTop(NamePlace.Top, 100f)
            val bottom = barred.blockTop(NamePlace.Bottom, 100f) + 100f
            assertTrue(top >= barred.bar && bottom <= shape.height - barred.bar)
        }
    }

    // --- Looks ----------------------------------------------------------------------------------------------------

    @Test fun postcardIsTheClipAsItAlwaysLooked() {
        assertEquals(ClipDesign(), ClipLook.Postcard.apply(ClipDesign()))
        assertEquals(ClipLook.Postcard, ClipDesign().look)
        assertEquals(TextSize.Normal, ClipDesign().textSize)
        assertFalse(ClipDesign().bars || ClipDesign().grain)
        assertEquals(ClipTone.Natural, ClipDesign().tone)
    }

    @Test fun eachLookSetsItsChoicesTogether() {
        val film = ClipLook.Film.apply(ClipDesign())
        assertTrue(film.bars && film.grain)
        assertEquals(ClipTone.Warm, film.tone)
        assertEquals(TextBack.None, film.back)
        assertEquals(LineWeight.Thin, film.weight)
        val minimal = ClipLook.Minimal.apply(ClipDesign())
        assertEquals(TextBack.None, minimal.back)
        assertEquals(LineWeight.Thin, minimal.weight)
        assertEquals(TextSize.Small, minimal.textSize)
        assertFalse(minimal.bars)
        val bold = ClipLook.Bold.apply(ClipDesign())
        assertEquals(TextSize.Large, bold.textSize)
        assertEquals(LineWeight.Bold, bold.weight)
        assertEquals(TextBack.Panel, bold.back)
        for (look in ClipLook.entries) {
            val d = look.apply(ClipDesign())
            assertEquals(look, d.look)
            assertEquals(look, ClipLook.of(look.id))
            // Looks differ from each other.
            for (other in ClipLook.entries) if (other != look) assertTrue(other.apply(ClipDesign()) != d)
        }
        assertTrue(TextSize.Small.scale < TextSize.Normal.scale && TextSize.Normal.scale < TextSize.Large.scale)
    }

    @Test fun aLookKeepsPhotosCameraAndEditsAndSingleOptionsStillChange() {
        val mine = ClipDesign(
            name = NamePlace.Bottom, opening = Opening.None, photoMode = PhotoMode.Pause3, photoRow = PhotoRow.Top,
            zoom = ClipZoom.Close, closeUp = CloseUp.VeryClose, overrides = mapOf("label:day:2027-10-04" to SceneOverride(text = "Gion")),
        )
        val film = ClipLook.Film.apply(mine)
        assertEquals(NamePlace.Bottom, film.name)
        assertEquals(Opening.None, film.opening)
        assertEquals(PhotoMode.Pause3, film.photoMode)
        assertEquals(PhotoRow.Top, film.photoRow)
        assertEquals(ClipZoom.Close, film.zoom)
        assertEquals(CloseUp.VeryClose, film.closeUp)
        assertEquals(mine.overrides, film.overrides)
        assertEquals(ClipLook.Film, film.look)
        // One option changed after: still that design, no longer the named look ("custom").
        val tweaked = film.copy(grain = false)
        assertNull(tweaked.look)
        assertTrue(tweaked.bars)
        // Picking the look again brings it back.
        assertEquals(ClipLook.Film, ClipLook.Film.apply(tweaked).look)
        // A look never changes the plan's options (photos, pauses, zoom).
        assertEquals(mine.applyTo(ClipOptions()), film.applyTo(ClipOptions()))
    }

    @Test fun looksAndFinishSurviveThePrefsAndOldPrefsLoadAsBefore() {
        for (look in ClipLook.entries) {
            val d = look.apply(ClipDesign(name = NamePlace.Bottom))
            assertEquals(d, ClipPrefs.decode(ClipPrefs.encode(d)))
        }
        for (v in TextSize.entries) assertEquals(v, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(textSize = v))).textSize)
        for (v in ClipTone.entries) assertEquals(v, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(tone = v))).tone)
        // A string from before looks (no textSize, bars, tone, grain): the defaults, so the clip looks as it did.
        val old = "opening=title;name=top;font=serif;back=panel;panel=rounded;color=light;line=solid;weight=medium;" +
            "lineColor=theme;photoRow=bottom;photoSide=both;photos=pause2;zoom=normal;closeUp=off"
        val d = ClipPrefs.decode(old)
        assertEquals(ClipFont.Serif, d.font)
        assertEquals(TextSize.Normal, d.textSize)
        assertFalse(d.bars || d.grain)
        assertEquals(ClipTone.Natural, d.tone)
        // The side it kept (Both) stays until the one-time move to the new defaults (ClipPrefs.photoDefaults).
        assertEquals(ClipDesign(font = ClipFont.Serif, photoSide = PhotoSide.Both), d)
        assertEquals(false, ClipPrefs.decode("bars=maybe").bars)
    }
}
