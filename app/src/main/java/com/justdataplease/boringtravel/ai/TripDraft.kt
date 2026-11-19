package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.Vehicle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * What the AI planner answers: the changes to a trip, which the traveller reviews before anything is saved. [summary]
 * is the planner's reply. Days are placed by [DraftDay.date], or by [DraftDay.dayIndex] counted from the trip's first
 * day (1 = the first day). [ideas] have no day yet. A saved trip can also be renamed, moved or resized ([title],
 * [startDate], [endDate], [route]), have items changed ([updates]) and removed ([removeItemIds]).
 * Field names are the JSON names in [DraftSchema]; DraftSchemaTest keeps the two in step.
 */
@Serializable
data class TripDraft(
    /** A new trip's name; for a saved trip only when the traveller renames it. */
    val title: String? = null,
    /** ISO dates, yyyy-MM-dd: a new trip's days, or a saved trip's new ones. */
    val startDate: String? = null,
    val endDate: String? = null,
    /** The trip's route line ("Madrid → Granada → Seville"): its subtitle. */
    val route: String? = null,
    /** With new dates for a saved trip: every dated item moves by as many days as the first day did. */
    val moveItemsWithDates: Boolean? = null,
    /** Days named or renamed ([com.justdataplease.boringtravel.model.DayNames]); an empty name takes the day's name away. */
    val dayNames: List<DraftDayName> = emptyList(),
    /** Essential ambiguities to answer before this draft may be saved. Unknown optional times stay unset. */
    val questions: List<String> = emptyList(),
    val updates: List<DraftUpdate> = emptyList(),
    /** Saved items to remove (exact app-provided ids). Each one is reviewed before anything goes. */
    val removeItemIds: List<String> = emptyList(),
    val days: List<DraftDay> = emptyList(),
    val ideas: List<DraftItem> = emptyList(),
    val summary: String = "",
) {
    val itemCount: Int get() = days.sumOf { it.items.size } + ideas.size
    val changeCount: Int get() = itemCount + updates.size + removeItemIds.size + dayNames.size
}

/** A day's own name: [date] is the day (ISO), [name] what it is called ("Temples and tea"; empty: no name any more). */
@Serializable
data class DraftDayName(val date: String = "", val name: String = "")

/** An explicit patch to an existing app-provided id. Null means keep the saved value. */
@Serializable
data class DraftUpdate(
    val itemId: String = "",
    /** ISO date; an empty string clears the day only when the traveller explicitly asks. */
    val day: String? = null,
    /** HH:mm; an empty string explicitly clears a previously assigned time. */
    val time: String? = null,
    val title: String? = null,
    /** Full replacement only if explicitly requested. Use noteAppend for confirmations and references. */
    val note: String? = null,
    val noteAppend: String? = null,
    val placeName: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    /** One of the [PlanKind] ids. */
    val kind: String? = null,
    /** How the traveller gets here: a [Vehicle] id. */
    val travel: String? = null,
    /** The item's website (http or https), only one the traveller gave. */
    val url: String? = null,
    /** ISO date of the last day; an empty string removes the end. */
    val endDate: String? = null,
    /** HH:mm the item ends; an empty string removes the end time. */
    val endTime: String? = null,
)

@Serializable
data class DraftDay(
    val date: String? = null,
    val dayIndex: Int? = null,
    val items: List<DraftItem> = emptyList(),
)

@Serializable
data class DraftItem(
    /** "HH:mm", 24 h. */
    val time: String? = null,
    /** One of the [PlanKind] ids. */
    val kind: String = PlanKind.Note.id,
    val title: String = "",
    val note: String = "",
    val placeName: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    /** How the traveller gets here from the item before: a [Vehicle] id. */
    val travel: String? = null,
    /** The item's website (http or https), only one the traveller gave. */
    val url: String? = null,
    /** The last day (ISO date) when it ends on a later day: a stay's check-out day. */
    val endDate: String? = null,
    /** "HH:mm" it ends (a stay's check-out time). */
    val endTime: String? = null,
)

/**
 * Reads the model's JSON forgivingly: code fences, missing or wrong-typed fields, numbers as strings, unknown kinds,
 * "7.30 pm" times and out-of-range coordinates all become something safe instead of an error. Throws
 * [DraftParseException] only when there is no JSON object at all.
 */
object TripDraftParser {
    const val MAX_DAYS = 60
    const val MAX_ITEMS_PER_DAY = 30
    const val MAX_IDEAS = 40
    const val MAX_QUESTIONS = 3
    const val MAX_UPDATES = 150
    private const val MAX_TITLE = 120
    private const val MAX_NOTE = 500
    private const val MAX_SUMMARY = 600

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(text: String): TripDraft {
        val root = runCatching { json.parseToJsonElement(extractJson(text)) }.getOrNull() as? JsonObject
            ?: throw DraftParseException("The answer had no plan in it.")
        return draft(root)
    }

    fun parseOrNull(text: String): TripDraft? = runCatching { parse(text) }.getOrNull()

    /** Strips ```json fences and any words around the outermost {...}. */
    internal fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }

    private fun draft(o: JsonObject): TripDraft {
        // Reject over-limit responses as a whole: silently dropping a day or booking is unsafe.
        if (o.array("days").size > MAX_DAYS) throw DraftLimitException("This draft has too many days. Ask the planner for up to $MAX_DAYS days at a time.")
        if (o.array("days").any { (it as? JsonObject)?.array("items")?.size?.let { count -> count > MAX_ITEMS_PER_DAY } == true }) {
            throw DraftLimitException("This draft has too many items in one day. Ask the planner for up to $MAX_ITEMS_PER_DAY items per day.")
        }
        if (o.array("ideas").size > MAX_IDEAS) throw DraftLimitException("This draft has too many ideas. Ask for up to $MAX_IDEAS ideas at a time.")
        if (o.array("updates").size > MAX_UPDATES) throw DraftLimitException("This draft changes too many saved items. Ask for up to $MAX_UPDATES changes at a time.")
        val days = o.array("days").mapNotNull { (it as? JsonObject)?.let(::day) }
            .filter { it.items.isNotEmpty() }
            .take(MAX_DAYS)
        return TripDraft(
            title = o.string("title")?.let { clip(it, MAX_TITLE) }?.ifBlank { null },
            startDate = o.string("startDate")?.let(::isoDate),
            endDate = o.string("endDate")?.let(::isoDate),
            route = o.string("route")?.let { clip(it, MAX_TITLE) }?.ifBlank { null },
            moveItemsWithDates = o.boolean("moveItemsWithDates"),
            dayNames = o.array("dayNames").mapNotNull { d ->
                val day = (d as? JsonObject)?.string("date")?.let(::isoDate) ?: return@mapNotNull null
                DraftDayName(day, DayNames.clean(d.string("name")).orEmpty())
            }.take(MAX_DAYS),
            summary = clip(o.string("summary").orEmpty(), MAX_SUMMARY),
            days = days,
            ideas = o.array("ideas").mapNotNull { (it as? JsonObject)?.let(::item) }.take(MAX_IDEAS),
            questions = o.array("questions").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .map { clip(it, 300) }.filter { it.isNotBlank() }.distinct().take(MAX_QUESTIONS),
            updates = o.array("updates").take(MAX_UPDATES).map { element ->
                val patch = element as? JsonObject
                // Keep malformed targets visible to validation; never quietly turn an unsafe edit into an add.
                DraftUpdate(
                    itemId = patch?.string("itemId").orEmpty(), day = patch?.string("day"), time = patch?.string("time"),
                    endDate = patch?.string("endDate"), endTime = patch?.string("endTime"),
                    title = patch?.string("title"), note = patch?.string("note"), noteAppend = patch?.string("noteAppend"),
                    placeName = patch?.string("placeName"), lat = patch?.number("lat"), lng = patch?.number("lng"),
                    kind = patch?.string("kind")?.takeIf { it.isNotBlank() }?.let(::kindOf),
                    travel = patch?.string("travel")?.let(::travelOf),
                    url = patch?.string("url")?.let { if (it.isBlank()) "" else webUrl(it) },
                )
            },
            removeItemIds = o.array("removeItemIds").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
                .filter { it.isNotEmpty() }.distinct().take(MAX_UPDATES),
        )
    }

    private fun day(o: JsonObject): DraftDay {
        val index = o.number("dayIndex")?.toInt()?.takeIf { it in 1..MAX_DAYS * 6 }
        return DraftDay(
            date = o.string("date")?.let(::isoDate),
            dayIndex = index,
            items = o.array("items").mapNotNull { (it as? JsonObject)?.let(::item) }.take(MAX_ITEMS_PER_DAY),
        )
    }

    private fun item(o: JsonObject): DraftItem? {
        val placeName = o.string("placeName")?.let { clip(it, MAX_TITLE) }?.ifBlank { null }
        val title = clip(o.string("title").orEmpty(), MAX_TITLE).ifBlank { placeName ?: return null }
        val lat = o.number("lat")
        val lng = o.number("lng")
        val coordsOk = lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)
        return DraftItem(
            time = o.string("time")?.let(::normaliseTime),
            endDate = o.string("endDate")?.let(::isoDate),
            endTime = o.string("endTime")?.let(::normaliseTime),
            kind = kindOf(o.string("kind")),
            title = title,
            note = clip(o.string("note").orEmpty(), MAX_NOTE),
            placeName = placeName,
            lat = if (coordsOk) lat else null,
            lng = if (coordsOk) lng else null,
            travel = o.string("travel")?.let(::travelOf),
            url = o.string("url")?.let(::webUrl),
        )
    }

    private val kindWords = mapOf(
        PlanKind.Food.id to listOf("food", "restaurant", "cafe", "café", "meal", "breakfast", "lunch", "dinner", "drink", "bar", "taverna"),
        PlanKind.Stay.id to listOf("stay", "hotel", "accommodation", "lodging", "check-in", "checkin", "hostel", "apartment"),
        PlanKind.Transport.id to listOf("transport", "travel", "flight", "ferry", "train", "bus", "car", "transfer", "drive", "boat"),
        PlanKind.Place.id to listOf("place", "sight", "sightseeing", "museum", "beach", "attraction", "visit", "viewpoint"),
        PlanKind.Activity.id to listOf("activity", "tour", "hike", "walk", "event", "class", "swim", "concert", "show"),
        PlanKind.Note.id to listOf("note"),
    )

    /** A known kind id, a common synonym, or [PlanKind.Note]. */
    fun kindOf(raw: String?): String {
        val k = raw?.trim()?.lowercase() ?: return PlanKind.Note.id
        return kindWords.entries.firstOrNull { (_, words) -> k in words }?.key ?: PlanKind.Note.id
    }

    private val travelWords = mapOf(
        Vehicle.Plane.id to listOf("plane", "flight", "fly", "air", "airplane", "aeroplane"),
        Vehicle.Train.id to listOf("train", "rail", "railway", "metro", "subway", "tram"),
        Vehicle.Ferry.id to listOf("ferry", "boat", "ship", "catamaran"),
        Vehicle.Car.id to listOf("car", "drive", "driving", "taxi", "bus", "coach", "transfer", "uber", "road"),
        Vehicle.Bike.id to listOf("bike", "bicycle", "cycle", "cycling"),
        Vehicle.Walk.id to listOf("walk", "foot", "on foot", "walking", "hike"),
    )

    /** A [Vehicle] id for the way a draft says ("flight", "bus", "on foot"), or null for none or anything else. */
    fun travelOf(raw: String?): String? {
        val k = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return travelWords.entries.firstOrNull { (_, words) -> k in words }?.key
    }

    /** An http(s) link with a host, else null: the model never gets to store anything else as a link. */
    fun webUrl(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 && it.none(Char::isWhitespace) } ?: return null
        val uri = runCatching { java.net.URI(t) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        return t.takeIf { (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank() }
    }

    private val timePattern = Regex("""^\s*(\d{1,2})(?:\s*[:.h]\s*(\d{1,2}))?(?::\d{2})?\s*([ap])?\.?\s*m?\.?\s*$""", RegexOption.IGNORE_CASE)

    /** "7:30", "07.30", "19h40", "7 pm", "12:05am", "24:00" → "HH:mm"; anything else → null. */
    fun normaliseTime(raw: String): String? {
        val m = timePattern.matchEntire(raw) ?: return null
        var h = m.groupValues[1].toInt()
        var min = m.groupValues[2].ifEmpty { "0" }.toInt().coerceAtMost(59)
        when (m.groupValues[3].lowercase()) {
            "a" -> { if (h > 12) return null; if (h == 12) h = 0 }
            "p" -> { if (h > 12) return null; if (h < 12) h += 12 }
        }
        if (h == 24 && min == 0) { h = 23; min = 59 }
        if (h > 23) return null
        return "%02d:%02d".format(h, min)
    }

    /** "2027-06-17" (or a longer ISO date-time) → "2027-06-17"; anything else → null. */
    fun isoDate(raw: String): String? =
        runCatching { LocalDate.parse(raw.trim().take(10)).toString() }.getOrNull()

    private fun clip(s: String, max: Int): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.string(key: String): String? = primitive(key)?.content

    private fun JsonObject.boolean(key: String): Boolean? = when (primitive(key)?.content?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun JsonObject.number(key: String): Double? = primitive(key)?.content?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun JsonObject.array(key: String): List<JsonElement> = when (val v = this[key]) {
        is JsonArray -> v
        is JsonObject -> listOf(v)
        else -> emptyList()
    }
}

class DraftParseException(message: String) : Exception(message)

class DraftLimitException(message: String) : Exception(message)
