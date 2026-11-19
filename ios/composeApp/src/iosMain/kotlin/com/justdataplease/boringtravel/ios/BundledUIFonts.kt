package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreText.CTFontCreateWithFontDescriptor
import platform.CoreText.CTFontDescriptorCreateCopyWithVariation
import platform.CoreText.CTFontManagerCreateFontDescriptorFromData
import platform.Foundation.CFBridgingRelease
import platform.UIKit.UIFont

/**
 * A UIKit font made from one of the app's bundled font files (the Android app's res/font, read at start by
 * [BtFontFiles]), for what UIKit draws itself: the printed pages' footer, which Android's CSS sets in Caveat and IBM Plex
 * Mono. Core Text makes the face from the file's bytes (CTFontManagerCreateFontDescriptorFromData, as the book's
 * measuring does), at [weight] on its 'wght' axis when given (Caveat is a variable font), and a Core Text font is a
 * UIFont (toll-free bridged). Null when the file isn't there or can't be read.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun bundledUIFont(file: String, size: Double, weight: Double? = null): UIFont? {
    val bytes = BtFontFiles.file(file)?.takeIf { it.isNotEmpty() } ?: return null
    val data = bytes.asUByteArray().usePinned { CFDataCreate(null, it.addressOf(0), bytes.size.convert()) } ?: return null
    val described = CTFontManagerCreateFontDescriptorFromData(data)
    CFRelease(data)
    described ?: return null
    val chosen = if (weight == null) described else memScoped {
        val tag = alloc<IntVar>().apply { value = WGHT_AXIS }
        val axis = CFNumberCreate(null, kCFNumberSInt32Type, tag.ptr)
        val varied = CTFontDescriptorCreateCopyWithVariation(described, axis, weight)
        CFRelease(axis)
        if (varied != null) {
            CFRelease(described)
            varied
        } else described
    }
    val font = CTFontCreateWithFontDescriptor(chosen, size, null)
    CFRelease(chosen)
    return font?.let { CFBridgingRelease(it) as? UIFont }
}

/** The 'wght' axis tag as Core Text names axes ('w' 'g' 'h' 't'). */
private const val WGHT_AXIS = 0x77676874
