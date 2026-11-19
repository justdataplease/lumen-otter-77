package com.justdataplease.boringtravel.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** How the active tab is marked on each theme's bottom bar. */
enum class NavMarker {
    /** Postcard Pop: a coral rounded pill behind the white icon, in line with the other icons; bold coral label. */
    Pill,
    /** Trail: a yellow luggage tag pointing left with a punched hole, leaning −5°. */
    LuggageTag,
    /** Sticker Book: the label in Caveat over a yellow marker swipe, leaning −2°. */
    Highlighter,
    /** Sketchbook: a hand-drawn pencil loop round the icon, the label in Caveat. */
    PencilLoop,
    /** Market: a round orange price sticker behind the icon, a little off-centre. */
    DotSticker,
}

/** Which face a bar label uses: Albert Sans (as the boards), the theme's text face, or its handwriting. */
enum class NavFace { Albert, Body, Hand }

/**
 * One theme's bottom bar, from the boards' CSS (`.trail .bar`, `.sticker .bar`, `.sketch .loop`, `.market .pdot`) and,
 * for Postcard Pop, the plain bar the owner picked on the Trips mockups (2026-11-07). The bar either floats [inset]
 * from the edges (a white card, a slab, a kraft strip) or runs the full width ([inset] 0).
 */
@Immutable
data class NavBarSpec(
    val marker: NavMarker,
    val inset: Dp,
    val height: Dp,
    /** null = no bar background: the page (and its ruling) shows through. */
    val fill: Color?,
    val corner: Dp,
    val border: BtStroke? = null,
    val shadow: BtShadow? = null,
    /** A hairline along the top of a full-width bar. */
    val topLine: Color? = null,
    /** A dashed stitch inside the bar's edge ([stitchInset] in). */
    val stitch: BtStroke? = null,
    val stitchInset: Dp = 6.dp,
    val label: Color,
    val labelSize: TextUnit,
    val labelWeight: FontWeight,
    val labelFace: NavFace,
    val upper: Boolean = false,
    val letterSpacing: TextUnit = 0.sp,
    val activeLabel: Color,
    val activeLabelSize: TextUnit = labelSize,
    val activeLabelWeight: FontWeight,
    val activeFace: NavFace = labelFace,
    val icon: Color,
    val activeIcon: Color,
    /** The marker's colour: pill, tag, marker swipe, pencil, sticker. */
    val markerColor: Color,
    val markerTilt: Float = 0f,
) {
    val floating: Boolean get() = inset > 0.dp
}

object NavBars {
    /**
     * Plain (owner: "maybe simplify the bottom bar", 2026-11-07): a white rounded bar with a 2 dp ink border and a hard
     * ink shadow; the active tab is a coral pill behind a white icon, every icon on one line.
     */
    val pop = NavBarSpec(
        marker = NavMarker.Pill, inset = 12.dp, height = 64.dp, fill = Color.White, corner = 18.dp,
        border = BtStroke(2.dp, Color(0xFF1D1B3A)), shadow = BtShadow(y = 4.dp, color = Color(0xFF1D1B3A)),
        label = Color(0xFF5E5A7D), labelSize = 11.sp, labelWeight = FontWeight.Medium, labelFace = NavFace.Albert,
        activeLabel = Color(0xFFFF5A3C), activeLabelWeight = FontWeight.Bold,
        icon = Color(0xFF5E5A7D), activeIcon = Color.White, markerColor = Color(0xFFFF5A3C),
    )

    val trail = NavBarSpec(
        marker = NavMarker.LuggageTag, inset = 12.dp, height = 62.dp, fill = Color(0xFF24132C), corner = 8.dp,
        stitch = BtStroke(1.5.dp, Color(0x52F6F1E4), dashed = true),
        label = Color(0xFFB9A2C6), labelSize = 9.5.sp, labelWeight = FontWeight.ExtraBold, labelFace = NavFace.Albert,
        upper = true, letterSpacing = 0.08.em,
        activeLabel = Color(0xFF24132C), activeLabelWeight = FontWeight.ExtraBold,
        icon = Color(0xFFB9A2C6), activeIcon = Color(0xFF24132C), markerColor = Color(0xFFF6C445), markerTilt = -5f,
    )

    val sticker = NavBarSpec(
        marker = NavMarker.Highlighter, inset = 0.dp, height = 70.dp, fill = Color(0xFFFCFCF9), corner = 0.dp,
        topLine = Color(0xFFD8DCEA),
        label = Color(0xFF525C86), labelSize = 11.sp, labelWeight = FontWeight.Medium, labelFace = NavFace.Body,
        activeLabel = Color(0xFF1E2A5A), activeLabelSize = 17.sp, activeLabelWeight = FontWeight.Bold, activeFace = NavFace.Hand,
        icon = Color(0xFF525C86), activeIcon = Color(0xFF1E2A5A), markerColor = Color(0xFFFFE066), markerTilt = -2f,
    )

    /** No background and no top line: never a UI line next to the ruling. */
    val sketch = NavBarSpec(
        marker = NavMarker.PencilLoop, inset = 0.dp, height = 70.dp, fill = null, corner = 0.dp,
        label = Color(0xFF5A6596), labelSize = 11.sp, labelWeight = FontWeight.Medium, labelFace = NavFace.Body,
        activeLabel = Color(0xFF1F2D6B), activeLabelSize = 16.sp, activeLabelWeight = FontWeight.Bold, activeFace = NavFace.Hand,
        icon = Color(0xFF5A6596), activeIcon = Color(0xFF1F2D6B), markerColor = Color(0xFF1F2D6B),
    )

    val market = NavBarSpec(
        marker = NavMarker.DotSticker, inset = 12.dp, height = 62.dp, fill = Color(0xFFF8F2E6), corner = 8.dp,
        stitch = BtStroke(1.5.dp, Color(0xFFB9A27F), dashed = true),
        label = Color(0xFF76624D), labelSize = 11.sp, labelWeight = FontWeight.Medium, labelFace = NavFace.Albert,
        activeLabel = Color(0xFF3A2A1C), activeLabelWeight = FontWeight.Bold,
        icon = Color(0xFF76624D), activeIcon = Color(0xFF3A2A1C), markerColor = Color(0xFFFF7A3D),
    )

    fun of(look: Look): NavBarSpec = when (look) {
        Look.PostcardPop -> pop
        Look.Trail -> trail
        Look.StickerBook -> sticker
        Look.Sketchbook -> sketch
        Look.Market -> market
    }
}
