package com.justdataplease.boringtravel.compat.graphics

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.compat.Context
import com.justdataplease.boringtravel.globe.GlobeGeometry
import com.justdataplease.boringtravel.globe.GlobePalette
import com.justdataplease.boringtravel.globe.GlobePin
import com.justdataplease.boringtravel.globe.GlobeRenderer

/*
 * The two things the trip video's renderer asks of Android besides android.graphics: Compose's font resolver, which
 * on Android hands it a Typeface for a family and weight (here the face is the family and weight themselves, laid out
 * by Compose's text: Text.kt), and the Map tab's globe renderer (here the app's own, drawn with Compose: GlobeRenderer).
 */

/** What Compose's resolver gives back: [value] is the face. */
class ResolvedFace(val value: Any)

class FaceResolver internal constructor() {
    fun resolve(family: FontFamily, weight: FontWeight): ResolvedFace = ResolvedFace(Typeface(family, weight))
}

/** Compose's createFontFamilyResolver(context), for ClipStyle.of: the faces themselves come from the app's fonts. */
@Suppress("UNUSED_PARAMETER")
fun createFontFamilyResolver(context: Context): FaceResolver = FaceResolver()

/**
 * The Android app's globe/GlobeRenderer as the video's globe ground (clip/GlobeGround.kt) uses it, over this app's
 * globe renderer (globe/GlobeRenderer.kt, Compose's canvas): built for one design dp of [density] px, flag pins
 * [pinSizeDp] across, drawn into a frame's canvas. The country names are the theme's face at Android's size.
 */
class AndroidGlobeRenderer(geometry: GlobeGeometry, private val density: Float, pinSizeDp: Float) {
    private val inner = GlobeRenderer(geometry, density, pinSizeDp)
    private val measurer = TextMeasurer(TextMeasure.resolver, Density(density), LayoutDirection.Ltr, cacheSize = 16)

    /** Android smooths the fills of a video frame; Compose always does. */
    var antiAliasFills = false

    var pinAlpha: Float
        get() = inner.pinAlpha
        set(value) { inner.pinAlpha = value }

    var showLabels: Boolean
        get() = inner.showLabels
        set(value) { inner.showLabels = value }

    fun setPalette(p: GlobePalette, typeface: Typeface?) {
        val face = typeface ?: Typeface.DEFAULT
        inner.setPalette(p, measurer, TextStyle(fontFamily = face.family, fontWeight = face.weight, fontSize = GlobeRenderer.LABEL_SP.sp))
    }

    fun setCountries(visited: Set<String>, dashed: Set<String>) = inner.setCountries(visited, dashed)

    fun setPins(list: List<GlobePin>) = inner.setPins(list)

    fun draw(canvas: Canvas, lonDeg: Double, latDeg: Double, cx: Float, cy: Float, r: Float, viewW: Float, viewH: Float) {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas.skia.asComposeCanvas(), Size(viewW, viewH)) {
            inner.draw(this, lonDeg, latDeg, cx, cy, r, viewW, viewH)
        }
        canvas.touched()
    }
}
