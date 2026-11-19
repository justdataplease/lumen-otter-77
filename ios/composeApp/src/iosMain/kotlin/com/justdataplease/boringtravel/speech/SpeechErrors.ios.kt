package com.justdataplease.boringtravel.speech

import platform.Foundation.NSLocale
import platform.Foundation.localizedStringForLanguageCode

/** Foundation's name for the language of [tag] ("el-GR" → "Greek" in English), as Java's Locale gives it on Android. */
internal actual fun displayLanguage(tag: String, display: String): String =
    NSLocale(localeIdentifier = display.replace('-', '_')).localizedStringForLanguageCode(tag.substringBefore('-').substringBefore('_')) ?: ""
