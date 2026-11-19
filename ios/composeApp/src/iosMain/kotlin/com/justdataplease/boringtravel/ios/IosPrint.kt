package com.justdataplease.boringtravel.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.justdataplease.boringtravel.export.Paper
import com.justdataplease.boringtravel.model.PrintTheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSMakeRange
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.Foundation.create
import platform.Foundation.setValue
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.PDFKit.PDFDocument
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.NSKernAttributeName
import platform.UIKit.NSLineBreakByTruncatingTail
import platform.UIKit.NSMutableParagraphStyle
import platform.UIKit.NSParagraphStyleAttributeName
import platform.UIKit.NSTextAlignmentRight
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightMedium
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginPDFContextToFile
import platform.UIKit.UIScreen
import platform.UIKit.UIGraphicsBeginPDFPage
import platform.UIKit.UIGraphicsEndPDFContext
import platform.UIKit.UIGraphicsGetPDFContextBounds
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPrintInfo
import platform.UIKit.UIPrintInfoOutputType
import platform.UIKit.UIPrintInteractionController
import platform.UIKit.UIPrintInteractionControllerDelegateProtocol
import platform.UIKit.UIPrintPageRenderer
import platform.UIKit.UIPrintPaper
import platform.UIKit.UIRectFill
import platform.UIKit.drawInRect
import platform.UIKit.valueWithCGRect
import platform.UIKit.viewPrintFormatter
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.posix.memcpy

/*
 * The trip's pages on iPhone (the Android app's export/PdfMaker.kt, which prints a WebView). Apple's APIs called here:
 * WebKit (WKWebView with JavaScript off, added to the key window off screen while it prints, CSS zoom for WebKit's print
 * scale, loadFileURL:allowingReadAccessToURL: on the folder of the page, its photos and
 * fonts; nothing else can load: the page carries a content security policy, PrintPages.lockedDown), UIKit's printing
 * (the web view's viewPrintFormatter in a UIPrintPageRenderer whose paperRect and printableRect are the paper and the
 * margins the HTML is laid out for, drawn page by page into UIGraphicsBeginPDFContextToFile; the page's ground with
 * UIRectFill, the footer with NSString's drawInRect:withAttributes:, in the CSS's own Caveat and IBM Plex Mono made from
 * the bundled files by Core Text), PDFKit (PDFDocument, PDFPage's thumbnailOfSize:forBox: for the preview) and
 * UIPrintInteractionController with the PDF file for Print, on the printer's paper nearest the PDF's page (its delegate's
 * choosePaper, UIPrintPaper.bestPaperForPageSize). Since round 21
 * also the book's pages ([writeBook]): the same WebKit print on the book's own sheet, no margins, no footer, its HTML in
 * WebKit's print form for the sheet (WebKitPrint.forBookPrint).
 */
@OptIn(ExperimentalForeignApi::class)
class IosPagePrinter : PagePrinter {
    override suspend fun writePdf(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, paper: Paper, theme: PrintTheme, footer: String): Int =
        withContext(Dispatchers.Main) {
            if (!files.write(htmlPath, forWebKit(html).encodeToByteArray())) throw ExportException("The PDF came out empty. Try again.")
            val folder = htmlPath.substringBeforeLast('/')
            val config = WKWebViewConfiguration()
            config.defaultWebpagePreferences.allowsContentJavaScript = false
            // Off screen but in the window: a web view outside any window may be paused by WebKit and print blank pages.
            val web = WKWebView(frame = CGRectMake(-4 * paper.widthMm * PT, 0.0, paper.widthMm * PT, paper.heightMm * PT), configuration = config)
            web.alpha = 0.01
            UIApplication.sharedApplication.keyWindow?.addSubview(web)
            try {
                web.loadFileURL(NSURL.fileURLWithPath(files.absolute(htmlPath)), allowingReadAccessToURL = NSURL.fileURLWithPath(files.absolute(folder), isDirectory = true))
                withTimeout(LOAD_TIMEOUT_MS) {
                    // The load starts a moment after it is asked for, then runs until the page and its pictures are in.
                    var waited = 0
                    while (!web.loading && waited < 40) {
                        delay(25)
                        waited++
                    }
                    while (web.loading) delay(50)
                }
                // Fonts and decoded photos settle a moment after the load (as on Android).
                delay(300)
                // The PDF goes straight to its file (a trip's photos can make it big); its folder is made first.
                if (!files.write(pdfPath, ByteArray(0))) throw ExportException("The PDF came out empty. Try again.")
                draw(web, paper, theme, footer, files.absolute(pdfPath))
            } finally {
                web.stopLoading()
                web.removeFromSuperview()
            }
        }

    /** [html] as WebKit should print it: its scale, and the markers' bands made flat ([WebKitPrint], in common code). */
    private fun forWebKit(html: String): String = WebKitPrint.forWebKit(html)

    /**
     * A book's pages (round 21): the same path as the trip's pages, on the book's own sheet with no margins and no
     * footer. The HTML goes in WebKit's print form for the sheet ([WebKitPrint.forBookPrint]: the highlighter's bands
     * flat, the scrim a picture, the zoom for WebKit's print scale on the sheet's rounded width, each page's section a
     * pixel shorter than WebKit's page so none runs onto the next sheet), the formatter's pages are drawn one per sheet
     * on [ground] (the book's paper; white around a bleed's crop marks), which also fills the last few tenths of a
     * millimetre WebKit leaves at a sheet's foot.
     */
    override suspend fun writeBook(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, sheet: BookSheetSize, ground: Long): Int =
        withContext(Dispatchers.Main) {
            val printed = WebKitPrint.forBookPrint(html, sheet.widthMm, sheet.heightMm)
            if (!files.write(htmlPath, printed.encodeToByteArray())) throw ExportException("The PDF came out empty. Try again.")
            val folder = htmlPath.substringBeforeLast('/')
            val config = WKWebViewConfiguration()
            config.defaultWebpagePreferences.allowsContentJavaScript = false
            val w = sheet.widthMm * PT
            val h = sheet.heightMm * PT
            val web = WKWebView(frame = CGRectMake(-4 * w, 0.0, w, h), configuration = config)
            web.alpha = 0.01
            UIApplication.sharedApplication.keyWindow?.addSubview(web)
            try {
                web.loadFileURL(NSURL.fileURLWithPath(files.absolute(htmlPath)), allowingReadAccessToURL = NSURL.fileURLWithPath(files.absolute(folder), isDirectory = true))
                withTimeout(LOAD_TIMEOUT_MS) {
                    var waited = 0
                    while (!web.loading && waited < 40) {
                        delay(25)
                        waited++
                    }
                    while (web.loading) delay(50)
                }
                // Fonts and decoded photos settle a moment after the load (as on Android).
                delay(300)
                if (!files.write(pdfPath, ByteArray(0))) throw ExportException("The PDF came out empty. Try again.")
                drawSheets(web, w, h, ground, files.absolute(pdfPath))
            } finally {
                web.stopLoading()
                web.removeFromSuperview()
            }
        }

    /** The web view's pages drawn one per sheet [w] × [h] points into a PDF at [out]; the number of pages. */
    private fun drawSheets(web: WKWebView, w: Double, h: Double, ground: Long, out: String): Int {
        val paperRect = CGRectMake(0.0, 0.0, w, h)
        val renderer = UIPrintPageRenderer()
        renderer.addPrintFormatter(web.viewPrintFormatter(), startingAtPageAtIndex = 0)
        renderer.setValue(NSValue.valueWithCGRect(paperRect), forKey = "paperRect")
        renderer.setValue(NSValue.valueWithCGRect(paperRect), forKey = "printableRect")
        val pages = renderer.numberOfPages.toInt()
        if (pages <= 0) return 0
        if (!UIGraphicsBeginPDFContextToFile(out, paperRect, null)) return 0
        renderer.prepareForDrawingPages(NSMakeRange(0u, pages.toULong()))
        val bounds = UIGraphicsGetPDFContextBounds()
        for (i in 0 until pages) {
            UIGraphicsBeginPDFPage()
            color(ground).setFill()
            UIRectFill(bounds)
            renderer.drawPageAtIndex(i.toLong(), inRect = bounds)
        }
        UIGraphicsEndPDFContext()
        return pages
    }

    /** The web view's pages drawn into a PDF at [out]; the number of pages (0 when it laid out none). */
    private fun draw(web: WKWebView, paper: Paper, theme: PrintTheme, footer: String, out: String): Int {
        val (topMm, sideMm, bottomMm) = PrintPages.MARGINS_MM
        val w = paper.widthMm * PT
        val h = paper.heightMm * PT
        val paperRect = CGRectMake(0.0, 0.0, w, h)
        val printable = CGRectMake(sideMm * PT, topMm * PT, w - 2 * sideMm * PT, h - (topMm + bottomMm) * PT)
        val renderer = UIPrintPageRenderer()
        renderer.addPrintFormatter(web.viewPrintFormatter(), startingAtPageAtIndex = 0)
        // Both are read-only on the renderer; key-value coding sets them, as Apple's own samples do.
        renderer.setValue(NSValue.valueWithCGRect(paperRect), forKey = "paperRect")
        renderer.setValue(NSValue.valueWithCGRect(printable), forKey = "printableRect")
        val pages = renderer.numberOfPages.toInt()
        if (pages <= 0) return 0
        if (!UIGraphicsBeginPDFContextToFile(out, paperRect, null)) return 0
        renderer.prepareForDrawingPages(NSMakeRange(0u, pages.toULong()))
        val bounds = UIGraphicsGetPDFContextBounds()
        for (i in 0 until pages) {
            UIGraphicsBeginPDFPage()
            color(PrintPages.pageColor(theme)).setFill()
            UIRectFill(bounds)
            renderer.drawPageAtIndex(i.toLong(), inRect = bounds)
            // The cover has no footer (the CSS's @page :first).
            if (i > 0) foot(footer, i + 1, theme, w, h)
        }
        UIGraphicsEndPDFContext()
        return pages
    }

    /**
     * The trip's name on the left and the page's number on the right, 4 mm under the text (the CSS's @page footer), in
     * the CSS's own faces from the app's bundled files ([bundledUIFont]): Caveat at 600 and 13 pt for Postcard's
     * opposites, IBM Plex Mono Medium at 7.5 pt with 0.04 em between the letters for Postcard (PrintCss.page).
     */
    private fun foot(title: String, number: Int, theme: PrintTheme, w: Double, h: Double) {
        val (_, sideMm, bottomMm) = PrintPages.MARGINS_MM
        val mono = theme == PrintTheme.Postcard
        val font = if (mono) {
            bundledUIFont("plex_mono_medium.ttf", MONO_PT) ?: UIFont.monospacedSystemFontOfSize(MONO_PT, UIFontWeightMedium)
        } else {
            bundledUIFont("caveat.ttf", HAND_PT, weight = 600.0) ?: UIFont.systemFontOfSize(HAND_PT)
        }
        val ink = color(PrintPages.footerColor(theme))
        val top = h - bottomMm * PT + 4 * PT
        val left = sideMm * PT
        val width = w - 2 * sideMm * PT
        val truncating = NSMutableParagraphStyle().apply { setLineBreakMode(NSLineBreakByTruncatingTail) }
        val right = NSMutableParagraphStyle().apply { setAlignment(NSTextAlignmentRight) }
        fun attributes(paragraph: NSMutableParagraphStyle): Map<Any?, Any?> = buildMap {
            put(NSFontAttributeName, font)
            put(NSForegroundColorAttributeName, ink)
            put(NSParagraphStyleAttributeName, paragraph)
            // letter-spacing:.04em on the mono footer.
            if (mono) put(NSKernAttributeName, NSNumber(double = MONO_PT * 0.04))
        }
        NSString.create(string = title).drawInRect(CGRectMake(left, top, width * 0.8, 24.0), withAttributes = attributes(truncating))
        NSString.create(string = number.toString()).drawInRect(CGRectMake(left, top, width, 24.0), withAttributes = attributes(right))
    }

    override suspend fun open(files: MediaFiles, pdfPath: String): PdfPages? = withContext(Dispatchers.Main) {
        val path = files.absolute(pdfPath)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val doc = runCatching { PDFDocument(uRL = NSURL.fileURLWithPath(path)) }.getOrNull() ?: return@withContext null
        val count = doc.pageCount.toInt()
        val first = if (count > 0) doc.pageAtIndex(0u) else null
        if (first == null) return@withContext null
        val aspect = first.boundsForBox(kPDFDisplayBoxMediaBox).useContents { (size.width / size.height).toFloat() }
        object : PdfPages {
            override val count = count
            override val aspect = aspect

            override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.Main) {
                val page = if (index in 0 until count) doc.pageAtIndex(index.toULong()) else null
                page ?: return@withContext null
                // Points: PDFKit makes the picture at the screen's scale.
                val wPt = widthPx.coerceIn(64, PdfPages.MAX_PX) / UIScreen.mainScreen.scale
                val image = page.thumbnailOfSize(CGSizeMake(wPt, wPt / aspect), forBox = kPDFDisplayBoxMediaBox)
                val png = UIImagePNGRepresentation(image)?.toBytes() ?: return@withContext null
                runCatching { org.jetbrains.skia.Image.makeFromEncoded(png).toComposeImageBitmap() }.getOrNull()
            }
        }
    }

    /**
     * The PDF at [pdfPath] to the phone's print dialog, on the printer's paper nearest the PDF's own page size
     * (UIPrintInteractionControllerDelegate's choosePaper with UIPrintPaper.bestPaperForPageSize), as Android's print job
     * asks for the page's media size (a trip's pages on its paper, a book on its sheet) instead of fitting it to
     * whatever paper the printer has loaded.
     */
    override fun print(files: MediaFiles, pdfPath: String, jobName: String): Boolean {
        if (!UIPrintInteractionController.printingAvailable) return false
        val controller = UIPrintInteractionController.sharedPrintController
        val info = UIPrintInfo.printInfo()
        info.jobName = jobName
        info.outputType = UIPrintInfoOutputType.UIPrintInfoOutputGeneral
        controller.printInfo = info
        val url = NSURL.fileURLWithPath(files.absolute(pdfPath))
        val page = runCatching { PDFDocument(uRL = url).takeIf { it.pageCount > 0u }?.pageAtIndex(0u) }.getOrNull()
        val size = page?.boundsForBox(kPDFDisplayBoxMediaBox)?.useContents { size.width to size.height }
        printPaper = size?.let { (w, h) -> PaperAtPageSize(w, h) }
        controller.delegate = printPaper
        controller.printingItem = url
        return controller.presentAnimated(true) { _, _, _ ->
            controller.delegate = null
            printPaper = null
        }
    }

    private fun color(argb: Long): UIColor = UIColor.colorWithRed(
        ((argb shr 16) and 0xFF) / 255.0, ((argb shr 8) and 0xFF) / 255.0, (argb and 0xFF) / 255.0, 1.0,
    )

    private fun NSData.toBytes(): ByteArray {
        val n = length.toInt()
        val out = ByteArray(n)
        if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }

    private companion object {
        /** Points per mm. */
        const val PT = 72.0 / 25.4
        const val LOAD_TIMEOUT_MS = 60_000L

        /** The footer's sizes in the CSS: Caveat 13 pt, IBM Plex Mono 7.5 pt. */
        const val HAND_PT = 13.0
        const val MONO_PT = 7.5
    }
}

/**
 * The print dialog's paper: the printer's paper nearest a [width] × [height] point page (Apple's bestPaperForPageSize).
 * UIKit keeps its delegate weakly, so the one in use is held in [printPaper] until the dialog closes.
 */
@OptIn(ExperimentalForeignApi::class)
private class PaperAtPageSize(private val width: Double, private val height: Double) : NSObject(), UIPrintInteractionControllerDelegateProtocol {
    override fun printInteractionController(printInteractionController: UIPrintInteractionController, choosePaper: List<*>): UIPrintPaper =
        UIPrintPaper.bestPaperForPageSize(CGSizeMake(width, height), withPapersFromArray = choosePaper)
}

/**
 * The print dialog's paper chooser while it is open. Outside the class: a Kotlin class that extends an Objective-C one
 * can't keep a field in its companion (the Mac's rule).
 */
private var printPaper: PaperAtPageSize? = null
