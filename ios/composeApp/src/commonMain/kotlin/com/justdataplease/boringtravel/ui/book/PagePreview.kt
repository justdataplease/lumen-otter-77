package com.justdataplease.boringtravel.ui.book

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.justdataplease.boringtravel.ios.BookFolder

/**
 * The page exactly as it prints (the Android app's PagePreview in ui/book/PageViews.kt, a WebView, cut out of the
 * read-in file): [html] is the page's HTML (book/BookHtml.kt's `page`, Editor mode, zoomed to the view), its photos and
 * fonts in [allowedDir] beside it; nothing else is loaded and the page has no scripts of its own. [paper] shows while it
 * loads. Touches go to what is drawn above it, not to the page. While an element is dragged ([live], round 22's page
 * editor) the page moves it directly with [moveScript], so it follows the finger before the page is laid out again.
 *
 * iPhone: WebKit (a WKWebView through Compose's UIKit interop, the page's gradients in their WebKit forms, its own
 * scripts off, the editor's scripts through evaluateJavaScript). The desktop: the stand-in that draws the same HTML.
 */
@Composable
expect fun PagePreview(html: String, allowedDir: BookFolder, paper: Color, modifier: Modifier = Modifier, live: LiveMove? = null)
