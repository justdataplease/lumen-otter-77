package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateOfEpochDay
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One screen on the app's stack: its [place], and [id], under which its remembered state is kept (a NavBackStackEntry's id). */
class StackEntry(val place: Place, val id: Int) {
    /** The key of its remembered state (a scrolled list, a form) and of a planner's chat. */
    val key: String get() = "screen-$id"
}

/**
 * The app's screens as Android's NavController keeps them (ui/nav/AppNav.kt, AppActions.kt): the stack on show, Trips
 * always at the bottom (the start destination), each opening a new entry with its own remembered state; and each home
 * tab's pages while another tab shows: a tab switch keeps the pages over the tab left (Android's popUpTo(start) {
 * saveState }) and brings back the tapped tab's (restoreState), each with where it was scrolled. [first] is the stack to
 * start with, [tabs] the tabs' pages kept from before (a restore).
 */
class AppStack(first: List<Place>, tabs: Map<HomeTab, List<Place>> = emptyMap()) {
    private var next = 0

    /** The screens on show, the first at the bottom. */
    val entries = mutableStateListOf<StackEntry>()

    /** Each home tab's pages over the bottom, kept while another tab shows. */
    val tabPages = mutableStateMapOf<HomeTab, List<StackEntry>>()

    init {
        first.forEach(::push)
        tabs.forEach { (tab, pages) -> if (pages.isNotEmpty()) tabPages[tab] = pages.map(::entry) }
    }

    private fun entry(place: Place) = StackEntry(place, next++)

    val size: Int get() = entries.size
    val last: StackEntry get() = entries.last()
    val places: List<Place> get() = entries.map { it.place }

    fun push(place: Place): StackEntry = entry(place).also { entries += it }

    /** Takes the top off (never the bottom one); what was taken. */
    fun pop(): StackEntry? = if (entries.size > 1) entries.removeAt(entries.lastIndex) else null

    /** Everything but the bottom, taken off; what was taken, bottom first. */
    fun popToBottom(): List<StackEntry> {
        val above = entries.drop(1)
        while (entries.size > 1) entries.removeAt(entries.lastIndex)
        return above
    }

    /** The stack is [place] alone (Welcome done: Trips); what was there. */
    fun reset(place: Place): List<StackEntry> {
        val gone = entries.toList()
        entries.clear()
        push(place)
        return gone
    }

    fun indexOfLast(predicate: (Place) -> Boolean): Int = entries.indexOfLast { predicate(it.place) }
}

/**
 * Where the app was, as text iOS keeps with the app's window (SwiftUI's scene storage) and gives back when it opens the
 * app again after closing it in the background (Android: the saved instance state, which brings the back stack, the
 * trip's tab and day and an open note back after the process was stopped). Gone when the traveller closes the app
 * themselves, as on Android. A note being written comes back as that note ([noteIds]: the stack entry's note, saved
 * when the app left the screen); a photo just taken, dictation and the photo library opening at the start don't run again.
 */
object AppRestore {
    /** What a restore brings back; [tripDayKey] is the plan's day asked for (an epoch day, or PLAN_IDEAS). */
    class Saved(val stack: List<Place>, val tabs: Map<HomeTab, List<Place>>, val tripTab: TripTab?, val tripDayKey: String?) {
        /** The plan's day asked for, when it is a day (not the Ideas). */
        val tripDay: LocalDate? get() = tripDayKey?.toLongOrNull()?.let(::localDateOfEpochDay)
    }

    fun encode(stack: AppStack, tripTab: TripTab, tripDay: LocalDate?, noteIds: Map<Int, String>, join: String? = null): String =
        encodeKey(stack, tripTab, tripDay?.toEpochDay()?.toString(), noteIds, join)

    /**
     * Where the app is, with the plan's day as its key ([tripDay]: an epoch day, or PLAN_IDEAS), a join link waiting
     * ([join]), and what another app shared that waits for its sheet ([place]) or for the planner ([imported]).
     */
    fun encodeKey(
        stack: AppStack, tripTab: TripTab, tripDay: String?, noteIds: Map<Int, String>, join: String? = null,
        place: ShareRoute? = null, imported: com.justdataplease.boringtravel.ai.SharedItinerary? = null,
    ): String = buildJsonObject {
        put("v", 1)
        // A shared trip's join link waiting for its sheet (round 26; Android's KEY_JOIN in its saved state).
        join?.let { put("join", it) }
        // A link shared from another app waiting for Add to plan, or a few words without one (round 27; Android's
        // KEY_PLACE_URL, KEY_PLACE_NAME and KEY_NO_LINK).
        when (place) {
            is ShareRoute.Place -> put("place", buildJsonObject {
                put("url", place.link.url)
                place.link.name?.let { put("name", it) }
            })
            ShareRoute.NoLink -> put("noLink", true)
            else -> Unit
        }
        // An itinerary or pictures waiting for the planner (Android's itineraryText, itineraryUri, itineraryPictures).
        imported?.let { share ->
            put("import", buildJsonObject {
                share.text?.let { put("text", it) }
                share.uri?.let { put("uri", it) }
                if (share.pictures.isNotEmpty()) put("pictures", buildJsonArray { share.pictures.forEach { add(JsonPrimitive(it)) } })
            })
        }
        put("stack", buildJsonArray { stack.entries.forEach { e -> placeJson(e.place, noteIds[e.id])?.let { add(it) } } })
        put("tabs", buildJsonObject {
            stack.tabPages.forEach { (tab, pages) ->
                put(tab.name, buildJsonArray { pages.forEach { e -> placeJson(e.place, noteIds[e.id])?.let { add(it) } } })
            }
        })
        put("tripTab", tripTab.key)
        when {
            tripDay == PLAN_IDEAS -> put("tripDay", PLAN_IDEAS)
            else -> tripDay?.toLongOrNull()?.let { put("tripDay", it) }
        }
    }.toString()

    /**
     * The stack and tabs in [text], or null for nothing usable. Screens whose trip, note or idea is gone are left off the
     * top (Android's screens close themselves then); the bottom is always Trips.
     */
    fun decode(text: String?, book: TripBook): Saved? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            fun places(array: JsonArray?): List<Place> = array.orEmpty().mapNotNull { (it as? JsonObject)?.let(::placeOf) }
            val all = places(o["stack"]?.jsonArray)
            val usable = all.takeWhile { valid(it, book) }
            if (usable.firstOrNull() != Place.Trips) return@runCatching null
            val tabs = o["tabs"]?.jsonObject.orEmpty().mapNotNull { (name, pages) ->
                val tab = HomeTab.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                tab to places(pages as? JsonArray).takeWhile { valid(it, book) }
            }.toMap()
            val day = o["tripDay"] as? JsonPrimitive
            Saved(
                usable, tabs,
                TripTab.of(o["tripTab"]?.jsonPrimitive?.contentOrNull),
                day?.longOrNull?.toString() ?: day?.contentOrNull?.takeIf { it == PLAN_IDEAS },
            )
        }.getOrNull()
    }

    /** The join link [text] kept waiting for its sheet, also before Welcome is done; null when there is none. */
    fun joinOf(text: String?): com.justdataplease.boringtravel.together.ShareLink? =
        read(text) { o -> com.justdataplease.boringtravel.together.ShareLink.find(o["join"]?.jsonPrimitive?.contentOrNull) }

    /** A link shared from another app kept waiting for Add to plan (or "nothing to add"), also before Welcome is done. */
    fun sharedPlaceOf(text: String?): ShareRoute? = read(text) { o ->
        val place = o["place"] as? JsonObject
        val url = (place?.get("url") as? JsonPrimitive)?.contentOrNull
        when {
            url != null -> ShareRoute.Place(SharedLink(url, (place["name"] as? JsonPrimitive)?.contentOrNull))
            (o["noLink"] as? JsonPrimitive)?.contentOrNull == "true" -> ShareRoute.NoLink
            else -> null
        }
    }

    /** An itinerary or pictures shared from another app, kept waiting for the planner, also before Welcome is done. */
    fun sharedImportOf(text: String?): com.justdataplease.boringtravel.ai.SharedItinerary? = read(text) { o ->
        val share = o["import"] as? JsonObject ?: return@read null
        fun s(name: String) = (share[name] as? JsonPrimitive)?.contentOrNull
        val pictures = (share["pictures"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val words = s("text")
        val uri = s("uri")
        if (words == null && uri == null && pictures.isEmpty()) null
        else com.justdataplease.boringtravel.ai.SharedItinerary(words, uri, pictures)
    }

    private fun <T> read(text: String?, of: (JsonObject) -> T?): T? {
        if (text.isNullOrBlank()) return null
        return runCatching { of(kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject) }.getOrNull()
    }

    private fun valid(place: Place, book: TripBook): Boolean {
        fun trip(id: String?) = id == null || book.trip(id) != null
        return when (place) {
            is Place.Trip -> trip(place.tripId)
            is Place.EditTrip -> trip(place.tripId)
            is Place.Note -> book.entry(place.entryId) != null
            is Place.Write -> trip(place.tripId)
            is Place.PlacePage -> trip(place.tripId)
            is Place.Share -> trip(place.tripId)
            is Place.Clip -> trip(place.tripId)
            is Place.Book -> trip(place.tripId)
            is Place.Planner -> trip(place.tripId)
            is Place.CopySite -> trip(place.tripId)
            is Place.Idea -> com.justdataplease.boringtravel.programs.ProgramLibrary.destinations.any { it.id == place.destinationId }
            else -> true
        }
    }

    private fun placeJson(place: Place, noteId: String?): JsonObject? = buildJsonObject {
        when (place) {
            Place.Welcome -> return null
            is Place.Home -> put("p", "home").also { put("tab", place.tab.name) }
            Place.LooseNotes -> put("p", "looseNotes")
            Place.NewTrip -> put("p", "newTrip")
            is Place.NewTripFrom -> put("p", "newTripFrom").also { put("start", place.start.toEpochDay()) }
            Place.Ideas -> put("p", "ideas")
            is Place.Idea -> put("p", "idea").also { put("destination", place.destinationId) }
            is Place.Trip -> put("p", "trip").also { put("trip", place.tripId); place.tab?.let { put("tab", it.key) } }
            is Place.EditTrip -> put("p", "editTrip").also { put("trip", place.tripId) }
            is Place.Note -> put("p", "note").also { place.tripId?.let { put("trip", it) }; put("note", place.entryId) }
            is Place.Write -> put("p", "write").also {
                place.tripId?.let { put("trip", it) }
                (noteId ?: place.entryId)?.let { put("note", it) }
                // A note about a plan item not saved yet starts from the item again.
                place.planItemId?.let { put("item", it) }
            }
            is Place.PlacePage -> put("p", "place").also {
                put("trip", place.tripId)
                place.itemId?.let { put("item", it) }
                place.stopId?.let { put("stop", it) }
            }
            is Place.CopySite -> put("p", "copySite").also { place.tripId?.let { put("trip", it) }; place.address?.let { put("address", it) } }
            Place.PlacesBeen -> put("p", "placesBeen")
            Place.ShareWorld -> put("p", "shareWorld")
            Place.Backup -> put("p", "backup")
            is Place.Share -> put("p", "share").also { put("trip", place.tripId) }
            is Place.Planner -> put("p", "planner").also { place.tripId?.let { put("trip", it) } }
            Place.AiSettings -> put("p", "ai")
            Place.Tracking -> put("p", "tracking")
            is Place.Clip -> put("p", "clip").also { put("trip", place.tripId) }
            is Place.Book -> put("p", "book").also { put("trip", place.tripId) }
            is Place.CommunityPost -> put("p", "communityPost").also { put("post", place.postId) }
            is Place.CommunityProfile -> put("p", "communityProfile").also { put("uid", place.uid) }
            is Place.CommunityFollows -> put("p", "communityFollows").also { put("uid", place.uid); put("followers", place.followers) }
            Place.CommunityProfileEdit -> put("p", "communityProfileEdit")
            is Place.Compose -> put("p", "compose").also {
                put("trip", place.route.tripId)
                put("kind", place.route.kind)
                place.route.clipId?.let { put("clip", it) }
                place.route.mediaIds?.let { put("media", it) }
            }
        }
    }

    private fun placeOf(o: JsonObject): Place? {
        fun s(name: String): String? = (o[name] as? JsonPrimitive)?.contentOrNull
        fun trip(): String? = s("trip")
        return when (s("p")) {
            "home" -> HomeTab.entries.firstOrNull { it.name == s("tab") }?.let { if (it == HomeTab.Trips) Place.Trips else Place.Home(it) }
            "looseNotes" -> Place.LooseNotes
            "newTrip" -> Place.NewTrip
            "newTripFrom" -> (o["start"] as? JsonPrimitive)?.longOrNull?.let { Place.NewTripFrom(localDateOfEpochDay(it)) }
            "ideas" -> Place.Ideas
            "idea" -> s("destination")?.let(Place::Idea)
            "trip" -> trip()?.let { Place.Trip(it, TripTab.of(s("tab"))) }
            "editTrip" -> trip()?.let(Place::EditTrip)
            "note" -> s("note")?.let { Place.Note(trip(), it) }
            "write" -> Place.Write(trip(), s("note"), planItemId = s("item"))
            "place" -> trip()?.let { Place.PlacePage(it, s("item"), s("stop")) }
            "copySite" -> Place.CopySite(trip(), s("address"))
            "placesBeen" -> Place.PlacesBeen
            "shareWorld" -> Place.ShareWorld
            "backup" -> Place.Backup
            "share" -> trip()?.let(Place::Share)
            "planner" -> Place.Planner(trip())
            "ai" -> Place.AiSettings
            "tracking" -> Place.Tracking
            "clip" -> trip()?.let(Place::Clip)
            "book" -> trip()?.let(Place::Book)
            "communityPost" -> s("post")?.let(Place::CommunityPost)
            "communityProfile" -> s("uid")?.let(Place::CommunityProfile)
            "communityFollows" -> s("uid")?.let { Place.CommunityFollows(it, (o["followers"] as? JsonPrimitive)?.contentOrNull != "false") }
            "communityProfileEdit" -> Place.CommunityProfileEdit
            "compose" -> trip()?.let { Place.Compose(CommunityCompose(it, s("kind") ?: "trip", s("clip"), s("media"))) }
            else -> null
        }
    }
}
