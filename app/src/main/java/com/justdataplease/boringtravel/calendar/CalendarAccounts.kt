package com.justdataplease.boringtravel.calendar

/** One calendar on the phone (a row of CalendarContract.Calendars). */
data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val ownerAccount: String = "",
    /** ARGB as the provider stores it. */
    val color: Int = 0,
    val primary: Boolean = false,
    /** CalendarContract access level: 500 (contributor) and up may add events. */
    val accessLevel: Int = 0,
    val visible: Boolean = true,
) {
    val writable: Boolean get() = accessLevel >= CONTRIBUTOR

    companion object {
        /** CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR. */
        const val CONTRIBUTOR = 500
    }
}

/** Who keeps a calendar in sync, from its account type (and name, for iCloud through a CalDAV app). */
enum class AccountKind(val label: String) {
    Google("Google"),
    ICloud("iCloud"),
    Outlook("Outlook"),
    Exchange("Exchange"),
    Samsung("Samsung"),
    CalDav("CalDAV"),
    Phone("On this phone"),
    Other("Other"),
}

/** Calendars under one account: "Google · ana@gmail.com". */
data class CalendarGroup(val kind: AccountKind, val accountName: String, val calendars: List<CalendarInfo>) {
    val title: String
        get() = if (accountName.isBlank() || kind == AccountKind.Phone || accountName.equals(kind.label, ignoreCase = true)) kind.label
        else "${kind.label} · $accountName"
}

/** Pure helpers for the calendar picker. */
object CalendarAccounts {

    fun kind(accountType: String, accountName: String): AccountKind {
        val type = accountType.lowercase()
        val name = accountName.lowercase()
        return when {
            name.contains("icloud") || name.endsWith("@me.com") || name.endsWith("@mac.com") -> AccountKind.ICloud
            type == "com.google" -> AccountKind.Google
            type.startsWith("com.microsoft") || type.contains("outlook") -> AccountKind.Outlook
            type.contains("exchange") -> AccountKind.Exchange
            type == "com.osp.app.signin" || type.startsWith("com.samsung") -> AccountKind.Samsung
            type.contains("davdroid") || type.contains("davx") || type.contains("caldav") || type.contains("smoothsync") ||
                type.contains("dmfs") -> AccountKind.CalDav
            type == "local" || type.isBlank() -> AccountKind.Phone
            else -> AccountKind.Other
        }
    }

    /**
     * Calendars that only mirror other data (holidays, contacts' birthdays, week numbers): never a place to add a
     * trip, and noise when importing.
     */
    fun isNoise(c: CalendarInfo): Boolean {
        val owner = c.ownerAccount.lowercase()
        val type = c.accountType.lowercase()
        return owner.endsWith("#holiday@group.v.calendar.google.com") ||
            owner.endsWith("#contacts@group.v.calendar.google.com") ||
            owner.endsWith("#weeknum@group.v.calendar.google.com") ||
            type.contains("birthday") || type.contains("holiday")
    }

    /** Writable, visible calendars to add a trip to, grouped by account: Google first, then iCloud, Outlook… */
    fun groups(calendars: List<CalendarInfo>): List<CalendarGroup> =
        calendars
            .filter { it.writable && it.visible && !isNoise(it) }
            .groupBy { kind(it.accountType, it.accountName) to it.accountName }
            .map { (k, list) -> CalendarGroup(k.first, k.second, list.sortedWith(compareBy({ !it.primary }, { it.name.lowercase() }))) }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.accountName.lowercase() }))

    /** The calendar to preselect: the linked one, else the first account's main calendar. */
    fun defaultChoice(groups: List<CalendarGroup>, linkedId: Long?, preferredAccountEmail: String? = null): CalendarInfo? {
        val all = groups.flatMap { it.calendars }
        val google = all.filter { it.accountType == "com.google" }
        val preferred = google.filter { preferredAccountEmail != null && it.accountName.equals(preferredAccountEmail, ignoreCase = true) }
        return all.firstOrNull { it.id == linkedId } ?: preferred.firstOrNull { it.primary } ?: preferred.firstOrNull()
            ?: google.firstOrNull { it.primary } ?: google.firstOrNull() ?: all.firstOrNull { it.primary } ?: all.firstOrNull()
    }
}
