package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.links.MapsList
import com.justdataplease.boringtravel.links.MapsListException
import com.justdataplease.boringtravel.links.MapsListPlace
import com.justdataplease.boringtravel.links.MapsLists
import com.justdataplease.boringtravel.places.LatLng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** The copy with an in-memory phone: whole notes only, pictures that fail left out, already copied posts skipped. */
class SiteImporterTest {
    private class FakeStore : SiteStore {
        val notes = LinkedHashMap<String, SiteNotes.Note>()
        val media = HashMap<String, Media>()
        val files = HashSet<String>()
        val trips = ArrayList<Triple<String, LocalDate?, LocalDate?>>()
        val existing = HashMap<String, Triple<String, LocalDate?, LocalDate?>>() // live trips of earlier copies
        val extended = ArrayList<Triple<String, LocalDate?, LocalDate?>>()
        val lookups = ArrayList<String>()
        val areas = ArrayList<String?>()
        val plan = ArrayList<PlanItem>()
        val deletedItems = HashSet<String>()
        val fromFiles = ArrayList<String>()
        var failing = emptySet<String>()
        var mapDown = false
        var gateUrl: String? = null
        val gate = CompletableDeferred<Unit>()

        override suspend fun copied(entryId: String) = notes[entryId]?.let { CopiedNote(it.entry.tripId, it.entry.occurredAt) }
        override suspend fun trip(id: String, notes: List<Long>) = existing[id]?.let { ExistingTrip(id, it.first, it.second, it.third, notes) }
        override suspend fun createTrip(title: String, start: LocalDate?, end: LocalDate?): String {
            trips += Triple(title, start, end)
            return "trip-${trips.size}"
        }
        override suspend fun extendTrip(id: String, start: LocalDate?, end: LocalDate?) {
            extended += Triple(id, start, end)
        }
        override suspend fun savePicture(url: String, mediaId: String, local: File?): SavedPicture {
            if (url == gateUrl) gate.await()
            if (url in failing) throw IOException("refused")
            if (local != null) fromFiles += url
            val path = "media/$mediaId.jpg"
            files += path
            return SavedPicture(Media(id = mediaId, localPath = path, createdAt = 0, updatedAt = 0), fresh = true)
        }
        override suspend fun discard(pictures: List<SavedPicture>) {
            pictures.filter { it.fresh }.forEach { files -= it.media.localPath }
        }
        override suspend fun saveNote(note: SiteNotes.Note, media: List<Media>) {
            media.forEach { this.media[it.id] = it }
            notes[note.entry.id] = note
        }
        override suspend fun findPlace(name: String, area: String?, near: LatLng?): SiteNotes.Place? {
            lookups += name
            areas += area
            if (mapDown) throw IOException("timeout")
            return when (name) {
                "Kyoto" -> SiteNotes.Place(name, 35.0, 135.7, "Kyoto, Japan")
                "Fushimi Inari" -> SiteNotes.Place(name, 34.967, 135.773, "Fushimi Inari-taisha, Kyoto, Japan", "activity", "osm:W1")
                "Lisbon" -> SiteNotes.Place(name, 38.72, -9.14, "Lisbon, Portugal")
                else -> null
            }
        }
        override suspend fun addPlanItem(item: PlanItem): Boolean {
            if (item.id in deletedItems || plan.any { it.id == item.id }) return false
            if (plan.any { it.tripId == item.tripId && SiteNotes.samePlace(it, item) }) return false
            plan += item
            return true
        }
    }

    private fun post(n: Int, day: String?, vararg pictures: String) = SitePost(
        "$n", "https://wanderingfeet.example.com/$n", "Post $n", day?.let { Instant.parse("${it}T10:00:00Z") },
        listOf(SiteBlock.Text("Text $n")) + pictures.map { SiteBlock.Picture(it) },
    )

    private val posts = listOf(
        post(1, "2016-04-01", "https://cdn.example.com/1a.jpg"),
        post(2, "2016-04-03", "https://cdn.example.com/2a.jpg", "https://cdn.example.com/2b.jpg"),
        post(3, "2019-05-04"),
    )

    /** Reads [list] like SiteReader: the ones already copied are counted, not returned; Stop ends it. */
    private fun reading(list: List<SitePost> = posts, fail: Exception? = null, slow: Boolean = false, inline: Map<String, File> = emptyMap()) =
        SiteReading { _, isCopied, onProgress, stop ->
            if (fail != null) throw fail
            if (slow) {
                onProgress(1)
                while (!stop()) kotlinx.coroutines.delay(10)
                return@SiteReading SiteRead("Wandering Feet", "wanderingfeet.example.com", SiteKind.Feed, list.take(1), more = true, stopped = true)
            }
            val copied = list.count { isCopied(it) }
            onProgress(list.size)
            SiteRead("Wandering Feet", "wanderingfeet.example.com", SiteKind.Feed, list.filterNot { isCopied(it) }, copied, inline = inline)
        }

    private fun TestScope.importer(
        store: SiteStore, reading: SiteReading = reading(), ai: SiteAi? = null, now: Long = 42L, lists: MapsListReading? = null,
    ) = SiteImporter(
        this, reading, store, ai = { ai }, lists = lists, zone = { ZoneOffset.UTC }, locale = { Locale.ENGLISH }, clock = { now },
    )

    // ---- Google Maps lists, a trip of your own, places only (owner, 17 Nov 2026) ----

    private val listLink = "https://maps.app.goo.gl/4XbhDugkXP74C4Vu7?g_st=ac"
    private val seville = MapsList(
        "abc", "Seville", "",
        listOf(
            MapsListPlace("Plaza de España", 37.3772, -5.9869, "Plaza de España, Sevilla", mapsUrl = "https://maps.google.com/?cid=1"),
            MapsListPlace("Bodega Santa Cruz", 37.3863, -5.9915, "C. Rodrigo Caro, 1, Sevilla", "Montaditos at the bar", "https://maps.google.com/?cid=2"),
            // Two branches of one name, streets apart: two places.
            MapsListPlace("Bodega Santa Cruz", 37.3901, -5.9950, "Calle Feria, Sevilla", mapsUrl = "https://maps.google.com/?cid=3"),
            MapsListPlace("Mezquita-Catedral", 37.8789, -4.7794, "C. Cardenal Herrero, 1, Córdoba", mapsUrl = "https://maps.google.com/?cid=4"),
        ),
        total = 4,
    )
    private val lists = MapsListReading { url -> if (url.startsWith("https://maps.app.goo.gl/")) seville else null }

    @Test fun aGoogleMapsListBecomesATripOfIdeas() = runTest {
        val store = FakeStore()
        val importer = importer(store, lists = lists)
        importer.read(listLink)
        advanceUntilIdle()
        val ready = importer.state.value as SiteImportState.Ready
        // Places only: no notes, nothing read from a site, nothing for the AI.
        assertTrue(ready.listOnly)
        assertEquals(4, ready.pins)
        assertEquals("Seville", ready.trips.single().title)
        assertTrue(ready.canCopy)
        importer.copy()
        advanceUntilIdle()
        assertEquals(listOf(Triple("Seville", null, null)), store.trips)
        assertTrue(store.notes.isEmpty())
        assertTrue(store.lookups.isEmpty())
        assertEquals(4, store.plan.size)
        assertTrue(store.plan.all { it.tripId == "trip-1" && it.day == null && it.source == "import" && !it.done })
        val bodega = store.plan[1]
        assertEquals("food", bodega.kind)
        assertEquals("Montaditos at the bar", bodega.note)
        assertEquals("C. Rodrigo Caro, 1, Sevilla", bodega.placeName)
        assertEquals(37.3863 to -5.9915, bodega.lat to bodega.lng)
        assertEquals("https://maps.google.com/?cid=2", bodega.mapsUrl)
        assertNull(bodega.url)
        val done = importer.state.value as SiteImportState.Done
        assertTrue(done.placesOnly)
        assertEquals(4, done.places)
        assertEquals(0, done.notes)
    }

    @Test fun aListGoesIntoATripYouHaveAndNeverTwice() = runTest {
        val store = FakeStore().apply { existing["mine"] = Triple("Andalusia", LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 6)) }
        val importer = importer(store, lists = lists)
        // Opened from the trip: it comes chosen.
        importer.read(listLink, into = "mine")
        advanceUntilIdle()
        assertEquals("mine", (importer.state.value as SiteImportState.Ready).into?.id)
        importer.copy()
        advanceUntilIdle()
        // No new trip; the trip keeps its days; every place is an idea there.
        assertTrue(store.trips.isEmpty())
        assertTrue(store.extended.isEmpty())
        assertEquals(4, store.plan.count { it.tripId == "mine" && it.day == null })
        val done = importer.state.value as SiteImportState.Done
        assertEquals(listOf(CopiedTrip("mine", "Andalusia", 0, joined = true, places = 4)), done.trips)
        // The same list again: nothing new, and said so.
        importer.reset()
        importer.read(listLink, into = "mine")
        advanceUntilIdle()
        importer.copy()
        advanceUntilIdle()
        assertEquals(4, store.plan.size)
        val again = importer.state.value as SiteImportState.Done
        assertEquals(0, again.places)
        assertEquals(4, again.planned)
        // Back to a new trip when chosen so.
        importer.reset()
        importer.read(listLink, into = "mine")
        advanceUntilIdle()
        importer.setInto(null)
        importer.copy()
        advanceUntilIdle()
        assertEquals(listOf(Triple("Seville", null, null)), store.trips)
    }

    @Test fun aListThatCannotBeReadOrIsOnePlaceIsSaidPlainly() = runTest {
        val store = FakeStore()
        val notShared = importer(store, lists = { throw MapsListException() })
        notShared.read(listLink)
        advanceUntilIdle()
        assertEquals(MapsLists.NOT_READ, (notShared.state.value as SiteImportState.Failed).message)
        val place = importer(store, lists = { null })
        place.read("https://maps.app.goo.gl/OnePlace")
        advanceUntilIdle()
        assertEquals(MapsLists.NOT_A_LIST, (place.state.value as SiteImportState.Failed).message)
        val empty = importer(store, lists = { seville.copy(places = emptyList(), total = 0) })
        empty.read(listLink)
        advanceUntilIdle()
        assertEquals(SiteImporter.EMPTY_LIST, (empty.state.value as SiteImportState.Failed).message)
        // A blog's address never asks Google Maps.
        val blog = importer(store, lists = { error("asked") })
        blog.read("wanderingfeet.example.com")
        advanceUntilIdle()
        assertTrue(blog.state.value is SiteImportState.Ready)
    }

    @Test fun aBlogsPlacesWithoutItsNotesGoIntoATripYouHave() = runTest {
        // Someone else's guide: its places are wanted, not its words.
        val ai = SiteAi { _, _ ->
            """{"trips":[{"title":"Japan","posts":[1,2]},{"title":"Portugal","posts":[3]}],
               "details":[{"post":1,"place":"Kyoto","area":"Japan"},{"post":2,"place":"Fushimi Inari","area":"Kyoto, Japan"},{"post":3,"place":"Lisbon","area":"Portugal"}]}"""
        }
        val store = FakeStore().apply { existing["mine"] = Triple("Next year", LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 10)) }
        val importer = importer(store, ai = ai)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        importer.setWithNotes(false)
        val ready = importer.state.value as SiteImportState.Ready
        assertTrue(ready.addPlaces)
        // Places alone can't be switched off: they are all there is to copy.
        importer.setAddPlaces(false)
        assertTrue((importer.state.value as SiteImportState.Ready).addPlaces)
        assertEquals(3, ready.selectedPlaces)
        importer.setInto(ExistingTrip("mine", "Next year", LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 10), emptyList()))
        importer.copy()
        advanceUntilIdle()
        // No note, no picture, no new trip: three ideas in the trip, each at its place.
        assertTrue(store.notes.isEmpty())
        assertTrue(store.files.isEmpty())
        assertTrue(store.trips.isEmpty())
        assertEquals(listOf("Kyoto", "Fushimi Inari", "Lisbon"), store.plan.map { it.title })
        assertTrue(store.plan.all { it.tripId == "mine" && it.day == null && !it.done })
        val done = importer.state.value as SiteImportState.Done
        assertTrue(done.placesOnly)
        assertEquals(3, done.places)
    }

    @Test fun notesIntoATripYouHaveKeepItsDays() = runTest {
        val ai = SiteAi { _, _ ->
            """{"trips":[{"title":"Japan","posts":[1,2]},{"title":"Portugal","posts":[3]}],"details":[{"post":1,"place":"Kyoto","area":"Japan"},{"post":3,"place":"Lisbon","area":"Portugal"}]}"""
        }
        val store = FakeStore().apply { existing["mine"] = Triple("Spring 2016", LocalDate.of(2016, 4, 1), LocalDate.of(2016, 4, 2)) }
        val importer = importer(store, ai = ai)
        importer.read("wanderingfeet.example.com", into = "mine")
        advanceUntilIdle()
        importer.copy()
        advanceUntilIdle()
        // Every ticked note goes into the chosen trip, which keeps its own days.
        assertTrue(store.trips.isEmpty())
        assertTrue(store.extended.isEmpty())
        assertTrue(store.notes.values.all { it.entry.tripId == "mine" })
        assertEquals(3, store.notes.size)
        // A place on one of the trip's days goes on that day; one from another time is an idea.
        assertEquals(LocalDate.of(2016, 4, 1), store.plan.single { it.title == "Kyoto" }.day)
        assertNull(store.plan.single { it.title == "Lisbon" }.day)
        assertEquals(listOf(CopiedTrip("mine", "Spring 2016", 3, joined = true, places = 2)), (importer.state.value as SiteImportState.Done).trips)
    }

    @Test fun readsProposesAndCopiesWholeNotes() = runTest {
        val store = FakeStore()
        val importer = importer(store)
        importer.read(" wanderingfeet.example.com ")
        advanceUntilIdle()
        val ready = importer.state.value as SiteImportState.Ready
        assertEquals("wanderingfeet.example.com", ready.address)
        assertEquals(Organisation.Dates, ready.by)
        assertEquals(3, ready.notes)
        assertEquals(3, ready.pictures)
        assertFalse(ready.addPlaces)
        assertEquals(listOf("Wandering Feet · Apr 2016", "Wandering Feet · May 2019"), ready.trips.map { it.title })
        assertEquals(LocalDate.of(2016, 4, 1) to LocalDate.of(2016, 4, 3), ready.trips[0].start to ready.trips[0].end)

        importer.copy()
        advanceUntilIdle()
        val done = importer.state.value as SiteImportState.Done
        assertEquals(SiteImportState.Done("Wandering Feet", 3, 3, 0, 0, 0, listOf(CopiedTrip("trip-1", "Wandering Feet · Apr 2016", 2), CopiedTrip("trip-2", "Wandering Feet · May 2019", 1)), stopped = false), done)
        assertEquals(
            listOf(Triple("Wandering Feet · Apr 2016", LocalDate.of(2016, 4, 1), LocalDate.of(2016, 4, 3)), Triple("Wandering Feet · May 2019", LocalDate.of(2019, 5, 4), LocalDate.of(2019, 5, 4))),
            store.trips,
        )
        val second = store.notes.getValue(SiteNotes.entryId(posts[1]))
        assertEquals("trip-1", second.entry.tripId)
        assertEquals(SiteNotes.pictureIds(posts[1], second.entry.id), second.doc.mediaIds())
        second.doc.mediaIds().forEach { id ->
            assertEquals("trip-1", store.media.getValue(id).tripId)
            assertEquals(second.entry.id, store.media.getValue(id).entryId)
        }
        assertEquals("trip-2", store.notes.getValue(SiteNotes.entryId(posts[2])).entry.tripId)
    }

    @Test fun postsAlreadyCopiedAreSkipped() = runTest {
        val store = FakeStore()
        store.notes[SiteNotes.entryId(posts[0])] = SiteNotes.note(posts[0], SiteNotes.entryId(posts[0]), "old", ZoneOffset.UTC, 0L, emptySet(), 1L)
        val importer = importer(store)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        val ready = importer.state.value as SiteImportState.Ready
        assertEquals(1, ready.alreadyCopied)
        assertEquals(2, ready.notes)
        // Copied in the meantime (another run): skipped, not doubled.
        store.notes[SiteNotes.entryId(posts[2])] = SiteNotes.note(posts[2], SiteNotes.entryId(posts[2]), "old", ZoneOffset.UTC, 0L, emptySet(), 1L)
        importer.copy()
        advanceUntilIdle()
        val done = importer.state.value as SiteImportState.Done
        assertEquals(1, done.notes)
        assertEquals(1, done.skipped)
        assertEquals(3, store.notes.size)
    }

    @Test fun aCopyThatContinuesAddsToTheTripAlreadyMade() = runTest {
        // An earlier copy, stopped after post 1, made "Japan, spring 2016"; post 2 belongs with it, post 3 doesn't.
        val store = FakeStore()
        store.existing["trip-old"] = Triple("Japan, spring 2016", LocalDate.of(2016, 4, 1), LocalDate.of(2016, 4, 1))
        store.notes[SiteNotes.entryId(posts[0])] = SiteNotes.note(posts[0], SiteNotes.entryId(posts[0]), "trip-old", ZoneOffset.UTC, 0L, emptySet(), 1L)
        val importer = importer(store)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        val ready = importer.state.value as SiteImportState.Ready
        assertEquals(listOf("trip-old", null), ready.trips.map { it.joins?.id })
        assertEquals("Japan, spring 2016", ready.trips[0].title)
        importer.copy()
        advanceUntilIdle()
        val done = importer.state.value as SiteImportState.Done
        assertEquals(listOf(CopiedTrip("trip-old", "Japan, spring 2016", 1, joined = true), CopiedTrip("trip-1", "Wandering Feet · May 2019", 1)), done.trips)
        assertEquals("trip-old", store.notes.getValue(SiteNotes.entryId(posts[1])).entry.tripId)
        assertEquals(listOf(Triple("trip-old", LocalDate.of(2016, 4, 1), LocalDate.of(2016, 4, 3))), store.extended)
        assertEquals(1, store.trips.size) // only the 2019 trip is new
    }

    @Test fun aDeletedTripIsNotBroughtBack() = runTest {
        // Its notes were deleted with it: nothing counts as copied, every post makes new trips.
        val store = FakeStore()
        val importer = importer(store)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        assertTrue((importer.state.value as SiteImportState.Ready).trips.all { it.joins == null })
    }

    @Test fun aPictureThatFailsIsLeftOutAndCounted() = runTest {
        val store = FakeStore().apply { failing = setOf("https://cdn.example.com/2a.jpg") }
        val importer = importer(store)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        importer.copy()
        advanceUntilIdle()
        val done = importer.state.value as SiteImportState.Done
        assertEquals(3, done.notes)
        assertEquals(2, done.pictures)
        assertEquals(1, done.failedPictures)
        val note = store.notes.getValue(SiteNotes.entryId(posts[1]))
        assertEquals(listOf(SiteNotes.pictureIds(posts[1], note.entry.id)[1]), note.doc.mediaIds())
    }

    @Test fun picturesThatCameInsideThePageAreCopiedFromTheirFiles() = runTest {
        val inline = "${InlinePictures.ADDRESS_PREFIX}abc.png"
        val doc = post(9, "2020-01-01", inline)
        val store = FakeStore()
        val importer = importer(store, reading(listOf(doc), inline = mapOf(inline to File("abc.png"))))
        importer.read("https://docs.google.com/document/d/x")
        advanceUntilIdle()
        importer.copy()
        advanceUntilIdle()
        assertEquals(listOf(inline), store.fromFiles)
    }

    @Test fun cancelLeavesNoHalfWrittenNote() = runTest {
        val store = FakeStore().apply { gateUrl = "https://cdn.example.com/2b.jpg" }
        val importer = importer(store)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        importer.copy()
        advanceUntilIdle()
        // Post 1 is saved; post 2 waits on its second picture, its first one already on disk.
        val copying = importer.state.value as SiteImportState.Copying
        assertEquals(1, copying.done)
        assertTrue(store.files.any { it.startsWith("media/" + SiteNotes.pictureIds(posts[1], SiteNotes.entryId(posts[1]))[0]) })
        importer.cancel()
        advanceUntilIdle()
        val done = importer.state.value as SiteImportState.Done
        assertTrue(done.stopped)
        assertEquals(1, done.notes)
        assertEquals(listOf(CopiedTrip("trip-1", "Wandering Feet · Apr 2016", 1)), done.trips)
        assertEquals(setOf(SiteNotes.entryId(posts[0])), store.notes.keys)
        assertEquals(1, store.files.size) // only post 1's picture: post 2's was removed again
        assertEquals(1, store.media.size)
        assertFalse(importer.busy)
    }

    @Test fun untickedRenamedAndOneTrip() = runTest {
        val store = FakeStore()
        val importer = importer(store)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        importer.toggle(0)
        importer.rename(1, "  Lisbon, at last  ")
        val ready = importer.state.value as SiteImportState.Ready
        assertEquals(1, ready.selectedNotes)
        assertEquals("Lisbon, at last", ready.trips[1].title)
        importer.copy()
        advanceUntilIdle()
        assertEquals(setOf(SiteNotes.entryId(posts[2])), store.notes.keys)
        assertEquals(listOf("Lisbon, at last"), store.trips.map { it.first })

        val all = FakeStore()
        val one = importer(all)
        one.read("wanderingfeet.example.com")
        advanceUntilIdle()
        one.setOneTrip(true)
        assertEquals(listOf("Wandering Feet"), (one.state.value as SiteImportState.Ready).trips.map { it.title })
        one.copy()
        advanceUntilIdle()
        assertEquals(listOf(Triple("Wandering Feet", LocalDate.of(2016, 4, 1), LocalDate.of(2019, 5, 4))), all.trips)
        assertEquals(3, all.notes.size)
    }

    @Test fun placesFromTheOrganiserAreLookedUpOnceAndCanBeSwitchedOff() = runTest {
        val ai = SiteAi { _, _ ->
            """{"trips":[{"title":"Japan","posts":[1,2]},{"title":"Portugal","posts":[3]}],
               "details":[{"post":1,"place":"Kyoto"},{"post":2,"place":"Kyoto"},{"post":3,"place":"Atlantis"}]}"""
        }
        val store = FakeStore()
        val importer = importer(store, ai = ai)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        val ready = importer.state.value as SiteImportState.Ready
        assertEquals(Organisation.Ai, ready.by)
        assertTrue(ready.addPlaces)
        assertEquals(listOf("Kyoto"), ready.trips[0].placeNames)
        importer.copy()
        advanceUntilIdle()
        assertEquals(listOf("Kyoto", "Atlantis"), store.lookups)
        val first = store.notes.getValue(SiteNotes.entryId(posts[0])).entry
        assertEquals("Kyoto" to 35.0, first.placeName to first.lat)
        val third = store.notes.getValue(SiteNotes.entryId(posts[2])).entry
        assertEquals("Atlantis", third.placeName) // not found on the map: the name only
        assertNull(third.lat)
        // The words are the post's own.
        assertEquals("Text 1", first.plainText)

        val off = FakeStore()
        val second = importer(off, ai = ai)
        second.read("wanderingfeet.example.com")
        advanceUntilIdle()
        second.setAddPlaces(false)
        second.copy()
        advanceUntilIdle()
        assertTrue(off.lookups.isEmpty())
        assertEquals("", off.notes.getValue(SiteNotes.entryId(posts[0])).entry.placeName)
    }

    @Test fun aPlaceFoundBecomesAPlanItemThatShowsItsNotes() = runTest {
        // Owner, 15 Nov 2026: copying a site "should try to find the place and create also trip items and associate with notes".
        val ai = SiteAi { _, _ ->
            """{"trips":[{"title":"Japan","posts":[1,2]},{"title":"Portugal","posts":[3]}],
               "details":[{"post":1,"place":"Kyoto","area":"Japan"},{"post":2,"place":"Kyoto","area":"Japan"},{"post":3,"place":"Atlantis","area":"The sea"}]}"""
        }
        val store = FakeStore()
        val importer = importer(store, ai = ai, now = Instant.parse("2026-11-15T10:00:00Z").toEpochMilli())
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        assertEquals(mapOf(SiteNotes.entryId(posts[0]) to "Japan", SiteNotes.entryId(posts[1]) to "Japan", SiteNotes.entryId(posts[2]) to "The sea"), (importer.state.value as SiteImportState.Ready).areas)
        importer.copy()
        advanceUntilIdle()
        // The place is searched in its country; one item for Kyoto on its first note's day, ticked (2016 is gone by).
        assertEquals(listOf("Japan", "The sea"), store.areas)
        val item = store.plan.single()
        assertEquals("trip-1", item.tripId)
        assertEquals("Kyoto", item.title)
        assertEquals("Kyoto, Japan", item.placeName)
        assertEquals(35.0 to 135.7, item.lat to item.lng)
        assertEquals(LocalDate.of(2016, 4, 1), item.day)
        assertTrue(item.done)
        assertEquals("place" to "import", item.kind to item.source)
        assertEquals(SiteNotes.placeItemId("trip-1", "kyoto "), item.id)
        // Both notes are the item's: placed by its name, at its point (ui/trip/ItemNotes goes by either).
        val notes = listOf(posts[0], posts[1]).map { store.notes.getValue(SiteNotes.entryId(it)).entry }
        assertTrue(notes.all { it.tripId == item.tripId && it.placeName == item.title && it.lat == item.lat && it.lng == item.lng })
        // A place the map doesn't know stays a name on its note: no item for it.
        assertEquals("Atlantis", store.notes.getValue(SiteNotes.entryId(posts[2])).entry.placeName)
        val done = importer.state.value as SiteImportState.Done
        assertEquals(1, done.places)
        assertEquals(listOf(1, 0), done.trips.map { it.places })
    }

    @Test fun aMapThatDoesNotAnswerIsLeftAloneAndTheNotesAreCopied() = runTest {
        val ai = SiteAi { _, _ ->
            """{"trips":[{"title":"Japan","posts":[1,2]},{"title":"Portugal","posts":[3]}],
               "details":[{"post":1,"place":"Kyoto"},{"post":2,"place":"Fushimi Inari"},{"post":3,"place":"Lisbon"}]}"""
        }
        val store = FakeStore().apply { mapDown = true }
        val importer = importer(store, ai = ai)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        importer.copy()
        advanceUntilIdle()
        // Two failures in a row: the third place isn't asked for. Every note is copied, with its place by name.
        assertEquals(listOf("Kyoto", "Fushimi Inari"), store.lookups)
        assertEquals(listOf("Kyoto", "Fushimi Inari", "Lisbon"), posts.map { store.notes.getValue(SiteNotes.entryId(it)).entry.placeName })
        assertTrue(store.notes.values.all { it.entry.lat == null })
        assertTrue(store.plan.isEmpty())
        assertEquals(3, (importer.state.value as SiteImportState.Done).notes)
    }

    @Test fun anAiThatDidNotAnswerIsSaidAndCanBeAskedAgain() = runTest {
        var calls = 0
        var reads = 0
        val ai = SiteAi { _, _ ->
            if (calls++ == 0) throw IOException("No AI answers left today")
            """{"trips":[{"title":"Japan","posts":[1,2]},{"title":"Portugal","posts":[3]}],"details":[{"post":1,"place":"Kyoto","area":"Japan"}]}"""
        }
        val store = FakeStore()
        val counted = SiteReading { address, isCopied, onProgress, stop ->
            reads++
            reading().read(address, isCopied, onProgress, stop)
        }
        val importer = importer(store, counted, ai = ai)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        val first = importer.state.value as SiteImportState.Ready
        assertTrue(first.aiSilent)
        assertEquals(Organisation.Dates, first.by)
        assertFalse(first.hasPlaces)
        importer.organiseAgain()
        advanceUntilIdle()
        val second = importer.state.value as SiteImportState.Ready
        assertFalse(second.aiSilent)
        assertEquals(Organisation.Ai, second.by)
        assertEquals(listOf("Japan", "Portugal"), second.trips.map { it.title })
        assertTrue(second.addPlaces)
        // The site was read once.
        assertEquals(1, reads)
        // Without any AI there is nothing to say and nothing to ask again.
        val none = importer(FakeStore())
        none.read("wanderingfeet.example.com")
        advanceUntilIdle()
        assertFalse((none.state.value as SiteImportState.Ready).aiSilent)
    }

    @Test fun planItemsAreNotDoubledOrBroughtBack() = runTest {
        val ai = SiteAi { _, _ ->
            """{"trips":[{"title":"Japan","posts":[1,2]},{"title":"Portugal","posts":[3]}],
               "details":[{"post":1,"place":"Kyoto"},{"post":2,"place":"Fushimi Inari","area":"Kyoto, Japan"},{"post":3,"place":"Lisbon"}]}"""
        }
        val store = FakeStore().apply {
            // The traveller deleted the Lisbon item an earlier copy made; trip-1 has a Kyoto of their own already.
            deletedItems += SiteNotes.placeItemId("trip-2", "Lisbon")
            plan += PlanItem(id = "own", tripId = "trip-1", title = "kyoto", createdAt = 0, updatedAt = 0)
        }
        val importer = importer(store, ai = ai)
        importer.read("wanderingfeet.example.com")
        advanceUntilIdle()
        importer.copy()
        advanceUntilIdle()
        assertEquals(listOf("kyoto", "Fushimi Inari"), store.plan.map { it.title })
        val inari = store.plan.last()
        assertEquals("activity" to "osm:W1", inari.kind to inari.externalId)
        assertEquals(LocalDate.of(2016, 4, 3), inari.day)
        assertEquals(1, (importer.state.value as SiteImportState.Done).places)

        // Places switched off: no pins and no plan items.
        val off = FakeStore()
        val second = importer(off, ai = ai)
        second.read("wanderingfeet.example.com")
        advanceUntilIdle()
        second.setAddPlaces(false)
        second.copy()
        advanceUntilIdle()
        assertTrue(off.plan.isEmpty())
        assertEquals(0, (second.state.value as SiteImportState.Done).places)
    }

    @Test fun anUndatedNotesPlaceIsAnIdeaAndALinkToOnePostOffersTheWholeBlog() = runTest {
        val ai = SiteAi { _, _ -> """{"trips":[{"title":"Kyoto","posts":[1]}],"details":[{"post":1,"place":"Kyoto"}]}""" }
        val store = FakeStore()
        val one = SiteReading { address, _, _, _ ->
            if (address == "https://x.example.com/") SiteRead("X", "x.example.com", SiteKind.Feed, listOf(post(7, null), post(8, null)))
            else SiteRead("X", "x.example.com", SiteKind.Page, listOf(post(7, null)), whole = "https://x.example.com/")
        }
        val importer = importer(store, one, ai = ai)
        importer.read("https://x.example.com/7")
        advanceUntilIdle()
        val ready = importer.state.value as SiteImportState.Ready
        assertEquals("https://x.example.com/" to 1, ready.whole to ready.notes)
        importer.copy()
        advanceUntilIdle()
        val item = store.plan.single()
        assertNull(item.day)
        assertFalse(item.done)

        // "Copy the whole blog instead" reads the blog's own address.
        val whole = importer(FakeStore(), one)
        whole.read("https://x.example.com/7")
        advanceUntilIdle()
        whole.readWhole()
        advanceUntilIdle()
        val all = whole.state.value as SiteImportState.Ready
        assertEquals("https://x.example.com/" to 2, all.address to all.notes)
        assertNull(all.whole)
    }

    /** One post about three places, each under its heading, after a line of its own. */
    private val world = SitePost(
        "9", "https://wanderingfeet.example.com/world", "Round the world", Instant.parse("2019-05-04T10:00:00Z"),
        listOf(
            SiteBlock.Text("Three stops."),
            SiteBlock.Heading("Kyoto", 2), SiteBlock.Text("Temples."),
            SiteBlock.Heading("Fushimi Inari", 2), SiteBlock.Text("Gates."), SiteBlock.Picture("https://cdn.example.com/gates.jpg"),
            SiteBlock.Heading("Lisbon", 2), SiteBlock.Text("Trams."),
        ),
        tags = listOf("world"),
    )

    private val worldCut = SiteAi { _, _ ->
        """{"trips":[{"title":"Round the world","posts":[1]}],"details":[{"post":1,"places":[
            {"place":"Kyoto","area":"Japan","part":2},{"place":"Fushimi Inari","area":"Kyoto, Japan","part":4},{"place":"Lisbon","area":"Portugal","part":6}]}]}"""
    }

    @Test fun aPostAboutSeveralPlacesIsANotePerPlaceEachUnderItsPlanItem() = runTest {
        // Owner, 16 Nov 2026: "a post may be 10 places, it should create 10 places, 10 journal posts if it can separate them".
        val store = FakeStore()
        val importer = importer(store, reading(listOf(world)), ai = worldCut, now = Instant.parse("2026-11-16T10:00:00Z").toEpochMilli())
        importer.read("wanderingfeet.example.com/world")
        advanceUntilIdle()
        val ready = importer.state.value as SiteImportState.Ready
        assertEquals(4, ready.notes)
        assertEquals(listOf("Kyoto", "Fushimi Inari", "Lisbon"), ready.trips.single().placeNames)
        importer.copy()
        advanceUntilIdle()
        val done = importer.state.value as SiteImportState.Done
        assertEquals(listOf(4, 1, 3), listOf(done.notes, done.pictures, done.places))
        // Its opening words, then a note per place, in the post's order a minute apart, the words as written.
        val notes = store.notes.values.map { it.entry }.sortedBy { it.occurredAt }
        assertEquals(listOf("Round the world", "Kyoto", "Fushimi Inari", "Lisbon"), notes.map { it.title })
        assertEquals(listOf("Three stops.", "Temples.", "Gates.", "Trams.\n\n#world"), notes.map { it.plainText.trim() })
        assertEquals(listOf("", "Kyoto", "Fushimi Inari", "Lisbon"), notes.map { it.placeName })
        assertEquals(listOf(0L, 1L, 2L, 3L), notes.map { (it.occurredAt - world.at!!.toEpochMilli()) / SiteSections.STEP_MS })
        // Each place is an item in the plan, on the post's day, and its note is the item's (same name, same point).
        assertEquals(listOf("Kyoto", "Fushimi Inari", "Lisbon"), store.plan.map { it.title })
        assertTrue(store.plan.all { it.day == LocalDate.of(2019, 5, 4) && it.done })
        store.plan.forEach { item ->
            val note = notes.single { it.placeName == item.title }
            assertEquals(item.lat to item.lng, note.lat to note.lng)
        }
        assertEquals(listOf("Japan", "Kyoto, Japan", "Portugal"), store.areas)
        // The picture went with its place's note.
        assertEquals(notes[2].id, store.media.values.single().entryId)

        // The same link again: the post is already copied.
        val again = importer(store, reading(listOf(world)), ai = worldCut)
        again.read("wanderingfeet.example.com/world")
        advanceUntilIdle()
        val second = again.state.value as SiteImportState.Ready
        assertEquals(0 to 1, second.notes to second.alreadyCopied)
    }

    @Test fun aPostThatCannotBeSeparatedIsOneNoteWithAnItemForEveryPlace() = runTest {
        val ai = SiteAi { _, _ ->
            """{"trips":[{"title":"Round the world","posts":[1]}],"details":[{"post":1,"places":[
                {"place":"Kyoto","area":"Japan","part":null},{"place":"Fushimi Inari","area":"Kyoto, Japan","part":null},
                {"place":"Atlantis","area":null,"part":null},{"place":"Lisbon","area":"Portugal","part":null}]}]}"""
        }
        val store = FakeStore()
        val importer = importer(store, reading(listOf(world)), ai = ai)
        importer.read("wanderingfeet.example.com/world")
        advanceUntilIdle()
        val ready = importer.state.value as SiteImportState.Ready
        assertEquals(1, ready.notes)
        assertEquals(listOf("Kyoto", "Fushimi Inari", "Atlantis", "Lisbon"), ready.trips.single().placeNames)
        importer.copy()
        advanceUntilIdle()
        // One note, at its first place; every place the map knows is in the plan.
        val note = store.notes.values.single().entry
        assertEquals(SiteNotes.entryId(world), note.id)
        assertEquals("Kyoto" to 35.0, note.placeName to note.lat)
        assertEquals(listOf("Kyoto", "Fushimi Inari", "Atlantis", "Lisbon"), store.lookups)
        assertEquals(listOf("Kyoto", "Fushimi Inari", "Lisbon"), store.plan.map { it.title })
        assertEquals(3, (importer.state.value as SiteImportState.Done).places)

        // Places switched off: the note alone.
        val off = FakeStore()
        val second = importer(off, reading(listOf(world)), ai = ai)
        second.read("wanderingfeet.example.com/world")
        advanceUntilIdle()
        second.setAddPlaces(false)
        second.copy()
        advanceUntilIdle()
        assertTrue(off.lookups.isEmpty() && off.plan.isEmpty())
        assertEquals("", off.notes.values.single().entry.placeName)
    }

    @Test fun aPostStoppedHalfWayIsFinishedByTheNextCopy() = runTest {
        // Stopped while its third note's picture came down: the post doesn't count as copied yet (its last note
        // carries its id), so the same link finishes it in the same trip, without doubling the notes already made.
        val store = FakeStore().apply { gateUrl = "https://cdn.example.com/gates.jpg" }
        val importer = importer(store, reading(listOf(world)), ai = worldCut)
        importer.read("wanderingfeet.example.com/world")
        advanceUntilIdle()
        importer.copy()
        advanceUntilIdle()
        importer.cancel()
        advanceUntilIdle()
        assertEquals(listOf("Round the world", "Kyoto"), store.notes.values.map { it.entry.title })
        assertTrue(SiteNotes.entryId(world) !in store.notes)

        store.gateUrl = null
        store.existing["trip-1"] = Triple("Round the world", LocalDate.of(2019, 5, 4), LocalDate.of(2019, 5, 4))
        val again = importer(store, reading(listOf(world)), ai = worldCut)
        again.read("wanderingfeet.example.com/world")
        advanceUntilIdle()
        val ready = again.state.value as SiteImportState.Ready
        assertEquals(2, ready.notes)
        assertEquals("trip-1", ready.trips.single().joins?.id)
        again.copy()
        advanceUntilIdle()
        val done = again.state.value as SiteImportState.Done
        assertEquals(listOf(2, 0), listOf(done.notes, done.skipped))
        assertEquals(listOf(CopiedTrip("trip-1", "Round the world", 2, joined = true, places = 2)), done.trips)
        assertEquals(1, store.trips.size)
        assertEquals(setOf("trip-1"), store.notes.values.mapTo(HashSet()) { it.entry.tripId })
        assertEquals(listOf("Round the world", "Kyoto", "Fushimi Inari", "Lisbon"), store.notes.values.map { it.entry.title })
        assertEquals(listOf("Kyoto", "Fushimi Inari", "Lisbon"), store.plan.map { it.title })
    }

    @Test fun undatedPostsKeepTheirOrder() = runTest {
        val undated = listOf(post(7, null), post(8, null), post(9, null))
        val store = FakeStore()
        val importer = importer(store, reading(undated))
        importer.read("x.example.com")
        advanceUntilIdle()
        importer.copy()
        advanceUntilIdle()
        val times = undated.map { store.notes.getValue(SiteNotes.entryId(it)).entry.occurredAt }
        assertEquals(listOf(42L, 42L + SiteSections.STEP_MS, 42L + 2 * SiteSections.STEP_MS), times)
    }

    @Test fun readingFailsWithAPlainReasonStopsOrKeepsWhatItFound() = runTest {
        val failing = importer(FakeStore(), reading(fail = SiteReadException(SiteReader.NO_POSTS)))
        failing.read("x.example.com")
        advanceUntilIdle()
        assertEquals(SiteImportState.Failed("x.example.com", SiteReader.NO_POSTS), failing.state.value)

        val broken = importer(FakeStore(), reading(fail = IllegalStateException("boom")))
        broken.read("x.example.com")
        advanceUntilIdle()
        assertEquals(SiteImportState.Failed("x.example.com", SiteReader.COULD_NOT_READ), broken.state.value)

        val slow = importer(FakeStore(), reading(slow = true))
        slow.read("x.example.com")
        runCurrent()
        assertTrue(slow.state.value is SiteImportState.Reading)
        slow.cancel()
        advanceUntilIdle()
        assertEquals(SiteImportState.Idle, slow.state.value)

        val partial = importer(FakeStore(), reading(slow = true))
        partial.read("x.example.com")
        runCurrent()
        partial.stopReading()
        advanceUntilIdle()
        val ready = partial.state.value as SiteImportState.Ready
        assertTrue(ready.stopped)
        assertEquals(1, ready.notes)
    }
}
