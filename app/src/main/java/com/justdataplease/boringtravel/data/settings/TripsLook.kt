package com.justdataplease.boringtravel.data.settings

/**
 * Me › Trips look: how the Trips tab shows the trips. Tickets (the default): each trip a ticket with its countdown on
 * the stub, in its Calendar colour. Pictures: the framed covers and thumbnails, with the countdown beside them.
 */
enum class TripsLook(val id: String, val label: String) {
    Tickets("tickets", "Tickets"), Pictures("pictures", "Pictures");

    companion object {
        /** Unknown and missing ids are Tickets. */
        fun of(id: String?): TripsLook = entries.firstOrNull { it.id == id } ?: Tickets
    }
}
