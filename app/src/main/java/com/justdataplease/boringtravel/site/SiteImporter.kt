package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.links.LinkUrls
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.links.MapsList
import com.justdataplease.boringtravel.links.MapsListException
import com.justdataplease.boringtravel.links.MapsListPlace
import com.justdataplease.boringtravel.links.MapsLists
import com.justdataplease.boringtravel.places.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** A picture copied into the app: its photo row (saved with its note) and whether this copy wrote its file ([fresh]). */
data class SavedPicture(val media: Media, val fresh: Boolean)

/** A note an earlier copy made and that is still in the app: its trip (null when it is outside trips) and time. */
data class CopiedNote(val tripId: String?, val occurredAt: Long)

/** What [SiteImporter] needs from the phone (AppSiteStore); tests use an in-memory one. */
interface SiteStore {
    /** Note [entryId] when it exists and isn't deleted, else null (a deleted note, or one deleted with its trip). */
    suspend fun copied(entryId: String): CopiedNote?

    /** Trip [id] when it is still in the app (not deleted), with the times of [notes] in it. */
    suspend fun trip(id: String, notes: List<Long>): ExistingTrip?

    /** A new trip of the traveller's own; returns its id. */
    suspend fun createTrip(title: String, start: LocalDate?, end: LocalDate?): String

    /** Trip [id]'s days grown to cover [start]..[end] (an earlier copy's trip that new notes joined). */
    suspend fun extendTrip(id: String, start: LocalDate?, end: LocalDate?)

    /**
     * The picture at [url] as photo [mediaId]: copied from [local] when it came inside the page, else downloaded.
     * Throws when it can't be kept (too large, not a picture, refused).
     */
    suspend fun savePicture(url: String, mediaId: String, local: File? = null): SavedPicture

    /** Removes the files this copy wrote for a note that was not saved. */
    suspend fun discard(pictures: List<SavedPicture>)

    /** The note and its photos, in one transaction. */
    suspend fun saveNote(note: SiteNotes.Note, media: List<Media>)

    /**
     * [name] on the map (the app's place search): in [area] (its town and country) when the organiser gave one, near
     * [near] when known; null when nothing is found.
     */
    suspend fun findPlace(name: String, area: String?, near: LatLng?): SiteNotes.Place?

    /**
     * [item] into its trip's plan ([SiteNotes.placeItem]), last on its day, unless the trip has an item of that name
     * already or had this one before (deleted since: it stays deleted). True when it was added.
     */
    suspend fun addPlanItem(item: PlanItem): Boolean = false

    /** Forgets the pictures saved while reading a page that held them inside (a Google Doc). */
    suspend fun clearInline() {}
}

/** How the importer reads a site ([SiteReader.read]). */
fun interface SiteReading {
    suspend fun read(address: String, isCopied: suspend (SitePost) -> Boolean, onProgress: (Int) -> Unit, stop: () -> Boolean): SiteRead
}

/**
 * How the importer reads a list of saved places shared from Google Maps
 * ([com.justdataplease.boringtravel.links.LinkPreviews.mapsList]): null when the link isn't a list's.
 */
fun interface MapsListReading {
    suspend fun read(url: String): MapsList?
}

/**
 * A trip on offer: its title, first and last day (null for undated posts), posts in order (a post about several places
 * may be a note per place, [SiteParts]), the place each note is at (by note id) and the other places it tells about
 * ([also]), whether it is ticked, and [joins] when its posts go into a trip an earlier copy made. [pins]: places that
 * come with their own pin and no note (a Google Maps list); each becomes an idea in the trip's plan.
 */
data class ProposedTrip(
    val title: String,
    val start: LocalDate?,
    val end: LocalDate?,
    val posts: List<SitePost>,
    val selected: Boolean = true,
    val places: Map<String, String> = emptyMap(),
    val joins: ExistingTrip? = null,
    val also: Map<String, List<SiteSpot>> = emptyMap(),
    val pins: List<MapsListPlace> = emptyList(),
) {
    val pictures: Int get() = posts.sumOf { it.pictures.size }

    /** The places of its posts and its pins, each once, in order ("Kyoto · Nara · Osaka"). */
    val placeNames: List<String>
        get() = (posts.flatMap { p ->
            val id = SiteNotes.entryId(p)
            listOfNotNull(places[id]) + also[id].orEmpty().map { it.name }
        } + pins.map { it.name }).distinctBy { it.lowercase() }
}

/**
 * A trip the copy made or added to ([joined]: it was there already), with the notes it put in it and the [places] it
 * added to its plan.
 */
data class CopiedTrip(val id: String, val title: String, val notes: Int, val joined: Boolean = false, val places: Int = 0)

/** Where a copy from a link is. */
sealed interface SiteImportState {
    data object Idle : SiteImportState

    /** [found] posts so far; [organising] once the reading is done and the trips are being made. */
    data class Reading(val address: String, val found: Int, val organising: Boolean = false) : SiteImportState

    /**
     * The proposal: [groups] as organised and [single] (every post in one trip named after the site); [oneTrip] shows
     * the single one. [addPlaces]: notes get the organiser's places, looked up on the map ([areas]: the town and
     * country of each note's place, by note id), and each place found becomes an item in its trip's plan. [stopped]: the reading
     * ended early (the site stopped answering, or Stop); [more]: a limit or an early end left posts for a later copy.
     * [whole]: the address was one post's, so only that post was read; the blog's address, for [SiteImporter.readWhole].
     * [aiSilent]: the AI was asked and didn't answer, so the dates decided and no place was found
     * ([SiteImporter.organiseAgain] asks once more).
     * [into]: a trip the traveller has, which everything ticked goes into instead of new trips (owner, 17 Nov 2026:
     * "create new trip or append"). [withNotes] false: no note or picture is copied, only the places go into the plan.
     * [listOnly]: a Google Maps list, which is places and nothing else.
     */
    data class Ready(
        val address: String,
        val site: String,
        val host: String,
        val groups: List<ProposedTrip>,
        val single: ProposedTrip,
        val by: Organisation,
        val alreadyCopied: Int,
        val more: Boolean,
        val reblogs: Int = 0,
        val stopped: Boolean = false,
        val oneTrip: Boolean = false,
        val addPlaces: Boolean = false,
        val inline: Map<String, File> = emptyMap(),
        val areas: Map<String, String> = emptyMap(),
        val whole: String? = null,
        val aiSilent: Boolean = false,
        val into: ExistingTrip? = null,
        val withNotes: Boolean = true,
    ) : SiteImportState {
        val trips: List<ProposedTrip> get() = if (oneTrip) listOf(single) else groups
        val notes: Int get() = trips.sumOf { it.posts.size }
        val pictures: Int get() = trips.sumOf { it.pictures }
        val pins: Int get() = trips.sumOf { it.pins.size }
        val selectedNotes: Int get() = trips.filter { it.selected }.sumOf { it.posts.size }
        val selectedPins: Int get() = trips.filter { it.selected }.sumOf { it.pins.size }

        /** The places the ticked trips name, each once: what a copy of places only can add at most. */
        val selectedPlaces: Int get() = trips.filter { it.selected }.flatMap { it.placeNames }.distinctBy { it.lowercase() }.size
        val hasPlaces: Boolean get() = groups.any { it.places.isNotEmpty() }
        val listOnly: Boolean get() = notes == 0 && pins > 0

        /** Something ticked that the copy would bring in. */
        val canCopy: Boolean get() = selectedPins > 0 || (if (withNotes) selectedNotes > 0 else selectedPlaces > 0)
    }

    /** [places]: only places are being added (a Google Maps list, or a copy without notes); [done] of [total] then count them or the posts looked through. */
    data class Copying(val site: String, val done: Int, val total: Int, val pictures: Int, val places: Boolean = false) : SiteImportState

    /**
     * Finished, or [stopped] by Cancel: [notes] and [pictures] copied, pictures and notes that failed, posts found
     * already copied ([skipped]), the trips made or added to, the [places] added to their plans and the ones those
     * plans had already ([planned]). [placesOnly]: no notes were asked for.
     */
    data class Done(
        val site: String,
        val notes: Int,
        val pictures: Int,
        val failedPictures: Int,
        val failedNotes: Int,
        val skipped: Int,
        val trips: List<CopiedTrip>,
        val stopped: Boolean,
        val places: Int = 0,
        val planned: Int = 0,
        val placesOnly: Boolean = false,
    ) : SiteImportState

    data class Failed(val address: String, val message: String) : SiteImportState
}

/**
 * Copy from a link, for the whole app (AppGraph.siteImport): reads the address ([SiteReading]), puts the posts into
 * trips ([SiteOrganizer], with [ai] when there is one; posts that belong with notes an earlier copy made join that
 * trip), and copies the ticked trips; a place the organiser found for a note is pinned on it and becomes an item in the
 * trip's plan, which then shows that note ([SiteNotes.placeItem]); the other places a note tells about get their items
 * too. Runs in [scope], so leaving the screen doesn't stop it; [state] says where it is,
 * [stopReading] keeps what was read so far and [cancel] stops. Each note is written whole or not at all: its pictures
 * are downloaded first (a picture that fails is left out of the note and counted), then the note and its photos are
 * saved together; a note stopped half way leaves no files behind. A new trip is made just before its first note is
 * saved, so a copy stopped early leaves no empty trips; a trip that new notes joined grows to cover them.
 *
 * A link to a list of saved places shared from Google Maps ([lists]) gives places only: each becomes an idea in the
 * plan, with its pin, the note written beside it and its Maps link. The traveller chooses where everything goes: new
 * trips, or one of their own trips ([setInto]); and for a blog or document, whether the notes come too or only the
 * places ([setWithNotes]).
 */
class SiteImporter(
    private val scope: CoroutineScope,
    private val reader: SiteReading,
    private val store: SiteStore,
    private val ai: () -> SiteAi?,
    private val lists: MapsListReading? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: () -> Locale = Locale::getDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<SiteImportState>(SiteImportState.Idle)
    val state: StateFlow<SiteImportState> = _state.asStateFlow()
    private var job: Job? = null

    /** What the last reading found, kept while its proposal is on show, so the AI can be asked again without reading again. */
    private class LastRead(val address: String, val read: SiteRead, val existing: List<ExistingTrip>, val into: ExistingTrip?)

    @Volatile private var lastRead: LastRead? = null

    @Volatile private var stopRequested = false

    val busy: Boolean get() = _state.value.let { it is SiteImportState.Reading || it is SiteImportState.Copying }

    /**
     * Reads [address] and proposes trips. [into]: the trip the copy was opened from (its id), offered as where
     * everything goes.
     */
    fun read(address: String, into: String? = null) {
        if (busy) return
        val a = address.trim()
        stopRequested = false
        _state.value = SiteImportState.Reading(a, 0)
        job = scope.launch {
            try {
                store.clearInline()
                val target = into?.let { store.trip(it, emptyList()) }
                mapsList(a)?.let { list ->
                    lastRead = null
                    _state.value = listProposal(a, list, target)
                    return@launch
                }
                val copied = ArrayList<CopiedNote>()
                val read = reader.read(a, { p -> store.copied(SiteNotes.entryId(p))?.also { copied += it } != null }, { n ->
                    _state.value = SiteImportState.Reading(a, n)
                }, { stopRequested })
                _state.value = SiteImportState.Reading(a, read.posts.size + read.alreadyCopied, organising = read.posts.isNotEmpty())
                val existing = copied.filter { it.tripId != null }.groupBy { it.tripId!! }
                    .mapNotNull { (id, notes) -> store.trip(id, notes.map { it.occurredAt }) }
                lastRead = LastRead(a, read, existing, target)
                _state.value = proposal(a, read, existing, target)
            } catch (e: CancellationException) {
                _state.value = SiteImportState.Idle
                throw e
            } catch (e: SiteReadException) {
                _state.value = SiteImportState.Failed(a, e.message ?: SiteReader.COULD_NOT_READ)
            } catch (_: Exception) {
                _state.value = SiteImportState.Failed(a, SiteReader.COULD_NOT_READ)
            }
        }
    }

    /**
     * The list behind a Google Maps link; null for any other address. A Maps link that is one place, a list that isn't
     * shared and a list without places are each said plainly.
     */
    private suspend fun mapsList(address: String): MapsList? {
        val url = LinkUrls.normalize(address)?.takeIf(MapsLinks::isMaps) ?: return null
        val list = try {
            lists?.read(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: MapsListException) {
            throw SiteReadException(MapsLists.NOT_READ)
        } catch (_: IOException) {
            throw SiteReadException(MAPS_NO_ANSWER)
        } ?: throw SiteReadException(MapsLists.NOT_A_LIST)
        if (list.places.isEmpty()) throw SiteReadException(EMPTY_LIST)
        return list
    }

    /** A Google Maps list laid out for the review: one trip named after the list, holding its places. */
    private fun listProposal(address: String, list: MapsList, into: ExistingTrip?): SiteImportState.Ready {
        val title = list.name.ifBlank { LIST_TITLE }.take(MAX_TITLE)
        val trip = ProposedTrip(title, null, null, emptyList(), pins = list.places)
        return SiteImportState.Ready(
            address, title, MapsLinks.SITE_NAME, listOf(trip), trip, Organisation.Dates, alreadyCopied = 0, more = list.more,
            addPlaces = true, into = into,
        )
    }

    /** The posts of [read] put into trips ([SiteOrganizer]) and laid out for the review. */
    private suspend fun proposal(address: String, read: SiteRead, existing: List<ExistingTrip>, into: ExistingTrip? = null): SiteImportState.Ready {
        val z = zone()
        val organised = if (read.posts.isEmpty()) Organised(emptyList(), Organisation.Dates)
        else SiteOrganizer.organise(read.posts, read.name, z, ai(), locale(), existing = existing)
        val whole = read.posts.mapTo(HashSet(), SiteNotes::entryId)
        val trips = organised.trips.mapNotNull { resumed(it, whole) }
        val groups = trips.map { proposed(it, z, organised.also) }
        // Every post (with the dates the organiser read) in one trip named after the site.
        val all = trips.flatMap { it.posts }.sortedWith(compareBy(nullsLast<java.time.Instant>()) { it.at })
        val single = proposed(
            TripGroup(SiteOrganizer.siteTitle(read.name), all, trips.flatMap { it.places.entries }.associate { it.key to it.value }), z, organised.also,
        )
        return SiteImportState.Ready(
            address, read.name, read.host, groups, single, organised.by, read.alreadyCopied, read.more, read.reblogs, read.stopped,
            addPlaces = organised.by != Organisation.Dates && groups.any { it.places.isNotEmpty() }, inline = read.inline,
            areas = organised.areas, whole = read.whole, aiSilent = organised.asked && organised.by == Organisation.Dates,
            into = into,
        )
    }

    /**
     * Asks the AI once more about the posts already read, when it didn't answer ([SiteImportState.Ready.aiSilent]):
     * without it there are no places, so no pins and no plan items. The site isn't read again; Cancel, or an AI that
     * still doesn't answer, leaves a proposal on show.
     */
    fun organiseAgain() {
        val ready = _state.value as? SiteImportState.Ready ?: return
        val last = lastRead?.takeIf { it.address == ready.address } ?: return
        _state.value = SiteImportState.Reading(ready.address, last.read.posts.size + last.read.alreadyCopied, organising = true)
        job = scope.launch {
            try {
                // Where it goes is the traveller's choice so far, not the one the copy was opened with.
                _state.value = proposal(last.address, last.read, last.existing, ready.into)
            } catch (e: CancellationException) {
                _state.value = ready
                throw e
            } catch (_: Exception) {
                _state.value = ready
            }
        }
    }

    /**
     * [g] without the notes an earlier copy already made of a post cut into several ([SiteParts]; that copy was stopped
     * before the post's last note, so the post was read again): the rest go on in the trip those notes are in. Null
     * when nothing is left. [whole]: the ids of the posts as read, which the reading has checked already.
     */
    private suspend fun resumed(g: TripGroup, whole: Set<String>): TripGroup? {
        val made = g.posts.mapNotNull { p -> SiteNotes.entryId(p).takeIf { it !in whole }?.let { id -> store.copied(id)?.let { id to it } } }
        if (made.isEmpty()) return g
        val ids = made.mapTo(HashSet()) { it.first }
        val rest = g.posts.filter { SiteNotes.entryId(it) !in ids }
        if (rest.isEmpty()) return null
        val joins = g.joins ?: made.firstNotNullOfOrNull { it.second.tripId }
            ?.let { trip -> store.trip(trip, made.filter { it.second.tripId == trip }.map { it.second.occurredAt }) }
        return g.copy(posts = rest, places = g.places.filterKeys { it !in ids }, joins = joins)
    }

    private fun proposed(g: TripGroup, zone: ZoneId, also: Map<String, List<SiteSpot>>): ProposedTrip {
        val days = SiteOrganizer.dates(g.posts, zone)
        val mine = g.posts.mapNotNull { p -> SiteNotes.entryId(p).let { id -> also[id]?.let { id to it } } }.toMap()
        return ProposedTrip(g.title, days?.first, days?.second, g.posts, places = g.places, joins = g.joins, also = mine)
    }

    /** Reads the whole blog a single post's link belongs to ([SiteImportState.Ready.whole]), instead of that post. */
    fun readWhole() {
        val ready = _state.value as? SiteImportState.Ready ?: return
        read(ready.whole ?: return, ready.into?.id)
    }

    /** Ends the reading with the posts found so far: they are organised and offered as usual. */
    fun stopReading() {
        stopRequested = true
    }

    /** Ticks or unticks trip [index] of the proposal. */
    fun toggle(index: Int) = editTrip(index) { it.copy(selected = !it.selected) }

    /** A new name for trip [index] (a new trip only: a trip an earlier copy made keeps its name). */
    fun rename(index: Int, title: String) {
        val t = title.trim().take(MAX_TITLE)
        if (t.isEmpty()) return
        editTrip(index) { if (it.joins == null) it.copy(title = t) else it }
    }

    private fun editTrip(index: Int, change: (ProposedTrip) -> ProposedTrip) {
        val ready = _state.value as? SiteImportState.Ready ?: return
        _state.value = if (ready.oneTrip) ready.copy(single = change(ready.single))
        else ready.copy(groups = ready.groups.mapIndexed { i, t -> if (i == index) change(t) else t })
    }

    /** Every post in one trip named after the site, or the trips as organised. */
    fun setOneTrip(on: Boolean) {
        val ready = _state.value as? SiteImportState.Ready ?: return
        _state.value = ready.copy(oneTrip = on)
    }

    /** Notes get the organiser's places (looked up on the map) and the plan gets an item for each, or neither. */
    fun setAddPlaces(on: Boolean) {
        val ready = _state.value as? SiteImportState.Ready ?: return
        // Without notes the places are all there is to copy.
        if (!on && !ready.withNotes) return
        _state.value = ready.copy(addPlaces = on)
    }

    /**
     * Where everything ticked goes: [trip], one the traveller has (owner, 17 Nov 2026: "add places to an existing
     * trip using all methods … create new trip or append"), or null for new trips as proposed.
     */
    fun setInto(trip: ExistingTrip?) {
        val ready = _state.value as? SiteImportState.Ready ?: return
        _state.value = ready.copy(into = trip)
    }

    /**
     * The notes and pictures come too, or only the places go into the plan (a guide someone else wrote: its places are
     * what the traveller wants, not its words). Only where the organiser found places.
     */
    fun setWithNotes(on: Boolean) {
        val ready = _state.value as? SiteImportState.Ready ?: return
        if (!on && !ready.hasPlaces) return
        _state.value = ready.copy(withNotes = on, addPlaces = if (on) ready.addPlaces else true)
    }

    /** Copies the ticked trips. */
    fun copy() {
        val ready = _state.value as? SiteImportState.Ready ?: return
        if (!ready.canCopy) return
        val notes = ready.withNotes
        val chosen = ready.trips.filter { it.selected && (it.pins.isNotEmpty() || (it.posts.isNotEmpty() && (notes || it.placeNames.isNotEmpty()))) }
        if (chosen.isEmpty()) return
        val into = ready.into
        // Into a trip the traveller has: everything ticked goes there together, in time order.
        val work = if (into == null) chosen else listOf(merged(chosen, into))
        val tally = Tally(ready.site, work.sumOf { it.posts.size + it.pins.size }, placesOnly = !notes || work.all { it.posts.isEmpty() })
        _state.value = tally.copying()
        job = scope.launch {
            val z = zone()
            val places = Places(ready.addPlaces || !notes, ready.areas)
            try {
                for (trip in work) copyTrip(trip, z, tally, ready.inline, places, into, notes)
                _state.value = tally.done(stopped = false)
            } catch (e: CancellationException) {
                _state.value = tally.done(stopped = true)
                throw e
            } finally {
                withContext(NonCancellable) { store.clearInline() }
            }
        }
    }

    /** The ticked trips as one, for [into]: their posts in time order (undated ones last, as they were), their places and pins. */
    private fun merged(chosen: List<ProposedTrip>, into: ExistingTrip): ProposedTrip = ProposedTrip(
        into.title, into.start, into.end,
        chosen.flatMap { it.posts }.sortedWith(compareBy(nullsLast<Instant>()) { it.at }),
        places = chosen.flatMap { it.places.entries }.associate { it.key to it.value },
        also = chosen.flatMap { it.also.entries }.associate { it.key to it.value },
        pins = chosen.flatMap { it.pins },
    )

    /** Stops reading (back to the start) or copying (the notes copied so far stay). */
    fun cancel() {
        job?.cancel()
    }

    /** Back to the start once a copy is over (Done, Failed, a proposal not wanted). */
    fun reset() {
        if (busy) return
        lastRead = null
        _state.value = SiteImportState.Idle
        scope.launch { store.clearInline() }
    }

    /** The organiser's places on the map, each looked up once per copy, at most [SiteLimits.MAX_PLACE_LOOKUPS]. */
    private inner class Places(val on: Boolean, private val areas: Map<String, String>) {
        private val found = HashMap<String, SiteNotes.Place>()
        private var lookups = 0

        /** Lookups that failed in a row: at [SiteLimits.MAX_PLACE_FAILURES] the map isn't answering and isn't asked again. */
        private var failures = 0

        /** The town and country the organiser gave for the place of note [entryId]. */
        fun area(entryId: String): String? = areas[entryId]

        /** [name] on the map, in [area] when the organiser gave one; the name alone when the map isn't asked or has no answer. */
        suspend fun of(name: String?, area: String?, near: LatLng?): SiteNotes.Place? {
            if (!on || name.isNullOrBlank()) return null
            val key = (name + "|" + area.orEmpty()).lowercase()
            found[key]?.let { return it }
            if (lookups >= SiteLimits.MAX_PLACE_LOOKUPS || failures >= SiteLimits.MAX_PLACE_FAILURES) return SiteNotes.Place(name)
            // The map is a free service: one place after the other, its questions a second apart (SitePlaces).
            lookups++
            val place = try {
                store.findPlace(name, area, near).also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures++
                null
            }?.copy(name = name) ?: SiteNotes.Place(name)
            found[key] = place
            return place
        }
    }

    /**
     * One trip's notes and places. [into]: the traveller's own trip they go into (its days stay as they are, and a
     * place goes on its note's day only when that is one of them). [withNotes] false: no note or picture is copied;
     * the places go into the plan as ideas.
     */
    private suspend fun copyTrip(
        trip: ProposedTrip, zone: ZoneId, tally: Tally, inline: Map<String, File>, places: Places, into: ExistingTrip?, withNotes: Boolean,
    ) {
        var tripId: String? = into?.id ?: trip.joins?.id
        var notes = 0
        var undated = 0
        var near: LatLng? = null
        var placed = 0
        val planned = HashSet<String>()
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val undatedBase = trip.start?.atTime(SiteSections.NOON, 0)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: clock()
        suspend fun theTrip(): String = tripId ?: store.createTrip(trip.title, trip.start, trip.end).also { tripId = it }
        fun planDay(day: LocalDate?): LocalDate? = when {
            !withNotes || day == null -> null
            into == null -> day
            into.start != null && into.end != null && !day.isBefore(into.start) && !day.isAfter(into.end) -> day
            else -> null
        }
        suspend fun plan(item: PlanItem) {
            val added = try {
                store.addPlanItem(item)
            } catch (_: Exception) {
                false
            }
            if (added) placed++ else tally.planned++
        }
        try {
            for (post in trip.posts) {
                currentCoroutineContext().ensureActive()
                val entryId = SiteNotes.entryId(post)
                if (withNotes && store.copied(entryId) != null) {
                    tally.skipped++
                } else {
                    val saved = Collections.synchronizedList(ArrayList<SavedPicture>())
                    try {
                        val place = places.of(trip.places[entryId], places.area(entryId), near)
                        place?.lat?.let { lat -> place.lng?.let { lng -> near = LatLng(lat, lng) } }
                        // The other places the note tells about: looked up around the note's own.
                        val others = trip.also[entryId].orEmpty().mapNotNull { places.of(it.name, it.area, near) }
                        val located = (listOfNotNull(place) + others).filter { it.located }
                        if (withNotes) download(post, SiteNotes.pictureIds(post, entryId), saved, tally, inline)
                        // Places only, and the map found none of this post's: nothing to add, and no empty trip.
                        if (withNotes || located.isNotEmpty()) withContext(NonCancellable) {
                            val id = theTrip()
                            if (withNotes) {
                                // Undated posts keep their order a minute apart.
                                val undatedAt = undatedBase + undated * SiteSections.STEP_MS
                                if (post.at == null) undated++
                                val kept = saved.mapTo(HashSet()) { it.media.id }
                                val note = SiteNotes.note(post, entryId, id, zone, undatedAt, kept, clock(), place)
                                store.saveNote(note, saved.map { it.media.copy(tripId = id, entryId = entryId) })
                            }
                            // Each place into the plan, once per trip, on its first note's day. Never a reason to lose
                            // the note: its place stays on it either way.
                            val day = planDay(post.at?.atZone(zone)?.toLocalDate())
                            for (p in located) {
                                if (!planned.add(p.name.lowercase())) continue
                                plan(SiteNotes.placeItem(id, p, day, visited = day != null && day < today, now = clock()))
                            }
                        }
                        if (withNotes) {
                            notes++
                            tally.notes++
                            tally.pictures += saved.size
                        }
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { store.discard(saved.toList()) }
                        throw e
                    } catch (_: Exception) {
                        withContext(NonCancellable) { store.discard(saved.toList()) }
                        tally.failedNotes++
                    }
                }
                tally.done++
                _state.value = tally.copying()
            }
            // Places that came with their pin (a Google Maps list): ideas, in the list's order.
            for (pin in trip.pins) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { plan(SiteNotes.pinItem(theTrip(), pin, clock())) }
                tally.done++
                _state.value = tally.copying()
            }
        } finally {
            val joins = trip.joins
            tripId?.let { id ->
                tally.trips += CopiedTrip(id, into?.title ?: trip.title, notes, joined = into != null || joins != null, places = placed)
                // A trip an earlier copy made grows to cover its new notes; a trip the traveller chose keeps its days.
                if (into == null && joins != null && notes > 0) withContext(NonCancellable) {
                    val start = listOfNotNull(joins.start, trip.start).minOrNull()
                    val end = listOfNotNull(joins.end, trip.end).maxOrNull()
                    if (start != joins.start || end != joins.end) store.extendTrip(id, start, end)
                }
            }
        }
    }

    /** The post's pictures, a few at a time; each that fails is counted and left out. */
    private suspend fun download(post: SitePost, ids: List<String>, saved: MutableList<SavedPicture>, tally: Tally, inline: Map<String, File>) = coroutineScope {
        val gate = Semaphore(SiteLimits.DOWNLOADS_AT_ONCE)
        post.pictures.mapIndexed { i, picture ->
            async {
                gate.withPermit {
                    try {
                        saved += store.savePicture(picture.url, ids[i], inline[picture.url])
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        tally.failedPictures.incrementAndGet()
                    }
                }
            }
        }.awaitAll()
    }

    private class Tally(val site: String, val total: Int, val placesOnly: Boolean = false) {
        var done = 0
        var notes = 0
        var pictures = 0
        var failedNotes = 0
        var skipped = 0

        /** Places the trip's plan had already. */
        var planned = 0
        val failedPictures = AtomicInteger()
        val trips = ArrayList<CopiedTrip>()

        fun copying() = SiteImportState.Copying(site, done, total, pictures, placesOnly)
        fun done(stopped: Boolean) = SiteImportState.Done(
            site, notes, pictures, failedPictures.get(), failedNotes, skipped, trips.toList(), stopped, trips.sumOf { it.places }, planned, placesOnly,
        )
    }

    companion object {
        /** The longest trip name typed in the review. */
        const val MAX_TITLE = 80

        /** The trip a list without a name becomes. */
        const val LIST_TITLE = "Google Maps list"
        const val EMPTY_LIST = "This Google Maps list has no places yet."
        const val MAPS_NO_ANSWER = "Google Maps didn't answer. Check your connection and try again."
    }
}
