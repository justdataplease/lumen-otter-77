package com.justdataplease.boringtravel.ui.studio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.LocalBt

/*
 * The editors' dark studio (the book's page editor; the trip video's editor next): a dark screen whatever the app's
 * theme, the preview on a near-black stage, a panel under it and grouped tools at the bottom. Colours from the design
 * canvas (Editor, EditorTool, BookB), type in the app's own fonts.
 */

/** The studio's colours. Never follows the app's theme: an editor is always dark. */
object StudioColors {
    /** The screen, the top bar and the tool row. */
    val bg = Color(0xFF15142B)

    /** The panel under the preview. */
    val panel = Color(0xFF1C1B38)

    /** Round buttons, chips, hairlines and the picked row. */
    val raised = Color(0xFF2A2850)

    /** Behind the preview. */
    val stage = Color(0xFF0E0D1F)

    /** Lines around dark swatches and empty tiles. */
    val edge = Color(0xFF3A3766)

    val ink = Color(0xFFFFF6E5)
    val muted = Color(0xFF8F8BB3)

    /** Quiet icons (drag handles, eyes). */
    val soft = Color(0xFFC9C5E8)

    /** The main action (Export, PDF) and what is selected on the preview. */
    val coral = Color(0xFFFF5A3C)

    /** Picked chips, Done, Add and the name of what the tools act on. */
    val yellow = Color(0xFFFFC93C)
    val onYellow = Color(0xFF15142B)

    /** Warnings ("may print soft"). */
    val warn = Color(0xFFFF8F7A)

    /** Deleting and removing, as everywhere in the app (BtConfirm.kt), bright enough for the dark editor. */
    val danger = Color(0xFFFF6B57)

    /** The page's paper behind words in a thumbnail, and ink on it. */
    val paper = Color(0xFFFFFDF7)
    val paperInk = Color(0xFF1D1B3A)
}

/** The studio's type, in the app's text and mono faces (the person's font choices apply). */
object StudioText {
    /** Top bar title. */
    val title: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.sp, color = StudioColors.ink)

    /** The quiet line under a title or a row's name. */
    val sub: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.body, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, color = StudioColors.muted)

    /** A row's name. */
    val item: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp, color = StudioColors.ink)

    /** A panel's title. */
    val panelTitle: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.body, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.sp, color = StudioColors.ink)

    /** Small upper-case labels over sections and tool groups (mono). */
    val label: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.12.em, color = StudioColors.muted)

    /** A tool group's name, a little smaller. */
    val group: TextStyle
        @Composable @ReadOnlyComposable get() = label.copy(fontSize = 9.sp, lineHeight = 12.sp)

    /** Numbers beside a section label (sizes, angles). */
    val value: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 15.sp, color = StudioColors.ink)

    /** The word under a tool. */
    val tool: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.body, fontWeight = FontWeight.Normal, fontSize = 10.5.sp, lineHeight = 13.sp, color = StudioColors.ink)

    /** Chips and buttons. */
    val chip: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, color = StudioColors.ink)

    val button: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.body, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, color = Color.White)

    /** Running text in fields. */
    val body: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp, color = StudioColors.ink)

    /** Notes under controls. */
    val note: TextStyle
        @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = Bt.tokens.type.body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = StudioColors.muted)
}

/**
 * A studio screen: the dark background edge to edge, light status and navigation bar icons while it shows, and dark
 * Material defaults (sliders, sheets, progress) for what is inside.
 */
@Composable
fun StudioScreen(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    StudioSystemBars()
    StudioMaterial {
        Box(modifier.fillMaxSize().background(StudioColors.bg), content = content)
    }
}

/** Material's own components (sliders, bottom sheets, progress, text selection) in the studio's colours. */
@Composable
fun StudioMaterial(content: @Composable () -> Unit) {
    val scheme = remember {
        darkColorScheme(
            primary = StudioColors.yellow, onPrimary = StudioColors.onYellow, secondary = StudioColors.coral, onSecondary = Color.White,
            background = StudioColors.bg, onBackground = StudioColors.ink, surface = StudioColors.panel, onSurface = StudioColors.ink,
            surfaceVariant = StudioColors.raised, onSurfaceVariant = StudioColors.muted, outline = StudioColors.edge, outlineVariant = StudioColors.raised,
            error = StudioColors.warn, surfaceContainer = StudioColors.panel, surfaceContainerHigh = StudioColors.panel,
            surfaceContainerHighest = StudioColors.raised, surfaceContainerLow = StudioColors.bg,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography) {
        CompositionLocalProvider(LocalContentColor provides StudioColors.ink, content = content)
    }
}

/**
 * The app's own components (theme thumbnails and the like) drawn on the studio: the current theme's shapes and type
 * with the studio's colours, so their words read on the dark panel.
 */
@Composable
fun StudioBtColors(content: @Composable () -> Unit) {
    val t = Bt.tokens
    val tokens = remember(t) {
        t.copy(
            colors = t.colors.copy(
                bg = StudioColors.bg, surface = StudioColors.panel, ink = StudioColors.ink, muted = StudioColors.muted, line = StudioColors.edge,
                primary = StudioColors.coral, onPrimary = Color.White, accent = StudioColors.yellow, accentInk = StudioColors.yellow,
                chip = StudioColors.raised, chipOn = StudioColors.yellow, onChipOn = StudioColors.onYellow, route = StudioColors.yellow,
                plan = StudioColors.yellow, onAccent = StudioColors.onYellow, danger = StudioColors.warn,
            ),
        )
    }
    CompositionLocalProvider(LocalBt provides tokens, content = content)
}

/**
 * Light clock and navigation icons while a studio screen shows (on the activity's window, or a dialog's), put back as
 * they were when it goes.
 */
@Composable
fun StudioSystemBars() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = bars?.isAppearanceLightStatusBars
        val nav = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (bars != null) {
                bars.isAppearanceLightStatusBars = status == true
                bars.isAppearanceLightNavigationBars = nav == true
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
