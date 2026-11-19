package com.justdataplease.boringtravel.speech

import java.util.Locale

/** The Android app's DictationTextTest (read in as it is) names the display language as a java.util.Locale. */
fun DictationText.languageName(tag: String, display: Locale): String = languageName(tag, display.toLanguageTag())
