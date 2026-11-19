package com.justdataplease.boringtravel.ui.book

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.justdataplease.boringtravel.ios.BookFolder
import com.justdataplease.boringtravel.ios.PrintPages
import com.justdataplease.boringtravel.ios.WebKitPrint
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/*
 * The book's page on iPhone (the Android app's PagePreview, a WebView): a WKWebView put on screen through Compose's UIKit
 * interop. The page's HTML is written beside its photos and fonts (exports/, as Share's pages) in its WebKit form
 * (WebKitPrint.forBookScreen: the gradients WebKit prints wrong in the forms it prints right, so the screen shows what
 * the PDF will; a viewport so a CSS pixel is a point and the page's zoom fits it to the view) and with the content
 * security policy Share's pages carry (PrintPages.lockedDown: nothing from the network, no scripts of the page's own).
 * It is loaded with loadFileURL:allowingReadAccessToURL: on that folder only. The page's own JavaScript is off
 * (WKWebpagePreferences.allowsContentJavaScript); the editor's scripts (round 22's live moves, moveScript and
 * resetScript) run through evaluateJavaScript:completionHandler:, which that switch doesn't stop. It takes no touches:
 * they go to what Compose draws above it. Apple's calls here were only type-checked on Windows.
 */

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PagePreview(html: String, allowedDir: BookFolder, paper: Color, modifier: Modifier, live: LiveMove?) {
    val holder = remember { PageWebView() }
    DisposableEffect(holder) { onDispose { holder.release() } }
    UIKitView(
        factory = { holder.make(paper) },
        modifier = modifier,
        update = { holder.show(html, allowedDir, live) },
        properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
    )
}

/** One page's web view and what it last showed (the Android app's PreviewState). */
@OptIn(ExperimentalForeignApi::class)
private class PageWebView {
    private var web: WKWebView? = null
    private var shown: String? = null
    private var file: Pair<BookFolder, String>? = null
    private var moved: String? = null

    fun make(paper: Color): WKWebView {
        val config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = false
        val view = WKWebView(frame = CGRectMake(0.0, 0.0, 10.0, 10.0), configuration = config)
        val ground = UIColor.colorWithRed(paper.red.toDouble(), paper.green.toDouble(), paper.blue.toDouble(), paper.alpha.toDouble())
        view.opaque = false
        view.backgroundColor = ground
        view.scrollView.backgroundColor = ground
        view.scrollView.scrollEnabled = false
        view.scrollView.bounces = false
        view.userInteractionEnabled = false
        web = view
        return view
    }

    fun show(html: String, folder: BookFolder, live: LiveMove?) {
        val view = web ?: return
        if (shown != html) {
            shown = html
            moved = null
            val name = "book-view-${++written}.html"
            val path = "${folder.path}/$name"
            if (folder.files.write(path, WebKitPrint.forBookScreen(PrintPages.lockedDown(html)).encodeToByteArray())) {
                view.loadFileURL(
                    NSURL.fileURLWithPath(folder.files.absolute(path)),
                    allowingReadAccessToURL = NSURL.fileURLWithPath(folder.files.absolute(folder.path), isDirectory = true),
                )
                file?.let { (f, p) -> f.files.delete(p) }
                file = folder to path
            }
        }
        if (live != null) {
            moved?.takeIf { it != live.id }?.let { view.evaluateJavaScript(resetScript(it), completionHandler = null) }
            moved = live.id
            view.evaluateJavaScript(moveScript(live), completionHandler = null)
        } else {
            moved?.let { view.evaluateJavaScript(resetScript(it), completionHandler = null) }
            moved = null
        }
    }

    fun release() {
        web?.stopLoading()
        web = null
        file?.let { (f, p) -> f.files.delete(p) }
        file = null
    }

    private companion object {
        /** Pages written this run: each its own file, so WebKit never shows an older one from its cache. */
        var written = 0
    }
}
