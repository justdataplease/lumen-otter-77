package com.justdataplease.boringtravel.ios

/**
 * The desktop moves, except under its tests (the build sets boringtravel.reduceMotion): an endless idle spin never
 * lets a UI test settle.
 */
actual fun reduceMotion(): Boolean = System.getProperty("boringtravel.reduceMotion") == "true"

/** Windows' emoji font has no flags: the badges show the two letters, as Android's do without the glyph. */
actual val flagEmojiShown: Boolean = false
