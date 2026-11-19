package com.justdataplease.boringtravel.ios

/** The phone's processor cores, for how many threads prepare the video's preview (Android: availableProcessors). */
expect fun processorCount(): Int

/**
 * The memory the app may count on, in MB, as Android's ActivityManager.memoryClass gives it (the preview's prepared
 * frames take a sixth of it, 24–64 MB: PreviewMath.budgetBytes). iPhone: a sixteenth of the phone's memory.
 */
expect fun memoryClassMb(): Int
