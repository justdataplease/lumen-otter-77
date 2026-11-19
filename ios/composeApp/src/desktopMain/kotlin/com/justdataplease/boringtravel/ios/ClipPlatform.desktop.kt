package com.justdataplease.boringtravel.ios

actual fun processorCount(): Int = Runtime.getRuntime().availableProcessors()

actual fun memoryClassMb(): Int = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt().coerceIn(64, 512)
