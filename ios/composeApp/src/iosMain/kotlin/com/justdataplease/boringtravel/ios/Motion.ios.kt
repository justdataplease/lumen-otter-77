package com.justdataplease.boringtravel.ios

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** UIKit's Reduce Motion setting. */
actual fun reduceMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()

/** Apple Color Emoji draws every flag. */
actual val flagEmojiShown: Boolean = true
