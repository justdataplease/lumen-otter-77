package com.justdataplease.boringtravel.book

/*
 * How the book measures its text on this phone (the Android app's book/PaintTextMeasure.kt, which measures with
 * android.graphics.Paint): with the bundled font files the pages print in, at the weight they print at, so the book
 * puts on a page what WebKit will put there. iPhone: Core Text (CTLine, kerning and ligatures as WebKit shapes them,
 * iosMain/…/book/CoreTextMeasure.kt); the desktop, its tests and its pictures: Skia's shaper (HarfBuzz) on the same
 * files (desktopMain/…/book/SkiaTextMeasure.kt). Both answer in mm, as TextFit asks.
 */

/** The phone's text measure, one for the app (its cache of measured words is kept between books). */
expect fun phoneTextMeasure(): TextMeasure

/**
 * The weight a face is measured at, as Android's PaintTextMeasure sets its variable fonts: bold is 700 (Literata's 600,
 * the weight its titles print at), regular 400; a face that is one weight in its file ([variableFaces] leaves it out)
 * is measured as it is (null).
 */
fun measuredWeight(face: Face, bold: Boolean): Float? = when {
    face !in variableFaces -> null
    bold && face == Face.Literata -> 600f
    bold -> 700f
    else -> 400f
}

/** The faces whose font file is a variable font with a weight axis (Android's PaintTextMeasure.variable). */
val variableFaces: Set<Face> = setOf(
    Face.Literata, Face.LiterataItalic, Face.Albert, Face.Caveat, Face.SourceSerif, Face.WorkSans, Face.JetBrains, Face.Lora,
)

/** The font file a face is measured with ("caveat.ttf"), the one the page's @font-face draws it from. */
val Face.fontFile: String get() = "$file.ttf"
