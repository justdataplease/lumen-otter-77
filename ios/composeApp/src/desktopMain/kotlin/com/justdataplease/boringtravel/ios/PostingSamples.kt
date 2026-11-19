package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.startOfDayMs

/*
 * A trip of the traveller's own to post (round 25), for the composer's tests and pictures: Paros and Naxos with a plan of
 * located places (so its km and countries are the plan's), an idea, two notes with photos in them and a loose photo,
 * and optionally a saved trip video with its picture. Every row made through the engine's trip store, every photo drawn
 * (SampleNotes.kt); nothing is a real person's.
 */

/** The trip to post: its [book], the app's media [kit] holding its photos (and clip), the trip's id and the clip's. */
class PostingTrip(val book: TripBook, val kit: MediaKit, val tripId: String, val clipId: String?) {
    val title: String get() = book.trip(tripId)!!.title
}

/** A clip's picture from 40 % in, and its length, as AVFoundation reads them on iPhone; nothing else of a video. */
object PostingFrames : VideoFrames {
    val frame: ByteArray by lazy { samplePhoto(Scene.Sunset, 960, 540) }

    override fun poster(path: String, maxPx: Int): ByteArray? = null

    override fun durationMs(path: String): Long = 42_000

    override fun frameAt(path: String, atMs: Long, maxPx: Int, quality: Int): ByteArray = frame
}

/**
 * Paros and Naxos, 20 days before [today]: five days, six planned places and an idea, two notes with two photos each
 * and a photo in no note; [clip] adds a saved trip video. Its photos are 2400 × 1800 JPEGs, so posting makes them
 * smaller.
 */
fun postingTrip(clip: Boolean = false, today: LocalDate = SAMPLE_TODAY): PostingTrip {
    var clock = 1_795_000_000_000L
    var n = 0
    val ids = { "post-${n++}" }
    val book = TripBook(MemoryTripFile(), now = { clock++ }, ids = ids)
    val kit = MediaKit(MemoryMediaFiles(), frames = PostingFrames)
    val start = today.minusDays(20)
    val trip = book.newTrip("Paros and Naxos", start, start.plusDays(4), subtitle = "Paros → Naxos")
    fun place(day: Int, title: String, kind: PlanKind, lat: Double, lng: Double, minutes: Int?, pos: Double) =
        book.saveItem(book.draftItem(trip.id, start.plusDays(day.toLong()), pos).copy(title = title, kind = kind.id, lat = lat, lng = lng, placeName = title, timeMinutes = minutes))
    place(0, "Ferry from Piraeus", PlanKind.Transport, 37.9420, 23.6465, 7 * 60 + 30, 1.0)
    place(0, "Parikia harbour", PlanKind.Place, 37.0853, 25.1500, 13 * 60, 2.0)
    place(1, "Dinner in Naoussa", PlanKind.Food, 37.1241, 25.2367, 20 * 60 + 30, 1.0)
    place(2, "Ferry to Naxos", PlanKind.Transport, 37.1066, 25.3762, 10 * 60, 1.0)
    place(2, "Portara at sunset", PlanKind.Activity, 37.1109, 25.3719, 19 * 60 + 45, 2.0)
    place(3, "Mount Zas", PlanKind.Activity, 37.0435, 25.4920, 8 * 60, 1.0)
    book.addItem(trip.id, "Kitron tasting in Halki", null)
    // Been there: the planned places are ticked (the route passed them), so the trip's countries are where it went.
    book.items(trip.id).filter { it.day != null }.forEach { book.setDone(it.id, true) }
    val zone = "Europe/Athens"
    val first = book.writeNote(trip.id, "Windy crossing, then the whole harbour smelled of grilled octopus.", title = "Paros", at = startOfDayMs(start, zone) + 15 * 3_600_000L, zoneId = zone)!!
    val second = book.writeNote(trip.id, "The marble doorway at sunset, and half the island came to watch.", title = "The Portara", at = startOfDayMs(start.plusDays(2), zone) + 21 * 3_600_000L, zoneId = zone)!!
    addSamplePhoto(book, kit, samplePhoto(Scene.Town, 2400, 1800), trip.id, first.id, ids)
    addSamplePhoto(book, kit, samplePhoto(Scene.Sea, 2400, 1800), trip.id, first.id, ids)
    addSamplePhoto(book, kit, samplePhoto(Scene.Sunset, 2400, 1800), trip.id, second.id, ids)
    addSamplePhoto(book, kit, samplePhoto(Scene.Olive, 2400, 1800), trip.id, second.id, ids)
    addSamplePhoto(book, kit, samplePhoto(Scene.Sea, 1800, 2400), trip.id, null, ids)
    val clipId = if (!clip) null else {
        val id = "1795000000000-naxos"
        // A clip's first bytes as an MP4 starts ("ftyp"); its picture; its listing written last, as SavedClips keeps one.
        kit.files.write(SavedClipIndex.video(id), byteArrayOf(0, 0, 0, 24) + "ftypmp42".encodeToByteArray() + ByteArray(4096))
        kit.files.write(SavedClipIndex.thumb(id), samplePhoto(Scene.Sunset, 480, 270))
        kit.files.write(SavedClipIndex.meta(id), SavedClipIndex.encode(SavedClip(id, trip.id, trip.title, 1_795_000_000_000L, 42_000, 1080, 1920)).encodeToByteArray())
        id
    }
    return PostingTrip(book, kit, trip.id, clipId)
}
