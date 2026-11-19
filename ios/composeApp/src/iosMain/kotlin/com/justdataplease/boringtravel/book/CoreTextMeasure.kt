package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.value
import platform.CoreFoundation.CFAttributedStringCreate
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreText.CTFontCreateWithFontDescriptor
import platform.CoreText.CTFontDescriptorCreateCopyWithVariation
import platform.CoreText.CTFontManagerCreateFontDescriptorFromData
import platform.CoreText.CTFontRef
import platform.CoreText.CTLineCreateWithAttributedString
import platform.CoreText.CTLineGetTypographicBounds
import platform.CoreText.kCTFontAttributeName
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSString
import platform.Foundation.create
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The book's text measured on iPhone with Core Text, as WebKit measures what it prints there: each face made from the
 * bundled font file the page's @font-face draws it from ([fontFile]: CTFontManagerCreateFontDescriptorFromData, so the
 * same file, not a system font), at the weight it prints at ([measuredWeight]: CTFontDescriptorCreateCopyWithVariation on
 * the 'wght' axis), and each word laid out as one CTLine (Core Text's shaping: kerning and the standard ligatures on,
 * as in WebKit's text) and read back with CTLineGetTypographicBounds. Words are measured once at 100 pt and kept (as
 * Android's PaintTextMeasure keeps them). [fonts] reads a font file by its name.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class CoreTextMeasure(private val fonts: (String) -> ByteArray?) : TextMeasure {
    private val faces = HashMap<Pair<Face, Float?>, CTFontRef?>()
    private val cache = HashMap<String, Double>()
    private val busy = AtomicInt(0)

    override fun widthMm(text: String, face: Face, pt: Double, bold: Boolean): Double {
        val weight = measuredWeight(face, bold)
        val key = "${face.ordinal}|${weight ?: 0f}|$text"
        val em = locked {
            cache[key] ?: run {
                val font = faces.getOrPut(face to weight) { font(face, weight) }
                val w = if (font == null) TextFit.estimateEm(text) * face.widthFactor else line(text, font) / SIZE
                w.also { if (cache.size < MAX_CACHED) cache[key] = it }
            }
        }
        return em * pt * TextFit.PT_MM
    }

    /** The face from its file at [SIZE] points, at [weight] when it has a weight axis; null when the file can't be read. */
    private fun font(face: Face, weight: Float?): CTFontRef? {
        val bytes = fonts(face.fontFile) ?: return null
        if (bytes.isEmpty()) return null
        val data = bytes.asUByteArray().usePinned { CFDataCreate(null, it.addressOf(0), bytes.size.convert()) } ?: return null
        val described = CTFontManagerCreateFontDescriptorFromData(data)
        CFRelease(data)
        described ?: return null
        val chosen = if (weight == null) described else memScoped {
            val tag = alloc<IntVar>().apply { value = WGHT }
            val axis = CFNumberCreate(null, kCFNumberSInt32Type, tag.ptr)
            val varied = CTFontDescriptorCreateCopyWithVariation(described, axis, weight.toDouble())
            CFRelease(axis)
            if (varied != null) {
                CFRelease(described)
                varied
            } else {
                described
            }
        }
        val made = CTFontCreateWithFontDescriptor(chosen, SIZE, null)
        CFRelease(chosen)
        return made
    }

    /** [text]'s width in points as one line of Core Text in [font]. */
    private fun line(text: String, font: CTFontRef): Double {
        val string: CFStringRef = CFBridgingRetain(NSString.create(string = text))?.reinterpret() ?: return TextFit.estimateEm(text) * SIZE
        val attributes = CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionarySetValue(attributes, kCTFontAttributeName, font)
        val attributed = CFAttributedStringCreate(null, string, attributes)
        val ct = attributed?.let { CTLineCreateWithAttributedString(it) }
        val width = ct?.let { CTLineGetTypographicBounds(it, null, null, null) } ?: (TextFit.estimateEm(text) * SIZE)
        ct?.let { CFRelease(it as CFTypeRef) }
        attributed?.let { CFRelease(it as CFTypeRef) }
        attributes?.let { CFRelease(it as CFTypeRef) }
        CFRelease(string as CFTypeRef)
        return width
    }

    private inline fun <R> locked(block: () -> R): R {
        while (!busy.compareAndSet(0, 1)) Unit
        try {
            return block()
        } finally {
            busy.store(0)
        }
    }

    private companion object {
        const val SIZE = 100.0
        const val MAX_CACHED = 20_000

        /** 'wght' as a four-character code. */
        const val WGHT = 0x77676874
    }
}

private val shared: TextMeasure by lazy { CoreTextMeasure(BtFontFiles::file) }

actual fun phoneTextMeasure(): TextMeasure = shared
