package com.justdataplease.boringtravel.compat

import platform.Foundation.NSLog

/** NSLog, with no format arguments: a % in the message is doubled so NSLog prints it as it is. */
actual fun diag(message: String) {
    NSLog("BT-DIAG " + message.replace("%", "%%"))
}
