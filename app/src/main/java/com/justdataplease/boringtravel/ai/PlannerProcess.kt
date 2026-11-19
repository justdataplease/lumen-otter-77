package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ai.tools.LinkLookup
import com.justdataplease.boringtravel.ai.tools.LinkFound
import com.justdataplease.boringtravel.ai.tools.MapsListLookup
import com.justdataplease.boringtravel.ai.tools.MapsLookup
import com.justdataplease.boringtravel.ai.tools.MessageLinks
import com.justdataplease.boringtravel.ai.tools.PageLookup
import com.justdataplease.boringtravel.ai.tools.PendingPlaces
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import com.justdataplease.boringtravel.ai.tools.PlaceLookup
import com.justdataplease.boringtravel.ai.tools.PlanContext
import com.justdataplease.boringtravel.ai.tools.PlannerAction
import com.justdataplease.boringtravel.ai.tools.PlannerTools
import com.justdataplease.boringtravel.ai.tools.ToolCall
import com.justdataplease.boringtravel.ai.tools.TripAnchors
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * The AI planner's process: the same six steps whatever model answers (owner, 2026-11-11: "whatever model we have,
 * it initiates a process and just has some inputs for this process"). The model only supplies inputs, as the
 * arguments of the planner's tools ([com.justdataplease.boringtravel.ai.tools.PlannerTools]); the app does the rest.
 *
 * 1. **Context.** The app writes the trip, its saved plan with ids and the pending changes ([PlannerPrompt.context]),
 *    and reads the message's links ([MessageLinks]: Google Maps links, a shared Google Maps list, a place's website):
 *    a message of nothing but such places is added by the app without a model; otherwise each link's place, list or
 *    page title goes under the message.
 * 2. **Inputs.** The model calls the tools: find_places, read_link, set_trip, add_items, add_maps_list, update_items, remove_items,
 *    and ends with finish, or hands over with ask_traveller (the trip questions) or copy_notes_from_link. A
 *    provider with tool calls runs them in a loop ([com.justdataplease.boringtravel.ai.tools.ToolLoop]); one without
 *    sends every tool's arguments in one answer ([PlannerRuns.oneShot]). See docs/AI-PLANNER.md.
 * 3. **Checks.** Every input is checked as a hand edit would be ([com.justdataplease.boringtravel.ai.tools.ToolRunner]):
 *    unknown ids, impossible dates and times, repeats. In the loop, problems go back to the model to put right.
 * 4. **Places.** Items with a place name but no pin get one from the app's map search ([PendingPlaces]).
 * 5. **Review.** The pending changes laid out against the trip ([DraftLayout]): the traveller ticks what to keep.
 * 6. **Save.** What was kept goes into the trip ([save], [DraftApplier]).
 *
 * Steps 1–5 are [run]; nothing is saved until [save]. [links] reads the pages read_link asks for.
 */
class PlannerProcess(
    private val places: PlaceLookup?,
    private val links: LinkLookup? = null,
    private val maps: MapsLookup? = null,
    private val pages: PageLookup? = null,
    private val lists: MapsListLookup? = null,
) {
    /**
     * What one message gave: the pending changes, the reply, the review, the inputs the model sent, and the hand-over
     * when the turn ended with the trip questions or Copy from a link ([action]). [byApp]: no model was asked (a
     * message of links to places only), so it doesn't count against the allowance.
     */
    data class Outcome(
        val pending: PendingPlan,
        val reply: String,
        val draft: TripDraft,
        val layout: DraftLayout,
        val calls: List<ToolCall>,
        val raw: String,
        val model: String,
        val action: PlannerAction? = null,
        val byApp: Boolean = false,
    ) {
        /** Something to review and save: changes, and no question left open. */
        val ready: Boolean get() = pending.hasChanges && layout.questions.isEmpty()

        /** The reply with the open questions numbered under it. */
        val replyWithQuestions: String get() {
            val questions = layout.questions.takeIf { it.isNotEmpty() }?.mapIndexed { n, q -> "${n + 1}. $q" }?.joinToString("\n")
            return listOfNotNull(reply.ifBlank { null }, questions).joinToString("\n\n")
        }
    }

    /** Steps 1–5 for the traveller's [message]; [onStep] gets short lines for the screen ("Finding 6 places"). */
    suspend fun run(
        engine: PlannerEngine,
        trip: Trip?,
        items: List<PlanItem>,
        pending: PendingPlan,
        history: List<ChatTurn>,
        message: String,
        today: LocalDate = LocalDate.now(),
        onStep: (String) -> Unit = {},
    ): Outcome {
        val context = PlanContext(trip, items, today)
        // The last turn's reply and questions belong to that turn: an answer that doesn't finish mustn't show them again.
        val start = pending.copy(summary = "", questions = emptyList())
        // 1: links hold their place (a Maps link, a place's website): read by the app, before (often instead of) the model.
        val found = readLinks(message, context, start, onStep)
        val request = PlanRequest(trip, items, start, history, message, today, places, links, found)
        if (MessageLinks.onlyPlaces(message, found)) return byApp(request, found, trip, items)
        // 2–3: the model's inputs, checked tool call by tool call.
        val result = engine.plan(request, onStep)
        // 4: pins for what still has none.
        val located = places?.let { lookup ->
            if (result.pending.added.any { it.item.lat == null } || result.pending.updates.values.any { it.placeName != null && it.lat == null }) onStep("Finding places on the map")
            PendingPlaces.locate(result.pending, context, lookup, PLACES_TIMEOUT_MS)
        } ?: result.pending
        // 5: the review.
        val draft = located.toDraft()
        return Outcome(located, result.reply, draft, DraftLayout.of(draft, trip, items), result.calls, result.raw, engine.reportName, result.action)
    }

    /** The message's links, each read near the trip; none without a reader. */
    private suspend fun readLinks(message: String, context: PlanContext, pending: PendingPlan, onStep: (String) -> Unit): List<LinkFound> {
        if (maps == null && pages == null && lists == null) return emptyList()
        val urls = MessageLinks.links(message)
        if (urls.isEmpty()) return emptyList()
        onStep(if (urls.size == 1) "Reading the link" else "Reading ${urls.size} links")
        return MessageLinks.read(urls, maps, pages, places, TripAnchors(context, places).of(pending), lists)
    }

    /**
     * Places that came as links alone (owner, 15 Nov 2026: a Google Maps link "should be instant", "or when adding a
     * website page"): the app makes the add_items call the model would have made, with the same checks, and the review
     * shows it. A place the trip has already (its name on any day, as the review counts it, or its link) isn't added
     * and the reply says so. No model is asked. A shared Google Maps list alone adds all its places the same way
     * (owner, 17 Nov 2026), and a new trip without a name takes the list's.
     */
    private suspend fun byApp(request: PlanRequest, found: List<LinkFound>, trip: Trip?, items: List<PlanItem>): Outcome {
        val live = items.filter { it.deletedAt == null && (trip == null || it.tripId == trip.id) }
        val titles = (live.map { it.title } + request.pending.added.map { it.item.title }).mapTo(HashSet(), DraftLayout::normaliseTitle)
        val links = (live.flatMap { listOfNotNull(it.url, it.mapsUrl) } + request.pending.added.mapNotNull { it.item.url }).toSet()
        // A list's places twice in one list (two branches of a café) are each asked for once.
        val all = MessageLinks.places(found).distinctBy { it.url ?: it.title }
        val (already, fresh) = all.partition { p ->
            (p.url != null && p.url in links) || DraftLayout.normaliseTitle(p.title) in titles
        }
        val runner = request.runner(checkStays = false)
        val calls = ArrayList<ToolCall>()
        var after = request.pending
        // A new trip made from one list is named after it.
        val list = found.mapNotNull { it.list }.singleOrNull()?.takeIf { found.size == 1 }
        if (trip == null && after.trip.title == null && list != null && list.name.isNotBlank() && fresh.isNotEmpty()) {
            val name = ToolCall(PlannerTools.SET_TRIP, buildJsonObject { put("title", list.name) })
            calls += name
            after = runner.run(name, after).pending
        }
        val call = MessageLinks.addCall(fresh)
        calls += call
        // The traveller named these places by their links: a hotel's link is their own choice, not an invented one.
        if (fresh.isNotEmpty()) after = runner.run(call, after).pending
        val added = after.added.drop(request.pending.added.size).map { it.item.title }
        val left = found.sumOf { f -> f.list?.let { (it.places.size - MessageLinks.MAX_LIST_ITEMS).coerceAtLeast(0) } ?: 0 }
        val reply = MessageLinks.reply(added, already.map { it.title }, left)
        val pending = after.copy(summary = reply, questions = emptyList())
        val draft = pending.toDraft()
        val raw = PlanResult(pending, reply, calls).raw
        return Outcome(pending, reply, draft, DraftLayout.of(draft, trip, items), calls, raw, APP_MODEL, byApp = true)
    }

    /**
     * Step 6: the ticked changes of [draft] (reviewed against [baseline]) laid out again against the plan as it is
     * now ([existing]), so nothing is doubled, and turned into rows. Throws [StaleDraftException] when the trip changed
     * under the review.
     */
    fun save(
        draft: TripDraft, trip: Trip?, existing: List<PlanItem>, baseline: List<PlanItem>, unticked: Set<String>,
        draftId: String, now: Long, newId: () -> String,
    ): ApplyResult {
        val layout = DraftLayout.of(draft, trip, existing, baseline)
        if (layout.questions.isNotEmpty() || layout.problems.isNotEmpty()) throw StaleDraftException("The trip changed since this review. Ask the planner again before saving.")
        return DraftApplier.apply(layout, trip, existing, unticked, draftId, now, newId)
    }

    companion object {
        /** The longest the map search may take after an answer; what isn't found by then stays without a pin. */
        const val PLACES_TIMEOUT_MS = 30_000L

        /** Who made an answer no model was asked for, for "Report this answer". */
        const val APP_MODEL = "BoringTravel (links to places, no AI)"
    }
}
