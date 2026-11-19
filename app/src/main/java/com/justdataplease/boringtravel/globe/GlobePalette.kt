package com.justdataplease.boringtravel.globe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.justdataplease.boringtravel.ui.theme.BtTokens

/**
 * The globe's colours, worked out from a theme's own tokens (so a theme change recolours it): the theme's sea and land,
 * visited countries in its highlighter, planned ones dashed in its plan colour, the rim in faint ink (the boards'
 * World screen). ARGB ints, ready for android.graphics paints.
 */
data class GlobePalette(
    val sea: Int,
    val land: Int,
    val visited: Int,
    val visitedLine: Int,
    val line: Int,
    /** Planned and wished countries: a dashed outline. */
    val dashed: Int,
    val grid: Int,
    val rim: Int,
    /** Thin halo just outside the rim (the air around the planet). */
    val glow: Int,
    /** Darkening towards the edge of the disc. */
    val limb: Int,
    /** Soft light on the upper left (the day side). */
    val light: Int,
    /** Paper themes: the globe sits on the page with a flat offset shadow, like the cards. */
    val paperShadow: Int?,
    val stars: Boolean,
    val star: Int,
    val labelInk: Int,
    val labelHalo: Int,
    val chip: Int,
    val ink: Int,
    /** Flag pins lean a little, like stickers. */
    val pinTilt: Float,
) {
    companion object {
        fun from(t: BtTokens): GlobePalette {
            val c = t.colors
            val tilt = -6f * t.photo.tiltK
            return if (!t.look.dark) {
                GlobePalette(
                    sea = c.sea.toArgb(), land = c.land.toArgb(), visited = c.accent.toArgb(),
                    visitedLine = c.ink.copy(alpha = 0.85f).toArgb(), line = c.ink.copy(alpha = 0.45f).toArgb(),
                    dashed = c.plan.toArgb(), grid = c.ink.copy(alpha = 0.10f).toArgb(), rim = c.ink.copy(alpha = 0.25f).toArgb(),
                    // The sea's own tint, so each theme's air glows in its own colour.
                    glow = c.sea.copy(alpha = 0.9f).toArgb(), limb = c.ink.copy(alpha = 0.10f).toArgb(),
                    light = Color.White.copy(alpha = 0.5f).toArgb(), paperShadow = c.ink.copy(alpha = 0.07f).toArgb(),
                    stars = false, star = 0, labelInk = c.ink.toArgb(), labelHalo = c.bg.toArgb(), chip = c.chip.toArgb(),
                    ink = c.ink.toArgb(), pinTilt = tilt,
                )
            } else {
                // Trail: a deep plum sea, lighter plum land, yellow visited countries, sunrise-orange plans.
                GlobePalette(
                    sea = c.sea.toArgb(), land = c.land.toArgb(), visited = c.accent.toArgb(),
                    visitedLine = c.onAccent.toArgb(), line = c.ink.copy(alpha = 0.30f).toArgb(),
                    dashed = c.plan.toArgb(), grid = Color.White.copy(alpha = 0.06f).toArgb(),
                    rim = c.ink.copy(alpha = 0.25f).toArgb(), glow = c.land.copy(alpha = 0.35f).toArgb(),
                    limb = Color.Black.copy(alpha = 0.30f).toArgb(), light = Color.White.copy(alpha = 0.08f).toArgb(),
                    paperShadow = null, stars = false, star = Color(0xFFDCE6EA).toArgb(), labelInk = c.ink.toArgb(),
                    labelHalo = c.bg.toArgb(), chip = c.chip.toArgb(), ink = c.ink.toArgb(), pinTilt = tilt,
                )
            }
        }
    }
}
