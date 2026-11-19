package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class PostDraftTest {
    private val start = LocalDate.of(2027, 3, 1)
    private val trip = Trip("t1", "Patagonia", startDate = start, endDate = start.plusDays(11), coverMediaId = "m2", createdAt = 1, updatedAt = 1)

    private fun item(id: String, day: LocalDate?, pos: Double, lat: Double? = null, lng: Double? = null) = PlanItem(
        id = id, tripId = "t1", day = day, position = pos, title = "Item $id", lat = lat, lng = lng, createdAt = 1, updatedAt = 1,
    )

    private fun entry(id: String, day: LocalDate, text: String = "We walked.", author: String? = null) = Entry(
        id = id, tripId = "t1", occurredAt = day.atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), zoneId = "UTC",
        title = "Note $id", plainText = text, createdAt = 1, updatedAt = 1, authorId = author,
    )

    private fun photo(id: String, entry: String?, at: Long, author: String? = null, mime: String = "image/jpeg") = Media(
        id = id, tripId = "t1", entryId = entry, localPath = "media/$id.jpg", mime = mime, takenAt = at, createdAt = at, updatedAt = at,
        authorId = author,
    )

    private val items = listOf(
        item("a", start, 1.0, -50.0, -73.0), item("b", start.plusDays(1), 1.0, -50.0, -72.0), item("idea", null, 1.0, 10.0, 10.0),
    )
    private val entries = listOf(entry("e1", start), entry("e2", start.plusDays(2)), entry("theirs", start, author = "friend"), entry("empty", start, text = "").copy(title = ""))
    private val photos = listOf(
        photo("m1", "e1", 10), photo("m2", "e1", 20), photo("m3", "e2", 30), photo("loose", null, 5),
        photo("friend", "theirs", 40, author = "friend"), photo("vid", null, 50, mime = "video/mp4"),
    )
    private val clips = listOf(SavedClip("c2", "t1", "Patagonia", 200), SavedClip("c1", "t1", "Patagonia", 100))
    private val source = PostSource(trip, items, entries, photos, clips, countries = listOf("AR", "CL"), recordedMeters = 0.0)

    @Test fun onlyTheTravellersOwnNotesAndPhotosCount() {
        assertEquals(listOf("loose", "m1", "m2", "m3"), source.ownPhotos.map { it.id })
        assertEquals(listOf("e1", "e2"), source.ownNotes.map { it.id })
        assertEquals(listOf("m1", "m2"), source.photosOf("e1"))
    }

    @Test fun aTripPostStartsWithTheTripsTitleAndCover() {
        val d = PostDrafts.start(source, "trip")
        assertEquals(PostKind.Trip, d.kind)
        assertEquals("Patagonia", d.title)
        assertEquals("m2", d.cover)
        assertFalse(d.includeNotes)
        // Notes are off by default: only the cover goes up, and the plan without notes.
        assertEquals(listOf("m2"), PostDrafts.pictures(source, d))
        val shared = PostDrafts.sharedTrip(source, d)!!
        assertEquals(3, shared.items.size)
        assertTrue(shared.notes.isEmpty())
        assertNull(PostDrafts.problem(source, d))
    }

    @Test fun onlyPicturesAndClipsNeedDrive() {
        val trip = PostDrafts.start(source, "trip")
        assertTrue(PostDrafts.needsDrive(source, trip)) // its photo cover
        val drawn = PostDrafts.withCover(source, trip, null)
        assertFalse(PostDrafts.needsDrive(source, drawn)) // the plan alone, with a drawn cover
        assertTrue(PostDrafts.needsDrive(source, PostDrafts.withNotes(source, drawn, true))) // the notes' photos go up
        assertTrue(PostDrafts.needsDrive(source, PostDrafts.start(source, "clip")))
        assertTrue(PostDrafts.needsDrive(source, PostDrafts.start(source, "album", mediaIds = listOf("m1"))))
    }

    @Test fun notesGoAlongWithTheirPhotos() {
        val d = PostDrafts.withNotes(source, PostDrafts.start(source, "trip"), true)
        assertEquals(setOf("e1", "e2"), d.noteIds)
        assertEquals(listOf("m2", "m1", "m3"), PostDrafts.pictures(source, d))
        val notes = PostDrafts.sharedTrip(source, d)!!.notes
        assertEquals(listOf("Note e1", "Note e2"), notes.map { it.title })
        assertEquals(listOf("m1", "m2"), notes[0].photos)
        val one = PostDrafts.toggleNote(d, "e1")
        assertEquals(listOf("Note e2"), PostDrafts.sharedTrip(source, one)!!.notes.map { it.title })
        // Off keeps the picks for later.
        val off = PostDrafts.withNotes(source, one, false)
        assertTrue(PostDrafts.sharedTrip(source, off)!!.notes.isEmpty())
        assertEquals(setOf("e2"), PostDrafts.withNotes(source, off, true).noteIds)
    }

    @Test fun notePhotosStopAtThirty() {
        val many = (1..40).map { photo("p$it", "e1", 100L + it) }
        val s = source.copy(photos = many)
        val d = PostDrafts.withNotes(s, PostDrafts.start(s, "trip"), true)
        assertEquals(30, PostDrafts.pictures(s, d).size)
        assertEquals(10, PostDrafts.leftOut(s, d))
        assertEquals(30, PostDrafts.sharedTrip(s, d)!!.notes[0].photos.size)
    }

    @Test fun anAlbumIsThePickedPhotosUpToThirty() {
        val d = PostDrafts.start(source, "album", mediaIds = listOf("m3", "friend", "m1", "nope"))
        assertEquals(listOf("m3", "m1"), d.albumIds)
        assertEquals("m3", d.cover)
        assertNull(PostDrafts.sharedTrip(source, d))
        val dropped = PostDrafts.toggleAlbum(d, "m3")
        assertEquals(listOf("m1"), dropped.albumIds)
        assertEquals("m1", dropped.cover)
        assertEquals("Pick at least one photo.", PostDrafts.problem(source, PostDrafts.toggleAlbum(dropped, "m1")))
        var full = PostDrafts.start(source, "album")
        (1..35).forEach { full = PostDrafts.toggleAlbum(full, "x$it") }
        assertEquals(30, full.albumIds.size)
    }

    @Test fun aClipPostTakesTheNewestClipAndItsFrame() {
        val d = PostDrafts.start(source, "clip")
        assertEquals("c2", d.clipId)
        assertEquals(PostPlan.CLIP_FRAME, d.cover)
        assertEquals("c1", PostDrafts.start(source, "clip", clipId = "c1").clipId)
        val plan = PostDrafts.plan("p1", source, d) { File("/clips/${it.id}.mp4") }
        assertEquals(File("/clips/c2.mp4"), plan.clip)
        assertEquals(listOf(PostPlan.CLIP_FRAME), plan.pictures)
        assertNull(plan.trip)
        assertEquals("Make a clip of this trip first.", PostDrafts.problem(source.copy(clips = emptyList()), PostDrafts.start(source.copy(clips = emptyList()), "clip")))
    }

    @Test fun switchingKindMovesTheCover() {
        val d = PostDrafts.start(source, "trip")
        assertEquals(PostPlan.CLIP_FRAME, PostDrafts.withKind(source, d, PostKind.Clip).cover)
        assertNull(PostDrafts.withKind(source, d, PostKind.Album).cover)
        assertEquals(listOf(null, "loose", "m1", "m2", "m3"), PostDrafts.coverChoices(source, d))
        assertNull(PostDrafts.withCover(source, d, null).cover)
        assertEquals("m2", PostDrafts.withCover(source, d, "not-a-photo").cover)
    }

    @Test fun factsComeFromTheTrip() {
        assertEquals(12, PostDrafts.days(source))
        assertEquals("2027-03", PostDrafts.startMonth(source))
        // No route recorded: the planned places in order, ideas left out (one degree of longitude at 50° S ≈ 71.5 km).
        assertEquals(71, PostDrafts.km(source))
        assertEquals(1240, PostDrafts.km(source.copy(recordedMeters = 1_240_400.0)))
        val someday = source.copy(trip = trip.copy(startDate = null, endDate = null), items = listOf(item("x", start.plusDays(10), 1.0), item("y", start.plusDays(12), 1.0)))
        assertEquals(3, PostDrafts.days(someday))
        assertEquals("2027-03", PostDrafts.startMonth(someday))
        val ideas = source.copy(trip = trip.copy(startDate = null, endDate = null), items = listOf(item("i", null, 1.0)))
        assertNull(PostDrafts.startMonth(ideas))
        assertEquals(0, PostDrafts.km(ideas))
    }

    @Test fun aFlightIsLeftOutOfThePlannedKmUnlessSwitchedOn() {
        // Owner, 2026-11-11: the flight to Madrid should not count. Athens airport → Madrid airport flies (over 700 km).
        val flight = listOf(item("ath", start, 1.0, 37.94, 23.94), item("mad", start, 2.0, 40.47, -3.56), item("prado", start.plusDays(1), 1.0, 40.4138, -3.6921))
        val s = source.copy(items = flight)
        assertTrue(PostDrafts.km(s) in 10..16) // Barajas → the Prado only
        val counted = trip.copy(travelModes = TravelModes.setKm(null, RouteLegKey(RouteLegSource.Planned, "ath", "mad"), true))
        assertTrue(PostDrafts.km(s.copy(trip = counted)) > 2_000)
    }

    @Test fun thePlanCarriesTheFacts() {
        val d = PostDrafts.start(source, "trip").copy(caption = "  Wind  ", title = " Patagonia 2027 ")
        val plan = PostDrafts.plan("p9", source, d) { null }
        assertEquals("p9", plan.id)
        assertEquals("Patagonia 2027", plan.title)
        assertEquals("Wind", plan.caption)
        assertEquals(listOf("AR", "CL"), plan.countries)
        assertEquals(12, plan.days)
        assertEquals("m2", plan.cover)
        assertNotNull(plan.trip)
        assertNull(PostRules.problem(plan))
    }

    @Test fun thePreviewLooksLikeTheFeedCard() {
        val d = PostDrafts.withNotes(source, PostDrafts.start(source, "trip"), true)
        val p = PostDrafts.preview("p1", source, d, uid = "me", username = "alexa", nowMs = 1_800_000_000_000)
        assertEquals("alexa", p.username)
        assertEquals(PostKind.Trip, p.kind)
        assertEquals(3, p.photoFileIds.size)
        assertEquals("cover", p.coverFileId)
        assertEquals("12 days · 71 km", PostText.facts(p, java.util.Locale.UK))
        assertNull(PostDrafts.preview("p1", source, PostDrafts.withCover(source, d, null), "me", "alexa", 0).coverFileId)
    }

    @Test fun demoTripsAndEmptyPlansCantBePosted() {
        val demo = source.copy(trip = trip.copy(id = "demo-patagonia"))
        assertEquals("Demo trips can't be posted. Post a trip of your own.", PostDrafts.problem(demo, PostDrafts.start(demo, "trip")))
        val empty = source.copy(items = emptyList())
        assertEquals("This trip has no plan to share yet.", PostDrafts.problem(empty, PostDrafts.start(empty, "trip")))
        // Notes alone are enough.
        assertNull(PostDrafts.problem(empty, PostDrafts.withNotes(empty, PostDrafts.start(empty, "trip"), true)))
        assertEquals("Give it a title.", PostDrafts.problem(source, PostDrafts.start(source, "trip").copy(title = " ")))
    }
}
