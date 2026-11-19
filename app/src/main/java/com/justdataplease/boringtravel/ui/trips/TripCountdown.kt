package com.justdataplease.boringtravel.ui.trips

import com.justdataplease.boringtravel.data.db.Trip
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * What the Trips tab counts for a trip (the ticket stub in Tickets, the sticker and the row number in Pictures): a big
 * number over its unit ("17" over "days left", "330" / "days to go", "6" / "months ago"), or a word alone ("Tomorrow",
 * "Last day", "Yesterday", "Add dates"). A trip with a start and no end is one day, as on the Calendar. Pure.
 */
object TripCountdown {
    /** Where a trip stands today. */
    enum class Phase { Now, Next, Past, Someday }

    /** [number] over [unit]; a word alone has no number. */
    data class Count(val phase: Phase, val number: Long?, val unit: String)

    fun of(trip: Trip, today: LocalDate): Count = of(trip.startDate, trip.endDate, today)

    fun of(start: LocalDate?, end: LocalDate?, today: LocalDate): Count {
        if (start == null) return Count(Phase.Someday, null, "Add dates")
        val last = lastDay(start, end)
        return when {
            today.isBefore(start) -> {
                val days = start.toEpochDay() - today.toEpochDay()
                if (days == 1L) Count(Phase.Next, null, "Tomorrow") else Count(Phase.Next, days, "days to go")
            }
            today.isAfter(last) -> ago(today.toEpochDay() - last.toEpochDay())
            else -> {
                val left = last.toEpochDay() - today.toEpochDay()
                when (left) {
                    0L -> Count(Phase.Now, null, "Last day")
                    1L -> Count(Phase.Now, 1, "day left")
                    else -> Count(Phase.Now, left, "days left")
                }
            }
        }
    }

    /**
     * [days] since a trip ended, in the unit that reads naturally: days up to 13, then weeks up to 8, then months
     * (rounded) under a year, then years (rounded).
     */
    fun ago(days: Long): Count {
        val d = days.coerceAtLeast(1)
        if (d == 1L) return Count(Phase.Past, null, "Yesterday")
        if (d < 14) return Count(Phase.Past, d, "days ago")
        if (d < 60) return Count(Phase.Past, d / 7, "weeks ago")
        val months = (d / DAYS_PER_MONTH).roundToLong()
        if (months < 12) return Count(Phase.Past, months, "months ago")
        val years = (d / DAYS_PER_YEAR).roundToLong().coerceAtLeast(1)
        return Count(Phase.Past, years, if (years == 1L) "year ago" else "years ago")
    }

    /** How much of the trip has gone by, today included: day 14 of 31 is 14/31; 0 before it starts, 1 once it is over. */
    fun progress(start: LocalDate?, end: LocalDate?, today: LocalDate): Float {
        if (start == null) return 0f
        val last = lastDay(start, end)
        val days = last.toEpochDay() - start.toEpochDay() + 1
        val done = today.toEpochDay() - start.toEpochDay() + 1
        return (done.toFloat() / days).coerceIn(0f, 1f)
    }

    /**
     * The count under a trip: "68 places planned" (next and someday), "8 entries" (past); [short] for the Pictures rows
     * ("68 places"). Null for none.
     */
    fun countLine(count: Int?, past: Boolean, short: Boolean = false): String? {
        val n = count ?: 0
        return when {
            n <= 0 -> null
            past -> if (n == 1) "1 entry" else "$n entries"
            short -> if (n == 1) "1 place" else "$n places"
            else -> if (n == 1) "1 place planned" else "$n places planned"
        }
    }

    /** The last day: the end, or the start when there is no end (or it comes first). */
    private fun lastDay(start: LocalDate, end: LocalDate?): LocalDate = end?.takeIf { !it.isBefore(start) } ?: start

    private const val DAYS_PER_MONTH = 365.25 / 12
    private const val DAYS_PER_YEAR = 365.25
}
