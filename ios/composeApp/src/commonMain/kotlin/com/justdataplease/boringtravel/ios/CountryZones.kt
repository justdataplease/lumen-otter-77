package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.ios.resources.Res

/*
 * Each country's time zones, for the phone's calendar's guess of the zone a trip's times are in (the Android app's
 * CalendarSync.zonesOf: ICU's TimeZone.getAvailableIDs(SystemTimeZoneType.CANONICAL_LOCATION, country, null), which
 * Foundation has no counterpart of; the second parity round, 19 Nov 2026).
 *
 * The table is the IANA time zone database's zone.tab (public domain; release 2026f, from
 * https://data.iana.org/time-zones/tzdb/zone.tab), bundled as files/tz/zone.tab. zone.tab, not zone1970.tab: ICU gives a
 * country the zones zone.tab lists for it (one country a row), while zone1970.tab files countries under a shared zone
 * since 1970 (Denmark under Europe/Berlin, the Netherlands under Europe/Brussels, Croatia under Europe/Belgrade), which
 * would name another country's city where Android names the trip's own.
 *
 * ICU's ids are CLDR's canonical ones, which keep a zone's older name where the tz database renamed it (Android writes
 * "Calcutta", "Saigon", "Kiev"): [CLDR_NAMES] turns zone.tab's names into those, so the guess and its label are the same
 * as Android's.
 */
object CountryZones {
    /** The table once read: each country code (upper case) with its zones; null before [load] or when it can't be read. */
    var loaded: Map<String, Set<String>>? = null
        private set

    suspend fun load(): Map<String, Set<String>>? =
        loaded ?: runCatching { parse(Res.readBytes(FILE).decodeToString()) }.getOrNull()?.also { loaded = it }

    /** Country [code]'s zones, as Android's ICU gives them; empty before the table is read, or for a code it hasn't. */
    fun of(code: String): Set<String> = loaded?.get(code.uppercase()).orEmpty()

    /**
     * zone.tab's rows as country → zones: "#" lines are comments, a row is the country code, its coordinates and the zone
     * (tab separated, a comment after). Each zone under its CLDR name ([CLDR_NAMES]).
     */
    fun parse(text: String): Map<String, Set<String>> {
        val out = LinkedHashMap<String, LinkedHashSet<String>>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val cells = line.split('\t')
            if (cells.size < 3) continue
            val country = cells[0].trim().uppercase()
            val zone = cells[2].trim()
            if (country.length != 2 || zone.isEmpty()) continue
            out.getOrPut(country) { LinkedHashSet() } += CLDR_NAMES[zone] ?: zone
        }
        return out
    }

    const val FILE = "files/tz/zone.tab"

    /**
     * The zones whose CLDR (and so ICU's, and so Android's) canonical id is an older name than the tz database's own:
     * CLDR's timezone aliases for the zones zone.tab lists. A name not here is the same in both.
     */
    val CLDR_NAMES: Map<String, String> = mapOf(
        "Africa/Asmara" to "Africa/Asmera",
        "America/Argentina/Buenos_Aires" to "America/Buenos_Aires",
        "America/Argentina/Catamarca" to "America/Catamarca",
        "America/Argentina/Cordoba" to "America/Cordoba",
        "America/Argentina/Jujuy" to "America/Jujuy",
        "America/Argentina/Mendoza" to "America/Mendoza",
        "America/Atikokan" to "America/Coral_Harbour",
        "America/Indiana/Indianapolis" to "America/Indianapolis",
        "America/Kentucky/Louisville" to "America/Louisville",
        "America/Nuuk" to "America/Godthab",
        "Asia/Ho_Chi_Minh" to "Asia/Saigon",
        "Asia/Kathmandu" to "Asia/Katmandu",
        "Asia/Kolkata" to "Asia/Calcutta",
        "Asia/Yangon" to "Asia/Rangoon",
        "Atlantic/Faroe" to "Atlantic/Faeroe",
        "Europe/Kyiv" to "Europe/Kiev",
        "Pacific/Chuuk" to "Pacific/Truk",
        "Pacific/Kanton" to "Pacific/Enderbury",
        "Pacific/Pohnpei" to "Pacific/Ponape",
    )
}
