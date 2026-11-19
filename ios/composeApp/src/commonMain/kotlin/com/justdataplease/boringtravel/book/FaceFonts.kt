package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.R

/**
 * The bundled font file a face is drawn with, as Android's `R.font` number (the Android app's `Face.fontRes` in
 * book/PaintTextMeasure.kt, which this app doesn't read in): the page editor's font chips and layer rows draw a face
 * with `Font(face.fontRes())` (ui/theme/FontFiles.kt). The same files the pages print in ([fontFile]).
 */
fun Face.fontRes(): Int = R.fontFiles.entries.firstOrNull { it.value == fontFile }?.key
    ?: error("No bundled font file $fontFile")
