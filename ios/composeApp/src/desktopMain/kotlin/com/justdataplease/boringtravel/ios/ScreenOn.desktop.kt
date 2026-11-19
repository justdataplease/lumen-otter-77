package com.justdataplease.boringtravel.ios

/** The desktop's screen locks as it likes: the times it was asked to stay on are counted for the tests. */
actual fun setScreenKeptOn(on: Boolean) {
    ScreenKeptOn.now = on
}

/** Whether the screen was last asked to stay on (desktop tests read it). */
object ScreenKeptOn {
    var now: Boolean = false
}
