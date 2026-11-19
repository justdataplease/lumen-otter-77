package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.clip.PlannedReplay
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.tracking.StopSource
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SampleArgentinaTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 6)
    private val start: LocalDate = today.minusDays(13)
    private val plan = SampleArgentina.plan(start, 0L)

    /** 15:00 in Athens = 10:00 in Argentina. */
    private val now: Instant = today.atTime(15, 0).atZone(ZoneId.of("Europe/Athens")).toInstant()
    private val t = 1_000L
    private val demo = SampleArgentina.demo(today, now, t)

    // ---- The plan ----

    @Test fun everyDayHasSomethingAndNothingFallsOutside() {
        val days = plan.items.mapNotNull { it.day }
        assertTrue(days.all { !it.isBefore(plan.trip.startDate) && !it.isAfter(plan.trip.endDate) })
        assertEquals((0L..30L).map { start.plusDays(it) }.toSet(), days.toSet())
        assertEquals(31, plan.trip.days)
    }

    @Test fun noBookingTimesAreInvented() {
        assertTrue(plan.items.all { it.timeMinutes == null })
    }

    @Test fun positionsAreUniqueWithinADay() {
        plan.items.groupBy { it.day }.values.forEach { day -> assertEquals(day.size, day.map { it.position }.toSet().size) }
    }

    @Test fun theRouteRunsFromBuenosAiresToSantiagoWithFlightsAndBoats() {
        val replay = PlannedReplay.build(plan.trip, plan.items)
        assertEquals("Fly to Buenos Aires", replay.waypoints.first().title)
        assertEquals("Fly home", replay.waypoints.last().title)
        val legs = RouteLegs.planned(plan.trip, plan.items)
        assertEquals(listOf("Fly to Mendoza", "Fly to Bariloche", "Fly to El Calafate", "Fly to Santiago"), legs.filter { it.vehicle == Vehicle.Plane }.map { it.toTitle })
        assertTrue(legs.count { it.vehicle == Vehicle.Ferry } >= 2)
    }

    // ---- The demo, relative to today ----

    @Test fun todayIsDay14InElChalten() {
        val trip = demo.trip
        assertEquals(SampleArgentina.TRIP_ID, trip.id)
        assertEquals(today.minusDays(13), trip.startDate)
        assertEquals(today.plusDays(17), trip.endDate)
        assertTrue(trip.isCurrent(today))
        assertEquals(14, trip.dayNumber(today))
        assertEquals(31, trip.days)
        assertEquals("Buenos Aires → Patagonia → Santiago", trip.subtitle)
        assertTrue(trip.isDemo)
        assertEquals("Laguna Capri and Mirador Fitz Roy", demo.items.single { it.day == today }.title)
        assertTrue(demo.items.all { it.day == null || (!it.day!!.isBefore(trip.startDate) && !it.day!!.isAfter(trip.endDate)) })
    }

    @Test fun daysGoneByAreTickedExceptNotesAndOptionalOnes() {
        val gone = demo.items.filter { it.day != null && it.day!!.isBefore(today) }
        assertTrue(gone.filter { it.kind != "note" && !it.title.contains("Acto Central") }.all { it.done })
        assertFalse(gone.single { it.title.contains("Acto Central") }.done)
        assertTrue(demo.items.filter { it.day == null || !it.day!!.isBefore(today) }.none { it.done })
    }

    @Test fun entriesFollowThePlanOnDaysOneToFourteen() {
        val zone = SampleArgentina.ZONE
        assertTrue(demo.entries.size in 10..12)
        val days = demo.entries.map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        assertTrue(days.all { !it.isBefore(start) && !it.isAfter(today) })
        assertEquals(today, days.max())
        assertEquals(1, days.count { it == today })
        assertTrue(demo.entries.all { it.zoneId == zone.id && it.tripId == SampleArgentina.TRIP_ID })
        assertTrue(demo.entries.all { it.weather == null && it.tempC == null })
        assertTrue(demo.entries.all { it.lat != null && it.lng != null && it.placeName.isNotBlank() && it.plainText.isNotBlank() })
        // Untouched rows: the same stamp for created and updated.
        assertTrue(demo.entries.all { it.createdAt == t && it.updatedAt == t })
        val titles = demo.entries.map { it.title }
        assertTrue(titles.containsAll(listOf("Recoleta", "San Telmo, then tango", "Lunch in Valle de Uco", "Vendimia", "Bariloche", "Circuito Chico", "Arrayanes by boat", "Laguna Capri")))
    }

    @Test fun todaysEntryIsOnTheTrailAndNeverInTheFuture() {
        val zone = SampleArgentina.ZONE
        val today = demo.entries.maxBy { it.occurredAt }
        val time = Instant.ofEpochMilli(today.occurredAt).atZone(zone).toLocalTime()
        assertEquals(java.time.LocalTime.of(9, 40), time) // 10:00 now, minus 20 minutes
        val day = this.today
        fun at(h: Int) = day.atTime(h, 0).atZone(zone).toInstant()
        fun timeFor(now: Instant) = SampleArgentina.todayEntryTime(day, now).atZone(zone).toLocalTime()
        assertEquals(java.time.LocalTime.of(12, 40), timeFor(at(22)))
        assertEquals(java.time.LocalTime.of(10, 0), timeFor(at(11).minusSeconds(40 * 60))) // 10:20 now
        // Early in the morning there: 20 minutes ago, not later in the day.
        assertEquals(java.time.LocalTime.of(5, 40), timeFor(at(6)))
    }

    @Test fun nothingIsLaterThanTwentyMinutesAgo() {
        // 8:00 in Athens = 3:00 in Argentina, and a phone far west of it.
        for (now in listOf(today.atTime(8, 0).atZone(ZoneId.of("Europe/Athens")).toInstant(), today.atTime(1, 0).atZone(ZoneId.of("Pacific/Honolulu")).toInstant())) {
            val d = SampleArgentina.demo(today, now, t)
            val cap = now.minusSeconds(20 * 60).toEpochMilli()
            assertTrue(d.entries.all { it.occurredAt <= cap })
            assertTrue(d.media.all { it.takenAt!! <= cap })
            val byId = d.entries.associateBy { it.id }
            assertTrue(d.media.filter { it.entryId != null }.all { it.takenAt!! <= byId.getValue(it.entryId!!).occurredAt })
        }
    }

    /** The media ids of an entry, single photos and layouts, in reading order. */
    private fun photoIds(body: String) = EntryDoc.parse(body).mediaIds()

    @Test fun everyEntryHasRealPhotosPlacedLikeTheEditorDoes() {
        val byId = demo.media.associateBy { it.id }
        for (e in demo.entries) {
            val ids = photoIds(e.body)
            assertTrue(e.title, ids.size in 1..3)
            for (id in ids) {
                val m = byId.getValue(id)
                assertEquals(e.id, m.entryId)
                assertEquals(SampleArgentina.TRIP_ID, m.tripId)
                // A real photo from the app's assets, never a drawn one.
                val photo = demo.photos.getValue(m.id)
                assertEquals(DemoPhotos.localPath(photo, m.id), m.localPath)
                assertTrue(m.localPath.startsWith("media/argentina-") && m.localPath.endsWith(".jpg"))
                assertEquals(photo.width, m.width); assertEquals(photo.height, m.height)
                assertTrue(m.takenAt!! <= e.occurredAt)
                val takenDay = Instant.ofEpochMilli(m.takenAt!!).atZone(SampleArgentina.ZONE).toLocalDate()
                assertEquals(Instant.ofEpochMilli(e.occurredAt).atZone(SampleArgentina.ZONE).toLocalDate(), takenDay)
                assertNotNull(m.lat); assertNotNull(m.lng)
            }
        }
        assertTrue(demo.pictures.isEmpty())
        // Every photo once.
        val all = demo.entries.flatMap { photoIds(it.body) }.map { demo.photos.getValue(it) }
        assertEquals(all.size, all.toSet().size)
        // Today's photo shows the tilt and the frame; the other single ones are placed as Write places them.
        val singles = demo.entries.flatMap { EntryDoc.parse(it.body).blocks.filterIsInstance<Block.Photo>() }
        val special = singles.filter { it.rotation != 0f || it.frame != PhotoFrame.Rounded }
        assertEquals(1, special.size)
        assertEquals(PhotoFrame.Polaroid, special.single().frame)
        assertEquals("Laguna Capri", special.single().caption)
        // Three layouts: plain, taped and polaroid.
        val layouts = demo.entries.flatMap { EntryDoc.parse(it.body).blocks.filterIsInstance<Block.Album>() }
        assertEquals(listOf(PhotoFrame.None, PhotoFrame.Tape, PhotoFrame.Polaroid), layouts.map { it.frame })
        assertEquals(listOf(3, 2, 2), layouts.map { it.mediaIds.size })
        // A checklist and bold, as in the other samples; the notes stay short.
        val blocks = demo.entries.flatMap { EntryDoc.parse(it.body).blocks }
        assertTrue(blocks.any { it is Block.Checklist })
        assertTrue(blocks.filterIsInstance<Block.Paragraph>().count { "<b>" in it.html } in 1..2)
        assertTrue(demo.entries.all { it.plainText.length < 400 })
    }

    @Test fun photosFollowThePlaces() {
        val byId = demo.entries.associateBy { it.id }
        fun names(title: String) = demo.media.filter { byId[it.entryId]?.title == title }.sortedBy { it.takenAt }.map { demo.photos.getValue(it.id) }
        assertEquals(listOf(DemoPhotos.OBELISCO), names("First night"))
        assertEquals(listOf(DemoPhotos.CAFE, DemoPhotos.RECOLETA, DemoPhotos.ROSEDAL), names("Recoleta"))
        assertEquals(listOf(DemoPhotos.UCO), names("Lunch in Valle de Uco"))
        assertEquals(listOf(DemoPhotos.CAMPANARIO), names("Circuito Chico"))
        assertEquals(listOf(DemoPhotos.CHALTEN, DemoPhotos.SUNSET), names("To El Chaltén"))
        assertEquals(listOf(DemoPhotos.CAPRI), names("Laguna Capri"))
    }

    @Test fun theCoverIsFitzRoy() {
        val cover = demo.media.single { it.id == demo.trip.coverMediaId }
        assertNull(cover.entryId)
        assertEquals(DemoPhotos.FITZ_ROY, demo.photos.getValue(cover.id))
        assertTrue(cover.localPath.startsWith("media/argentina-fitz-roy-cover-"))
    }

    @Test fun photosThatAskForCreditAreCreditedInThePlan() {
        val needed = DemoPhotos.ALL.filter { it.credit != null }
        val notes = plan.items.filter { it.title == SampleArgentina.CREDITS_TITLE }
        if (needed.isEmpty()) {
            assertTrue(notes.isEmpty())
            return
        }
        val note = notes.single()
        assertNull(note.day)
        assertEquals("note", note.kind)
        assertTrue(needed.all { it.credit!! in note.note })
        assertTrue(DemoPhotos.ALL.all { it.source.startsWith("https://") })
    }

    @Test fun everyPhotoIsInTheAssetsAtItsSize() {
        val dir = File("src/main/assets/${DemoPhotos.FOLDER}")
        assertEquals(DemoPhotos.ALL.map { "${it.name}.jpg" }.toSet(), dir.list()!!.toSet())
        for (photo in DemoPhotos.ALL) {
            val image = ImageIO.read(File("src/main/assets/${photo.asset}"))
            assertEquals(photo.name, photo.width, image.width)
            assertEquals(photo.name, photo.height, image.height)
            assertEquals(photo.name, 1280, maxOf(image.width, image.height))
        }
        assertEquals(DemoPhotos.ALL.size, DemoPhotos.ALL.map { it.name }.toSet().size)
    }

    @Test fun placesStayedAreTheStaysUpToToday() {
        assertEquals(4, demo.stops.size)
        assertTrue(demo.stops.all { it.countryCode == "AR" && it.source == StopSource.MANUAL && it.tripId == SampleArgentina.TRIP_ID })
        val sorted = demo.stops.sortedBy { it.arrivedAt }
        sorted.zipWithNext().forEach { (a, b) -> assertTrue(a.leftAt!! <= b.arrivedAt) }
        assertNull(sorted.last().leftAt)
        assertEquals("El Chaltén", sorted.last().name)
        val stays = demo.items.filter { it.kind == "stay" && it.lat != null }.map { it.lat to it.lng }
        assertTrue(sorted.dropLast(1).all { (it.lat to it.lng) in stays })
        val lastDay = Instant.ofEpochMilli(sorted.last().arrivedAt).atZone(SampleArgentina.ZONE).toLocalDate()
        assertEquals(today.minusDays(1), lastDay)
    }

    // ---- Sample version 8: the first journal becomes this one ----

    /** The first journal as an install still has it: drawn pictures, one entry per first title, and a drawn cover. */
    private fun firstJournal(stamp: Long): Triple<com.justdataplease.boringtravel.data.db.Trip, List<com.justdataplease.boringtravel.data.db.Entry>, List<com.justdataplease.boringtravel.data.db.Media>> {
        val trip = demo.trip.copy(coverMediaId = "old-cover", createdAt = stamp, updatedAt = stamp)
        // The daily move restamps the rows: created and updated differ even where nobody wrote anything.
        val entries = SampleArgentina.FIRST_TEXTS.entries.mapIndexed { i, (title, text) ->
            com.justdataplease.boringtravel.data.db.Entry(
                id = "old-$i", tripId = SampleArgentina.TRIP_ID, occurredAt = 0, zoneId = SampleArgentina.ZONE.id, title = title,
                plainText = text, createdAt = stamp - 5, updatedAt = stamp,
            )
        }
        val media = entries.map { e ->
            com.justdataplease.boringtravel.data.db.Media(id = "pic-${e.id}", tripId = SampleArgentina.TRIP_ID, entryId = e.id, localPath = "media/demo-lake-n-${e.id}.jpg", createdAt = stamp, updatedAt = stamp)
        } + com.justdataplease.boringtravel.data.db.Media(id = "old-cover", tripId = SampleArgentina.TRIP_ID, localPath = "media/demo-mountain-n-x.jpg", createdAt = stamp, updatedAt = stamp)
        return Triple(trip, entries, media)
    }

    @Test fun anUntouchedFirstJournalBecomesTheOneWithPhotos() {
        val (trip, entries, media) = firstJournal(stamp = 7)
        val swap = SampleArgentina.newJournal(trip, entries, media, now, t)!!
        // The old entries and every drawn picture go; the new journal comes on the trip's dates, stamped as seeded.
        assertEquals(entries.map { it.id }.toSet(), swap.entries.filter { it.deletedAt == t }.map { it.id }.toSet())
        val added = swap.entries.filter { it.deletedAt == null }
        assertEquals(demo.entries.map { it.title }, added.map { it.title })
        assertTrue(added.all { it.createdAt == 7L && it.updatedAt == 7L })
        assertEquals(media.map { it.id }.toSet(), swap.gone.map { it.id }.toSet())
        assertTrue(swap.gone.all { it.deletedAt == t })
        assertEquals(demo.media.size, swap.added.size)
        assertTrue(swap.added.all { it.createdAt == 7L && swap.photos.containsKey(it.id) })
        // The cover is the Fitz Roy photo; the trip still reads as untouched.
        assertEquals(DemoPhotos.FITZ_ROY, swap.photos.getValue(swap.trip.coverMediaId!!))
        assertEquals(7L, swap.trip.createdAt); assertEquals(7L, swap.trip.updatedAt)
        val days = added.map { Instant.ofEpochMilli(it.occurredAt).atZone(SampleArgentina.ZONE).toLocalDate() }
        assertEquals(today, days.max()); assertEquals(start, days.min())
        // The plan gets the photo credits once, after its ideas.
        val ideas = demo.items.filter { it.day == null && it.title != SampleArgentina.CREDITS_TITLE }
        val withCredits = SampleArgentina.newJournal(trip, entries, media, now, t, ideas)!!.items
        if (DemoPhotos.CREDITS.isEmpty()) {
            assertTrue(withCredits.isEmpty())
        } else {
            val note = withCredits.single()
            assertEquals(SampleArgentina.CREDITS_TITLE, note.title)
            assertNull(note.day)
            assertTrue(note.position > ideas.maxOf { it.position })
            assertEquals(7L, note.createdAt); assertEquals(7L, note.updatedAt)
            assertTrue(SampleArgentina.newJournal(trip, entries, media, now, t, ideas + note)!!.items.isEmpty())
        }
    }

    @Test fun aJournalSomeoneChangedStaysButTheDrawnCoverGoes() {
        val (trip, entries, media) = firstJournal(stamp = 7)
        // A note of the traveller's own stays, and the first journal goes around it.
        val withMine = entries + entries[0].copy(id = "mine", title = "My own note", plainText = "Mine")
        val around = SampleArgentina.newJournal(trip, withMine, media, now, t)!!
        assertEquals(entries.map { it.id }.toSet(), around.entries.filter { it.deletedAt == t }.map { it.id }.toSet())
        // Words someone changed: the journal stays as it is.
        for (changed in listOf(
            entries.mapIndexed { i, e -> if (i == 2) e.copy(plainText = e.plainText + " And a line of mine.") else e },
        )) {
            val swap = SampleArgentina.newJournal(trip.copy(updatedAt = 9), changed, media, now, t)!!
            assertTrue(swap.entries.isEmpty())
            assertEquals(listOf("old-cover"), swap.gone.map { it.id })
            assertEquals(1, swap.added.size)
            assertEquals(DemoPhotos.FITZ_ROY, swap.photos.getValue(swap.trip.coverMediaId!!))
            assertEquals(t, swap.trip.updatedAt)
        }
        // Nothing to do: a real cover of the traveller's and a changed journal, a deleted trip, another trip.
        val ownCover = media.map { if (it.id == "old-cover") it.copy(localPath = "media/abc.jpg") else it }
        val edited = entries.map { it.copy(plainText = "Rewritten") }
        assertNull(SampleArgentina.newJournal(trip, edited, ownCover, now, t))
        assertNull(SampleArgentina.newJournal(trip.copy(deletedAt = 3), entries, media, now, t))
        assertNull(SampleArgentina.newJournal(trip.copy(id = "mine"), entries, media, now, t))
        // Already the new journal: nothing to do.
        assertNull(SampleArgentina.newJournal(demo.trip, demo.entries, demo.media, now, t))
    }

}
