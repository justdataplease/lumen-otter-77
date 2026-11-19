package com.justdataplease.boringtravel.ai.tools

import kotlinx.serialization.json.JsonObject

/** A turn of the planner's conversation, as every provider gets it. */
sealed interface LoopTurn {
    data class Traveller(val text: String) : LoopTurn

    /**
     * What the planner said and the tools it called. [raw] is the provider's own message, sent back unchanged (Gemini's
     * thought signatures, OpenRouter's reasoning); null for an earlier reply kept only as text.
     */
    data class Planner(val text: String, val calls: List<ToolCall> = emptyList(), val raw: Any? = null) : LoopTurn

    /** The answers to the planner's calls, in call order. */
    data class Results(val results: List<Pair<ToolCall, JsonObject>>) : LoopTurn
}

/** One model turn: words (often none) and tool calls; [raw] goes back in the next request. */
data class ModelTurn(val text: String, val calls: List<ToolCall>, val raw: Any? = null)

/** A provider that can run the planner's tools: Gemini (Firebase AI Logic or a key) and OpenRouter. */
interface ToolChat {
    /** The next turn for [turns] (oldest first), with [tools] on offer and a tool call required. */
    suspend fun next(system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): ModelTurn
}

/**
 * How a run ended: the pending changes, the reply to show, every call made (for "Report this answer"), and the hand-over
 * when the turn ended with the trip questions or Copy from a link ([action]).
 */
data class LoopResult(val pending: PendingPlan, val reply: String, val calls: List<ToolCall>, val finished: Boolean, val action: PlannerAction? = null)

/**
 * The planner's loop: ask the model, carry out the tools it calls ([ToolRunner]), send the results back, until it
 * calls a tool that ends the turn ([PlannerTool.finishes]: finish, ask_traveller, copy_notes_from_link), answers in
 * words only, or [maxRounds] runs out. Calls in one turn run in order, the one that ends it last (a hand-over before a
 * plain finish). The trip questions end the turn rather than wait inside it: the traveller may take minutes, leave the
 * screen or cancel, and their answers come back as an ordinary message, the same in every provider and in the
 * one-answer mode. [onStep] gets a short line for the screen ("Finding 6 places").
 */
class ToolLoop(private val chat: ToolChat, private val maxRounds: Int = MAX_ROUNDS) {
    suspend fun run(
        system: String,
        start: List<LoopTurn>,
        runner: ToolRunner,
        pending: PendingPlan,
        onStep: (String) -> Unit = {},
        message: String = "",
    ): LoopResult {
        val turns = start.toMutableList()
        var plan = pending
        val made = ArrayList<ToolCall>()
        var checkedNothing = false
        var checkedDetails = false
        var checkedProblems = false
        repeat(maxRounds) {
            val turn = chat.next(system, turns, PlannerTools.all)
            if (turn.calls.isEmpty()) return LoopResult(plan, turn.text.trim(), made, finished = false)
            turns += LoopTurn.Planner(turn.text, turn.calls, turn.raw)
            val ending = turn.calls.filter { PlannerTools.named(it.name)?.finishes == true }
            val ordered = turn.calls.filter { it !in ending } + listOfNotNull(ending.firstOrNull { it.name != PlannerTools.FINISH } ?: ending.firstOrNull())
            val results = ArrayList<Pair<ToolCall, JsonObject>>()
            // A change this turn came back with a problem: the finish beside it would lose that change.
            var lost = false
            for (call in ordered) {
                step(call)?.let(onStep)
                if (call.name == PlannerTools.FINISH && lost && !checkedProblems) {
                    checkedProblems = true
                    made += call
                    results += call to FIX_FIRST
                    continue
                }
                // A finish that changed nothing gets one question back: some models look a place up and stop there.
                val asks = (call.args["questions"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true
                if (call.name == PlannerTools.FINISH && !asks && !checkedNothing && plan == pending && made.none { it.name in CHANGES }) {
                    checkedNothing = true
                    made += call
                    results += call to NOTHING_CHANGED
                    continue
                }
                // Before finishing: every flight number and booking code from the message held by some item.
                if (call.name == PlannerTools.FINISH && !checkedDetails) {
                    val missing = runner.missingDetails(message, plan)
                    if (missing.isNotEmpty()) {
                        checkedDetails = true
                        made += call
                        results += call to missingDetails(missing)
                        continue
                    }
                }
                made += call
                val outcome = runner.run(call, plan)
                plan = outcome.pending
                if (outcome.finished) return LoopResult(plan, plan.summary.ifBlank { turn.text.trim() }, made, finished = true, action = outcome.action)
                if (call.name in CHANGES && lostChange(outcome.result)) lost = true
                results += call to outcome.result
            }
            // A call the order above left out (a second finish) still gets an answer: every call needs one.
            turn.calls.filter { c -> results.none { it.first === c } }.forEach { results += it to JsonObject(emptyMap()) }
            turns += LoopTurn.Results(results)
            runner.nextTurn()
        }
        return LoopResult(plan, plan.summary, made, finished = false)
    }

    private fun step(call: ToolCall): String? {
        fun count(key: String) = (call.args[key] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        return when (call.name) {
            PlannerTools.FIND_PLACES -> count("queries").let { if (it == 1) "Finding a place" else "Finding $it places" }
            PlannerTools.SET_TRIP -> "Setting the trip"
            PlannerTools.ADD_ITEMS -> count("items").let { if (it == 1) "Adding an item" else "Adding $it items" }
            PlannerTools.ADD_MAPS_LIST -> "Adding the list's places"
            PlannerTools.UPDATE_ITEMS -> count("updates").let { if (it == 1) "Changing an item" else "Changing $it items" }
            PlannerTools.REMOVE_ITEMS -> "Removing items"
            PlannerTools.READ_LINK -> LinkText.address((call.args["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
                ?.let { "Reading ${com.justdataplease.boringtravel.links.LinkUrls.domain(it)}" }
            else -> null
        }
    }

    companion object {
        /** Look up, plan, fix what came back as a problem, finish: four is typical, eight leaves room. */
        const val MAX_ROUNDS = 8

        private val CHANGES = setOf(PlannerTools.SET_TRIP, PlannerTools.ADD_ITEMS, PlannerTools.ADD_MAPS_LIST, PlannerTools.UPDATE_ITEMS, PlannerTools.REMOVE_ITEMS)

        /** A call's answer holds a problem that lost a change (a repeat, left out, loses nothing). */
        private fun lostChange(result: JsonObject): Boolean =
            (result["problems"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(ToolRunner::isRepeat) == false }

        /** The answer to a finish sent beside a change that came back with a problem. */
        internal val FIX_FIRST = kotlinx.serialization.json.buildJsonObject {
            put("ok", kotlinx.serialization.json.JsonPrimitive(false))
            put("check", kotlinx.serialization.json.JsonPrimitive(
                "A change in this turn came back with problems and wasn't made. Put it right with the tools, then call finish.",
            ))
        }

        /** The answer to a finish that left booking details out. */
        internal fun missingDetails(missing: List<String>) = kotlinx.serialization.json.buildJsonObject {
            put("ok", kotlinx.serialization.json.JsonPrimitive(false))
            put("check", kotlinx.serialization.json.JsonPrimitive(
                "These booking details from the traveller's message are in no item yet: ${missing.joinToString()}. " +
                    "Add each to the note of the item it belongs to (update_items with noteAppend), then call finish.",
            ))
        }

        /** The answer to a finish that changed nothing. */
        internal val NOTHING_CHANGED = kotlinx.serialization.json.buildJsonObject {
            put("ok", kotlinx.serialization.json.JsonPrimitive(false))
            put("check", kotlinx.serialization.json.JsonPrimitive(
                "Nothing has changed yet. If the traveller asked for a change, make it now with the tools, then call finish. If nothing should change, call finish again.",
            ))
        }
    }
}
