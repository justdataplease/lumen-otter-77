package com.justdataplease.boringtravel.ios

/*
 * Two things the globe asks of the phone (the Android app asks Android's animator settings and Paint.hasGlyph).
 */

/** Whether the phone asks for less motion (iPhone: Settings › Accessibility › Motion › Reduce Motion): no idle spin. */
expect fun reduceMotion(): Boolean

/** Whether this phone draws flags as emoji (iPhone: Apple Color Emoji has every flag; Windows has none). */
expect val flagEmojiShown: Boolean
