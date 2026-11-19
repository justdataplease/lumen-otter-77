package com.justdataplease.boringtravel.compat

/** The computer's region, from Java's default locale. */
actual fun phoneRegion(): String = java.util.Locale.getDefault().country.orEmpty()
