package com.justdataplease.boringtravel.ios

import java.time.Instant
import java.time.ZoneId

private fun zone(id: String?): ZoneId = id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

actual fun zoneOffsetMs(epochMs: Long, zoneId: String?): Long = zone(zoneId).rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds * 1000L

actual fun zoneKnown(zoneId: String): Boolean = runCatching { ZoneId.of(zoneId) }.isSuccess

actual fun zoneDst(epochMs: Long, zoneId: String): Boolean = zone(zoneId).rules.isDaylightSavings(Instant.ofEpochMilli(epochMs))

actual fun zoneNextTransition(epochMs: Long, zoneId: String): Long? = zone(zoneId).rules.nextTransition(Instant.ofEpochMilli(epochMs))?.instant?.toEpochMilli()
