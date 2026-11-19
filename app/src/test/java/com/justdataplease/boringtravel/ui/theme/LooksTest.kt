package com.justdataplease.boringtravel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LooksTest {

    // ---- The five themes ----

    @Test
    fun pickerOrder() {
        assertEquals(
            listOf(Look.PostcardPop, Look.Trail, Look.StickerBook, Look.Sketchbook, Look.Market),
            Look.entries.toList(),
        )
        assertEquals(listOf("pop", "trail", "sticker", "sketch", "market"), Look.entries.map { it.id })
    }

    @Test
    fun postcardPopIsTheDefault() {
        assertEquals(Look.PostcardPop, Look.Default)
        assertEquals(Look.PostcardPop, Look.of(null))
    }

    @Test
    fun everyIdRoundTrips() {
        Look.entries.forEach { assertEquals(it, Look.of(it.id)) }
        assertEquals(Look.entries.size, Look.entries.map { it.id }.toSet().size)
    }

    @Test
    fun oldSavedIdsMapToTheNearestTheme() {
        assertEquals(Look.StickerBook, Look.of("notebook"))
        assertEquals(Look.Sketchbook, Look.of("lined"))
        assertEquals(Look.Sketchbook, Look.of("notebook2"))
        assertEquals(Look.Sketchbook, Look.of("field"))
        assertEquals(Look.StickerBook, Look.of("graph"))
        assertEquals(Look.Market, Look.of("kraft"))
        assertEquals(Look.Trail, Look.of("night"))
        assertEquals(Look.PostcardPop, Look.of("logbook"))
    }

    @Test
    fun unknownOrMissingIdIsPostcardPop() {
        assertEquals(Look.PostcardPop, Look.of(""))
        assertEquals(Look.PostcardPop, Look.of("Notebook"))
        assertEquals(Look.PostcardPop, Look.of("gridx"))
        assertEquals(Look.PostcardPop, Look.of("sepia"))
    }

    @Test
    fun retiredIdsAreNotInThePicker() {
        val ids = Look.entries.map { it.id }.toSet()
        listOf("notebook", "lined", "field", "graph", "kraft", "night", "logbook").forEach { assertFalse(it, it in ids) }
    }

    @Test
    fun onlyTrailIsDark() {
        assertEquals(listOf(Look.Trail), Look.entries.filter { it.dark })
        assertEquals(Look.entries.filter { !it.dark }, Look.entries.filter { it.isNotebook })
    }

    @Test
    fun allFiveTokenSetsArePresentAndBelongToTheirTheme() {
        assertEquals(5, BtLooks.all.size)
        Look.entries.forEach { assertEquals(it, BtLooks.of(it).look) }
        assertEquals(Look.entries.toList(), BtLooks.all.map { it.look })
        assertEquals(5, BtLooks.all.map { it.colors }.toSet().size)
        assertEquals(5, BtLooks.all.map { it.skin }.toSet().size)
    }

    @Test
    fun colourTokensFromTheBoards() {
        with(BtLooks.pop.colors) {
            assertEquals(Color(0xFFFFF6E5), bg)
            assertEquals(Color(0xFF1D1B3A), ink)
            assertEquals(Color(0xFFFF5A3C), primary)
            assertEquals(Color(0xFFFFC93C), accent)
            assertEquals(Color(0xFF13A89E), plan)
        }
        with(BtLooks.trail.colors) {
            assertEquals(Color(0xFF3D2448), bg)
            assertEquals(Color(0xFF4A2E57), surface)
            assertEquals(Color(0xFFF6C445), primary)
            assertEquals(Color(0xFFF27A54), plan)
        }
        with(BtLooks.sticker.colors) {
            assertEquals(Color(0xFFFCFCF9), bg)
            assertEquals(Color(0xFF1E2A5A), ink)
            assertEquals(Color(0xFFFFE066), chipOn)
            assertEquals(Color(0xFFC4423A), route)
        }
        with(BtLooks.sketch.colors) {
            assertEquals(Color(0xFF1F2D6B), ink)
            assertEquals(Color(0xFFE8553D), primary)
            assertEquals(Color(0xFF1F2D6B), chipOn)
        }
        with(BtLooks.market.colors) {
            assertEquals(Color(0xFFEFE5D3), bg)
            assertEquals(Color(0xFF3A2A1C), ink)
            assertEquals(Color(0xFFFF7A3D), chipOn)
            assertEquals(Color(0xFFE4572E), route)
        }
    }

    @Test
    fun everyThemeHasReadableInkAndButtons() {
        BtLooks.all.forEach { t ->
            val c = t.colors
            val name = t.look.name
            assertTrue("$name ink on page", contrast(c.ink, c.bg) >= 7f)
            assertTrue("$name ink on surface", contrast(c.ink, c.surface) >= 7f)
            assertTrue("$name primary button", contrast(c.onPrimary, c.primary) >= 3f)
            assertTrue("$name selected chip", contrast(c.onChipOn, c.chipOn) >= 3f)
            assertTrue("$name highlighted words", contrast(c.onAccent, c.accent) >= 4.5f)
            assertTrue("$name accent text on the page", contrast(c.accentInk, c.bg) >= 4.5f)
        }
    }

    // ---- Paper ----

    @Test
    fun papers() {
        assertEquals(Paper.Plain, BtLooks.pop.paper)
        assertEquals(Paper.Plain, BtLooks.trail.paper)
        assertEquals(Paper.Dots, BtLooks.sticker.paper)
        assertEquals(16.dp, BtLooks.sticker.paperStep)
        assertEquals(Paper.Lines, BtLooks.sketch.paper)
        assertEquals(22.dp, BtLooks.sketch.paperStep)
        assertEquals(Paper.Dots, BtLooks.market.paper)
        assertEquals(9.dp, BtLooks.market.paperStep)
        assertTrue("Market's fibres are finer", BtLooks.market.paperDotRadius < BtLooks.sticker.paperDotRadius)
        BtLooks.all.forEach { t ->
            if (t.paper == Paper.Plain) assertNull(t.look.name, t.colors.paperDot) else assertNotNull(t.look.name, t.colors.paperDot)
        }
    }

    @Test
    fun onlySketchbookPaperIsRuledSoOnlyItDropsDividers() {
        assertEquals(listOf(Look.Sketchbook), BtLooks.all.filter { it.paper.ruled }.map { it.look })
        assertTrue(Paper.Lines.ruled && Paper.Grid.ruled)
        assertFalse(Paper.Plain.ruled || Paper.Dots.ruled)
    }

    // ---- Skins ----

    @Test
    fun postcardPopHasInkOutlinesAndHardShadows() {
        val s = BtLooks.pop.skin
        val ink = BtLooks.pop.colors.ink
        for (stroke in listOf(s.cardBorder, s.buttonBorder, s.iconBorder)) {
            assertEquals(1.5.dp, stroke!!.width)
            assertEquals(ink, stroke.color)
        }
        for (shadow in listOf(s.cardShadow, s.buttonShadow, s.fabShadow)) {
            assertEquals(3.dp, shadow!!.y)
            assertEquals(0.dp, shadow.blur)
            assertEquals(ink, shadow.color)
        }
    }

    @Test
    fun sketchbookCardsAreSeeThroughHandDrawnBoxes() {
        val s = BtLooks.sketch.skin
        assertEquals(Color.Transparent, s.cardFill)
        assertEquals(1.6.dp, s.cardBorder.width)
        assertEquals(BtCorners(14.dp, 9.dp, 16.dp, 10.dp), s.cardCorners)
        assertTrue(s.wobbly)
        assertNull(s.cardShadow)
    }

    @Test
    fun marketIsStitchedAndTrailChipsAreOutlined() {
        assertTrue(BtLooks.market.skin.cardBorder.dashed)
        assertTrue(BtLooks.market.skin.backBorder!!.dashed)
        assertNotNull(BtLooks.trail.skin.chipBorder)
        BtLooks.all.filter { it.look != Look.Trail }.forEach { assertNull(it.look.name, it.skin.chipBorder) }
        assertTrue("Trail controls are fully round", BtLooks.trail.pill)
        assertEquals(listOf(Look.Trail), BtLooks.all.filter { it.pill }.map { it.look })
    }

    @Test
    fun cornersFromTheBoards() {
        assertEquals(listOf(14, 16, 10, 12, 8), BtLooks.all.map { it.radius.value.toInt() })
        assertEquals(listOf(12, 999, 10, 10, 8), BtLooks.all.map { it.pillRadius.value.toInt() })
    }

    // ---- Photos ----

    @Test
    fun photoFramesFromTheBoards() {
        with(BtLooks.pop.photo) { assertEquals(5.dp, framePad); assertEquals(Color.White, frameColor); assertEquals(PhotoTape.None, tape) }
        with(BtLooks.trail.photo) { assertEquals(4.dp, framePad); assertEquals(Color.White, frameColor); assertEquals(10.dp, frameCorner); assertEquals(7.dp, corner) }
        with(BtLooks.sticker.photo) {
            assertEquals(14.dp, frameBottom)
            assertTrue("a polaroid", frameBottom > framePad)
            assertEquals(1.2f, tiltK)
            assertEquals(PhotoTape.Washi, tape)
        }
        with(BtLooks.sketch.photo) { assertNull(drop); assertNotNull(border); assertEquals(PhotoTape.Corners, tape) }
        with(BtLooks.market.photo) { assertEquals(Color(0xFFF8F2E6), frameColor); assertEquals(PhotoTape.Kraft, tape) }
        BtLooks.all.forEach { assertTrue(it.look.name, it.photo.framePad > 0.dp) }
    }

    @Test
    fun photosLeanByTheThemesTiltFactor() {
        assertEquals(listOf(3f, -3f, 2f, -2f, 3f), (0..4).map { BtLooks.pop.photo.tiltFor(it) })
        assertEquals(3.6f, BtLooks.sticker.photo.tiltFor(0), 0.0001f)
        assertEquals(-2.4f, BtLooks.sticker.photo.tiltFor(3), 0.0001f)
    }

    // ---- Fonts ----

    @Test
    fun eachThemeHasItsOwnFonts() {
        with(BtLooks.of(Look.PostcardPop, FontSet.LooksOwn).type) {
            assertEquals(BtFonts.fraunces, display)
            assertEquals(900, displayWeight.weight)
            assertEquals(BtFonts.albert, body)
            assertEquals(BtFonts.plexMono, mono)
            assertFalse(displayUpper)
            assertEquals(38.sp, h1)
        }
        with(BtLooks.of(Look.Trail, FontSet.LooksOwn).type) {
            assertEquals(BtFonts.archivoBlack, display)
            assertTrue("Trail titles are upper-case", displayUpper)
            assertEquals(BtFonts.albert, body)
            assertEquals(30.sp, h1)
        }
        for (look in listOf(Look.StickerBook, Look.Sketchbook)) with(BtLooks.of(look, FontSet.LooksOwn).type) {
            assertEquals(BtFonts.caveat, display)
            assertEquals(BtFonts.literata, body)
            assertEquals(BtFonts.plexMono, mono)
            assertEquals(44.sp, h1)
        }
        with(BtLooks.of(Look.Market, FontSet.LooksOwn).type) {
            assertEquals(BtFonts.gochi, display)
            assertEquals(BtFonts.lora, body)
            assertEquals(BtFonts.gochi, hand)
            assertEquals(42.sp, h1)
        }
    }

    @Test
    fun missingTitleFacesStandInBehindOneConstantEach() {
        // Fraunces and Archivo Black are not bundled yet (the owner's OK is needed): Literata Black and Work Sans
        // Black stand in. Adding the files changes only BtFonts.FRAUNCES / BtFonts.ARCHIVO_BLACK.
        assertEquals(R.font.literata, BtFonts.FRAUNCES.res)
        assertEquals(900, BtFonts.FRAUNCES.weight)
        assertEquals(R.font.work_sans, BtFonts.ARCHIVO_BLACK.res)
        assertEquals(BtFonts.FRAUNCES, BtTypes.pop.displayFace)
        assertEquals(BtFonts.ARCHIVO_BLACK, BtTypes.trail.displayFace)
        assertEquals("'wght' 900, 'opsz' 72.0", BtFonts.FRAUNCES.variation)
    }

    @Test
    fun themesOwnFontsChangeNothing() {
        Look.entries.forEach { assertEquals(BtLooks.of(it), BtLooks.of(it, FontSet.LooksOwn)) }
        Look.entries.forEach { assertEquals(BtLooks.of(it).type, BtTypes.of(it, FontSet.LooksOwn)) }
    }

    @Test
    fun otherFontsKeepTheThemesColoursPaperShapesAndPhotos() {
        for (look in Look.entries) {
            val own = BtLooks.of(look)
            for (set in FontSet.entries.filter { it != FontSet.LooksOwn }) {
                val t = BtLooks.of(look, set)
                val name = "$look + $set"
                assertEquals(name, look, t.look)
                assertEquals(name, own.colors, t.colors)
                assertEquals(name, own.paper, t.paper)
                assertEquals(name, own.skin, t.skin)
                assertEquals(name, own.pill, t.pill)
                assertEquals(name, own.radius, t.radius)
                assertEquals(name, own.photo, t.photo)
                assertEquals(name, own.vehicle, t.vehicle)
                // Titles, dates and captions come from the set; running text stays the theme's (its own choice).
                val titles = BtTypes.of(set).copy(body = own.type.body, bodyFace = own.type.bodyFace)
                assertEquals(name, titles, t.type)
                assertEquals(name, BtTypes.of(look, set).copy(body = own.type.body, bodyFace = own.type.bodyFace), t.type)
            }
        }
    }

    @Test
    fun fontSettingDefaultsToTheThemesOwn() {
        assertEquals(FontSet.LooksOwn, FontSet.of(null))
        assertEquals(FontSet.LooksOwn, FontSet.of(""))
        assertEquals(FontSet.LooksOwn, FontSet.of("comic"))
        assertEquals(FontSet.LooksOwn, FontSet.of("look"))
        assertEquals(FontSet.LooksOwn, FontSet.of("logbook"))
        assertEquals("Theme's own", FontSet.LooksOwn.label)
    }

    @Test
    fun fontSetIdsRoundTripInPickerOrder() {
        FontSet.entries.forEach { assertEquals(it, FontSet.of(it.id)) }
        assertEquals(
            listOf(FontSet.LooksOwn, FontSet.Notebook, FontSet.FieldBook, FontSet.Graph, FontSet.Kraft, FontSet.NightRoute),
            FontSet.entries.toList(),
        )
    }

    @Test
    fun pickedFontSetsKeepTheirSizes() {
        assertEquals(42f, BtTypes.notebook.h1.value)
        assertEquals(36f, BtTypes.field.h1.value)
        assertEquals(32f, BtTypes.graph.h1.value)
        assertEquals(40f, BtTypes.kraft.h1.value)
        assertTrue(BtTypes.night.displaySize(19.sp).value < 19f)
        assertEquals(BtLooks.of(Look.Market, FontSet.Notebook).type.h1, BtLooks.of(Look.Trail, FontSet.Notebook).type.h1)
        assertFalse("upper-case only comes with its fonts", BtLooks.of(Look.Trail, FontSet.Notebook).type.displayUpper)
        assertTrue(BtLooks.of(Look.PostcardPop, FontSet.NightRoute).type.displayUpper)
    }

    @Test
    fun regularOnlyHandsAreNotCaptionedBold() {
        for (look in Look.entries) for (set in FontSet.entries) {
            val type = BtLooks.of(look, set).type
            assertTrue("$look + $set", type.captionWeight <= type.displayWeight)
        }
    }

    @Test
    fun legacyRoleColoursStayKnown() {
        assertEquals(5, BtLooks.legacyRoleColors.size)
        BtLooks.legacyRoleColors.forEach { assertEquals(5, it.size) }
        assertNotEquals(BtLooks.legacyRoleColors[1], BtLooks.legacyRoleColors[2])
    }

    private fun luminance(c: Color): Float {
        fun ch(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test
    fun textIsThePhonesFontByDefaultAndTitlesKeepTheirFaces() {
        assertEquals(TextFont.Phone, TextFont.Default)
        assertEquals(TextFont.Phone, TextFont.of(null))
        assertEquals(TextFont.Phone, TextFont.of("comic"))
        TextFont.entries.forEach { assertEquals(it, TextFont.of(it.id)) }
        for (look in Look.entries) for (set in FontSet.entries) {
            val withSet = BtLooks.of(look, set)
            val phone = BtLooks.of(look, set, TextFont.Phone)
            assertEquals(FontFamily.Default, phone.type.body)
            assertTrue(phone.type.bodyFace.isSystem)
            // Only the running text changes: titles, numbers, captions, sizes and the rest stay.
            assertEquals(withSet.type.copy(body = FontFamily.Default, bodyFace = Face.System), phone.type)
            assertEquals(withSet.colors, phone.colors)
            assertEquals(BtLooks.of(look).type.body, BtLooks.of(look, set, TextFont.LooksOwn).type.body)
        }
        assertEquals(BtFonts.lora, BtLooks.of(Look.PostcardPop, FontSet.LooksOwn, TextFont.Lora).type.body)
    }
}
