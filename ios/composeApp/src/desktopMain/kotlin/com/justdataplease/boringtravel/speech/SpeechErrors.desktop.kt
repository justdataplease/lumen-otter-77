package com.justdataplease.boringtravel.speech

import java.util.Locale

internal actual fun displayLanguage(tag: String, display: String): String =
    Locale.forLanguageTag(tag).getDisplayLanguage(Locale.forLanguageTag(display))
