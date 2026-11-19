package com.justdataplease.boringtravel.widget

import androidx.compose.ui.graphics.Color
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.Paper
import com.justdataplease.boringtravel.util.Dates
import java.time.LocalDate
import com.justdataplease.boringtravel.art.PlaneMark
import com.justdataplease.boringtravel.ui.theme.planeMark

/** What the home-screen widgets know about today; loaded fresh on every update (see [WidgetUpdates]). */
data class TodayInfo(
    val today: LocalDate,
    val look: Look,
    /** The trip happening today, if any. */
    val trip: Trip? = null,
    /** Where today is spent ("Naxos"): the day's own name when it has one, else its stops, else its planned places. */
    val place: String? = null,
    /** The next trip with dates after today, for "Cyclades in 12 days". */
    val next: Trip? = null,
    val wroteToday: Int = 0,
) {
    val day: Int? get() = trip?.dayNumber(today)
}

/** The bar's two lines next to the logo. */
data class ContextLine(val primary: String, val secondary: String)

object WidgetText {
    /** How far ahead a next trip is named on the bar ("Cyclades in 12 days"); past that, just the date. */
    const val NEXT_TRIP_DAYS = 60

    /**
     * "Day 4 · Naxos" over the trip's name; "Day 4 of 9" when the day's place isn't known yet; on a one-day trip
     * "Today". Without a trip: "No trip today" over the next trip ("Cyclades in 12 days") or today's date.
     */
    fun context(info: TodayInfo): ContextLine {
        val trip = info.trip
        val day = info.day
        if (trip != null && day != null) {
            val place = info.place?.trim()?.takeIf { it.isNotEmpty() && !it.equals(trip.title.trim(), ignoreCase = true) }
            val days = trip.days
            val head = if (days == 1) "Today" else "Day $day"
            val primary = when {
                place != null -> "$head · $place"
                days != null && days > 1 -> "Day $day of $days"
                else -> head
            }
            return ContextLine(primary, trip.title.trim().ifEmpty { date(info.today) })
        }
        val next = info.next
        val inDays = next?.startDate?.let { (it.toEpochDay() - info.today.toEpochDay()).toInt() }
        val secondary = when {
            next == null || inDays == null || inDays <= 0 || inDays > NEXT_TRIP_DAYS -> date(info.today)
            inDays == 1 -> "${next.title.trim()} tomorrow"
            else -> "${next.title.trim()} in $inDays days"
        }
        return ContextLine("No trip today", secondary)
    }

    /** The "Today" widget's second line: "Day 4 of 9 · 2 written today", or "No trip today · Fri 6 Nov 2026". */
    fun todayLine(info: TodayInfo): String = buildString {
        val trip = info.trip
        val day = info.day
        if (trip != null && day != null) append("Day $day of ${trip.days} · ") else append("No trip today · ")
        append(if (info.wroteToday == 0) Dates.dayLong(info.today) else "${info.wroteToday} written today")
    }

    /** The trip that starts soonest after [today] (deleted and undated trips left out). */
    fun nextTrip(trips: List<Trip>, today: LocalDate): Trip? =
        trips.filter { it.deletedAt == null && it.startDate?.isAfter(today) == true }.minByOrNull { it.startDate!! }

    private fun date(d: LocalDate): String = "${Dates.weekday(d)} ${Dates.dayMonth(d)}"
}

/**
 * A widget's colours and paper in one theme. The widgets keep the system font (see PLAN section 0) but take
 * everything else from the theme: its paper with its dots or rules, the ink, the Note button in the colour of the
 * theme's active tab (Postcard Pop's coral pill, Trail's yellow tag, the highlighter, Market's orange dot), and the
 * bar's edge and shadow (Postcard Pop's ink outline and hard shadow).
 */
data class WidgetLook(
    val look: Look,
    /** The bar's fill. */
    val paper: Color,
    val texture: Paper,
    val textureColor: Color?,
    /** Hairline round the bar, so light paper still has an edge on a light wallpaper. */
    val edge: Color,
    /** A flat shadow under the bar. */
    val shadow: Color,
    val ink: Color,
    val muted: Color,
    /** The Note button. */
    val note: Color,
    val onNote: Color,
    /** Degrees the Note pill leans, like the theme's active tab. */
    val noteTilt: Float,
    /** The round Camera and Voice buttons. */
    val button: Color,
    val buttonEdge: Color?,
    val onButton: Color,
    /** The logo's colours: the plane on the theme's yellow. */
    val mark: PlaneMark.Colors,
    /** Round ends (Trail) or the theme's small corners. */
    val pill: Boolean,
    /** Width of [edge] in dp (Postcard Pop's ink outline is heavier). */
    val edgeWidth: Float = 1f,
    /** Dot spacing on the bar in dp (Market's kraft fibres are finer). */
    val dotStep: Float = PaperGrid.DOT_STEP,
) {
    val notebook: Boolean get() = look.isNotebook

    companion object {
        fun of(look: Look): WidgetLook {
            val t = BtLooks.of(look)
            val c = t.colors
            return when (look) {
                // The ticket: white with the ink outline and a hard ink shadow, the coral Note pill leaning -3.
                Look.PostcardPop -> WidgetLook(
                    look = look, paper = c.surface, texture = Paper.Plain, textureColor = null, edge = c.ink,
                    shadow = c.ink, ink = c.ink, muted = c.muted, note = c.primary, onNote = c.onPrimary, noteTilt = -3f,
                    button = c.chip, buttonEdge = c.ink, onButton = c.ink, mark = c.planeMark(), pill = false, edgeWidth = 1.5f,
                )
                // The slab: Trail's dark bar colour, the yellow Note, round ends.
                Look.Trail -> WidgetLook(
                    look = look, paper = Color(0xFF24132C), texture = Paper.Plain, textureColor = null, edge = c.line,
                    shadow = Color.Black.copy(alpha = 0.28f), ink = c.ink, muted = c.muted,
                    note = c.primary, onNote = c.onPrimary, noteTilt = -2f,
                    button = c.chip, buttonEdge = null, onButton = c.ink, mark = c.planeMark(), pill = true,
                )
                // Paper themes: the page with its dots, rules or fibres; the active tab's colour for Note.
                else -> WidgetLook(
                    look = look, paper = c.bg, texture = t.paper, textureColor = c.paperDot, edge = c.line,
                    shadow = c.ink.copy(alpha = 0.10f), ink = c.ink, muted = c.muted,
                    note = if (look == Look.Market) c.chipOn else c.accent,
                    onNote = if (look == Look.Market) c.onChipOn else c.onAccent,
                    noteTilt = if (look == Look.Market) 0f else -2f,
                    button = c.surface, buttonEdge = c.line, onButton = c.ink, mark = c.planeMark(), pill = false,
                    dotStep = if (look == Look.Market) 9f else PaperGrid.DOT_STEP,
                )
            }
        }
    }
}
