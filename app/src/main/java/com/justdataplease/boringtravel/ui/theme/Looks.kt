package com.justdataplease.boringtravel.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.R
import com.justdataplease.boringtravel.art.PlaneMark

/**
 * The app's themes, in the order of the Me › Theme picker. Every screen reads its colours, type, shapes, paper and
 * photo treatment from the current theme's tokens, so a theme is one data object, never per-screen code.
 * (Called "look" in code and settings since the first looks; the stored id is [id].)
 */
enum class Look(val id: String, val label: String, val dark: Boolean, val blurb: String) {
    PostcardPop("pop", "Postcard Pop", false, "Cream, coral and sun. Chunky outlines, postcard photos."),
    Trail("trail", "Trail", true, "Plum dusk, sunrise colours, big outdoor type."),
    StickerBook("sticker", "Sticker Book", false, "Dot paper, washi tape on every photo."),
    Sketchbook("sketch", "Sketchbook", false, "Ruled paper and ballpoint blue."),
    Market("market", "Market", false, "Kraft paper, dashed stitching, dot stickers.");

    /**
     * The light paper themes (all but Trail). Screens written for the earlier notebook looks branch on this; the
     * notebook treatments (framed, captioned photos, paper colours) read right on every light theme.
     */
    val isNotebook: Boolean get() = !dark

    companion object {
        val Default = PostcardPop

        /**
         * Ids saved before the five themes (settings and Drive backups) map to the nearest theme; anything unknown or
         * missing is the default, Postcard Pop.
         */
        private val retired = mapOf(
            "notebook" to StickerBook,
            "lined" to Sketchbook,
            "notebook2" to Sketchbook,
            "field" to Sketchbook,
            "graph" to StickerBook,
            "kraft" to Market,
            "night" to Trail,
            "logbook" to PostcardPop,
        )

        fun of(id: String?): Look = entries.firstOrNull { it.id == id } ?: retired[id] ?: Default
    }
}

/**
 * Me › Fonts › Titles and dates: the faces of screen titles, dates and numbers, and handwritten captions, with its
 * own title size. "Theme's own" follows the theme; any other set can be combined with any theme, which keeps its
 * colours, paper, shapes and photos. Running text has its own choice ([TextFont]).
 */
enum class FontSet(val id: String, val label: String, val faces: String) {
    LooksOwn("look", "Theme's own", ""),
    Notebook("notebook", "Notebook", "Caveat · Plex Mono"),
    FieldBook("field", "Field book", "Kalam · Courier Prime"),
    Graph("graph", "Graph paper", "Architects Daughter · JetBrains Mono"),
    Kraft("kraft", "Kraft", "Gochi Hand · Plex Mono"),
    NightRoute("night", "Night Route", "Barlow Condensed · Plex Mono");

    companion object {
        /** Retired "logbook", unknown and missing font ids safely follow the selected theme. */
        fun of(id: String?): FontSet = entries.firstOrNull { it.id == id } ?: LooksOwn
    }
}

/**
 * Me › Fonts › Text: the face of everything that is not a title or a date (body text, lists, buttons, journal text).
 * The phone's own sans (Roboto on Pixel and most Android phones) by default (owner, 2026-11-07: "keep Google default
 * fonts for all except titles and numbers of dates"); "Theme's own" brings back the theme's text face.
 */
enum class TextFont(val id: String, val label: String) {
    Phone("phone", "Phone (Roboto)"),
    LooksOwn("look", "Theme's own"),
    Albert("albert", "Albert Sans"),
    Literata("literata", "Literata"),
    SourceSerif("serif", "Source Serif"),
    WorkSans("work", "Work Sans"),
    Lora("lora", "Lora"),
    Barlow("barlow", "Barlow");

    companion object {
        val Default = Phone

        /** Unknown and missing ids are the phone's font. */
        fun of(id: String?): TextFont = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * What the page is drawn on: plain, a dot grid (Sticker Book's dots, Market's fine kraft fibres), ruled lines
 * (Sketchbook) or squares. The step and dot size are the theme's ([BtTokens.paperStep], [BtTokens.paperDotRadius]).
 */
enum class Paper {
    Plain, Dots, Lines, Grid;

    /** Paper whose own lines already separate rows, so dividers are left out. */
    val ruled: Boolean get() = this == Lines || this == Grid
}

@Immutable
data class BtColors(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val primary: Color,
    val onPrimary: Color,
    /** The highlighter: marked words, the PRO tag, visited countries. */
    val accent: Color,
    /** Accent-coloured text on the page or a card (links, "today", section labels that stand out). */
    val accentInk: Color,
    val chip: Color,
    /** Selected chip/segment fill and its text. */
    val chipOn: Color,
    val onChipOn: Color,
    /** The travelled route, and the selection ring. */
    val route: Color,
    val sea: Color,
    val land: Color,
    /** The paper's dot or rule colour, or null for plain backgrounds. */
    val paperDot: Color?,
    val danger: Color,
    val onPhoto: Color,
    /** The planned route (dashed) and "next" flags. */
    val plan: Color = route,
    /** Text and icons on the [accent] fill (the boards' `--accent-ink`). */
    val onAccent: Color = accentInk,
)

/**
 * The brand mark in this theme ([PlaneMark]): the navy-edged plane on the theme's yellow highlighter ([accent]); the
 * plane itself keeps the brand's navy and white on every theme (it reads on each yellow, PlaneMarkTest checks).
 */
fun BtColors.planeMark(): PlaneMark.Colors = PlaneMark.Colors(accent.toArgb())

/** Each font set's faces and sizes. */
@Immutable
data class BtType(
    val display: FontFamily,
    val displayWeight: FontWeight,
    val displayUpper: Boolean,
    val displaySpacing: TextUnit,
    /** Screen-title size; Caveat needs more size than Albert Sans for the same presence. */
    val h1: TextUnit,
    val body: FontFamily,
    val mono: FontFamily,
    /** Mono sizes × this: Courier Prime is small on its body, so it is set a little larger. */
    val monoScale: Float = 1f,
    /** Photo captions and handwritten labels. */
    val hand: FontFamily = BtFonts.caveat,
    /** The same faces as files, for drawing outside Compose (share card, globe labels). */
    val displayFace: Face = Face(R.font.caveat, 700),
    val bodyFace: Face = Face(R.font.literata, 400),
    val monoFace: Face = Face(R.font.plex_mono_semibold, 600),
) {
    /** Photo captions in the display face: semi-bold at most, so a regular-only hand isn't drawn fake-bold. */
    val captionWeight: FontWeight get() = minOf(FontWeight.SemiBold, displayWeight)

    /** A display-face size with the presence [caveatSize] has in Caveat (scaled like the title sizes). */
    fun displaySize(caveatSize: TextUnit): TextUnit = caveatSize * (h1.value / CAVEAT_H1)

    private companion object {
        const val CAVEAT_H1 = 42f
    }
}

object BtTypes {
    // ---- The themes' own sets (sizes from the design boards) ----

    /** Postcard Pop: Fraunces 900 titles (placeholder, see [BtFonts.FRAUNCES]), Albert Sans, Plex Mono. */
    val pop = BtType(
        display = BtFonts.fraunces, displayWeight = FontWeight(BtFonts.FRAUNCES.weight), displayUpper = false,
        displaySpacing = 0.em, h1 = 38.sp, body = BtFonts.albert, mono = BtFonts.plexMono, hand = BtFonts.caveat,
        displayFace = BtFonts.FRAUNCES, bodyFace = Face(R.font.albert_sans, 400),
    )

    /** Trail: Archivo Black upper-case titles (placeholder, see [BtFonts.ARCHIVO_BLACK]), Albert Sans, Plex Mono. */
    val trail = BtType(
        display = BtFonts.archivoBlack, displayWeight = FontWeight(BtFonts.ARCHIVO_BLACK.weight), displayUpper = true,
        displaySpacing = 0.em, h1 = 30.sp, body = BtFonts.albert, mono = BtFonts.plexMono, hand = BtFonts.caveat,
        displayFace = BtFonts.ARCHIVO_BLACK, bodyFace = Face(R.font.albert_sans, 400),
    )

    /** Sticker Book and Sketchbook: Caveat, Literata, Plex Mono. */
    val sticker = BtType(
        display = BtFonts.caveat, displayWeight = FontWeight.Bold, displayUpper = false,
        displaySpacing = 0.em, h1 = 44.sp, body = BtFonts.literata, mono = BtFonts.plexMono,
    )
    val sketch = sticker

    /** Market: Gochi Hand, Lora, Plex Mono. */
    val market = BtType(
        display = BtFonts.gochi, displayWeight = FontWeight.Normal, displayUpper = false,
        displaySpacing = 0.em, h1 = 42.sp, body = BtFonts.lora, mono = BtFonts.plexMono, hand = BtFonts.gochi,
        displayFace = Face(R.font.gochi_hand_regular, 400), bodyFace = Face(R.font.lora, 400),
    )

    // ---- The font sets a person can pick in Me › Fonts ----

    val notebook = BtType(
        display = BtFonts.caveat, displayWeight = FontWeight.Bold, displayUpper = false,
        displaySpacing = 0.em, h1 = 42.sp, body = BtFonts.literata, mono = BtFonts.plexMono,
    )
    val field = BtType(
        display = BtFonts.kalam, displayWeight = FontWeight.Bold, displayUpper = false,
        displaySpacing = 0.em, h1 = 36.sp, body = BtFonts.sourceSerif, mono = BtFonts.courierPrime, monoScale = 1.15f,
        hand = BtFonts.kalam, displayFace = Face(R.font.kalam_bold, 700), bodyFace = Face(R.font.source_serif_4, 400),
        monoFace = Face(R.font.courier_prime_bold, 700),
    )
    val graph = BtType(
        display = BtFonts.architects, displayWeight = FontWeight.Normal, displayUpper = false,
        displaySpacing = 0.em, h1 = 32.sp, body = BtFonts.workSans, mono = BtFonts.jetbrainsMono,
        hand = BtFonts.architects, displayFace = Face(R.font.architects_daughter_regular, 400),
        bodyFace = Face(R.font.work_sans, 400), monoFace = Face(R.font.jetbrains_mono, 600),
    )
    val kraft = BtType(
        display = BtFonts.gochi, displayWeight = FontWeight.Normal, displayUpper = false,
        displaySpacing = 0.em, h1 = 40.sp, body = BtFonts.lora, mono = BtFonts.plexMono, hand = BtFonts.gochi,
        displayFace = Face(R.font.gochi_hand_regular, 400), bodyFace = Face(R.font.lora, 400),
    )
    /** Road-sign type. The upper-case titles belong to this set, so they go wherever it goes. */
    val night = BtType(
        display = BtFonts.barlowCondensed, displayWeight = FontWeight.Bold, displayUpper = true,
        displaySpacing = 0.01.em, h1 = 36.sp, body = BtFonts.barlow, mono = BtFonts.plexMono,
        displayFace = Face(R.font.barlow_condensed_bold, 700), bodyFace = Face(R.font.barlow_regular, 400),
    )

    /** A picked set's faces; [FontSet.LooksOwn] has none of its own (use [BtLooks.of] with a theme). */
    fun of(set: FontSet): BtType = when (set) {
        FontSet.LooksOwn, FontSet.Notebook -> notebook
        FontSet.FieldBook -> field
        FontSet.Graph -> graph
        FontSet.Kraft -> kraft
        FontSet.NightRoute -> night
    }

    /** The type [set] gives on [look]: the theme's own for "Theme's own". */
    fun of(look: Look, set: FontSet): BtType = if (set == FontSet.LooksOwn) BtLooks.of(look).type else of(set)

    /** [type] with its running text in [text]; [TextFont.LooksOwn] keeps the theme's own text face ([themeType]). */
    fun withText(type: BtType, text: TextFont, themeType: BtType): BtType {
        val (family, face) = when (text) {
            TextFont.Phone -> FontFamily.Default to Face.System
            TextFont.LooksOwn -> themeType.body to themeType.bodyFace
            TextFont.Albert -> BtFonts.albert to Face(R.font.albert_sans, 400)
            TextFont.Literata -> BtFonts.literata to Face(R.font.literata, 400)
            TextFont.SourceSerif -> BtFonts.sourceSerif to Face(R.font.source_serif_4, 400)
            TextFont.WorkSans -> BtFonts.workSans to Face(R.font.work_sans, 400)
            TextFont.Lora -> BtFonts.lora to Face(R.font.lora, 400)
            TextFont.Barlow -> BtFonts.barlow to Face(R.font.barlow_regular, 400)
        }
        return type.copy(body = family, bodyFace = face)
    }
}

/** A CSS-like box shadow: offset, blur and spread (negative pulls it in). Blur 0 = a flat, hard shadow. */
@Immutable
data class BtShadow(val x: Dp = 0.dp, val y: Dp, val blur: Dp = 0.dp, val spread: Dp = 0.dp, val color: Color)

/** A border: width, colour, and dashed (Market's stitching) or solid. */
@Immutable
data class BtStroke(val width: Dp, val color: Color, val dashed: Boolean = false)

/** Four corner radii (Sketchbook's hand-drawn boxes are a little uneven). */
@Immutable
data class BtCorners(val topStart: Dp, val topEnd: Dp, val bottomEnd: Dp, val bottomStart: Dp) {
    constructor(all: Dp) : this(all, all, all, all)
}

/** What sits on a photo's frame: washi tape (Sticker Book), yellow tape on two corners (Sketchbook), kraft tape (Market). */
enum class PhotoTape { None, Washi, Corners, Kraft }

/** How photos sit on a page: frame, padding (a deeper bottom on polaroids), shadow, corners, tilt and tape. */
@Immutable
data class BtPhoto(
    /** Default lean of a single photo (a cover), in degrees before [tiltK]. */
    val tiltDeg: Float,
    val framePad: Dp,
    val frameBottom: Dp,
    val frameColor: Color,
    /** Elevation for code that can only draw elevation shadows; [drop] is the exact one. */
    val shadow: Dp,
    /** Corner of the picture inside the frame. */
    val corner: Dp,
    val shadowColor: Color = Color.Black,
    /** Corner of the frame itself. */
    val frameCorner: Dp = 2.dp,
    val drop: BtShadow? = null,
    /** Every angle × this (Sticker Book's photos lean a little more). 0 would stand every photo straight. */
    val tiltK: Float = 1f,
    /** An ink line round the frame (Sketchbook). */
    val border: BtStroke? = null,
    val tape: PhotoTape = PhotoTape.None,
    /** Handwritten caption colour on the frame. */
    val captionColor: Color = Color(0xFF1E2A5A),
) {
    /** The lean of the photo at [index] in a list (3°, −3°, 2°, −2° …, as on the boards), × [tiltK]. */
    fun tiltFor(index: Int): Float = LIST_TILTS[Math.floorMod(index, LIST_TILTS.size)] * tiltK

    private companion object {
        val LIST_TILTS = floatArrayOf(3f, -3f, 2f, -2f)
    }
}

/**
 * The shared components' skin, straight from the boards' CSS: borders and shadows of cards, buttons and FABs, the
 * icon and back buttons, the chips.
 */
@Immutable
data class BtSkin(
    /** null = the surface colour; Sketchbook's cards are see-through. */
    val cardFill: Color? = null,
    val cardBorder: BtStroke,
    val cardShadow: BtShadow? = null,
    val cardCorners: BtCorners? = null,
    /** Primary buttons, ghost buttons and the FAB: Postcard Pop's ink outline and hard shadow. */
    val buttonBorder: BtStroke? = null,
    val buttonShadow: BtShadow? = null,
    val ghostBorder: BtStroke,
    val fabShadow: BtShadow,
    val iconBorder: BtStroke,
    /** null = the surface colour. */
    val iconFill: Color? = null,
    /** Back buttons on top bars. */
    val backFill: Color,
    val backIcon: Color,
    val backBorder: BtStroke? = null,
    val backShadow: BtShadow? = null,
    /** Hand-drawn circles for round buttons (Sketchbook). */
    val wobbly: Boolean = false,
    /** Outlined chips (Trail): the border of an unselected chip, which is then see-through. */
    val chipBorder: BtStroke? = null,
)

@Immutable
data class BtTokens(
    val look: Look,
    val colors: BtColors,
    val type: BtType,
    /** Card corner. */
    val radius: Dp,
    /** Fully round controls (Trail), or [pillRadius] corners. */
    val pill: Boolean,
    val photo: BtPhoto,
    val paper: Paper = Paper.Plain,
    /** Red margin line of ruled paper, or null. */
    val margin: Color? = null,
    /** Corner of buttons, chips and segments when not [pill]. */
    val pillRadius: Dp = 10.dp,
    /** Distance between the paper's dots or rules. */
    val paperStep: Dp = 16.dp,
    /** Radius of the paper's dots. */
    val paperDotRadius: Dp = 1.dp,
    val skin: BtSkin,
    /** The paper vehicles of the trip clip and the travel picker. */
    val vehicle: BtVehicle,
)

/**
 * The paper vehicles' colours: [body] (car body, fuselage, carriages), [card] (lighter paper parts), [ink] (hull,
 * cab, trousers, details, folds), [accent] (roof, bike frame, stripes), [glass] (windows), [shadow] (tyres, ground
 * shadow).
 */
@Immutable
data class BtVehicle(
    val body: Color,
    val card: Color,
    val ink: Color,
    val accent: Color,
    val glass: Color,
    val shadow: Color,
    val dark: Boolean = false,
    /** How strongly the paper folds are inked, 0–1. */
    val outline: Float = 0.12f,
)

object BtLooks {
    /** Postcard Pop: cream, coral and sun; chunky ink outlines and hard shadows; a plain white bar with a coral pill. */
    val pop: BtTokens = run {
        val ink = Color(0xFF1D1B3A)
        BtTokens(
            look = Look.PostcardPop,
            colors = BtColors(
                bg = Color(0xFFFFF6E5), surface = Color(0xFFFFFFFF), ink = ink, muted = Color(0xFF5E5A7D),
                line = Color(0x291D1B3A), primary = Color(0xFFFF5A3C), onPrimary = Color(0xFFFFFFFF),
                accent = Color(0xFFFFC93C), accentInk = ink, chip = Color(0xFFFFE9C2),
                chipOn = ink, onChipOn = Color(0xFFFFF6E5), route = Color(0xFFFF5A3C), plan = Color(0xFF13A89E),
                sea = Color(0xFF8ED3F2), land = Color(0xFFFFF6E5), paperDot = null, danger = Color(0xFFC8321E),
                onPhoto = ink,
            ),
            type = BtTypes.pop,
            radius = 14.dp,
            pill = false,
            pillRadius = 12.dp,
            photo = BtPhoto(
                tiltDeg = -1.5f, framePad = 5.dp, frameBottom = 5.dp, frameColor = Color.White, shadow = 4.dp,
                corner = 1.dp, shadowColor = ink, frameCorner = 2.dp,
                drop = BtShadow(y = 8.dp, blur = 16.dp, spread = (-8).dp, color = Color(0x801D1B3A)),
                captionColor = ink,
            ),
            skin = BtSkin(
                cardBorder = BtStroke(1.5.dp, ink),
                cardShadow = BtShadow(y = 3.dp, color = ink),
                buttonBorder = BtStroke(1.5.dp, ink),
                buttonShadow = BtShadow(y = 3.dp, color = ink),
                ghostBorder = BtStroke(1.5.dp, ink),
                fabShadow = BtShadow(y = 3.dp, color = ink),
                iconBorder = BtStroke(1.5.dp, ink),
                backFill = Color.White, backIcon = ink, backBorder = BtStroke(1.5.dp, ink),
                backShadow = BtShadow(y = 2.dp, color = ink),
            ),
            vehicle = BtVehicle(
                body = Color(0xFFFFFDF7), card = Color(0xFFFFF6E5), ink = ink, accent = Color(0xFFFF5A3C),
                glass = Color(0xFFFFC93C), shadow = ink, outline = 0.40f,
            ),
        )
    }

    /** Trail: plum dusk (picked over the first green, 2026-11-06), sunrise colours, big outdoor type, white photo frames; the luggage-tag bar. */
    val trail: BtTokens = run {
        val cream = Color(0xFFF6F1E4)
        val dusk = Color(0xFF3D2448)
        BtTokens(
            look = Look.Trail,
            colors = BtColors(
                bg = dusk, surface = Color(0xFF4A2E57), ink = cream, muted = Color(0xFFD6C3DF),
                line = Color(0x29F6F1E4), primary = Color(0xFFF6C445), onPrimary = dusk,
                accent = Color(0xFFF6C445), accentInk = Color(0xFFF6C445), onAccent = dusk, chip = Color(0xFF563766),
                chipOn = Color(0xFFF6C445), onChipOn = dusk, route = Color(0xFFF6C445), plan = Color(0xFFF27A54),
                sea = Color(0xFF2E1A38), land = Color(0xFF5F3F70), paperDot = null, danger = Color(0xFFFF8A73),
                onPhoto = dusk,
            ),
            type = BtTypes.trail,
            radius = 16.dp,
            pill = true,
            pillRadius = 999.dp,
            photo = BtPhoto(
                tiltDeg = -1.5f, framePad = 4.dp, frameBottom = 4.dp, frameColor = Color.White, shadow = 6.dp,
                corner = 7.dp, frameCorner = 10.dp,
                drop = BtShadow(y = 12.dp, blur = 22.dp, spread = (-12).dp, color = Color(0x99000000)),
                captionColor = dusk,
            ),
            skin = BtSkin(
                cardBorder = BtStroke(1.dp, Color(0x29F6F1E4)),
                ghostBorder = BtStroke(1.5.dp, Color(0x29F6F1E4)),
                fabShadow = BtShadow(y = 10.dp, blur = 22.dp, spread = (-10).dp, color = Color(0x99000000)),
                iconBorder = BtStroke(1.dp, Color(0x29F6F1E4)),
                backFill = Color(0xF0FFFFFF), backIcon = dusk,
                chipBorder = BtStroke(1.5.dp, Color(0x8CF6F1E4)),
            ),
            vehicle = BtVehicle(
                body = cream, card = Color(0xFFE9E1CC), ink = Color(0xFF24132C), accent = Color(0xFFF6C445),
                glass = Color(0xFFF27A54), shadow = Color(0xFF06140F), dark = true, outline = 0.18f,
            ),
        )
    }

    /** Sticker Book: dot paper, navy ink, the yellow highlighter, polaroids with washi tape; the highlighter bar. */
    val sticker: BtTokens = run {
        val ink = Color(0xFF1E2A5A)
        BtTokens(
            look = Look.StickerBook,
            colors = BtColors(
                bg = Color(0xFFFCFCF9), surface = Color(0xFFFFFFFF), ink = ink, muted = Color(0xFF525C86),
                line = Color(0xFFD8DCEA), primary = ink, onPrimary = Color(0xFFFFFFFF),
                accent = Color(0xFFFFE066), accentInk = ink, chip = Color(0xFFEEF0F7),
                chipOn = Color(0xFFFFE066), onChipOn = ink, route = Color(0xFFC4423A), plan = Color(0xFF3FA77A),
                sea = Color(0xFFE3E9F6), land = Color(0xFFFFFFFF), paperDot = Color(0x291E2A5A), danger = Color(0xFFC4423A),
                onPhoto = ink,
            ),
            type = BtTypes.sticker,
            radius = 10.dp,
            pill = false,
            pillRadius = 10.dp,
            paper = Paper.Dots,
            paperStep = 16.dp,
            paperDotRadius = 1.dp,
            photo = BtPhoto(
                tiltDeg = -1.5f, framePad = 5.dp, frameBottom = 14.dp, frameColor = Color.White, shadow = 4.dp,
                corner = 1.dp, shadowColor = ink, frameCorner = 2.dp,
                drop = BtShadow(y = 8.dp, blur = 16.dp, spread = (-8).dp, color = Color(0x801E2A5A)),
                tiltK = 1.2f, tape = PhotoTape.Washi, captionColor = ink,
            ),
            skin = BtSkin(
                cardBorder = BtStroke(1.dp, Color(0xFFD8DCEA)),
                cardShadow = BtShadow(x = 3.dp, y = 4.dp, color = Color(0x121E2A5A)),
                ghostBorder = BtStroke(1.5.dp, Color(0xFFD8DCEA)),
                fabShadow = BtShadow(y = 10.dp, blur = 22.dp, spread = (-10).dp, color = Color(0x73000000)),
                iconBorder = BtStroke(1.dp, Color(0xFFD8DCEA)),
                backFill = Color.White, backIcon = ink, backBorder = BtStroke(1.dp, Color(0xFFD8DCEA)),
            ),
            vehicle = BtVehicle(
                body = Color(0xFFFFFFFF), card = Color(0xFFF3F4FA), ink = ink, accent = Color(0xFFFF8FB1),
                glass = Color(0xFF7FD8B2), shadow = ink, outline = 0.25f,
            ),
        )
    }

    /** Sketchbook: ruled paper, ballpoint blue, hand-drawn boxes, yellow tape on photo corners; the pencil-loop bar. */
    val sketch: BtTokens = run {
        val ink = Color(0xFF1F2D6B)
        val paper = Color(0xFFFCFCF9)
        BtTokens(
            look = Look.Sketchbook,
            colors = BtColors(
                bg = paper, surface = Color(0xFFFFFFFF), ink = ink, muted = Color(0xFF5A6596),
                line = Color(0x381F2D6B), primary = Color(0xFFE8553D), onPrimary = Color(0xFFFFFFFF),
                accent = Color(0xFFFFE45C), accentInk = ink, chip = Color(0xFFEEF0F7),
                chipOn = ink, onChipOn = Color(0xFFFFFFFF), route = Color(0xFFE8553D), plan = ink,
                sea = Color(0xFFE6EEF8), land = paper, paperDot = Color(0x215A606E), danger = Color(0xFFC8402B),
                onPhoto = ink,
            ),
            type = BtTypes.sketch,
            radius = 12.dp,
            pill = false,
            pillRadius = 10.dp,
            paper = Paper.Lines,
            paperStep = 22.dp,
            photo = BtPhoto(
                tiltDeg = -1.5f, framePad = 3.dp, frameBottom = 3.dp, frameColor = Color.White, shadow = 0.dp,
                corner = 1.dp, shadowColor = ink, frameCorner = 2.dp, drop = null,
                border = BtStroke(1.6.dp, ink), tape = PhotoTape.Corners, captionColor = ink,
            ),
            skin = BtSkin(
                cardFill = Color.Transparent,
                cardBorder = BtStroke(1.6.dp, ink),
                cardCorners = BtCorners(14.dp, 9.dp, 16.dp, 10.dp),
                ghostBorder = BtStroke(1.5.dp, Color(0x381F2D6B)),
                fabShadow = BtShadow(y = 10.dp, blur = 22.dp, spread = (-10).dp, color = Color(0x73000000)),
                iconBorder = BtStroke(1.6.dp, ink),
                iconFill = Color.Transparent,
                backFill = paper, backIcon = ink, backBorder = BtStroke(1.6.dp, ink),
                wobbly = true,
            ),
            vehicle = BtVehicle(
                body = Color(0xFFFFFFFF), card = paper, ink = ink, accent = Color(0xFFE8553D),
                glass = Color(0xFF8FA6DA), shadow = ink, outline = 0.38f,
            ),
        )
    }

    /** Market: kraft paper with fibre dots, brown ink, dashed stitching, kraft tape; the dot-sticker bar. */
    val market: BtTokens = run {
        val ink = Color(0xFF3A2A1C)
        val card = Color(0xFFF8F2E6)
        BtTokens(
            look = Look.Market,
            colors = BtColors(
                bg = Color(0xFFEFE5D3), surface = card, ink = ink, muted = Color(0xFF76624D),
                line = Color(0xFFDCCDB2), primary = ink, onPrimary = card,
                accent = Color(0xFFF2C14E), accentInk = ink, chip = Color(0xFFE6DAC3),
                chipOn = Color(0xFFFF7A3D), onChipOn = ink, route = Color(0xFFE4572E), plan = Color(0xFF2F7F7A),
                sea = Color(0xFFDCE6E2), land = card, paperDot = Color(0x1A50371E), danger = Color(0xFFA8392A),
                onPhoto = ink,
            ),
            type = BtTypes.market,
            radius = 8.dp,
            pill = false,
            pillRadius = 8.dp,
            paper = Paper.Dots,
            paperStep = 9.dp,
            paperDotRadius = 0.9.dp,
            photo = BtPhoto(
                tiltDeg = -1.5f, framePad = 4.dp, frameBottom = 4.dp, frameColor = card, shadow = 3.dp,
                corner = 1.dp, shadowColor = ink, frameCorner = 2.dp,
                drop = BtShadow(y = 6.dp, blur = 12.dp, spread = (-6).dp, color = Color(0x803A2A1C)),
                tape = PhotoTape.Kraft, captionColor = ink,
            ),
            skin = BtSkin(
                cardBorder = BtStroke(1.5.dp, Color(0xFFB9A27F), dashed = true),
                ghostBorder = BtStroke(1.5.dp, Color(0xFFDCCDB2)),
                fabShadow = BtShadow(y = 10.dp, blur = 22.dp, spread = (-10).dp, color = Color(0x73000000)),
                iconBorder = BtStroke(1.dp, Color(0xFFDCCDB2)),
                backFill = card, backIcon = ink, backBorder = BtStroke(1.5.dp, Color(0xFF9C8360), dashed = true),
            ),
            vehicle = BtVehicle(
                body = card, card = Color(0xFFEFE5D3), ink = ink, accent = Color(0xFFFF7A3D),
                glass = Color(0xFFF2C14E), shadow = ink, outline = 0.20f,
            ),
        )
    }

    fun of(look: Look): BtTokens = when (look) {
        Look.PostcardPop -> pop
        Look.Trail -> trail
        Look.StickerBook -> sticker
        Look.Sketchbook -> sketch
        Look.Market -> market
    }

    /**
     * The theme's tokens with [fonts] for titles, dates and captions and [text] for running text; colours, paper,
     * shapes and photos stay the theme's.
     */
    fun of(look: Look, fonts: FontSet, text: TextFont = TextFont.LooksOwn): BtTokens {
        val tokens = of(look)
        val titles = if (fonts == FontSet.LooksOwn) tokens.type else BtTypes.of(fonts)
        return tokens.copy(type = BtTypes.withText(titles, text, tokens.type))
    }

    /** Every theme's tokens, in picker order (declared last: it reads the themes above). */
    val all: List<BtTokens> = Look.entries.map { of(it) }

    /**
     * Role colours of the retired looks (Notebook, Notebook 2, Field book, Graph paper, Kraft, Night Route): route,
     * danger, highlighter ink, muted, highlighter. Text coloured in one of them in an older entry can follow that role.
     */
    val legacyRoleColors: List<List<Color>> = listOf(
        listOf(Color(0xFFC4423A), Color(0xFFC4423A), Color(0xFF1E2A5A), Color(0xFF525C86), Color(0xFFF7E36A)),
        listOf(Color(0xFFD2452F), Color(0xFFB8402F), Color(0xFF24211D), Color(0xFF6B645A), Color(0xFFFFBF94)),
        listOf(Color(0xFFE0533D), Color(0xFFC8452F), Color(0xFF1F3B33), Color(0xFF5C7069), Color(0xFFBDEFD3)),
        listOf(Color(0xFF2F7F7A), Color(0xFFA8392A), Color(0xFF3A2A1C), Color(0xFF76624D), Color(0xFFF2C14E)),
        listOf(Color(0xFFFFB547), Color(0xFFFF8A80), Color(0xFFFFC56E), Color(0xFF9AABB2), Color(0xFFFFB547)),
    )
}
