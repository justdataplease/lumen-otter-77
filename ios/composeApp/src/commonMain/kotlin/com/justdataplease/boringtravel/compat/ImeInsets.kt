package com.justdataplease.boringtravel.compat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * Compose's `WindowInsets.isImeVisible`, which only Android's Compose has: whether the keyboard is up, read from the
 * keyboard's insets every phone gives (the book's page editor hides its tool row while words are typed, round 22).
 */
val WindowInsets.Companion.isImeVisible: Boolean
    @Composable get() = WindowInsets.ime.getBottom(LocalDensity.current) > 0
