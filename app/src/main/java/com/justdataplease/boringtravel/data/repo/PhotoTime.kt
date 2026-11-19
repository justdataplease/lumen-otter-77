package com.justdataplease.boringtravel.data.repo

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Public EXIF attributes, including the capture offset when present. Never invent a date for a bad tag. */
object PhotoTime {
    private val format = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

    fun parse(value: String?, offset: String?, fallbackZone: ZoneId = ZoneId.systemDefault()): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val date = LocalDateTime.parse(value.trim(), format)
            val zone = offset?.trim()?.takeIf { it.isNotEmpty() }?.let { ZoneOffset.of(it) } ?: fallbackZone
            date.atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** How far outside the camera session a shot's EXIF time may be (a slow save, a clock a little off). */
    const val SESSION_SLACK_MS = 2 * 60_000L

    /**
     * The time for a photo just taken with the camera: its EXIF time when that falls while the camera was open, else
     * when the camera came back (a camera with a wrong clock, or no EXIF at all).
     */
    fun forShot(exif: Long?, launchedAt: Long, returnedAt: Long): Long =
        exif?.takeIf { it in (launchedAt - SESSION_SLACK_MS)..(returnedAt + SESSION_SLACK_MS) } ?: returnedAt
}
