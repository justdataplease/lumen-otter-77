package com.justdataplease.boringtravel.ui.book

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.justdataplease.boringtravel.ios.BookFolder
import com.justdataplease.boringtravel.ios.BookPageDrawer
import com.justdataplease.boringtravel.ios.MediaWork
import com.justdataplease.boringtravel.ios.WebKitPrint
import kotlinx.coroutines.withContext

/*
 * The book's page on the desktop: the stand-in (ios/BookPageDrawer.kt) draws the page's HTML, in the form WebKit gets
 * it on iPhone (WebKitPrint.forBookScreen), as one picture the size of the view. [live] (round 22's moves) isn't drawn
 * here: the stand-in draws whole pages.
 */
@Composable
actual fun PagePreview(html: String, allowedDir: BookFolder, paper: Color, modifier: Modifier, live: LiveMove?) {
    BoxWithConstraints(modifier.background(paper)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val picture by produceState<ImageBitmap?>(null, html, widthPx) {
            value = withContext(MediaWork) {
                runCatching {
                    val drawer = BookPageDrawer { src -> allowedDir.files.read("${allowedDir.path}/$src") }
                    try {
                        val page = drawer.pages(WebKitPrint.forBookScreen(html)).firstOrNull() ?: return@runCatching null
                        val image = drawer.draw(page, (widthPx / page.widthMm).toFloat())
                        image.toComposeImageBitmap().also { image.close() }
                    } finally {
                        drawer.close()
                    }
                }.getOrNull()
            }
        }
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}
