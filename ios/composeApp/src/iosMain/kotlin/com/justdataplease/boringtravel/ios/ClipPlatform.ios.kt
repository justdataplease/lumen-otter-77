package com.justdataplease.boringtravel.ios

import platform.Foundation.NSProcessInfo

actual fun processorCount(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt().coerceAtLeast(1)

/** A sixteenth of the phone's memory (a 6 GB iPhone: 384 MB, so the preview keeps 64 MB of frames). */
actual fun memoryClassMb(): Int = (NSProcessInfo.processInfo.physicalMemory / (16UL * 1024UL * 1024UL)).toInt().coerceIn(128, 512)
