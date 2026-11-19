package com.justdataplease.boringtravel.ios

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithName

/** Foundation's NSTimeZone: secondsFromGMTForDate, isDaylightSavingTimeForDate, nextDaylightSavingTimeTransitionAfterDate. */
private fun zone(id: String?): NSTimeZone = id?.takeIf { it.isNotBlank() }?.let { NSTimeZone.timeZoneWithName(it) } ?: NSTimeZone.localTimeZone

private fun date(ms: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)

actual fun zoneOffsetMs(epochMs: Long, zoneId: String?): Long = zone(zoneId).secondsFromGMTForDate(date(epochMs)) * 1000L

actual fun zoneKnown(zoneId: String): Boolean = NSTimeZone.timeZoneWithName(zoneId) != null

actual fun zoneDst(epochMs: Long, zoneId: String): Boolean = zone(zoneId).isDaylightSavingTimeForDate(date(epochMs))

actual fun zoneNextTransition(epochMs: Long, zoneId: String): Long? =
    zone(zoneId).nextDaylightSavingTimeTransitionAfterDate(date(epochMs))?.let { (it.timeIntervalSince1970 * 1000.0).toLong() }
