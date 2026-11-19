package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.ai.DraftItem
import com.justdataplease.boringtravel.ai.DraftLayout
import com.justdataplease.boringtravel.ai.DraftUpdate
import com.justdataplease.boringtravel.ai.TripDraftParser
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.LooseQueries
import com.justdataplease.boringtravel.places.NearTrip
import com.justdataplease.boringtravel.places.PlaceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

/** A tool call the model made; [id] when the provider names its calls (OpenRouter, newer Gemini). */
data class ToolCall(val name: String, val args: JsonObject, val id: String? = null)

/**
 * What a call did: the pending changes after it, what the model is told, whether the turn is over, and what the
 * screen does next when the turn ended with something other than a reply ([action]).
 */
data class ToolOutcome(val pending: PendingPlan, val result: JsonObject, val finished: Boolean = false, val action: PlannerAction? = null)

/** A turn that ends by handing over to the traveller: the trip questions, or Copy from a link. */
sealed interface PlannerAction {
    /** [PlannerTools.ASK_TRAVELLER]: show the trip questions with [brief] filled in. */
    data class Ask(val brief: TravelBrief) : PlannerAction

    /** [PlannerTools.COPY_NOTES]: offer Copy from a link with [address]. */
    data class CopyNotes(val address: String) : PlannerAction
}

/** The map search the planner may use ([PlannerTools.FIND_PLACES]): the app's own place search. */
fun interface PlaceLookup {
    suspend fun find(query: String, near: LatLng?): List<PlaceResult>
}

/** The trip as saved when the request started (null = a new trip) and its live items. */
data class PlanContext(val trip: Trip?, val items: List<PlanItem>, val today: LocalDate) {
    val live: List<PlanItem> = items.filter { it.deletedAt == null && (trip == null || it.tripId == trip.id) }
    val byId: Map<String, PlanItem> = live.associateBy { it.id }
}

/**
 * Carries out the planner's tool calls on the pending changes. Every argument is checked as it would be by hand:
 * unknown ids, impossible dates and times, repeats of planned items come back to the model as problems so it can put
 * them right in its next call; nothing is saved here.
 *
 * [links] reads pages for [PlannerTools.READ_LINK]. [briefAnswered]: the traveller has answered the trip questions in
 * this chat, so [PlannerTools.ASK_TRAVELLER] isn't shown again. [travellerWords]: what the traveller wrote; when given
 * (the loop, where a problem can be put right), a stay named as a lodging they never named is refused ([StayNames]).
 */
class ToolRunner(
    private val context: PlanContext,
    private val places: PlaceLookup?,
    private val links: LinkLookup? = null,
    private val briefAnswered: Boolean = false,
    private val travellerWords: String? = null,
    private val found: List<LinkFound> = emptyList(),
) {
    private val nearCache = HashMap<String, LatLng?>()

    /** Where the trip is, for ranking searches nearest it. */
    private val anchors = TripAnchors(context, places)

    /** find_places calls in this request ([MAX_FIND_CALLS] at most). */
    private var findCalls = 0

    /** Every find_places answer of this request, by [Query.key]: a place asked again gets the same answer. */
    private val searched = HashMap<String, JsonObject>()

    /** Pages read in this request ([MAX_LINK_READS] at most). */
    private var linksRead = 0

    /**
     * The traveller's words, their saved plan and the places their links hold (a hotel's Maps link or website, a list):
     * lodgings named there are theirs ([StayNames]).
     */
    private val ownWords: String by lazy {
        buildString {
            append(travellerWords.orEmpty())
            context.live.forEach { append('\n').append(it.title).append(' ').append(it.placeName).append(' ').append(it.note) }
            found.forEach { f ->
                append('\n').append(f.name.orEmpty()).append(' ').append(f.page.orEmpty()).append(' ').append(f.address.orEmpty())
                f.list?.places?.forEach { append('\n').append(it.name) }
            }
        }
    }

    /**
     * Past dates of a new trip already questioned, with the model turn that was told ([round]): sent again in a later
     * turn, they are meant (a trip to write up afterwards).
     */
    private val pastAsked = HashMap<LocalDate, Int>()

    /** The model's turn: every call of one turn was written before any answer came back. */
    private var round = 0

    /** A new model turn starts: dates questioned in earlier turns are meant when they come again. */
    fun nextTurn() { round++ }

    /**
     * A new trip's date before today, in the turn it first comes (every call and item of that turn: the model sent them
     * together): most likely a date without a year read as this year ("Lisbon 3-6 March" in November). Saved trips may
     * hold any date.
     */
    private fun pastProblem(date: LocalDate?): String? {
        if (date == null || context.trip != null || !date.isBefore(context.today)) return null
        if (pastAsked.getOrPut(date) { round } < round) return null
        val next = generateSequence(date) { it.plusYears(1) }.first { !it.isBefore(context.today) }
        return "$date is in the past (today is ${context.today}). A date without a year is its next one: $next. If the traveller meant $date, send it again."
    }

    suspend fun run(call: ToolCall, pending: PendingPlan): ToolOutcome = when (call.name) {
        PlannerTools.FIND_PLACES -> ToolOutcome(pending, findPlaces(call.args, pending))
        PlannerTools.SET_TRIP -> setTrip(call.args, pending)
        PlannerTools.ADD_ITEMS -> addItems(call.args, pending)
        PlannerTools.ADD_MAPS_LIST -> addMapsList(call.args, pending)
        PlannerTools.UPDATE_ITEMS -> updateItems(call.args, pending)
        PlannerTools.REMOVE_ITEMS -> removeItems(call.args, pending)
        PlannerTools.READ_LINK -> ToolOutcome(pending, readLink(call.args))
        PlannerTools.ASK_TRAVELLER -> askTraveller(call.args, pending)
        PlannerTools.COPY_NOTES -> copyNotes(call.args, pending)
        PlannerTools.FINISH -> finish(call.args, pending)
        else -> ToolOutcome(pending, problem("There is no tool called ${call.name}. Use ${PlannerTools.all.joinToString { it.name }}."))
    }

    /** The page's text for the model, or why it couldn't be read; never more than [MAX_LINK_READS] pages a request. */
    private suspend fun readLink(args: JsonObject): JsonObject {
        val raw = args.text("url")
        if (LinkText.isMap(raw)) return MessageLinks.readAnswer(raw, found)
        val reader = links ?: return problem("Reading pages isn't available here. Plan from what the traveller wrote.")
        val url = LinkText.address(raw) ?: return problem("url \"${raw.orEmpty().take(200)}\" isn't a web address.")
        if (linksRead >= MAX_LINK_READS) return problem("That is $MAX_LINK_READS pages already. Plan from what you have.")
        linksRead++
        val page = try {
            withTimeoutOrNull(LINK_TIMEOUT_MS) { reader.read(url) } ?: return problem("The page took too long to answer.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkReadException) {
            return problem(e.message ?: "The page couldn't be read.")
        } catch (_: Exception) {
            return problem("The page couldn't be read.")
        }
        return LinkText.result(page)
    }

    /** Point 11: the trip questions, once a chat; the turn ends and the answers come back as the next message. */
    private fun askTraveller(args: JsonObject, pending: PendingPlan): ToolOutcome {
        if (briefAnswered) {
            return ToolOutcome(pending, problem(
                "The traveller answered the trip questions already (their message starting \"${TravelBrief.HEADER}\"). " +
                    "Plan the trip with those answers now, and decide what they left open.",
            ))
        }
        return ToolOutcome(pending.copy(summary = "", questions = emptyList()), OK, finished = true, action = PlannerAction.Ask(TravelBrief.fromArgs(args)))
    }

    /** Point 12: the traveller's own notes go to Copy from a link, which copies them as written; the turn ends. */
    private fun copyNotes(args: JsonObject, pending: PendingPlan): ToolOutcome {
        val raw = args.text("url")
        val url = LinkText.address(raw)
            ?: return ToolOutcome(pending, problem("url \"${raw.orEmpty().take(200)}\" isn't a blog's or a Google Doc's address. Ask the traveller for it with finish."))
        return ToolOutcome(pending.copy(summary = "", questions = emptyList()), OK, finished = true, action = PlannerAction.CopyNotes(url))
    }

    /** One look-up of find_places: the place, and the town to search around ([key]: the same search). */
    private data class Query(val query: String, val near: String?) {
        val key: String = query.trim().lowercase() + "|" + near?.trim()?.lowercase().orEmpty()
    }

    /**
     * The map search for the model (owner, 15 Nov 2026: "by giving a name it did many iterations, stuck at finding a
     * place"): every query at once, each place searched once a request (asked again, the same answer), [MAX_FIND_CALLS]
     * calls a request, results nearest the trip first ([NearTrip]: the restaurant in Madrid, not the one in Barcelona)
     * with their distance, and plain next steps when nothing is found: add it by name, the app looks it up afterwards.
     */
    private suspend fun findPlaces(args: JsonObject, pending: PendingPlan): JsonObject {
        val lookup = places ?: return problem("Place search isn't available. Give placeName and leave lat and lng out; the app finds them.")
        if (findCalls >= MAX_FIND_CALLS) return problem(ENOUGH_SEARCHING)
        findCalls++
        val queries = args.objects("queries").take(PlannerTools.MAX_QUERIES)
            .mapNotNull { q -> q.text("query")?.take(200)?.let { Query(it, q.text("near")?.take(120)) } }
            .distinctBy { it.key }
        val trip = anchors.of(pending)
        val fresh = queries.filter { it.key !in searched }
        // A slow map search mustn't hold a whole proposal up: what isn't found in time is looked up after the answer.
        val deadline = System.nanoTime() + FIND_PLACES_BUDGET_MS * 1_000_000
        coroutineScope {
            fresh.mapNotNull { it.near?.trim()?.lowercase() }.distinct().filter { it !in nearCache }
                .map { town -> async { town to timed(deadline) { lookup.find(town, null).firstOrNull()?.let { LatLng(it.lat, it.lng) } } } }
                .awaitAll().forEach { (town, point) -> nearCache[town] = point }
            val gate = Semaphore(PARALLEL_SEARCHES)
            fresh.map { q -> async { q.key to gate.withPermit { search(lookup, q, trip, deadline) } } }
                .awaitAll().forEach { (key, answer) -> searched[key] = answer }
        }
        return buildJsonObject {
            putJsonArray("results") {
                queries.forEach { q ->
                    val answer = searched[q.key] ?: return@forEach
                    add(if (q in fresh) answer else JsonObject(answer + ("again" to JsonPrimitive(SEARCHED_ALREADY))))
                }
            }
        }
    }

    /** One query's answer: up to three places nearest the trip (or [Query.near]), with their distance. */
    private suspend fun search(lookup: PlaceLookup, q: Query, trip: List<LatLng>, deadline: Long): JsonObject {
        val near = q.near?.trim()?.lowercase()?.let { nearCache[it] }
        val around = near?.let(::listOf) ?: trip
        val found = timed(deadline) { LooseQueries.search(q.query, around) { lookup.find(it, near ?: trip.lastOrNull()) } }
        return buildJsonObject {
            put("query", q.query)
            when {
                found == null -> put("error", NO_ANSWER)
                found.isEmpty() -> {
                    putJsonArray("found") {}
                    put("next", NOT_FOUND)
                }
                else -> {
                    val best = NearTrip.ranked(found, around).take(3)
                    val distanceKey = if (near != null) "kmFromNear" else "kmFromTrip"
                    putJsonArray("found") {
                        best.forEach { p ->
                            add(buildJsonObject {
                                put("name", p.name)
                                put("address", p.displayName)
                                put("lat", round6(p.lat))
                                put("lng", round6(p.lng))
                                NearTrip.distanceM(p, around)?.let { put(distanceKey, Math.round(it / 100.0) / 10.0) }
                            })
                        }
                    }
                    val nearest = NearTrip.distanceM(best.first(), around)
                    if (nearest != null && nearest > NearTrip.DAY_TRIP_M) put("next", FAR_AWAY)
                }
            }
        }
    }

    /** [block] within what's left until [deadline]; null when time is up or the search failed. */
    private suspend fun <T> timed(deadline: Long, block: suspend () -> T): T? {
        val left = (deadline - System.nanoTime()) / 1_000_000
        if (left <= 0) return null
        return try {
            withTimeoutOrNull(left) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun setTrip(args: JsonObject, pending: PendingPlan): ToolOutcome {
        val problems = ArrayList<String>()
        fun date(key: String): LocalDate? = args.text(key)?.let { raw ->
            TripDraftParser.isoDate(raw)?.let(LocalDate::parse) ?: null.also { problems += "$key \"$raw\" isn't a date. Use yyyy-MM-dd." }
        }
        val start = date("startDate")
        val end = date("endDate")
        listOfNotNull(pastProblem(start), pastProblem(end)).let { if (it.isNotEmpty()) return ToolOutcome(pending, problems(it)) }
        val patch = pending.trip
        // The same plan moved by one date: the other follows at the saved trip's length (as the review lays it out).
        val (s, e) = run {
            var s0 = start ?: patch.startDate
            var e0 = end ?: patch.endDate
            val savedS = context.trip?.startDate
            val savedE = context.trip?.endDate
            if ((args.bool("moveItemsWithDates") ?: patch.moveItemsWithDates) == true && savedS != null && savedE != null && (s0 == null) != (e0 == null)) {
                val length = savedE.toEpochDay() - savedS.toEpochDay()
                if (e0 == null) e0 = s0!!.plusDays(length) else s0 = e0.minusDays(length)
            }
            (s0 ?: savedS) to (e0 ?: savedE)
        }
        if (s != null && e != null && e.isBefore(s)) problems += "endDate is before startDate."
        else if (s != null && e != null && e.toEpochDay() - s.toEpochDay() >= DraftLayout.MAX_TRIP_DAYS) problems += "A trip can be up to ${DraftLayout.MAX_TRIP_DAYS} days."
        // A day's own name, by its date: a day of the trip as it will be.
        val names = LinkedHashMap(patch.dayNames)
        for (o in args.objects("dayNames")) {
            val raw = o.text("date").orEmpty()
            val day = TripDraftParser.isoDate(raw)?.let(LocalDate::parse)
            when {
                day == null -> problems += "dayNames: \"$raw\" isn't a date. Use yyyy-MM-dd."
                s == null || e == null -> problems += "dayNames: the trip has no dates yet. Give startDate and endDate first."
                day.isBefore(s) || day.isAfter(e) -> problems += "dayNames: $day is not a day of the trip ($s to $e)."
                else -> names[day] = DayNames.clean(o.text("name", keepEmpty = true)).orEmpty()
            }
        }
        if (problems.isNotEmpty()) return ToolOutcome(pending, problems(problems))
        val next = patch.copy(
            title = args.text("title")?.let { clip(it, 80) } ?: patch.title,
            startDate = start ?: patch.startDate,
            endDate = end ?: patch.endDate,
            route = args.text("route")?.let { clip(it, 120) } ?: patch.route,
            moveItemsWithDates = args.bool("moveItemsWithDates") ?: patch.moveItemsWithDates,
            dayNames = names,
        )
        return ToolOutcome(pending.copy(trip = next), buildJsonObject {
            put("ok", true)
            if (names.isNotEmpty()) put("dayNames", names.entries.joinToString(", ") { (day, name) -> if (name.isEmpty()) "$day: no name" else "$day: $name" })
            if (s != null && e != null) put("tripDates", "$s to $e")
        })
    }

    private fun addItems(args: JsonObject, pending: PendingPlan): ToolOutcome {
        var plan = pending
        val problems = ArrayList<String>()
        val added = ArrayList<PendingItem>()
        val savedByDay = context.live.groupBy { it.day }.mapValues { (_, rows) -> rows.associateBy { DraftLayout.normaliseTitle(it.title) } }
        for ((index, o) in args.objects("items").withIndex()) {
            val label = o.text("title") ?: "item ${index + 1}"
            val parsed = parseItem(o) { problems += "$label: $it" } ?: continue
            val (item, date, dayIndex) = parsed
            pastProblem(date)?.let { problems += "$label: $it"; continue }
            if (item.kind == PlanKind.Stay.id && travellerWords != null && StayNames.invented(item.title, item.placeName, ownWords)) {
                problems += "$label: plan where to stay, not which hotel or hostel: title it for the area (\"Stay in Alfama\") with placeName " +
                    "that neighbourhood or town. Name a lodging only when the traveller gave it."
                continue
            }
            val key = DraftLayout.normaliseTitle(item.title)
            val saved = savedByDay[date]?.get(key)
            if (saved != null && saved.id !in plan.removed) {
                problems += "$label is already planned${date?.let { " on $it" } ?: ""} as itemId ${saved.id}$REPEAT"
                continue
            }
            // The same day: by date, or by day number in a trip without dates.
            val twin = plan.added.firstOrNull { it.date == date && it.dayIndex == dayIndex && DraftLayout.normaliseTitle(it.item.title) == key }
            if (twin != null) {
                problems += "$label is already pending as ${twin.id}$REPEAT"
                continue
            }
            val p = PendingItem("new-${plan.nextId}", date, dayIndex, item)
            plan = plan.copy(added = plan.added + p, nextId = plan.nextId + 1)
            added += p
        }
        return ToolOutcome(plan, buildJsonObject {
            putJsonArray("added") {
                added.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id)
                        put("title", p.item.title)
                        put("date", p.date?.toString() ?: p.dayIndex?.let { "day $it" } ?: "idea")
                        if (p.item.lat == null && !p.item.placeName.isNullOrBlank()) put("map", "no coordinates yet: the app looks up placeName")
                    })
                }
            }
            if (problems.isNotEmpty()) putJsonArray("problems") { problems.forEach { add(JsonPrimitive(it)) } }
        })
    }

    /**
     * Every place of a Google Maps list the app read from the traveller's message ([MessageLinks]), through the same
     * checks as [addItems]: one call, whatever the list's size. A place the plan has already is left out and counted;
     * a new trip without a name takes the list's.
     */
    private fun addMapsList(args: JsonObject, pending: PendingPlan): ToolOutcome {
        val found = MessageLinks.listFor(args.text("url"), this.found)
        val list = found?.list ?: return ToolOutcome(pending, problem(
            if (this.found.any { it.listUnread }) "The app couldn't read that Google Maps list. Ask the traveller to open the list in Google Maps, choose Share, and send that link."
            else "The traveller's message holds no Google Maps list the app read. Add places with ${PlannerTools.ADD_ITEMS}.",
        ))
        val date = args.text("date")?.let { raw ->
            TripDraftParser.isoDate(raw) ?: return ToolOutcome(pending, problem("date \"$raw\" isn't yyyy-MM-dd."))
        }
        val places = MessageLinks.listPlaces(list, date)
        val outcome = addItems(MessageLinks.addCall(places).args, pending)
        val problems = (outcome.result["problems"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val added = outcome.pending.added.drop(pending.added.size)
        var plan = outcome.pending
        if (context.trip == null && plan.trip.title == null && list.name.isNotBlank()) plan = plan.copy(trip = plan.trip.copy(title = clip(list.name, 80)))
        return ToolOutcome(plan, buildJsonObject {
            put("list", list.name.ifBlank { "Google Maps list" })
            put("added", added.size)
            put("as", date ?: "ideas")
            // Each place's pending id, so the next call can give them days ("plan these over three days").
            putJsonArray("items") {
                added.forEach { p -> add(buildJsonObject { put("id", p.id); put("title", p.item.title) }) }
            }
            val repeats = problems.count { isRepeat(it) }
            if (repeats > 0) put("alreadyPlanned", repeats)
            if (list.places.size > places.size) put("notAdded", "${list.places.size - places.size} more places: the app's Copy from a link takes a whole list.")
            problems.filterNot { isRepeat(it) }.takeIf { it.isNotEmpty() }?.let { rest -> putJsonArray("problems") { rest.forEach { add(JsonPrimitive(it)) } } }
        })
    }

    private fun updateItems(args: JsonObject, pending: PendingPlan): ToolOutcome {
        var plan = pending
        val problems = ArrayList<String>()
        val done = ArrayList<String>()
        for (o in args.objects("updates")) {
            val id = o.text("itemId").orEmpty()
            val pendingItem = plan.added.firstOrNull { it.id == id }
            when {
                pendingItem != null -> {
                    val edited = editPending(pendingItem, o) { problems += "$id: $it" } ?: continue
                    plan = plan.copy(added = plan.added.map { if (it.id == id) edited else it })
                    done += id
                }
                id in context.byId -> {
                    val patch = patchOf(id, o) { problems += "$id: $it" } ?: continue
                    plan = plan.withUpdate(patch).copy(removed = plan.removed - id)
                    done += id
                }
                else -> problems += "No item has id \"$id\". Use the itemIds from the plan or the pending ids."
            }
        }
        return ToolOutcome(plan, result("updated", done, problems))
    }

    private fun removeItems(args: JsonObject, pending: PendingPlan): ToolOutcome {
        var plan = pending
        val problems = ArrayList<String>()
        val done = ArrayList<String>()
        for (id in args.strings("itemIds")) {
            when {
                plan.added.any { it.id == id } -> { plan = plan.copy(added = plan.added.filter { it.id != id }); done += id }
                id in context.byId -> { plan = plan.copy(removed = plan.removed + id, updates = plan.updates - id); done += id }
                else -> problems += "No item has id \"$id\"."
            }
        }
        return ToolOutcome(plan, result("removed", done, problems))
    }

    private fun finish(args: JsonObject, pending: PendingPlan): ToolOutcome {
        val questions = args.strings("questions").map { clip(it, 300) }.filter { it.isNotBlank() }.distinct().take(3)
        val summary = clip(args.text("summary").orEmpty(), 600)
        return ToolOutcome(pending.copy(summary = summary, questions = questions), buildJsonObject { put("ok", true) }, finished = true)
    }

    /**
     * Booking details in the traveller's [message] that no item holds yet: flight numbers ("IB 3151", "A3 612") and
     * booking codes ("Booking Code: JMKXT", "confirmation 88231"). The loop asks the model once to put them in.
     */
    fun missingDetails(message: String, pending: PendingPlan): List<String> {
        val found = LinkedHashSet<String>()
        FLIGHT.findAll(message).forEach { found += it.groupValues[1] + " " + it.groupValues[2] }
        BOOKING.findAll(message).forEach { found += it.groupValues[1] }
        if (found.isEmpty()) return emptyList()
        val held = buildString {
            context.live.forEach { append(it.title).append(' ').append(it.note).append(' ') }
            pending.added.forEach { append(it.item.title).append(' ').append(it.item.note).append(' ') }
            pending.updates.values.forEach { append(it.title.orEmpty()).append(' ').append(it.note.orEmpty()).append(' ').append(it.noteAppend.orEmpty()).append(' ') }
        }.uppercase().replace(" ", "").replace("\n", "")
        return found.filter { it.uppercase().replace(" ", "") !in held }
    }

    // ---- arguments ----

    private data class Parsed(val item: DraftItem, val date: LocalDate?, val dayIndex: Int?)

    private fun parseItem(o: JsonObject, problem: (String) -> Unit): Parsed? {
        val placeName = o.text("placeName")?.let { clip(it, 160) }
        val title = o.text("title")?.let { clip(it, 120) } ?: placeName ?: return null.also { problem("title is missing.") }
        val date = o.text("date")?.let { raw -> TripDraftParser.isoDate(raw)?.let(LocalDate::parse) ?: return null.also { problem("date \"$raw\" isn't yyyy-MM-dd.") } }
        val dayIndex = if (date == null) o.int("dayIndex")?.takeIf { it in 1..400 } else null
        val note = o.text("note")?.let { clip(it, 500) }.orEmpty()
        // No time but a note that starts with one ("19:00-21:00 · free entry"): that is the start time.
        val time = o.text("time")?.let { raw -> TripDraftParser.normaliseTime(raw) ?: return null.also { problem("time \"$raw\" isn't HH:mm.") } }
            ?: NOTE_TIME.find(note)?.groupValues?.get(1)?.let(TripDraftParser::normaliseTime)
        val endDate = o.text("endDate")?.let { raw -> TripDraftParser.isoDate(raw) ?: return null.also { problem("endDate \"$raw\" isn't yyyy-MM-dd.") } }
        val endTime = o.text("endTime")?.let { raw -> TripDraftParser.normaliseTime(raw) ?: return null.also { problem("endTime \"$raw\" isn't HH:mm.") } }
        if (endDate != null && date != null && endDate < date.toString()) return null.also { problem("endDate $endDate is before date $date.") }
        val (lat, lng) = coordinates(o, problem) ?: return null
        return Parsed(
            DraftItem(
                time = time, endDate = endDate, endTime = endTime, kind = TripDraftParser.kindOf(o.text("kind")), title = title,
                note = note, placeName = placeName, lat = lat, lng = lng,
                travel = TripDraftParser.travelOf(o.text("travel")), url = TripDraftParser.webUrl(o.text("url")),
            ),
            date, dayIndex,
        )
    }

    /** Both coordinates or neither, on the globe; (null, null) when none were sent, null when they are wrong. */
    private fun coordinates(o: JsonObject, problem: (String) -> Unit): Pair<Double?, Double?>? {
        val lat = o.number("lat")
        val lng = o.number("lng")
        if (lat == null && lng == null) return null to null
        if (lat == null || lng == null) return null.also { problem("give both lat and lng, or neither.") }
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) return null.also { problem("lat/lng $lat, $lng isn't a place.") }
        return lat to lng
    }

    private fun editPending(p: PendingItem, o: JsonObject, problem: (String) -> Unit): PendingItem? {
        var date = p.date
        var dayIndex = p.dayIndex
        o.text("date", keepEmpty = true)?.let { raw ->
            if (raw.isBlank()) { date = null; dayIndex = null }
            else date = TripDraftParser.isoDate(raw)?.let(LocalDate::parse) ?: return null.also { problem("date \"$raw\" isn't yyyy-MM-dd.") }
        }
        var time = p.item.time
        o.text("time", keepEmpty = true)?.let { raw ->
            time = if (raw.isBlank()) null else TripDraftParser.normaliseTime(raw) ?: return null.also { problem("time \"$raw\" isn't HH:mm.") }
        }
        var endDate = p.item.endDate
        var endTime = p.item.endTime
        o.text("endDate", keepEmpty = true)?.let { raw ->
            if (raw.isBlank()) { endDate = null; endTime = null }
            else endDate = TripDraftParser.isoDate(raw) ?: return null.also { problem("endDate \"$raw\" isn't yyyy-MM-dd.") }
        }
        o.text("endTime", keepEmpty = true)?.let { raw ->
            endTime = if (raw.isBlank()) null else TripDraftParser.normaliseTime(raw) ?: return null.also { problem("endTime \"$raw\" isn't HH:mm.") }
        }
        val coords = coordinates(o, problem) ?: return null
        val place = o.text("placeName")?.let { clip(it, 160) }
        val note = o.text("note", keepEmpty = true)?.let { clip(it, 500) } ?: p.item.note
        val append = o.text("noteAppend")?.trim()
        val item = p.item.copy(
            time = time,
            endDate = endDate,
            endTime = endTime,
            kind = o.text("kind")?.let(TripDraftParser::kindOf) ?: p.item.kind,
            title = o.text("title")?.let { clip(it, 120) } ?: p.item.title,
            note = if (append.isNullOrBlank() || note.contains(append)) note else listOf(note, append).filter { it.isNotBlank() }.joinToString(" · "),
            placeName = place ?: p.item.placeName,
            // A new place without coordinates drops the old pin: the app looks the new one up.
            lat = coords.first ?: if (place != null && place != p.item.placeName) null else p.item.lat,
            lng = coords.second ?: if (place != null && place != p.item.placeName) null else p.item.lng,
            travel = o.text("travel")?.let(TripDraftParser::travelOf) ?: p.item.travel,
            url = when (val raw = o.text("url", keepEmpty = true)) {
                // No word about the link: it stays, unless the item became another place (new name and new place).
                null -> p.item.url.takeUnless { place != null && place != p.item.placeName && o.text("title") != null }
                // An empty string takes the link away, as it does for a saved item.
                "" -> null
                else -> TripDraftParser.webUrl(raw) ?: p.item.url
            },
        )
        return p.copy(date = date, dayIndex = dayIndex, item = item)
    }

    private fun patchOf(id: String, o: JsonObject, problem: (String) -> Unit): DraftUpdate? {
        val day = o.text("date", keepEmpty = true)?.let { raw ->
            if (raw.isBlank()) "" else TripDraftParser.isoDate(raw) ?: return null.also { problem("date \"$raw\" isn't yyyy-MM-dd.") }
        }
        val time = o.text("time", keepEmpty = true)?.let { raw ->
            if (raw.isBlank()) "" else TripDraftParser.normaliseTime(raw) ?: return null.also { problem("time \"$raw\" isn't HH:mm.") }
        }
        val endDate = o.text("endDate", keepEmpty = true)?.let { raw ->
            if (raw.isBlank()) "" else TripDraftParser.isoDate(raw) ?: return null.also { problem("endDate \"$raw\" isn't yyyy-MM-dd.") }
        }
        val endTime = o.text("endTime", keepEmpty = true)?.let { raw ->
            if (raw.isBlank()) "" else TripDraftParser.normaliseTime(raw) ?: return null.also { problem("endTime \"$raw\" isn't HH:mm.") }
        }
        val (lat, lng) = coordinates(o, problem) ?: return null
        var note = o.text("note", keepEmpty = true)?.let { clip(it, 2000) }
        var append = o.text("noteAppend")?.let { clip(it, 1000) }
        if (note != null && append != null) { note = listOf(note, append).filter { it.isNotBlank() }.joinToString("\n"); append = null }
        return DraftUpdate(
            itemId = id, day = day, time = time, endDate = endDate, endTime = endTime,
            title = o.text("title")?.let { clip(it, 120) }, note = note, noteAppend = append,
            placeName = o.text("placeName")?.let { clip(it, 160) }, lat = lat, lng = lng,
            kind = o.text("kind")?.let(TripDraftParser::kindOf), travel = TripDraftParser.travelOf(o.text("travel")),
            url = o.text("url", keepEmpty = true)?.let { if (it.isBlank()) "" else TripDraftParser.webUrl(it) },
        )
    }

    // ---- small helpers ----

    private fun result(key: String, ids: List<String>, problems: List<String>) = buildJsonObject {
        putJsonArray(key) { ids.forEach { add(JsonPrimitive(it)) } }
        if (problems.isNotEmpty()) putJsonArray("problems") { problems.forEach { add(JsonPrimitive(it)) } }
    }

    private fun problem(text: String) = problems(listOf(text))
    private fun problems(list: List<String>) = buildJsonObject { putJsonArray("problems") { list.forEach { add(JsonPrimitive(it)) } } }

    private fun clip(s: String, max: Int): String {
        val t = s.trim().replace(Regex("[ \\t]+"), " ")
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    companion object {
        /** Pages [PlannerTools.READ_LINK] may read in one request: a front page, a post, one more. */
        const val MAX_LINK_READS = 3

        /** One page, a front page's feed included. */
        const val LINK_TIMEOUT_MS = 45_000L

        /** One find_places call: twelve look-ups at once usually take a few seconds. */
        const val FIND_PLACES_BUDGET_MS = 40_000L

        /** find_places calls a request: look up, then look up what the first answer raised; then plan. */
        const val MAX_FIND_CALLS = 2

        /** Map searches running at once (Photon is a shared public server). */
        const val PARALLEL_SEARCHES = 4

        internal const val NOT_FOUND = "Nothing found. Add it with placeName (name, street, town, country) and no lat or lng: " +
            "the app looks it up again after your answer. Don't search for it again."
        internal const val NO_ANSWER = "The search didn't answer. Add it with placeName and no lat or lng: the app looks it up again after your answer."
        internal const val FAR_AWAY = "None of these is near the trip. If none is the place meant, add it with placeName (name, town, country) and no lat or lng."
        internal const val SEARCHED_ALREADY = "Searched already: the same answer. Use it, or add the item with placeName and no lat or lng."
        internal const val ENOUGH_SEARCHING = "Places were looked up twice already in this request. Add the items now: lat and lng where a search " +
            "found the place, placeName alone for the rest (the app looks those up, nearest the trip). Then call finish."

        private val OK = buildJsonObject { put("ok", true) }

        /** The end of a repeat's problem: the item is planned already, so leaving it out loses nothing. */
        private const val REPEAT = ": use update_items to change it."

        /** A problem that only says an item was a repeat ([REPEAT]); every other problem lost a change. */
        fun isRepeat(problem: String): Boolean = problem.endsWith(REPEAT)

        /** A start time at the front of a note: "19:00-21:00", "18:00 · free entry". */
        private val NOTE_TIME = Regex("""^\s*((?:[01]?\d|2[0-3])[:.][0-5]\d)(?![0-9])""")

        /** A flight number: airline code (two letters, or a letter and a digit) and 1–4 digits: "IB 3151", "A3 612". */
        private val FLIGHT = Regex("""(?<![A-Za-z0-9])([A-Z]{2}|[A-Z][0-9]|[0-9][A-Z])\s?([0-9]{1,4})(?![0-9A-Za-z])""")

        /** A code after "booking", "confirmation", "reference", "reservation", "PNR": "Booking Code: JMKXT". */
        private val BOOKING = Regex(
            """(?i:booking|confirmation|reference|reservation|pnr|locator)(?i:\s*(?:code|number|no\.?|nr\.?|ref\.?))?\s*[:#]?\s*([A-Z0-9]{5,10})(?![A-Za-z0-9])""",
        )

        internal fun JsonObject.text(key: String, keepEmpty: Boolean = false): String? {
            val p = this[key] as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            val s = p.contentOrNull?.trim() ?: return null
            return if (s.isEmpty() && !keepEmpty) null else s
        }

        internal fun JsonObject.number(key: String): Double? =
            (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

        internal fun JsonObject.int(key: String): Int? = number(key)?.toInt()

        internal fun JsonObject.bool(key: String): Boolean? = when ((this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

        internal fun JsonObject.array(key: String): List<JsonElement> = when (val v = this[key]) {
            is JsonArray -> v
            is JsonObject -> listOf(v)
            else -> emptyList()
        }

        internal fun JsonObject.objects(key: String): List<JsonObject> = array(key).filterIsInstance<JsonObject>()

        internal fun JsonObject.strings(key: String): List<String> =
            array(key).mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }
}

/** Search results put in order for the planner: nearest [near] first ([NearTrip]). */
internal object Places {
    fun ranked(found: List<PlaceResult>, near: LatLng?): List<PlaceResult> = NearTrip.ranked(found, listOfNotNull(near))
}
