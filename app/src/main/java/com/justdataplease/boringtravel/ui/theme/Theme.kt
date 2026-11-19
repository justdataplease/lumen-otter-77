package com.justdataplease.boringtravel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val LocalBt = staticCompositionLocalOf { BtLooks.pop }
private val LocalBtText = staticCompositionLocalOf { BtTextStyles(BtLooks.pop) }

/** Short access to the current look: `Bt.colors.ink`, `Bt.text.h1`, `Bt.tokens.photo`. */
object Bt {
    val tokens: BtTokens
        @Composable @ReadOnlyComposable get() = LocalBt.current
    val colors: BtColors
        @Composable @ReadOnlyComposable get() = LocalBt.current.colors
    val text: BtTextStyles
        @Composable @ReadOnlyComposable get() = LocalBtText.current
}

/** The type scale shared by all screens (sizes from the theme boards' CSS; 1 px there = 1 dp here). */
@androidx.compose.runtime.Immutable
class BtTextStyles(private val t: BtTokens) {
    private val c = t.colors
    private val ty = t.type

    /** Screen titles and big names (`.s-h1`). Upper-case in Trail; callers pass text through [display]. */
    val h1 = TextStyle(fontFamily = ty.display, fontWeight = ty.displayWeight, fontSize = ty.h1, lineHeight = ty.h1 * 1.04f, letterSpacing = ty.displaySpacing, color = c.ink)
    /** Pushed-screen titles (`.s-h2`, 0.62 of the title size). */
    val h2 = h1.copy(fontSize = ty.h1 * 0.62f, lineHeight = ty.h1 * 0.66f)
    /** Card titles (`.s-t0`). */
    val title = TextStyle(fontFamily = ty.body, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 21.6.sp, color = c.ink)
    /** List-item titles (`.s-t1`). */
    val item = TextStyle(fontFamily = ty.body, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 18.sp, color = c.ink)
    /** Running text (`.s-body`). */
    val body = TextStyle(fontFamily = ty.body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.5.sp, color = c.ink)
    /** Secondary lines under titles (`.s-t2`). */
    val sub = TextStyle(fontFamily = ty.body, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.muted)
    val fine = TextStyle(fontFamily = ty.body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = c.muted)
    /** Small upper-case labels: dates, sections (`.s-mono`). Always in the mono face. */
    val eyebrow = TextStyle(fontFamily = ty.mono, fontWeight = FontWeight.Medium, fontSize = 11.sp * ty.monoScale, letterSpacing = 0.08.em, color = c.muted)
    /** Times, distances, places under entries (`.s-meta`). */
    val mono = TextStyle(fontFamily = ty.mono, fontWeight = FontWeight.Medium, fontSize = 11.sp * ty.monoScale, color = c.muted)
    /** Handwritten photo captions (`.phcap`). */
    val caption = TextStyle(fontFamily = ty.hand, fontWeight = minOf(FontWeight.Bold, if (ty.hand == ty.display) ty.displayWeight else FontWeight.Bold), fontSize = 15.sp, lineHeight = 17.sp, color = t.photo.captionColor)
    /** Size of the smallest labels in the body face (tab bar, tiles). */
    val small = 11.sp
    /** Buttons (`.btn`) in the theme's text face. */
    val button = TextStyle(fontFamily = ty.body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    /** Chips and segments (`.chip`). */
    val label = TextStyle(fontFamily = ty.body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)

    fun display(s: String): String = if (ty.displayUpper) s.uppercase() else s
}

/**
 * The look's colours, paper and shapes; titles and dates in [fonts] (the look's own unless the person picked a set),
 * running text in [text] (the phone's font unless the person picked another).
 */
@Composable
fun BoringTravelTheme(look: Look, fonts: FontSet = FontSet.LooksOwn, text: TextFont = TextFont.Default, content: @Composable () -> Unit) {
    val tokens = remember(look, fonts, text) { BtLooks.of(look, fonts, text) }
    val c = tokens.colors
    val scheme = remember(tokens) { if (look.dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, onSecondary = c.onPrimary,
            background = c.bg, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.chip, onSurfaceVariant = c.muted, outline = c.line, outlineVariant = c.line,
            error = c.danger, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.chip, surfaceContainerLow = c.bg,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, onSecondary = c.ink,
            background = c.bg, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.chip, onSurfaceVariant = c.muted, outline = c.line, outlineVariant = c.line,
            error = c.danger, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.chip, surfaceContainerLow = c.bg,
        )
    } }
    val styles = remember(tokens) { BtTextStyles(tokens) }
    val base = Typography()
    val typography = remember(styles) { Typography(
        displayLarge = base.displayLarge.copy(fontFamily = tokens.type.display),
        displayMedium = base.displayMedium.copy(fontFamily = tokens.type.display),
        displaySmall = base.displaySmall.copy(fontFamily = tokens.type.display),
        headlineLarge = styles.h1,
        headlineMedium = styles.h2,
        headlineSmall = styles.title,
        titleLarge = styles.title,
        titleMedium = styles.item,
        titleSmall = styles.item.copy(fontSize = 14.sp),
        bodyLarge = styles.body,
        bodyMedium = styles.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = styles.sub,
        labelLarge = styles.button,
        labelMedium = styles.label,
        labelSmall = styles.eyebrow,
    ) }
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalBt provides tokens, LocalBtText provides styles, content = content)
    }
}
