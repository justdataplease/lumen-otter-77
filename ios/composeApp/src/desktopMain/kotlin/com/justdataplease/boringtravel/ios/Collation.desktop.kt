package com.justdataplease.boringtravel.ios

import java.text.Collator

private val collator: Collator = Collator.getInstance()

/** Java's Collator in the computer's language, as the Android app sorts. */
actual fun compareNames(a: String, b: String): Int = collator.compare(a, b)
