package com.justdataplease.boringtravel.ios

import platform.UIKit.UIApplication

/** UIKit's idle timer, off while the screen is to stay on. */
actual fun setScreenKeptOn(on: Boolean) {
    UIApplication.sharedApplication.idleTimerDisabled = on
}
