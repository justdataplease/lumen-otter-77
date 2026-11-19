package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.ai.SchemaNode
import com.justdataplease.boringtravel.ai.tools.ToolRunner.Companion.bool
import com.justdataplease.boringtravel.ai.tools.ToolRunner.Companion.int
import com.justdataplease.boringtravel.ai.tools.ToolRunner.Companion.strings
import com.justdataplease.boringtravel.ai.tools.ToolRunner.Companion.text
import com.justdataplease.boringtravel.data.db.Trip
import kotlinx.serialization.json.JsonObject

/**
 * The questions a travel agent asks before planning a trip (owner, 2026-11-12: "will ask some fixed questions that
 * travel agents usually ask"). The app defines them, not the model: [PlannerTools.ASK_TRAVELLER] only fills in what the
 * chat already said ([fromArgs]), the traveller answers on one sheet, and [message] is what the planner reads next.
 * Every question may be left open: the planner decides it. Pure and unit tested.
 */
data class TravelBrief(
    val where: String = "",
    /** "Suggest one": the planner picks the destination. */
    val suggestWhere: Boolean = false,
    /** Dates or a month, as typed ("3–7 May", "May"). */
    val whenText: String = "",
    val days: Int? = null,
    val who: Who? = null,
    val people: Int? = null,
    val pace: Pace? = null,
    val interests: Set<Interest> = emptySet(),
    val budget: Budget? = null,
    val gettingAround: GettingAround? = null,
    val startFrom: String = "",
    /** Must-sees and things to avoid, one free line. */
    val wishes: String = "",
) {
    /** A question's answer: [id] for the tool's arguments, [label] for the sheet, [said] for the planner. */
    interface Answer {
        val id: String
        val label: String
        val said: String get() = label.lowercase()
    }

    enum class Who(override val id: String, override val label: String, val fixedPeople: Int? = null) : Answer {
        Solo("solo", "Solo", 1), Couple("couple", "Couple", 2), Family("family", "Family with children"), Friends("friends", "Friends"),
    }

    enum class Pace(override val id: String, override val label: String) : Answer {
        Easy("easy", "Easy"), Balanced("balanced", "Balanced"), Full("full", "Full"),
    }

    enum class Interest(override val id: String, override val label: String) : Answer {
        Sights("sights", "Sights and history"), Museums("museums", "Museums and art"), Food("food", "Food"),
        Nature("nature", "Nature and hikes"), Beaches("beaches", "Beaches"), Nightlife("nightlife", "Nightlife"),
        Shopping("shopping", "Shopping"), Offbeat("offbeat", "Off the usual path"),
    }

    enum class Budget(override val id: String, override val label: String) : Answer {
        Lean("lean", "Lean"), Middle("middle", "Middle"), Generous("generous", "Generous"),
    }

    enum class GettingAround(override val id: String, override val label: String) : Answer {
        Transit("transit", "On foot and public transport"), Car("car", "Rental car"), Mixed("mixed", "Mixed"),
    }

    /** How many people: asked only when [who] doesn't say it (a family, friends). */
    val asksPeople: Boolean get() = who != null && who.fixedPeople == null

    /** The questions left open, by their names in [message]: the planner decides them. */
    val open: List<String> get() = buildList {
        if (where.isBlank() && !suggestWhere) add("where")
        if (whenText.isBlank()) add("when")
        if (days == null) add("how long")
        if (who == null) add("who")
        if (pace == null) add("pace")
        if (interests.isEmpty()) add("interests")
        if (budget == null) add("budget")
        if (gettingAround == null) add("getting around")
    }

    /** The answers as the traveller's next message: [HEADER], one line per answer, what is left open, the request. */
    fun message(): String = buildString {
        appendLine(HEADER)
        when {
            where.isNotBlank() -> appendLine("Where: ${where.trim()}")
            suggestWhere -> appendLine("Where: suggest a place that fits")
        }
        if (whenText.isNotBlank()) appendLine("When: ${whenText.trim()}")
        days?.let { appendLine("How long: ${daysLabel(it).lowercase()}") }
        who?.let { w ->
            val count = w.fixedPeople ?: people
            appendLine("Who: ${w.said}" + (count?.takeIf { w.fixedPeople == null }?.let { ", ${peopleLabel(it)} people" } ?: ""))
        }
        pace?.let { appendLine("Pace: ${it.said}") }
        if (interests.isNotEmpty()) appendLine("Interests: " + Interest.entries.filter { it in interests }.joinToString(", ") { it.said })
        budget?.let { appendLine("Budget: ${it.said}") }
        gettingAround?.let { appendLine("Getting around: ${it.said}") }
        if (startFrom.isNotBlank()) appendLine("Starting from: ${startFrom.trim()}")
        if (wishes.isNotBlank()) appendLine("Must-sees and things to avoid: ${wishes.trim().replace('\n', ' ')}")
        val open = open
        if (open.isNotEmpty()) appendLine("Decide for me: ${open.joinToString(", ")}")
        append("Plan the trip and propose it.")
    }

    companion object {
        /** The first line of [message]: the planner (and [answered]) know the questions were asked and answered. */
        const val HEADER = "Trip questions, answered:"

        /** Choices for how long, in days. */
        val DAY_CHOICES = listOf(2, 3, 4, 5, 7, 10, 14)

        /** Choices for how many people (6 means six or more). */
        val PEOPLE_CHOICES = listOf(2, 3, 4, 5, 6)

        const val MAX_DAYS = 60
        const val MAX_PEOPLE = 30
        private const val MAX_FIELD = 80
        private const val MAX_WISHES = 300

        fun daysLabel(days: Int): String = when (days) {
            7 -> "A week"
            14 -> "2 weeks"
            1 -> "1 day"
            else -> "$days days"
        }

        fun peopleLabel(count: Int): String = if (count >= 6) "6 or more" else count.toString()

        /** True when any of [texts] (the traveller's messages) is a brief: the questions were answered already. */
        fun answered(texts: List<String>): Boolean = texts.any { it.trimStart().startsWith(HEADER) }

        /** What the planner already knew, from [PlannerTools.ASK_TRAVELLER]'s arguments; anything unknown is left out. */
        fun fromArgs(args: JsonObject): TravelBrief {
            fun field(key: String, max: Int = MAX_FIELD) = args.text(key)?.replace(Regex("\\s+"), " ")?.take(max).orEmpty()
            val who = choice<Who>(args.text("who"))
            return TravelBrief(
                where = field("where"),
                suggestWhere = args.bool("suggestWhere") == true && field("where").isBlank(),
                whenText = field("when"),
                days = args.int("days")?.takeIf { it in 1..MAX_DAYS },
                who = who,
                people = (who?.fixedPeople ?: args.int("people"))?.takeIf { it in 1..MAX_PEOPLE }?.coerceAtMost(PEOPLE_CHOICES.last()),
                pace = choice<Pace>(args.text("pace")),
                interests = args.strings("interests").mapNotNull { choice<Interest>(it) }.toSet(),
                budget = choice<Budget>(args.text("budget")),
                gettingAround = choice<GettingAround>(args.text("gettingAround")),
                startFrom = field("startFrom"),
                wishes = field("wishes", MAX_WISHES),
            )
        }

        /** A saved trip's own answers for the starter: where (its route, else its name) and its dates. */
        fun fromTrip(trip: Trip?): TravelBrief {
            if (trip == null) return TravelBrief()
            val s = trip.startDate
            val e = trip.endDate
            return TravelBrief(
                where = trip.subtitle.ifBlank { trip.title }.take(MAX_FIELD),
                whenText = if (s != null && e != null) "$s to $e" else "",
                days = if (s != null && e != null) trip.days.takeIf { it in 1..MAX_DAYS } else null,
            )
        }

        private inline fun <reified T> choice(id: String?): T? where T : Enum<T>, T : Answer =
            enumValues<T>().firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

        private fun ids(values: List<Answer>) = values.map { it.id }

        /** [PlannerTools.ASK_TRAVELLER]'s arguments: every answer optional, the choices the app's own. */
        val parameters: SchemaNode.Obj = SchemaNode.Obj(
            linkedMapOf(
                "where" to SchemaNode.Str("Destination when the traveller named one: a city, region or country."),
                "suggestWhere" to SchemaNode.Bool("True when they want you to suggest the destination."),
                "when" to SchemaNode.Str("Dates or a month as they said it: \"3-7 May\", \"May\", \"spring\"."),
                "days" to SchemaNode.Integer("How many days, when said."),
                "who" to SchemaNode.Choice(ids(Who.entries), "Who travels, when said."),
                "people" to SchemaNode.Integer("How many people, when said."),
                "pace" to SchemaNode.Choice(ids(Pace.entries), "How full the days are, when said."),
                "interests" to SchemaNode.Arr(SchemaNode.Choice(ids(Interest.entries)), Interest.entries.size, "What they like, when said."),
                "budget" to SchemaNode.Choice(ids(Budget.entries), "Budget, when said."),
                "gettingAround" to SchemaNode.Choice(ids(GettingAround.entries), "transit: on foot and public transport; car: a rental car; mixed."),
                "startFrom" to SchemaNode.Str("The town they start from, when said."),
                "wishes" to SchemaNode.Str("Must-sees and things to avoid, when said, short."),
            ),
            optional = listOf("where", "suggestWhere", "when", "days", "who", "people", "pace", "interests", "budget", "gettingAround", "startFrom", "wishes"),
        )
    }
}
