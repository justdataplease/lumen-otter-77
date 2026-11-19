package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.links.LinkFill
import com.justdataplease.boringtravel.links.LinkUrls
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.links.MapsList
import com.justdataplease.boringtravel.links.MapsPlace
import com.justdataplease.boringtravel.links.SharedLinks
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.LooseQueries
import com.justdataplease.boringtravel.places.NearTrip
import com.justdataplease.boringtravel.places.PlaceResult
import com.justdataplease.boringtravel.site.GoogleDocs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A link from the traveller's message as the app read it: the place it stands for (name and pin) when known. [maps]:
 * a Google Maps link; else a website, with its title ([page]) and, when the map found the venue, its [kind] and
 * [address]. [list]: the link is a shared Google Maps list of saved places, read with all its places ([name] is the
 * list's); [listUnread]: it is such a list, but it couldn't be read (not shared, or Google didn't answer).
 */
data class LinkFound(
    val url: String,
    val name: String?,
    val lat: Double?,
    val lng: Double?,
    val maps: Boolean = true,
    val page: String? = null,
    val kind: PlanKind? = null,
    val address: String? = null,
    val list: MapsList? = null,
    val listUnread: Boolean = false,
) {
    val located: Boolean get() = lat != null && lng != null

    /** Holds places the app can add by itself: a located place, or a list with places. */
    val addable: Boolean get() = located || list?.places?.isNotEmpty() == true

    /** The item's title: a Maps place's name without the address after it ("Café Tortoni", not its street). */
    val title: String? get() = (if (maps) LinkFill.nameOf(url, name) else name?.trim())?.take(TITLE_CHARS)?.trim()?.takeIf { it.isNotEmpty() }

    private companion object { const val TITLE_CHARS = 60 }
}

/** How the planner reads a Google Maps link: the app's link previews (short links followed), fixtures in tests. */
fun interface MapsLookup {
    suspend fun place(url: String, near: LatLng?): MapsPlace?
}

/**
 * How the planner reads a shared Google Maps list ([com.justdataplease.boringtravel.links.MapsLists]): null when the
 * link isn't a list's; it throws when the list can't be read.
 */
fun interface MapsListLookup {
    suspend fun list(url: String): MapsList?
}

/** One place the app can add from the message's links: a link that is a place, or one place of a list. */
data class LinkPlace(val title: String, val url: String?, val item: JsonObject)

/** A website's own words about itself: its title and site name (og:site_name). */
data class PageTitle(val title: String?, val siteName: String?)

/** How the planner reads a website's title: the app's link previews, fixtures in tests. */
fun interface PageLookup {
    suspend fun page(url: String): PageTitle?
}

/**
 * Links in the traveller's message, read by the app before any model is asked (owner, 15 Nov 2026: a Google Maps link
 * "should be instant", "or when adding a website page"). A Google Maps link holds its place. A website is a place when
 * its own name (site name, or the first part of its title) is a venue of that name on the map near the trip
 * ([venue]). A message of nothing but such places becomes items at once, without the model ([onlyPlaces], [addCall]);
 * otherwise the model gets each link's place, or the page's title, under the message ([context]) and needs no
 * search. Google Docs and Drive files are imports, not places: they are left to the model.
 *
 * A shared Google Maps list (owner, 17 Nov 2026: "import a trip from a Google Maps user list") holds many places:
 * alone in a message, all of them become ideas at once; with other words the model gets the list under the message
 * and adds it with one [PlannerTools.ADD_MAPS_LIST] call ([listCall]), or picks from it.
 */
object MessageLinks {
    const val MAX_LINKS = 8

    /** Every link together; what isn't read by then goes to the model as unread. */
    const val TIMEOUT_MS = 15_000L

    /** Searches for a website's name: its site name, its title's parts, each also without "Restaurante", "Hotel" …. */
    private const val MAX_NAME_SEARCHES = 3

    /** Places one list adds to a chat's pending changes (the review shows each with its tick); Copy from a link takes a whole list. */
    const val MAX_LIST_ITEMS = 200

    /** A list's places written out for the model, under the message and in read_link's answer. */
    const val MAX_LIST_SHOWN = 100

    /** Names said in the app's own reply before "and 24 more". */
    private const val MAX_NAMES_SAID = 5
    private const val TITLE_CHARS = 60

    fun links(message: String): List<String> =
        SharedLinks.links(message).filter { MapsLinks.isMaps(it) || isSite(it) }.distinct().take(MAX_LINKS)

    private fun isSite(url: String): Boolean = LinkText.address(url) != null && GoogleDocs.address(url) == null

    /** Nothing but Google Maps links, no other words: such a message never needs a model. */
    fun onlyMapsLinks(message: String): Boolean {
        val all = SharedLinks.links(message)
        return all.isNotEmpty() && all.all(MapsLinks::isMaps) && SharedLinks.words(message).isBlank()
    }

    /**
     * The message holds only these places: their links, and at most each place's own name beside it (as the Maps app
     * shares "Café Tortoni https://maps.app.goo.gl/…"). Any other word ("dinner", "on the 4th") is for the model, and
     * so is any link that isn't a located place.
     */
    fun onlyPlaces(message: String, found: List<LinkFound>): Boolean {
        if (found.isEmpty() || found.any { !it.addable }) return false
        if (SharedLinks.links(message).distinct().size != found.size) return false
        return SharedLinks.words(message).lines().map { it.trim(' ', '-', '–', '—', '·', ',', ':') }.filter { it.isNotEmpty() }.all { line ->
            found.any { f -> f.title.equals(line, ignoreCase = true) || f.name.equals(line, ignoreCase = true) }
        }
    }

    /** Each link read near the trip ([trip]: its places), all at once, within [TIMEOUT_MS]. */
    suspend fun read(
        links: List<String>, maps: MapsLookup?, pages: PageLookup?, places: PlaceLookup?, trip: List<LatLng>, lists: MapsListLookup? = null,
    ): List<LinkFound> =
        coroutineScope {
            links.map { url ->
                async {
                    val isMaps = MapsLinks.isMaps(url)
                    try {
                        withTimeoutOrNull(TIMEOUT_MS) { if (isMaps) readMaps(url, maps, lists, trip) else readSite(url, pages, places, trip) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } ?: LinkFound(url, null, null, null, maps = isMaps)
                }
            }.awaitAll()
        }

    private suspend fun readMaps(url: String, maps: MapsLookup?, lists: MapsListLookup?, trip: List<LatLng>): LinkFound {
        // A shared list of saved places first: it is every place it holds, never one pin where its map happened to look.
        val list = try {
            lists?.list(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return LinkFound(url, null, null, null, maps = true, listUnread = true)
        }
        if (list != null) return LinkFound(url, list.name.ifBlank { null }, null, null, maps = true, list = list)
        val place = maps?.place(url, trip.lastOrNull())
        return LinkFound(url, place?.name?.trim()?.takeIf { it.isNotEmpty() }, place?.lat, place?.lng, maps = true)
    }

    private suspend fun readSite(url: String, pages: PageLookup?, places: PlaceLookup?, trip: List<LatLng>): LinkFound {
        val page = pages?.page(url) ?: return LinkFound(url, null, null, null, maps = false)
        val title = page.title?.trim()?.takeIf { it.isNotEmpty() } ?: page.siteName?.trim()?.takeIf { it.isNotEmpty() }
        val venue = if (places != null && trip.isNotEmpty()) venue(page, places, trip) else null
        return LinkFound(
            url, venue?.name ?: names(page).firstOrNull(), venue?.lat, venue?.lng, maps = false, page = title,
            kind = venue?.kind, address = venue?.displayName,
        )
    }

    /** The venue a website stands for: a place of its own name on the map within a day trip of the trip, not a town or street. */
    private suspend fun venue(page: PageTitle, places: PlaceLookup, trip: List<LatLng>): PlaceResult? {
        for (name in searches(page)) {
            val found = places.find(name, trip.lastOrNull())
            NearTrip.ranked(found, trip).firstOrNull { p ->
                !p.area && sameName(p.name, name) && (NearTrip.distanceM(p, trip) ?: Double.MAX_VALUE) <= NearTrip.DAY_TRIP_M
            }?.let { return it }
        }
        return null
    }

    /** A website's names for itself: its site name, then the parts of its title ("Casa Lucio | Restaurante en Madrid"). */
    fun names(page: PageTitle): List<String> {
        val parts = page.siteName.orEmpty().split(TITLE_PARTS) + page.title.orEmpty().split(TITLE_PARTS)
        return parts.map { it.trim() }.filter { it.length >= 3 && LooseQueries.normal(it) !in GENERIC }.distinctBy(LooseQueries::normal)
    }

    /** What a website's names are searched as: each, then without the words that only say what it is ("Lhardy"). */
    private fun searches(page: PageTitle): List<String> =
        names(page).flatMap(LooseQueries::names).distinctBy(LooseQueries::normal).take(MAX_NAME_SEARCHES)

    /** The same name ([LooseQueries.sameName]). */
    fun sameName(a: String, b: String): Boolean = LooseQueries.sameName(a, b)

    private val TITLE_PARTS = Regex("\\s+[|–—·•:-]\\s+|\\s*\\|\\s*")
    private val GENERIC = setOf("home", "homepage", "inicio", "accueil", "startseite", "welcome", "official site", "official website", "menu", "contact")

    /** Every place the app can add from [found] by itself, in the message's order: each located link, and a list's places. */
    fun places(found: List<LinkFound>): List<LinkPlace> = found.flatMap { f ->
        when {
            f.list != null -> listPlaces(f.list)
            f.located -> listOf(LinkPlace(itemTitle(f), f.url, buildJsonObject {
                put("kind", (f.kind?.takeIf { it != PlanKind.Note } ?: LinkFill.obviousKind(f.name) ?: PlanKind.Place).id)
                put("title", itemTitle(f))
                (f.address ?: f.name)?.let { put("placeName", it) }
                put("lat", f.lat)
                put("lng", f.lng)
                put("url", f.url)
            }))
            else -> emptyList()
        }
    }

    /**
     * A list's places as items ([MAX_LIST_ITEMS] at most): ideas, or all on [date]; Food or Stay when the name says
     * so, with the pin, the address, the note written beside it in the list and the place's own Maps link.
     */
    fun listPlaces(list: MapsList, date: String? = null): List<LinkPlace> = list.places.take(MAX_LIST_ITEMS).map { p ->
        val title = p.name.take(TITLE_CHARS).trim()
        LinkPlace(title, p.mapsUrl, buildJsonObject {
            put("kind", (LinkFill.obviousKind(p.name) ?: PlanKind.Place).id)
            put("title", title)
            put("placeName", p.address.ifBlank { p.name })
            put("lat", p.lat)
            put("lng", p.lng)
            if (p.note.isNotBlank()) put("note", p.note)
            p.mapsUrl?.let { put("url", it) }
            date?.let { put("date", it) }
        })
    }

    /** The add_items call for [places] (places that came as links alone: ideas without a day, each with its name, pin and link). */
    fun addCall(places: List<LinkPlace>): ToolCall = ToolCall(PlannerTools.ADD_ITEMS, buildJsonObject {
        putJsonArray("items") { places.forEach { add(it.item) } }
    })

    /** The list among [found] that [raw] names: by its link, else the only list there is (a model may rewrite a link). */
    fun listFor(raw: String?, found: List<LinkFound>): LinkFound? {
        val lists = found.filter { it.list != null }
        val url = LinkUrls.normalize(raw)
        return lists.firstOrNull { it.url == url || it.url == raw?.trim() } ?: lists.singleOrNull()
    }

    /** The title an item from [f] gets ([addCall]). */
    fun itemTitle(f: LinkFound): String = f.title ?: if (f.maps) MapsLinks.SITE_NAME else LinkUrls.domain(f.url)

    /**
     * The reply when the app added them itself: what was added and how to give it a day; what the trip had already.
     * [left]: places of a list beyond [MAX_LIST_ITEMS], which Copy from a link takes.
     */
    fun reply(added: List<String>, already: List<String>, left: Int = 0): String {
        val had = when {
            already.isEmpty() -> null
            already.size <= MAX_NAMES_SAID -> "${already.joinToString()} ${if (already.size == 1) "is" else "are"} already in your plan."
            else -> "${already.size} of them are already in your plan."
        }
        val new = when (added.size) {
            0 -> if (had == null) "Nothing added." else null
            1 -> "Added ${added[0]} to your ideas, with its pin. Tell me a day and time to plan it."
            else -> "Added ${added.size} places to your ideas, with their pins: ${said(added)}. Tell me the days to plan them."
        }
        val more = if (left > 0) "The list holds $left more: Copy from a link takes all of it." else null
        return listOfNotNull(new, had, more).joinToString(" ")
    }

    /** "A, B, C, D, E and 24 more". */
    private fun said(names: List<String>): String =
        if (names.size <= MAX_NAMES_SAID) names.joinToString() else names.take(MAX_NAMES_SAID).joinToString() + " and ${names.size - MAX_NAMES_SAID} more"

    /** The lines under the traveller's message: each link and what it holds. */
    fun context(found: List<LinkFound>): String = buildString {
        append("Links in this message, read by the app. A Google Maps link or a place's own website is a place: add it with the placeName, lat and lng given here and the link as url, in one turn. Don't search for it.")
        found.forEach { f ->
            append("\n- ").append(f.url).append(": ")
            when {
                f.list != null -> append(listLines(f.list))
                f.listUnread -> append("a Google Maps list the app couldn't read. Tell the traveller to open the list in Google Maps, choose Share, and send that link; add nothing for it.")
                f.located && f.maps -> append(f.name ?: "a place").append(" (").append(f.lat).append(", ").append(f.lng).append(")")
                f.located -> append("the website of ").append(f.address ?: f.name).append(" (").append(f.lat).append(", ").append(f.lng).append(")")
                f.maps && f.name != null -> append(f.name).append(" (no pin: leave lat and lng out, the app finds it)")
                f.maps -> append("the app couldn't read this Google Maps link. Use it as the url; name the place from the message, or ask which place it is.")
                f.page != null -> append("a web page titled \"").append(f.page.take(160)).append("\". If it is a place's own website, add the place by its name with this link as url (the app pins it); to plan from a blog or guide, ").append(PlannerTools.READ_LINK).append(" it.")
                else -> append("the app couldn't open this page.")
            }
        }
    }

    /** A list for the model: what it is, how to add all of it in one call, and its places with their pins to pick from. */
    private fun listLines(list: MapsList): String = buildString {
        append("a Google Maps list")
        if (list.name.isNotBlank()) append(" \"").append(list.name).append('"')
        append(" with ").append(list.places.size).append(if (list.places.size == 1) " place" else " places")
        append(". To add the list (\"add these places\", \"import this list\"), call ").append(PlannerTools.ADD_MAPS_LIST)
            .append(" with this link: one call, the app adds every place with its pin, note and Maps link. Only to add some of them, use ")
            .append(PlannerTools.ADD_ITEMS).append(" with the name as title and placeName and the lat and lng given here. Its places:")
        list.places.take(MAX_LIST_SHOWN).forEachIndexed { i, p ->
            append("\n  ").append(i + 1).append(". ").append(p.name).append(" (").append(p.lat).append(", ").append(p.lng).append(")")
            if (p.note.isNotBlank()) append(" · note: ").append(p.note.replace('\n', ' ').take(120))
        }
        if (list.places.size > MAX_LIST_SHOWN) append("\n  and ").append(list.places.size - MAX_LIST_SHOWN).append(" more")
    }

    /** What read_link answers for a Maps link: the place it holds, a list's places, or why there is nothing to read. */
    fun readAnswer(raw: String?, found: List<LinkFound>): JsonObject {
        val url = LinkUrls.normalize(raw)
        val f = found.firstOrNull { it.maps && (it.url == url || it.url == raw?.trim()) }
        val list = f?.list
        if (list != null) return buildJsonObject {
            put("mapsList", list.name.ifBlank { "a list of saved places" })
            putJsonArray("places") {
                list.places.take(MAX_LIST_SHOWN).forEach { p ->
                    add(buildJsonObject {
                        put("name", p.name)
                        put("lat", p.lat)
                        put("lng", p.lng)
                        if (p.note.isNotBlank()) put("note", p.note)
                    })
                }
            }
            if (list.places.size > MAX_LIST_SHOWN) put("more", "${list.places.size - MAX_LIST_SHOWN} more places not shown")
            put("next", "A Google Maps list. To add all of it call ${PlannerTools.ADD_MAPS_LIST} with this link (one call). To add some, add_items with these names, lat and lng.")
        }
        return buildJsonObject {
            if (f?.located == true) {
                put("mapsPlace", f.name ?: "a place")
                put("lat", f.lat)
                put("lng", f.lng)
                put("next", "A Google Maps link is a place, not a page. Add it with add_items: this placeName, lat and lng, and the link as url.")
            } else {
                putJsonArray("problems") {
                    add(JsonPrimitive("That is a Google Maps link: a place, not a page. Use it as the item's url, with the place's name from the message."))
                }
            }
        }
    }
}
