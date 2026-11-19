package com.justdataplease.boringtravel.data.db

/** A count per trip from a GROUP BY query (planned items, entries). Not a table. */
data class TripCount(val tripId: String, val n: Int)

/** A trip's first photo, from a query (not a table). */
data class TripPhoto(val tripId: String, val mediaId: String)
