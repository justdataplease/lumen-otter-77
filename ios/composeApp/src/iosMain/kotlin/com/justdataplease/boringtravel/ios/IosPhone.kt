package com.justdataplease.boringtravel.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCalendar
import platform.Foundation.NSData
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.writeToFile
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityItemSourceProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/*
 * The phone's clock, week, share sheet, photo library and file pickers on iPhone ([Phone]). Apple's APIs called here:
 * Foundation (NSDateFormatter's dateFormatFromTemplate, NSLocale.currentLocale, NSCalendar.currentCalendar's
 * firstWeekday, NSFileManager, NSData), UIKit (UIActivityViewController, UIDocumentPickerViewController for exporting a
 * file and for opening a zip, presented over the key window's top view controller), UniformTypeIdentifiers (UTTypeZIP)
 * and Photos (PHPhotoLibrary with add-only access, PHAssetChangeRequest.creationRequestForAssetFromImage); a link
 * opened with UIApplication's openURL:options:completionHandler: (the AI planner's report, by mail).
 */

/** This iPhone: its region's clock, its first weekday, its share sheet, Photos and the Files app, over [files]. */
fun iosPhone(files: IosMediaFiles): Phone = Phone(
    uses24Hour = uses24Hour(), firstDayOfWeek = firstDayOfWeek(), share = ::shareSheet,
    shareFile = ::shareFile, saveImage = ::saveImage,
    exportFile = { path, done -> exportFile(files.absolute(path), done) },
    pickFile = { to, done -> pickFile(files, to, done) },
    openLink = ::openLink,
)

/** A link to the app that takes it (the mail app for mailto:); [done] on the main thread with whether one opened it. */
private fun openLink(url: String, done: (Boolean) -> Unit) {
    val link = NSURL.URLWithString(url) ?: return done(false)
    UIApplication.sharedApplication.openURL(link, options = emptyMap<Any?, Any?>()) { opened ->
        dispatch_async(dispatch_get_main_queue()) { done(opened) }
    }
}

/** The region's time pattern for an hour ("j") has an AM/PM mark ("a") on a 12-hour clock. */
private fun uses24Hour(): Boolean {
    val pattern = NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale) ?: return true
    return !pattern.contains('a')
}

/** Foundation counts 1 = Sunday … 7 = Saturday; the app 1 = Monday … 7 = Sunday. */
private fun firstDayOfWeek(): Int {
    val sundayFirst = NSCalendar.currentCalendar.firstWeekday.toInt()
    return (sundayFirst + 5) % 7 + 1
}

/** The view controller on top, to present over; null when there is no window. */
private fun top(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    while (true) top = top.presentedViewController ?: break
    return top
}

/**
 * The system share sheet with [text] (messages, mail, notes) and [subject] for the apps that take one (Android's
 * EXTRA_SUBJECT: a mail's subject); false when there is no window to show it over.
 */
private fun shareSheet(subject: String, text: String): Boolean = presentShareSheet(listOf(withSubject(text, subject)))

/**
 * [item] as the share sheet's item with [subject] (UIActivityItemSource's subjectForActivityType, Apple's way to give
 * the mail app its subject); [item] itself when [subject] is blank.
 */
internal fun withSubject(item: Any, subject: String): Any = if (subject.isBlank()) item else SubjectItem(item, subject)

/** A share item and its subject (the share sheet asks for both when an app is picked). */
@OptIn(ExperimentalForeignApi::class)
private class SubjectItem(private val item: Any, private val subject: String) : NSObject(), UIActivityItemSourceProtocol {
    override fun activityViewControllerPlaceholderItem(activityViewController: UIActivityViewController): Any = item

    @ObjCSignatureOverride
    override fun activityViewController(activityViewController: UIActivityViewController, itemForActivityType: String?): Any? = item

    @ObjCSignatureOverride
    override fun activityViewController(activityViewController: UIActivityViewController, subjectForActivityType: String?): String = subject
}

/** The share sheet with [items] over the top view controller; false when there is no window (the trip video too, round 20). */
internal fun presentShareSheet(items: List<Any>): Boolean {
    val top = top() ?: return false
    val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
    // On a bigger screen the sheet hangs from a view; on iPhone it rises from the bottom.
    sheet.popoverPresentationController?.sourceView = top.view
    top.presentViewController(sheet, animated = true, completion = null)
    return true
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.data(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/**
 * A file for the share sheet: written to the temporary folder under [name], handed over by its address, with [subject]
 * for the apps that take one (blank: none).
 */
private fun shareFile(name: String, @Suppress("UNUSED_PARAMETER") mime: String, bytes: ByteArray, subject: String): Boolean {
    val path = NSTemporaryDirectory().trimEnd('/') + "/" + name.replace('/', '-')
    if (!bytes.data().writeToFile(path, atomically = true)) return false
    return presentShareSheet(listOf(withSubject(NSURL.fileURLWithPath(path), subject)))
}

/** A picture into Photos, with add-only access (asked the first time). [done] comes back on the main thread. */
private fun saveImage(png: ByteArray, done: (Boolean) -> Unit) {
    fun main(ok: Boolean) = dispatch_async(dispatch_get_main_queue()) { done(ok) }
    PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
        if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
            main(false)
            return@requestAuthorizationForAccessLevel
        }
        val image = UIImage(data = png.data())
        PHPhotoLibrary.sharedPhotoLibrary().performChanges({ PHAssetChangeRequest.creationRequestForAssetFromImage(image) }) { ok, _ -> main(ok) }
    }
}

/** The document picker's answer: the files picked, or none. Kept while the picker is open. */
private class PickerAnswer(private val picked: (List<NSURL>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        openPicker = null
        picked(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        openPicker = null
        picked(emptyList())
    }
}

/**
 * The open picker's delegate (UIKit holds it weakly). Outside the class: a Kotlin class that extends an Objective-C one
 * can't keep a field in its companion (the Mac build of 18 Nov 2026 stopped on it; Windows can't see this rule).
 */
private var openPicker: PickerAnswer? = null

/** The Files app's save sheet with a copy of the file at [absolute]; [done] says whether it was saved. */
private fun exportFile(absolute: String, done: (Boolean) -> Unit) {
    val top = top() ?: return done(false)
    val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(absolute)), asCopy = true)
    val answer = PickerAnswer { urls -> done(urls.isNotEmpty()) }
    openPicker = answer
    picker.delegate = answer
    top.presentViewController(picker, animated = true, completion = null)
}

/** The Files app's picker for a zip; the file picked is copied to [to] under the app's files. */
@OptIn(ExperimentalForeignApi::class)
private fun pickFile(files: IosMediaFiles, to: String, done: (Boolean) -> Unit) {
    val top = top() ?: return done(false)
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeZIP), asCopy = true)
    val answer = PickerAnswer { urls ->
        val from = urls.firstOrNull()?.path
        if (from == null) {
            done(false)
        } else {
            val target = files.absolute(to)
            val manager = NSFileManager.defaultManager
            files.folderFor(target)
            manager.removeItemAtPath(target, error = null)
            done(manager.copyItemAtPath(from, toPath = target, error = null))
        }
    }
    openPicker = answer
    picker.delegate = answer
    top.presentViewController(picker, animated = true, completion = null)
}
