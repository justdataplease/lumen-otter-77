package com.justdataplease.boringtravel.data.repo

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The time rows are stamped with. Backups and shared trips merge by "the later change wins", so two phones must agree
 * on what later is: each takes the time from Google's replies (the Date header of a Drive call) and keeps the
 * difference to its own clock while the app runs. Until a reply arrives (a phone offline since the app started) it
 * uses its own clock.
 */
object Stamps {
    /** Smaller differences are noise: the header counts whole seconds and the reply took time to arrive. */
    const val NOISE_MS = 3_000L

    /** Google's clock minus this phone's, in ms; 0 while unknown or within [NOISE_MS]. */
    @Volatile var offsetMs: Long = 0L
        private set

    fun now(wallMs: Long = System.currentTimeMillis()): Long = wallMs + offsetMs

    /** The stamp of a change to a row last stamped [previous]: now, and never earlier than what it replaces. */
    fun next(previous: Long = 0L, wallMs: Long = System.currentTimeMillis()): Long = maxOf(now(wallMs), previous + 1)

    /** Google's clock said [serverMs] when this phone's said [wallMs]. */
    fun observe(serverMs: Long, wallMs: Long = System.currentTimeMillis()) {
        val raw = serverMs - wallMs
        val offset = if (raw in -NOISE_MS..NOISE_MS) 0L else raw
        // A settled offset moves only when the clocks drifted apart again, not with every reply's delay.
        if (offset != 0L && offsetMs != 0L && (offset - offsetMs) in -NOISE_MS..NOISE_MS) return
        offsetMs = offset
    }

    /** An HTTP Date header ("Tue, 10 Nov 2026 20:27:19 GMT") as epoch ms, or null. */
    fun httpDate(value: String?): Long? = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { ZonedDateTime.parse(it, HTTP_DATE).withZoneSameInstant(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)
}
