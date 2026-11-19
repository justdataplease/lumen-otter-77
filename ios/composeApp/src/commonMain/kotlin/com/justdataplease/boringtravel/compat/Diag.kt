package com.justdataplease.boringtravel.compat

/**
 * A line in the app's own log, for what a test on the Mac can't otherwise tell (why a sound or a map picture wasn't
 * made). On iPhone it is NSLog, which the Simulator's CI prints from the test result (Kotlin's println is buffered and
 * lost when the tour ends the app); on the desktop it is println. Starts with "BT-DIAG" so the workflow can find it.
 */
expect fun diag(message: String)
