package com.justdataplease.boringtravel.io

/**
 * A failure reading or writing: `java.io.IOException` on Android and the desktop (so the Android app still catches the
 * engine's errors as the IOExceptions they were), the engine's own on iPhone.
 */
expect open class IOException(message: String?) : Exception
