package com.justdataplease.boringtravel.ios

/**
 * Compares two names as the phone's language sorts them (the Android app's java.text.Collator): Foundation's
 * localizedCompare on iPhone, Java's Collator on the desktop.
 */
expect fun compareNames(a: String, b: String): Int
