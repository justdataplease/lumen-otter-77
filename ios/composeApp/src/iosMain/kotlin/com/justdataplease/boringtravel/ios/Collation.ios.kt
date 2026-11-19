package com.justdataplease.boringtravel.ios

import platform.Foundation.NSString
import platform.Foundation.localizedCompare

/** NSString's localizedCompare, in the phone's language. */
@Suppress("CAST_NEVER_SUCCEEDS")
actual fun compareNames(a: String, b: String): Int = (a as NSString).localizedCompare(b).toInt()
