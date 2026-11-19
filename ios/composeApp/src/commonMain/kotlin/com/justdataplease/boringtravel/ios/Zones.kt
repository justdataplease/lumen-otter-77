package com.justdataplease.boringtravel.ios

/*
 * Time zones by their ids, as the calendar's rules need them (the Android app reads them with java.time's ZoneId):
 * Foundation's NSTimeZone on iPhone, java.time on the desktop.
 */

/** The offset of the zone named [zoneId] from UTC at [epochMs], in ms (null, blank or unknown: the phone's zone). */
expect fun zoneOffsetMs(epochMs: Long, zoneId: String?): Long

/** Whether [zoneId] names a zone this phone knows. */
expect fun zoneKnown(zoneId: String): Boolean

/** Whether summer time is in force in [zoneId] at [epochMs]. */
expect fun zoneDst(epochMs: Long, zoneId: String): Boolean

/** The zone's first change of offset after [epochMs]; null when it changes no more. */
expect fun zoneNextTransition(epochMs: Long, zoneId: String): Long?
